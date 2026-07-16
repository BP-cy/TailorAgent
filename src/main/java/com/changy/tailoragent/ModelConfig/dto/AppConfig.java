package com.changy.tailoragent.ModelConfig.dto;

import com.changy.tailoragent.mcp.dto.McpServerConfig;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用 AI 配置 —— 持久化到 AppPaths.dataDir()/app-config.json。
 * <p>
 * 存储用户可用的模型列表，前端 ChatPanel 每轮对话前可选择使用哪个模型。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppConfig {

    /** 可用对话模型列表 */
    private List<ChatModel> availableChatModels = new ArrayList<>();

    /** 可用 OCR 模型列表 */
    private List<OCRModel> availableOCRModels = new ArrayList<>();

    /** 知识库 BM25 + KNN 混合检索使用的独立 Embedding 模型。 */
    private EmbeddingModelConfig embeddingModel = new EmbeddingModelConfig();

    /** 已配置的 MCP 服务列表（主对话 agent 用） */
    private List<McpServerConfig> mcpServers = new ArrayList<>();

    /** 知识库 AI 编辑 agent 专用的 MCP 服务列表（与主对话隔离，独立连接池/状态，见 KbMcpClientManager） */
    private List<McpServerConfig> kbMcpServers = new ArrayList<>();

    /** 工作目录路径，null 或空字符串表示未设置 */
    private String workingDir;

    /** 上下文管理可调参数(自动压缩阈值/保护轮次/工作集预算/工具输出上限);缺省为全默认对象 */
    private ContextConfig context = new ContextConfig();
}
