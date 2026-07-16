package com.changy.tailoragent.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单篇文档读取结果：相对路径 + 名称 + 正文（从磁盘实时读取）。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class KbDoc {
    /** 相对 knowledge 根的相对路径（正斜杠）。 */
    private String path;
    /** 文件名。 */
    private String name;
    /** 正文（Markdown 原文）。 */
    private String content;
}
