package com.example.rpcs.web;

import com.example.rpcs.mcp.McpService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CodeExecutionController {

    public static final String KERNEL_HEADER = "X-Kernel-Id";

    private final McpService mcpService;

    public CodeExecutionController(McpService mcpService) {
        this.mcpService = mcpService;
    }

    /**
     * Тело запроса — код целиком (text/plain). Необязательный X-Kernel-Id исполняет код в конкретном ядре Jupyter.
     * Правило content-type — как в ScriptController: JSON-вывод отдаётся как application/json.
     */
    @PostMapping(value = "/api/execute", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> execute(@RequestBody String code,
                                          @RequestHeader(value = KERNEL_HEADER, required = false) String kernelId) {
        String output = mcpService.execute(code, kernelId);
        MediaType contentType = output.strip().startsWith("{")
                ? MediaType.APPLICATION_JSON
                : MediaType.TEXT_PLAIN;
        return ResponseEntity.ok().contentType(contentType).body(output);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public String onError(Exception e) {
        return "MCP error: " + e.getMessage();
    }
}
