package com.changy.tailoragent.knowledge.service;

import java.util.List;

/**
 * 知识库索引进度监听器。
 *
 * <p>文件完成表示该文件已经完成读取、AST 解析、物理切块和全部向量化；
 * Lucene 写入通过 {@link #onWriting()} 单独表示。整库重建时可以重新规划队列，
 * 因而 {@link #onPlan(List)} 允许在同一任务中调用多次。</p>
 */
public interface KnowledgeIndexProgressListener {

    KnowledgeIndexProgressListener NONE = new KnowledgeIndexProgressListener() {};

    /** 用实际需要处理的 Markdown 路径重新规划本次文件队列。 */
    default void onPlan(List<String> paths) {}

    /** 开始解析、切块并向量化某个文件。 */
    default void onFileStarted(String path) {}

    /** 某个文件的解析、切块和向量化已经完成。 */
    default void onFileCompleted(String path) {}

    /** 所有文件准备完成，开始写入或原子替换 Lucene 索引。 */
    default void onWriting() {}
}
