package com.changy.tailoragent.chat.service;

/**
 * 工具调用事件接收器 —— 轮次级的「工具调用」事件出口。
 * <p>
 * 设计要点:本接口是 {@link com.changy.tailoragent.mcp.service.EventEmittingToolCallback}
 * 与具体落库 / 推送实现之间的解耦层。装饰器只依赖本接口,不感知 SSE / 数据库的存在;
 * 真正的实现({@code SseToolCallSink})负责把每次工具调用①落库为 tool_call / tool_result
 * 事件 ②通过 SSE 实时推给前端。
 * <p>
 * <b>传递方式</b>:本轮的 sink 实例通过 Spring AI 的 {@code ToolContext} 以 {@link #KEY} 为键传入
 * (见 ChatServiceImpl 的 {@code .toolContext(Map.of(ToolCallSink.KEY, sink))})。
 * 因为是显式传参而非线程绑定,所以即便 {@code .call()} 跑在后台线程也能取到正确的 sink。
 * 一轮对话内工具调用是串行的,故实现无需考虑并发。
 */
public interface ToolCallSink {

    /** ToolContext 中存放 sink 的键 */
    String KEY = "tailoragent.toolCallSink";

    /**
     * 模型发起一次工具调用(执行前)。
     *
     * @param callId   本次调用的唯一 id(配对 call 与 result)
     * @param toolName 工具名(snake_case)
     * @param source   工具来源:{@code local}(本地 @Tool) / {@code mcp}(外部 MCP)
     * @param argsJson 模型给出的入参(原始 JSON 字符串)
     */
    void onToolCall(String callId, String toolName, String source, String argsJson);

    /**
     * 一次工具调用返回(执行后)。
     *
     * @param callId 与 {@link #onToolCall} 对应的调用 id
     * @param status {@code success} / {@code error}
     * @param result 工具返回结果(成功时,可能被截断);失败时为 null
     * @param error  错误信息(失败时);成功时为 null
     */
    void onToolResult(String callId, String status, String result, String error);

    /**
     * 追加一条「持久上下文」事件 —— 仅落库、<b>不</b>推送前端,供需要跨轮持续生效的内部指令使用
     * (如已加载的 Skill 正文)。落为 {@code role=system / type=skill_context} 事件,
     * 由 {@link ChatContextProjector} 在后续轮次回放成 SystemMessage;前端事件接口会将其过滤,
     * 不在界面重复展示(该 Skill 的加载过程已由工具卡呈现)。
     *
     * @param content 要长期注入上下文的内容
     */
    void appendDurableContext(String content);
}
