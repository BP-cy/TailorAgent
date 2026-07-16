package com.changy.tailoragent.knowledge.service;

import com.changy.tailoragent.common.exception.BusinessException;
import com.changy.tailoragent.ModelConfig.event.ConfigChangedEvent;
import com.changy.tailoragent.knowledge.entity.KbCatalogEntry;
import com.changy.tailoragent.knowledge.index.ChunkedMarkdownDocument;
import com.changy.tailoragent.knowledge.index.IndexChunk;
import com.changy.tailoragent.knowledge.index.KnowledgeEmbeddingService;
import com.changy.tailoragent.knowledge.index.KnowledgeIndexMetadata;
import com.changy.tailoragent.knowledge.index.KnowledgeIndexProperties;
import com.changy.tailoragent.knowledge.index.KnowledgeLuceneDocumentFactory;
import com.changy.tailoragent.knowledge.index.KnowledgeLuceneIndex;
import com.changy.tailoragent.knowledge.index.MarkdownIndexDocumentBuilder;
import com.changy.tailoragent.knowledge.mapper.KbCatalogMapper;
import com.changy.tailoragent.tool.support.KnowledgePathResolver;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Markdown 知识库索引服务。
 *
 * <p>每篇文档严格按“AST 解析 → 全部物理切块 → 全部 Embedding → 全部 Lucene Document”
 * 的顺序在内存中准备完成，之后才调用 Lucene 原子替换；commit 成功后才把 SQLite catalog
 * 标为 {@code indexed}。索引版本不兼容时同样先准备整库，再一次性替换旧索引。</p>
 */
@Slf4j
@Service
public class KnowledgeIndexService {

    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private final KbCatalogMapper catalog;
    private final KnowledgePathResolver pathResolver;
    private final MarkdownIndexDocumentBuilder markdownBuilder;
    private final KnowledgeEmbeddingService embeddingService;
    private final KnowledgeLuceneDocumentFactory documentFactory;
    private final KnowledgeLuceneIndex luceneIndex;
    private final KnowledgeIndexProperties properties;

    public KnowledgeIndexService(KbCatalogMapper catalog,
                                 KnowledgePathResolver pathResolver,
                                 MarkdownIndexDocumentBuilder markdownBuilder,
                                 KnowledgeEmbeddingService embeddingService,
                                 KnowledgeLuceneDocumentFactory documentFactory,
                                 KnowledgeLuceneIndex luceneIndex,
                                 KnowledgeIndexProperties properties) {
        this.catalog = catalog;
        this.pathResolver = pathResolver;
        this.markdownBuilder = markdownBuilder;
        this.embeddingService = embeddingService;
        this.documentFactory = documentFactory;
        this.luceneIndex = luceneIndex;
        this.properties = properties;
    }

    /** 文件保存/AI 编辑后原子地标脏并删除旧索引。 */
    public synchronized void invalidateDocument(String relPath) {
        String normalized = normalizeRelPath(relPath);
        catalog.markDirty(normalized);
        luceneIndex.deleteDocument(normalized);
        log.debug("知识文档已标脏并删除旧索引: {}", normalized);
    }

    /** 文件消失/删除后原子地移除 catalog 和 Lucene 文档。 */
    public synchronized void removeDocument(String relPath) {
        String normalized = normalizeRelPath(relPath);
        catalog.deleteByPath(normalized);
        luceneIndex.deleteDocument(normalized);
    }

    /** 文件夹删除后原子地移除整个前缀的 catalog 和 Lucene 文档。 */
    public synchronized void removePrefix(String relPathPrefix) {
        String normalized = directoryPrefix(relPathPrefix);
        catalog.deleteByPrefix(normalized);
        luceneIndex.deletePrefix(normalized);
    }

