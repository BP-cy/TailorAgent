package com.changy.tailoragent.knowledge.service;

import com.changy.tailoragent.chat.service.ToolCallSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库 AI 编辑用的 {@link ToolCallSink} —— 只把工具调用事件<b>推送</b>给前端 SSE，<b>不落库</b>。
 *
 * <p>与聊天的 {@code SseToolCallSink} 结构一致，但去掉了 SessionService 持久化：知识库编辑是临时会话，
 * 关闭编辑页即弃、无历史（见知识库重构决策）。负载结构（callId/toolName/source/args、status/result/error）
 * 与聊天完全相同，故前端可直接复用同一套 ToolCallCard 渲染。
 */
public class KnowledgeToolCallSink implements ToolCallSink {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeToolCallSink.class);
    private static final int VALUE_LIMIT = 4000;

    private final SseEmitter emitter;
    private final ObjectMapper mapper;
    /** 工具调用边界回调：在写 tool_call 事件「之前」触发，用于先把上一段思考 flush 出去（可空） */
    private final Runnable onBeforeToolCall;

    public KnowledgeToolCallSink(SseEmitter emitter, ObjectMapper mapper) {
        this(emitter, mapper, null);
    }

    public KnowledgeToolCallSink(SseEmitter emitter, ObjectMapper mapper, Runnable onBeforeToolCall) {
        this.emitter = emitter;
        this.mapper = mapper;
        this.onBeforeToolCall = onBeforeToolCall;
    }

    @Override
    public void onToolCall(String callId, String toolName, String source, String argsJson) {
        // 工具调用即一段思考的终点：先把已累积的思考 flush 成 reasoning 事件（排在本 tool_call 之前），
        // 保证到达顺序 reasoning → tool_call，前端据此把每段思考独立成卡。
        if (onBeforeToolCall != null) {
            onBeforeToolCall.run();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callId", callId);
        payload.put("toolName", toolName);
        payload.put("source", source);
        payload.put("args", truncate(argsJson));
        send("tool_call", toJson(payload));
    }

    @Override
    public void onToolResult(String callId, String status, String result, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callId", callId);
        payload.put("status", status);
        if (result != null) payload.put("result", truncate(result));
        if (error != null) payload.put("error", error);
        send("tool_result", toJson(payload));
    }

    @Override
    public void appendDurableContext(String content) {
        // 知识库编辑不持久化上下文（无 skill / 无跨轮），no-op。
    }

    private void send(String name, String jsonData) {
        try {
            emitter.send(SseEmitter.event().name(name).data(jsonData));
        } catch (IOException | RuntimeException e) {
            log.warn("[知识库编辑] SSE 推送失败: name={}, err={}", name, e.getMessage());
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("[知识库编辑] 负载序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() <= VALUE_LIMIT ? s : s.substring(0, VALUE_LIMIT) + "…(共" + s.length() + "字)";
    }
}
