package com.changy.tailoragent.tool.skill;

/**
 * Skill 对外信息(API/前端用)—— 仅暴露 name + description,不含本地路径。
 * 与 {@link SkillMeta}(含 {@code Path skillMd})区分:后者是内部加载用的全量元数据。
 */
public record SkillInfo(String name, String description) {
}
