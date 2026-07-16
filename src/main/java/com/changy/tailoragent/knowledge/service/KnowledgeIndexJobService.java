package com.changy.tailoragent.knowledge.service;

import com.changy.tailoragent.common.exception.BusinessException;
import com.changy.tailoragent.knowledge.dto.KnowledgeIndexJobStatus;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把手动知识库索引包装成单线程异步任务，并保存可轮询的文件级进度。
 *
 * <p>同一时刻只运行一个任务。重复触发时返回当前活动任务，避免同一批文件被重复
 * 向量化；具体文件仍由 {@link KnowledgeIndexService} 按计划队列顺序处理。</p>
 */
@Slf4j
@Service
public class KnowledgeIndexJobService {

    private static final int MAX_HISTORY = 20;

    private final KnowledgeIndexService indexService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "knowledge-index-queue");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, MutableJob> jobs = new LinkedHashMap<>();
    private MutableJob activeJob;

    public KnowledgeIndexJobService(KnowledgeIndexService indexService) {
        this.indexService = indexService;
    }

    /** 创建索引任务；已有任务排队或运行时直接返回该任务。 */
    public synchronized KnowledgeIndexJobStatus start(String path) {
        if (activeJob != null && !activeJob.isTerminal()) {
            return activeJob.snapshot();
        }

        String requestedPath = path == null || path.isBlank() ? null : path.strip();
        MutableJob job = new MutableJob(UUID.randomUUID().toString());
        pruneHistory();
        jobs.put(job.jobId(), job);
        activeJob = job;
        executor.submit(() -> run(job, requestedPath));
        return job.snapshot();
    }

    /** 按任务标识读取状态。 */
    public synchronized KnowledgeIndexJobStatus status(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new BusinessException("索引任务标识不能为空");
        }
        MutableJob job = jobs.get(jobId);
        if (job == null) {
            throw new BusinessException("索引任务不存在或已过期: " + jobId);
        }
        return job.snapshot();
    }

    /** 返回当前排队或运行中的任务；没有活动任务时返回 null。 */
    public synchronized KnowledgeIndexJobStatus active() {
        return activeJob == null || activeJob.isTerminal() ? null : activeJob.snapshot();
    }

    private void run(MutableJob job, String path) {
        job.markRunning();
        KnowledgeIndexProgressListener progress = new KnowledgeIndexProgressListener() {
            @Override
            public void onPlan(List<String> paths) {
                job.plan(paths);
            }

            @Override
            public void onFileStarted(String currentPath) {
                job.fileStarted(currentPath);
            }

            @Override
            public void onFileCompleted(String completedPath) {
                job.fileCompleted(completedPath);
            }

            @Override
            public void onWriting() {
                job.writing();
            }
        };

        try {
            if (path == null) {
                indexService.reindexAllDirty(progress);
            } else {
                indexService.reindex(path, progress);
            }
            job.complete();
        } catch (RuntimeException e) {
            String error = rootMessage(e);
            job.fail(error);
            log.warn("知识库索引任务失败: jobId={}, path={}, error={}", job.jobId(), path, error, e);
        } finally {
            synchronized (this) {
                if (activeJob == job) {
                    activeJob = null;
                }
            }
        }
    }

    private synchronized void pruneHistory() {
        while (jobs.size() >= MAX_HISTORY) {
            boolean removed = false;
            Iterator<Map.Entry<String, MutableJob>> iterator = jobs.entrySet().iterator();
            while (iterator.hasNext()) {
                MutableJob candidate = iterator.next().getValue();
                if (candidate != activeJob && candidate.isTerminal()) {
                    iterator.remove();
                    removed = true;
                    break;
                }
            }
            if (!removed) {
                break;
            }
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 可变状态只在本类内部使用，对外始终返回不可变 record 快照。 */
    private static final class MutableJob {
        private final String jobId;
        private String state = "queued";
        private String phase = "planning";
        private int totalFiles;
        private int completedFiles;
        private String currentPath;
        private String message = "索引任务已加入队列";
        private String error;
        private String startedAt;
        private String completedAt;

        private MutableJob(String jobId) {
            this.jobId = jobId;
        }

        private String jobId() {
            return jobId;
        }

        private synchronized void markRunning() {
            state = "running";
            phase = "planning";
            message = "正在规划索引文件队列";
            startedAt = Instant.now().toString();
        }

        private synchronized void plan(List<String> paths) {
            totalFiles = paths == null ? 0 : paths.size();
            completedFiles = 0;
            currentPath = null;
            phase = "planning";
            message = totalFiles == 0
                    ? "没有需要处理的 Markdown 文件"
                    : "已建立索引文件队列，共 " + totalFiles + " 个文件";
        }

        private synchronized void fileStarted(String path) {
            phase = "embedding";
            currentPath = path;
            int currentNumber = Math.min(completedFiles + 1, Math.max(totalFiles, 1));
            message = "正在解析、切块并向量化 " + currentNumber + " / " + totalFiles;
        }

        private synchronized void fileCompleted(String path) {
            if (totalFiles > 0) {
                completedFiles = Math.min(completedFiles + 1, totalFiles);
            }
            currentPath = path;
            message = "已完成文件准备 " + completedFiles + " / " + totalFiles;
        }

        private synchronized void writing() {
            phase = "writing";
            currentPath = null;
            message = "正在写入 Lucene 索引";
        }

        private synchronized void complete() {
            state = "completed";
            phase = "completed";
            currentPath = null;
            completedFiles = totalFiles;
            message = totalFiles == 0
                    ? "没有需要重建的 Markdown 索引"
                    : "已完成 " + totalFiles + " 个 Markdown 文件的索引";
            completedAt = Instant.now().toString();
        }

        private synchronized void fail(String failure) {
            state = "failed";
            error = failure;
            message = "索引失败: " + failure;
            completedAt = Instant.now().toString();
        }

        private synchronized boolean isTerminal() {
            return "completed".equals(state) || "failed".equals(state);
        }

        private synchronized KnowledgeIndexJobStatus snapshot() {
            return new KnowledgeIndexJobStatus(jobId, state, phase, totalFiles, completedFiles,
                    currentPath, message, error, startedAt, completedAt);
        }
    }
}
