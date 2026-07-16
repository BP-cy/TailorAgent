package com.changy.tailoragent.ModelConfig.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * OCR 模型配置 —— 与 ChatModel 结构一致，独立列表。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OCRModel implements Serializable {

    /** API 基地址（含 /v1 等路径前缀） */
    private String baseUrl = "";

    /** 模型名 */
    private String modelName = "";

    /** API Key */
    private String apiKey = "";

    /** 前端展示名称 */
    private String displayName = "";

    /** 来源：preset（预设厂商）或 custom（自定义） */
    private String source = "custom";
}
