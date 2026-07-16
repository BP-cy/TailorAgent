package com.changy.tailoragent.tool.file;

import com.changy.tailoragent.tool.support.ToolInputException;
import com.changy.tailoragent.tool.support.WorkspacePathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Glob 工具 —— 按名字模式匹配文件,结果按修改时间倒序返回。
 * <p>
 * 移植自 Claude Code 的 GlobTool。<b>不</b>用 JDK 的 {@link java.nio.file.PathMatcher}:
 * 它在 Windows 上有两处坑 —— {@code **&#47;} 要求路径里至少有一个分隔符(匹配不到根目录下的文件),
 * 且依赖平台的 {@code /}↔{@code \} 转换,行为脆弱。这里改为把相对路径统一规范成正斜杠,
 * 再用自实现的 glob→regex 匹配,语义对齐 picomatch/Claude Code:
 * <ul>
 *   <li>{@code **} 跨目录匹配,且可匹配<b>零层</b>目录 —— {@code **&#47;*.java} 同时命中根目录与深层文件;</li>
 *   <li>{@code *} 仅匹配同层(不含 {@code /}),{@code ?} 匹配单个非分隔符字符;</li>
 *   <li>支持 {@code {ts,tsx}} 花括号展开与 {@code [...]} 字符类;</li>
 *   <li><b>不含斜杠的模式按文件名在任意层级匹配</b>(如 {@code *.vue}、{@code Foo.java} 等价于 {@code **&#47;...})。</li>
 * </ul>
 * 自动跳过 {@code .git/node_modules/target/...} 等重目录。
 */
@Component
public class GlobTool {

    private static final Logger log = LoggerFactory.getLogger(GlobTool.class);

    private static final int LIMIT = 100;

    /** 零命中时,日志里回显已访问文件名样例的条数上限(便于排查是模式问题还是编码问题)。 */
    private static final int SAMPLE_LIMIT = 30;

    /** 遍历时跳过的重目录。 */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", "target", "dist", "build",
            ".idea", ".gradle", "out", ".next", "__pycache__");

    private final WorkspacePathResolver pathResolver;

    public GlobTool(WorkspacePathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    @Tool(name = "glob", description = "按 glob 模式查找文件,返回匹配路径(按修改时间倒序)。需要按文件名/通配符定位文件时用本工具。\n" +
            "选对模式可避免空结果:\n" +
            "1) 匹配【整个文件名】而非子串——按关键词找,关键词两侧必须加 *:找名字含「报告」的文件用 `**/*报告*`;写 `**/报告`(要求精确整名)或 `报告*`(要求以此开头)都会漏掉如「附件1:xx报告.docx」这类文件;\n" +
            "2) 递归查找加 `**/` 前缀,它同时覆盖根目录和所有子目录,如 `**/*.docx`;仅 `*.docx` 只看当前层;\n" +
            "3) 不含 `/` 的模式按文件名在任意层级匹配,如 `*.vue`、`*报告*.docx`;\n" +
            "4) `*` 匹配同层任意字符(不含 /),`?` 匹配单个字符,`{ts,tsx}` 多选扩展名。\n" +
            "示例:`**/*.java`、`src/**/*.vue`、`**/*IB00192*`、`**/*报告*.docx`。")
    public String glob(
            @ToolParam(description = "glob 模式,如 **/*.ts 或 *.vue") String pattern,
            @ToolParam(required = false, description = "搜索目录的绝对路径,省略则用工作区默认根目录") String path) {
        try {
            if (pattern == null || pattern.isBlank()) {
                return "错误: 模式不能为空。";
            }
            Path root = (path == null || path.isBlank())
                    ? pathResolver.defaultRoot()
                    : pathResolver.resolve(path);

            if (!Files.exists(root)) {
                return "错误: 目录不存在: " + root;
            }
            if (!Files.isDirectory(root)) {
                return "错误: 不是目录: " + root;
            }

            // 规范化:反斜杠统一成正斜杠,匹配一律在正斜杠域进行(与平台无关)
            String glob = pattern.trim().replace('\\', '/');
            // 不含斜杠 → 只匹配文件名(等价于 **/<pattern>,任意层级)
            boolean matchByName = glob.indexOf('/') < 0;
            String regexStr = globToRegex(glob);
            Pattern regex = Pattern.compile(regexStr);
            log.info("glob 开始: pattern=\"{}\" root={} matchByName={} regex={}", pattern, root, matchByName, regexStr);

            List<Path> matches = new ArrayList<>();
            int[] visited = {0};                    // 已访问文件计数(匿名内部类需用可变容器)
            List<String> sample = new ArrayList<>(); // 零命中时用于回显的候选样例

            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    visited[0]++;
                    String candidate = matchByName
                            ? file.getFileName().toString()
                            : root.relativize(file).toString().replace('\\', '/');
                    boolean hit = regex.matcher(candidate).matches();
                    if (hit) {
                        matches.add(file);
                    } else if (sample.size() < SAMPLE_LIMIT) {
                        sample.add(candidate);
                    }
                    // 逐文件明细走 DEBUG,默认不刷屏;需要时把该 logger 调到 DEBUG 可看每个候选与匹配结果
                    log.debug("glob 候选: \"{}\" -> {}", candidate, hit ? "命中" : "未命中");
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE; // 单个文件读属性失败不影响整体
                }
            });

            log.info("glob 遍历完成: 访问文件 {} 个, 匹配 {} 个", visited[0], matches.size());

            if (matches.isEmpty()) {
                // 零命中是最需要排查的场景:回显部分实际文件名(未匹配样例),
                // 一眼即可判断是模式写法问题(如需子串匹配却用了前缀)还是文件名编码问题。
                log.info("glob 未命中。候选:{}={},待匹配串示例(最多 {} 个): {}",
                        matchByName ? "文件名" : "相对路径", matchByName ? "basename" : "rel-path",
                        SAMPLE_LIMIT, sample);
                return "未找到匹配文件。";
            }

            matches.sort(Comparator.comparingLong(GlobTool::mtime).reversed());
            boolean truncated = matches.size() > LIMIT;
            List<Path> shown = truncated ? matches.subList(0, LIMIT) : matches;

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(matches.size()).append(" 个文件");
            if (truncated) {
                sb.append("(仅显示前 ").append(LIMIT).append(" 个,请缩小模式或目录)");
            }
            sb.append(":\n");
            for (Path p : shown) {
                sb.append(p).append('\n');
            }
            return sb.toString().stripTrailing();
        } catch (ToolInputException e) {
            return "错误: " + e.getMessage();
        } catch (IOException e) {
            log.warn("Glob 失败: {}", e.getMessage());
            return "错误: 查找文件失败: " + e.getMessage();
        }
    }

    /**
     * 把 glob 模式(正斜杠域)编译为完整匹配的正则。
     * 语义:{@code **} 跨目录(含零层),{@code *} 同层,{@code ?} 单字符,{@code {a,b}} 展开,{@code [...]} 字符类。
     */
    static String globToRegex(String glob) {
        StringBuilder re = new StringBuilder("^");
        int n = glob.length();
        int braceDepth = 0;
        for (int i = 0; i < n; i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < n && glob.charAt(i + 1) == '*') {
                        i++; // 吃掉第二个 *
                        if (i + 1 < n && glob.charAt(i + 1) == '/') {
                            i++; // 连同其后的 / 一起吃掉 —— `**/` 允许匹配零层或多层目录
                            re.append("(?:.*/)?");
                        } else {
                            re.append(".*");
                        }
                    } else {
                        re.append("[^/]*");
                    }
                }
                case '?' -> re.append("[^/]");
                case '/' -> re.append('/');
                case '{' -> {
                    re.append("(?:");
                    braceDepth++;
                }
                case '}' -> {
                    if (braceDepth > 0) {
                        re.append(')');
                        braceDepth--;
                    } else {
                        appendLiteral(re, '}');
                    }
                }
                case ',' -> {
                    if (braceDepth > 0) {
                        re.append('|');
                    } else {
                        appendLiteral(re, ',');
                    }
                }
                case '[' -> i = appendCharClass(re, glob, i);
                case '\\' -> {
                    // 转义:把下一个字符当字面量
                    if (i + 1 < n) {
                        i++;
                        appendLiteral(re, glob.charAt(i));
                    } else {
                        appendLiteral(re, '\\');
                    }
                }
                default -> appendLiteral(re, c);
            }
        }
        re.append('$');
        return re.toString();
    }

    /** 拷贝 {@code [...]} 字符类,返回消费到的下标(指向 {@code ]});无闭合则按字面 {@code [} 处理。 */
    private static int appendCharClass(StringBuilder re, String glob, int start) {
        int n = glob.length();
        int j = start + 1;
        StringBuilder cls = new StringBuilder("[");
        if (j < n && (glob.charAt(j) == '!' || glob.charAt(j) == '^')) {
            cls.append('^');
            j++;
        }
        boolean closed = false;
        for (; j < n; j++) {
            char cc = glob.charAt(j);
            if (cc == ']') {
                closed = true;
                break;
            }
            if (cc == '\\') {
                cls.append("\\\\");
            } else {
                cls.append(cc);
            }
        }
        if (closed) {
            cls.append(']');
            re.append(cls);
            return j;
        }
        // 未闭合的 '[' 视作字面量
        appendLiteral(re, '[');
        return start;
    }

    /** 追加一个字面量字符,必要时转义正则元字符。 */
    private static void appendLiteral(StringBuilder re, char c) {
        if ("\\.[]{}()*+-?^$|".indexOf(c) >= 0) {
            re.append('\\');
        }
        re.append(c);
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
