package com.changy.tailoragent.ModelConfig.dto;

import lombok.Data;

/**
 * 上下文管理可调参数 —— 持久化在 app-config.json 的 {@code context} 块下。
 * <p>
 * 把原先散落在 {@code ChatServiceImpl} 的硬编码常量提到这里,便于按模型/场景调参,
 * 无需改代码重新打包。所有字段都带<b>合理默认值</b>:旧配置文件没有 {@code context} 块时,
 * Jackson 反序列化得到一个全默认的对象(见 {@link AppConfig#getContext()}),零迁移。
 */
@Data
public class ContextConfig {

    /**
     * 自动压缩阈值占上下文窗口的比例:预估本轮总输入超过 {@code 窗口 × 此值} 时,
     * 先压缩较早历史再发请求。留约 20% 给模型输出(200K≈160K 触发,1M≈800K 触发)。
     */
    private double autoCompactRatio = 0.8;

    /** 压缩时保护的最近轮次数:这些轮次的对话原文保持不压缩 */
    private int protectRecentTurns = 3;

    /** 工作集内容预算占上下文窗口的比例 */
    private double workingSetRatio = 0.4;

    /**
     * 单次工具结果回喂模型的常规 token 上限。超出按行边界截断并提示模型分页/缩小范围。
     * 默认 8000(≈32k 拉丁字符 / 8k CJK 字),够一个大文件或一次检索结果。
     */
    private int maxToolResultTokens = 8000;

    /**
     * 累计软护栏触发后(本轮工具输出已逼近窗口),单次工具结果的更狠 token 上限,
     * 配合「请尽快总结收尾」提示,促使模型基于现有信息收敛。
     */
    private int tightenedToolResultTokens = 1500;
}
