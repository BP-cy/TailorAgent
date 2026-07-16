package com.changy.tailoragent.knowledge.service;

import com.changy.tailoragent.common.exception.BusinessException;
import com.changy.tailoragent.knowledge.dto.KbDoc;
import com.changy.tailoragent.knowledge.dto.KbNode;
import com.changy.tailoragent.knowledge.entity.KbCatalogEntry;
import com.changy.tailoragent.knowledge.mapper.KbCatalogMapper;
import com.changy.tailoragent.tool.support.KnowledgePathResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 知识库业务层 —— 以<b>文件系统为真相源</b>的目录/文档 CRUD。
 *
 * <p>正文存于磁盘（{@code dataDir()/knowledge/{MD,files}}）；{@code kb_document} 表只存索引状态。
 * 目录树由<b>扫描磁盘目录派生</b>（文件夹=真实目录，含空目录），并在扫描时做 reconcile：
 * 新文件入库为 {@code unindexed}、消失的文件删行 + 删 chunk。文档以<b>相对路径</b>为标识
 * （砍掉自增 id），形如 {@code MD/工作/报告.md}；移动/改名即视为需重索引。
 */
@Slf4j
@Service
public class KnowledgeService {

    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final KnowledgePathResolver resolver;
    private final KbCatalogMapper catalog;
    private final KnowledgeIndexService indexService;

    public KnowledgeService(KnowledgePathResolver resolver,
                            KbCatalogMapper catalog,
                            KnowledgeIndexService indexService) {
        this.resolver = resolver;
        this.catalog = catalog;
        this.indexService = indexService;
    }

    // ==================== 目录树 ====================

    /**
     * 返回某子树（{@code MD} 或 {@code files}）的嵌套目录树（子节点列表）。
     * 扫描磁盘派生，顺带 reconcile catalog。
     */
    public List<KbNode> tree(String type) {
        String sub = normalizeType(type);
        Path subRoot = resolver.defaultRoot().resolve(sub);
        ensureDir(subRoot);
        reconcile(sub, subRoot);

        Map<String, String> statusMap = new HashMap<>();
        for (KbCatalogEntry e : catalog.findByPrefix(sub + "/")) {
            statusMap.put(e.getRelPath(), e.getStatus());
        }
        return buildChildren(subRoot, statusMap);
    }

