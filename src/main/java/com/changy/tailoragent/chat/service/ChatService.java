package com.changy.tailoragent.chat.service;

import com.changy.tailoragent.ModelConfig.dto.ChatRequest;
import com.changy.tailoragent.ModelConfig.dto.CompactionResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天服务接口。
 */
public interface ChatService {

    /**
     * 发送对话 —— SSE 流式。内部在后台线程执行,通过 {@code emitter} 逐条推送事件:
     * <pre>
     *   event: start        data: {sessionId, turnId}
     *   event: tool_call    data: {callId, toolName, source, args}     ← 模型每次发起工具调用
     *   event: tool_result  data: {callId, status, result|error}       ← 对应工具返回
     *   event: text         data: {eventId, content}                   ← 最终助手文本
     *   event: done         data: {sessionId}
     *   event: cancelled    data: {sessionId}                          ← 用户主动停止本轮时
     *   event: error        data: {message}                            ← 出错时
     * </pre>
     * <p>
     * 内部负责:必要时新建会话、开启轮次、落库用户/工具/助手事件、从数据库投影历史上下文后调用模型。
     * 工具调用事件由 {@code ToolAggregator} 包裹的装饰器经 {@link ToolCallSink} 实时产出。
     *
     * @param request 聊天请求(sessionId 可空 + 本轮 content + modelIndex)
     * @param emitter SSE 发射器(由 Controller 创建并返回给前端)
     */
    void chat(ChatRequest request, SseEmitter emitter);

    /**
     * 手动压缩会话上下文 —— 把较早的对话历史压成一条摘要,降低后续轮次的输入 token。
     * <p>同步执行(非流式):用所选模型生成摘要、落库 summary 事件,返回压缩前后估算与压缩后的
     * 上下文占用(供前端刷新占比条)。历史过短时不压缩,{@code compacted=false}。
     *
     * @param sessionId  会话 id
     * @param modelIndex 生成摘要所用模型在配置列表中的索引
     */
    CompactionResult compact(Integer sessionId, Integer modelIndex);
}
