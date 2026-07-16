package com.changy.tailoragent.ModelConfig.service;

import com.changy.tailoragent.ModelConfig.dto.EmbeddingModelConfig;
import com.changy.tailoragent.common.exception.BusinessException;
import com.changy.tailoragent.knowledge.index.KnowledgeEmbeddingService;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.credential.BearerTokenCredential;
import okhttp3.OkHttpClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态管理 OpenAI 兼容 Embedding 模型，并作为知识库向量化服务的生产实现。
 */
@Service
public class EmbeddingModelManager implements KnowledgeEmbeddingService {

    private final AppConfigService configService;
    private final ConcurrentHashMap<String, EmbeddingModel> cache = new ConcurrentHashMap<>();

    public EmbeddingModelManager(AppConfigService configService) {
        this.configService = configService;
    }

    @Override
    public Session openSession() {
        return openSession(configService.getConfig().getEmbeddingModel());
    }

    /** 使用给定配置创建会话；供设置页连通性测试使用，不修改或持久化当前配置。 */
    public Session openSession(EmbeddingModelConfig configured) {
        if (configured == null || !configured.isConfigured()) {
            throw new BusinessException("尚未配置知识库 Embedding 模型，请先在设置中填写 Base URL 和模型名");
        }

        // 复制快照，避免 app-config 对象被前端全量保存时在批处理中原地改变。
        EmbeddingModelConfig snapshot = new EmbeddingModelConfig(
                configured.getBaseUrl().strip(),
                configured.getModelName().strip(),
                configured.getApiKey() == null ? "" : configured.getApiKey(),
                positiveOrNull(configured.getDimensions()),
                requireBatchSize(configured.getBatchSize()));
        String cacheKey = snapshot.getBaseUrl() + "::" + snapshot.getApiKey() + "::"
                + snapshot.getModelName() + "::" + snapshot.getDimensions();
        EmbeddingModel model = cache.computeIfAbsent(cacheKey, ignored -> buildModel(snapshot));
        String modelId = buildModelId(snapshot);

        return new Session() {
            @Override
            public String modelId() {
                return modelId;
            }

            @Override
            public int batchSize() {
                return snapshot.getBatchSize();
            }

            @Override
            public List<float[]> embedAll(List<String> texts) {
                if (texts == null || texts.isEmpty()) {
                    return List.of();
                }
                List<float[]> vectors = model.embed(texts);
                validateVectors(texts.size(), vectors);
                return vectors;
            }
        };
    }

    private static EmbeddingModel buildModel(EmbeddingModelConfig config) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ofSeconds(300))
                .writeTimeout(Duration.ofSeconds(60))
                .build();

        ClientOptions clientOptions = ClientOptions.builder()
                .baseUrl(config.getBaseUrl())
                // OpenAI SDK 要求存在 credential；本地无鉴权兼容服务使用空 token 也可正常发起请求。
                .credential(BearerTokenCredential.create(config.getApiKey()))
                .httpClient(new OkHttpClientAdapter(httpClient))
                .timeout(Duration.ofSeconds(300))
                .maxRetries(0)
                .build();
        OpenAIClient openAiClient = new OpenAIClientImpl(clientOptions);

        OpenAiEmbeddingOptions.Builder options = OpenAiEmbeddingOptions.builder()
                .model(config.getModelName());
        if (config.getDimensions() != null) {
            options.dimensions(config.getDimensions());
        }
        return OpenAiEmbeddingModel.builder()
                .openAiClient(openAiClient)
                .options(options.build())
                .build();
    }

    private static void validateVectors(int expected, List<float[]> vectors) {
        if (vectors == null || vectors.size() != expected) {
            throw new BusinessException("Embedding 服务返回数量与请求不一致");
        }
        int dimension = -1;
        for (float[] vector : vectors) {
            if (vector == null || vector.length == 0) {
                throw new BusinessException("Embedding 服务返回了空向量");
            }
            if (dimension < 0) {
                dimension = vector.length;
            } else if (dimension != vector.length) {
                throw new BusinessException("Embedding 服务在同一批次返回了不同维度的向量");
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new BusinessException("Embedding 服务返回了非法向量值");
                }
            }
        }
    }

    private static Integer positiveOrNull(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    private static int requireBatchSize(Integer value) {
        int batchSize = value == null ? EmbeddingModelConfig.DEFAULT_BATCH_SIZE : value;
        if (batchSize < 1 || batchSize > EmbeddingModelConfig.MAX_BATCH_SIZE) {
            throw new BusinessException("单次向量化条数必须在 1 到 "
                    + EmbeddingModelConfig.MAX_BATCH_SIZE + " 之间");
        }
        return batchSize;
    }

    private static String buildModelId(EmbeddingModelConfig config) {
        String endpointHash = sha256(config.getBaseUrl()).substring(0, 12);
        String dimension = config.getDimensions() == null ? "default" : config.getDimensions().toString();
        return config.getModelName() + "@" + endpointHash + ":" + dimension;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算 Embedding 模型标识", e);
        }
    }
}
