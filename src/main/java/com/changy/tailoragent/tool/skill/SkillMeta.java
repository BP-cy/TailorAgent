package com.changy.tailoragent.tool.skill;

import java.nio.file.Path;

/**
 * Skill 元数据 —— 启动时只从 {@code SKILL.md} 的 frontmatter 解析出来的轻量信息。
 * <p>
 * {@code name}/{@code description} 会进系统提示词的「可用 Skills」清单(便宜,常驻);
 * {@code skillMd} 指向文件本身,真正的 body 仅在该 Skill 被调用时才懒加载(见 {@link SkillRegistry#loadBody}).
 *
 * @param name        Skill 名称(取 frontmatter 的 name,缺省回退目录名)
 * @param description 一句话描述(用于模型路由判断)
 * @param skillMd     SKILL.md 文件路径
 */
public record SkillMeta(String name, String description, Path skillMd) {
}
