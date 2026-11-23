package com.example.rpcs.mcp;

import com.example.rpcs.config.McpProperties;
import com.example.rpcs.mcp.model.JsonRpcRequest;
import com.example.rpcs.mcp.model.JsonRpcResponse;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Низкоуровневый асинхронный клиент MCP-протокола и REST API ядер Jupyter.
 * Все методы возвращают CompletableFuture (исключения доставляются через
 * completeExceptionally) — совместимо с WebFlux, если он понадобится.
 * Транспорт — OkHttp: асинхронные enqueue, без блокировки потоков.
 */
@Component
public class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);

    private final McpProperties props;
    private final ObjectMapper mapper;
    private final OkHttpClient http;

    public McpClient(McpProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(props.connectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(props.readTimeoutSeconds(), TimeUnit.SECONDS) // запас на долгие вычисления
                .build();
    }

    /** tools/call: исполняет инструмент и возвращает сырой JSON-RPC-ответ. */
    public CompletableFuture<JsonRpcResponse> callTool(String toolName, Map<String, Object> arguments) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", mapper.valueToTree(arguments));
        return post(JsonRpcRequest.request(1, "tools/call", params));
    }

    /** POST /api/kernels: создаёт ядро и возвращает его id. */
    public CompletableFuture<String> createKernel(String kernelName) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", kernelName);

        CompletableFuture<String> future = new CompletableFuture<>();
        send(kernelRequest("POST", props.apiUrl() + "/api/kernels", body), future, response -> {
            ResponseBody respBody = response.body();
            if (respBody == null) {
                throw new IllegalStateException("Создание ядра " + kernelName + ": пустое тело ответа");
            }
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Не удалось создать ядро \"" + kernelName + "\": HTTP "
                        + response.code() + ": " + respBody.string());
            }
            String id = mapper.readTree(respBody.string()).path("id").asString("");
            if (id.isBlank()) {
                throw new IllegalStateException("В ответе на создание ядра нет id");
            }
            return id;
        });
        return future;
    }

    /** GET /api/kernels: список запущенных ядер (id и имя kernelspec). */
    public CompletableFuture<List<KernelSummary>> listKernels() {
        CompletableFuture<List<KernelSummary>> future = new CompletableFuture<>();
        Request request = new Request.Builder()
                .url(props.apiUrl() + "/api/kernels")
                .get()
                .header("Authorization", "Bearer " + props.token())
                .build();
        send(request, future, response -> {
            ResponseBody respBody = response.body();
            if (respBody == null) {
                throw new IllegalStateException("Список ядер: пустое тело ответа");
            }
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Не удалось получить список ядер: HTTP " + response.code());
            }
            List<KernelSummary> kernels = new ArrayList<>();
            for (JsonNode kernel : mapper.readTree(respBody.string())) {
                String id = kernel.path("id").asString("");
                String name = kernel.path("name").asString("");
                if (!id.isBlank() && !name.isBlank()) {
                    kernels.add(new KernelSummary(id, name));
                }
            }
            return kernels;
        });
        return future;
    }

    public record KernelSummary(String id, String name) {
    }

    /** DELETE /api/kernels/{id}: удаляет ядро. Best-effort: никогда не завершается исключением. */
    public CompletableFuture<Void> deleteKernel(String kernelId) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        send(kernelRequest("DELETE", props.apiUrl() + "/api/kernels/" + kernelId, null),
                new CompletableFuture<>(), response -> {
                    if (!response.isSuccessful()) {
                        log.warn("Не удалось удалить ядро {}: HTTP {}", kernelId, response.code());
                    }
                    return null;
                }).whenComplete((v, e) -> {
                    if (e != null) {
                        log.warn("Не удалось удалить ядро {}: {}", kernelId, e.getMessage());
                    }
                    result.complete(null);
                });
        return result;
    }

    // ---------- Транспорт ----------

    @FunctionalInterface
    private interface ResponseHandler<T> {
        T handle(Response response) throws IOException;
    }

    private <T> CompletableFuture<T> send(Request request, CompletableFuture<T> future, ResponseHandler<T> handler) {
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(new UncheckedIOException("Ошибка обращения к Jupyter", e));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (response) {
                    future.complete(handler.handle(response));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        });
        return future;
    }

    private CompletableFuture<JsonRpcResponse> post(JsonRpcRequest request) {
        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        Request httpRequest = new Request.Builder()
                .url(props.url())
                .post(RequestBody.create(mapper.writeValueAsString(request), MediaType.parse("application/json")))
                .header("Authorization", "Bearer " + props.token())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .build();
        send(httpRequest, future, this::parseMcpResponse);
        return future;
    }

    private Request kernelRequest(String method, String url, ObjectNode body) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .method(method, body == null
                        ? null
                        : RequestBody.create(mapper.writeValueAsString(body), MediaType.parse("application/json")))
                .header("Authorization", "Bearer " + props.token());
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return builder.build();
    }

    private JsonRpcResponse parseMcpResponse(Response response) throws IOException {
        ResponseBody respBody = response.body();
        if (respBody == null) {
            throw new IllegalStateException("HTTP " + response.code() + ": пустое тело ответа");
        }
        if (!response.isSuccessful()) {
            throw new IllegalStateException("HTTP " + response.code() + ": " + respBody.string());
        }

        String contentType = response.header("Content-Type", "");
        if (contentType.contains("text/event-stream")) {
            return parseSse(new BufferedReader(respBody.charStream()));
        }
        // сервер может ответить обычным JSON (или пустым телом на уведомления) — обрабатываем оба варианта
        String text = respBody.string();
        return text.isBlank() ? JsonRpcResponse.empty() : mapper.readValue(text, JsonRpcResponse.class);
    }

    /**
     * Разбор SSE-потока: достаём первый message-кадр с ответом.
     * Пустой SSE-поток (так сервер отвечает на уведомления) — не ошибка, возвращаем пустой объект.
     */
    private JsonRpcResponse parseSse(BufferedReader reader) throws IOException {
        String line;
        StringBuilder data = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("data:")) {
                data.append(line.substring(5).trim());
                // возможны многострочные data-блоки — читаем до пустой строки
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.startsWith("data:")) {
                        data.append("\n").append(line.substring(5).trim());
                    }
                }
                break;
            }
        }
        if (data.isEmpty()) {
            return JsonRpcResponse.empty();
        }
        return mapper.readValue(data.toString(), JsonRpcResponse.class);
    }
}
