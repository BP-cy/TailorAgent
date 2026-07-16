package com.changy.tailoragent.knowledge.mapper;

import com.changy.tailoragent.knowledge.entity.KbCatalogEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库目录索引 MyBatis Mapper。
 * XML 映射文件：classpath:mapper/KbCatalogMapper.xml
 */
@Mapper
public interface KbCatalogMapper {

    /** 按相对路径查单条。 */
    KbCatalogEntry findByPath(@Param("path") String path);

    /** 按前缀（如 "MD/"）查该子树下全部条目。 */
    List<KbCatalogEntry> findByPrefix(@Param("prefix") String prefix);

    /** 状态为 unindexed 或 failed 的全部待建/可重试文档。 */
    List<KbCatalogEntry> findDirty();

    /** 新文件入库（若已存在则忽略），默认 unindexed。 */
    void insertIfAbsent(@Param("path") String path);

    /** 标脏：upsert 为 unindexed（写入/编辑后调用）。 */
    void markDirty(@Param("path") String path);

    /** 索引完成：写回哈希 + 置 indexed。 */
    void markIndexed(@Param("path") String path,
                     @Param("contentHash") String contentHash,
                     @Param("indexedAt") String indexedAt);

    /** 更新状态。 */
    void updateStatus(@Param("path") String path, @Param("status") String status);

    /** 删单条。 */
    void deleteByPath(@Param("path") String path);

    /** 删整个前缀子树（删文件夹时批量清行）。 */
    void deleteByPrefix(@Param("prefix") String prefix);
}
