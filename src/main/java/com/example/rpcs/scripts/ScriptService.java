package com.example.rpcs.scripts;

import com.example.rpcs.config.ScriptProperties;
import com.example.rpcs.mcp.McpService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class ScriptService {


    private final ScriptProperties props;
    private final MarkdownScriptParser parser;
    private final McpService mcpService;
    private final ObjectMapper mapper;
    /** Runtime-прелюдии по языкам: Report/load_payload/payload и хелперы, доступные автору скрипта. */
    private final Map<ScriptLanguage, String> preludes;

    public ScriptService(ScriptProperties props, MarkdownScriptParser parser, McpService mcpService,
                         ObjectMapper mapper) {
        this.props = props;
        this.parser = parser;
        this.mcpService = mcpService;
        this.mapper = mapper;
        this.preludes = Map.of(
                ScriptLanguage.PYTHON, loadPrelude("/runtime/python-prelude.py"),
                ScriptLanguage.KOTLIN, loadPrelude("/runtime/kotlin-prelude.kts"));
    }

    /** Ищет scripts/<id>.md, разбирает и исполняет блок кода в подходящем ядре Jupyter. */
    public String run(String id) {
        return runInternal(id, null);
    }

    /**
     * Запускает скрипт-правило с payload (JSON-тело запроса). Payload доступен в скрипте
     * как готовая переменная `payload`; контракт ответа — JSON-строка `{"valid", "message"}`.
     */
    public String runWithPayload(String id, JsonNode payload) {
        return runInternal(id, payload);
    }

    private String runInternal(String id, JsonNode payload) {
        ScriptDefinition script = parser.parse(resolve(id), id);
        String payloadB64 = payload == null
                ? null
                : Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(payload));
        String code = compose(script.language(), payloadB64, script.code());
        String output = mcpService.executeInKernel(code, script.language().kernelName());
        return payload == null ? output : extractLastJsonLine(output);
    }

    /**
     * Собирает исполняемый код: прелюдия языка + привязка payload + тело скрипта.
     * Payload передаётся как base64-JSON: алфавит base64 не содержит кавычек и слешей,
     * поэтому встраивается в код без escape-обработки и не может сломать скрипт.
     * Десериализацией занимается прелюдия (python — json, kotlin — klaxon).
     */
    private String compose(ScriptLanguage language, String payloadB64, String body) {
        String nullLiteral = language == ScriptLanguage.PYTHON ? "None" : "null";
        String arg = payloadB64 == null ? nullLiteral : "\"" + payloadB64 + "\"";
        String binding = switch (language) {
            case PYTHON -> "payload = load_payload(" + arg + ")";
            case KOTLIN -> "payload = decode_payload(" + arg + ")";
        };
        return preludes.get(language) + "\n\n" + binding + "\n\n" + body;
    }

    // ---------- Вывод ----------

    /**
     * Контракт скриптов-правил — JSON-строка последним выводом; служебные строки kernel'а
     * (например, лог подгрузки библиотек через %use) отсекаются.
     */
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

    // ---------- Файлы и ресурсы ----------

    private String loadPrelude(String path) {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Runtime-файл не найден: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать runtime-файл " + path, e);
        }
    }

    private Path resolve(String id) {
        Path scripts = Path.of(props.dir()).toAbsolutePath().normalize();
        Path file = scripts.resolve(id + ".md").normalize();
        if (!file.startsWith(scripts) || !Files.isRegularFile(file)) {
            throw new NoSuchElementException("Скрипт не найден: " + id);
        }
        return file;
    }
}
