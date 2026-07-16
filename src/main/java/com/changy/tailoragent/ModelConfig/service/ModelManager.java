package com.changy.tailoragent.ModelConfig.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.credential.BearerTokenCredential;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态管理多厂商 ChatClient 实例。
 * <p>
 * Spring AI 2.0.0 底层使用 OpenAI 官方 Java SDK（{@code com.openai.client.OpenAIClient}）。
 * <p>
 * <b>注意：</b>OpenAI Java SDK 4.39.1 的 {@code ClientOptions.Builder.build()}
 * 要求必须设置 {@code httpClient}，而 {@code openai-java-okhttp} 模块不在
 * Maven 仓库中。因此使用自定义 {@link OkHttpClientAdapter}（封装 OkHttp 4.12.0，
 * 已由 {@code spring-ai-openai} 传递引入）来满足该要求。
 * <p>
 * 缓存策略：key = {@code baseUrl::apiKey::model}，同一端点+Key+模型 复用同一个 ChatClient。
 */
@Service
public class ModelManager {

    private static final Logger log = LoggerFactory.getLogger(ModelManager.class);

    private final ConcurrentHashMap<String, ChatClient> cache = new ConcurrentHashMap<>();

    /**
     * 思考内容旁路拦截器(无状态,所有 OkHttp 客户端共用一个):从原始 SSE 流里截获
     * {@code reasoning_content} 投递到 {@link ReasoningStreamRegistry} —— 绕开 Spring AI 2.0.0
     * 流式 chunk 合并丢弃 delta 附加字段的缺陷。详见 {@link ReasoningStreamRegistry}。
     */
    private final ReasoningSseTap reasoningSseTap;

    public ModelManager(ReasoningStreamRegistry reasoningStreamRegistry) {
        this.reasoningSseTap = new ReasoningSseTap(reasoningStreamRegistry, new ObjectMapper());
    }

    /**
     * 获取或创建 ChatClient。
     *
     * @param baseUrl API 基地址（含 /v1 等路径前缀，由 ChatModel.baseUrl 提供）
     * @param apiKey  用户 API Key
     * @param model   模型名
     */
    public ChatClient getOrCreate(String baseUrl, String apiKey, String model) {
        String cacheKey = baseUrl + "::" + apiKey + "::" + model;
        return cache.computeIfAbsent(cacheKey, k -> buildClient(baseUrl, apiKey, model));
    }

    private ChatClient buildClient(String baseUrl, String apiKey, String model) {
        log.info("创建 ChatClient: baseUrl={}, model={}", baseUrl, model);

        // OpenAI Java SDK 4.39.1 要求 ClientOptions 必须设置 httpClient，
        // 但 openai-java-okhttp 模块不在 Maven 仓库中，因此用自定义
        // OkHttpClientAdapter 封装 OkHttp（已由 spring-ai-openai 传递引入）。
        // readTimeout 是「两次数据帧之间」的空闲超时:reasoning 模型思考静默期
        // 可能长达数分钟不吐任何 SSE 帧,设太短会在思考中途抛 SocketTimeoutException。
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ofSeconds(300))
                .writeTimeout(Duration.ofSeconds(60))
                // 思考内容旁路:从原始 SSE 流截获 reasoning_content(Spring AI 2.0.0 流式会丢弃它)。
                // 只读不改,响应字节原样交给上层 SDK;仅处理带 ReasoningStreamRegistry.HEADER 的流式响应。
                .addInterceptor(reasoningSseTap)
                .build();

        ClientOptions clientOptions = ClientOptions.builder()
                .baseUrl(baseUrl)
                .credential(BearerTokenCredential.create(apiKey))
                .httpClient(new OkHttpClientAdapter(okHttpClient))
                // SDK 整体请求超时:流式下覆盖「整条 SSE 流」的存活时长,需与 readTimeout 同步放大
                .timeout(Duration.ofSeconds(300))
                .maxRetries(0)
                .build();

        OpenAIClient openAiClient = new OpenAIClientImpl(clientOptions);

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .build();

        // 必须同时设置 sync 和 async 客户端 —— OpenAiChatModel.Builder.build()
        // 对两者都用了 requireNonNullElseGet，未设置时会回退到 OpenAiSetup，
        // 后者会尝试从环境变量/配置文件读取凭证，导致 "At least one credential
        // source must be specified" 错误。
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClient.async())
                .options(options)
                .build();

        return ChatClient.create(chatModel);
    }
}
