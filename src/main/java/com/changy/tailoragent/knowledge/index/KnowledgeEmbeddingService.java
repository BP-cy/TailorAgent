package com.changy.tailoragent.knowledge.index;

import java.util.List;

/**
 * 知识库索引使用的 Embedding 会话抽象。
 *
 * <p>一次建索引或检索固定使用同一个 {@link Session}，避免用户在批处理中修改配置后，
 * 同一个 Lucene 索引混入不同模型或不同维度的向量。接口独立于具体供应商，也方便测试
 * 用确定性向量替代真实网络调用。</p>
 */
public interface KnowledgeEmbeddingService {

    /** 读取当前配置并创建本次操作的固定模型会话。 */
    Session openSession();

    interface Session {

        /** 不包含凭据的模型标识，用于 Lucene commit user data。 */
        String modelId();

        /**
         * 一次 Embedding API 请求允许包含的最大文本条数。
         * 默认 10 兼容批次上限较小的供应商；生产会话会固定为打开会话时的配置快照。
         */
        default int batchSize() {
            return 10;
        }

        /** 批量生成向量，返回顺序必须与输入顺序一致。 */
        List<float[]> embedAll(List<String> texts);

        default float[] embed(String text) {
            List<float[]> vectors = embedAll(List.of(text));
            if (vectors.size() != 1) {
                throw new IllegalStateException("Embedding 服务返回数量与请求不一致");
            }
            return vectors.getFirst();
        }
    }
}
