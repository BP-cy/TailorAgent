package com.changy.tailoragent.web;

import java.nio.file.Path;

/**
 * 应用可写数据目录解析。
 *
 * <p>逻辑与 {@code desktop.JcefSetup#resolveInstallDir()} 保持一致：打包后绝不能写到
 * 只读的 {@code Program Files}，必须落到用户可写的 {@code %LOCALAPPDATA%\TailorAgent}；
 * 开发期则用项目工作目录，方便查看产物。
 */
public final class AppPaths {

    private AppPaths() {
    }

    /** 数据根目录：打包运行用 {@code %LOCALAPPDATA%\TailorAgent}，开发运行用项目工作目录。 */
    public static Path dataDir() {
        boolean packaged = System.getProperty("jpackage.app-path") != null;
        if (!packaged) {
            return Path.of("").toAbsolutePath();
        }
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = System.getProperty("user.home");
        }
        return Path.of(base, "TailorAgent");
    }

    /** 图片等媒体文件存放目录（数据根目录下的 {@code media/}）。 */
    public static Path mediaDir() {
        return dataDir().resolve("media");
    }

    /**
     * 知识库文件根目录（数据根目录下的 {@code knowledge/}）。
     *
     * <p>知识库正文以<b>真实文件</b>形式存放于此，SQLite 仅存索引/元数据（见知识库重构）。
     * 下设两个子目录：{@code knowledge/MD}（Markdown 文档）、{@code knowledge/files}（PDF/Word 等文件）。
     * 与用户可切换的工作区（{@code config.workingDir}）解耦，是一个固定目录。
     */
    public static Path knowledgeDir() {
        return dataDir().resolve("knowledge");
    }

    /**
     * 知识库 Lucene 索引目录（数据根目录下的 {@code index/knowledge/}）。
     *
     * <p>索引是由知识文件派生、可重建的数据，因此与 {@link #knowledgeDir()} 中的正文分开存放。
     */
    public static Path knowledgeIndexDir() {
        return dataDir().resolve("index").resolve("knowledge");
    }

    /**
     * 自动新建工作区的容器目录（数据根目录下的 {@code workspace/}）。
     *
     * <p>用户「新建工作区」时在此目录下创建 {@code ws-<日期>-<时间戳>} 子目录;
     * 当用户未设置 {@code workingDir} 时,工具沙箱也兜底到此目录。
     */
    public static Path workspaceContainerDir() {
        return dataDir().resolve("workspace");
    }
}
