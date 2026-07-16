package com.changy.tailoragent.knowledge.index;

/** Lucene 字段名的唯一来源，避免索引与查询两侧拼写漂移。 */
public final class KnowledgeLuceneFields {

    private KnowledgeLuceneFields() {
    }

    public static final String CHUNK_ID = "chunk_id";
    public static final String DOC_PATH = "doc_path";
    public static final String CONTENT_HASH = "content_hash";
    public static final String SECTION_ID = "section_id";
    public static final String PARENT_SECTION_ID = "parent_section_id";
    public static final String ANCESTOR_SECTION_ID = "ancestor_section_id";
    public static final String SECTION_LEVEL = "section_level";
    public static final String SECTION_ORDINAL = "section_ordinal";
    public static final String PART_NO = "part_no";
    public static final String EXPANDABLE = "expandable";

    public static final String DOC_NAME_ZH = "doc_name_zh";
    public static final String DOC_NAME_STANDARD = "doc_name_standard";
    public static final String HEADING_ZH = "heading_zh";
    public static final String HEADING_STANDARD = "heading_standard";
    public static final String HEADING_PATH_ZH = "heading_path_zh";
    public static final String HEADING_PATH_STANDARD = "heading_path_standard";
    public static final String BODY_ZH = "body_zh";
    public static final String BODY_STANDARD = "body_standard";
    public static final String VECTOR = "vector";

    public static final String HEADING = "heading";
    public static final String HEADING_PATH = "heading_path";
    public static final String CHUNK_TEXT = "chunk_text";
    public static final String SECTION_START = "section_start";
    public static final String SECTION_END = "section_end";
    public static final String CHUNK_START = "chunk_start";
    public static final String CHUNK_END = "chunk_end";
    public static final String SECTION_CHAR_COUNT = "section_char_count";
}
