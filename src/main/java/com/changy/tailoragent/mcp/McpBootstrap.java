package com.changy.tailoragent.mcp;

import com.changy.tailoragent.ModelConfig.event.ConfigChangedEvent;
import com.changy.tailoragent.ModelConfig.service.AppConfigService;
import com.changy.tailoragent.mcp.service.McpClientManager;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP 客户端的启动挂载与配置变更重连编排。
 * <p>
 * 把"何时同步"（生命周期/事件）与"如何同步"（{@link McpClientManager#syncClients}）分离：
 * <ul>
 *   <li>{@link ApplicationReadyEvent} —— 应用就绪后自动挂载所有启用的 MCP 服务；</li>
 *   <li>{@link ConfigChangedEvent} —— 用户保存配置后重新同步（幂等 diff，无变化则空转）。</li>
 * </ul>
 * 两者都在单线程后台 executor 上执行：建连可能较慢（远程 HTTP / stdio 子进程），
 * 放后台既不拖慢启动（契合"并行启动、耗时≈max"的设计），也不阻塞配置保存的 HTTP 响应。
 * 单线程保证多次同步串行、按提交顺序执行（最新配置最后生效）。
 */
@Component
public class McpBootstrap {

    private static final Logger log = LoggerFactory.getLogger(McpBootstrap.class);

    private final McpClientManager clientManager;
    private final AppConfigService configService;
    private final ExecutorService syncExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mcp-sync");
                t.setDaemon(true);
                return t;
            });

    public McpBootstrap(McpClientManager clientManager, AppConfigService configService) {
        this.clientManager = clientManager;
        this.configService = configService;
    }

    /** 应用就绪后自动挂载启用的 MCP 服务（后台执行，不阻塞启动与窗口创建）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("应用就绪，开始后台挂载 MCP 客户端...");
        submitSync("启动挂载");
    }

    /** 配置保存后重新同步 MCP 客户端（连接新增、重连变更、关闭删除/禁用）。 */
    @EventListener(ConfigChangedEvent.class)
    public void onConfigChanged(ConfigChangedEvent event) {
        submitSync("配置变更");
    }

    private void submitSync(String reason) {
        syncExecutor.submit(() -> {
            try {
                clientManager.syncClients(configService.getConfig().getMcpServers());
            } catch (Exception e) {
                log.warn("MCP 客户端同步异常（{}）: {}", reason, e.getMessage());
            }
        });
    }

    @PreDestroy
    void shutdown() {
        syncExecutor.shutdownNow();
    }
}
