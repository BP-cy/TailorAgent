package com.changy.tailoragent.ModelConfig.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 思考内容旁路拦截器 —— 从原始 {@code text/event-stream} 响应里「偷看」每个 SSE 增量的
 * {@code choices[0].delta.reasoning_content},投递到 {@link ReasoningStreamRegistry}。
 * <p>
 * <b>为什么在 HTTP 层做</b>:Spring AI 2.0.0 的流式 chunk 合并会丢弃 delta 的非标准附加字段
 * （见 {@link ReasoningStreamRegistry} 注释),思考内容到不了 {@code AssistantMessage}。HTTP 层是
 * 数据被丢弃前唯一干净的拦截点。
 * <p>
 * <b>透传保证</b>:本拦截器只**复制**经过的字节做解析,响应体字节原样交给上层 OpenAI SDK,
 * 不改变 Spring AI 看到的任何内容(正文 / 工具调用照常)。
 * <p>
 * <b>关联</b>:只处理带 {@link ReasoningStreamRegistry#HEADER} 请求头的流式响应;该头由
 * {@code ChatServiceImpl} 经 {@code OpenAiChatOptions.customHeaders} 按轮注入,OkHttp 头名大小写不敏感。
 */
public class ReasoningSseTap implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(ReasoningSseTap.class);

    private final ReasoningStreamRegistry registry;
    private final ObjectMapper mapper;

    public ReasoningSseTap(ReasoningStreamRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());

        String reqId = chain.request().header(ReasoningStreamRegistry.HEADER);
        ResponseBody body = response.body();
        if (reqId == null || body == null) {
            return response;
        }
        MediaType ct = body.contentType();
        if (ct == null || !ct.toString().contains("event-stream")) {
            return response; // 非流式(如普通 JSON / 工具循环中的非 SSE 调用)无需旁路
        }

        // 用一个会「偷看」字节的 Source 包裹原始流;字节读多少、解析多少,完全不影响上层消费
        BufferedSource teed = Okio.buffer(new ReasoningTeeSource(body.source(), reqId));
        return response.newBuilder()
                .body(new ForwardingBody(body.contentType(), body.contentLength(), teed))
                .build();
    }

    /** 从单行 SSE {@code data:} 负载里取思考增量(reasoning_content,兜底 reasoning),无则 null */
    private String extractReasoning(String json) {
        try {
            JsonNode delta = mapper.readTree(json).path("choices").path(0).path("delta");
            JsonNode rc = delta.path("reasoning_content");
            if (rc.isMissingNode() || rc.isNull()) {
                rc = delta.path("reasoning");
            }
            String text = rc.isTextual() ? rc.asText() : null;
            return (text == null || text.isEmpty()) ? null : text;
        } catch (Exception e) {
            return null; // 解析失败(如 [DONE]、心跳行)直接忽略,绝不影响主流
        }
    }

    /** 边读边按行解析 SSE 的 Source 装饰器:把读到的字节复制一份累积成行,逐行抽取思考增量 */
    private final class ReasoningTeeSource extends ForwardingSource {

        private final String reqId;
        private final Buffer lineBuf = new Buffer();

        ReasoningTeeSource(Source delegate, String reqId) {
            super(delegate);
            this.reqId = reqId;
        }

        @Override
        public long read(Buffer sink, long byteCount) throws IOException {
            long n = super.read(sink, byteCount);
            if (n > 0) {
                // 复制本次新读入的字节([size-n, size))到行缓冲,不消费 sink 本身
                sink.copyTo(lineBuf, sink.size() - n, n);
                drainLines();
            }
            return n;
        }

        /** 取出行缓冲中所有完整行(以 \n 分隔)并处理 */
        private void drainLines() throws IOException {
            long nl;
            while ((nl = lineBuf.indexOf((byte) '\n')) != -1L) {
                String line = lineBuf.readUtf8(nl);
                lineBuf.readByte(); // 丢弃换行符
                handleLine(line);
            }
        }

        private void handleLine(String line) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (!line.startsWith("data:")) {
                return;
            }
            String json = line.substring("data:".length()).trim();
            if (json.isEmpty() || "[DONE]".equals(json)) {
                return;
            }
            String delta = extractReasoning(json);
            if (delta != null) {
                if (log.isDebugEnabled()) {
                    log.debug("[思考旁路] reqId={} 截获 reasoning 增量 {} 字", reqId, delta.length());
                }
                registry.offer(reqId, delta);
            }
        }
    }

    /** 透传响应体:沿用原始 contentType / contentLength,数据源换成会偷看的 teed source */
    private static final class ForwardingBody extends ResponseBody {

        private final MediaType contentType;
        private final long contentLength;
        private final BufferedSource source;

        ForwardingBody(MediaType contentType, long contentLength, BufferedSource source) {
            this.contentType = contentType;
            this.contentLength = contentLength;
            this.source = source;
        }

        @Override
        public MediaType contentType() {
            return contentType;
        }

        @Override
        public long contentLength() {
            return contentLength;
        }

        @Override
        public BufferedSource source() {
            return source;
        }
    }
}
