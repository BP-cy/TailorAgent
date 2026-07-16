package com.changy.tailoragent.ModelConfig.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 手动压缩上下文请求 —— 用户在对话前主动触发,把较早的对话历史压缩成摘要。
 * <p>
 * {@code modelIndex} 指定用 {@code availableChatModels} 中第几个模型来生成摘要(与对话同一套模型)。
 */
@Data
public class CompactRequest {

    /** 要压缩的会话 id */
    @NotNull(message = "sessionId 不能为空")
    private Integer sessionId;

    /** 生成摘要所用模型在配置列表中的索引 */
    private int modelIndex;
}
