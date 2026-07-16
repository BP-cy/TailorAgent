package com.changy.tailoragent.knowledge.index;

import org.springframework.stereotype.Component;

/** 组合 AST 章节解析与物理切块。 */
@Component
public class MarkdownIndexDocumentBuilder {

    private final MarkdownSectionParser sectionParser;
    private final MarkdownChunker chunker;

    public MarkdownIndexDocumentBuilder(MarkdownSectionParser sectionParser, MarkdownChunker chunker) {
        this.sectionParser = sectionParser;
        this.chunker = chunker;
    }

    public ChunkedMarkdownDocument build(String docPath, String source) {
        ParsedMarkdownDocument parsed = sectionParser.parse(docPath, source);
        return new ChunkedMarkdownDocument(parsed, chunker.chunk(parsed));
    }
}
