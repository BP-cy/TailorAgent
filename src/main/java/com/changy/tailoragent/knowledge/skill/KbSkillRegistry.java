package com.changy.tailoragent.knowledge.skill;

import com.changy.tailoragent.tool.skill.SkillInfo;
import com.changy.tailoragent.tool.skill.SkillMeta;
import com.changy.tailoragent.web.AppPaths;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 知识库 AI 编辑 agent 专用的 Skill 注册表 —— 主对话 {@link com.changy.tailoragent.tool.skill.SkillRegistry}
 * 的<b>独立副本</b>。
 * <p>
 * 保持 frontmatter catalog 常驻、body 按需加载的渐进式披露。用户 Skill 位于
 * {@code AppPaths.dataDir()/kb-skills}，与主对话的 {@code skills/} 隔离；此外可合并与专用工具
 * 版本绑定的 classpath 内置 Skill，内置内容不释放到用户目录，也不能被用户同名覆盖或删除。
 */
@Component
public class KbSkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(KbSkillRegistry.class);

    /** frontmatter 分隔符 */
    private static final String FM_DELIM = "---";

    /** 单次导入的文件数上限(防滥用) */
    private static final int MAX_FILES = 200;
    /** 单次导入的总字节上限(20MB,Skill 多为文本) */
    private static final long MAX_TOTAL_BYTES = 20L * 1024 * 1024;

    /**
     * 与知识编辑专用工具配套的只读内置 Skill。正文是自足的知识编辑适配版，按需从 classpath 加载。
     */
    private static final BuiltinSkill VISUALIZE_DATA_STRUCTURES = new BuiltinSkill(
            "visualize-data-structures",
            "为知识库文档生成算法数据结构与执行状态图；调用对应的 kb_draw_* 工具前加载，以选择正确 renderer、输入形状和状态参数。",
            List.of("tools/visualize-data-structures/SKILL.md"));
    private static final Map<String, BuiltinSkill> BUILTIN_SKILLS = Map.of(
            VISUALIZE_DATA_STRUCTURES.name(), VISUALIZE_DATA_STRUCTURES);

    /**
     * name → 元数据。volatile + 整体替换:扫描时构建新 Map 再赋值,
     * 使运行时导入/删除(写)与编辑请求渲染清单(读)无需加锁即看到一致快照。
     */
    private volatile Map<String, SkillMeta> skills = new LinkedHashMap<>();
    /** name → body 缓存(懒加载) */
    private final Map<String, String> bodyCache = new ConcurrentHashMap<>();

    private Path skillsDir;

    @PostConstruct
    public void init() {
        skillsDir = AppPaths.dataDir().resolve("kb-skills");
        try {
            // 用户 Skill 仍来自可写目录；工具配套的只读内置 Skill 直接从 classpath 懒加载，不释放到磁盘。
            if (!Files.exists(skillsDir)) {
                Files.createDirectories(skillsDir);
            }
            scan();
        } catch (IOException e) {
            log.warn("初始化知识库 Skill 目录失败: {}", e.getMessage());
        }
        log.info("知识库 Skill 加载完成: {} 个（内置 {} + 用户 {}）→ {}",
                BUILTIN_SKILLS.size() + skills.size(), BUILTIN_SKILLS.size(), skills.size(), names());
    }

    // ==================== 对外 ====================

    /** 渲染「可用 Skills」清单(进系统提示词);无 Skill 时给出占位说明 */
    public String renderCatalog() {
        if (BUILTIN_SKILLS.isEmpty() && skills.isEmpty()) {
            return "（暂无可用 Skill）";
        }
        StringBuilder sb = new StringBuilder();
        for (BuiltinSkill builtin : BUILTIN_SKILLS.values()) {
            appendCatalogEntry(sb, builtin.name(), builtin.description());
        }
        for (SkillMeta m : skills.values()) {
            appendCatalogEntry(sb, m.name(), m.description());
        }
        return sb.toString().stripTrailing();
    }

    private static void appendCatalogEntry(StringBuilder sb, String name, String description) {
        sb.append("- ").append(name);
        if (description != null && !description.isBlank()) {
            sb.append(": ").append(description);
        }
        sb.append('\n');
    }

    /** 按名称查用户 Skill 元数据(null=不存在);内置 Skill 通过 contains/loadBody 访问。 */
    public SkillMeta find(String name) {
        return name == null ? null : skills.get(name.strip());
    }

    /** 名称是否对应内置或用户 Skill。 */
    public boolean contains(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.strip();
        return BUILTIN_SKILLS.containsKey(normalized) || skills.containsKey(normalized);
    }

    /** 当前所有 Skill 名称,逗号分隔(用于「未找到」时提示模型可选项) */
    public String names() {
        List<String> names = new ArrayList<>(BUILTIN_SKILLS.keySet());
        names.addAll(skills.keySet());
        return String.join(", ", names);
    }

    /** 懒加载某 Skill 的正文(去掉 frontmatter);不存在返回 null */
    public String loadBody(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.strip();
        BuiltinSkill builtin = BUILTIN_SKILLS.get(normalized);
        if (builtin != null) {
            return bodyCache.computeIfAbsent(normalized, k -> readBuiltinBody(builtin));
        }
        SkillMeta m = find(normalized);
        if (m == null) {
            return null;
        }
        return bodyCache.computeIfAbsent(m.name(), k -> readBody(m.skillMd()));
    }

    // ==================== 管理(列表 / 导入 / 删除) ====================

    /** 用户安装的 Skill 列表；内置 Skill 不出现在可删除的设置列表中。 */
    public List<SkillInfo> list() {
        List<SkillInfo> out = new ArrayList<>();
        for (SkillMeta m : skills.values()) {
            out.add(new SkillInfo(m.name(), m.description()));
        }
        return out;
    }

    /** 重新扫描 Skill 目录(导入/删除后刷新;吞 IOException 仅记日志) */
    public synchronized void rescan() {
        try {
            scan();
        } catch (IOException e) {
            log.warn("重新扫描知识库 Skill 失败: {}", e.getMessage());
        }
    }

    /**
     * 拖拽导入:前端读取文件夹内全部文件(相对路径 + 内容)上传,写入 {@code kb-skills/<folderName>/} 并重扫。
     * 校验:根目录须含 SKILL.md;数量/大小上限;逐文件做路径越界防护。同名文件夹先清空再写(允许覆盖更新)。
     */
    public synchronized void importFromFiles(String folderName, List<ImportFile> files) {
        String safe = sanitizeName(folderName);
        if (safe.isBlank()) {
            throw new IllegalArgumentException("无效的 Skill 文件夹名");
        }
        if (BUILTIN_SKILLS.containsKey(safe)) {
            throw new IllegalArgumentException("不能覆盖内置 Skill: " + safe);
        }
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("未读取到任何文件");
        }
        if (files.size() > MAX_FILES) {
            throw new IllegalArgumentException("文件过多(>" + MAX_FILES + ")");
        }
        boolean hasSkillMd = files.stream()
                .anyMatch(f -> normalizeRel(f.path()).equalsIgnoreCase("SKILL.md"));
        if (!hasSkillMd) {
            throw new IllegalArgumentException("文件夹根目录缺少 SKILL.md");
        }
        long total = files.stream().mapToLong(f -> f.content() == null ? 0 : f.content().length).sum();
        if (total > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("内容过大(>20MB)");
        }

        Path dest = skillsDir.resolve(safe).normalize();
        if (!dest.startsWith(skillsDir)) {
            throw new IllegalArgumentException("非法路径");
        }
        try {
            if (Files.exists(dest)) {
                deleteRecursively(dest);
            }
            for (ImportFile f : files) {
                String rel = normalizeRel(f.path());
                if (rel.isBlank()) {
                    continue;
                }
                Path target = dest.resolve(rel).normalize();
                if (!target.startsWith(dest)) {
                    throw new IllegalArgumentException("非法文件路径: " + f.path());
                }
                Files.createDirectories(target.getParent());
                Files.write(target, f.content() == null ? new byte[0] : f.content());
            }
            scan();
        } catch (IOException e) {
            throw new RuntimeException("写入 Skill 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 原生文件夹选择导入:把本地某文件夹整树复制进 {@code kb-skills/<目录名>/} 并重扫。
     * 校验:源目录须含 SKILL.md。
     */
    public synchronized void importFromDir(Path src) {
        if (src == null || !Files.isDirectory(src)) {
            throw new IllegalArgumentException("不是有效的文件夹");
        }
        if (!Files.isRegularFile(src.resolve("SKILL.md"))) {
            throw new IllegalArgumentException("文件夹根目录缺少 SKILL.md");
        }
        Path source = src.normalize();
        String safe = sanitizeName(source.getFileName().toString());
        if (safe.isBlank()) {
            throw new IllegalArgumentException("无效的文件夹名");
        }
        if (BUILTIN_SKILLS.containsKey(safe)) {
            throw new IllegalArgumentException("不能覆盖内置 Skill: " + safe);
        }
        Path dest = skillsDir.resolve(safe).normalize();
        if (!dest.startsWith(skillsDir)) {
            throw new IllegalArgumentException("非法路径");
        }
        try {
            if (Files.exists(dest)) {
                deleteRecursively(dest);
            }
            try (Stream<Path> walk = Files.walk(source)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    Path target = dest.resolve(source.relativize(p).toString()).normalize();
                    if (!target.startsWith(dest)) {
                        return;
                    }
                    try {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
            scan();
        } catch (IOException e) {
            throw new RuntimeException("导入 Skill 失败: " + e.getMessage(), e);
        } catch (UncheckedIOException e) {
            throw new RuntimeException("导入 Skill 失败: " + e.getCause().getMessage(), e);
        }
    }

    /** 删除某 Skill(整个文件夹)并重扫;不存在则静默 */
    public synchronized void delete(String name) {
        if (name != null && BUILTIN_SKILLS.containsKey(name.strip())) {
            throw new IllegalArgumentException("内置 Skill 不可删除: " + name.strip());
        }
        SkillMeta m = find(name);
        if (m == null) {
            return;
        }
        Path dir = m.skillMd().getParent();
        if (dir == null || !dir.normalize().startsWith(skillsDir)) {
            throw new IllegalArgumentException("非法删除路径");
        }
        try {
            deleteRecursively(dir);
            scan();
        } catch (IOException e) {
            throw new RuntimeException("删除 Skill 失败: " + e.getMessage(), e);
        }
    }

    /** 文件夹名清洗:取最后一段、去非法字符与前导点,挡住 {@code ..} / 路径分隔符 */
    private static String sanitizeName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.strip().replace('\\', '/');
        int slash = s.lastIndexOf('/');
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        s = s.replaceAll("[\\\\/:*?\"<>|]", "").strip();
        while (s.startsWith(".")) {
            s = s.substring(1);
        }
        return s.strip();
    }

    /** 相对路径规整:反斜杠转正斜杠、去前导斜杠(越界由调用方 startsWith 防护) */
    private static String normalizeRel(String p) {
        if (p == null) {
            return "";
        }
        String s = p.replace('\\', '/').strip();
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    /** 递归删除目录树 */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    // ==================== 扫描 ====================

    private void scan() throws IOException {
        Map<String, SkillMeta> found = new LinkedHashMap<>();
        bodyCache.clear();
        if (Files.isDirectory(skillsDir)) {
            try (Stream<Path> dirs = Files.list(skillsDir)) {
                dirs.filter(Files::isDirectory).sorted().forEach(dir -> {
                    Path md = dir.resolve("SKILL.md");
                    if (!Files.isRegularFile(md)) {
                        return;
                    }
                    try {
                        SkillMeta meta = parseFrontmatter(dir.getFileName().toString(), md);
                        if (BUILTIN_SKILLS.containsKey(meta.name())) {
                            log.warn("用户 Skill 与内置 Skill 同名，已跳过: {}", meta.name());
                            return;
                        }
                        found.put(meta.name(), meta);
                    } catch (IOException e) {
                        log.warn("解析知识库 Skill 失败,已跳过: {} — {}", md, e.getMessage());
                    }
                });
            }
        }
        skills = found; // 整体替换,读侧看到一致快照
    }

    // ==================== frontmatter 解析 ====================

    /**
     * 只解析 frontmatter(首个 {@code ---} 与第二个 {@code ---} 之间的 {@code key: value}),
     * 取 name/description。容错:无 frontmatter 时 name 回退目录名、description 为空。
     */
    private SkillMeta parseFrontmatter(String folderName, Path md) throws IOException {
        List<String> lines = Files.readAllLines(md, StandardCharsets.UTF_8);
        String name = folderName;
        String description = "";

        int i = 0;
        while (i < lines.size() && lines.get(i).isBlank()) {
            i++;
        }
        if (i < lines.size() && lines.get(i).strip().equals(FM_DELIM)) {
            for (i++; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.strip().equals(FM_DELIM)) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = line.substring(0, colon).strip();
                String val = stripQuotes(line.substring(colon + 1).strip());
                if ("name".equals(key) && !val.isBlank()) {
                    name = val;
                } else if ("description".equals(key)) {
                    description = val;
                }
            }
        }
        return new SkillMeta(name, description, md);
    }

    private String readBody(Path md) {
        try {
            return stripFrontmatter(Files.readString(md, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("读取知识库 Skill 正文失败: {} — {}", md, e.getMessage());
            return "";
        }
    }

    /**
     * 加载内置 Skill。资源列表支持将来拆分正文，但当前数据结构 Skill 是自足的知识编辑适配版。
     * 正文一次性作为 skill 工具结果回喂，保持“catalog 常驻、详细知识按需加载”。
     */
    private String readBuiltinBody(BuiltinSkill builtin) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < builtin.resources().size(); i++) {
            String location = builtin.resources().get(i);
            try {
                ClassPathResource resource = new ClassPathResource(location);
                if (!resource.exists()) {
                    log.warn("内置知识库 Skill 资源不存在: {}", location);
                    return "";
                }
                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                if (i == 0) {
                    body.append(stripFrontmatter(content));
                } else {
                    body.append("\n\n---\n\n").append(content.strip());
                }
            } catch (IOException e) {
                log.warn("读取内置知识库 Skill 失败: {} — {}", location, e.getMessage());
                return "";
            }
        }
        return body.toString().strip();
    }

    /** 去掉开头的 frontmatter 块,返回正文(无 frontmatter 则原样返回去空白后的内容) */
    private static String stripFrontmatter(String content) {
        String s = content.stripLeading();
        if (!s.startsWith(FM_DELIM)) {
            return content.strip();
        }
        int firstNl = s.indexOf('\n');
        if (firstNl < 0) {
            return "";
        }
        int closing = s.indexOf("\n" + FM_DELIM, firstNl);
        if (closing < 0) {
            return content.strip(); // 没有闭合分隔符,当作没有 frontmatter
        }
        int afterClosing = s.indexOf('\n', closing + 1);
        return afterClosing < 0 ? "" : s.substring(afterClosing + 1).strip();
    }

    private static String stripQuotes(String v) {
        if (v.length() >= 2
                && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** 拖拽导入用:单个文件的相对路径 + 原始字节(由 Controller 从 base64 解码得到) */
    public record ImportFile(String path, byte[] content) {
    }

    /** classpath 内置 Skill 元数据及按加载顺序排列的正文/参考资源。 */
    private record BuiltinSkill(String name, String description, List<String> resources) {
    }
}
