package com.example.rpcs.scripts;

import com.example.rpcs.config.ScriptProperties;
import com.example.rpcs.mcp.McpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScriptServiceTest {

    @TempDir
    Path dir;

    @Mock
    McpService mcpService;

    private ScriptService scriptService;

    @BeforeEach
    void setUp() {
        scriptService = new ScriptService(new ScriptProperties(dir.toString()), new MarkdownScriptParser(),
                mcpService, JsonMapper.builder().build());
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
    void run_passesCodeAndKernelName() throws IOException {
        writeScript("demo", "python", "print(1)");
        when(mcpService.executeInKernel(anyString(), anyString())).thenReturn("out");

        assertThat(scriptService.run("demo")).isEqualTo("out");
        verify(mcpService).executeInKernel("print(1)", "python3");
    }

    @Test
    void runWithPayload_injectsBase64Payload() throws IOException {
        writeScript("tpl", "python", "__PAYLOAD_B64__");
        when(mcpService.executeInKernel(anyString(), anyString())).thenReturn("{}");

        scriptService.runWithPayload("tpl", new LinesPayload(List.of("a")));

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mcpService).executeInKernel(code.capture(), anyString());
        String decoded = new String(Base64.getDecoder().decode(code.getValue()));
        assertThat(decoded).isEqualTo("{\"lines\":[\"a\"]}");
    }

    @Test
    void runWithPayload_requiresPlaceholder() throws IOException {
        writeScript("plain", "python", "print(1)");
        assertThatThrownBy(() -> scriptService.runWithPayload("plain", new LinesPayload(List.of("a"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("__PAYLOAD_B64__");
    }

    @Test
    void runWithPayload_returnsLastJsonLine() throws IOException {
        writeScript("tpl", "python", "__PAYLOAD_B64__");
        // kernel может примешать служебные строки (например, лог подгрузки библиотек через %use)
        when(mcpService.executeInKernel(anyString(), anyString()))
                .thenReturn("Resolving com.beust:klaxon...\n{\"line\": 2, \"message\": \"ok\"}");

        assertThat(scriptService.runWithPayload("tpl", new LinesPayload(List.of("a"))))
                .isEqualTo("{\"line\": 2, \"message\": \"ok\"}");
    }

    @Test
    void runWithPayload_keepsPlainOutputWhenNoJson() throws IOException {
        writeScript("tpl", "python", "__PAYLOAD_B64__");
        when(mcpService.executeInKernel(anyString(), anyString())).thenReturn("просто текст без json");

        assertThat(scriptService.runWithPayload("tpl", new LinesPayload(List.of("a"))))
                .isEqualTo("просто текст без json");
    }

    /** Аналог rule.model.LinesIdentification для проверки сериализации payload'а. */
    record LinesPayload(List<String> lines) {
    }
}
