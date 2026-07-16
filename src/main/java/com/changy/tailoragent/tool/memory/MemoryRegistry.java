package com.changy.tailoragent.tool.memory;

import com.changy.tailoragent.ModelConfig.service.AppConfigService;
import com.changy.tailoragent.tool.support.ToolInputException;
import com.changy.tailoragent.tool.support.ToolText;
import com.changy.tailoragent.web.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 记忆库注册表 —— 跨会话记忆的目录所有者与读写收口(对标 {@code SkillRegistry})。
 * <p>
 * <b>按工作区隔离</b>:记忆落在 {@code AppPaths.dataDir()/memory/<工作区名>/} 下,而非全局。
 * 工作区名由当前 {@code config.workingDir} 实时清洗得到(如 {@code C:\Users\Lenovo\Desktop\staticblog}
 * → {@code C-Users-Lenovo-Desktop-staticblog}),切换工作区即时生效。这与 {@code WorkspacePathResolver}
 * 取根目录是同一个源,但记忆目录在 {@code dataDir()} 下、<b>位于工作区之外</b>,因此不复用文件工具的写沙箱
 * —— 记忆的读写一律经本类自有的路径收口 {@link #resolveUnderRoot(String)}。
 * <p>
 * 二级结构由<b>提示词规则</b>约定、模型自行维护:{@code MEMORY.md}(总索引)→ 板块文件 → 条目文件。
 * 本类不解析 frontmatter、不自动重建索引,只做「记忆目录作用域内的简单文件 CRUD」+ 索引注入。
 */
@Component
public class MemoryRegistry {

    private static final Logger log = LoggerFactory.getLogger(MemoryRegistry.class);

    /** 总索引文件名 */
    private static final String INDEX_FILE = "MEMORY.md";
    /** 工作区未设置时的兜底目录名 */
    private static final String DEFAULT_WS = "default";
    /** 读取记忆内容的字符上限(超出在行边界截断) */
    private static final int MAX_READ_CHARS = 60_000;

    private final AppConfigService appConfigService;

    public MemoryRegistry(AppConfigService appConfigService) {
        this.appConfigService = appConfigService;
    }

    // ==================== 目录解析 ====================

    /**
     * 当前工作区的记忆根目录:{@code dataDir()/memory/<工作区名>/}。
     * 每次调用实时计算(模仿 {@code WorkspacePathResolver#currentRoot()}),使切换工作区后立即生效;
     * 并尽力确保目录存在(创建失败仅 warn,不硬失败)。
     */
    private Path memoryRoot() {
        String workingDir = appConfigService.getConfig().getWorkingDir();
        String ws = sanitizeWorkspaceName(workingDir);
        Path root = AppPaths.dataDir().resolve("memory").resolve(ws).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.warn("创建记忆目录失败: {} ({})", root, e.getMessage());
        }
        return root;
    }

    /**
     * 工作区路径 → 目录名:去盘符冒号,把 {@code \ / :} 等分隔符折叠为单个 {@code -},去首尾 {@code -}。
     * 例:{@code C:\Users\Lenovo\Desktop\staticblog} → {@code C-Users-Lenovo-Desktop-staticblog}。
     * 空白或清洗后为空时回退到 {@link #DEFAULT_WS}。
     */
    static String sanitizeWorkspaceName(String workingDir) {
        if (workingDir == null || workingDir.isBlank()) {
            return DEFAULT_WS;
        }
        // 非字母数字(含 : \ / 空格等)统一视作分隔符 → 折叠为单个 '-'
        String s = workingDir.strip()
                .replaceAll("[^\\p{Alnum}\\p{IsHan}]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return s.isBlank() ? DEFAULT_WS : s;
    }

    // ==================== 对外:索引注入 ====================

    /** 渲染总索引(进系统提示词):返回 {@code MEMORY.md} 内容,不存在时给占位说明。 */
    public String renderIndex() {
        Path index = memoryRoot().resolve(INDEX_FILE);
        if (!Files.isRegularFile(index)) {
            return "（暂无记忆,可在需要时通过 memory_write 创建,并建立 " + INDEX_FILE + " 索引）";
        }
        try {
            String content = Files.readString(index, StandardCharsets.UTF_8).strip();
            return content.isBlank()
                    ? "（" + INDEX_FILE + " 为空)"
                    : ToolText.truncate(content, MAX_READ_CHARS);
        } catch (IOException e) {
            log.warn("读取记忆索引失败: {} ({})", index, e.getMessage());
            return "（读取记忆索引失败）";
        }
    }

    // ==================== 对外:CRUD ====================

    /** 读取一条记忆文件的完整内容(超长截断);不存在返回提示串。 */
    public String read(String name) {
        Path target = resolveUnderRoot(name);
        if (!Files.exists(target)) {
            return "记忆不存在: " + name;
        }
        if (Files.isDirectory(target)) {
            return "错误: 目标是一个目录,无法读取: " + name;
        }
        try {
            String content = Files.readString(target, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return "(记忆为空: " + name + ")";
            }
            return ToolText.truncate(content, MAX_READ_CHARS);
        } catch (IOException e) {
            log.warn("读取记忆失败: {} ({})", target, e.getMessage());
            return "错误: 读取记忆失败: " + e.getMessage();
        }
    }

    /** 创建或全量覆盖一条记忆文件;自动建父目录。返回「已创建/已更新」结果。 */
    public synchronized String write(String name, String content) {
        Path target = resolveUnderRoot(name);
        if (Files.isDirectory(target)) {
            return "错误: 目标是一个目录,无法写入: " + name;
        }
        try {
            boolean exists = Files.exists(target);
            String normalized = ToolText.normalizeNewlines(content == null ? "" : content);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, normalized, StandardCharsets.UTF_8);
            int lines = ToolText.countLines(normalized);
            return (exists ? "记忆已更新: " : "记忆已创建: ") + name + "(" + lines + " 行)";
        } catch (IOException e) {
            log.warn("写入记忆失败: {} ({})", target, e.getMessage());
            return "错误: 写入记忆失败: " + e.getMessage();
        }
    }

    /** 删除一条记忆文件;不存在视为成功(幂等)。 */
    public synchronized String delete(String name) {
        Path target = resolveUnderRoot(name);
        if (Files.isDirectory(target)) {
            return "错误: 目标是一个目录,拒绝删除: " + name;
        }
        try {
            boolean deleted = Files.deleteIfExists(target);
            return deleted ? "记忆已删除: " + name : "记忆不存在(无需删除): " + name;
        } catch (IOException e) {
            log.warn("删除记忆失败: {} ({})", target, e.getMessage());
            return "错误: 删除记忆失败: " + e.getMessage();
        }
    }

    // ==================== 路径安全收口 ====================

    /**
     * 把逻辑名解析为记忆根下的绝对路径,并做越界防护(对标 SkillRegistry 的 {@code startsWith} 校验)。
     * <ul>
     *   <li>{@code name} 视为记忆根下的相对路径(如 {@code MEMORY.md}、{@code 用户习惯.md}、{@code 用户习惯/简洁回答.md});</li>
     *   <li>反斜杠转正斜杠、去前导斜杠;无扩展名则补 {@code .md},保证记忆均为 markdown;</li>
     *   <li>{@code normalize()} 后必须仍在记忆根内,否则抛 {@link ToolInputException}(挡住 {@code ..} 穿越/绝对路径)。</li>
     * </ul>
     */
    private Path resolveUnderRoot(String name) {
        if (name == null || name.isBlank()) {
            throw new ToolInputException("记忆名称不能为空");
        }
        String rel = name.strip().replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        if (rel.isBlank()) {
            throw new ToolInputException("非法记忆路径: " + name);
        }
        // 末段无扩展名则补 .md
        int lastSlash = rel.lastIndexOf('/');
        String lastSeg = lastSlash >= 0 ? rel.substring(lastSlash + 1) : rel;
        if (!lastSeg.contains(".")) {
            rel = rel + ".md";
        }
        Path root = memoryRoot();
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) {
            throw new ToolInputException("非法记忆路径(越出记忆库): " + name);
        }
        return target;
    }
}
