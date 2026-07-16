package com.changy.tailoragent.mcp.dto;

/**
 * MCP 服务的连接状态 —— 供前端展示红绿灯。
 * <ul>
 *   <li>{@code CONNECTED}  —— 已建连（绿灯）</li>
 *   <li>{@code FAILED}     —— 建连失败（红灯，附 lastError）</li>
 *   <li>{@code CONNECTING} —— 建连中（刚添加、异步同步尚未完成，琥珀色）</li>
 *   <li>{@code DISABLED}   —— 用户停用或已删除（灰色）</li>
 * </ul>
 */
public enum McpConnectionStatus {
    CONNECTED,
    FAILED,
    CONNECTING,
    DISABLED
}
