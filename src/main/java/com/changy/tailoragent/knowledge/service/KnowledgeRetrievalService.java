package com.changy.tailoragent.knowledge.service;

import com.changy.tailoragent.common.exception.BusinessException;
import com.changy.tailoragent.knowledge.dto.KnowledgeRetrievalResult;
import com.changy.tailoragent.knowledge.dto.MatchedChunk;
import com.changy.tailoragent.knowledge.entity.KbCatalogEntry;
import com.changy.tailoragent.knowledge.index.KnowledgeEmbeddingService;
import com.changy.tailoragent.knowledge.index.KnowledgeIndexMetadata;
import com.changy.tailoragent.knowledge.index.KnowledgeIndexProperties;
import com.changy.tailoragent.knowledge.index.KnowledgeLuceneIndex;
import com.changy.tailoragent.knowledge.index.LuceneChunkHit;
import com.changy.tailoragent.knowledge.index.StoredIndexChunk;
import com.changy.tailoragent.knowledge.mapper.KbCatalogMapper;
import com.changy.tailoragent.tool.support.KnowledgePathResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** BM25 + KNN 双路召回、Section 级 RRF、父子归并与正文预算裁剪。 */
@Slf4j
@Service
public class KnowledgeRetrievalService {

    private static final int PHYSICAL_RECALL_LIMIT = 100;
    private static final int SECTION_RECALL_LIMIT = 40;
    private static final int RRF_K = 60;
    private static final int DIAGNOSTIC_HIT_LIMIT = 10;

    private final KnowledgeLuceneIndex luceneIndex;
    private final KnowledgeEmbeddingService embeddingService;
    private final KnowledgeIndexProperties properties;
    private final KnowledgePathResolver pathResolver;
    private final KbCatalogMapper catalog;
    private final KnowledgeIndexService indexService;

    public KnowledgeRetrievalService(KnowledgeLuceneIndex luceneIndex,
                                     KnowledgeEmbeddingService embeddingService,
                                     KnowledgeIndexProperties properties,
                                     KnowledgePathResolver pathResolver,
                                     KbCatalogMapper catalog,
                                     KnowledgeIndexService indexService) {
        this.luceneIndex = luceneIndex;
        this.embeddingService = embeddingService;
        this.properties = properties;
        this.pathResolver = pathResolver;
        this.catalog = catalog;
        this.indexService = indexService;
    }

