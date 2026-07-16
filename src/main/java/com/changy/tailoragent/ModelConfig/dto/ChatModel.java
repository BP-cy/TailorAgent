package com.changy.tailoragent.ModelConfig.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 对话模型配置 —— 用户可添加多个，每轮对话前自行选择。
 * <p>
 * 纯 POJO，由 Jackson 序列化到 app-config.json 的 availableChatModels 数组中。
 * 预设厂商通过 ProviderPreset 快速填充 baseUrl，自定义则手动填写。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatModel implements Serializable {

    /** API 基地址（含 /v1 等路径前缀） */
    private String baseUrl = "";

    /** 模型名 */
    private String modelName = "";

    /** API Key */
    private String apiKey = "";

    /** 前端展示名称（如 "阿里百炼 qwen-plus"） */
    private String displayName = "";

    /** 来源：preset（预设厂商）或 custom（自定义） */
    private String source = "custom";

    /** 上下文窗口长度（token）—— 上下文占比 UI 的分母 */
    private Integer contextLength = 200000;

    /** 最大输入长度（token）—— 预留：未来历史压缩/裁剪的触发阈值，本期仅存储 */
    private Integer maxInputTokens = 168000;

    /** 最大输出长度（token）—— 预留：未来请求 maxTokens 上限，本期仅存储 */
    private Integer maxOutputTokens = 32000;
}
