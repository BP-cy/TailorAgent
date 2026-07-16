package com.changy.tailoragent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 知识库目录树节点（由扫描磁盘目录派生）。
 *
 * <p>文件夹节点 {@code type=folder}，含 {@link #children}；文件节点 {@code type=md|file}，
 * 含 {@link #status}/{@link #size}/{@link #mtime}。{@link #path} 为相对 knowledge 根的相对路径（正斜杠）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KbNode {

    /** 相对 knowledge 根的相对路径（正斜杠，形如 MD/工作/报告.md）。 */
    private String path;
    /** 文件名或文件夹名。 */
    private String name;
    /** folder | md | file。 */
    private String type;
    /** 索引状态（仅文件节点）。 */
    private String status;
    /** 文件字节数（仅文件节点）。 */
    private Long size;
    /** 最后修改时间 ISO 8601（仅文件节点）。 */
    private String mtime;
    /** 子节点（仅文件夹节点）。 */
    private List<KbNode> children;
}
