package com.changy.tailoragent.ModelConfig.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 厂商摘要信息 —— 返回给前端下拉框使用。
 * <p>
 * {@code models} 为该厂商支持的模型列表，第一个为默认模型。
 */
@Data
@AllArgsConstructor
public class ProviderInfo {
    private String id;
    private String name;
    private String baseUrl;
    private String defaultModel;
    /** 该厂商支持的全部模型列表 */
    private List<String> models;
}