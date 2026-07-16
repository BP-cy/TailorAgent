package com.changy.tailoragent.chat.controller;

import com.changy.tailoragent.ModelConfig.dto.CancelRequest;
import com.changy.tailoragent.ModelConfig.dto.ChatRequest;
import com.changy.tailoragent.ModelConfig.dto.CompactRequest;
import com.changy.tailoragent.ModelConfig.dto.CompactionResult;
import com.changy.tailoragent.chat.service.ChatService;
import com.changy.tailoragent.chat.service.TurnControlRegistry;
import com.changy.tailoragent.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天 API —— 发送消息并通过 SSE 流式接收 AI 回复(含工具调用过程)。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final TurnControlRegistry turnControl;

    public ChatController(ChatService chatService, TurnControlRegistry turnControl) {
        this.chatService = chatService;
        this.turnControl = turnControl;
    }

    /**
     * 发送对话 —— SSE 流式返回本轮事件(start / tool_call / tool_result / text / done / error)。
     * <p>
     * 真正的执行在 {@code ChatService} 内部的后台线程进行,本方法立即返回 emitter,
     * Spring 保持连接打开直到 {@code emitter.complete()}。与 {@code /api/ai-edit} 同套约定。
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request) {
        log.info("=== 聊天请求入口: sessionId={}, modelIndex={} ===",
                request.getSessionId(), request.getModelIndex());
        SseEmitter emitter = new SseEmitter(600_000L); // 10 分钟超时,与 ai-edit 一致
        chatService.chat(request, emitter);
        return emitter;
    }

    /**
     * 主动停止某一正在运行的轮次 —— 立即强杀其前台工具进程、断开模型流、中断执行线程,
     * 并把该轮置为 {@code cancelled}(执行线程被唤醒后通过 SSE 发 {@code cancelled} 事件)。
     */
    @PostMapping("/cancel")
    public ApiResponse<Void> cancel(@Valid @RequestBody CancelRequest request) {
        log.info("=== 取消请求: turnId={} ===", request.getTurnId());
        boolean ok = turnControl.cancel(request.getTurnId());
        return ok ? ApiResponse.success("已请求取消") : ApiResponse.error("该轮次不在运行(可能已结束)");
    }

    /**
     * 手动压缩会话上下文 —— 把较早的对话历史压成一条摘要,降低后续轮次的输入 token。
     * <p>同步执行(用所选模型生成摘要),返回压缩前后估算与压缩后的上下文占用(供前端刷新占比条)。
     */
    @PostMapping("/compact")
    public ApiResponse<CompactionResult> compact(@Valid @RequestBody CompactRequest request) {
        log.info("=== 压缩上下文请求: sessionId={}, modelIndex={} ===",
                request.getSessionId(), request.getModelIndex());
        CompactionResult result = chatService.compact(request.getSessionId(), request.getModelIndex());
        return ApiResponse.success(result.getMessage(), result);
    }
}
