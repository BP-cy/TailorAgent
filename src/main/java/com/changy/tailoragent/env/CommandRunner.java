package com.changy.tailoragent.env;

import com.changy.tailoragent.tool.support.ProcessTrees;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 外部命令执行工具 —— 全项目唯一的 {@link ProcessBuilder} 收口处。
 * <p>
 * 统一注入增强 PATH（见 {@link #augmentedPath()}）解决一个关键坑：
 * <b>打包后的 jpackage GUI 进程不继承用户登录 shell 的 PATH</b>，导致
 * {@code npx}/{@code uvx}/{@code winget}/{@code rg} 在终端能跑、在应用里却找不到。
 * <p>
 * 提供四类入口：
 * <ul>
 *   <li>{@link #run(Duration, String...)} —— {@code cmd.exe /c <parts...>}（MCP/env 既有用法）；</li>
 *   <li>{@link #runShell(Duration, Path, String)} —— {@code cmd.exe /c <整条命令>}（Bash 前台，保留管道/重定向）；</li>
 *   <li>{@link #runExec(Duration, Path, Charset, List)} —— 直接执行某可执行文件（Grep 调 rg，输出按指定字符集读）；</li>
 *   <li>{@link #startShell(Path, String)} —— 启动后立即返回 {@link Process}（Bash 后台，交注册表接管输出）。</li>
 * </ul>
 * 仅 Windows。无状态单例，可并发调用。
 */
@Component
public class CommandRunner {

    private static final Logger log = LoggerFactory.getLogger(CommandRunner.class);

    /**
     * 读取控制台输出用的字符集。
     * <p>
     * <b>关键坑</b>:JDK 18+(JEP 400)起 {@link Charset#defaultCharset()} / {@code file.encoding} <b>恒为 UTF-8</b>,
     * 但 {@code cmd.exe} 的输出仍按系统<b>本地代码页</b>编码(中文 Windows 为 GBK/936)。若用 UTF-8 解码,
     * 中文会全部乱码。因此这里优先取 {@code sun.jnu.encoding}(源自系统 ANSI 代码页,中文 Windows = GBK),
     * 只有它不可用时才回退默认字符集。命令<b>参数</b>方向由 JVM 用 native 编码传给 cmd,无需在此处理。
     */
    private static final Charset CONSOLE_CHARSET = resolveConsoleCharset();

    private static Charset resolveConsoleCharset() {
        String enc = System.getProperty("sun.jnu.encoding");
        if (enc != null && !enc.isBlank()) {
            try {
                return Charset.forName(enc);
            } catch (RuntimeException e) {
                log.warn("无法识别 sun.jnu.encoding={},回退默认字符集({})", enc, Charset.defaultCharset());
            }
        }
        return Charset.defaultCharset();
    }

    /** 控制台输出字符集 —— 供后台 shell(BashOutput)读取输出时复用,保证与前台解码口径一致。 */
    public static Charset consoleCharset() {
        return CONSOLE_CHARSET;
    }

    /** 子进程兜底:每个 spawn 的进程都登记进去,应用退出时统一清理其进程树 */
    private final ChildProcessGuard processGuard;

    public CommandRunner(ChildProcessGuard processGuard) {
        this.processGuard = processGuard;
    }

    /** 一次命令执行的结果。 */
    public record Result(boolean timedOut, int exitCode, String stdout, String stderr) {
        /** 未超时且退出码为 0 视为成功。 */
        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    // ---------------------------------------------------------------------
    // 公共入口
    // ---------------------------------------------------------------------

    /**
     * 同步执行 {@code cmd.exe /c <parts...>}，捕获输出，带硬超时。
     */
    public Result run(Duration timeout, String... parts) {
        List<String> command = new ArrayList<>(parts.length + 2);
        command.add("cmd.exe");
        command.add("/c");
        for (String p : parts) {
            command.add(p);
        }
        return capture(newBuilder(command, null), timeout, CONSOLE_CHARSET, String.join(" ", parts), null);
    }

    /**
     * 同步执行一整条 shell 命令 {@code cmd.exe /c <command>}（保留管道/重定向），
     * 可指定工作目录，输出按系统字符集读取。用于 Bash 工具前台模式。
     */
    public Result runShell(Duration timeout, Path workingDir, String command) {
        return runShell(timeout, workingDir, command, null);
    }

    /**
     * 同上,额外在进程启动后回调 {@code onStart} 把 {@link Process} 暴露给调用方
     * （Bash 前台据此把进程登记到本轮取消句柄,使用户主动取消能立即强杀该命令）。
     */
    public Result runShell(Duration timeout, Path workingDir, String command, Consumer<Process> onStart) {
        List<String> cmd = List.of("cmd.exe", "/c", command);
        return capture(newBuilder(cmd, workingDir), timeout, CONSOLE_CHARSET, command, onStart);
    }

    /**
     * 直接执行某可执行文件（不经 {@code cmd /c} 包裹），输出按 {@code charset} 读取。
     * 用于调用 ripgrep —— rg 输出 UTF-8，需传 {@link java.nio.charset.StandardCharsets#UTF_8}。
     */
    public Result runExec(Duration timeout, Path workingDir, Charset charset, List<String> command) {
        return capture(newBuilder(command, workingDir), timeout, charset, command.isEmpty() ? "" : command.get(0), null);
    }

    /**
     * 启动 {@code cmd.exe /c <command>} 后立即返回 {@link Process}，<b>不</b>等待结束、
     * <b>不</b>读取输出（交由 {@code BackgroundShellRegistry} 接管）。用于 Bash 后台模式。
     */
    public Process startShell(Path workingDir, String command) {
        List<String> cmd = List.of("cmd.exe", "/c", command);
        try {
            Process process = newBuilder(cmd, workingDir).start();
            processGuard.assign(process); // 登记兜底:应用退出时清理其进程树,不留孤儿
            return process;
        } catch (IOException e) {
            throw new RuntimeException("后台命令启动失败: " + e.getMessage(), e);
        }
    }

    /**
     * 异步启动 {@code cmd.exe /c <parts...>} 后立即返回，不等待结束（winget 等长耗时/需 UAC 交互）。
     */
    public void runDetached(String... parts) {
        List<String> command = new ArrayList<>(parts.length + 2);
        command.add("cmd.exe");
        command.add("/c");
        for (String p : parts) {
            command.add(p);
        }
        ProcessBuilder pb = newBuilder(command, null);
        pb.inheritIO();
        try {
            processGuard.assign(pb.start());
            log.info("已启动后台命令: {}", String.join(" ", parts));
        } catch (IOException e) {
            log.warn("后台命令启动失败: {} — {}", String.join(" ", parts), e.getMessage());
            throw new RuntimeException("命令启动失败: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    /** 构造一个注入增强 PATH、可选工作目录的 ProcessBuilder。 */
    private ProcessBuilder newBuilder(List<String> command, Path workingDir) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("PATH", augmentedPath());
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        return pb;
    }

    /**
     * 启动进程、用独立线程排空 stdout/stderr（防管道写满死锁）、带硬超时。
     * 超时则 {@code destroyForcibly()} 并返回 {@code timedOut=true}。
     */
    private Result capture(ProcessBuilder pb, Duration timeout, Charset charset, String label, Consumer<Process> onStart) {
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            log.warn("命令启动失败: {} — {}", label, e.getMessage());
            return new Result(false, -1, "", e.getMessage());
        }
        processGuard.assign(process); // 登记兜底:应用退出时随子进程树一并清理
        if (onStart != null) {
            onStart.accept(process); // 暴露进程供调用方登记(用户主动取消时强杀)
        }

        StreamPump out = new StreamPump(process.getInputStream(), charset);
        StreamPump err = new StreamPump(process.getErrorStream(), charset);
        Thread outThread = new Thread(out, "cmd-stdout");
        Thread errThread = new Thread(err, "cmd-stderr");
        outThread.setDaemon(true);
        errThread.setDaemon(true);
        outThread.start();
        errThread.start();

        try {
            boolean done = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!done) {
                ProcessTrees.killTree(process); // 超时:连同子孙一并强杀,不留孤儿
                log.warn("命令超时: {}", label);
                return new Result(true, -1, "", "命令执行超时");
            }
            outThread.join(1000);
            errThread.join(1000);
            return new Result(false, process.exitValue(), out.text(), err.text());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ProcessTrees.killTree(process); // 被取消/中断:强杀整棵进程树
            return new Result(false, -1, "", "命令执行被中断");
        }
    }

    /**
     * 在 JVM 当前 PATH 基础上，补上打包 GUI 启动器常拿不到的安装目录。
     */
    private String augmentedPath() {
        StringBuilder sb = new StringBuilder();
        String current = System.getenv("PATH");
        if (current != null && !current.isBlank()) {
            sb.append(current);
        }

        String appData = System.getenv("APPDATA");
        String programFiles = System.getenv("ProgramFiles");
        String userProfile = System.getenv("USERPROFILE");
        String localAppData = System.getenv("LOCALAPPDATA");

        appendIfPresent(sb, appData, "\\npm");                          // npm 全局：npx.cmd
        appendIfPresent(sb, programFiles, "\\nodejs");                  // Node 安装目录
        appendIfPresent(sb, userProfile, "\\.local\\bin");              // uv 默认
        appendIfPresent(sb, localAppData, "\\Programs\\uv");            // uv 备选
        appendIfPresent(sb, localAppData, "\\Microsoft\\WindowsApps");  // winget 别名

        return sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, String base, String suffix) {
        if (base == null || base.isBlank()) return;
        if (sb.length() > 0) sb.append(';');
        sb.append(base).append(suffix);
    }

    /** 把输入流读成字符串的小工具，配合独立线程使用。 */
    private static final class StreamPump implements Runnable {
        private final InputStream in;
        private final Charset charset;
        private volatile String text = "";

        StreamPump(InputStream in, Charset charset) {
            this.in = in;
            this.charset = charset;
        }

        @Override
        public void run() {
            try {
                byte[] bytes = in.readAllBytes();
                text = new String(bytes, charset).trim();
            } catch (IOException e) {
                text = "";
            }
        }

        String text() {
            return text;
        }
    }
}
