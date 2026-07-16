package com.changy.tailoragent.chat.controller;

import com.changy.tailoragent.chat.entity.ChatEvent;
import com.changy.tailoragent.chat.entity.ChatSession;
import com.changy.tailoragent.chat.service.SessionService;
import com.changy.tailoragent.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 会话 API —— 会话列表、会话事件流的读取与删除。
 * <p>
 * 发送对话(产生新会话/轮次/事件)走 {@link ChatController};本控制器只读历史与管理会话。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 会话列表(侧边栏),按最后更新时间倒序 */
    @GetMapping
    public ApiResponse<List<ChatSession>> list() {
        return ApiResponse.success(sessionService.listSessions());
    }

    /** 某会话的完整事件流(自增 id 升序),前端据此一次性渲染 */
    @GetMapping("/{id}/events")
    public ApiResponse<List<ChatEvent>> events(@PathVariable Integer id) {
        log.info("GET /api/sessions/{}/events — 载入会话事件流", id);
        return ApiResponse.success(sessionService.loadVisibleEvents(id));
    }

    /**
     * 某会话最新的上下文占用 token 数 —— 前端切换/载入会话时回读,驱动占比条。
     * 无记录(尚未跑过轮次)时 contextTokens 为 null,前端显示「—」。
     */
    @GetMapping("/{id}/context-usage")
    public ApiResponse<Map<String, Integer>> contextUsage(@PathVariable Integer id) {
        Integer contextTokens = sessionService.latestContextTokens(id);
        return ApiResponse.success(Collections.singletonMap("contextTokens", contextTokens));
    }

    /** 删除会话 */
    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable Integer id) {
        log.info("DELETE /api/sessions/{} — 删除会话", id);
        sessionService.deleteSession(id);
        return ApiResponse.success("删除成功");
    }
}