    /** 单文件移动后删除旧定位，并把新路径登记为待索引。 */
    public synchronized void moveDocument(String from, String to) {
        String oldPath = normalizeRelPath(from);
        String newPath = normalizeRelPath(to);
        catalog.deleteByPath(oldPath);
        luceneIndex.deleteDocument(oldPath);
        // 目标路径理论上不存在，但清理潜在孤儿索引可防止异常退出遗留数据复活。
        luceneIndex.deleteDocument(newPath);
        catalog.markDirty(newPath);
    }

    /** 文件夹移动后删除旧前缀，并登记移动后的全部真实文件。 */
    public synchronized void movePrefix(String from, String to, List<String> movedFiles) {
        String oldPrefix = directoryPrefix(from);
        String newPrefix = directoryPrefix(to);
        catalog.deleteByPrefix(oldPrefix);
        luceneIndex.deletePrefix(oldPrefix);
        luceneIndex.deletePrefix(newPrefix);
        for (String movedFile : movedFiles) {
            catalog.markDirty(normalizeRelPath(movedFile));
        }
    }

    /** 手动重建单篇 Markdown；版本不兼容时会自动重建全部 Markdown。 */
    public void reindex(String relPath) {
        reindex(relPath, KnowledgeIndexProgressListener.NONE);
    }

    /** 手动重建单篇 Markdown，并报告文件队列和写入阶段。 */
    public synchronized void reindex(String relPath, KnowledgeIndexProgressListener progressListener) {
        KnowledgeIndexProgressListener progress = listenerOrNone(progressListener);
        String normalized = normalizeRelPath(relPath);
        requireMarkdown(normalized);
        Path file = resolveMarkdown(normalized);
        if (!Files.isRegularFile(file)) {
            removeDocument(normalized);
            throw new BusinessException("文档不存在: " + normalized);
        }
        catalog.insertIfAbsent(normalized);

        KnowledgeEmbeddingService.Session embedding = embeddingService.openSession();
        KnowledgeIndexMetadata current = luceneIndex.metadata();
        try {
            if (requiresRebuildBeforeEmbedding(current, embedding.modelId())) {
                rebuildAll(embedding, Map.of(), progress);
                return;
            }

            progress.onPlan(List.of(normalized));
            catalog.updateStatus(normalized, "processing");
            PreparedDocument prepared = prepareWithProgress(normalized, file, embedding, progress);
            KnowledgeIndexMetadata desired = metadata(embedding.modelId(), prepared.dimension());
            if ((current == null && luceneIndex.hasDocuments())
                    || (current != null && !current.equals(desired))) {
                rebuildAll(embedding, Map.of(normalized, prepared), progress);
                return;
            }

            progress.onWriting();
            luceneIndex.replaceDocument(normalized, prepared.luceneDocuments(), desired);
            markIndexed(prepared);
            log.info("重建 Markdown 索引完成: {}（{} 个物理块）",
                    normalized, prepared.luceneDocuments().size());
        } catch (RuntimeException e) {
            catalog.updateStatus(normalized, "failed");
            throw asBusinessException("重建索引失败: " + normalized, e);
        }
    }

    /** 手动触发：重建全部 unindexed Markdown，非 Markdown 文件留给后续 Tika 流程。 */
    public int reindexAllDirty() {
        return reindexAllDirty(KnowledgeIndexProgressListener.NONE);
    }

