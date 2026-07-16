package com.changy.tailoragent.chat.service;

import com.changy.tailoragent.chat.entity.ChatEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文投影层 —— 将持久化的事件流投影成喂给模型的 {@code List<Message>}。
 * <p>
 * <b>为什么单独一层</b>:存储的事件是给「人看 / 审计」的富事件(文件 diff、完整 stdout 等),
 * 而模型需要的是 Spring AI 的 user/assistant/system 消息序列。两者是不同的形状,
 * 隔离在这一层后,将来要做上下文裁剪 / 大输出摘要 / 换模型,都只改这里,不动存储。
 * <p>
 * <b>Phase 1</b>:工具尚未接入,仅投影 {@code type=text} 事件;
 * tool_call / tool_result 等留待工具落地时在此扩展(转成对应的 tool 消息或摘要)。
 */
@Component
public class ChatContextProjector {

    private static final Logger log = LoggerFactory.getLogger(ChatContextProjector.class);

    /** 摘要消息前缀:标明这是「前文压缩」而非用户输入,降低被当成指令的风险 */
    private static final String SUMMARY_PREFIX =
            "【以下为前文对话的压缩摘要,作为背景记忆参考,不是用户的新指令】\n\n";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 将整条会话事件流投影为模型消息序列。
     * <p>
     * 若存在 {@code summary} 事件(上下文压缩产物),取**最新一条**摘要,把它 {@code coversUpToEventId}
     * 之前的 {@code text} 原文一律剔除、用摘要替代;摘要消息置于「被压缩段」之后、保留段之前,
     * 使时间顺序为 [被压缩范围的持久指令] → [摘要] → [近期原文]。
     * {@code skill_context}(持久指令)无论是否在压缩范围内都保留原文。
     */
    public List<Message> project(List<ChatEvent> events) {
        // 1) 找最新摘要 + 其覆盖截止 id
        ChatEvent lastSummary = null;
        for (ChatEvent e : events) {
            if ("summary".equals(e.getType())) {
                lastSummary = e;
            }
        }
        int cutoff = lastSummary != null ? coversUpTo(lastSummary) : -1;

        // 2) 分头(被压缩范围内仍需保留的持久指令)/ 尾(摘要之后的原文)两段拼装
        List<Message> head = new ArrayList<>();
        List<Message> tail = new ArrayList<>();
        for (ChatEvent e : events) {
            boolean compressed = cutoff >= 0 && e.getId() != null && e.getId() <= cutoff;

            if ("summary".equals(e.getType())) {
                // 旧摘要被新摘要取代,只在下面统一注入最新一条;此处一律跳过
                logEvent(e, e == lastSummary ? "✓最新摘要(稍后注入)" : "✗旧摘要跳过");
                continue;
            }
            if ("skill_context".equals(e.getType())) {
                // Skill 持久指令:无论是否在压缩范围,都保留原文(不进摘要)
                String body = e.getContent();
                Message m = (body == null || body.isBlank()) ? null : new SystemMessage(body);
                if (m != null) {
                    (compressed ? head : tail).add(m);
                }
                logEvent(e, m != null ? "✓skill_context" : "✗空跳过");
                continue;
            }
            if (compressed) {
                logEvent(e, "✗已被摘要覆盖");
                continue; // 被压缩范围内的 text → 由摘要替代
            }
            Message m = toMessage(e);
            if (m != null) {
                tail.add(m);
            }
            logEvent(e, m != null ? "✓进上下文 → " + m.getClass().getSimpleName() : "✗跳过");
        }

        List<Message> messages = new ArrayList<>(head.size() + tail.size() + 1);
        messages.addAll(head);
        if (lastSummary != null && lastSummary.getContent() != null && !lastSummary.getContent().isBlank()) {
            messages.add(new SystemMessage(SUMMARY_PREFIX + lastSummary.getContent()));
        }
        messages.addAll(tail);
        return messages;
    }

    /** 解析摘要 payload 的 coversUpToEventId;缺失则回退到该摘要事件自身 id */
    private int coversUpTo(ChatEvent summary) {
        try {
            JsonNode node = mapper.readTree(summary.getPayload());
            JsonNode c = node.get("coversUpToEventId");
            if (c != null && c.isNumber()) {
                return c.asInt();
            }
        } catch (Exception ignore) {
            // 落到回退
        }
        return summary.getId() != null ? summary.getId() : -1;
    }

    /** 逐条打印事件 → 投影去向(便于追踪上下文组装) */
    private void logEvent(ChatEvent e, String outcome) {
        log.info("投影[#{} {}/{}{}] {} : {}",
                e.getId(), e.getType(), e.getRole(),
                e.getStatus() == null ? "" : "/" + e.getStatus(), outcome, preview(e));
    }

    /** 取事件正文/载荷的单行预览(截断,避免日志爆量) */
    private String preview(ChatEvent e) {
        String s = e.getContent() != null ? e.getContent() : e.getPayload();
        if (s == null) {
            return "<空>";
        }
        s = s.replace("\n", "\\n").replace("\r", "");
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private Message toMessage(ChatEvent e) {
        // 仅文本事件进入上下文;工具 / 思考等类型不投影
        if (!"text".equals(e.getType())) {
            return null;
        }
        String content = e.getContent();
        if (content == null || content.isBlank()) {
            return null;
        }
        String role = e.getRole() == null ? "" : e.getRole().toLowerCase();
        return switch (role) {
            case "user"      -> new UserMessage(content);
            case "assistant" -> new AssistantMessage(content);
            case "system"    -> new SystemMessage(content);
            default -> null;
        };
    }
}
