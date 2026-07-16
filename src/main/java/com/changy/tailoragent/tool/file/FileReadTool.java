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
import java.util.Set;

/**
 * Read 工具 —— 读取本地文本文件,带 {@code cat -n} 行号返回。
 * <p>
 * 移植自 Claude Code 的 FileReadTool:支持各类源码/文本({@code .java/.py/.vue/.ts/...}),
 * 仅拦截真正的二进制格式。读取成功后记入 {@link ReadFileStateService},作为 Edit/Write
 * 的前置凭据。图片/PDF/notebook 暂不支持(v1)。
 */
@Component
public class FileReadTool {

    private static final Logger log = LoggerFactory.getLogger(FileReadTool.class);

    /** 默认读取行数(从首行起)。 */
    private static final int DEFAULT_LIMIT = 2000;
    /** 文件总字节硬上限,超出拒绝整读。 */
    private static final long HARD_MAX_BYTES = 10L * 1024 * 1024;
    /** 返回给模型的字符上限,超出截断。 */
    private static final int MAX_OUTPUT_CHARS = 100_000;

    /** 真正的二进制扩展名 —— 拦截(源码不在此列)。 */
    private static final Set<String> BINARY_EXTS = Set.of(
            "exe", "dll", "so", "dylib", "bin", "class", "jar", "war",
            "zip", "gz", "tar", "rar", "7z", "png", "jpg", "jpeg", "gif",
            "bmp", "webp", "ico", "pdf", "mp3", "mp4", "avi", "mov", "wav",
            "ttf", "otf", "woff", "woff2", "o", "a", "lib", "pdb", "db", "sqlite");

    private final WorkspacePathResolver pathResolver;
    private final ReadFileStateService readState;

    public FileReadTool(WorkspacePathResolver pathResolver, ReadFileStateService readState) {
        this.pathResolver = pathResolver;
        this.readState = readState;
    }

    @Tool(name = "read_file", description = "读取本地文件内容,以 `行号→内容` 形式返回(行号从 1 起)。支持任意文本/源码文件" +
            "(.java/.py/.vue/.ts/.md/.json 等)。默认从头读取最多 2000 行,可用 offset/limit 读取指定范围。" +
            "编辑文件前必须先用本工具读取。")
    public String readFile(
            @ToolParam(description = "文件路径;相对路径相对工作区根目录,绝对路径可读工作区外文件") String filePath,
            @ToolParam(required = false, description = "起始行号(1-based),仅在文件较大需分段读取时提供") Integer offset,
            @ToolParam(required = false, description = "读取的行数,仅在文件较大需分段读取时提供") Integer limit) {
        try {
            Path path = pathResolver.resolve(filePath);

            if (!Files.exists(path)) {
                return "错误: 文件不存在: " + path;
            }
            if (Files.isDirectory(path)) {
                return "错误: 这是一个目录,不是文件。列目录请用 Glob 工具或 Bash 的 dir/ls 命令: " + path;
            }
            String ext = extension(path);
            if (BINARY_EXTS.contains(ext)) {
                return "错误: 这是二进制文件(." + ext + "),Read 工具仅支持文本文件。";
            }

            long size = Files.size(path);
            if (size > HARD_MAX_BYTES) {
                return "错误: 文件过大(" + (size / 1024 / 1024) + "MB),超出读取上限。请改用 Grep 检索或缩小读取范围。";
            }

            String raw = Files.readString(path, StandardCharsets.UTF_8);
            String content = ToolText.normalizeNewlines(raw);
            long mtime = Files.getLastModifiedTime(path).toMillis();

            // 空文件:返回系统提醒式占位,而非空串
            if (content.isEmpty()) {
                readState.recordRead(path, content, mtime, offset, limit);
                return "<系统提示: 该文件存在但内容为空>";
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

            // 记录读取状态:始终存全量内容,供 Edit/Write 的防陈旧内容比对
            readState.recordRead(path, content, mtime, offset, limit);

            StringBuilder result = new StringBuilder(numbered);
            if (endExclusive < lines.length) {
                result.append("\n\n… [文件共 ").append(totalLines).append(" 行,本次显示第 ")
                        .append(startLine).append("–").append(endExclusive)
                        .append(" 行;如需后续内容请用 offset=").append(endExclusive + 1).append(" 继续读取] …");
            }
            return result.toString();
        } catch (ToolInputException e) {
            return "错误: " + e.getMessage();
        } catch (IOException e) {
            log.warn("读取文件失败: {}", e.getMessage());
            return "错误: 读取文件失败: " + e.getMessage();
        }
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }
}
