package com.changy.tailoragent.ModelConfig.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 知识库向量化模型配置。
 *
 * <p>Embedding 与对话模型是两类端点，不能把对话模型名直接拿来调用
 * {@code /embeddings}。配置持久化在用户数据目录的 {@code app-config.json}，
 * 仓库和索引元数据均不保存真实 API Key。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingModelConfig implements Serializable {

    /** 默认每次 Embedding API 请求包含的文本条数。 */
    public static final int DEFAULT_BATCH_SIZE = 10;

    /** 防止误配置导致一次请求占用过多内存；供应商限制通常远小于该值。 */
    public static final int MAX_BATCH_SIZE = 2048;

    /** OpenAI 兼容 API 基地址（通常包含 /v1）。 */
    private String baseUrl = "";

    /** Embedding 模型名。 */
    private String modelName = "";

    /** API Key；允许兼容本地服务时为空。 */
    private String apiKey = "";

    /** 可选的固定输出维度；null 表示使用模型默认维度。 */
    private Integer dimensions;

    /**
     * 单次向量化条数，即一次 Embedding API 请求中 {@code input} 的文本分块数量。
     * 这不是一次索引的文件数量；一篇 Markdown 也可能拆成多个请求。
     */
    private Integer batchSize = DEFAULT_BATCH_SIZE;

    @JsonIgnore
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
                && modelName != null && !modelName.isBlank();
    }
}
