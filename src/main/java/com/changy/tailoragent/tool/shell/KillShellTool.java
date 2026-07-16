package com.changy.tailoragent.tool.shell;

import com.changy.tailoragent.tool.support.BackgroundShellRegistry;
import com.changy.tailoragent.tool.support.BackgroundShellRegistry.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * KillShell 工具 —— 终止一个仍在运行的后台 shell。
 * <p>
 * 移植自 Claude Code 的 TaskStop:仅当任务处于运行中才可终止,否则报错。
 * 兼容旧参数名 {@code shellId}(等价于 {@code taskId})。
 */
@Component
public class KillShellTool {

    private static final Logger log = LoggerFactory.getLogger(KillShellTool.class);

    private final BackgroundShellRegistry registry;

    public KillShellTool(BackgroundShellRegistry registry) {
        this.registry = registry;
    }

    @Tool(name = "kill_shell", description = "终止一个由 Bash(runInBackground=true)启动、仍在运行的后台命令。")
    public String killShell(
            @ToolParam(required = false, description = "后台命令的 shellId") String taskId,
            @ToolParam(required = false, description = "已废弃别名,等同 taskId") String shellId) {
        String id = BackgroundShellRegistry.resolveId(taskId, shellId);
        if (id == null) {
            return "错误: 缺少必填参数 taskId。";
        }
        Optional<Shell> opt = registry.get(id);
        if (opt.isEmpty()) {
            return "错误: 找不到 shellId 为 " + id + " 的后台命令。";
        }
        Shell shell = opt.get();
        if (shell.status() != BackgroundShellRegistry.Status.RUNNING) {
            return "无需操作: 该命令已不在运行(状态: " + shell.status() + ")。";
        }
        registry.kill(shell);
        log.info("已终止后台命令: id={}", id);
        return "已终止后台命令: " + id;
    }
}
