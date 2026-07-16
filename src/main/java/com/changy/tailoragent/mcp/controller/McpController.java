package com.changy.tailoragent.mcp.controller;

import com.changy.tailoragent.ModelConfig.dto.AppConfig;
import com.changy.tailoragent.ModelConfig.service.AppConfigService;
import com.changy.tailoragent.common.response.ApiResponse;
import com.changy.tailoragent.mcp.dto.McpConnectionStatus;
import com.changy.tailoragent.mcp.dto.McpServerConfig;
import com.changy.tailoragent.mcp.dto.McpServerStatusDto;
import com.changy.tailoragent.mcp.service.McpClientManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 运行态查询 API。
 * <p>
 * 与 {@code ConfigController}（管 app-config.json 读写）分开：MCP 的实时连接状态
 * 来自 {@link McpClientManager} 的内存注册表，是运行态而非配置。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final McpClientManager clientManager;
    private final AppConfigService configService;

    public McpController(McpClientManager clientManager, AppConfigService configService) {
        this.clientManager = clientManager;
        this.configService = configService;
    }

    /**
     * 每个 MCP 服务的连接状态（供前端红绿灯）。
     * <p>
     * 合并「配置列表」与「实时状态」：按配置顺序输出——未启用→DISABLED；
     * 启用且有实时状态→取之；启用但实时状态缺失（刚添加、异步同步尚未跑完）→CONNECTING。
     */
    @GetMapping("/status")
    public ApiResponse<List<McpServerStatusDto>> status() {
        AppConfig config = configService.getConfig();
        List<McpServerConfig> servers = config.getMcpServers();

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
