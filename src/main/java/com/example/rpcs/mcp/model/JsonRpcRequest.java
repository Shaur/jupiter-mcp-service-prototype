package com.example.rpcs.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSON-RPC 2.0 запрос к MCP-серверу. Поле id обязательно: сообщение без id
 * считается уведомлением, и сервер не отвечает на него.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcRequest(String jsonrpc, Long id, String method, Object params) {

    public static JsonRpcRequest request(long id, String method, Object params) {
        return new JsonRpcRequest("2.0", id, method, params);
    }
}
