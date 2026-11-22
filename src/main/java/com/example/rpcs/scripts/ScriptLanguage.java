package com.example.rpcs.scripts;

import java.util.Locale;

public enum ScriptLanguage {
    PYTHON("python3"),
    KOTLIN("kotlin");

    private final String kernelName;

    ScriptLanguage(String kernelName) {
        this.kernelName = kernelName;
    }

    /** Имя kernelspec Jupyter для исполнения скрипта. */
    public String kernelName() {
        return kernelName;
    }

    public static ScriptLanguage from(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "python", "python3" -> PYTHON;
            case "kotlin" -> KOTLIN;
            default -> throw new IllegalArgumentException(
                    "Язык не поддерживается: " + value + " (доступны: python, kotlin)");
        };
    }
}
