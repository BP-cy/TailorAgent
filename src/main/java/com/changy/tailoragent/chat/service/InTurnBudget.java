package com.changy.tailoragent.chat.service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮内工具输出预算 —— 单轮对话生命周期内,累计「回喂模型的工具结果」token,
 * 并在逼近上下文窗口时收紧后续工具输出,防止工具循环在<b>单轮内</b>顶破窗口。
 * <p>
 * <b>为何需要它</b>:Spring AI 的工具调用循环跑在框架内部(一次 {@code .stream()} 内
 * 自动「模型→工具→喂回→再模型」),没有迭代间钩子。本预算经 {@code ToolContext} 注入,
 * 由唯一流经所有工具输出的 {@code EventEmittingToolCallback} 在每次结果上读取/累加,
 * 从而在不接管框架循环的前提下,对轮内增长做边界封顶。
 * <p>
 * <b>线程</b>:框架可能并发执行同一条 assistant 消息里的多个 tool call,故 {@link #used}
 * 用 {@link AtomicInteger}。读写无锁,弱一致即可(护栏是软的,毫秒级竞态不影响正确性)。
 */
public final class InTurnBudget {

    /** 放入 ToolContext 的键 */
    public static final String CTX_KEY = "inTurnBudget";

    /** 本轮可用于工具输出的软上限(= 窗口阈值 − 轮起基线输入);≤0 表示开局即收紧 */
    private final int softLimitTokens;
    /** 常规模式下单次工具结果的 token 上限 */
    private final int perCallMaxTokens;
    /** 收紧模式下单次工具结果的更狠 token 上限 */
    private final int tightenedMaxTokens;
    /** 已累计的工具输出 token(模型可见、封顶后) */
    private final AtomicInteger used = new AtomicInteger(0);

    public InTurnBudget(int softLimitTokens, int perCallMaxTokens, int tightenedMaxTokens) {
        this.softLimitTokens = softLimitTokens;
        this.perCallMaxTokens = perCallMaxTokens;
        this.tightenedMaxTokens = tightenedMaxTokens;
    }

    /** 是否已进入收紧模式:累计工具输出已达本轮软上限 */
    public boolean isTightened() {
        return used.get() >= softLimitTokens;
    }

    /** 本次工具结果应套用的有效 token 上限 */
    public int effectiveCap() {
        return isTightened() ? tightenedMaxTokens : perCallMaxTokens;
    }

    /** 累加本次(封顶后)工具结果的估算 token,返回累加后的总量 */
    public int addUsed(int tokens) {
        return used.addAndGet(Math.max(0, tokens));
    }

    public int used() {
        return used.get();
    }

    public int softLimitTokens() {
        return softLimitTokens;
    }
}
