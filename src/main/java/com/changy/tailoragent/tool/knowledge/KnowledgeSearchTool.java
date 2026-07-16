package com.changy.tailoragent.tool.knowledge;

import com.changy.tailoragent.knowledge.dto.KnowledgeRetrievalResult;
import com.changy.tailoragent.knowledge.service.KnowledgeRetrievalService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/** 主对话 Agent 使用的知识库混合检索工具。 */
@Component
@Slf4j
public class KnowledgeSearchTool {

    private final KnowledgeRetrievalService retrievalService;

    public KnowledgeSearchTool(KnowledgeRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Tool(name = "kb_search", description = "在已建立索引的 Markdown 知识库中执行关键词+语义混合检索。"
            + "返回最相关的完整章节或命中片段，并标注来源路径和标题路径。")
    public String search(@ToolParam(description = "自然语言问题或要查找的关键词") String query) {
        try {
            List<KnowledgeRetrievalResult> results = retrievalService.retrieve(query);
            if (results.isEmpty()) {
                log.info("kb_search 工具未返回匹配内容");
                return "未检索到匹配内容。若知识文件刚修改，请先在知识库面板重新构建索引。";
            }
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < results.size(); i++) {
                KnowledgeRetrievalResult result = results.get(i);
                if (i > 0) {
                    output.append("\n\n---\n\n");
                }
                output.append("来源：").append(result.docPath()).append('\n')
                        .append("章节：").append(result.headingPath()).append('\n')
                        .append("相关度：")
                        .append(String.format(java.util.Locale.ROOT, "%.6f", result.score()))
                        .append("\n\n").append(result.content());
            }
            String response = output.toString();
            log.info("kb_search 工具返回: results={}, chars={}", results.size(), response.length());
            return response;
        } catch (Exception e) {
            log.warn("kb_search 工具调用失败: {}", e.getMessage(), e);
            return "知识库检索失败: " + e.getMessage();
        }
    }
}
