package com.changy.tailoragent.tool.shell;

import com.changy.tailoragent.chat.service.TurnControlRegistry;
import com.changy.tailoragent.chat.service.TurnControlRegistry.RunHandle;
import com.changy.tailoragent.env.CommandRunner;
import com.changy.tailoragent.tool.support.BackgroundShellRegistry;
import com.changy.tailoragent.tool.support.ToolInputException;
import com.changy.tailoragent.tool.support.ToolText;
import com.changy.tailoragent.tool.support.WorkspacePathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Bash 工具 —— 执行一条 shell 命令({@code cmd.exe /c <command>},保留管道/重定向)。
 * <p>
 * 移植自 Claude Code 的 BashTool,执行收口到 {@link CommandRunner}。支持:
 * <ul>
 *   <li><b>前台</b>:同步等待、带硬超时,返回 stdout+stderr;</li>
 *   <li><b>后台</b>({@code runInBackground=true}):立即返回 shellId,输出由
 *       {@link BackgroundShellRegistry} 持续收集,后续用 BashOutput 读取、KillShell 终止。</li>
 * </ul>
 * 对极少数灾难性命令(rm -rf / 格式化等)做轻量拦截。
 */
@Component
public class BashTool {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);

    private static final long DEFAULT_TIMEOUT_MS = 120_000;
    private static final long MAX_TIMEOUT_MS = 600_000;
    private static final int MAX_OUTPUT_CHARS = 30_000;

    /** 灾难性命令轻量拦截(需用户显式确认才宜执行)。 */
    private static final Pattern DANGEROUS = Pattern.compile(
            "(?i)\\b(rm\\s+-rf\\s+[/\\\\]|del\\s+/[sq]|rmdir\\s+/s|format\\s+[a-z]:|mkfs|" +
                    ":\\s*>\\s*/dev/sd|dd\\s+if=)");

    private final WorkspacePathResolver pathResolver;
    private final CommandRunner commandRunner;
    private final BackgroundShellRegistry registry;

    public BashTool(WorkspacePathResolver pathResolver, CommandRunner commandRunner,
                    BackgroundShellRegistry registry) {
        this.pathResolver = pathResolver;
        this.commandRunner = commandRunner;
        this.registry = registry;
    }

    @Tool(name = "bash", description = "执行一条 Windows shell 命令(cmd /c,支持管道与重定向),用于构建、运行脚本、git 等。" +
            "默认前台同步执行并返回输出;长耗时命令设 runInBackground=true 立即返回 shellId,再用 BashOutput 读取输出。")
    public String bash(
            @ToolParam(description = "要执行的命令") String command,
            @ToolParam(required = false, description = "一句话说明该命令用途(便于展示/日志)") String description,
            @ToolParam(required = false, description = "工作目录的绝对路径,省略则用工作区默认根目录") String workingDir,
            @ToolParam(required = false, description = "超时毫秒数,默认 120000,上限 600000") Integer timeout,
            @ToolParam(required = false, description = "true 为后台运行,立即返回 shellId") Boolean runInBackground,
            ToolContext toolContext) {
        try {
            if (command == null || command.isBlank()) {
                return "错误: 命令不能为空。";
            }
            if (DANGEROUS.matcher(command).find()) {
                return "已拒绝: 该命令具有破坏性(可能不可恢复)。如确需执行,请让用户明确确认后再运行。";
            }

            Path dir = (workingDir == null || workingDir.isBlank())
                    ? pathResolver.defaultRoot()
                    : pathResolver.resolve(workingDir);

            if (Boolean.TRUE.equals(runInBackground)) {
                Process process = commandRunner.startShell(dir, command);
                // 用与前台一致的控制台字符集(中文 Windows = GBK)读后台输出,避免中文乱码
                String id = registry.register(command, process, CommandRunner.consoleCharset());
                log.info("后台命令已启动: id={}, cmd={}", id, command);
                return "命令已在后台运行,shellId=" + id +
                        "。用 BashOutput(taskId=\"" + id + "\") 读取输出,KillShell 可终止它。";
            }

            long timeoutMs = timeout == null ? DEFAULT_TIMEOUT_MS : Math.min(Math.max(timeout, 1), MAX_TIMEOUT_MS);

            // 取本轮取消句柄(经 ToolContext 传入):前台执行前登记进程,使用户主动取消能立即强杀该命令
            RunHandle handle = extractHandle(toolContext);
            CommandRunner.Result r;
            try {
                r = commandRunner.runShell(Duration.ofMillis(timeoutMs), dir, command,
                        handle == null ? null : handle::setForeground);
            } finally {
                if (handle != null) {
                    handle.clearForeground();
                }
            }

            // 进程因用户取消被强杀:返回明确提示,不把半截输出当正常结果回喂模型
            if (handle != null && handle.isCancelled()) {
                return "命令已被用户取消。";
            }
            if (r.timedOut()) {
                return "命令超时(" + timeoutMs + "ms)已被终止。如为长耗时任务,请改用 runInBackground=true。";
            }

            StringBuilder sb = new StringBuilder();
            if (!r.stdout().isEmpty()) {
                sb.append(r.stdout());
            }
            if (!r.stderr().isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(r.stderr());
            }
            if (r.exitCode() != 0) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("[退出码 ").append(r.exitCode()).append("]");
            }
            if (sb.length() == 0) {
                return "(命令执行完毕,无输出)";
            }
            return ToolText.truncate(sb.toString(), MAX_OUTPUT_CHARS);
        } catch (ToolInputException e) {
            return "错误: " + e.getMessage();
        } catch (RuntimeException e) {
            log.warn("命令执行失败: {}", e.getMessage());
            return "错误: 命令执行失败: " + e.getMessage();
        }
    }

    /** 从 ToolContext 取本轮取消句柄(可能为 null:未挂 toolContext 时优雅降级,不影响执行) */
    private static RunHandle extractHandle(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object v = toolContext.getContext().get(TurnControlRegistry.CTX_KEY);
        return v instanceof RunHandle h ? h : null;
    }
}
