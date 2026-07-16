package com.changy.tailoragent.knowledge.mcp;

import com.changy.tailoragent.ModelConfig.dto.AppConfig;
import com.changy.tailoragent.ModelConfig.service.AppConfigService;
import com.changy.tailoragent.common.response.ApiResponse;
import com.changy.tailoragent.mcp.dto.McpConnectionStatus;
import com.changy.tailoragent.mcp.dto.McpServerConfig;
import com.changy.tailoragent.mcp.dto.McpServerStatusDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库 AI 编辑 agent 的 MCP 运行态查询 API。
 * <p>
 * 是主对话 {@link com.changy.tailoragent.mcp.controller.McpController} 的<b>独立副本</b>：
 * 合并逻辑（配置列表 × 实时状态 → 红绿灯）完全一致，只是数据来自 {@code kbMcpServers} 与
 * {@link KbMcpClientManager}。
 */
@RestController
@RequestMapping("/api/mcp")
public class KbMcpController {

    private final KbMcpClientManager clientManager;
    private final AppConfigService configService;

    public KbMcpController(KbMcpClientManager clientManager, AppConfigService configService) {
        this.clientManager = clientManager;
        this.configService = configService;
    }

    /**
     * 每个知识库 MCP 服务的连接状态（供前端红绿灯）。
     * <p>
     * 合并「配置列表」与「实时状态」：按配置顺序输出——未启用→DISABLED；
     * 启用且有实时状态→取之；启用但实时状态缺失（刚添加、异步同步尚未跑完）→CONNECTING。
     */
    @GetMapping("/kb-status")
    public ApiResponse<List<McpServerStatusDto>> status() {
        AppConfig config = configService.getConfig();
        List<McpServerConfig> servers = config.getKbMcpServers();

        // 实时状态按 name 索引
        Map<String, McpServerStatusDto> live = new LinkedHashMap<>();
        for (McpServerStatusDto dto : clientManager.getStatuses()) {
            live.put(dto.getName(), dto);
        }

        List<McpServerStatusDto> result = new ArrayList<>();
        if (servers != null) {
            for (McpServerConfig c : servers) {
                if (c == null || c.getName() == null || c.getName().isBlank()) continue;
                if (!c.isEnabled()) {
                    result.add(new McpServerStatusDto(c.getName(), c.getTransportType(),
                            McpConnectionStatus.DISABLED, null, 0L));
                    continue;
                }
                McpServerStatusDto known = live.get(c.getName());
                if (known != null) {
                    result.add(known);
                } else {
                    // 已启用但还没被同步线程处理到
                    result.add(new McpServerStatusDto(c.getName(), c.getTransportType(),
                            McpConnectionStatus.CONNECTING, null, 0L));
                }
            }
        }
        return ApiResponse.success(result);
    }
}
