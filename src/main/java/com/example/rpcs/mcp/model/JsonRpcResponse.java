package com.example.rpcs.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * JSON-RPC 2.0 ответ MCP-сервера. Для уведомлений ответа нет — приходит {@link #empty()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JsonRpcResponse(String jsonrpc, Long id, JsonRpcError error, ToolCallResult result) {

    public static JsonRpcResponse empty() {
        return new JsonRpcResponse(null, null, null, null);
    }
}
