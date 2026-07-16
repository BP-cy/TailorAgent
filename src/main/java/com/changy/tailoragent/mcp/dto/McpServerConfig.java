package com.changy.tailoragent.mcp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务配置 —— 描述一个外部 MCP 服务的连接方式。
 * <p>
 * 支持两种传输方式：
 * <ul>
 *   <li>{@code stdio} —— 本地子进程（如 Python/Node MCP server）</li>
 *   <li>{@code streamable_http} —— 远程 HTTP 端点（如 Tavily、Exa）</li>
 * </ul>
 * 持久化在 {@code app-config.json} 的 {@code mcpServers} 数组中。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpServerConfig implements Serializable {

    /** 唯一标识（如 "tavily"、"bocha"） */
    private String name = "";

    /** 传输方式：{@code "stdio"} 或 {@code "streamable_http"} */
    private String transportType = "stdio";

    // ========== stdio 字段 ==========

    /** 可执行命令（stdio 模式），如 "npx"、"uv"、"python" */
    private String command = "";

    /** 命令参数列表 */
    private List<String> args = new ArrayList<>();

    /** 子进程环境变量（如 BOCHA_API_KEY） */
    private Map<String, String> env = new HashMap<>();

    // ========== streamable_http 字段 ==========

    /** 远程 MCP 端点 URL（streamable_http 模式） */
    private String url = "";

    /** HTTP 请求头（如 Authorization） */
    private Map<String, String> headers = new HashMap<>();

    // ========== 通用 ==========

    /** 是否启用 */
    private boolean enabled = false;
}
