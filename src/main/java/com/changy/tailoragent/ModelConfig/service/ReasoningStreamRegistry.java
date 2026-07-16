package com.changy.tailoragent.ModelConfig.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 思考增量（reasoning_content）旁路缓冲区 —— 按「请求 id」暂存从原始 SSE 流里解析出的思考增量。
 * <p>
 * <b>为什么需要它</b>:Spring AI 2.0.0 的流式 chunk 合并（{@code OpenAiChatModel$ChunkMerger.chunkToChatCompletion}）
 * 只把 delta 的 {@code content/refusal/toolCalls} 搬到消息上,**丢弃了 {@code delta._additionalProperties}** ——
 * 而 DeepSeek 等模型的思考内容 {@code reasoning_content} 正好落在这个 map 里。于是 {@code .stream()} 下
 * {@code AssistantMessage} 的 {@code reasoningContent} 元数据恒为空。{@code .call()} 不受影响。
 * <p>
 * <b>绕法</b>:在 {@link ModelManager} 的 OkHttp 客户端上挂 {@link ReasoningSseTap},直接从原始
 * {@code text/event-stream} 字节里解析 {@code choices[0].delta.reasoning_content},按请求头
 * {@link #HEADER} 携带的 id offer 到这里;{@code ChatServiceImpl} 在它原有的逐块循环里
 * {@link #drain(String)} 取出并推送,**全程在同一后台线程发送 SSE,不引入新的并发**。
 * <p>
 * <b>线程</b>:{@link #offer} 在 HTTP 解析线程调用,{@link #drain} 在 chat-stream 线程调用,
 * 经 {@link ConcurrentLinkedQueue} 跨线程交接;HTTP 流读到 EOF(→ Spring AI 流完成)前所有
 * offer 均已发生,故循环后的尾部 drain 不会漏。
 */
@Component
public class ReasoningStreamRegistry {

    /** 关联「本轮请求 → 思考缓冲队列」的请求头名(随请求发出,旁路拦截器据此回填到对应队列) */
    public static final String HEADER = "X-Tailor-Reasoning-Req";

    private final Map<String, Queue<String>> queues = new ConcurrentHashMap<>();

    /** 开始一轮:为该请求 id 建一个空队列(调用模型前) */
    public void register(String reqId) {
        queues.put(reqId, new ConcurrentLinkedQueue<>());
    }

    /** 旁路解析到一段思考增量时投入对应队列(请求 id 未注册则忽略) */
    public void offer(String reqId, String delta) {
        if (reqId == null || delta == null || delta.isEmpty()) {
            return;
        }
        Queue<String> q = queues.get(reqId);
        if (q != null) {
            q.add(delta);
        }
    }

    /**
     * 取走该请求当前已缓冲的全部思考增量并拼成一段;无内容返回 {@code null}。
     * 由消费侧在逐块循环里反复调用,实现近实时流式推送。
     */
    public String drain(String reqId) {
        Queue<String> q = queues.get(reqId);
        if (q == null) {
            return null;
        }
        StringBuilder sb = null;
        String s;
        while ((s = q.poll()) != null) {
            if (sb == null) {
                sb = new StringBuilder();
            }
            sb.append(s);
        }
        return sb == null ? null : sb.toString();
    }

    /** 结束一轮:移除队列(finally 中调用,避免泄漏) */
    public void unregister(String reqId) {
        queues.remove(reqId);
    }
}
