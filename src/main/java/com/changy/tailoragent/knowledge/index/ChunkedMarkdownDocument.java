package com.changy.tailoragent.knowledge.index;

import java.util.List;

/** 单篇 Markdown 完成标题树解析和物理切块后的中间结果。 */
public record ChunkedMarkdownDocument(
        ParsedMarkdownDocument parsed,
        List<IndexChunk> chunks
) {
    public ChunkedMarkdownDocument {
        chunks = List.copyOf(chunks);
    }
}
