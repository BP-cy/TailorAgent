package com.changy.tailoragent.knowledge.controller;

import com.changy.tailoragent.common.response.ApiResponse;
import com.changy.tailoragent.knowledge.dto.KbDoc;
import com.changy.tailoragent.knowledge.dto.KbNode;
import com.changy.tailoragent.knowledge.dto.KnowledgeIndexJobStatus;
import com.changy.tailoragent.knowledge.dto.KnowledgeRetrievalRequest;
import com.changy.tailoragent.knowledge.dto.KnowledgeRetrievalResult;
import com.changy.tailoragent.knowledge.service.KnowledgeIndexJobService;
import com.changy.tailoragent.knowledge.service.KnowledgeRetrievalService;
import com.changy.tailoragent.knowledge.service.KnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库控制器 —— 目录树 + 文档/文件夹 CRUD + 手动索引触发。
 *
 * <p>文档以<b>相对路径</b>为标识（形如 {@code MD/工作/报告.md}），无自增 id。正文真相源在磁盘。
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService service;
    private final KnowledgeIndexJobService indexJobService;
    private final KnowledgeRetrievalService retrievalService;

    public KnowledgeController(KnowledgeService service,
                               KnowledgeIndexJobService indexJobService,
                               KnowledgeRetrievalService retrievalService) {
        this.service = service;
        this.indexJobService = indexJobService;
        this.retrievalService = retrievalService;
    }

    /** 目录树：type = MD | files（默认 MD）。 */
    @GetMapping("/tree")
    public ApiResponse<List<KbNode>> tree(@RequestParam(defaultValue = "MD") String type) {
        log.info("GET /api/knowledge/tree — type={}", type);
        return ApiResponse.success(service.tree(type));
    }

    /** 读单篇正文。 */
    @GetMapping("/doc")
    public ApiResponse<KbDoc> read(@RequestParam String path) {
        log.info("GET /api/knowledge/doc — path={}", path);
        return ApiResponse.success(service.read(path));
    }

    /** 新建文档。 */
    @PostMapping("/doc")
    public ApiResponse<KbDoc> create(@RequestBody CreateDocRequest req) {
        log.info("POST /api/knowledge/doc — path={}", req.path());
        return ApiResponse.success(service.create(req.path(), req.content()));
    }

    /** 保存文档（覆盖写 + 标脏）。 */
    @PutMapping("/doc")
    public ApiResponse<String> save(@RequestParam String path, @RequestBody SaveDocRequest req) {
        log.info("PUT /api/knowledge/doc — path={}", path);
        service.save(path, req.content());
        return ApiResponse.success("保存成功");
    }

    /** 导入上传文件到某文件夹（folder = MD/files 下的相对目录，根即 MD/files）；支持一次批量多文件。 */
    @PostMapping("/upload")
    public ApiResponse<Integer> upload(@RequestParam String folder,
                                       @RequestParam("files") MultipartFile[] files) {
        log.info("POST /api/knowledge/upload — folder={}, count={}", folder, files == null ? 0 : files.length);
        int n = 0;
        if (files != null) {
            for (MultipartFile f : files) {
                if (f != null && !f.isEmpty()) {
                    service.upload(folder, f);
                    n++;
                }
            }
        }
        return ApiResponse.success("已导入 " + n + " 个文件", n);
    }

    /** 新建文件夹。 */
    @PostMapping("/folder")
    public ApiResponse<String> mkdir(@RequestBody PathRequest req) {
        log.info("POST /api/knowledge/folder — path={}", req.path());
        service.mkdir(req.path());
        return ApiResponse.success("创建成功");
    }

    /** 移动/重命名。 */
    @PostMapping("/rename")
    public ApiResponse<String> rename(@RequestBody RenameRequest req) {
        log.info("POST /api/knowledge/rename — {} → {}", req.from(), req.to());
        service.rename(req.from(), req.to());
        return ApiResponse.success("移动成功");
    }

    /** 删除文件或文件夹。 */
    @DeleteMapping("/doc")
    public ApiResponse<String> delete(@RequestParam String path) {
        log.info("DELETE /api/knowledge/doc — path={}", path);
        service.delete(path);
        return ApiResponse.success("删除成功");
    }

    /** 手动触发异步索引：带 path 只重建该篇，否则建立全部待索引 Markdown 的文件队列。 */
    @PostMapping("/index")
    public ApiResponse<KnowledgeIndexJobStatus> index(@RequestBody(required = false) PathRequest req) {
        String path = req == null ? null : req.path();
        if (req != null && req.path() != null && !req.path().isBlank()) {
            log.info("POST /api/knowledge/index — path={}", req.path());
        } else {
            log.info("POST /api/knowledge/index — 全部未索引");
        }
        KnowledgeIndexJobStatus status = indexJobService.start(path);
        return ApiResponse.success("索引任务已启动", status);
    }

    /** 查询指定异步索引任务的当前进度。 */
    @GetMapping("/index/status")
    public ApiResponse<KnowledgeIndexJobStatus> indexStatus(@RequestParam String jobId) {
        return ApiResponse.success(indexJobService.status(jobId));
    }

    /** 查询当前仍在排队或运行的索引任务，供知识库面板重新打开后恢复进度。 */
    @GetMapping("/index/active")
    public ApiResponse<KnowledgeIndexJobStatus> activeIndex() {
        KnowledgeIndexJobStatus status = indexJobService.active();
        return ApiResponse.success(status == null ? "当前没有活动索引任务" : "存在活动索引任务", status);
    }

    /** BM25 + KNN + RRF 的 Markdown 父子章节混合检索。 */
    @PostMapping("/retrieve")
    public ApiResponse<List<KnowledgeRetrievalResult>> retrieve(@RequestBody KnowledgeRetrievalRequest req) {
        return ApiResponse.success(retrievalService.retrieve(req == null ? null : req.query()));
    }

    // ==================== 请求体 ====================

    public record CreateDocRequest(String path, String content) {}
    public record SaveDocRequest(String content) {}
    public record PathRequest(String path) {}
    public record RenameRequest(String from, String to) {}
}
