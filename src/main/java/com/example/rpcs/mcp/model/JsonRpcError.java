package com.example.rpcs.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JsonRpcError(Integer code, String message, Object data) {
}
