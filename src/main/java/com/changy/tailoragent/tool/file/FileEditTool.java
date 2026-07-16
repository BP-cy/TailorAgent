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
 * Edit 工具 —— 对文件做精确字符串替换。
 * <p>
 * 移植自 Claude Code 的 FileEditTool 核心语义:
 * <ul>
 *   <li>编辑前必须先 Read,且做写前防陈旧;</li>
 *   <li>{@code oldString} 必须在文件中<b>唯一</b>,否则报错(除非 {@code replaceAll=true});</li>
 *   <li>找不到 / 新旧相同 / 替换后无变化 → 报错;</li>
 *   <li>非 {@code .md/.mdx} 文件对 {@code newString} 按行去尾部空白(md 的两个尾空格是硬换行,保留);</li>
 *   <li>纯删除({@code newString} 为空)时连带尾随换行删除,避免留空行。</li>
 * </ul>
 * 入参里的 {@code oldString} 必须是 Read 输出里<b>去掉行号前缀</b>后的纯文件内容。
 */
@Component
public class FileEditTool {

    private static final Logger log = LoggerFactory.getLogger(FileEditTool.class);

    /** 变更片段上下文行数。 */
    private static final int CONTEXT_LINES = 4;

    private final WorkspacePathResolver pathResolver;
    private final ReadFileStateService readState;

    public FileEditTool(WorkspacePathResolver pathResolver, ReadFileStateService readState) {
        this.pathResolver = pathResolver;
        this.readState = readState;
    }

    @Tool(name = "edit_file", description = "对文件做精确字符串替换。oldString 必须与文件内容逐字符匹配(不含 Read 输出的行号前缀)。" +
            "若 oldString 在文件中不唯一,需提供更多上下文使其唯一,或设 replaceAll=true 全部替换。编辑前必须先 Read 该文件。")
    public String editFile(
            @ToolParam(description = "文件路径;相对路径相对工作区根目录。只能编辑工作区目录内的文件") String filePath,
            @ToolParam(description = "要被替换的原始字符串(精确匹配,不含行号前缀)") String oldString,
            @ToolParam(description = "替换后的新字符串") String newString,
            @ToolParam(required = false, description = "为 true 时替换全部匹配(用于重命名等);默认 false") Boolean replaceAll) {
        try {
            Path path = pathResolver.resolveForWrite(filePath);
            if (!Files.exists(path)) {
                return "错误: 文件不存在: " + path + "。创建新文件请用 Write 工具。";
            }
            if (Files.isDirectory(path)) {
                return "错误: 目标是一个目录: " + path;
            }
            String staleError = checkStale(path);
            if (staleError != null) {
                return "错误: " + staleError;
            }

            boolean all = Boolean.TRUE.equals(replaceAll);
            String content = ToolText.normalizeNewlines(Files.readString(path, StandardCharsets.UTF_8));
            String oldS = ToolText.normalizeNewlines(oldString);
            String newS = ToolText.normalizeNewlines(newString);

            // 非 markdown 去除 newString 行尾空白
            boolean isMarkdown = filePath.toLowerCase().matches(".*\\.(md|mdx)$");
            if (!isMarkdown) {
                newS = stripTrailingWhitespacePerLine(newS);
            }

            if (oldS.equals(newS)) {
                return "错误: oldString 与 newString 相同,无需编辑。";
            }

            int occurrences = ToolText.countOccurrences(content, oldS);
            if (occurrences == 0) {
                return "错误: 在文件中找不到要替换的内容。请确认 oldString 与文件内容逐字符一致(含缩进)。";
            }
            if (occurrences > 1 && !all) {
                return "错误: oldString 在文件中出现了 " + occurrences + " 次,不唯一。请提供更多上下文使其唯一,或设 replaceAll=true。";
            }

            String updated = applyEdit(content, oldS, newS, all);
            if (updated.equals(content)) {
                return "错误: 替换后文件无变化。";
            }

            Files.writeString(path, updated, StandardCharsets.UTF_8);
            long mtime = Files.getLastModifiedTime(path).toMillis();
            readState.recordRead(path, updated, mtime, null, null);

            String snippet = buildSnippet(content, updated, oldS);
            String head = all
                    ? "已替换全部 " + occurrences + " 处。"
                    : "编辑成功。";
            return head + "\n文件: " + path + "\n变更片段:\n" + snippet;
        } catch (ToolInputException e) {
            return "错误: " + e.getMessage();
        } catch (IOException e) {
            log.warn("编辑文件失败: {}", e.getMessage());
            return "错误: 编辑文件失败: " + e.getMessage();
        }
    }

    /** 执行替换;处理纯删除时的尾随换行。 */
    private static String applyEdit(String content, String oldS, String newS, boolean all) {
        if (newS.isEmpty() && !oldS.endsWith("\n") && content.contains(oldS + "\n")) {
            String target = oldS + "\n";
            return all ? content.replace(target, "") : replaceFirst(content, target, "");
        }
        return all ? content.replace(oldS, newS) : replaceFirst(content, oldS, newS);
    }

    /** 字面量替换首个匹配(避免正则陷阱)。 */
    private static String replaceFirst(String content, String target, String replacement) {
        int idx = content.indexOf(target);
        if (idx < 0) {
            return content;
        }
        return content.substring(0, idx) + replacement + content.substring(idx + target.length());
    }

    /** 按行去除尾部空白,保留换行符。 */
    private static String stripTrailingWhitespacePerLine(String s) {
        String[] parts = s.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            sb.append(parts[i].replaceAll("[ \\t]+$", ""));
            if (i < parts.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** 以替换位置为中心,取 ±CONTEXT_LINES 行带行号的片段。 */
    private static String buildSnippet(String oldContent, String newContent, String oldS) {
        int idx = oldContent.indexOf(oldS);
        int startLineOfChange = idx < 0 ? 1 : (int) oldContent.substring(0, idx).chars().filter(c -> c == '\n').count() + 1;

        String[] newLines = newContent.split("\n", -1);
        int from = Math.max(1, startLineOfChange - CONTEXT_LINES);
        int to = Math.min(newLines.length, startLineOfChange + CONTEXT_LINES + ToolText.countLines(oldS));

        StringBuilder slice = new StringBuilder();
        for (int i = from - 1; i < to; i++) {
            slice.append(newLines[i]);
            if (i < to - 1) {
                slice.append('\n');
            }
        }
        return ToolText.addLineNumbers(slice.toString(), from);
    }

    /** 与 Write 同款防陈旧检查。 */
    private String checkStale(Path path) throws IOException {
        Optional<ReadFileStateService.Entry> entry = readState.get(path);
        if (entry.isEmpty()) {
            return "编辑前必须先用 Read 工具读取该文件: " + path;
        }
        ReadFileStateService.Entry e = entry.get();
        long currentMtime = Files.getLastModifiedTime(path).toMillis();
        if (currentMtime > e.mtimeMs()) {
            if (e.isFullRead()) {
                String current = ToolText.normalizeNewlines(Files.readString(path, StandardCharsets.UTF_8));
                if (current.equals(e.content())) {
                    return null;
                }
            }
            return "文件自上次 Read 后已被修改,请重新 Read 后再编辑。";
        }
        return null;
    }
}
