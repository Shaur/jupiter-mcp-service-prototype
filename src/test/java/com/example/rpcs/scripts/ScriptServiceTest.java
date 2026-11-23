package com.example.rpcs.scripts;

import com.example.rpcs.config.ScriptProperties;
import com.example.rpcs.mcp.McpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScriptServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path dir;

    @Mock
    McpService mcpService;

    private ScriptService scriptService;

    @BeforeEach
    void setUp() {
        scriptService = new ScriptService(new ScriptProperties(dir.toString()), new MarkdownScriptParser(),
                mcpService, JSON);
    }

    private void writeScript(String id, String language, String body) throws IOException {
        Files.writeString(dir.resolve(id + ".md"),
                "---\nid: " + id + "\nlanguage: " + language + "\n---\n\n```" + language + "\n" + body + "\n```\n");
    }

    @Test
    void run_missingScript() {
        assertThatThrownBy(() -> scriptService.run("unknown"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void run_blocksPathTraversal() throws IOException {
        Files.writeString(dir.getParent().resolve("outside.md"), "секрет");
        assertThatThrownBy(() -> scriptService.run("../outside"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("../outside");
    }

    @Test
    void run_composesPreludeAndPassesKernelName() throws IOException {
        writeScript("demo", "python", "print(1)");
        when(mcpService.executeInKernel(anyString(), anyString())).thenReturn("out");

        assertThat(scriptService.run("demo")).isEqualTo("out");

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mcpService).executeInKernel(code.capture(), eq("python3"));
        assertThat(code.getValue())
                .contains("class Report")                       // прелюдия
                .contains("payload = load_payload(None)")       // payload по умолчанию
                .contains("print(1)");                          // тело скрипта
    }

    @Test
    void runWithPayload_bindsPayloadAsBase64() throws IOException {
        writeScript("tpl", "python", "report.invalid(\"дубль\")\n\nreport");
        when(mcpService.executeInKernel(anyString(), anyString()))
                .thenReturn("{\"valid\": false, \"message\": \"дубль\"}");

        String output = scriptService.runWithPayload("tpl", JSON.readTree("{\"lines\": [\"a\"]}"));

        assertThat(output).isEqualTo("{\"valid\": false, \"message\": \"дубль\"}");
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mcpService).executeInKernel(code.capture(), eq("python3"));
        String expectedB64 = Base64.getEncoder().encodeToString(
                JSON.writeValueAsBytes(JSON.readTree("{\"lines\": [\"a\"]}")));
        assertThat(code.getValue())
                .contains("payload = load_payload(\"" + expectedB64 + "\")")
                .contains("report.invalid(\"дубль\")");
    }

    @Test
    void runWithPayload_kotlinBindsViaKlaxon() throws IOException {
        writeScript("ktpl", "kotlin", "report");
        when(mcpService.executeInKernel(anyString(), anyString())).thenReturn("{\"valid\": true}");

        scriptService.runWithPayload("ktpl", JSON.readTree("{\"lines\": [\"a\", \"b\"]}"));

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mcpService).executeInKernel(code.capture(), eq("kotlin"));
        assertThat(code.getValue())
                .contains("%use klaxon")
                .contains("payload = decode_payload(");
    }

    @Test
    void runWithPayload_returnsLastJsonLineSkippingKernelChatter() throws IOException {
        writeScript("tpl", "python", "report");
        when(mcpService.executeInKernel(anyString(), anyString()))
                .thenReturn("Resolving com.beust:klaxon...\n{\"valid\": false, \"message\": \"ok\"}");

        assertThat(scriptService.runWithPayload("tpl", JSON.readTree("{\"lines\": []}")))
                .isEqualTo("{\"valid\": false, \"message\": \"ok\"}");
    }

    @Test
    void run_plainScriptKeepsRawOutput() throws IOException {
        writeScript("demo", "python", "print(1)");
        when(mcpService.executeInKernel(anyString(), anyString())).thenReturn("текст {не json}\nещё");

        assertThat(scriptService.run("demo")).isEqualTo("текст {не json}\nещё");
    }
}
