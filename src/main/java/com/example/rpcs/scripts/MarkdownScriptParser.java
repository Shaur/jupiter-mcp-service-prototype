package com.example.rpcs.scripts;

import org.commonmark.ext.front.matter.YamlFrontMatterBlock;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterVisitor;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class MarkdownScriptParser {

    private final Parser markdown = Parser.builder()
            .extensions(List.of(YamlFrontMatterExtension.create()))
            .build();

    /**
     * Разбирает markdown-скрипт: YAML front matter с полем language
     * и единственный ограждённый блок кода. Идентификатор берётся из имени файла.
     */
    public ScriptDefinition parse(Path file, String id) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать " + file, e);
        }
        Node document = markdown.parse(content);

        if (!(document.getFirstChild() instanceof YamlFrontMatterBlock)) {
            throw new IllegalArgumentException("Файл должен начинаться с YAML front matter (---)");
        }
        YamlFrontMatterVisitor frontMatter = new YamlFrontMatterVisitor();
        document.accept(frontMatter);
        List<String> language = frontMatter.getData().get("language");
        if (language == null || language.isEmpty() || language.getFirst().isBlank()) {
            throw new IllegalArgumentException("В YAML front matter не указан language");
        }

        return new ScriptDefinition(id, ScriptLanguage.from(language.getFirst()), firstCodeBlock(document));
    }

    private String firstCodeBlock(Node document) {
        String only = null;
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof FencedCodeBlock code && code.getLiteral() != null && !code.getLiteral().isBlank()) {
                if (only != null) {
                    throw new IllegalArgumentException("В файле несколько блоков кода — ожидается ровно один");
                }
                only = code.getLiteral().strip();
            }
        }
        if (only == null) {
            throw new IllegalArgumentException("Файл не содержит блока кода (```)");
        }
        return only;
    }
}
