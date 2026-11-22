package com.example.rpcs.web;

import com.example.rpcs.scripts.ScriptService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.NoSuchElementException;

@RestController
public class ScriptController {

    private final ScriptService scriptService;

    public ScriptController(ScriptService scriptService) {
        this.scriptService = scriptService;
    }

    /**
     * Запускает markdown-скрипт scripts/{id}.md — YAML front matter с языком + блок кода.
     * Если передано JSON-тело, оно уходит в скрипт как payload (в плейсхолдер __PAYLOAD_B64__),
     * и ответ возвращается как application/json; без тела — вывод скрипта как text/plain.
     */
    @PostMapping(value = "/api/scripts/{id}/run", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> run(@PathVariable String id,
                                      @RequestBody(required = false) JsonNode payload) {
        String output = payload == null
                ? scriptService.run(id)
                : scriptService.runWithPayload(id, payload);
        MediaType contentType = output.strip().startsWith("{")
                ? MediaType.APPLICATION_JSON
                : MediaType.TEXT_PLAIN;
        return ResponseEntity.ok().contentType(contentType).body(output);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NoSuchElementException e) {
        return e.getMessage();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(IllegalArgumentException e) {
        return e.getMessage();
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public String onError(Exception e) {
        return "MCP error: " + e.getMessage();
    }
}
