package com.changy.tailoragent.chat.service;

import com.changy.tailoragent.chat.entity.ChatEvent;
import com.changy.tailoragent.chat.entity.ChatTurn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文压缩服务 —— 把一个会话较早的对话历史(text)压缩成一条 {@code type=summary} 事件,
 * 由 {@link ChatContextProjector} 在投影时用摘要替代被覆盖的原文,从而把长会话的输入 token 压下来。
 * <p>
 * <b>为什么用 summary 事件而非 session 表字段</b>:{@link ChatEvent} 是「单表 + type 判别 + payload」
 * 的事件流模型,summary 天然契合 —— 持久化随事件流走、重开会话由 {@code loadEvents} 自动带回、
 * 一个会话可多次压缩(每次 append 一条新摘要,各记自己的覆盖范围),零迁移。
 * <p>
 * <b>滚动压缩</b>:若已存在上一份摘要,本次会把「上一份摘要正文 + 它之后新产生的对话」一起重新浓缩,
 * 避免摘要套摘要无限增厚。摘要的覆盖范围记在 payload 的 {@code coversUpToEventId}。
 * <p>
 * <b>与工作集正交</b>:文件最新内容由 {@link WorkingSetService} 提供,摘要只负责对话脉络/决策,
 * 不必把文件内容塞进摘要。本服务只压缩 {@code text} 事件;tool/reasoning 本就不进投影,无需处理。
 */
@Service
public class SessionCompactionService {

    private static final Logger log = LoggerFactory.getLogger(SessionCompactionService.class);

    private static final String PROMPT_COMPACT = "classpath:prompts/compact.md";

    private final SessionService sessionService;
    private final TokenEstimator tokenEstimator;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 摘要提示词模板(启动加载一次) */
    private final String compactPrompt;

    public SessionCompactionService(SessionService sessionService,
                                    TokenEstimator tokenEstimator,
                                    ResourceLoader resourceLoader) {
        this.sessionService = sessionService;
        this.tokenEstimator = tokenEstimator;
        this.compactPrompt = loadPrompt(resourceLoader, PROMPT_COMPACT);
    }

