package com.changy.tailoragent.knowledge.index;

import java.util.List;

/** Markdown AST 解析结果；正文仍以原始字符串为真相源。 */
public record ParsedMarkdownDocument(
        String docPath,
        String source,
        List<MarkdownSection> rootSections,
        List<MarkdownSection> sections
) {
    public ParsedMarkdownDocument {
        rootSections = List.copyOf(rootSections);
        sections = List.copyOf(sections);
    }
}