    public List<KnowledgeRetrievalResult> retrieve(String query) {
        if (query == null || query.isBlank()) {
            throw new BusinessException("检索词不能为空");
        }
        String searchId = UUID.randomUUID().toString().substring(0, 8);
        String normalizedQuery = query.strip();
        long startedAt = System.nanoTime();
        log.info("[kb_search:{}] 开始检索: query=\"{}\", chars={}",
                searchId, summarizeQuery(normalizedQuery), normalizedQuery.length());

        KnowledgeIndexMetadata metadata = luceneIndex.metadata();
        if (metadata == null || !luceneIndex.hasDocuments()) {
            log.info("[kb_search:{}] 检索结束: 索引为空, elapsedMs={}",
                    searchId, elapsedMillis(startedAt));
            return List.of();
        }

        long embeddingStartedAt = System.nanoTime();
        KnowledgeEmbeddingService.Session embedding = embeddingService.openSession();
        validateMetadata(metadata, embedding.modelId());
        float[] queryVector = embedding.embed(normalizedQuery);
        if (queryVector.length != metadata.embeddingDimension()) {
            throw new BusinessException("Embedding 向量维度已变化，请重新构建知识库索引");
        }
        long embeddingMs = elapsedMillis(embeddingStartedAt);

        long recallStartedAt = System.nanoTime();
        List<LuceneChunkHit> bm25Physical = luceneIndex.searchBm25(normalizedQuery, PHYSICAL_RECALL_LIMIT);
        List<LuceneChunkHit> vectorPhysical = luceneIndex.searchVector(queryVector, PHYSICAL_RECALL_LIMIT);
        long recallMs = elapsedMillis(recallStartedAt);
        if (log.isDebugEnabled()) {
            log.debug("[kb_search:{}] BM25 原始召回 top{}: {}", searchId,
                    DIAGNOSTIC_HIT_LIMIT, describePhysicalHits(bm25Physical));
            log.debug("[kb_search:{}] KNN 原始召回 top{}: {}", searchId,
                    DIAGNOSTIC_HIT_LIMIT, describePhysicalHits(vectorPhysical));
        }

        RouteRanking bm25 = rankSections(bm25Physical, true);
        RouteRanking vector = rankSections(vectorPhysical, false);
        List<SectionHit> fused = fuse(bm25, vector);
        List<HitGroup> groups = mergeParentChild(fused);
        if (log.isDebugEnabled()) {
            log.debug("[kb_search:{}] RRF 融合 top{}: {}", searchId,
                    DIAGNOSTIC_HIT_LIMIT, describeSectionHits(fused));
            log.debug("[kb_search:{}] 父子归并 top{}: {}", searchId,
                    DIAGNOSTIC_HIT_LIMIT, describeGroups(groups));
        }

        long materializeStartedAt = System.nanoTime();
        List<KnowledgeRetrievalResult> results = materialize(groups);
        long materializeMs = elapsedMillis(materializeStartedAt);
        log.info("[kb_search:{}] 检索结束: bm25Physical={}/{}, knnPhysical={}/{}, "
                        + "bm25Sections={}, knnSections={}, fusedSections={}, mergedGroups={}, results={}, "
                        + "resultChars={}, embeddingMs={}, recallMs={}, materializeMs={}, elapsedMs={}",
                searchId,
                bm25.validPhysicalHits(), bm25.rawPhysicalHits(),
                vector.validPhysicalHits(), vector.rawPhysicalHits(),
                bm25.bySection().size(), vector.bySection().size(), fused.size(), groups.size(), results.size(),
                results.stream().mapToInt(result -> result.content().length()).sum(),
                embeddingMs, recallMs, materializeMs, elapsedMillis(startedAt));
        if (log.isDebugEnabled()) {
            log.debug("[kb_search:{}] 最终结果 top{}: {}", searchId,
                    DIAGNOSTIC_HIT_LIMIT, describeResults(results));
        }
        return results;
    }

    private RouteRanking rankSections(List<LuceneChunkHit> physicalHits, boolean bm25) {
        Map<String, RouteSection> bySection = new LinkedHashMap<>();
        Map<String, Boolean> currentDocuments = new HashMap<>();
        int validPhysicalHits = 0;
        for (LuceneChunkHit physical : physicalHits) {
            if (!currentDocuments.computeIfAbsent(physical.chunk().docPath(), ignored ->
                    isCatalogCurrent(physical.chunk()))) {
                continue;
            }
            validPhysicalHits++;
            String sectionId = physical.chunk().sectionId();
            RouteSection existing = bySection.get(sectionId);
            if (existing == null) {
                if (bySection.size() >= SECTION_RECALL_LIMIT) {
                    continue;
                }
                existing = new RouteSection(bySection.size() + 1, physical.chunk());
                bySection.put(sectionId, existing);
            }
            existing.addEvidence(physical.chunk(), bm25 ? physical.rank() : null,
                    bm25 ? null : physical.rank());
        }
        return new RouteRanking(bySection, physicalHits.size(), validPhysicalHits);
    }

