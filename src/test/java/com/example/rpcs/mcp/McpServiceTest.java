package com.example.rpcs.mcp;

import com.example.rpcs.config.McpProperties;
import com.example.rpcs.mcp.model.ContentBlock;
import com.example.rpcs.mcp.model.JsonRpcError;
import com.example.rpcs.mcp.model.JsonRpcResponse;
import com.example.rpcs.mcp.model.ToolCallResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpServiceTest {

    private static final McpProperties PROPS = new McpProperties(
            "http://localhost:4040/mcp", "http://localhost:4040", "token", 5, 5, 42, 1, 2, 1);

    @Mock
    McpClient mcpClient;

    private static JsonRpcResponse ok(String text) {
        return new JsonRpcResponse("2.0", 1L, null,
                new ToolCallResult(List.of(new ContentBlock("text", text)), false));
    }

    private static CompletableFuture<JsonRpcResponse> done(JsonRpcResponse response) {
        return CompletableFuture.completedFuture(response);
    }

    /** Пустой список ядер: усыновлять нечего — пул создаёт своё. */
    private static CompletableFuture<List<McpClient.KernelSummary>> noKernels() {
        return CompletableFuture.completedFuture(List.of());
    }

    @Test
    void execute_passesArgumentsAndExtractsOutput() {
        var service = new McpService(PROPS, mcpClient);
        when(mcpClient.callTool(eq("execute_code"), any())).thenReturn(done(ok("4\n")));

        assertThat(service.execute("print(2 + 2)", null)).isEqualTo("4\n");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mcpClient).callTool(eq("execute_code"), captor.capture());
        Map<String, Object> args = captor.getValue();
        assertThat(args).containsEntry("code", "print(2 + 2)").containsEntry("timeout", 42);
        assertThat(args).doesNotContainKey("kernel_id");
    }

    @Test
    void execute_reportsJsonRpcError() {
        var service = new McpService(PROPS, mcpClient);
        var response = new JsonRpcResponse("2.0", 1L, new JsonRpcError(-32000, "boom", null), null);
        when(mcpClient.callTool(anyString(), any())).thenReturn(done(response));

        assertThatThrownBy(() -> service.execute("print(1)", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void execute_reportsIsError() {
        var service = new McpService(PROPS, mcpClient);
        var response = new JsonRpcResponse("2.0", 1L, null,
                new ToolCallResult(List.of(new ContentBlock("text", "boom")), true));
        when(mcpClient.callTool(anyString(), any())).thenReturn(done(response));

        assertThatThrownBy(() -> service.execute("print(1)", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ошибка инструмента");
    }

    @Test
    void execute_unwrapsClientFailure() {
        var service = new McpService(PROPS, mcpClient);
        when(mcpClient.callTool(anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("transport down")));

        assertThatThrownBy(() -> service.execute("print(1)", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("transport down");
    }

    @Test
    void executeInKernel_warmsPoolAndReusesKernel() {
        var service = new McpService(PROPS, mcpClient);
        when(mcpClient.listKernels()).thenReturn(noKernels()); // усыновлять нечего
        when(mcpClient.createKernel("python3")).thenReturn(CompletableFuture.completedFuture("k-1"));
        // последовательность вызовов: прогрев k-1, запуск 1, запуск 2 (ядро из пула)
        when(mcpClient.callTool(eq("execute_code"), any()))
                .thenReturn(done(ok("1")), done(ok("r1")), done(ok("r2")));

        assertThat(service.executeInKernel("print(1)", "python3")).isEqualTo("r1");
        assertThat(service.executeInKernel("print(2)", "python3")).isEqualTo("r2");

        verify(mcpClient, times(1)).listKernels();       // попытка усыновления — один раз на жизнь пула
        verify(mcpClient, times(1)).createKernel("python3");
        verify(mcpClient, times(3)).callTool(eq("execute_code"), any());
    }

    @Test
    void executeInKernel_adoptsKernelWarmedByContainer() {
        var service = new McpService(PROPS, mcpClient);
        // docker-entrypoint.sh уже создал и прогрел ядро — сервис усыновляет его,
        // не создаёт своё и не прогревает повторно (один callTool вместо двух)
        when(mcpClient.listKernels()).thenReturn(CompletableFuture.completedFuture(
                List.of(new McpClient.KernelSummary("warm-1", "python3"))));
        when(mcpClient.callTool(eq("execute_code"), any())).thenReturn(done(ok("r1")));

        assertThat(service.executeInKernel("print(1)", "python3")).isEqualTo("r1");

        verify(mcpClient, never()).createKernel(anyString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mcpClient).callTool(eq("execute_code"), captor.capture());
        assertThat(captor.getValue()).containsEntry("kernel_id", "warm-1");
    }

    @Test
    void executeInKernel_growsPoolOnDemand() {
        var props = new McpProperties(PROPS.url(), PROPS.apiUrl(), "token", 5, 5, 42, 0, 2, 1);
        var service = new McpService(props, mcpClient);
        when(mcpClient.listKernels()).thenReturn(noKernels());
        when(mcpClient.createKernel("python3")).thenReturn(CompletableFuture.completedFuture("k-1"));
        when(mcpClient.callTool(eq("execute_code"), any()))
                .thenReturn(done(ok("warm")), done(ok("r1")));

        assertThat(service.executeInKernel("print(1)", "python3")).isEqualTo("r1");
        verify(mcpClient, times(1)).createKernel("python3");
    }

    @Test
    void executeInKernel_creationFailurePropagates() {
        var service = new McpService(PROPS, mcpClient);
        when(mcpClient.listKernels()).thenReturn(noKernels());
        when(mcpClient.createKernel("python3"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException(
                        "Не удалось создать ядро \"python3\": HTTP 404")));

        assertThatThrownBy(() -> service.executeInKernel("print(1)", "python3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Не удалось создать ядро");
        verify(mcpClient, never()).callTool(anyString(), any());
    }

    @Test
    void executeInKernel_preWarmFailureIsRetried() {
        var service = new McpService(PROPS, mcpClient);
        when(mcpClient.listKernels()).thenReturn(noKernels());
        // прогрев пула падает — слот откатывается; при реальном запуске создание повторяется успешно
        when(mcpClient.createKernel("python3"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("transient")))
                .thenReturn(CompletableFuture.completedFuture("k-1"));
        when(mcpClient.callTool(eq("execute_code"), any()))
                .thenReturn(done(ok("warm")), done(ok("r1")));

        assertThat(service.executeInKernel("print(1)", "python3")).isEqualTo("r1");
        verify(mcpClient, times(2)).createKernel("python3");
    }
}
