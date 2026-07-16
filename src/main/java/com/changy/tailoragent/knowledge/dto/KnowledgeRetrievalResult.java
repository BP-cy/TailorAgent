package com.changy.tailoragent.knowledge.dto;

import java.util.List;

/** 父子归并和总预算裁剪后的最终知识检索结果。 */
public record KnowledgeRetrievalResult(
        String docPath,
        String sectionId,
        String heading,
        String headingPath,
        int sectionStart,
        int sectionEnd,
        String content,
        double score,
        List<String> matchedSectionIds,
        List<MatchedChunk> matchedChunks
) {
    public KnowledgeRetrievalResult {
        matchedSectionIds = List.copyOf(matchedSectionIds);
        matchedChunks = List.copyOf(matchedChunks);
    }
}
