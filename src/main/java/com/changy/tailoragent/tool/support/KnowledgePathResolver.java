package com.changy.tailoragent.tool.support;

import com.changy.tailoragent.web.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 知识库路径解析与安全收口 —— 知识库 AI 编辑工具（{@code kb_read_file}/{@code kb_write_file}/
 * {@code kb_edit_file}）的入参路径都必须先过这里。
 * <p>
 * 结构与 {@link WorkspacePathResolver} 完全同构，差异仅在于<b>根目录是固定的知识库目录</b>
 * {@link AppPaths#knowledgeDir()}（而非用户可切换的 {@code config.workingDir}），因此
 * <b>不注入 {@code AppConfigService}</b>。相对路径（如 {@code MD/工作/报告.md}）挂到知识库根下，
 * 写入被强制限制在知识库目录内，读取不限制（允许跨篇引用），UNC 防护保留。
 * <p>
 * 约定：知识库内相对路径一律相对 {@code knowledge/} 根，形如 {@code MD/...} 或 {@code files/...}。
 */
@Component
public class KnowledgePathResolver {

    private static final Logger log = LoggerFactory.getLogger(KnowledgePathResolver.class);

    /**
     * 当前知识库根:{@link AppPaths#knowledgeDir()}。已绝对化 + normalize，并确保根目录及
     * {@code MD}/{@code files} 两个子目录存在。
     */
    private Path currentRoot() {
        Path root = AppPaths.knowledgeDir().toAbsolutePath().normalize();
        try {
            Files.createDirectories(root.resolve("MD"));
            Files.createDirectories(root.resolve("files"));
        } catch (IOException e) {
            log.warn("创建知识库目录失败: {} ({})", root, e.getMessage());
        }
        return root;
    }

    /** 知识库根目录(已绝对化);省略 path 时的回退目录，也是系统提示词 {@code {{KNOWLEDGE_ROOT}}} 的来源。 */
    public Path defaultRoot() {
        return currentRoot();
    }

    /**
     * 读取/检索用解析:相对路径挂到知识库根下;<b>不</b>强制包含(允许 AI 编辑时跨篇 read 引用其它文档),
     * 仅做 UNC 防护。
     *
     * @throws ToolInputException 路径为空或为 UNC
     */
    public Path resolve(String raw) {
        return toAbsolute(raw, currentRoot());
    }

    /**
     * 写入/编辑用解析:在 {@link #resolve} 基础上,强制结果<b>必须落在知识库根内</b>,否则抛错。
     * 供 {@code kb_write_file}/{@code kb_edit_file} 调用。
     *
     * @throws ToolInputException 路径为空 / 为 UNC / 越出知识库根
     */
    public Path resolveForWrite(String raw) {
        Path root = currentRoot();
        Path p = toAbsolute(raw, root);
        if (!p.startsWith(root)) {
            throw new ToolInputException(
                    "出于安全考虑,只能在知识库目录内创建或修改文件。\n知识库根: " + root
                            + "\n目标路径: " + p
                            + "\n请使用知识库内的相对路径(如 \"MD/foo.md\")。");
        }
        return p;
    }

    /** 公共解析:校验非空/非 UNC,展开 ~,相对路径挂到知识库根,转绝对并规范化。 */
    private Path toAbsolute(String raw, Path root) {
        if (raw == null || raw.isBlank()) {
            throw new ToolInputException("路径不能为空");
        }
        String expanded = expandHome(raw.trim());

        // UNC 防护:Windows 上对 \\server\share 做文件操作会触发 SMB 认证,可能外泄凭据。
        if (expanded.startsWith("\\\\") || expanded.startsWith("//")) {
            throw new ToolInputException("出于安全考虑,拒绝访问 UNC 网络路径: " + raw);
        }

        Path p = Paths.get(expanded);
        if (!p.isAbsolute()) {
            // 关键:相对路径挂到知识库根,而非进程 CWD
            p = root.resolve(p);
        }
        return p.normalize();
    }

    /** {@code ~} / {@code ~/x} 展开为用户主目录。 */
    private String expandHome(String p) {
        if (p.equals("~")) {
            return System.getProperty("user.home");
        }
        if (p.startsWith("~/") || p.startsWith("~\\")) {
            return System.getProperty("user.home") + p.substring(1);
        }
        return p;
    }
}
