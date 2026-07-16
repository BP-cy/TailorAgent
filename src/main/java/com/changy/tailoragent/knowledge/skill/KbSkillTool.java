package com.changy.tailoragent.knowledge.skill;

import com.changy.tailoragent.chat.service.ToolCallSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 知识库 AI 编辑 agent 的 Skill 工具 —— 主对话 {@link com.changy.tailoragent.tool.skill.SkillTool}
 * 的<b>独立副本</b>，绑定 {@link KbSkillRegistry}。
 * <p>
 * 工具名仍为 {@code skill}：编辑 agent 与主对话是<b>各自独立的工具集</b>（本工具不进
 * {@code ToolConfig.localToolBeans}，仅由 {@code KnowledgeEditService} 显式挂载），
 * 同名不冲突。
 * <p>
 * <b>当轮生效</b>：返回值即该 Skill 的正文，Spring AI 作为工具结果回喂模型，模型本轮立即遵循。
 * <p>
 * <b>跨轮说明</b>：编辑链路<b>不落库</b>，其 sink（{@code KnowledgeToolCallSink}）的
 * {@code appendDurableContext} 为 no-op，故 Skill 仅当轮生效、不跨轮持久（区别于主对话）。
 * 编辑指令通常自足，模型每轮按需重新加载即可。
 */
@Component
public class KbSkillTool {

    private static final Logger log = LoggerFactory.getLogger(KbSkillTool.class);

    private final KbSkillRegistry registry;

    public KbSkillTool(KbSkillRegistry registry) {
        this.registry = registry;
    }

    @Tool(name = "skill", description =
            "加载一个 Skill(专家提示词包)并使其生效。当用户任务匹配系统提示『可用 Skills』清单中某项的描述时调用;" +
            "name 须与清单中的名称完全一致。加载后该 Skill 的指令在本轮编辑生效。仅在确有匹配时调用。")
    public String skill(
            @ToolParam(description = "要加载的 Skill 名称,须与系统提示『可用 Skills』清单中的名称完全一致")
            String name,
            ToolContext toolContext) {

        if (!registry.contains(name)) {
            log.info("[知识库 Skill] 未找到: {}", name);
            return "未找到名为 \"" + name + "\" 的 Skill。当前可用: " + registry.names();
        }

        String normalizedName = name.strip();
        String body = registry.loadBody(normalizedName);
        if (body == null || body.isBlank()) {
            return "Skill \"" + normalizedName + "\" 正文为空,无法加载。";
        }

        // 落持久上下文事件(编辑链路 sink 为 no-op,此处仅为与主对话保持一致的调用形态)
        ToolCallSink sink = extractSink(toolContext);
        if (sink != null) {
            sink.appendDurableContext("【已加载 Skill: " + normalizedName + "】\n\n" + body);
        }

        log.info("[知识库 Skill] 已加载: {} ({} chars)", normalizedName, body.length());
        return body;
    }

    /** 从 ToolContext 取本轮 sink(复用与 EventEmittingToolCallback 相同的键;可能为 null) */
    private static ToolCallSink extractSink(ToolContext ctx) {
        if (ctx == null || ctx.getContext() == null) {
            return null;
        }
        Object v = ctx.getContext().get(ToolCallSink.KEY);
        return v instanceof ToolCallSink sink ? sink : null;
    }
}
