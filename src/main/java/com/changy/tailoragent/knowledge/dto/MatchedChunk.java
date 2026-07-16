package com.changy.tailoragent.knowledge.dto;

/** 真正触发 BM25/KNN 召回的物理块证据。rank 为物理召回列表中的 1-based 排名。 */
public record MatchedChunk(
        String chunkId,
        String sectionId,
        String headingPath,
        int chunkStart,
        int chunkEnd,
        String text,
        Integer bm25Rank,
        Integer vectorRank
) {
}
