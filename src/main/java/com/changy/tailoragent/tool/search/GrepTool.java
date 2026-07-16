package com.changy.tailoragent.tool.search;

import com.changy.tailoragent.env.CommandRunner;
import com.changy.tailoragent.tool.support.RipgrepProvisioner;
import com.changy.tailoragent.tool.support.ToolInputException;
import com.changy.tailoragent.tool.support.WorkspacePathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Grep 工具 —— 在文件内容里做正则检索,基于 ripgrep。
 * <p>
 * 移植自 Claude Code 的 GrepTool。优先调用打包内置的 ripgrep(由 {@link RipgrepProvisioner}
 * 供给);若 rg 不可用或执行异常,自动回退到纯 Java 实现(保证离线永不硬失败)。
 * <p>
 * 三种输出模式:{@code files_with_matches}(默认,仅文件名)、{@code content}(匹配行,
 * 支持上下文/行号)、{@code count}(每文件命中数)。
 */
@Component
public class GrepTool {

    private static final Logger log = LoggerFactory.getLogger(GrepTool.class);

    private static final int DEFAULT_HEAD_LIMIT = 250;
    private static final int MAX_COLUMNS = 500;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Set<String> VCS_DIRS = Set.of(".git", ".svn", ".hg", ".bzr", ".jj", ".sl");
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", "target", "dist", "build",
            ".idea", ".gradle", "out", ".next", "__pycache__");

    private final WorkspacePathResolver pathResolver;
    private final RipgrepProvisioner ripgrep;
    private final CommandRunner commandRunner;

    public GrepTool(WorkspacePathResolver pathResolver, RipgrepProvisioner ripgrep, CommandRunner commandRunner) {
        this.pathResolver = pathResolver;
        this.ripgrep = ripgrep;
        this.commandRunner = commandRunner;
    }

    @Tool(name = "grep", description = "在文件内容中用正则检索(基于 ripgrep)。用于写代码时定位符号、用法、文本。" +
            "outputMode: files_with_matches(默认,只列文件)、content(列匹配行)、count(每文件命中数)。" +
            "可用 glob(如 *.java)或按目录缩小范围。")
    public String grep(
            @ToolParam(description = "正则表达式") String pattern,
            @ToolParam(required = false, description = "搜索目录或文件的绝对路径,省略则用工作区默认根目录") String path,
            @ToolParam(required = false, description = "文件名过滤 glob,如 *.java、*.{ts,tsx}") String glob,
            @ToolParam(required = false, description = "输出模式: files_with_matches / content / count,默认 files_with_matches") String outputMode,
            @ToolParam(required = false, description = "true 为忽略大小写") Boolean caseInsensitive,
            @ToolParam(required = false, description = "content 模式下每个匹配前后展示的行数") Integer contextLines,
            @ToolParam(required = false, description = "结果条数上限,默认 250") Integer headLimit) {
        try {
            Path target = (path == null || path.isBlank())
                    ? pathResolver.defaultRoot()
                    : pathResolver.resolve(path);
            if (!Files.exists(target)) {
                return "错误: 路径不存在: " + target;
            }

            String mode = normalizeMode(outputMode);
            boolean ci = Boolean.TRUE.equals(caseInsensitive);
            int ctx = (contextLines == null || contextLines < 0) ? 0 : contextLines;
            int limit = (headLimit == null || headLimit < 0) ? DEFAULT_HEAD_LIMIT : headLimit;

            Optional<Path> rg = ripgrep.rgPath();
            if (rg.isPresent()) {
                try {
                    return runRipgrep(rg.get(), pattern, target, glob, mode, ci, ctx, limit);
                } catch (Exception e) {
                    log.warn("ripgrep 执行异常,回退 Java 实现: {}", e.getMessage());
                }
            }
            return runJava(pattern, target, glob, mode, ci, ctx, limit);
        } catch (ToolInputException e) {
            return "错误: " + e.getMessage();
        } catch (PatternSyntaxException e) {
            return "错误: 正则表达式非法: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------------
    // ripgrep 路径
    // ---------------------------------------------------------------------

    private String runRipgrep(Path rg, String pattern, Path target, String glob,
                              String mode, boolean ci, int ctx, int limit) {
        boolean targetIsFile = Files.isRegularFile(target);
        Path workingDir = targetIsFile ? target.getParent() : target;
        String searchArg = targetIsFile ? target.getFileName().toString() : ".";

        List<String> cmd = new ArrayList<>();
        cmd.add(rg.toString());
        cmd.add("--hidden");
        cmd.add("--max-columns");
        cmd.add(String.valueOf(MAX_COLUMNS));
        for (String dir : VCS_DIRS) {
            cmd.add("--glob");
            cmd.add("!" + dir);
        }
        if (ci) {
            cmd.add("-i");
        }
        switch (mode) {
            case "files_with_matches" -> cmd.add("-l");
            case "count" -> cmd.add("-c");
            case "content" -> {
                cmd.add("-n");
                if (ctx > 0) {
                    cmd.add("-C");
                    cmd.add(String.valueOf(ctx));
                }
            }
            default -> { }
        }
        if (glob != null && !glob.isBlank()) {
            for (String g : glob.split("\\s+")) {
                if (!g.isBlank()) {
                    cmd.add("--glob");
                    cmd.add(g);
                }
            }
        }
        if (pattern.startsWith("-")) {
            cmd.add("-e");
        }
        cmd.add(pattern);
        cmd.add(searchArg);

        CommandRunner.Result r = commandRunner.runExec(TIMEOUT, workingDir, StandardCharsets.UTF_8, cmd);
        if (r.timedOut()) {
            return "错误: 搜索超时(30s),请缩小范围或细化模式。";
        }
        // rg 退出码: 0=有匹配, 1=无匹配, 2=错误
        if (r.exitCode() == 1) {
            return mode.equals("content") ? "未找到匹配。" : "未找到匹配的文件。";
        }
        if (r.exitCode() != 0) {
            throw new IllegalStateException(r.stderr().isBlank() ? "rg 退出码 " + r.exitCode() : r.stderr());
        }

        List<String> lines = r.stdout().isEmpty() ? List.of() : List.of(r.stdout().split("\n"));
        return formatLines(lines, mode, limit);
    }

    // ---------------------------------------------------------------------
    // 纯 Java 回退
    // ---------------------------------------------------------------------

    private String runJava(String pattern, Path target, String glob, String mode,
                           boolean ci, int ctx, int limit) {
        int flags = Pattern.UNICODE_CASE | (ci ? Pattern.CASE_INSENSITIVE : 0);
        Pattern re = Pattern.compile(pattern, flags);
        PathMatcher globMatcher = (glob == null || glob.isBlank())
                ? null : FileSystems.getDefault().getPathMatcher("glob:" + glob);

        Path root = Files.isRegularFile(target) ? target.getParent() : target;
        List<String> out = new ArrayList<>();
        int[] totalMatches = {0};
        int[] fileCount = {0};

        try {
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(target) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (globMatcher != null) {
                        Path rel = root.relativize(file);
                        if (!globMatcher.matches(rel) && !globMatcher.matches(file.getFileName())) {
                            return FileVisitResult.CONTINUE;
                        }
                    }
                    searchFile(file, root, re, mode, ctx, out, totalMatches, fileCount);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return "错误: 搜索失败: " + e.getMessage();
        }

        if (out.isEmpty()) {
            return mode.equals("content") ? "未找到匹配。" : "未找到匹配的文件。";
        }
        if (mode.equals("count")) {
            String body = formatLines(out, mode, limit);
            return body + "\n\n共 " + totalMatches[0] + " 处匹配,across " + fileCount[0] + " 个文件。";
        }
        return formatLines(out, mode, limit);
    }

    /** 在单个文件中按模式匹配,按 mode 收集结果行。 */
    private void searchFile(Path file, Path root, Pattern re, String mode, int ctx,
                            List<String> out, int[] totalMatches, int[] fileCount) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return; // 二进制/非 UTF-8 文件跳过
        }
        String rel = root.relativize(file).toString();
        int fileHits = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (re.matcher(lines.get(i)).find()) {
                fileHits++;
                if (mode.equals("content")) {
                    for (int c = Math.max(0, i - ctx); c <= Math.min(lines.size() - 1, i + ctx); c++) {
                        String text = clip(lines.get(c));
                        out.add(rel + ":" + (c + 1) + ":" + text);
                    }
                }
            }
        }
        if (fileHits > 0) {
            totalMatches[0] += fileHits;
            fileCount[0]++;
            if (mode.equals("files_with_matches")) {
                out.add(rel);
            } else if (mode.equals("count")) {
                out.add(rel + ":" + fileHits);
            }
        }
    }

    // ---------------------------------------------------------------------
    // 公共
    // ---------------------------------------------------------------------

    private static String normalizeMode(String mode) {
        if (mode == null) return "files_with_matches";
        return switch (mode.trim()) {
            case "content", "count", "files_with_matches" -> mode.trim();
            default -> "files_with_matches";
        };
    }

    private static String clip(String line) {
        return line.length() > MAX_COLUMNS ? line.substring(0, MAX_COLUMNS) + "…" : line;
    }

    /** 应用 headLimit 并拼接输出。 */
    private static String formatLines(List<String> lines, String mode, int limit) {
        boolean truncated = limit > 0 && lines.size() > limit;
        List<String> shown = truncated ? lines.subList(0, limit) : lines;
        StringBuilder sb = new StringBuilder();
        if (mode.equals("files_with_matches")) {
            sb.append("找到 ").append(lines.size()).append(" 个文件:\n");
        }
        sb.append(String.join("\n", shown));
        if (truncated) {
            sb.append("\n\n… [结果已截断为前 ").append(limit).append(" 条,请细化模式或缩小范围] …");
        }
        return sb.toString();
    }
}
