package com.changy.tailoragent.chat.service.impl;

import com.changy.tailoragent.chat.entity.ChatTurn;
import com.changy.tailoragent.chat.service.SessionService;
import com.changy.tailoragent.chat.service.ToolCallSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ToolCallSink} 的 SSE 实现 —— 每次工具调用做两件事:
 * <ol>
 *   <li>落库:作为 {@code tool_call} / {@code tool_result} 事件写入当前轮次(刷新/重连可重放);</li>
 *   <li>推送:通过 {@link SseEmitter} 把同一份 JSON 负载实时发给前端(事件名 tool_call / tool_result)。</li>
 * </ol>
 * <p>
 * <b>生命周期</b>:每轮对话 new 一个,绑定该轮的 {@code turn} 与 {@code emitter}。
 * <b>线程</b>:工具调用在 Reactor 的 {@code boundedElastic} 线程执行(与主流逐块消费的
 * {@code chat-stream} 线程不同),但二者**不并发**——工具执行期间主流阻塞等待流恢复,
 * 故 {@code emitter.send} 不会两线程同时进行,无需额外同步。
 * <p>
 * 落库的 payload 与 SSE 发送的 data 完全一致,保证「实时看到的」与「刷新后重放的」结构相同。
 */
public class SseToolCallSink implements ToolCallSink {

    private static final Logger log = LoggerFactory.getLogger(SseToolCallSink.class);

    /** 结果/入参落库与展示的最大字符数,避免超大输出撑爆 DB 与前端(原始结果仍原样回喂模型,不受影响) */
    private static final int VALUE_LIMIT = 4000;

    private final SessionService sessionService;
    private final ChatTurn turn;
    private final SseEmitter emitter;
    private final ObjectMapper mapper;
    /** 工具调用边界回调:在写 tool_call 事件「之前」触发,用于先把上一段思考落库(可空) */
    private final Runnable onBeforeToolCall;

    public SseToolCallSink(SessionService sessionService, ChatTurn turn,
                           SseEmitter emitter, ObjectMapper mapper) {
        this(sessionService, turn, emitter, mapper, null);
    }

    public SseToolCallSink(SessionService sessionService, ChatTurn turn,
                           SseEmitter emitter, ObjectMapper mapper, Runnable onBeforeToolCall) {
        this.sessionService = sessionService;
        this.turn = turn;
        this.emitter = emitter;
        this.mapper = mapper;
        this.onBeforeToolCall = onBeforeToolCall;
    }

    @Override
    public void onToolCall(String callId, String toolName, String source, String argsJson) {
        // 工具调用即一段思考的终点:先把已累积的思考落成独立 reasoning 事件(排在本 tool_call 之前),
        // 保证 DB 重放顺序与实时流一致(reasoning₁ → tool_call₁ → tool_result₁ → reasoning₂ → …)
        if (onBeforeToolCall != null) {
            onBeforeToolCall.run();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callId", callId);
        payload.put("toolName", toolName);
        payload.put("source", source);
        payload.put("args", truncate(argsJson));
        String json = toJson(payload);
        sessionService.appendToolEvent(turn, "tool_call", json, "running");
        send("tool_call", json);
    }

    @Override
    public void onToolResult(String callId, String status, String result, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callId", callId);
        payload.put("status", status);
        if (result != null) {
            payload.put("result", truncate(result));
        }
        if (error != null) {
            payload.put("error", error);
        }
        String json = toJson(payload);
        sessionService.appendToolEvent(turn, "tool_result", json, status);
        send("tool_result", json);
    }

    @Override
    public void appendDurableContext(String content) {
        // 仅落库:role=system, type=skill_context;不走 SSE。
        // 界面上该 Skill 的加载已由 tool_call/tool_result 工具卡呈现,此事件纯供模型跨轮上下文。
        sessionService.appendEvent(turn, "system", "skill_context", content, null, null);
    }

    /** 发送一条具名 SSE 事件;data 已是 JSON 单行字符串,前端按事件名分发后 JSON.parse */
    private void send(String name, String jsonData) {
        try {
            emitter.send(SseEmitter.event().name(name).data(jsonData));
        } catch (IOException | RuntimeException e) {
            // 连接已断开等情况:落库已完成,推送失败仅告警,不影响工具循环继续
            log.warn("[工具事件] SSE 推送失败: name={}, err={}", name, e.getMessage());
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("[工具事件] 负载序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    /** 入参/结果超长截断(仅影响落库与展示) */
    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= VALUE_LIMIT
                ? s
                : s.substring(0, VALUE_LIMIT) + "…(共" + s.length() + "字)";
    }
}
