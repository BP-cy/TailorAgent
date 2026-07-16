package com.changy.tailoragent.tool.knowledge;

import com.changy.tailoragent.knowledge.service.KnowledgeService;
import com.changy.tailoragent.tool.support.KnowledgePathResolver;
import com.changy.tailoragent.tool.support.ReadFileStateService;
import com.changy.tailoragent.tool.support.ToolInputException;
import com.changy.tailoragent.tool.support.ToolText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 知识库 Write 工具（{@code kb_write_file}）—— {@link com.changy.tailoragent.tool.file.FileWriteTool}
 * 的知识库沙箱版：写入限制在 {@code dataDir()/knowledge} 内，写成功后调用
 * {@link KnowledgeService#markDirty} 标记该文档"未索引"（供手动重建索引 + 前端徽标刷新）。
 */
@Component
public class KnowledgeWriteTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeWriteTool.class);

    private final KnowledgePathResolver pathResolver;
    private final ReadFileStateService readState;
    private final KnowledgeService knowledgeService;

    public KnowledgeWriteTool(KnowledgePathResolver pathResolver,
                              ReadFileStateService readState,
                              KnowledgeService knowledgeService) {
        this.pathResolver = pathResolver;
        this.readState = readState;
        this.knowledgeService = knowledgeService;
    }

    @Tool(name = "kb_write_file", description = "将内容全量写入知识库文档(若已存在则覆盖)。覆盖已有文档前必须先用 kb_read_file 读取。" +
            "路径为知识库内相对路径(如 MD/工作/报告.md)。适合创建新文档或整体重写;局部修改优先用 kb_edit_file。")
    public String writeFile(
            @ToolParam(description = "知识库内相对路径,如 MD/工作/报告.md") String filePath,
            @ToolParam(description = "要写入的完整内容") String content) {
        try {
            Path path = pathResolver.resolveForWrite(filePath);
            boolean exists = Files.exists(path);

            if (exists) {
                if (Files.isDirectory(path)) {
                    return "错误: 目标是一个目录,无法写入: " + filePath;
                }
                String staleError = checkStale(path);
                if (staleError != null) {
                    return "错误: " + staleError;
                }
            }

            String normalized = ToolText.normalizeNewlines(content);
            String oldContent = exists ? ToolText.normalizeNewlines(
                    Files.readString(path, StandardCharsets.UTF_8)) : null;

            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, normalized, StandardCharsets.UTF_8);

            long mtime = Files.getLastModifiedTime(path).toMillis();
            readState.recordRead(path, normalized, mtime, null, null);
            knowledgeService.markDirty(knowledgeService.toRelPath(path));

            if (!exists) {
                return "文档已创建: " + filePath + "(" + ToolText.countLines(normalized) + " 行)";
            }
            int oldLines = ToolText.countLines(oldContent);
            int newLines = ToolText.countLines(normalized);
            return "文档已更新: " + filePath + "(" + oldLines + " 行 → " + newLines + " 行)";
        } catch (ToolInputException e) {
            return "错误: " + e.getMessage();
        } catch (IOException e) {
            log.warn("写入知识库文档失败: {}", e.getMessage());
            return "错误: 写入失败: " + e.getMessage();
        }
    }

    private String checkStale(Path path) throws IOException {
        Optional<ReadFileStateService.Entry> entry = readState.get(path);
        if (entry.isEmpty()) {
            return "覆盖已有文档前必须先用 kb_read_file 读取它: " + path;
        }
        ReadFileStateService.Entry e = entry.get();
        long currentMtime = Files.getLastModifiedTime(path).toMillis();
        if (currentMtime > e.mtimeMs()) {
            if (e.isFullRead()) {
                String current = ToolText.normalizeNewlines(
                        Files.readString(path, StandardCharsets.UTF_8));
                if (current.equals(e.content())) {
                    return null;
                }
            }
            return "文档自上次读取后已被修改,请重新 kb_read_file 后再写。";
        }
        return null;
    }
}