    /** 手动触发批量索引，并按实际文件队列报告进度。 */
    public synchronized int reindexAllDirty(KnowledgeIndexProgressListener progressListener) {
        KnowledgeIndexProgressListener progress = listenerOrNone(progressListener);
        Set<String> dirtyPaths = new LinkedHashSet<>();
        for (KbCatalogEntry entry : catalog.findDirty()) {
            String path = entry.getRelPath();
            if (!isMarkdown(path)) {
                continue;
            }
            if (Files.isRegularFile(resolveMarkdown(path))) {
                dirtyPaths.add(path);
            } else {
                removeDocument(path);
            }
        }
        List<String> dirty = dirtyPaths.stream().sorted().toList();
        KnowledgeIndexMetadata current = luceneIndex.metadata();
        boolean hasDocuments = luceneIndex.hasDocuments();
        if (dirty.isEmpty() && !hasDocuments && allMarkdownPaths().isEmpty()) {
            progress.onPlan(List.of());
            return 0;
        }

        KnowledgeEmbeddingService.Session embedding = embeddingService.openSession();
        if (requiresRebuildBeforeEmbedding(current, embedding.modelId())
                || (current == null && hasDocuments)
                || (current == null && dirty.isEmpty())
                || (current != null && !hasDocuments)) {
            return rebuildAll(embedding, Map.of(), progress);
        }
        progress.onPlan(dirty);
        if (dirty.isEmpty()) {
            // 配置标识未变时仍用一次轻量请求核对服务实际维度，处理供应商原地升级模型的情况。
            float[] probe = embedding.embed("知识库索引向量维度校验");
            if (probe.length != current.embeddingDimension()) {
                return rebuildAll(embedding, Map.of(), progress);
            }
            return 0;
        }

        List<PreparedDocument> prepared = new ArrayList<>();
        try {
            for (String path : dirty) {
                catalog.updateStatus(path, "processing");
                prepared.add(prepareWithProgress(path, resolveMarkdown(path), embedding, progress));
            }
            int dimension = requireSingleDimension(prepared);
            KnowledgeIndexMetadata desired = metadata(embedding.modelId(), dimension);
            if (current != null && !current.equals(desired)) {
                Map<String, PreparedDocument> cached = new HashMap<>();
                for (PreparedDocument document : prepared) {
                    cached.put(document.relPath(), document);
                }
                return rebuildAll(embedding, cached, progress);
            }

            progress.onWriting();
            for (PreparedDocument document : prepared) {
                luceneIndex.replaceDocument(document.relPath(), document.luceneDocuments(), desired);
                markIndexed(document);
            }
            log.info("手动索引完成，共重建 {} 篇 Markdown", prepared.size());
            return prepared.size();
        } catch (RuntimeException e) {
            for (String path : dirty) {
                catalog.updateStatus(path, "failed");
            }
            throw asBusinessException("批量重建知识库索引失败", e);
        }
    }

    /**
     * Embedding 配置变化后立即让旧索引失效，但仍保持“用户手动触发重新索引”的产品约定。
     * 这样 UI 状态不会继续显示 indexed，检索也不会误用旧模型向量。
     */
    @EventListener
    public synchronized void onConfigChanged(ConfigChangedEvent ignored) {
        KnowledgeIndexMetadata current;
        try {
            current = luceneIndex.metadata();
        } catch (RuntimeException e) {
            log.error("检查 Embedding 配置对应的旧索引失败: {}", e.getMessage(), e);
            return;
        }
        if (current == null) {
            return;
        }
        String configuredModelId = null;
        try {
            configuredModelId = embeddingService.openSession().modelId();
        } catch (RuntimeException ignoredConfigError) {
            // 用户清空 Embedding 配置同样应使旧向量失效。
        }
        if (current.embeddingModelId().equals(configuredModelId)) {
            return;
        }
        try {
            for (KbCatalogEntry entry : allCatalogEntries()) {
                if (isMarkdown(entry.getRelPath())) {
                    catalog.markDirty(entry.getRelPath());
                }
            }
            luceneIndex.deleteAllDocuments();
            log.info("Embedding 配置已变化，旧知识库向量索引已失效，等待用户手动重建");
        } catch (RuntimeException e) {
            // 配置已成功持久化；失效失败不能把设置保存伪装成失败，检索侧 metadata 校验仍会拒绝旧索引。
            log.error("Embedding 配置变化后失效旧索引失败: {}", e.getMessage(), e);
        }
    }

