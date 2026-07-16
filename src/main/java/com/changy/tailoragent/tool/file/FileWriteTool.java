package com.changy.tailoragent.tool.file;

import com.changy.tailoragent.tool.support.ReadFileStateService;
import com.changy.tailoragent.tool.support.ToolInputException;
import com.changy.tailoragent.tool.support.ToolText;
import com.changy.tailoragent.tool.support.WorkspacePathResolver;
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
 * Write 工具 —— 全量写入(创建或覆盖)文件。
 * <p>
 * 移植自 Claude Code 的 FileWriteTool:覆盖已存在文件前<b>必须先 Read</b>,且做<b>写前防陈旧</b>
 * (read 之后被外部改动则拒绝,要求重读)。Windows 上云同步/杀软可能只改 mtime 不改内容,
 * 故对全量读追加内容比对兜底,避免误报。写盘统一 LF。
 */
@Component
public class FileWriteTool {

    private static final Logger log = LoggerFactory.getLogger(FileWriteTool.class);

    private final WorkspacePathResolver pathResolver;
    private final ReadFileStateService readState;

    public FileWriteTool(WorkspacePathResolver pathResolver, ReadFileStateService readState) {
        this.pathResolver = pathResolver;
        this.readState = readState;
    }

    @Tool(name = "write_file", description = "将内容全量写入文件(若已存在则覆盖)。覆盖已有文件前必须先用 Read 读取该文件。" +
            "适合创建新文件或整体重写;对已有文件的局部修改应优先用 Edit 工具。")
    public String writeFile(
            @ToolParam(description = "文件路径;相对路径相对工作区根目录。只能写入工作区目录内的文件") String filePath,
            @ToolParam(description = "要写入的完整内容") String content) {
        try {
            Path path = pathResolver.resolveForWrite(filePath);
            boolean exists = Files.exists(path);

            if (exists) {
                if (Files.isDirectory(path)) {
                    return "错误: 目标是一个目录,无法写入: " + path;
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

            if (!exists) {
                return "文件已创建: " + path + "(" + ToolText.countLines(normalized) + " 行)";
            }
            int oldLines = ToolText.countLines(oldContent);
            int newLines = ToolText.countLines(normalized);
            return "文件已更新: " + path + "(" + oldLines + " 行 → " + newLines + " 行)";
        } catch (ToolInputException e) {
            return "错误: " + e.getMessage();
        } catch (IOException e) {
            log.warn("写入文件失败: {}", e.getMessage());
            return "错误: 写入文件失败: " + e.getMessage();
        }
    }

    /** 返回陈旧错误信息;null 表示可以写。 */
    private String checkStale(Path path) throws IOException {
        Optional<ReadFileStateService.Entry> entry = readState.get(path);
        if (entry.isEmpty()) {
            return "覆盖已有文件前必须先用 Read 工具读取它: " + path;
        }
        ReadFileStateService.Entry e = entry.get();
        long currentMtime = Files.getLastModifiedTime(path).toMillis();
        if (currentMtime > e.mtimeMs()) {
            // Windows mtime 可能在内容未变时跳动:全量读时用内容比对兜底
            if (e.isFullRead()) {
                String current = ToolText.normalizeNewlines(
                        Files.readString(path, StandardCharsets.UTF_8));
                if (current.equals(e.content())) {
                    return null; // 内容未变,放行
                }
            }
            return "文件自上次 Read 后已被修改(可能是用户或外部工具改动),请重新 Read 后再写。";
        }
        return null;
    }
}
