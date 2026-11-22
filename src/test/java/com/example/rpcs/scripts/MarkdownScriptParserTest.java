package com.example.rpcs.scripts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkdownScriptParserTest {

    private final MarkdownScriptParser parser = new MarkdownScriptParser();

    @TempDir
    Path dir;

    private Path write(String content) throws IOException {
        Path file = dir.resolve("script.md");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void parsesValidScript() throws IOException {
        ScriptDefinition definition = parser.parse(write("""
                ---
                id: demo
                language: python
                ---

                ```python
                x = 1
                print(x)
                ```
                """), "demo");

        assertThat(definition.id()).isEqualTo("demo");
        assertThat(definition.language()).isEqualTo(ScriptLanguage.PYTHON);
        assertThat(definition.code()).isEqualTo("x = 1\nprint(x)");
    }

    @Test
    void parsesFenceWithoutLanguageTag() throws IOException {
        ScriptDefinition definition = parser.parse(write("""
                ---
                language: kotlin
                ---

                ```
                println(1)
                ```
                """), "demo");

        assertThat(definition.language()).isEqualTo(ScriptLanguage.KOTLIN);
        assertThat(definition.code()).isEqualTo("println(1)");
    }

    @Test
    void handlesCrlfLineEndings() throws IOException {
        ScriptDefinition definition = parser.parse(write(
                "---\r\nlanguage: python\r\n---\r\n\r\n```python\r\nprint(1)\r\n```\r\n"), "demo");

        assertThat(definition.code()).isEqualTo("print(1)");
    }

    @Test
    void requiresFrontMatter() throws IOException {
        assertThatThrownBy(() -> parser.parse(write("просто текст"), "demo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YAML front matter");
    }

    @Test
    void requiresClosedFrontMatter() throws IOException {
        assertThatThrownBy(() -> parser.parse(write("""
                ---
                language: python
                """), "demo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YAML front matter");
    }

    @Test
    void requiresLanguage() throws IOException {
        assertThatThrownBy(() -> parser.parse(write("""
                ---
                id: demo
                ---
                """), "demo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не указан language");
    }

    @Test
    void rejectsUnknownLanguage() throws IOException {
        assertThatThrownBy(() -> parser.parse(write("""
                ---
                language: ruby
                ---

                ```ruby
                puts 1
                ```
                """), "demo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruby");
    }

    @Test
    void rejectsMissingCodeBlock() throws IOException {
        assertThatThrownBy(() -> parser.parse(write("""
                ---
                language: python
                ---
                """), "demo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не содержит блока кода");
    }

    @Test
    void rejectsEmptyCodeBlock() throws IOException {
        // пустой блок эквивалентен отсутствию блока
        assertThatThrownBy(() -> parser.parse(write("""
                ---
                language: python
                ---

                ```python
                ```
                """), "demo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не содержит блока кода");
    }

    @Test
    void rejectsMultipleCodeBlocks() throws IOException {
        assertThatThrownBy(() -> parser.parse(write("""
                ---
                language: python
                ---

                ```python
                print(1)
                ```

                ```python
                print(2)
                ```
                """), "demo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("несколько блоков кода");
    }
}
