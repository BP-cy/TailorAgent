package com.changy.tailoragent.desktop;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefSettings;

import java.io.File;
import java.nio.file.Path;

/**
 * 负责初始化 JCEF（嵌入式 Chromium）。
 *
 * <p>核心是 {@link #resolveInstallDir()}：打包后绝不能把原生二进制解压到工作目录
 * （会落到只读的 {@code Program Files}），必须指向用户可写的 {@code %LOCALAPPDATA%}。
 */
public final class JcefSetup {

    private JcefSetup() {
    }

    /**
     * 构建并初始化 {@link CefApp}。首次运行会从 classpath 内置的 natives 包解压原生二进制
     * 到 {@link #resolveInstallDir()}，全程不联网。该方法会阻塞直至初始化完成，应在非 EDT 线程调用。
     */
    public static CefApp createCefApp() throws Exception {
        CefAppBuilder builder = new CefAppBuilder();
        builder.setInstallDir(resolveInstallDir());
        // 初始化进度（解压/加载）输出到控制台，便于排查首启缓慢问题。
        builder.setProgressHandler((state, percent) ->
                System.out.printf("[JCEF] %s%s%n", state,
                        percent >= 0 ? " " + (int) percent + "%" : ""));

        CefSettings settings = builder.getCefSettings();
        settings.windowless_rendering_enabled = false; // 使用原生窗口渲染（非离屏）
        // 仅输出 FATAL 级别日志，屏蔽 Chromium 内部的 ERROR 噪音
        // （如 external_registry_loader、usb_service 等底层非致命错误）
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_FATAL;

        // CefApp 状态变为 TERMINATED 时退出 JVM，确保进程与所有 Helper 子进程干净结束。
        builder.setAppHandler(new MavenCefAppHandlerAdapter() {
            @Override
            public void stateHasChanged(CefApp.CefAppState state) {
                if (state == CefApp.CefAppState.TERMINATED) {
                    System.exit(0);
                }
            }
        });

        return builder.build();
    }

    /**
     * 解析原生二进制的安装/解压目录。
     * <ul>
     *   <li>打包运行（jpackage 会设置 {@code jpackage.app-path}）：{@code %LOCALAPPDATA%\TailorAgent\jcef-bundle}</li>
     *   <li>开发运行：项目目录下的 {@code ./jcef-bundle}</li>
     * </ul>
     */
    static File resolveInstallDir() {
        boolean packaged = System.getProperty("jpackage.app-path") != null;
        if (!packaged) {
            return new File("jcef-bundle");
        }
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = System.getProperty("user.home");
        }
        return Path.of(base, "TailorAgent", "jcef-bundle").toFile();
    }
}