package com.example.rpcs.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Блок результата инструмента; интересен только text, остальные типы пропускаем. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentBlock(String type, String text) {
}
