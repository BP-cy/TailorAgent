package com.changy.tailoragent.knowledge.index;

import java.util.LinkedHashMap;
import java.util.Map;

/** 写入 Lucene commit user data 的兼容性元数据。 */
public record KnowledgeIndexMetadata(
        String indexSchemaVersion,
        String chunkingVersion,
        String embeddingModelId,
        int embeddingDimension
) {
    public static final String SCHEMA_VERSION = "markdown-parent-child-v1";

    public Map<String, String> toMap() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("indexSchemaVersion", indexSchemaVersion);
        values.put("chunkingVersion", chunkingVersion);
        values.put("embeddingModelId", embeddingModelId);
        values.put("embeddingDimension", Integer.toString(embeddingDimension));
        return values;
    }

    public static KnowledgeIndexMetadata fromMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            String schema = values.get("indexSchemaVersion");
            String chunking = values.get("chunkingVersion");
            String model = values.get("embeddingModelId");
            String dimension = values.get("embeddingDimension");
            if (schema == null || chunking == null || model == null || dimension == null) {
                return null;
            }
            return new KnowledgeIndexMetadata(schema, chunking, model, Integer.parseInt(dimension));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