    /**
     * 压缩一个会话:保留最近 {@code protectTurns} 个轮次的原文,把更早的对话(含上一份摘要)
     * 压成一条新的 summary 事件。
     *
     * @param sessionId    会话 id
     * @param client       用于生成摘要的 ChatClient(由调用方按所选模型创建)
     * @param modelName    模型名(仅用于日志)
     * @param protectTurns 保护窗口:最近多少个轮次保持原文不压缩
     * @param trigger      触发来源:{@code "auto"} / {@code "manual"}(记入 payload)
     * @return 压缩结果;历史过短无可压缩内容时 {@code compacted=false}
     */
    public Result compact(Integer sessionId, ChatClient client, String modelName,
                          int protectTurns, String trigger) {
        List<ChatEvent> events = sessionService.loadEvents(sessionId);

        // 1) 上一份摘要(若有):取其覆盖截止 id,用于滚动压缩(只压它之后的新对话)
        ChatEvent priorSummary = null;
        for (ChatEvent e : events) {
            if ("summary".equals(e.getType())) {
                priorSummary = e;
            }
        }
        int priorCutoff = priorSummary != null ? coversUpTo(priorSummary) : -1;

        // 2) 确定保护窗口:最近 protectTurns 个轮次的 turnId 集合(按出现顺序)
        Set<Integer> distinctTurns = new LinkedHashSet<>();
        for (ChatEvent e : events) {
            if (e.getTurnId() != null) {
                distinctTurns.add(e.getTurnId());
            }
        }
        if (distinctTurns.size() <= protectTurns) {
            log.info("压缩跳过: 会话 {} 仅 {} 个轮次,不超过保护窗口 {}", sessionId, distinctTurns.size(), protectTurns);
            return Result.skipped();
        }
        List<Integer> turnList = new ArrayList<>(distinctTurns);
        Set<Integer> protectedTurns = new LinkedHashSet<>(
                turnList.subList(turnList.size() - protectTurns, turnList.size()));

        // 3) 收集待压缩的 text 事件(非保护轮次 + 在上一份摘要覆盖之后),并计算新覆盖截止 id
        //    cutoff = 非保护轮次里最大的事件 id —— 保护轮次更晚、id 更大,故 cutoff 必小于它们
        List<ChatEvent> toCompress = new ArrayList<>();
        int newCutoff = priorCutoff;
        for (ChatEvent e : events) {
            if (e.getTurnId() == null || protectedTurns.contains(e.getTurnId())) {
                continue;
            }
            Integer id = e.getId();
            if (id != null && id > newCutoff) {
                newCutoff = id; // 非保护轮次的最大 id
            }
            if ("text".equals(e.getType()) && id != null && id > priorCutoff) {
                toCompress.add(e);
            }
        }

        if (toCompress.isEmpty() && priorSummary == null) {
            log.info("压缩跳过: 会话 {} 没有可压缩的对话文本", sessionId);
            return Result.skipped();
        }

        // 4) 拼装待压缩文本:上一份摘要 + 本次新增对话
        StringBuilder dump = new StringBuilder();
        if (priorSummary != null && priorSummary.getContent() != null) {
            dump.append("【已有摘要(请将其与下文合并、重新浓缩)】\n")
                .append(priorSummary.getContent()).append("\n\n【新增对话】\n");
        }
        for (ChatEvent e : toCompress) {
            dump.append('[').append(roleLabel(e.getRole())).append("] ")
                .append(e.getContent() == null ? "" : e.getContent()).append("\n\n");
        }
        int tokensBefore = tokenEstimator.estimate(dump.toString());

        // 5) 调模型生成摘要(非流式阻塞 .call())
        String summary;
        try {
            summary = client.prompt()
                    .system(compactPrompt)
                    .user(dump.toString())
                    .call()
                    .content();
        } catch (RuntimeException ex) {
            log.warn("压缩摘要调用失败: sessionId={}, model={}, err={}", sessionId, modelName, ex.getMessage());
            throw ex;
        }
        if (summary == null || summary.isBlank()) {
            log.warn("压缩摘要为空,放弃本次压缩: sessionId={}", sessionId);
            return Result.skipped();
        }
        int tokensAfter = tokenEstimator.estimate(summary);

        // 6) 落一条 summary 事件(挂在独立的 compact 轮次下;type=summary 不在前端渲染)
        ChatTurn turn = sessionService.startTurn(sessionId, "compact");
        String payload = buildPayload(newCutoff, trigger, tokensBefore, tokensAfter);
        sessionService.appendSummaryEvent(turn, summary, payload);
        sessionService.finishTurn(turn, "done");

        log.info("上下文压缩完成: sessionId={}, trigger={}, 覆盖至事件 #{}, 压缩 {} 条对话 ~{} tokens → 摘要 ~{} tokens, 保护最近 {} 轮",
                sessionId, trigger, newCutoff, toCompress.size(), tokensBefore, tokensAfter, protectTurns);

        return new Result(true, turn, newCutoff, tokensBefore, tokensAfter);
    }

    /** 解析摘要 payload 里的 coversUpToEventId;缺失则回退到该事件自身 id */
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

    private String buildPayload(int coversUpToEventId, String trigger, int tokensBefore, int tokensAfter) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "coversUpToEventId", coversUpToEventId,
                    "trigger", trigger,
                    "tokensBefore", tokensBefore,
                    "tokensAfter", tokensAfter));
        } catch (Exception e) {
            // 兜底:最关键的是 coversUpToEventId,手拼一个合法 JSON
            return "{\"coversUpToEventId\":" + coversUpToEventId + ",\"trigger\":\"" + trigger + "\"}";
        }
    }

    private static String roleLabel(String role) {
        if (role == null) return "未知";
        return switch (role.toLowerCase()) {
            case "user" -> "用户";
            case "assistant" -> "助手";
            case "system" -> "系统";
            default -> role;
        };
    }

    private String loadPrompt(ResourceLoader resourceLoader, String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("提示词文件不存在: " + location);
        }
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            log.info("加载提示词: {} ({} chars)", location, content.length());
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("加载提示词失败: " + location, e);
        }
    }

    /**
     * 压缩结果。
     *
     * @param compacted         是否真的压缩了
     * @param compactTurn       承载 summary 事件的 compact 轮次(未压缩时为 null)
     * @param coversUpToEventId 摘要覆盖的截止事件 id(未压缩时为 -1)
     * @param tokensBefore      被压缩段估算 token
     * @param tokensAfter       摘要估算 token
     */
    public record Result(boolean compacted, ChatTurn compactTurn, int coversUpToEventId,
                         int tokensBefore, int tokensAfter) {
        static Result skipped() {
            return new Result(false, null, -1, 0, 0);
        }
    }
}
