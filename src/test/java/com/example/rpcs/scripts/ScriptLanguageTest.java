package com.example.rpcs.scripts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptLanguageTest {

    @Test
    void resolvesPythonAliases() {
        assertThat(ScriptLanguage.from("python")).isEqualTo(ScriptLanguage.PYTHON);
        assertThat(ScriptLanguage.from("python3")).isEqualTo(ScriptLanguage.PYTHON);
        assertThat(ScriptLanguage.from(" Python ").kernelName()).isEqualTo("python3");
    }

    @Test
    void resolvesKotlinCaseInsensitively() {
        assertThat(ScriptLanguage.from("KOTLIN")).isEqualTo(ScriptLanguage.KOTLIN);
        assertThat(ScriptLanguage.from("kotlin").kernelName()).isEqualTo("kotlin");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> ScriptLanguage.from(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownLanguage() {
        assertThatThrownBy(() -> ScriptLanguage.from("ruby"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruby");
    }
}
