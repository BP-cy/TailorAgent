package com.changy.tailoragent.knowledge.index;

/** 一个真正写入 Lucene、具有硬大小上限的物理检索块。 */
public record IndexChunk(
        String chunkId,
        MarkdownSection section,
        int partNo,
        int chunkStart,
        int chunkEnd,
        String text
) {
}
