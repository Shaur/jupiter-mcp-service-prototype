package com.example.rpcs.mcp;

import com.example.rpcs.config.McpProperties;
import com.example.rpcs.mcp.model.ContentBlock;
import com.example.rpcs.mcp.model.JsonRpcRequest;
import com.example.rpcs.mcp.model.JsonRpcResponse;
import com.example.rpcs.mcp.model.ToolCallParams;
import com.example.rpcs.mcp.model.ToolCallResult;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class McpService {

    private static final Logger log = LoggerFactory.getLogger(McpService.class);

    private static final String TOOL_NAME = "execute_code";

    private final McpProperties props;
    private final ObjectMapper mapper;
    private final OkHttpClient http;
    /** Прогретые (свободные) ядра по имени kernelspec. */
    private final Map<String, BlockingQueue<String>> idleKernels = new ConcurrentHashMap<>();
    /** Сколько ядер каждого kernelspec создано — чтобы пул не рос бесконечно. */
    private final Map<String, AtomicInteger> createdKernels = new ConcurrentHashMap<>();

    public McpService(McpProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(props.connectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(props.readTimeoutSeconds(), TimeUnit.SECONDS) // запас на долгие вычисления
                .build();
    }

    /**
     * Исполняет код в Jupyter MCP. kernelId (если задан) направляет код в конкретное ядро.
     * Наш сервер не требует MCP-handshake (initialize + notifications/initialized),
     * поэтому шлём только tools/call; согласование версии протокола не выполняем
     * (сервер отвечает по 2024-11-05). При смене сервера handshake, возможно, придётся вернуть.
     */
    public String execute(String code, String kernelId) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("code", code);
        arguments.put("timeout", props.executionTimeoutSeconds());
        if (kernelId != null && !kernelId.isBlank()) {
            arguments.put("kernel_id", kernelId);
        }

        JsonRpcResponse resp = post(JsonRpcRequest.request(1, "tools/call",
                new ToolCallParams(TOOL_NAME, arguments)));

        if (resp.error() != null) {
            throw new IllegalStateException("Ошибка tools/call: " + resp.error());
        }
        ToolCallResult result = resp.result();
        if (result == null) {
            throw new IllegalStateException("tools/call вернул ответ без result: " + resp);
        }
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("Ошибка инструмента: " + result);
        }
        return extractText(result.content());
    }

    /**
     * Исполняет код в ядре из пула данного kernelspec. Пул лениво прогревается до
     * kernel-pool-min-size, под нагрузкой растёт до kernel-pool-max-size и не превышает его:
     * когда все ядра заняты, запуск ждёт освобождения до kernel-acquire-timeout-seconds.
     * Состояние между запусками не сохраняется: следующий запуск может попасть в другое ядро пула.
     */
    public String executeInKernel(String code, String kernelName) {
        BlockingQueue<String> idle = poolFor(kernelName);
        String kernelId = acquireKernel(idle, kernelName);
        try {
            return execute(code, kernelId);
        } finally {
            idle.offer(kernelId);
        }
    }

    /** Лениво создаёт пул ядра: kernel-pool-min-size прогретых ядер. */
    private BlockingQueue<String> poolFor(String kernelName) {
        return idleKernels.computeIfAbsent(kernelName, name -> {
            BlockingQueue<String> queue = new LinkedBlockingQueue<>();
            int warmed = 0;
            for (int i = 0; i < props.kernelPoolMinSize(); i++) {
                try {
                    queue.offer(createKernel(name));
                    warmed++;
                } catch (RuntimeException e) {
                    log.warn("Не удалось прогреть ядро {}: {}", name, e.getMessage());
                }
            }
            createdKernels.put(name, new AtomicInteger(warmed));
            return queue;
        });
    }

    private String acquireKernel(BlockingQueue<String> idle, String kernelName) {
        String kernelId = idle.poll();
        if (kernelId != null) {
            return kernelId;
        }
        AtomicInteger created = createdKernels.computeIfAbsent(kernelName, name -> new AtomicInteger());
        while (true) {
            int current = created.get();
            if (current >= props.kernelPoolMaxSize()) {
                try {
                    String pooled = idle.poll(props.kernelAcquireTimeoutSeconds(), TimeUnit.SECONDS);
                    if (pooled == null) {
                        throw new IllegalStateException("Нет свободных ядер \"" + kernelName
                                + "\" (пул исчерпан, лимит " + props.kernelPoolMaxSize() + ")");
                    }
                    return pooled;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Ожидание свободного ядра прервано", e);
                }
            }
            if (created.compareAndSet(current, current + 1)) {
                return createKernel(kernelName); // после запуска ядро вернётся в пул
            }
        }
    }

    /** Создаёт ядро и прогревает его холостым вызовом (первый execute_code теряет stdout). */
    private String createKernel(String kernelName) {
        if (props.apiUrl() == null || props.apiUrl().isBlank()) {
            throw new IllegalStateException("Не задан mcp.api-url — нужен для создания ядер Jupyter");
        }
        Request request = new Request.Builder()
                .url(props.apiUrl() + "/api/kernels")
                .post(RequestBody.create(mapper.writeValueAsString(Map.of("name", kernelName)),
                        MediaType.parse("application/json")))
                .header("Authorization", "Bearer " + props.token())
                .build();
        try (Response response = http.newCall(request).execute()) {
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
            execute("1", id);
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException("Ошибка обращения к Jupyter API", e);
        }
    }

    // ---------- Базовый вызов ----------

    private JsonRpcResponse post(JsonRpcRequest request) {
        Request httpRequest = new Request.Builder()
                .url(props.url())
                .post(RequestBody.create(mapper.writeValueAsString(request), MediaType.parse("application/json")))
                .header("Authorization", "Bearer " + props.token())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .build();

        try (Response response = http.newCall(httpRequest).execute()) {
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
        } catch (IOException e) {
            throw new UncheckedIOException("Ошибка обращения к Jupyter MCP", e);
        }
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

    /** Вытаскиваем текст из content-блоков (их может быть несколько). */
    private String extractText(List<ContentBlock> content) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            if ("text".equals(block.type())) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(block.text());
            } else {
                sb.append("\n[блок типа: ").append(block.type()).append(']');
            }
        }
        return sb.toString();
    }
}
