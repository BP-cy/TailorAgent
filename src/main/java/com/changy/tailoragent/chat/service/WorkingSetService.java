package com.changy.tailoragent.chat.service;

import com.changy.tailoragent.chat.entity.ChatEvent;
import com.changy.tailoragent.tool.support.WorkspacePathResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作集(Working Set)投影 —— 把一个会话「最近 Read/Edit/Write 过的文件」以**磁盘最新全量内容**
 * 拼成一条 {@link SystemMessage},注入到每轮上下文,使模型无需重复读取同一批文件。
 * <p>
 * <b>为什么需要</b>:{@link ChatContextProjector} 是 Phase 1,{@code tool_call/tool_result} 不跨轮投影,
 * 因此新一轮开始时模型「忘了」上一轮读过哪些文件。本服务补上这块——但**不存文件内容快照**,
 * 而是只从会话事件流推断「哪些文件在工作集里」(成员归属),内容一律在投影时读磁盘最新,
 * 从根本上绕开「旧版本残留 / edit 后是否覆盖 / 外部改动」等版本陈旧问题。
 * <p>
 * <b>per-session</b>:成员来自<b>该会话自己的事件流</b>(而非进程级的 {@code ReadFileStateService},
 * 后者非会话隔离会串味),因此天然按会话隔离,且重启后可由持久化事件重建。
 * <p>
 * <b>有界</b>:按「最近引用优先」装入内容,累计超 token 预算后剩余文件降级为「引用占位」
 * (列出文件名,提示模型需要时重新 {@code read_file})。
 */
@Component
public class WorkingSetService {

    private static final Logger log = LoggerFactory.getLogger(WorkingSetService.class);

    /** 纳入工作集的文件类工具名(三者参数首位均为 filePath) */
    private static final Set<String> FILE_TOOLS = Set.of("read_file", "edit_file", "write_file");

    /** 单文件纳入内容的字节上限;超出则降级为引用占位,避免单个巨文件吃光预算 */
    private static final long PER_FILE_MAX_BYTES = 256 * 1024;

    /** 预算无效(模型未配 contextLength)时的兜底 token 预算 */
    private static final int DEFAULT_TOKEN_BUDGET = 30_000;

