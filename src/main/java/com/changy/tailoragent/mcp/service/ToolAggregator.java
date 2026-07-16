package com.changy.tailoragent.mcp.service;

import com.changy.tailoragent.ModelConfig.dto.ContextConfig;
import com.changy.tailoragent.ModelConfig.service.AppConfigService;
import com.changy.tailoragent.chat.service.TokenEstimator;
import com.changy.tailoragent.mcp.dto.McpServerConfig;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 工具回调聚合器 —— 架构唯一收敛点。
 * <p>
 * 将本地 {@code @Tool} 方法和外部 MCP 服务发现的工具统一合并为
 * {@link ToolCallback} 数组，供 {@code ChatServiceImpl} 调模型时传入。
 * <p>
 * MCP 客户端懒初始化：首次 {@link #resolveAll()} 时才建连，不拖慢应用启动。
 * 单个 MCP server 建连失败只 warn 并跳过，不影响其他工具。
 */
@Service
public class ToolAggregator {

    private static final Logger log = LoggerFactory.getLogger(ToolAggregator.class);

    private final List<Object> localToolBeans;
    private final McpClientManager mcpClientManager;
    private final AppConfigService configService;
    /** token 估算器:传给装饰器做工具结果轮内封顶 */
    private final TokenEstimator tokenEstimator;

    /**
     * @param localToolBeans 所有含 {@code @Tool} 方法的 Spring Bean（由 ToolConfig 收集）
     */
    public ToolAggregator(List<Object> localToolBeans,
                          McpClientManager mcpClientManager,
                          AppConfigService configService,
                          TokenEstimator tokenEstimator) {
        this.localToolBeans = localToolBeans != null ? localToolBeans : List.of();
        this.mcpClientManager = mcpClientManager;
        this.configService = configService;
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 聚合所有可用工具。
     * <p>
     * 每次调用都会重新收集（MCP server 列表可能被用户修改），
     * 但 MCP 客户端本身有缓存不会重复建连。
     */
    public ToolCallback[] resolveAll() {
        List<ToolCallback> wrapped = new ArrayList<>();

        // 工具结果轮内封顶上限:每轮实时读 config,改动即时生效(本方法每轮调用)
        ContextConfig ctx = configService.getConfig().getContext();
        int perCallMax = ctx.getMaxToolResultTokens();
        int tightenedMax = ctx.getTightenedToolResultTokens();

        // 1) 本地 @Tool —— 包裹事件装饰器,来源标记 local
        List<ToolCallback> local = new ArrayList<>();
        collectLocalTools(local);
        for (ToolCallback cb : local) {
            wrapped.add(new EventEmittingToolCallback(cb, "local", tokenEstimator, perCallMax, tightenedMax));
        }

        // 2) 外部 MCP 服务 —— 包裹事件装饰器,来源标记 mcp
        List<ToolCallback> mcp = new ArrayList<>();
        collectMcpTools(mcp);
        for (ToolCallback cb : mcp) {
            wrapped.add(new EventEmittingToolCallback(cb, "mcp", tokenEstimator, perCallMax, tightenedMax));
        }

        // [验证用] 打印本轮提供给模型的工具清单(仅"告知"模型有这些,不代表会调用)
        if (!wrapped.isEmpty()) {
            List<String> names = new ArrayList<>(wrapped.size());
            for (ToolCallback cb : wrapped) {
                names.add(cb.getToolDefinition().name());
            }
            log.info("[工具清单] 本轮提供给模型 {} 个工具: {}", names.size(), names);
        }
        return wrapped.toArray(new ToolCallback[0]);
    }

    private void collectLocalTools(List<ToolCallback> target) {
        for (Object bean : localToolBeans) {
            try {
                ToolCallback[] callbacks = ToolCallbacks.from(bean);
                target.addAll(Arrays.asList(callbacks));
            } catch (Exception e) {
                log.warn("解析本地工具 Bean 失败: {} — {}", bean.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    private void collectMcpTools(List<ToolCallback> target) {
        List<McpServerConfig> servers = configService.getConfig().getMcpServers();
        if (servers == null || servers.isEmpty()) return;

        List<McpSyncClient> activeClients = new ArrayList<>();
        for (McpServerConfig config : servers) {
            if (!config.isEnabled()) continue;
            try {
                McpSyncClient client = mcpClientManager.getOrCreateClient(config);
                activeClients.add(client);
            } catch (Exception e) {
                log.warn("MCP 客户端连接失败，已跳过: name={}, err={}", config.getName(), e.getMessage());
            }
        }

        if (activeClients.isEmpty()) return;

        for (McpSyncClient client : activeClients) {
            try {
                var provider = new SyncMcpToolCallbackProvider(client);
                ToolCallback[] callbacks = provider.getToolCallbacks();
                target.addAll(Arrays.asList(callbacks));
            } catch (Exception e) {
                log.warn("获取 MCP 工具列表失败: {}", e.getMessage());
            }
        }
    }
}