    private List<SectionHit> fuse(RouteRanking bm25, RouteRanking vector) {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(bm25.bySection().keySet());
        ids.addAll(vector.bySection().keySet());
        List<SectionHit> result = new ArrayList<>();

        for (String id : ids) {
            RouteSection keyword = bm25.bySection().get(id);
            RouteSection semantic = vector.bySection().get(id);
            StoredIndexChunk representative = keyword != null
                    ? keyword.representative() : semantic.representative();
            if (!isCatalogCurrent(representative)) {
                continue;
            }
            Integer bm25Rank = keyword == null ? null : keyword.sectionRank();
            Integer vectorRank = semantic == null ? null : semantic.sectionRank();
            double score = (bm25Rank == null ? 0.0 : 1.0 / (RRF_K + bm25Rank))
                    + (vectorRank == null ? 0.0 : 1.0 / (RRF_K + vectorRank));

            Map<String, Evidence> evidence = new LinkedHashMap<>();
            if (keyword != null) {
                mergeEvidence(evidence, keyword.evidence());
            }
            if (semantic != null) {
                mergeEvidence(evidence, semantic.evidence());
            }
            result.add(new SectionHit(representative, score, evidence));
        }
        result.sort(Comparator.comparingDouble(SectionHit::score).reversed()
                .thenComparing(hit -> hit.section().docPath())
                .thenComparingInt(hit -> hit.section().sectionOrdinal()));
        return result;
    }

    private List<HitGroup> mergeParentChild(List<SectionHit> hits) {
        Map<String, SectionHit> hitBySectionId = new HashMap<>();
        for (SectionHit hit : hits) {
            hitBySectionId.put(hit.section().sectionId(), hit);
        }

        Map<String, HitGroup> groups = new LinkedHashMap<>();
        for (SectionHit hit : hits) {
            SectionHit retained = hit;
            for (String ancestorId : hit.section().ancestorSectionIds()) {
                SectionHit ancestor = hitBySectionId.get(ancestorId);
                if (ancestor != null && ancestor.section().expandable()) {
                    retained = ancestor;
                }
            }
            SectionHit retainedHit = retained;
            groups.computeIfAbsent(retainedHit.section().sectionId(), ignored -> new HitGroup(retainedHit))
                    .addMember(hit);
        }
        List<HitGroup> result = new ArrayList<>(groups.values());
        result.sort(Comparator.comparingDouble(HitGroup::score).reversed()
                .thenComparing(group -> group.retained().section().docPath())
                .thenComparingInt(group -> group.retained().section().sectionOrdinal()));
        return result;
    }

    private List<KnowledgeRetrievalResult> materialize(List<HitGroup> groups) {
        List<KnowledgeRetrievalResult> results = new ArrayList<>();
        Map<String, SourceSnapshot> sources = new HashMap<>();
        int usedChars = 0;

        for (HitGroup group : groups) {
            if (results.size() >= properties.getMaxResults()
                    || usedChars >= properties.getTotalContextMaxChars()) {
                break;
            }
            StoredIndexChunk retained = group.retained().section();
            SourceSnapshot source = sources.computeIfAbsent(retained.docPath(), this::readCurrentSource);
            if (source == null || !retained.contentHash().equals(source.contentHash())) {
                invalidateStaleDocument(retained.docPath());
                continue;
            }

            int remaining = properties.getTotalContextMaxChars() - usedChars;
            List<Evidence> orderedEvidence = group.orderedEvidence();
            String content = null;
            if (retained.expandable()
                    && validRange(retained.sectionStart(), retained.sectionEnd(), source.content().length())) {
                String fullSection = source.content().substring(retained.sectionStart(), retained.sectionEnd());
                if (fullSection.length() <= remaining) {
                    content = fullSection;
                }
            }
            if (content == null) {
                content = evidenceContent(orderedEvidence, remaining, retained.headingPath());
            }
            if (content.isEmpty()) {
                continue;
            }

            List<MatchedChunk> matchedChunks = orderedEvidence.stream().map(Evidence::toDto).toList();
            List<String> matchedSectionIds = group.members().stream()
                    .map(member -> member.section().sectionId()).distinct().toList();
            results.add(new KnowledgeRetrievalResult(
                    retained.docPath(), retained.sectionId(), retained.heading(), retained.headingPath(),
                    retained.sectionStart(), retained.sectionEnd(), content, group.score(),
                    matchedSectionIds, matchedChunks));
            usedChars += content.length();
        }
        return results;
    }

