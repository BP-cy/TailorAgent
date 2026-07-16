package com.changy.tailoragent.ModelConfig.config;

import com.changy.tailoragent.ModelConfig.dto.ProviderInfo;
import com.changy.tailoragent.common.exception.BusinessException;
import lombok.Getter;

import java.util.List;

/**
 * AI 厂商预设 —— 国内主流 OpenAI 协议兼容的大模型厂商。
 * <p>
 * 每个常量携带：唯一标识、中文名、API 基地址、可用模型列表。
 * 模型列表第一个为默认模型。Spring AI 2.0.0 底层使用 OpenAI 官方 Java SDK，
 * URL 拼接方式为 {@code baseUrl + "/chat/completions"}，因此 baseUrl 须直接
 * 包含路径前缀（如 {@code /v1}）。
 */
@Getter
public enum ProviderPreset {

    BAILIAN ("bailian",  "阿里百炼",   "https://llm-cx4ppwj2yuqysnu2.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
            List.of("qwen3.7-max", "ZHIPU/GLM-5.2", "kimi/kimi-k2.7-code-highspeed")),
    DEEPSEEK("deepseek", "DeepSeek",    "https://api.deepseek.com",
            List.of("deepseek-v4-pro", "deepseek-v4-flash"));

    private final String id;
    private final String name;
    private final String baseUrl;
    private final List<String> models;

    ProviderPreset(String id, String name, String baseUrl, List<String> models) {
        this.id = id;
        this.name = name;
        this.baseUrl = baseUrl;
        this.models = models;
    }

    /** 默认模型 —— 模型列表中的第一个 */
    public String getDefaultModel() {
        return models.getFirst();
    }

    /** 转为前端使用的摘要对象 */
    public ProviderInfo toInfo() {
        return new ProviderInfo(id, name, baseUrl, getDefaultModel(), models);
    }

    /**
     * 根据 ID 查找预设。
     *
     * @throws BusinessException 当厂商 ID 不在预设列表中时抛出 ——
     *         通常是前端传了错误的 providerId，也可能是枚举中遗漏了厂商或 baseUrl 配置有误
     */
    public static ProviderPreset fromId(String id) {
        for (ProviderPreset p : values()) {
            if (p.id.equals(id)) return p;
        }
        throw new BusinessException("不支持的AI厂商: " + id + "，请检查厂商ID是否正确，或联系开发者确认该厂商的API地址是否已配置");
    }
}