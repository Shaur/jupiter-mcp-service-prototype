package com.example.rpcs.scripts;

import com.example.rpcs.config.ScriptProperties;
import com.example.rpcs.mcp.McpService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.NoSuchElementException;

@Service
public class ScriptService {

    /** Метка в шаблоне скрипта, на место которой подставляется base64 от JSON-payload. */
    public static final String PAYLOAD_PLACEHOLDER = "__PAYLOAD_B64__";

    private final ScriptProperties props;
    private final MarkdownScriptParser parser;
    private final McpService mcpService;
    private final ObjectMapper mapper;

    public ScriptService(ScriptProperties props, MarkdownScriptParser parser, McpService mcpService,
                         ObjectMapper mapper) {
        this.props = props;
        this.parser = parser;
        this.mcpService = mcpService;
        this.mapper = mapper;
    }

    /** Ищет scripts/<id>.md, разбирает и исполняет блок кода в подходящем ядре Jupyter. */
    public String run(String id) {
        ScriptDefinition script = parser.parse(resolve(id), id);
        return mcpService.executeInKernel(script.code(), script.language().kernelName());
    }

    /**
     * Запускает скрипт-шаблон, подставив payload вместо {@link #PAYLOAD_PLACEHOLDER}
     * (base64-кодирование, чтобы содержимое не сломало python/kotlin-код).
     * Контракт шаблонного скрипта — JSON-ответ последней строкой вывода; служебные строки
     * kernel'а (например, лог подгрузки библиотек через %use) отсекаются.
     */
    public String runWithPayload(String scriptId, Object payload) {
        ScriptDefinition script = parser.parse(resolve(scriptId), scriptId);
        if (!script.code().contains(PAYLOAD_PLACEHOLDER)) {
            throw new IllegalArgumentException("Скрипт " + scriptId + " не содержит " + PAYLOAD_PLACEHOLDER);
        }
        String base64Payload = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(payload));
        String output = mcpService.executeInKernel(script.code().replace(PAYLOAD_PLACEHOLDER, base64Payload),
                script.language().kernelName());
        return extractLastJsonLine(output);
    }

    private String extractLastJsonLine(String output) {
        String stripped = output == null ? "" : output.strip();
        String[] lines = stripped.isEmpty() ? new String[0] : stripped.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String candidate = lines[i].strip();
            if (candidate.startsWith("{") && candidate.endsWith("}")) {
                return candidate;
            }
        }
        return stripped;
    }

    private Path resolve(String id) {
        Path dir = Path.of(props.dir()).toAbsolutePath().normalize();
        Path file = dir.resolve(id + ".md").normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            throw new NoSuchElementException("Скрипт не найден: " + id);
        }
        return file;
    }
}