    private int rebuildAll(KnowledgeEmbeddingService.Session embedding,
                           Map<String, PreparedDocument> preparedCache,
                           KnowledgeIndexProgressListener progress) {
        List<String> paths = allMarkdownPaths();
        progress.onPlan(paths);
        if (paths.isEmpty()) {
            float[] probe = embedding.embed("空知识库索引版本初始化");
            progress.onWriting();
            luceneIndex.replaceAll(List.of(), metadata(embedding.modelId(), probe.length));
            return 0;
        }

        for (String path : paths) {
            catalog.updateStatus(path, "processing");
        }
        try {
            List<PreparedDocument> prepared = new ArrayList<>(paths.size());
            for (String path : paths) {
                progress.onFileStarted(path);
                PreparedDocument cached = preparedCache.get(path);
                prepared.add(cached != null ? cached : prepare(path, resolveMarkdown(path), embedding));
                progress.onFileCompleted(path);
            }
            int dimension = requireSingleDimension(prepared);
            KnowledgeIndexMetadata desired = metadata(embedding.modelId(), dimension);
            List<Document> allDocuments = prepared.stream()
                    .flatMap(document -> document.luceneDocuments().stream())
                    .toList();

            // 所有解析和网络调用均已成功，直到此处才替换旧索引。
            progress.onWriting();
            luceneIndex.replaceAll(allDocuments, desired);
            for (PreparedDocument document : prepared) {
                markIndexed(document);
            }
            log.info("知识库索引版本变化，已整库重建 {} 篇 Markdown（{} 个物理块）",
                    prepared.size(), allDocuments.size());
            return prepared.size();
        } catch (RuntimeException e) {
            for (String path : paths) {
                catalog.updateStatus(path, "failed");
            }
            throw asBusinessException("整库重建知识库索引失败", e);
        }
    }

