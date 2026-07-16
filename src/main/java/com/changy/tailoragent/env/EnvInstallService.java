package com.changy.tailoragent.env;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * 运行时一键安装 —— 通过 Windows 包管理器 winget 安装 Node.js LTS / uv。
 * <p>
 * winget 安装长耗时且需 UAC 交互，故用 {@link CommandRunner#runDetached} 异步启动后
 * 立即返回，不阻塞 HTTP 线程。安装完成后用户需点「重新检测」；新装命令通常要
 * <b>重启本应用</b> 才能被稳定识别（运行中的 JVM 环境块不会刷新）。
 */
@Service
public class EnvInstallService {

    private static final Logger log = LoggerFactory.getLogger(EnvInstallService.class);

    /** runtimeId → winget 包 id */
    private static final Map<String, String> WINGET_IDS = Map.of(
            "node", "OpenJS.NodeJS.LTS",
            "uv", "astral-sh.uv"
    );

    private final CommandRunner runner;

    public EnvInstallService(CommandRunner runner) {
        this.runner = runner;
    }

    /** 安装结果：是否已启动安装 + 提示文案 */
    public record InstallOutcome(boolean launched, String message) {}

    public InstallOutcome install(String runtimeId) {
        String pkgId = WINGET_IDS.get(runtimeId);
        if (pkgId == null) {
            return new InstallOutcome(false, "未知的运行时: " + runtimeId);
        }
        if (!wingetAvailable()) {
            return new InstallOutcome(false,
                    "未检测到 winget（“应用安装程序”）。请从 Microsoft Store 安装“应用安装程序”后重试，"
                    + "或手动下载：Node.js https://nodejs.org/ ， uv https://docs.astral.sh/uv/getting-started/installation/");
        }
        try {
            // 保持控制台窗口可见，便于用户看到进度与完成 UAC 授权
            runner.runDetached("winget", "install", "-e", "--id", pkgId,
                    "--accept-package-agreements", "--accept-source-agreements");
        } catch (RuntimeException e) {
            log.warn("winget 安装启动失败: runtimeId={}, err={}", runtimeId, e.getMessage());
            return new InstallOutcome(false, "启动安装失败: " + e.getMessage());
        }
        log.info("已启动 winget 安装: {} ({})", runtimeId, pkgId);
        return new InstallOutcome(true,
                "已启动安装。请在弹出的窗口中完成安装（可能需要管理员授权），完成后点击「重新检测」。"
                + "注意：新安装的命令通常需重启本应用后才能被识别。");
    }

    private boolean wingetAvailable() {
        return runner.run(Duration.ofSeconds(5), "winget", "--version").ok();
    }
}
