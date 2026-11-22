package com.example.rpcs.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolCallResult(List<ContentBlock> content, Boolean isError) {
}
