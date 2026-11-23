package com.example.rpcs.mcp;

import com.example.rpcs.config.McpProperties;
import com.example.rpcs.mcp.model.ContentBlock;
import com.example.rpcs.mcp.model.JsonRpcResponse;
import com.example.rpcs.mcp.model.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class McpService {

    private static final Logger log = LoggerFactory.getLogger(McpService.class);

    private static final String TOOL_NAME = "execute_code";

    private final McpProperties props;
    private final McpClient mcpClient;
    /** Прогретые (свободные) ядра по имени kernelspec. */
    private final Map<String, BlockingQueue<String>> idleKernels = new ConcurrentHashMap<>();
    /** Сколько ядер каждого kernelspec создано — чтобы пул не рос бесконечно. */
    private final Map<String, AtomicInteger> createdKernels = new ConcurrentHashMap<>();
    /** Языки, для которых усыновление уже пытались выполнить (одна попытка на жизнь пула). */
    private final Set<String> adoptionChecked = ConcurrentHashMap.newKeySet();

    public McpService(McpProperties props, McpClient mcpClient) {
        this.props = props;
        this.mcpClient = mcpClient;
    }

    /**
     * Исполняет код в Jupyter MCP. kernelId (если задан) направляет код в конкретное ядро.
     * Синхронная обёртка над {@link McpClient}: контроллеры остаются servlet-совместимыми,
     * при переходе на WebFlux можно вернуть CompletableFuture без изменения клиента.
     */
    public String execute(String code, String kernelId) {
        return join(executeAsync(code, kernelId));
    }

    private CompletableFuture<String> executeAsync(String code, String kernelId) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("code", code);
        arguments.put("timeout", props.executionTimeoutSeconds());
        if (kernelId != null && !kernelId.isBlank()) {
            arguments.put("kernel_id", kernelId);
        }
        return mcpClient.callTool(TOOL_NAME, arguments).thenApply(this::extractOutput);
    }

    /**
     * Исполняет код в ядре из пула данного kernelspec. Пул лениво прогревается до
     * kernel-pool-min-size, под нагрузкой растёт до kernel-pool-max-size и не превышает его:
     * когда все ядра заняты, запуск ждёт освобождения до kernel-acquire-timeout-seconds.
     * Состояние между запусками не сохраняется: следующий запуск может попасть в другое ядро пула.
     */
    public String executeInKernel(String code, String kernelName) {
        BlockingQueue<String> idle = poolFor(kernelName);
        AtomicInteger created = createdKernels.computeIfAbsent(kernelName, name -> new AtomicInteger());

        CompletableFuture<String> acquired = acquireKernel(idle, kernelName, created);
        return join(acquired.thenCompose(kernelId ->
                executeAsync(code, kernelId).whenComplete((result, error) -> idle.offer(kernelId))));
    }

    // ---------- Пул ядер ----------

    private BlockingQueue<String> poolFor(String kernelName) {
        return idleKernels.computeIfAbsent(kernelName, name -> {
            AtomicInteger created = new AtomicInteger();
            createdKernels.put(name, created);
            BlockingQueue<String> queue = new LinkedBlockingQueue<>();
            // ждём окончания прогрева, но не валим запрос, если прогреть не удалось:
            // запрос создаст/возьмёт ядро сам (ошибка прогрева уже залогирована)
            warmPool(queue, name, created).forEach(future -> {
                try {
                    future.join();
                } catch (RuntimeException ignored) {
                    // ignore
                }
            });
            return queue;
        });
    }

    private java.util.List<CompletableFuture<String>> warmPool(BlockingQueue<String> queue,
                                                               String kernelName, AtomicInteger created) {
        java.util.List<CompletableFuture<String>> warming = new java.util.ArrayList<>();
        for (int i = 0; i < props.kernelPoolMinSize(); i++) {
            warming.add(acquireKernel(queue, kernelName, created)
                    .whenComplete((kernelId, error) -> {
                        if (error != null) {
                            log.warn("Не удалось прогреть ядро \"{}\": {}", kernelName, error.getMessage());
                        } else {
                            queue.offer(kernelId);
                        }
                    }));
        }
        return warming;
    }

    private CompletableFuture<String> acquireKernel(BlockingQueue<String> idle,
                                                    String kernelName, AtomicInteger created) {
        String pooled = idle.poll();
        if (pooled != null) {
            return CompletableFuture.completedFuture(pooled);
        }
        synchronized (idle) {
            pooled = idle.poll(); // сосед мог вернуть/усыновить ядро, пока мы ждали лок
            if (pooled != null) {
                return CompletableFuture.completedFuture(pooled);
            }
            if (adoptionChecked.add(kernelName)) {
                // контейнер мог прогреть и оставить ядро при старте — усыновляем его,
                // иначе создаём своё; усыновлённое учитывается в лимите пула
                return mcpClient.listKernels()
                        .thenApply(kernels -> kernels.stream()
                                .filter(kernel -> kernelName.equals(kernel.name()))
                                .map(McpClient.KernelSummary::id)
                                .findFirst())
                        .thenCompose(existing -> {
                            if (existing.isPresent()) {
                                created.incrementAndGet();
                                log.info("Усыновлено прогретое ядро \"{}\": {}", kernelName, existing.get());
                                return CompletableFuture.completedFuture(existing.get());
                            }
                            return createKernel(idle, kernelName, created);
                        });
            }
            return createKernel(idle, kernelName, created);
        }
    }

    private CompletableFuture<String> createKernel(BlockingQueue<String> idle,
                                                   String kernelName, AtomicInteger created) {
        int current = created.get();
        if (current < props.kernelPoolMaxSize()) {
            // резервируем слот счётчиком и создаём новое ядро с прогревом;
            // при неудаче слот откатывается — попытку создания можно повторить
            created.incrementAndGet();
            return mcpClient.createKernel(kernelName)
                    .thenCompose(kernelId -> warmUp(kernelId).thenApply(v -> kernelId))
                    .whenComplete((kernelId, error) -> {
                        if (error != null) {
                            created.decrementAndGet();
                            log.warn("Не удалось создать ядро \"{}\": {}", kernelName, error.getMessage());
                        }
                    });
        }

        // пул исчерпан — ждём освобождения ядра
        try {
            String pooledAfterWait = idle.poll(props.kernelAcquireTimeoutSeconds(), TimeUnit.SECONDS);
            if (pooledAfterWait == null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Нет свободных ядер \"" + kernelName + "\" (пул исчерпан, лимит "
                                + props.kernelPoolMaxSize() + ")"));
            }
            return CompletableFuture.completedFuture(pooledAfterWait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(new IllegalStateException("Ожидание свободного ядра прервано", e));
        }
    }

    /**
     * Первый execute_code на свежесозданном ядре теряет stdout (гонка в jupyter-mcp-server 0.20.0),
     * поэтому после создания делаем холостой вызов; его вывод не нужен.
     */
    private CompletableFuture<Void> warmUp(String kernelId) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("code", "1");
        arguments.put("timeout", props.executionTimeoutSeconds());
        arguments.put("kernel_id", kernelId);
        return mcpClient.callTool(TOOL_NAME, arguments).handle((result, error) -> null);
    }

    // ---------- Разбор ответа ----------

    /** Проверки JSON-RPC-ответа и склейка текстовых content-блоков. */
    private String extractOutput(JsonRpcResponse resp) {
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
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : result.content()) {
            if ("text".equals(block.type())) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(block.text());
            } else {
                sb.append("\n[блок типа: ").append(block.type()).append(']');
            }
        }
        return sb.toString();
    }

    /** join с распаковкой CompletionException: наружу уходят исходные RuntimeException. */
    private <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }
}