    /** 扫描子树，把新文件补进 catalog（unindexed），消失的文件删行 + 删 chunk。 */
    private void reconcile(String sub, Path subRoot) {
        Set<String> walked = new HashSet<>();
        try (Stream<Path> s = Files.walk(subRoot)) {
            s.filter(Files::isRegularFile)
             .filter(p -> !p.getFileName().toString().startsWith("."))
             .forEach(p -> walked.add(toRelPath(p)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> known = new HashSet<>();
        for (KbCatalogEntry e : catalog.findByPrefix(sub + "/")) {
            known.add(e.getRelPath());
        }
        for (String w : walked) {
            if (!known.contains(w)) catalog.insertIfAbsent(w);
        }
        for (String k : known) {
            if (!walked.contains(k)) {
                indexService.removeDocument(k);
            }
        }
    }

    /** 递归构建某目录下的子节点列表（文件夹在前、按名排序）。 */
    private List<KbNode> buildChildren(Path dir, Map<String, String> statusMap) {
        List<KbNode> children = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> entries = s
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator
                            .comparing((Path p) -> Files.isDirectory(p) ? 0 : 1)
                            .thenComparing(p -> p.getFileName().toString(),
                                    String.CASE_INSENSITIVE_ORDER))
                    .toList();
            for (Path p : entries) {
                if (Files.isDirectory(p)) {
                    KbNode folder = new KbNode();
                    String rel = toRelPath(p);
                    folder.setPath(rel);
                    folder.setName(fileName(rel));
                    folder.setType("folder");
                    folder.setChildren(buildChildren(p, statusMap));
                    children.add(folder);
                } else {
                    children.add(buildFileNode(p, statusMap));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return children;
    }

    private KbNode buildFileNode(Path file, Map<String, String> statusMap) {
        String rel = toRelPath(file);
        KbNode n = new KbNode();
        n.setPath(rel);
        n.setName(fileName(rel));
        // 按扩展名判 md/file（而非子树前缀）：MD tab 现在也能导入非-md 文件，
        // 只有真正的 .md/.markdown 才走可编辑文档（编辑器/阅读页），其余一律普通文件。
        n.setType(isMarkdown(rel) ? "md" : "file");
        n.setStatus(statusMap.getOrDefault(rel, "unindexed"));
        try {
            BasicFileAttributes attr = Files.readAttributes(file, BasicFileAttributes.class);
            n.setSize(attr.size());
            n.setMtime(ISO_FMT.format(LocalDateTime.ofInstant(
                    attr.lastModifiedTime().toInstant(), ZoneId.systemDefault())));
        } catch (IOException ignored) {
            // 属性读取失败不致命，节点仍返回
        }
        return n;
    }

    // ==================== 单篇读写 ====================

    /** 读取单篇正文（从磁盘实时读取，仅文本/MD）。 */
    public KbDoc read(String relPath) {
        requireInSubtree(relPath);
        Path abs = resolver.resolve(relPath);
        if (!Files.isRegularFile(abs)) {
            throw new BusinessException("文档不存在: " + relPath);
        }
        try {
            String content = Files.readString(abs, StandardCharsets.UTF_8);
            return new KbDoc(relPath, fileName(relPath), content);
        } catch (IOException e) {
            throw new BusinessException("读取文档失败: " + relPath + " (" + e.getMessage() + ")");
        }
    }

    /** 保存正文（人工编辑器保存）：覆盖写盘 + 标脏。 */
    public void save(String relPath, String content) {
        requireInSubtree(relPath);
        Path abs = resolver.resolveForWrite(relPath);
        writeFile(abs, content == null ? "" : content);
        indexService.invalidateDocument(relPath);
        log.info("保存文档: {}", relPath);
    }

    /** 新建文档：文件已存在则报错。 */
    public KbDoc create(String relPath, String content) {
        requireInSubtree(relPath);
        Path abs = resolver.resolveForWrite(relPath);
        if (Files.exists(abs)) {
            throw new BusinessException("文档已存在: " + relPath);
        }
        writeFile(abs, content == null ? "" : content);
        indexService.invalidateDocument(relPath);
        log.info("新建文档: {}", relPath);
        return new KbDoc(relPath, fileName(relPath), content == null ? "" : content);
    }

    /**
     * 导入上传文件到某文件夹（folder 为 MD/ 或 files/ 下的相对目录，根即 {@code MD}/{@code files}）。
     * 二进制原样落盘；同名文件自动加后缀 {@code (1)}、{@code (2)}…避免覆盖。返回最终相对路径。
     */
    public String upload(String folder, MultipartFile file) {
        requireInSubtree(folder);
        // 取原始文件名并剥掉任何路径成分（防目录穿越）
        String original = file.getOriginalFilename();
        String base = original == null ? "" : Path.of(original).getFileName().toString();
        if (base.isBlank()) {
            throw new BusinessException("文件名为空");
        }
        Path dir = resolver.resolveForWrite(folder);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new BusinessException("创建目录失败: " + folder + " (" + e.getMessage() + ")");
        }
        String name = uniqueChildName(dir, base);
        Path target = dir.resolve(name);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target);
        } catch (IOException e) {
            throw new BusinessException("导入文件失败: " + base + " (" + e.getMessage() + ")");
        }
        String relPath = folder + "/" + name;
        indexService.invalidateDocument(relPath);
        log.info("导入文件: {}", relPath);
        return relPath;
    }

    /** 新建文件夹（真实目录，可空）。 */
    public void mkdir(String relPath) {
        requireInSubtree(relPath);
        Path abs = resolver.resolveForWrite(relPath);
        try {
            Files.createDirectories(abs);
            log.info("新建文件夹: {}", relPath);
        } catch (IOException e) {
            throw new BusinessException("新建文件夹失败: " + relPath + " (" + e.getMessage() + ")");
        }
    }

    /** 移动/重命名文件或文件夹。移动即视为需重索引（删旧 catalog 行，reconcile 时以 unindexed 重新加入）。 */
    public void rename(String from, String to) {
        requireInSubtree(from);
        requireInSubtree(to);
        Path src = resolver.resolveForWrite(from);
        Path dst = resolver.resolveForWrite(to);
        if (!Files.exists(src)) {
            throw new BusinessException("源不存在: " + from);
        }
        if (Files.exists(dst)) {
            throw new BusinessException("目标已存在: " + to);
        }
        try {
            if (dst.getParent() != null) Files.createDirectories(dst.getParent());
            Files.move(src, dst);
        } catch (IOException e) {
            throw new BusinessException("移动失败: " + from + " → " + to + " (" + e.getMessage() + ")");
        }
        if (Files.isDirectory(dst)) {
            List<String> movedFiles;
            try (Stream<Path> moved = Files.walk(dst)) {
                movedFiles = moved.filter(Files::isRegularFile)
                        .map(this::toRelPath)
                        .toList();
            } catch (IOException e) {
                throw new BusinessException("移动后刷新索引状态失败: " + to + " (" + e.getMessage() + ")");
            }
            indexService.movePrefix(from, to, movedFiles);
        } else {
            indexService.moveDocument(from, to);
        }
        log.info("移动: {} → {}", from, to);
    }

    /** 删除文件或整个文件夹。 */
    public void delete(String relPath) {
        requireInSubtree(relPath);
        Path abs = resolver.resolveForWrite(relPath);
        if (!Files.exists(abs)) {
            throw new BusinessException("不存在: " + relPath);
        }
        try {
            if (Files.isDirectory(abs)) {
                deleteRecursively(abs);
                indexService.removePrefix(relPath);
            } else {
                Files.delete(abs);
                indexService.removeDocument(relPath);
            }
            log.info("删除: {}", relPath);
        } catch (IOException e) {
            throw new BusinessException("删除失败: " + relPath + " (" + e.getMessage() + ")");
        }
    }

    // ==================== 供 kb 工具调用 ====================

    /** 标脏（写入/编辑工具成功后调用）。参数为 knowledge 相对路径。 */
    public void markDirty(String relPath) {
        indexService.invalidateDocument(relPath);
    }

    /** 绝对路径 → knowledge 相对路径（正斜杠）；供 kb 工具将解析后的绝对路径转回相对路径标脏。 */
    public String toRelPath(Path abs) {
        return resolver.defaultRoot().relativize(abs.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    // ==================== 私有辅助 ====================

    private void writeFile(Path abs, String content) {
        try {
            if (abs.getParent() != null) Files.createDirectories(abs.getParent());
            Files.writeString(abs, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException("写入失败: " + abs + " (" + e.getMessage() + ")");
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new BusinessException("创建目录失败: " + dir + " (" + e.getMessage() + ")");
        }
    }

    private String normalizeType(String type) {
        return "files".equalsIgnoreCase(type) ? "files" : "MD";
    }

    private void requireInSubtree(String relPath) {
        if (relPath == null || relPath.isBlank()) {
            throw new BusinessException("路径不能为空");
        }
        String norm = relPath.replace('\\', '/');
        boolean ok = norm.equals("MD") || norm.equals("files")
                || norm.startsWith("MD/") || norm.startsWith("files/");
        if (!ok) {
            throw new BusinessException("路径必须位于 MD/ 或 files/ 子目录下: " + relPath);
        }
    }

    private String fileName(String relPath) {
        int i = relPath.lastIndexOf('/');
        return i < 0 ? relPath : relPath.substring(i + 1);
    }

    private boolean isMarkdown(String relPath) {
        String lower = relPath.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    /**
     * 在目录 {@code dir} 下为 {@code fileName} 找一个不冲突的名字：
     * 不存在则原样返回，否则依次尝试 {@code stem(1).ext}、{@code stem(2).ext}…（无扩展名则 {@code name(1)}）。
     */
    private String uniqueChildName(Path dir, String fileName) {
        if (!Files.exists(dir.resolve(fileName))) {
            return fileName;
        }
        int dot = fileName.lastIndexOf('.');
        String stem = dot <= 0 ? fileName : fileName.substring(0, dot);
        String ext = dot <= 0 ? "" : fileName.substring(dot);
        int i = 1;
        while (Files.exists(dir.resolve(stem + "(" + i + ")" + ext))) {
            i++;
        }
        return stem + "(" + i + ")" + ext;
    }
}
