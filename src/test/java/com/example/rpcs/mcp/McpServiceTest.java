package com.example.rpcs.mcp;

import com.example.rpcs.config.McpProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpServiceTest {

    private static final String TOOLS_CALL_OK = """
            {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"4\\n"}]}}
            """;

    private MockWebServer server;
    private McpService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        service = service(1, 2, 1);
    }

    private McpService service(int poolMin, int poolMax, int acquireTimeoutSeconds) {
        String url = server.url("/mcp").toString();
        String base = url.replace("/mcp", "");
        return new McpService(new McpProperties(url, base, "token", 5, 5, 42,
                poolMin, poolMax, acquireTimeoutSeconds), JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static MockResponse toolsCall(String text) {
        return jsonResponse("""
                {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":%s}]}}
                """.formatted(JsonMapper.builder().build().writeValueAsString(text)));
    }

    @Test
    void execute_postsToolsCallAndExtractsText() throws Exception {
        server.enqueue(jsonResponse(TOOLS_CALL_OK));

        assertThat(service.execute("print(2 + 2)", null)).isEqualTo("4\n");

        var request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/mcp");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer token");
        assertThat(request.getHeader("Accept")).contains("text/event-stream");
        assertThat(request.getHeader("Mcp-Session-Id")).isNull();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"tools/call\"").contains("print(2 + 2)").contains("\"timeout\":42");
        assertThat(body).doesNotContain("kernel_id");
    }

    @Test
    void execute_passesKernelId() throws Exception {
        server.enqueue(jsonResponse(TOOLS_CALL_OK));

        service.execute("print(1)", "k-1");

        assertThat(server.takeRequest().getBody().readUtf8())
                .contains("\"kernel_id\":\"k-1\"").contains("\"timeout\":42");
    }

    @Test
    void execute_reportsJsonRpcError() {
        server.enqueue(jsonResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"boom\"}}"));

        assertThatThrownBy(() -> service.execute("print(1)", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void execute_reportsIsError() {
        server.enqueue(jsonResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"boom\"}]}}"));

        assertThatThrownBy(() -> service.execute("print(1)", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ошибка инструмента");
    }

    @Test
    void execute_parsesSseResponse() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: " + TOOLS_CALL_OK.strip() + "\n\n"));

        assertThat(service.execute("print(2 + 2)", null)).isEqualTo("4\n");
    }

    @Test
    void execute_failsOnEmptyBody() {
        server.enqueue(new MockResponse().setResponseCode(200));

        assertThatThrownBy(() -> service.execute("print(1)", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("без result");
    }

    @Test
    void executeInKernel_warmsPoolAndReusesKernel() throws Exception {
        server.enqueue(jsonResponse("{\"id\":\"k-1\",\"name\":\"python3\"}")); // прогрев пула (min=1)
        server.enqueue(toolsCall("warm"));                                     // прогревочный вызов
        server.enqueue(toolsCall("r1"));                                       // запуск 1
        server.enqueue(toolsCall("r2"));                                       // запуск 2 (то же ядро)

        assertThat(service.executeInKernel("print(1)", "python3")).isEqualTo("r1");
        assertThat(service.executeInKernel("print(2)", "python3")).isEqualTo("r2");

        assertThat(server.getRequestCount()).isEqualTo(4);
        assertThat(server.takeRequest().getPath()).isEqualTo("/api/kernels");
        assertThat(server.takeRequest().getBody().readUtf8()).contains("\"timeout\":42");
        assertThat(server.takeRequest().getPath()).isEqualTo("/mcp");
        assertThat(server.takeRequest().getPath()).isEqualTo("/mcp");
    }

    @Test
    void executeInKernel_growsPoolOnDemand() throws Exception {
        var growing = service(0, 2, 1);

        server.enqueue(jsonResponse("{\"id\":\"k-1\",\"name\":\"python3\"}")); // создание (min=0, idle пуст)
        server.enqueue(toolsCall("warm"));                                     // прогрев созданного ядра
        server.enqueue(toolsCall("r1"));                                       // запуск 1
        server.enqueue(toolsCall("r2"));                                       // запуск 2 переиспользует

        assertThat(growing.executeInKernel("print(1)", "python3")).isEqualTo("r1");
        assertThat(growing.executeInKernel("print(1)", "python3")).isEqualTo("r2");

        assertThat(server.getRequestCount()).isEqualTo(4);
        assertThat(server.takeRequest().getPath()).isEqualTo("/api/kernels");
        assertThat(server.takeRequest().getPath()).isEqualTo("/mcp");
        assertThat(server.takeRequest().getPath()).isEqualTo("/mcp");
        assertThat(server.takeRequest().getPath()).isEqualTo("/mcp");
    }

    @Test
    void executeInKernel_failsWhenKernelCannotBeCreated() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("no such kernelspec"));
        server.enqueue(new MockResponse().setResponseCode(404).setBody("no such kernelspec"));

        assertThatThrownBy(() -> service.executeInKernel("print(1)", "python3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Не удалось создать ядро");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }
}
