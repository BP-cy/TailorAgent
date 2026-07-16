package com.changy.tailoragent.tool.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 数据结构可视化脚本供给器。
 *
 * <p>三个 Python 文件作为 classpath 资源随 fat jar 打包，首次调用工具时解压到用户可写的
 * {@code %LOCALAPPDATA%/TailorAgent/tools/visualize-data-structures}。这样 jpackage 安装到只读的
 * {@code Program Files} 后仍可由 Python 正常导入相邻模块。</p>
 */
@Component
public class DataStructureVisualizerProvisioner {

    private static final Logger log = LoggerFactory.getLogger(DataStructureVisualizerProvisioner.class);
    private static final String RESOURCE_ROOT = "tools/visualize-data-structures/";
    private static final List<String> SCRIPT_NAMES = List.of(
            "render.py", "algorithm_viz.py", "datastruct_viz.py");

    private volatile Path scriptsDir;
    private volatile boolean attempted;

    /** 返回已解压脚本目录；资源缺失或解压失败时返回空。 */
    public Optional<Path> scriptsDir() {
        if (!attempted) {
            ensureExtracted();
        }
        return Optional.ofNullable(scriptsDir);
    }

    private synchronized void ensureExtracted() {
        if (attempted) {
            return;
        }
        attempted = true;
        try {
            Path targetDir = installDir();
            Files.createDirectories(targetDir);
            for (String name : SCRIPT_NAMES) {
                ClassPathResource resource = new ClassPathResource(RESOURCE_ROOT + name);
                if (!resource.exists()) {
                    throw new IllegalStateException("缺少内置脚本: " + RESOURCE_ROOT + name);
                }
                Path target = targetDir.resolve(name);
                long expectedSize = resource.contentLength();
                if (Files.isRegularFile(target) && Files.size(target) == expectedSize) {
                    continue;
                }
                try (InputStream in = resource.getInputStream();
                     OutputStream out = Files.newOutputStream(target)) {
                    in.transferTo(out);
                }
            }
            scriptsDir = targetDir;
            log.info("数据结构可视化脚本已就绪: {}", targetDir);
        } catch (Exception e) {
            scriptsDir = null;
            log.warn("准备数据结构可视化脚本失败: {}", e.getMessage());
        }
    }

    private Path installDir() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = System.getProperty("user.home");
        }
        return Path.of(base, "TailorAgent", "tools", "visualize-data-structures");
    }
}
