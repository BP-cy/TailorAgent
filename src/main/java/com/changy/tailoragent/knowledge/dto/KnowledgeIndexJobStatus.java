package com.changy.tailoragent.knowledge.dto;

/**
 * 异步知识库索引任务的只读状态。
 *
 * @param jobId          任务标识
 * @param state          queued、running、completed 或 failed
 * @param phase          planning、embedding、writing 或 completed
 * @param totalFiles     当前实际文件队列总数；整库重建时可能重新规划
 * @param completedFiles 已完成解析、切块和向量化的文件数
 * @param currentPath    当前正在处理的知识库相对路径
 * @param message        面向用户的阶段说明
 * @param error          失败时的根因摘要
 * @param startedAt      ISO-8601 开始时间
 * @param completedAt    ISO-8601 结束时间
 */
public record KnowledgeIndexJobStatus(
        String jobId,
        String state,
        String phase,
        int totalFiles,
        int completedFiles,
        String currentPath,
        String message,
        String error,
        String startedAt,
        String completedAt
) {}
