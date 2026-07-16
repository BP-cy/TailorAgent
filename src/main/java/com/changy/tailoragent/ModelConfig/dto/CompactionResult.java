package com.changy.tailoragent.ModelConfig.dto;

import lombok.Data;

/**
 * 上下文压缩结果 —— 返回给前端,用于提示与刷新占比条。
 */
@Data
public class CompactionResult {

    /** 是否真的执行了压缩(历史过短时为 false) */
    private boolean compacted;

    /** 被压缩段(含上一份摘要)的估算 token 数 */
    private int tokensBefore;

    /** 生成的摘要估算 token 数 */
    private int tokensAfter;

    /** 压缩后整轮上下文的估算占用 token(系统提示词 + 投影历史 + 工作集);未压缩时为 null */
    private Integer contextTokens;

    /** 给用户的提示文案 */
    private String message;
}
