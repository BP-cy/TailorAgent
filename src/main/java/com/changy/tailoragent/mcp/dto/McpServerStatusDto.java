package com.changy.tailoragent.mcp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP 服务连接状态 DTO —— {@code GET /api/mcp/status} 的返回元素。
 * 前端据此渲染每个服务的名字与红绿灯。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpServerStatusDto {

    /** 服务名（对应 McpServerConfig.name） */
    private String name;

    /** 传输方式：stdio / streamable_http / sse */
    private String transportType;

    /** 连接状态 */
    private McpConnectionStatus status;

    /** 失败原因，仅 status=FAILED 时非空 */
    private String lastError;

    /** 状态更新时间（epoch 毫秒） */
    private long updatedAt;
}
