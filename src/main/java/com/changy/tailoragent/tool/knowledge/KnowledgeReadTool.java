package com.changy.tailoragent.tool.knowledge;

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
import java.util.Set;

/**
 * 知识库 Read 工具（{@code kb_read_file}）—— {@link com.changy.tailoragent.tool.file.FileReadTool}
 * 的知识库沙箱版：根为 {@link KnowledgePathResolver}（{@code dataDir()/knowledge}），与工作区文件工具隔离。
 * 供知识库 AI 编辑链路读取当前文档及跨篇引用其它文档。
 */
@Component
public class KnowledgeReadTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReadTool.class);

    private static final int DEFAULT_LIMIT = 2000;
    private static final long HARD_MAX_BYTES = 10L * 1024 * 1024;
    private static final int MAX_OUTPUT_CHARS = 100_000;

    private static final Set<String> BINARY_EXTS = Set.of(
            "exe", "dll", "so", "dylib", "bin", "class", "jar", "war",
            "zip", "gz", "tar", "rar", "7z", "png", "jpg", "jpeg", "gif",
            "bmp", "webp", "ico", "pdf", "mp3", "mp4", "avi", "mov", "wav",
            "ttf", "otf", "woff", "woff2", "o", "a", "lib", "pdb", "db", "sqlite");

    private final KnowledgePathResolver pathResolver;
    private final ReadFileStateService readState;

    public KnowledgeReadTool(KnowledgePathResolver pathResolver, ReadFileStateService readState) {
        this.pathResolver = pathResolver;
        this.readState = readState;
    }

    @Tool(name = "kb_read_file", description = "读取知识库文档内容,以 `行号→内容` 形式返回(行号从 1 起)。" +
            "路径为知识库内相对路径(如 MD/工作/报告.md)。默认从头读取最多 2000 行,可用 offset/limit 分段。" +
            "编辑文档前必须先用本工具读取。")
    public String readFile(
            @ToolParam(description = "知识库内相对路径,如 MD/工作/报告.md") String filePath,
            @ToolParam(required = false, description = "起始行号(1-based),仅在文件较大需分段读取时提供") Integer offset,
            @ToolParam(required = false, description = "读取的行数,仅在文件较大需分段读取时提供") Integer limit) {
        try {
            Path path = pathResolver.resolve(filePath);

            if (!Files.exists(path)) {
                return "错误: 文档不存在: " + filePath;
            }
            if (Files.isDirectory(path)) {
                return "错误: 这是一个目录,不是文档: " + filePath;
            }
            String ext = extension(path);
            if (BINARY_EXTS.contains(ext)) {
                return "错误: 这是二进制文件(." + ext + "),仅支持文本文档。";
            }

            long size = Files.size(path);
            if (size > HARD_MAX_BYTES) {
                return "错误: 文件过大(" + (size / 1024 / 1024) + "MB),超出读取上限。";
            }

            String raw = Files.readString(path, StandardCharsets.UTF_8);
            String content = ToolText.normalizeNewlines(raw);
            long mtime = Files.getLastModifiedTime(path).toMillis();

            if (content.isEmpty()) {
                readState.recordRead(path, content, mtime, offset, limit);
                return "<系统提示: 该文档存在但内容为空>";
            }

            int totalLines = ToolText.countLines(content);
            int startLine = (offset == null || offset < 1) ? 1 : offset;
            int count = (limit == null || limit < 1) ? DEFAULT_LIMIT : limit;

            String[] lines = content.split("\n", -1);
            if (startLine > lines.length) {
                return "错误: 起始行 " + startLine + " 超出文件总行数 " + totalLines + "。";
            }
            int endExclusive = Math.min(lines.length, startLine - 1 + count);

            StringBuilder slice = new StringBuilder();
            for (int i = startLine - 1; i < endExclusive; i++) {
                slice.append(lines[i]);
                if (i < endExclusive - 1) {
                    slice.append('\n');
                }
            }

            String numbered = ToolText.addLineNumbers(slice.toString(), startLine);
            numbered = ToolText.truncate(numbered, MAX_OUTPUT_CHARS);

            readState.recordRead(path, content, mtime, offset, limit);

            StringBuilder result = new StringBuilder(numbered);
            if (endExclusive < lines.length) {
                result.append("\n\n… [文档共 ").append(totalLines).append(" 行,本次显示第 ")
                        .append(startLine).append("–").append(endExclusive)
                        .append(" 行;如需后续内容请用 offset=").append(endExclusive + 1).append(" 继续读取] …");
            }
            return result.toString();
        } catch (ToolInputException e) {
            return "错误: " + e.getMessage();
        } catch (IOException e) {
            log.warn("读取知识库文档失败: {}", e.getMessage());
            return "错误: 读取失败: " + e.getMessage();
        }
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }
}
