package com.changy.tailoragent.knowledge.mcp;

import com.changy.tailoragent.mcp.dto.McpConnectionStatus;
import com.changy.tailoragent.mcp.dto.McpServerConfig;
import com.changy.tailoragent.mcp.dto.McpServerStatusDto;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库 AI 编辑 agent 专用的 MCP 客户端生命周期管理器。
 * <p>
 * 是主对话 {@link com.changy.tailoragent.mcp.service.McpClientManager} 的<b>独立副本</b>：
 * 完全隔离的连接池与状态注册表，只服务于 {@code kbMcpServers} 配置，避免与主对话共享连接、
 * 相互影响。逻辑与主副本一一对应（三路 diff 同步 + 懒加载兜底 + {@code @PreDestroy} 释放），
 * 仅用途/作用域不同。复用共享 DTO（{@link McpServerConfig} / {@link McpServerStatusDto} /
 * {@link McpConnectionStatus}）。
 */
@Service
public class KbMcpClientManager {

    private static final Logger log = LoggerFactory.getLogger(KbMcpClientManager.class);

    private final ConcurrentHashMap<String, Managed> clients = new ConcurrentHashMap<>();

    /**
     * 每个 server 的连接状态快照 —— 供 {@code GET /api/mcp/kb-status} 读取展示红绿灯。
     * <p>
     * 写仅发生在单 {@code kb-mcp-sync} 线程的 {@link #syncClients(List)}（synchronized）内，
     * 读发生在 HTTP 线程；{@code ConcurrentHashMap} + 不可变 {@link StatusEntry} 即可，无需新锁。
     */
    private final ConcurrentHashMap<String, StatusEntry> statuses = new ConcurrentHashMap<>();

    /** 已建连的客户端 + 其建连时所用的配置快照（用于变更检测） */
    private record Managed(McpServerConfig config, McpSyncClient client) {}

    /** 连接状态条目（不可变） */
    private record StatusEntry(McpConnectionStatus status, String transportType, String lastError, long updatedAt) {}

    /**
     * 获取或创建 MCP 客户端。
     * <p>
     * 建连失败时抛出 {@link RuntimeException}，由调用方决定跳过还是终止。
     */
    public McpSyncClient getOrCreateClient(McpServerConfig config) {
        return clients.computeIfAbsent(config.getName(), name -> new Managed(config, buildClient(config))).client();
    }

    /**
     * 按期望配置全量同步客户端 —— 启动自动挂载与配置变更重连的唯一入口。
     * <p>
     * 三路 diff（幂等）：
     * <ul>
     *   <li>缓存里有、期望里没有（被删除或禁用）→ 关闭</li>
     *   <li>期望里有、缓存里没有 → 建连</li>
     *   <li>两边都有但配置已变（{@code !equals}）→ 先关后重建；未变则保持不动</li>
     * </ul>
     * 单个 server 建连失败只 warn 跳过，不影响其他。{@code synchronized} 避免启动同步、
     * 配置变更同步与懒加载之间的竞态。
     */
    public synchronized void syncClients(List<McpServerConfig> servers) {
        // 期望集合：仅启用且有合法名称的 server
        Map<String, McpServerConfig> desired = new LinkedHashMap<>();
        if (servers != null) {
            for (McpServerConfig c : servers) {
                if (c != null && c.isEnabled() && c.getName() != null && !c.getName().isBlank()) {
                    desired.put(c.getName(), c);
                }
            }
        }

        // 1) 关闭已不在期望集合中的连接（被删除或禁用）
        for (String name : new ArrayList<>(clients.keySet())) {
            if (!desired.containsKey(name)) {
                closeClient(name);
                statuses.put(name, new StatusEntry(McpConnectionStatus.DISABLED,
                        transportOf(servers, name), null, now()));
            }
        }
        // 期望集合外、但配置里存在的（停用项）也标记为 DISABLED，便于前端展示灰灯
        if (servers != null) {
            for (McpServerConfig c : servers) {
                if (c != null && c.getName() != null && !desired.containsKey(c.getName())) {
                    statuses.put(c.getName(), new StatusEntry(McpConnectionStatus.DISABLED,
                            c.getTransportType(), null, now()));
                }
            }
        }

        // 2) 新增 / 配置变更的连接
        for (McpServerConfig config : desired.values()) {
            Managed existing = clients.get(config.getName());
            if (existing != null && existing.config().equals(config)) {
                continue; // 配置未变，保持
            }
            if (existing != null) {
                closeClient(config.getName()); // 配置已变，先关后重建
            }
            statuses.put(config.getName(), new StatusEntry(McpConnectionStatus.CONNECTING,
                    config.getTransportType(), null, now()));
            try {
                clients.put(config.getName(), new Managed(config, buildClient(config)));
                statuses.put(config.getName(), new StatusEntry(McpConnectionStatus.CONNECTED,
                        config.getTransportType(), null, now()));
            } catch (Exception e) {
                log.warn("知识库 MCP 客户端同步失败，已跳过: name={}, err={}", config.getName(), e.getMessage());
                statuses.put(config.getName(), new StatusEntry(McpConnectionStatus.FAILED,
                        config.getTransportType(), e.getMessage(), now()));
            }
        }
    }

