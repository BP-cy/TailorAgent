package com.changy.tailoragent.mcp.service;

import com.changy.tailoragent.chat.service.InTurnBudget;
import com.changy.tailoragent.chat.service.TokenEstimator;
import com.changy.tailoragent.chat.service.ToolCallSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 工具调用事件装饰器 —— 包裹真实 {@link ToolCallback},在模型「实际发起调用」时:
 * <ol>
 *   <li>打印工具名 / 入参 / 返回(截断)日志;</li>
 *   <li>若 {@code ToolContext} 中带有本轮的 {@link ToolCallSink},则向其发出
 *       {@code onToolCall}(执行前)与 {@code onToolResult}(执行后)事件 ——
 *       由 sink 负责落库 + SSE 推送给前端展示。</li>
 * </ol>
 * <p>
 * <b>架构位置</b>:本地 @Tool 与外部 MCP 工具都在 {@code ToolAggregator} 里被本类统一包裹,
 * 因此前端拿到的工具调用事件对两类工具是同一套结构(靠 {@link #source} 区分来源)。
 * <p>
 * <b>为何在这一层拦截</b>:Spring AI 的工具执行是框架内部自动循环(模型要调工具→框架执行→回喂模型),
 * 整个循环发生在一次 {@code .call()} 内,前端只能拿到最终文本。框架执行工具调的就是
 * {@code ToolCallback.call()},这里正是把「工具调用过程」暴露出来的唯一干净缝。
 * <p>
 * <b>线程</b>:sink 经 ToolContext 显式传入,不依赖线程绑定;无 sink(如未挂 toolContext)时
 * 仅打印日志、不发事件,优雅降级。
 * <p>
 * <b>轮内封顶</b>:本类是本地 + MCP 所有工具「模型可见输出」流经的唯一收口,因此在这里对每次
 * 结果按 token 上限截断 —— 防止 Read 大文件 / Grep 海量命中 / MCP 巨型 JSON 在<b>单轮内</b>
 * 撑破上下文窗口。配合经 {@code ToolContext} 注入的 {@link InTurnBudget} 做累计软护栏:
 * 本轮工具输出逼近窗口时,后续结果套用更狠的上限并提示模型尽快收尾。
 */
public class EventEmittingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(EventEmittingToolCallback.class);

    /** 返回结果日志预览的最大字符数 */
    private static final int PREVIEW_LIMIT = 300;

    private final ToolCallback delegate;
    /** 工具来源:{@code local} / {@code mcp},用于前端图标分流 */
    private final String source;
    /** token 估算器:判断工具结果是否超上限 + 累加软护栏用量 */
    private final TokenEstimator estimator;
    /** 单次工具结果常规 token 上限(取自 ContextConfig) */
    private final int perCallMaxTokens;
    /** 软护栏触发后单次工具结果的更狠 token 上限 */
    private final int tightenedMaxTokens;

    public EventEmittingToolCallback(ToolCallback delegate, String source,
                                     TokenEstimator estimator,
                                     int perCallMaxTokens, int tightenedMaxTokens) {
        this.delegate = delegate;
        this.source = source;
        this.estimator = estimator;
        this.perCallMaxTokens = perCallMaxTokens;
        this.tightenedMaxTokens = tightenedMaxTokens;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return invoke(toolInput, null, null, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return invoke(toolInput, extractSink(toolContext), extractBudget(toolContext),
                () -> delegate.call(toolInput, toolContext));
    }

    /** 从 ToolContext 取出本轮 sink(可能为 null) */
    private ToolCallSink extractSink(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object v = toolContext.getContext().get(ToolCallSink.KEY);
        return v instanceof ToolCallSink sink ? sink : null;
    }

    /** 从 ToolContext 取出本轮工具输出预算(可能为 null:无预算时仅做常规单次封顶) */
    private InTurnBudget extractBudget(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object v = toolContext.getContext().get(InTurnBudget.CTX_KEY);
        return v instanceof InTurnBudget b ? b : null;
    }

    /** 包裹一次真实调用:发 call/result 事件 + 打印日志 + 轮内封顶 */
    private String invoke(String toolInput, ToolCallSink sink, InTurnBudget budget, Supplier<String> action) {
        String name = delegate.getToolDefinition().name();
        String callId = UUID.randomUUID().toString();

        log.info("[工具调用] 模型发起调用: source={}, name={}, callId={}, 入参={}", source, name, callId, toolInput);
        if (sink != null) {
            sink.onToolCall(callId, name, source, toolInput);
        }

        try {
            String raw = action.get();
            // 轮内封顶:对模型可见结果按 token 上限截断;有预算时套用软护栏(逼近窗口则更狠)并累加用量。
            // sink 与返回值拿同一份(已封顶),保证「展示」与「模型可见」一致。
            boolean tightened = budget != null && budget.isTightened();
            int cap = budget != null ? budget.effectiveCap() : perCallMaxTokens;
            String result = capResult(raw, cap, tightened);
            if (budget != null && result != null) {
                budget.addUsed(estimator.estimate(result));
            }

            int len = result == null ? 0 : result.length();
            String preview = len > PREVIEW_LIMIT
                    ? result.substring(0, PREVIEW_LIMIT) + "…(共" + len + "字)"
                    : result;
            log.info("[工具调用] 返回: name={}, callId={}, 结果={}", name, callId, preview);
            if (sink != null) {
                sink.onToolResult(callId, "success", result, null);
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("[工具调用] 失败: name={}, callId={}, err={}", name, callId, e.getMessage());
            if (sink != null) {
                sink.onToolResult(callId, "error", null, e.getMessage());
            }
            throw e;
        }
    }

    /**
     * 把工具结果按 token 上限封顶(模型可见侧)。未超限原样返回;超限则按比例估出保留字符数、
     * 在行边界回退截断,并追加引导标记。{@code tightened=true} 用收尾提示,否则用「分页/缩小范围」提示。
     */
    private String capResult(String result, int maxTokens, boolean tightened) {
        if (result == null || result.isEmpty() || estimator == null || maxTokens <= 0) {
            return result;
        }
        int tokens = estimator.estimate(result);
        if (tokens <= maxTokens) {
            return result;
        }
        // 按 token 比例估算可保留的字符数(粗算够用),再退到最近的换行处,避免截断在半行/半个 JSON
        int approxChars = (int) Math.min(result.length() - 1L, (long) result.length() * maxTokens / tokens);
        int cut = result.lastIndexOf('\n', approxChars);
        String kept = cut > 0 ? result.substring(0, cut) : result.substring(0, Math.max(1, approxChars));
        String marker = tightened
                ? "\n\n…[上下文接近上限,后续工具输出已大幅压缩,请尽快基于现有信息总结收尾]"
                : "\n\n…[工具输出过长,已截断(原 ~" + tokens + " tokens / 上限 " + maxTokens
                        + ");如需完整内容请用 read_file 分页或缩小检索范围]";
        log.info("[工具封顶] name={}, 原 ~{} tokens → 上限 {}{}",
                delegate.getToolDefinition().name(), tokens, maxTokens, tightened ? " (收紧)" : "");
        return kept + marker;
    }
}
