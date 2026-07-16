package com.changy.tailoragent.tool.shell;

import com.changy.tailoragent.tool.support.BackgroundShellRegistry;
import com.changy.tailoragent.tool.support.BackgroundShellRegistry.Shell;
import com.changy.tailoragent.tool.support.ToolText;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * BashOutput 工具 —— 读取后台 shell 的输出。
 * <p>
 * 移植自 Claude Code 的 TaskOutput:按 shellId 取增量 stdout(自上次读取以来的新增),
 * 附带状态与退出码。{@code block=true} 时最多等待 {@code timeout} 毫秒直到进程结束。
 */
@Component
public class BashOutputTool {

    private static final int MAX_OUTPUT_CHARS = 30_000;

    private final BackgroundShellRegistry registry;

    public BashOutputTool(BackgroundShellRegistry registry) {
        this.registry = registry;
    }

    @Tool(name = "bash_output", description = "读取某个后台命令(由 Bash 的 runInBackground 启动)的新增输出。" +
            "返回自上次读取以来的增量 stdout、当前状态与退出码。block=true 时最多等待 timeout 毫秒直至完成。")
    public String bashOutput(
            @ToolParam(description = "后台命令的 shellId(Bash 返回)") String taskId,
            @ToolParam(required = false, description = "是否等待命令完成,默认 true") Boolean block,
            @ToolParam(required = false, description = "最长等待毫秒数,默认 30000,上限 600000") Integer timeout) {
        Optional<Shell> opt = registry.get(taskId);
        if (opt.isEmpty()) {
            return "错误: 找不到 shellId 为 " + taskId + " 的后台命令。";
        }
        Shell shell = opt.get();

        boolean wait = !Boolean.FALSE.equals(block);
        long timeoutMs = timeout == null ? 30_000 : Math.min(Math.max(timeout, 0), 600_000);
        if (wait) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (shell.status() == BackgroundShellRegistry.Status.RUNNING
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        String delta = shell.drainNewStdout();
        String stderr = shell.stderrSnapshot();

        StringBuilder sb = new StringBuilder();
        sb.append("状态: ").append(statusText(shell));
        if (shell.exitCode() != null) {
            sb.append(" (退出码 ").append(shell.exitCode()).append(")");
        }
        sb.append('\n');
        if (!delta.isEmpty()) {
            sb.append("新增输出:\n").append(delta);
        } else {
            sb.append("(无新增输出)");
        }
        if (shell.status() != BackgroundShellRegistry.Status.RUNNING && !stderr.isBlank()) {
            sb.append("\n--- stderr ---\n").append(stderr);
        }
        return ToolText.truncate(sb.toString(), MAX_OUTPUT_CHARS);
    }

    private static String statusText(Shell shell) {
        return switch (shell.status()) {
            case RUNNING -> "运行中";
            case COMPLETED -> "已完成";
            case FAILED -> "已失败";
            case KILLED -> "已终止";
        };
    }
}
