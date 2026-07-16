package com.changy.tailoragent.knowledge.index;

/** 单路召回中的一个物理块命中。rank 从 1 开始。 */
public record LuceneChunkHit(StoredIndexChunk chunk, int rank, float score) {
}
