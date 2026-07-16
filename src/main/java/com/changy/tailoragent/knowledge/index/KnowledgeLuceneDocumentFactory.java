package com.changy.tailoragent.knowledge.index;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.springframework.stereotype.Component;

import static com.changy.tailoragent.knowledge.index.KnowledgeLuceneFields.*;

/** 把物理块及向量转换为一条完整的 Lucene Document。 */
@Component
public class KnowledgeLuceneDocumentFactory {

    public Document create(IndexChunk chunk, String contentHash, float[] vector) {
        MarkdownSection section = chunk.section();
        Document document = new Document();

        document.add(new StringField(CHUNK_ID, chunk.chunkId(), org.apache.lucene.document.Field.Store.YES));
        document.add(new StringField(DOC_PATH, section.docPath(), org.apache.lucene.document.Field.Store.YES));
        document.add(new StringField(CONTENT_HASH, contentHash, org.apache.lucene.document.Field.Store.YES));
        document.add(new StringField(SECTION_ID, section.sectionId(), org.apache.lucene.document.Field.Store.YES));
        if (section.parentSectionId() != null) {
            document.add(new StringField(PARENT_SECTION_ID, section.parentSectionId(),
                    org.apache.lucene.document.Field.Store.YES));
        }
        for (String ancestor : section.ancestorSectionIds()) {
            document.add(new StringField(ANCESTOR_SECTION_ID, ancestor,
                    org.apache.lucene.document.Field.Store.YES));
        }
        addInt(document, SECTION_LEVEL, section.level());
        addInt(document, SECTION_ORDINAL, section.ordinal());
        addInt(document, PART_NO, chunk.partNo());
        document.add(new StringField(EXPANDABLE, Boolean.toString(section.expandable()),
                org.apache.lucene.document.Field.Store.YES));

        String docName = fileName(section.docPath());
        addTextPair(document, DOC_NAME_ZH, DOC_NAME_STANDARD, docName);
        addTextPair(document, HEADING_ZH, HEADING_STANDARD, section.heading());
        addTextPair(document, HEADING_PATH_ZH, HEADING_PATH_STANDARD, section.headingPath());
        addTextPair(document, BODY_ZH, BODY_STANDARD, chunk.text());
        document.add(new KnnFloatVectorField(VECTOR, vector, VectorSimilarityFunction.COSINE));

        document.add(new StoredField(HEADING, section.heading()));
        document.add(new StoredField(HEADING_PATH, section.headingPath()));
        document.add(new StoredField(CHUNK_TEXT, chunk.text()));
        document.add(new StoredField(SECTION_START, section.sectionStart()));
        document.add(new StoredField(SECTION_END, section.sectionEnd()));
        document.add(new StoredField(CHUNK_START, chunk.chunkStart()));
        document.add(new StoredField(CHUNK_END, chunk.chunkEnd()));
        document.add(new StoredField(SECTION_CHAR_COUNT, section.sectionCharCount()));
        return document;
    }

    private static void addTextPair(Document document, String zhField, String standardField, String value) {
        String safe = value == null ? "" : value;
        document.add(new TextField(zhField, safe, org.apache.lucene.document.Field.Store.NO));
        document.add(new TextField(standardField, safe, org.apache.lucene.document.Field.Store.NO));
    }

    private static void addInt(Document document, String field, int value) {
        document.add(new IntPoint(field, value));
        document.add(new StoredField(field, value));
    }

    private static String fileName(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }
}
