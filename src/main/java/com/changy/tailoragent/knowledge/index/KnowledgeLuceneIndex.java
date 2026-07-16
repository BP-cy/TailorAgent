package com.changy.tailoragent.knowledge.index;

import com.changy.tailoragent.web.AppPaths;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.changy.tailoragent.knowledge.index.KnowledgeLuceneFields.*;

/**
 * 知识库 Lucene 索引的唯一磁盘访问层。
 *
 * <p>桌面单用户场景下用进程内锁串行化短写事务；查询每次打开最新 commit，保证不会看到
 * 尚未提交的文档替换。索引目录是派生数据，可由 {@link #replaceAll} 整体重建。</p>
 */
@Component
public class KnowledgeLuceneIndex {

    private static final String[] BM25_FIELDS = {
            DOC_NAME_ZH, DOC_NAME_STANDARD,
            HEADING_ZH, HEADING_STANDARD,
            HEADING_PATH_ZH, HEADING_PATH_STANDARD,
            BODY_ZH, BODY_STANDARD
    };

    private static final Map<String, Float> BM25_BOOSTS = Map.of(
            DOC_NAME_ZH, 2.0f,
            DOC_NAME_STANDARD, 2.0f,
            HEADING_ZH, 4.0f,
            HEADING_STANDARD, 4.0f,
            HEADING_PATH_ZH, 2.0f,
            HEADING_PATH_STANDARD, 2.0f,
            BODY_ZH, 1.0f,
            BODY_STANDARD, 1.5f);

    private final Path indexPath;
    private final Object lock = new Object();

    public KnowledgeLuceneIndex() {
        this(AppPaths.knowledgeIndexDir());
    }

    KnowledgeLuceneIndex(Path indexPath) {
        this.indexPath = indexPath;
    }

    /** 为隔离测试或离线维护任务创建指向指定派生目录的索引实例。 */
    public static KnowledgeLuceneIndex at(Path indexPath) {
        return new KnowledgeLuceneIndex(indexPath);
    }

    public KnowledgeIndexMetadata metadata() {
        synchronized (lock) {
            try (Directory directory = openDirectory()) {
                if (!DirectoryReader.indexExists(directory)) {
                    return null;
                }
                try (DirectoryReader reader = DirectoryReader.open(directory)) {
                    IndexCommit commit = reader.getIndexCommit();
                    return KnowledgeIndexMetadata.fromMap(commit.getUserData());
                }
            } catch (IOException e) {
                throw new IllegalStateException("读取知识库索引元数据失败", e);
            }
        }
    }

    public boolean hasDocuments() {
        synchronized (lock) {
            try (Directory directory = openDirectory()) {
                if (!DirectoryReader.indexExists(directory)) {
                    return false;
                }
                try (DirectoryReader reader = DirectoryReader.open(directory)) {
                    return reader.numDocs() > 0;
                }
            } catch (IOException e) {
                throw new IllegalStateException("检查知识库索引文档失败", e);
            }
        }
    }

    public void replaceDocument(String docPath, List<Document> documents, KnowledgeIndexMetadata metadata) {
        synchronized (lock) {
            write(IndexWriterConfig.OpenMode.CREATE_OR_APPEND, writer -> {
                writer.updateDocuments(new Term(DOC_PATH, docPath), documents);
                writer.setLiveCommitData(metadata.toMap().entrySet());
            });
        }
    }

    public void replaceAll(List<Document> documents, KnowledgeIndexMetadata metadata) {
        synchronized (lock) {
            write(IndexWriterConfig.OpenMode.CREATE, writer -> {
                if (!documents.isEmpty()) {
                    writer.addDocuments(documents);
                }
                writer.setLiveCommitData(metadata.toMap().entrySet());
            });
        }
    }

    public void deleteDocument(String docPath) {
        synchronized (lock) {
            if (!indexExists()) {
                return;
            }
            write(IndexWriterConfig.OpenMode.CREATE_OR_APPEND,
                    writer -> writer.deleteDocuments(new Term(DOC_PATH, docPath)));
        }
    }

    public void deletePrefix(String docPathPrefix) {
        synchronized (lock) {
            if (!indexExists()) {
                return;
            }
            write(IndexWriterConfig.OpenMode.CREATE_OR_APPEND,
                    writer -> writer.deleteDocuments(new PrefixQuery(new Term(DOC_PATH, docPathPrefix))));
        }
    }

    /** 删除索引中的全部派生文档，保留当前 commit metadata 供下一次建索引判断版本变化。 */
    public void deleteAllDocuments() {
        synchronized (lock) {
            if (!indexExists()) {
                return;
            }
            write(IndexWriterConfig.OpenMode.CREATE_OR_APPEND, IndexWriter::deleteAll);
        }
    }

