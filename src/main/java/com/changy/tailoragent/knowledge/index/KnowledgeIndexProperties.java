package com.changy.tailoragent.knowledge.index;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Markdown 切块、父集合返回和最终上下文预算的集中配置。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "knowledge.indexing")
public class KnowledgeIndexProperties {

    private int chunkTargetChars = 800;
    private int chunkMaxChars = 1200;
    private int chunkOverlapChars = 100;
    private int sectionMaxReturnChars = 4000;
    private int totalContextMaxChars = 12000;
    private int maxResults = 8;

    @PostConstruct
    void validate() {
        if (chunkTargetChars <= 0 || chunkMaxChars <= 0 || chunkTargetChars > chunkMaxChars) {
            throw new IllegalStateException("knowledge.indexing 的 chunk target/max 配置无效");
        }
        if (chunkOverlapChars < 0 || chunkOverlapChars >= chunkMaxChars) {
            throw new IllegalStateException("knowledge.indexing.chunk-overlap-chars 必须在 [0, chunk-max-chars) 内");
        }
        if (sectionMaxReturnChars <= 0 || totalContextMaxChars <= 0 || maxResults <= 0) {
            throw new IllegalStateException("knowledge.indexing 的返回预算必须为正数");
        }
    }

    /** 参与 commit metadata 的切块版本；任一不兼容参数变化都会得到不同值。 */
    public String chunkingVersion() {
        return "markdown-ast-v1:%d:%d:%d:%d".formatted(
                chunkTargetChars, chunkMaxChars, chunkOverlapChars, sectionMaxReturnChars);
    }
}
