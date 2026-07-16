package com.changy.tailoragent.tool.support;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * ripgrep 二进制供给器 —— 复刻 {@code JcefSetup.resolveInstallDir()} 的"内置在 jar、
 * 首启解压到可写目录"套路。
 * <p>
 * {@code rg.exe} 作为 classpath 资源 {@code /bin/rg.exe} 随 fat jar 一起打包(完全离线)。
 * 运行时解压到 {@code %LOCALAPPDATA%\TailorAgent\bin\rg.exe}(可写,避免安装在只读的
 * {@code Program Files} 下时失败)。开发态({@code mvn spring-boot:run})同样走这条路,
 * 故无需 rg 在 PATH 中。
 * <p>
 * 解压失败(缺资源/磁盘满/杀软拦截)时 {@link #rgPath()} 返回空,GrepTool 自动回退到纯
 * Java 实现,保证搜索永不硬失败。
 */
@Component
public class RipgrepProvisioner {

    private static final Logger log = LoggerFactory.getLogger(RipgrepProvisioner.class);

    private static final String RESOURCE_PATH = "bin/rg.exe";

    /** 解压后的 rg.exe 路径;null 表示不可用(回退 Java)。 */
    private volatile Path resolved;
    private volatile boolean attempted;

    /** 启动时异步预热一次,首个 Grep 不必等解压。 */
    @PostConstruct
    void warmUp() {
        Thread t = new Thread(this::ensureExtracted, "rg-provision");
        t.setDaemon(true);
        t.start();
    }

    /** 取可用的 rg.exe 路径;不可用返回空。 */
    public Optional<Path> rgPath() {
        if (!attempted) {
            ensureExtracted();
        }
        return Optional.ofNullable(resolved);
    }

    private synchronized void ensureExtracted() {
        if (attempted) {
            return;
        }
        attempted = true;
        try {
            ClassPathResource res = new ClassPathResource(RESOURCE_PATH);
            if (!res.exists()) {
                log.warn("未找到内置 {} —— Grep 将回退到纯 Java 实现", RESOURCE_PATH);
                return;
            }
            long resourceSize = res.contentLength();

            Path target = installDir().resolve("rg.exe");
            // 已存在且大小一致 → 复用(支持版本升级:大小变了就重解压)。
            if (Files.exists(target) && Files.size(target) == resourceSize) {
                resolved = target;
                return;
            }

            Files.createDirectories(target.getParent());
            // newOutputStream 默认即 CREATE + TRUNCATE_EXISTING + WRITE
            try (InputStream in = res.getInputStream();
                 OutputStream out = Files.newOutputStream(target)) {
                in.transferTo(out);
            }
            target.toFile().setExecutable(true); // Windows 上无实际作用,保持跨平台习惯
            resolved = target;
            log.info("ripgrep 已就绪: {}", target);
        } catch (Exception e) {
            log.warn("解压 ripgrep 失败,Grep 回退到纯 Java: {}", e.getMessage());
            resolved = null;
        }
    }

    /** 解压目标目录:{@code %LOCALAPPDATA%\TailorAgent\bin}(对齐 JcefSetup)。 */
    private Path installDir() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = System.getProperty("user.home");
        }
        return Path.of(base, "TailorAgent", "bin");
    }
}
