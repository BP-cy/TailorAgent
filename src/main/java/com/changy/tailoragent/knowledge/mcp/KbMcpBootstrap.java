package com.changy.tailoragent.knowledge.mcp;

import com.changy.tailoragent.ModelConfig.event.ConfigChangedEvent;
import com.changy.tailoragent.ModelConfig.service.AppConfigService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 知识库 AI 编辑 agent 的 MCP 启动挂载与配置变更重连编排。
 * <p>
 * 是主对话 {@link com.changy.tailoragent.mcp.McpBootstrap} 的<b>独立副本</b>：同样监听
 * {@link ApplicationReadyEvent}（启动挂载）与 {@link ConfigChangedEvent}（配置变更重连），
 * 但同步的是 {@code config.getKbMcpServers()}，作用于 {@link KbMcpClientManager}（独立连接池）。
 * 独立的单线程后台 executor（{@code kb-mcp-sync}）保证两条 MCP 链路互不干扰、各自串行。
 */
@Component
public class KbMcpBootstrap {

    private static final Logger log = LoggerFactory.getLogger(KbMcpBootstrap.class);

    private final KbMcpClientManager clientManager;
    private final AppConfigService configService;
    private final ExecutorService syncExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "kb-mcp-sync");
                t.setDaemon(true);
                return t;
            });

    public KbMcpBootstrap(KbMcpClientManager clientManager, AppConfigService configService) {
        this.clientManager = clientManager;
        this.configService = configService;
    }

    /** 应用就绪后自动挂载启用的知识库 MCP 服务（后台执行，不阻塞启动与窗口创建）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("应用就绪，开始后台挂载知识库 MCP 客户端...");
        submitSync("启动挂载");
    }

    /** 配置保存后重新同步知识库 MCP 客户端（连接新增、重连变更、关闭删除/禁用）。 */
    @EventListener(ConfigChangedEvent.class)
    public void onConfigChanged(ConfigChangedEvent event) {
        submitSync("配置变更");
    }

    private void submitSync(String reason) {
        syncExecutor.submit(() -> {
            try {
                clientManager.syncClients(configService.getConfig().getKbMcpServers());
            } catch (Exception e) {
                log.warn("知识库 MCP 客户端同步异常（{}）: {}", reason, e.getMessage());
            }
        });
    }

    @PreDestroy
    void shutdown() {
        syncExecutor.shutdownNow();
    }
}
