package com.example.rpcs.mcp.model;

import java.util.Map;

public record ToolCallParams(String name, Map<String, Object> arguments) {
}
