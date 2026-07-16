package com.changy.tailoragent.knowledge.index;

import java.util.List;

/** 从 Lucene StoredField 还原的物理块和所属 Section 元数据。 */
public record StoredIndexChunk(
        String chunkId,
        String docPath,
        String contentHash,
        String sectionId,
        String parentSectionId,
        List<String> ancestorSectionIds,
        int sectionLevel,
        int sectionOrdinal,
        int partNo,
        boolean expandable,
        String heading,
        String headingPath,
        String text,
        int sectionStart,
        int sectionEnd,
        int chunkStart,
        int chunkEnd,
        int sectionCharCount
) {
    public StoredIndexChunk {
        ancestorSectionIds = List.copyOf(ancestorSectionIds);
    }
}