    private PreparedDocument prepare(String relPath, Path file,
                                      KnowledgeEmbeddingService.Session embedding) {
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String contentHash = sha256(source.getBytes(StandardCharsets.UTF_8));
            ChunkedMarkdownDocument chunked = markdownBuilder.build(relPath, source);
            List<String> inputs = chunked.chunks().stream()
                    .map(this::embeddingInput)
                    .toList();
            List<float[]> vectors = embedBatched(embedding, inputs);
            if (vectors.size() != chunked.chunks().size()) {
                throw new IllegalStateException("Embedding 返回数量与物理块数量不一致");
            }

            int dimension = -1;
            List<Document> luceneDocuments = new ArrayList<>(vectors.size());
            for (int i = 0; i < vectors.size(); i++) {
                float[] vector = vectors.get(i);
                if (dimension < 0) {
                    dimension = vector.length;
                } else if (dimension != vector.length) {
                    throw new IllegalStateException("同一文档的 Embedding 向量维度不一致");
                }
                luceneDocuments.add(documentFactory.create(chunked.chunks().get(i), contentHash, vector));
            }
            if (dimension <= 0) {
                throw new IllegalStateException("Markdown 未生成任何可索引物理块");
            }
            return new PreparedDocument(relPath, contentHash, dimension, luceneDocuments);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("准备 Markdown 索引失败: " + relPath, e);
        }
    }

    private List<float[]> embedBatched(KnowledgeEmbeddingService.Session embedding, List<String> inputs) {
        int batchSize = embedding.batchSize();
        if (batchSize <= 0) {
            throw new IllegalStateException("Embedding 单次向量化条数必须为正数");
        }
        List<float[]> result = new ArrayList<>(inputs.size());
        for (int start = 0; start < inputs.size(); start += batchSize) {
            int end = Math.min(inputs.size(), start + batchSize);
            result.addAll(embedding.embedAll(inputs.subList(start, end)));
        }
        return result;
    }

    private PreparedDocument prepareWithProgress(String relPath, Path file,
                                                  KnowledgeEmbeddingService.Session embedding,
                                                  KnowledgeIndexProgressListener progress) {
        progress.onFileStarted(relPath);
        PreparedDocument prepared = prepare(relPath, file, embedding);
        progress.onFileCompleted(relPath);
        return prepared;
    }

    private String embeddingInput(IndexChunk chunk) {
        String path = chunk.section().docPath().replace('\\', '/');
        int slash = path.lastIndexOf('/');
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
        StringBuilder input = new StringBuilder()
                .append("文档：").append(fileName).append('\n')
                .append("章节：").append(chunk.section().headingPath());
        if (!chunk.text().isBlank()) {
            input.append("\n\n").append(chunk.text());
        }
        return input.toString();
    }

    private List<String> allMarkdownPaths() {
        Set<String> paths = new LinkedHashSet<>();
        for (KbCatalogEntry entry : allCatalogEntries()) {
            String path = entry.getRelPath();
            if (!isMarkdown(path)) {
                continue;
            }
            if (Files.isRegularFile(resolveMarkdown(path))) {
                paths.add(path);
            } else {
                removeDocument(path);
            }
        }
        return paths.stream().sorted().toList();
    }

    private static KnowledgeIndexProgressListener listenerOrNone(
            KnowledgeIndexProgressListener listener) {
        return listener == null ? KnowledgeIndexProgressListener.NONE : listener;
    }

    private List<KbCatalogEntry> allCatalogEntries() {
        List<KbCatalogEntry> entries = new ArrayList<>();
        entries.addAll(catalog.findByPrefix("MD/"));
        entries.addAll(catalog.findByPrefix("files/"));
        return entries;
    }

    private boolean requiresRebuildBeforeEmbedding(KnowledgeIndexMetadata current, String modelId) {
        return current != null
                && (!KnowledgeIndexMetadata.SCHEMA_VERSION.equals(current.indexSchemaVersion())
                || !properties.chunkingVersion().equals(current.chunkingVersion())
                || !modelId.equals(current.embeddingModelId()));
    }

    private KnowledgeIndexMetadata metadata(String modelId, int dimension) {
        return new KnowledgeIndexMetadata(KnowledgeIndexMetadata.SCHEMA_VERSION,
                properties.chunkingVersion(), modelId, dimension);
    }

    private static int requireSingleDimension(List<PreparedDocument> documents) {
        int dimension = documents.getFirst().dimension();
        for (PreparedDocument document : documents) {
            if (document.dimension() != dimension) {
                throw new IllegalStateException("Embedding 模型在不同文档间返回了不同维度");
            }
        }
        return dimension;
    }

    private void markIndexed(PreparedDocument document) {
        catalog.markIndexed(document.relPath(), document.contentHash(),
                ISO_FMT.format(LocalDateTime.now()));
    }

    private Path resolveMarkdown(String relPath) {
        return pathResolver.resolveForWrite(normalizeRelPath(relPath));
    }

    private static void requireMarkdown(String relPath) {
        if (!isMarkdown(relPath)) {
            throw new BusinessException("当前索引方案仅支持 .md 和 .markdown 文件: " + relPath);
        }
    }

    private static boolean isMarkdown(String relPath) {
        if (relPath == null) {
            return false;
        }
        String lower = relPath.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    private static String normalizeRelPath(String relPath) {
        if (relPath == null || relPath.isBlank()) {
            throw new BusinessException("知识库路径不能为空");
        }
        String normalized = relPath.strip().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String directoryPrefix(String relPathPrefix) {
        String normalized = normalizeRelPath(relPathPrefix);
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static BusinessException asBusinessException(String message, RuntimeException cause) {
        if (cause instanceof BusinessException businessException) {
            return businessException;
        }
        String detail = cause.getMessage();
        return new BusinessException(detail == null || detail.isBlank()
                ? message : message + "（" + detail + "）");
    }

    private record PreparedDocument(
            String relPath,
            String contentHash,
            int dimension,
            List<Document> luceneDocuments
    ) {
        private PreparedDocument {
            luceneDocuments = List.copyOf(luceneDocuments);
        }
    }
}