    /** 二进制扩展名:不纳入文本工作集 */
    private static final Set<String> BINARY_EXTS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "pdf", "zip", "gz", "tar",
            "jar", "class", "exe", "dll", "so", "dylib", "bin", "mp3", "mp4", "mov", "woff", "woff2", "ttf");

    /** 从(可能被截断的)args 文本里抽 filePath 的兜底正则:filePath 在参数对象首位,尾部截断不影响 */
    private static final Pattern FILE_PATH_PATTERN =
            Pattern.compile("\"filePath\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private final WorkspacePathResolver pathResolver;
    private final TokenEstimator tokenEstimator;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkingSetService(WorkspacePathResolver pathResolver, TokenEstimator tokenEstimator) {
        this.pathResolver = pathResolver;
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 由会话事件流构建工作集消息。
     *
     * @param events      会话完整事件流(顺序)
     * @param tokenBudget 工作集内容的 token 预算;{@code <=0} 时回退到 {@link #DEFAULT_TOKEN_BUDGET}
     * @return 工作集 SystemMessage;无任何可载入文件时为 {@link Optional#empty()}
     */
    public Optional<Message> build(List<ChatEvent> events, int tokenBudget) {
        if (events == null || events.isEmpty()) {
            return Optional.empty();
        }
        int budget = tokenBudget > 0 ? tokenBudget : DEFAULT_TOKEN_BUDGET;

        // 1) 成员归属:扫描 tool_call,抽出文件工具的 filePath → 绝对路径(后出现的覆盖,记录 recency 顺序)
        Set<String> errorCallIds = collectErrorCallIds(events);
        // key=绝对路径,value=递增序号(越大越近);LinkedHashMap 仅为稳定,排序在物化阶段
        Map<String, Integer> pathOrder = new LinkedHashMap<>();
        int order = 0;
        for (ChatEvent e : events) {
            if (!"tool_call".equals(e.getType())) {
                continue;
            }
            ToolCall call = parseToolCall(e.getPayload());
            if (call == null || !FILE_TOOLS.contains(call.toolName)
                    || (call.callId != null && errorCallIds.contains(call.callId))) {
                continue;
            }
            String filePath = extractFilePath(call.args);
            if (filePath == null || filePath.isBlank()) {
                continue;
            }
            String abs = resolveAbs(filePath);
            if (abs != null) {
                pathOrder.put(abs, order++); // 重复路径以最新序号覆盖
            }
        }
        if (pathOrder.isEmpty()) {
            return Optional.empty();
        }

        // 2) 物化:按 recency 倒序(最近用的优先保内容),累计超预算后降级为占位
        List<String> orderedPaths = new ArrayList<>(pathOrder.keySet());
        orderedPaths.sort((a, b) -> Integer.compare(pathOrder.get(b), pathOrder.get(a)));

        StringBuilder body = new StringBuilder();
        List<String> loadedPaths = new ArrayList<>(); // 已载入内容的文件
        List<String> demoted = new ArrayList<>(); // 未载入内容的文件(占位)
        int usedTokens = 0;

        for (String abs : orderedPaths) {
            Path path = Paths.get(abs);
            String content = readIfEligible(path);
            if (content == null) {
                demoted.add(abs); // 不存在 / 目录 / 二进制 / 过大
                continue;
            }
            int cost = tokenEstimator.estimate(content) + 8; // 8 ≈ 标题/围栏固定开销
            if (usedTokens + cost > budget) {
                demoted.add(abs); // 预算耗尽:最久未用的被挤出
                continue;
            }
            usedTokens += cost;
            loadedPaths.add(abs);
            body.append("## ").append(abs).append('\n')
                .append("```\n").append(content);
            if (!content.endsWith("\n")) {
                body.append('\n');
            }
            body.append("```\n\n");
        }

        if (loadedPaths.isEmpty() && demoted.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder msg = new StringBuilder();
        msg.append("# 当前工作集（以下为相关文件的磁盘最新内容，无需重复读取）\n\n");
        if (!loadedPaths.isEmpty()) {
            msg.append(body);
        }
        if (!demoted.isEmpty()) {
            msg.append("---\n以下文件曾访问但未载入上下文（需要时用 read_file 重新读取）：\n");
            for (String p : demoted) {
                msg.append("- ").append(p).append('\n');
            }
        }

        log.info("工作集: 载入 {} 文件 / 占位 {} 文件, ~{} tokens (预算 {})\n  载入: {}\n  占位: {}",
                loadedPaths.size(), demoted.size(), usedTokens, budget, loadedPaths, demoted);
        return Optional.of(new SystemMessage(msg.toString()));
    }

    /** 收集失败工具调用的 callId,用于跳过读取失败的文件 */
    private Set<String> collectErrorCallIds(List<ChatEvent> events) {
        Set<String> ids = new HashSet<>();
        for (ChatEvent e : events) {
            if (!"tool_result".equals(e.getType()) || !"error".equals(e.getStatus())) {
                continue;
            }
            JsonNode node = readTree(e.getPayload());
            if (node != null && node.hasNonNull("callId")) {
                ids.add(node.get("callId").asText());
            }
        }
        return ids;
    }

    /** 解析 tool_call 事件的 payload(外层 JSON 完整,args 值可能被截断) */
    private ToolCall parseToolCall(String payload) {
        JsonNode node = readTree(payload);
        if (node == null) {
            return null;
        }
        ToolCall c = new ToolCall();
        c.callId = node.hasNonNull("callId") ? node.get("callId").asText() : null;
        c.toolName = node.hasNonNull("toolName") ? node.get("toolName").asText() : null;
        c.args = node.hasNonNull("args") ? node.get("args").asText() : null;
        return c;
    }

    /** 从 args(JSON 文本,可能被截断)抽取 filePath:优先正常 parse,失败回退正则 */
    private String extractFilePath(String args) {
        if (args == null || args.isBlank()) {
            return null;
        }
        JsonNode node = readTree(args);
        if (node != null && node.hasNonNull("filePath")) {
            return node.get("filePath").asText();
        }
        // args 被截断成非法 JSON:正则抽首位 filePath,再用 JSON 反转义
        Matcher m = FILE_PATH_PATTERN.matcher(args);
        if (m.find()) {
            return unescapeJsonString(m.group(1));
        }
        return null;
    }

    /** 经 WorkspacePathResolver 统一为规范化绝对路径字符串(与工具侧 key 一致);非法路径返回 null */
    private String resolveAbs(String filePath) {
        try {
            return pathResolver.resolve(filePath).toString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 读取符合条件的文本文件全量内容(LF 规范化);不存在/目录/二进制/过大 → null */
    private String readIfEligible(Path path) {
        try {
            if (!Files.exists(path) || Files.isDirectory(path)) {
                return null;
            }
            if (BINARY_EXTS.contains(extension(path)) || Files.size(path) > PER_FILE_MAX_BYTES) {
                return null;
            }
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            return raw.replace("\r\n", "\n").replace("\r", "\n");
        } catch (IOException | RuntimeException ex) {
            log.debug("工作集读取文件失败,降级为占位: {} ({})", path, ex.getMessage());
            return null;
        }
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    /** 用 Jackson 把被正则截下的 JSON 字符串字面量反转义(处理换行/引号/反斜杠/Unicode 转义等) */
    private String unescapeJsonString(String raw) {
        try {
            return mapper.readValue("\"" + raw + "\"", String.class);
        } catch (IOException ex) {
            return raw; // 反转义失败则用原文,尽力而为
        }
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (IOException ex) {
            return null;
        }
    }

    /** tool_call payload 的轻量载体 */
    private static final class ToolCall {
        String callId;
        String toolName;
        String args;
    }
}