    /** 当前所有 server 的连接状态快照（仅含曾被 syncClients 处理过的 server） */
    public List<McpServerStatusDto> getStatuses() {
        List<McpServerStatusDto> result = new ArrayList<>(statuses.size());
        statuses.forEach((name, e) -> result.add(new McpServerStatusDto(
                name, e.transportType(), e.status(), e.lastError(), e.updatedAt())));
        return result;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** 从配置列表里查某 name 的 transportType，查不到返回 null（仅用于状态展示） */
    private static String transportOf(List<McpServerConfig> servers, String name) {
        if (servers == null) return null;
        for (McpServerConfig c : servers) {
            if (c != null && name.equals(c.getName())) return c.getTransportType();
        }
        return null;
    }

    /** 获取所有已连接的 MCP 客户端 */
    public List<McpSyncClient> getActiveClients() {
        List<McpSyncClient> result = new ArrayList<>(clients.size());
        for (Managed m : clients.values()) {
            result.add(m.client());
        }
        return result;
    }

    /** 关闭并移除指定客户端 */
    public void closeClient(String name) {
        Managed managed = clients.remove(name);
        if (managed != null) {
            try {
                managed.client().close();
                log.info("知识库 MCP 客户端已关闭: {}", name);
            } catch (Exception e) {
                log.warn("关闭知识库 MCP 客户端异常: {} — {}", name, e.getMessage());
            }
        }
    }

    /** 应用关闭时释放所有 MCP 连接 */
    @PreDestroy
    void closeAll() {
        if (clients.isEmpty()) return;
        log.info("正在关闭 {} 个知识库 MCP 客户端...", clients.size());
        List<String> names = new ArrayList<>(clients.keySet());
        for (String name : names) {
            closeClient(name);
        }
    }

    private McpSyncClient buildClient(McpServerConfig config) {
        log.info("正在初始化知识库 MCP 客户端: name={}, transport={}", config.getName(), config.getTransportType());
        return switch (config.getTransportType()) {
            case "stdio" -> buildStdioClient(config);
            case "streamable_http" -> buildStreamableHttpClient(config);
            case "sse" -> buildSseClient(config);
            default -> throw new IllegalArgumentException(
                    "不支持的 MCP 传输方式: " + config.getTransportType());
        };
    }

    private McpSyncClient buildStdioClient(McpServerConfig config) {
        // Windows 环境：通过 cmd.exe /c 启动子进程
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("/c");
        cmdArgs.add(config.getCommand());
        cmdArgs.addAll(config.getArgs());

        var paramsBuilder = ServerParameters.builder("cmd.exe")
                .args(cmdArgs);
        // 把用户配置的环境变量（如 TAVILY_API_KEY）传给子进程；
        // builder 会与默认安全环境变量（含 PATH）合并，不会覆盖 PATH。
        if (config.getEnv() != null && !config.getEnv().isEmpty()) {
            paramsBuilder.env(config.getEnv());
        }
        var serverParams = paramsBuilder.build();

        var transport = new StdioClientTransport(serverParams, McpJsonDefaults.getMapper());

        var clientInfo = new McpSchema.Implementation("TailorAgent", "1.0.0");
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(clientInfo)
                .build();
        client.initialize();
        log.info("知识库 MCP stdio 客户端已连接: {}", config.getName());
        return client;
    }

    /**
     * Streamable HTTP 传输（如高德 {@code /mcp} 端点、Tavily {@code /mcp}）。
     * <p>
     * 注意：{@code transportType=streamable_http} 必须用 {@link HttpClientStreamableHttpTransport}，
     * 不能用 SSE 传输——两者协议不同，端点对不上会直接连不通。
     */
    private McpSyncClient buildStreamableHttpClient(McpServerConfig config) {
        var builder = HttpClientStreamableHttpTransport.builder(config.getUrl());
        // 把配置里的自定义请求头（如 Authorization）加到每个请求上
        if (config.getHeaders() != null && !config.getHeaders().isEmpty()) {
            builder.httpRequestCustomizer((req, method, uri, body, ctx) ->
                    config.getHeaders().forEach(req::header));
        }
        var transport = builder.build();

        var clientInfo = new McpSchema.Implementation("TailorAgent", "1.0.0");
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(clientInfo)
                .build();
        client.initialize();
        log.info("知识库 MCP streamable_http 客户端已连接: {}", config.getName());
        return client;
    }

    /**
     * SSE 传输（如高德 {@code /sse} 端点）。鉴权 key 一般放在 URL query 参数里。
     */
    private McpSyncClient buildSseClient(McpServerConfig config) {
        var builder = HttpClientSseClientTransport.builder(config.getUrl());
        if (config.getHeaders() != null && !config.getHeaders().isEmpty()) {
            builder.httpRequestCustomizer((req, method, uri, body, ctx) ->
                    config.getHeaders().forEach(req::header));
        }
        var transport = builder.build();

        var clientInfo = new McpSchema.Implementation("TailorAgent", "1.0.0");
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(clientInfo)
                .build();
        client.initialize();
        log.info("知识库 MCP sse 客户端已连接: {}", config.getName());
        return client;
    }
}
