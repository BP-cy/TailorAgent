package com.changy.tailoragent.tool.skill;

import com.changy.tailoragent.chat.service.ToolCallSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Skill 工具 —— 模型自主加载「专家提示词包」的入口。
 * <p>
 * <b>触发</b>:模型看到系统提示里的「可用 Skills」清单,判断当前任务匹配某项描述时,
 * 调用本工具并传入其 name。本工具经 {@code ToolAggregator → EventEmittingToolCallback} 包裹,
 * 调用过程会自动落 tool_call/tool_result 事件并推前端(同其它本地工具)。
 * <p>
 * <b>当轮生效</b>:返回值即该 Skill 的正文,Spring AI 作为工具结果回喂模型,模型本轮立即遵循。
 * <p>
 * <b>跨轮长期生效</b>:正文同时经 {@link ToolCallSink#appendDurableContext} 落成一条
 * {@code system / skill_context} 事件 —— 该事件仅落库、不推前端,后续轮次由
 * {@code ChatContextProjector} 回放成 SystemMessage,使已加载的 Skill 在本会话内持续作用。
 */
@Component
public class SkillTool {

    private static final Logger log = LoggerFactory.getLogger(SkillTool.class);

    private final SkillRegistry registry;

    public SkillTool(SkillRegistry registry) {
        this.registry = registry;
    }

    @Tool(name = "skill", description =
            "加载一个 Skill(专家提示词包)并使其生效。当用户任务匹配系统提示『可用 Skills』清单中某项的描述时调用;" +
            "name 须与清单中的名称完全一致。加载后该 Skill 的指令会在本会话后续持续生效。仅在确有匹配时调用。")
    public String skill(
            @ToolParam(description = "要加载的 Skill 名称,须与系统提示『可用 Skills』清单中的名称完全一致")
            String name,
            ToolContext toolContext) {

        SkillMeta meta = registry.find(name);
        if (meta == null) {
            log.info("[Skill] 未找到: {}", name);
            return "未找到名为 \"" + name + "\" 的 Skill。当前可用: " + registry.names();
        }

        String body = registry.loadBody(meta.name());
        if (body == null || body.isBlank()) {
            return "Skill \"" + meta.name() + "\" 正文为空,无法加载。";
        }

        // 落持久上下文事件(仅落库,不推前端),使该 Skill 跨轮持续生效
        ToolCallSink sink = extractSink(toolContext);
        if (sink != null) {
            sink.appendDurableContext("【已加载 Skill: " + meta.name() + "】\n\n" + body);
        }

        log.info("[Skill] 已加载: {} ({} chars)", meta.name(), body.length());
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
