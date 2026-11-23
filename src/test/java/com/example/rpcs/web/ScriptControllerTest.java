package com.example.rpcs.web;

import com.example.rpcs.config.ScriptProperties;
import com.example.rpcs.mcp.McpService;
import com.example.rpcs.scripts.MarkdownScriptParser;
import com.example.rpcs.scripts.ScriptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScriptControllerTest {

    @TempDir
    Path dir;

    MockMvc mockMvc;
    McpService mcpService;

    @BeforeEach
    void setUp() {
        mcpService = Mockito.mock(McpService.class);
        ScriptService scriptService = new ScriptService(new ScriptProperties(dir.toString()),
                new MarkdownScriptParser(), mcpService, JsonMapper.builder().build());
        mockMvc = MockMvcBuilders.standaloneSetup(new ScriptController(scriptService)).build();
    }

    private void writeScript(String id, String language, String body) throws IOException {
        Files.writeString(dir.resolve(id + ".md"),
                "---\nid: " + id + "\nlanguage: " + language + "\n---\n\n```" + language + "\n" + body + "\n```\n");
    }

    @Test
    void runWithoutBody_returnsPlainText() throws Exception {
        writeScript("demo", "python", "print(1)");
        when(mcpService.executeInKernel(anyString(), anyString())).thenReturn("hello out");

        mockMvc.perform(post("/api/scripts/demo/run"))
                .andExpect(status().isOk())
                .andExpect(content().string("hello out"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN));
    }

    @Test
    void runWithJsonBody_returnsJsonResponse() throws Exception {
        writeScript("tpl", "python", "__PAYLOAD_B64__");
        when(mcpService.executeInKernel(anyString(), anyString())).thenReturn("{\"line\": 1}");

        mockMvc.perform(post("/api/scripts/tpl/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\": [\"a\"]}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"line\": 1}"));
    }

    @Test
    void run_payloadToPlainScript_isExecutedAnyway() throws Exception {
        // плейсхолдеров больше нет: payload доступен как переменная payload,
        // скрипт, который его не использует, просто игнорирует тело запроса
        writeScript("demo", "python", "print(1)");
        when(mcpService.executeInKernel(anyString(), anyString())).thenReturn("hello out");

        mockMvc.perform(post("/api/scripts/demo/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\": [\"a\"]}"))
                .andExpect(status().isOk())
                .andExpect(content().string("hello out"));
    }

    @Test
    void run_unknownScript_returns404() throws Exception {
        // кириллицу в standalone-тесте не проверяем (StringHttpMessageConverter без UTF-8),
        // реальный сервер отдаёт UTF-8 — проверяем статус и идентификатор
        mockMvc.perform(post("/api/scripts/unknown/run"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("unknown")));
    }
}
