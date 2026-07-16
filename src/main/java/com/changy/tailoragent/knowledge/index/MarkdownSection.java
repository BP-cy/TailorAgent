package com.changy.tailoragent.knowledge.index;

import java.util.List;

/**
 * Markdown 中由标题范围定义的逻辑章节集合。
 *
 * <p>{@code directTextStart/directTextEnd} 只覆盖当前标题自己的直属正文；
 * {@code sectionStart/sectionEnd} 则覆盖标题和全部后代，用于最终返回父集合。</p>
 */
public record MarkdownSection(
        String sectionId,
        String docPath,
        String parentSectionId,
        List<String> ancestorSectionIds,
        int level,
        int ordinal,
        String heading,
        String headingPath,
        int sectionStart,
        int sectionEnd,
        int directTextStart,
        int directTextEnd,
        int sectionCharCount,
        boolean expandable,
        List<MarkdownSection> children
) {
    public MarkdownSection {
        ancestorSectionIds = List.copyOf(ancestorSectionIds);
        children = List.copyOf(children);
    }
}
