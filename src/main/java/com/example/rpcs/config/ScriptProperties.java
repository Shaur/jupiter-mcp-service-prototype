package com.example.rpcs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "scripts")
public record ScriptProperties(@DefaultValue("scripts") String dir) {
}