    private static String evidenceContent(List<Evidence> evidence, int remaining, String headingPath) {
        if (remaining <= 0) {
            return "";
        }
        List<String> texts = evidence.stream().map(item -> item.chunk().text())
                .filter(text -> text != null && !text.isBlank()).distinct().toList();
        if (texts.isEmpty()) {
            return truncate(headingPath == null ? "" : headingPath, remaining);
        }
        String joined = String.join("\n\n", texts);
        if (joined.length() <= remaining) {
            return joined;
        }
        return truncate(texts.getFirst(), remaining);
    }

    private boolean isCatalogCurrent(StoredIndexChunk chunk) {
        KbCatalogEntry entry = catalog.findByPath(chunk.docPath());
        return entry != null && "indexed".equals(entry.getStatus())
                && chunk.contentHash().equals(entry.getContentHash())
                && chunk.contentHash().equals(entry.getChunkedHash());
    }

    private SourceSnapshot readCurrentSource(String relPath) {
        try {
            Path path = pathResolver.resolveForWrite(relPath);
            if (!Files.isRegularFile(path)) {
                return null;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return new SourceSnapshot(content, sha256(content));
        } catch (Exception e) {
            log.warn("读取检索结果原文失败: {} ({})", relPath, e.getMessage());
            return null;
        }
    }

    private void invalidateStaleDocument(String relPath) {
        try {
            indexService.invalidateDocument(relPath);
            log.warn("检测到知识文件与索引 hash 不一致，已失效旧索引: {}", relPath);
        } catch (Exception e) {
            log.warn("失效过期知识索引失败: {} ({})", relPath, e.getMessage());
        }
    }

    private void validateMetadata(KnowledgeIndexMetadata metadata, String modelId) {
        if (!KnowledgeIndexMetadata.SCHEMA_VERSION.equals(metadata.indexSchemaVersion())
                || !properties.chunkingVersion().equals(metadata.chunkingVersion())
                || !modelId.equals(metadata.embeddingModelId())) {
            throw new BusinessException("知识库索引版本或 Embedding 模型已变化，请重新构建索引");
        }
    }

    private static void mergeEvidence(Map<String, Evidence> target, Map<String, Evidence> additions) {
        for (Evidence addition : additions.values()) {
            target.merge(addition.chunk().chunkId(), addition, Evidence::merge);
        }
    }

    private static boolean validRange(int start, int end, int length) {
        return start >= 0 && end >= start && end <= length;
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || maxChars <= 0) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static String summarizeQuery(String query) {
        String singleLine = query.replaceAll("\\s+", " ");
        return singleLine.length() <= 200 ? singleLine : singleLine.substring(0, 200) + "...";
    }

    private static String describePhysicalHits(List<LuceneChunkHit> hits) {
        if (hits.isEmpty()) {
            return "[]";
        }
        return hits.stream().limit(DIAGNOSTIC_HIT_LIMIT)
                .map(hit -> "#%d score=%.4f %s [%s]".formatted(
                        hit.rank(), hit.score(), hit.chunk().docPath(), hit.chunk().headingPath()))
                .toList().toString();
    }

    private static String describeSectionHits(List<SectionHit> hits) {
        if (hits.isEmpty()) {
            return "[]";
        }
        return hits.stream().limit(DIAGNOSTIC_HIT_LIMIT)
                .map(hit -> "score=%.6f %s [%s] evidence=%d".formatted(
                        hit.score(), hit.section().docPath(), hit.section().headingPath(), hit.evidence().size()))
                .toList().toString();
    }

    private static String describeResults(List<KnowledgeRetrievalResult> results) {
        if (results.isEmpty()) {
            return "[]";
        }
        return results.stream().limit(DIAGNOSTIC_HIT_LIMIT)
                .map(result -> "score=%.6f %s [%s] chars=%d matchedSections=%d".formatted(
                        result.score(), result.docPath(), result.headingPath(), result.content().length(),
                        result.matchedSectionIds().size()))
                .toList().toString();
    }

    private static String describeGroups(List<HitGroup> groups) {
        if (groups.isEmpty()) {
            return "[]";
        }
        return groups.stream().limit(DIAGNOSTIC_HIT_LIMIT)
                .map(group -> "score=%.6f %s [%s] members=%d evidence=%d expandable=%s".formatted(
                        group.score(), group.retained().section().docPath(),
                        group.retained().section().headingPath(), group.members().size(),
                        group.orderedEvidence().size(), group.retained().section().expandable()))
                .toList().toString();
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record RouteRanking(Map<String, RouteSection> bySection,
                                int rawPhysicalHits,
                                int validPhysicalHits) {
    }

    private static final class RouteSection {
        private final int sectionRank;
        private final StoredIndexChunk representative;
        private final Map<String, Evidence> evidence = new LinkedHashMap<>();

        private RouteSection(int sectionRank, StoredIndexChunk representative) {
            this.sectionRank = sectionRank;
            this.representative = representative;
        }

        private void addEvidence(StoredIndexChunk chunk, Integer bm25Rank, Integer vectorRank) {
            evidence.merge(chunk.chunkId(), new Evidence(chunk, bm25Rank, vectorRank), Evidence::merge);
        }

        private int sectionRank() { return sectionRank; }
        private StoredIndexChunk representative() { return representative; }
        private Map<String, Evidence> evidence() { return evidence; }
    }

    private record SectionHit(StoredIndexChunk section, double score, Map<String, Evidence> evidence) {
    }

    private static final class HitGroup {
        private final SectionHit retained;
        private final List<SectionHit> members = new ArrayList<>();
        private double score;

        private HitGroup(SectionHit retained) {
            this.retained = retained;
        }

        private void addMember(SectionHit member) {
            if (members.stream().noneMatch(item -> item.section().sectionId()
                    .equals(member.section().sectionId()))) {
                members.add(member);
            }
            score = Math.max(score, member.score());
        }

        private List<Evidence> orderedEvidence() {
            Map<String, Evidence> merged = new LinkedHashMap<>();
            for (SectionHit member : members) {
                mergeEvidence(merged, member.evidence());
            }
            return merged.values().stream()
                    .sorted(Comparator.comparingDouble(Evidence::rankScore).reversed()
                            .thenComparing(item -> item.chunk().docPath())
                            .thenComparingInt(item -> item.chunk().chunkStart()))
                    .toList();
        }

        private SectionHit retained() { return retained; }
        private List<SectionHit> members() { return members; }
        private double score() { return score; }
    }

    private record Evidence(StoredIndexChunk chunk, Integer bm25Rank, Integer vectorRank) {
        private static Evidence merge(Evidence left, Evidence right) {
            return new Evidence(left.chunk,
                    bestRank(left.bm25Rank, right.bm25Rank),
                    bestRank(left.vectorRank, right.vectorRank));
        }

        private double rankScore() {
            return (bm25Rank == null ? 0.0 : 1.0 / (RRF_K + bm25Rank))
                    + (vectorRank == null ? 0.0 : 1.0 / (RRF_K + vectorRank));
        }

        private MatchedChunk toDto() {
            return new MatchedChunk(chunk.chunkId(), chunk.sectionId(), chunk.headingPath(),
                    chunk.chunkStart(), chunk.chunkEnd(), chunk.text(), bm25Rank, vectorRank);
        }

        private static Integer bestRank(Integer left, Integer right) {
            if (left == null) return right;
            if (right == null) return left;
            return Math.min(left, right);
        }
    }

    private record SourceSnapshot(String content, String contentHash) {
    }
}
