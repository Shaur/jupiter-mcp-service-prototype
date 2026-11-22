package com.example.rpcs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** url — адрес MCP-эндпоинта, apiUrl — базовый адрес Jupyter REST API (тот же сервер, без /mcp). */
@ConfigurationProperties(prefix = "mcp")
public record McpProperties(
        String url,
        String apiUrl,
        String token,
        @DefaultValue("10") int connectTimeoutSeconds,
        @DefaultValue("300") int readTimeoutSeconds,
        @DefaultValue("120") int executionTimeoutSeconds,
        @DefaultValue("1") int kernelPoolMinSize,
        @DefaultValue("4") int kernelPoolMaxSize,
        @DefaultValue("60") int kernelAcquireTimeoutSeconds) {
}
