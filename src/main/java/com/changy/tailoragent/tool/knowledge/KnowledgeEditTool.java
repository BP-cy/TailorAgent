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
 * 知识库 Edit 工具（{@code kb_edit_file}）—— {@link com.changy.tailoragent.tool.file.FileEditTool}
 * 的知识库沙箱版：精确字符串替换 + 唯一性约束 + 防陈旧，编辑成功后
 * {@link KnowledgeService#markDirty} 标脏。这是 AI 编辑文档的主力工具（最小编辑，diff 噪音小）。
 */
@Component
public class KnowledgeEditTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEditTool.class);

    private static final int CONTEXT_LINES = 4;

    private final KnowledgePathResolver pathResolver;
    private final ReadFileStateService readState;
    private final KnowledgeService knowledgeService;

    public KnowledgeEditTool(KnowledgePathResolver pathResolver,
                             ReadFileStateService readState,
                             KnowledgeService knowledgeService) {
        this.pathResolver = pathResolver;
        this.readState = readState;
        this.knowledgeService = knowledgeService;
    }

    @Tool(name = "kb_edit_file", description = "对知识库文档做精确字符串替换。oldString 必须与文档内容逐字符匹配(不含 kb_read_file 输出的行号前缀)。" +
            "若 oldString 不唯一,需提供更多上下文或设 replaceAll=true。编辑前必须先 kb_read_file 该文档。路径为知识库内相对路径。")
    public String editFile(
            @ToolParam(description = "知识库内相对路径,如 MD/工作/报告.md") String filePath,
            @ToolParam(description = "要被替换的原始字符串(精确匹配,不含行号前缀)") String oldString,
            @ToolParam(description = "替换后的新字符串") String newString,
            @ToolParam(required = false, description = "为 true 时替换全部匹配;默认 false") Boolean replaceAll) {
        try {
            Path path = pathResolver.resolveForWrite(filePath);
            if (!Files.exists(path)) {
                return "错误: 文档不存在: " + filePath + "。创建新文档请用 kb_write_file。";
            }
            if (Files.isDirectory(path)) {
                return "错误: 目标是一个目录: " + filePath;
            }
            String staleError = checkStale(path);
            if (staleError != null) {
                return "错误: " + staleError;
            }

            boolean all = Boolean.TRUE.equals(replaceAll);
            String content = ToolText.normalizeNewlines(Files.readString(path, StandardCharsets.UTF_8));
            String oldS = ToolText.normalizeNewlines(oldString);
            String newS = ToolText.normalizeNewlines(newString);

            boolean isMarkdown = filePath.toLowerCase().matches(".*\\.(md|mdx)$");
            if (!isMarkdown) {
                newS = stripTrailingWhitespacePerLine(newS);
            }

            if (oldS.equals(newS)) {
                return "错误: oldString 与 newString 相同,无需编辑。";
            }

            int occurrences = ToolText.countOccurrences(content, oldS);
            if (occurrences == 0) {
                return "错误: 在文档中找不到要替换的内容。请确认 oldString 与文档内容逐字符一致(含缩进)。";
            }
            if (occurrences > 1 && !all) {
                return "错误: oldString 在文档中出现了 " + occurrences + " 次,不唯一。请提供更多上下文使其唯一,或设 replaceAll=true。";
            }

            String updated = applyEdit(content, oldS, newS, all);
            if (updated.equals(content)) {
                return "错误: 替换后文档无变化。";
            }

            Files.writeString(path, updated, StandardCharsets.UTF_8);
            long mtime = Files.getLastModifiedTime(path).toMillis();
            readState.recordRead(path, updated, mtime, null, null);
            knowledgeService.markDirty(knowledgeService.toRelPath(path));

            String snippet = buildSnippet(content, updated, oldS);
            String head = all ? "已替换全部 " + occurrences + " 处。" : "编辑成功。";
            return head + "\n文档: " + filePath + "\n变更片段:\n" + snippet;
        } catch (ToolInputException e) {
            return "错误: " + e.getMessage();
        } catch (IOException e) {
            log.warn("编辑知识库文档失败: {}", e.getMessage());
            return "错误: 编辑失败: " + e.getMessage();
        }
    }

    private static String applyEdit(String content, String oldS, String newS, boolean all) {
        if (newS.isEmpty() && !oldS.endsWith("\n") && content.contains(oldS + "\n")) {
            String target = oldS + "\n";
            return all ? content.replace(target, "") : replaceFirst(content, target, "");
        }
        return all ? content.replace(oldS, newS) : replaceFirst(content, oldS, newS);
    }

    private static String replaceFirst(String content, String target, String replacement) {
        int idx = content.indexOf(target);
        if (idx < 0) {
            return content;
        }
        return content.substring(0, idx) + replacement + content.substring(idx + target.length());
    }

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

    private String checkStale(Path path) throws IOException {
        Optional<ReadFileStateService.Entry> entry = readState.get(path);
        if (entry.isEmpty()) {
            return "编辑前必须先用 kb_read_file 读取该文档: " + path;
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
            return "文档自上次读取后已被修改,请重新 kb_read_file 后再编辑。";
        }
        return null;
    }
}
