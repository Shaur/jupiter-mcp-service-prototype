package com.example.rpcs.mcp;

import com.example.rpcs.config.McpProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpClientTest {

    private MockWebServer server;
    private McpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String url = server.url("/mcp").toString();
        String base = url.replace("/mcp", "");
        client = new McpClient(new McpProperties(url, base, "token", 5, 5, 42, 1, 2, 1),
                JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static final String TOOLS_CALL_OK = """
            {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"4\\n"}]}}
            """;

    @Test
    void callTool_postsRequestAndParsesResponse() throws Exception {
        server.enqueue(jsonResponse(TOOLS_CALL_OK));

        var response = client.callTool("execute_code", Map.of("code", "print(2 + 2)", "timeout", 42))
                .get(5, TimeUnit.SECONDS);

        assertThat(response.error()).isNull();
        assertThat(response.result().content().getFirst().text()).isEqualTo("4\n");

        var request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/mcp");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer token");
        assertThat(request.getHeader("Accept")).contains("text/event-stream");
        assertThat(request.getHeader("Mcp-Session-Id")).isNull();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"tools/call\"").contains("print(2 + 2)").contains("\"timeout\":42");
    }

    @Test
    void callTool_parsesSseResponse() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: " + TOOLS_CALL_OK.strip() + "\n\n"));

        var response = client.callTool("execute_code", Map.of("code", "print(2 + 2)")).get(5, TimeUnit.SECONDS);
        assertThat(response.result().content().getFirst().text()).isEqualTo("4\n");
    }

    @Test
    void callTool_failsOnTransportError() throws Exception {
        var dead = new McpClient(new McpProperties("http://127.0.0.1:1/mcp", "http://127.0.0.1:1", "token", 1, 1, 42, 1, 2, 1),
                JsonMapper.builder().build());

        assertThatThrownBy(() -> dead.callTool("execute_code", Map.of("code", "print(1)")).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void createKernel_returnsId() throws Exception {
        server.enqueue(jsonResponse("{\"id\":\"k-1\",\"name\":\"python3\"}"));

        assertThat(client.createKernel("python3").get(5, TimeUnit.SECONDS)).isEqualTo("k-1");

        var request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/kernels");
        assertThat(request.getBody().readUtf8()).contains("\"name\":\"python3\"");
    }

    @Test
    void createKernel_failsOnHttpError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("no such kernelspec"));

        assertThatThrownBy(() -> client.createKernel("python3").get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Не удалось создать ядро");
    }

    @Test
    void deleteKernel_isBestEffort() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));

        assertThat(client.deleteKernel("k-1").get(5, TimeUnit.SECONDS)).isNull();

        var request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("DELETE");
        assertThat(request.getPath()).isEqualTo("/api/kernels/k-1");
    }
}