    public List<LuceneChunkHit> searchBm25(String rawQuery, int limit) {
        if (rawQuery == null || rawQuery.isBlank() || limit <= 0) {
            return List.of();
        }
        synchronized (lock) {
            try (Directory directory = openDirectory()) {
                if (!DirectoryReader.indexExists(directory)) {
                    return List.of();
                }
                try (DirectoryReader reader = DirectoryReader.open(directory);
                     Analyzer analyzer = createAnalyzer()) {
                    if (reader.numDocs() == 0) {
                        return List.of();
                    }
                    MultiFieldQueryParser parser = new MultiFieldQueryParser(BM25_FIELDS, analyzer, BM25_BOOSTS);
                    Query query = parser.parse(QueryParser.escape(rawQuery.strip()));
                    return search(new IndexSearcher(reader), query, limit);
                }
            } catch (Exception e) {
                throw new IllegalStateException("知识库 BM25 检索失败: " + e.getMessage(), e);
            }
        }
    }

    public List<LuceneChunkHit> searchVector(float[] queryVector, int limit) {
        if (queryVector == null || queryVector.length == 0 || limit <= 0) {
            return List.of();
        }
        synchronized (lock) {
            try (Directory directory = openDirectory()) {
                if (!DirectoryReader.indexExists(directory)) {
                    return List.of();
                }
                try (DirectoryReader reader = DirectoryReader.open(directory)) {
                    if (reader.numDocs() == 0) {
                        return List.of();
                    }
                    return search(new IndexSearcher(reader),
                            new KnnFloatVectorQuery(VECTOR, queryVector, limit), limit);
                }
            } catch (Exception e) {
                throw new IllegalStateException("知识库向量检索失败: " + e.getMessage(), e);
            }
        }
    }

    private List<LuceneChunkHit> search(IndexSearcher searcher, Query query, int limit) throws IOException {
        TopDocs topDocs = searcher.search(query, limit);
        List<LuceneChunkHit> hits = new ArrayList<>(topDocs.scoreDocs.length);
        for (int i = 0; i < topDocs.scoreDocs.length; i++) {
            ScoreDoc scoreDoc = topDocs.scoreDocs[i];
            Document stored = searcher.storedFields().document(scoreDoc.doc);
            hits.add(new LuceneChunkHit(toStoredChunk(stored), i + 1, scoreDoc.score));
        }
        return hits;
    }

    private static StoredIndexChunk toStoredChunk(Document document) {
        return new StoredIndexChunk(
                required(document, CHUNK_ID), required(document, DOC_PATH), required(document, CONTENT_HASH),
                required(document, SECTION_ID), document.get(PARENT_SECTION_ID),
                List.of(document.getValues(ANCESTOR_SECTION_ID)),
                intValue(document, SECTION_LEVEL), intValue(document, SECTION_ORDINAL),
                intValue(document, PART_NO), Boolean.parseBoolean(required(document, EXPANDABLE)),
                valueOrEmpty(document, HEADING), valueOrEmpty(document, HEADING_PATH),
                valueOrEmpty(document, CHUNK_TEXT), intValue(document, SECTION_START),
                intValue(document, SECTION_END), intValue(document, CHUNK_START),
                intValue(document, CHUNK_END), intValue(document, SECTION_CHAR_COUNT));
    }

    private void write(IndexWriterConfig.OpenMode mode, WriterAction action) {
        try (Directory directory = openDirectory(); Analyzer analyzer = createAnalyzer()) {
            IndexWriterConfig config = new IndexWriterConfig(analyzer)
                    .setOpenMode(mode)
                    .setCommitOnClose(false);
            IndexWriter writer = new IndexWriter(directory, config);
            try {
                action.accept(writer);
                writer.commit();
                writer.close();
            } catch (Exception e) {
                try {
                    writer.rollback();
                } catch (Exception ignored) {
                    // 保留原始异常。
                }
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("写入知识库 Lucene 索引失败: " + e.getMessage(), e);
        }
    }

    private boolean indexExists() {
        try (Directory directory = openDirectory()) {
            return DirectoryReader.indexExists(directory);
        } catch (IOException e) {
            throw new IllegalStateException("检查知识库索引失败", e);
        }
    }

    private Directory openDirectory() throws IOException {
        Files.createDirectories(indexPath);
        return FSDirectory.open(indexPath);
    }

    private static Analyzer createAnalyzer() {
        Map<String, Analyzer> perField = new HashMap<>();
        perField.put(DOC_NAME_ZH, new SmartChineseAnalyzer());
        perField.put(HEADING_ZH, new SmartChineseAnalyzer());
        perField.put(HEADING_PATH_ZH, new SmartChineseAnalyzer());
        perField.put(BODY_ZH, new SmartChineseAnalyzer());
        return new PerFieldAnalyzerWrapper(new StandardAnalyzer(), perField);
    }

    private static String required(Document document, String field) {
        String value = document.get(field);
        if (value == null) {
            throw new IllegalStateException("Lucene 文档缺少字段: " + field);
        }
        return value;
    }

    private static String valueOrEmpty(Document document, String field) {
        String value = document.get(field);
        return value == null ? "" : value;
    }

    private static int intValue(Document document, String field) {
        var value = document.getField(field);
        if (value == null || value.numericValue() == null) {
            throw new IllegalStateException("Lucene 文档缺少数值字段: " + field);
        }
        return value.numericValue().intValue();
    }

    @FunctionalInterface
    private interface WriterAction {
        void accept(IndexWriter writer) throws Exception;
    }
}
