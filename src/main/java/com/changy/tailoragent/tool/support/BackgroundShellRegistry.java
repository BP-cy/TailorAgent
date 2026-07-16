package com.changy.tailoragent.tool.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台 shell 进程注册表 —— Bash(后台模式) / BashOutput / KillShell 三件套共用。
 * <p>
 * 对应 Claude Code 的 {@code appState.tasks} + {@code _taskShared/registry}。注册一个
 * 已启动的 {@link Process} 后,本类用守护线程持续把 stdout/stderr 抽进缓冲区,进程结束
 * 时翻转状态。BashOutput 通过游标增量读取,避免重复回灌已读内容。
 */
@Component
public class BackgroundShellRegistry {

    private static final Logger log = LoggerFactory.getLogger(BackgroundShellRegistry.class);

    public enum Status { RUNNING, COMPLETED, FAILED, KILLED }

    /** 一个后台 shell 的运行态。 */
    public static final class Shell {
        final String id;
        final String command;
        final Process process;
        final StringBuffer stdout = new StringBuffer();
        final StringBuffer stderr = new StringBuffer();
        volatile Status status = Status.RUNNING;
        volatile Integer exitCode;
        /** BashOutput 增量读取游标(已返回到的 stdout 长度)。 */
        int stdoutCursor;

        Shell(String id, String command, Process process) {
            this.id = id;
            this.command = command;
            this.process = process;
        }

        public String id() { return id; }
        public String command() { return command; }
        public Status status() { return status; }
        public Integer exitCode() { return exitCode; }
        public Process process() { return process; }

        /** 取自上次游标以来的新增 stdout,并推进游标。 */
        public synchronized String drainNewStdout() {
            String full = stdout.toString();
            if (stdoutCursor >= full.length()) {
                return "";
            }
            String delta = full.substring(stdoutCursor);
            stdoutCursor = full.length();
            return delta;
        }

        /** 当前完整 stderr 快照。 */
        public String stderrSnapshot() {
            return stderr.toString();
        }
    }

    private final Map<String, Shell> shells = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    /**
     * 注册一个已启动的后台进程,接管其输出抽取与完成监听。
     *
     * @param command 原始命令(展示/日志用)
     * @param process 已 {@code start()} 的进程
     * @param charset 读取子进程输出的字符集(cmd 输出通常为系统默认 GBK)
     * @return 分配的 shell id
     */
    public String register(String command, Process process, Charset charset) {
        String id = "bash_" + Integer.toHexString(seq.incrementAndGet()) + Long.toHexString(System.nanoTime() & 0xffffff);
        Shell shell = new Shell(id, command, process);
        shells.put(id, shell);

        pump(process.getInputStream(), shell.stdout, charset, id + "-out");
        pump(process.getErrorStream(), shell.stderr, charset, id + "-err");

        Thread watcher = new Thread(() -> {
            try {
                int code = process.waitFor();
                shell.exitCode = code;
                if (shell.status != Status.KILLED) {
                    shell.status = code == 0 ? Status.COMPLETED : Status.FAILED;
                }
                log.info("后台命令结束: id={}, exit={}, status={}", id, code, shell.status);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, id + "-watch");
        watcher.setDaemon(true);
        watcher.start();

        return id;
    }

    public Optional<Shell> get(String id) {
        return Optional.ofNullable(id == null ? null : shells.get(id));
    }

    /**
     * 解析 id:优先 {@code taskId},回退已废弃的 {@code shellId}(KillShell 兼容)。
     */
    public static String resolveId(String taskId, String shellId) {
        return taskId != null ? taskId : shellId;
    }

    /** 强杀一个后台进程(连同其子孙进程树,避免孤儿)。 */
    public void kill(Shell shell) {
        shell.status = Status.KILLED;
        ProcessTrees.killTree(shell.process);
    }

    /** 持续把输入流读进缓冲区的守护线程。 */
    private void pump(InputStream in, StringBuffer sink, Charset charset, String name) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) != -1) {
                    sink.append(new String(buf, 0, n, charset));
                }
            } catch (IOException ignored) {
                // 进程被强杀时读流可能抛异常,忽略
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }
}
