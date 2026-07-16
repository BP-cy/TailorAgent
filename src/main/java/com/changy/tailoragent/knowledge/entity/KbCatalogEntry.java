package com.changy.tailoragent.knowledge.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库目录索引条目，映射 {@code kb_document} 表。
 *
 * <p>正文不在此 —— 真相源是磁盘上的文件；此实体只记录"指向文件"的索引元数据。
 * 主键 {@link #relPath} 是相对 {@code knowledge/} 根的相对路径（正斜杠，形如 {@code MD/工作/报告.md}）。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class KbCatalogEntry {

    /** 相对 knowledge 根的相对路径（正斜杠），主键。 */
    private String relPath;
    /** 索引状态：unindexed | processing | indexed | failed。 */
    private String status;
    /** 上次索引时的内容哈希（惰性于索引时计算）。 */
    private String contentHash;
    /** 上次切块时的内容哈希；null=从未切块。与 contentHash 不等即需重切。 */
    private String chunkedHash;
    /** 最后索引时间（ISO 8601）。 */
    private String indexedAt;
    /** 预留标签字段。 */
    private String tags;
}
