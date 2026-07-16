package com.changy.tailoragent.knowledge.controller;

import com.changy.tailoragent.common.response.ApiResponse;
import com.changy.tailoragent.knowledge.dto.KbCancelRequest;
import com.changy.tailoragent.knowledge.dto.KbEditRequest;
import com.changy.tailoragent.knowledge.service.KnowledgeEditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 知识库 AI 编辑接口 —— SSE 流式，独立于主对话（{@code /api/chat}），不落库。
 * 替代已退役的 {@code /api/ai-edit}（整篇重写管线）。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeEditController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEditController.class);

    private final KnowledgeEditService service;

    public KnowledgeEditController(KnowledgeEditService service) {
        this.service = service;
    }

    /** 发起知识库文档 AI 编辑，SSE 流式返回 start / tool_call / tool_result / text / done / error。 */
    @PostMapping(value = "/ai-edit", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter aiEdit(@RequestBody KbEditRequest request) {
        log.info("=== 知识库 AI 编辑: docPath={}, modelIndex={} ===",
                request.docPath(), request.modelIndex());
        SseEmitter emitter = new SseEmitter(600_000L);
        service.streamEdit(request, emitter);
        return emitter;
    }

    /**
     * 主动停止某一编辑轮次：断开模型流。{@code editId} 由前端在发起编辑时生成。
     * 找不到（已结束）返回失败提示（尽力而为，前端不据此报错）。
     */
    @PostMapping("/ai-edit/cancel")
    public ApiResponse<Void> cancelEdit(@RequestBody KbCancelRequest request) {
        log.info("=== 取消知识库 AI 编辑: editId={} ===", request.editId());
        boolean ok = service.cancel(request.editId());
        return ok ? ApiResponse.success("已请求取消") : ApiResponse.error("该编辑不在运行(可能已结束)");
    }
}
