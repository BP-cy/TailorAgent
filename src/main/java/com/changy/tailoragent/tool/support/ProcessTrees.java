package com.changy.tailoragent.tool.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程树终止工具 —— 杀进程时连同其子孙一并强杀,避免孤儿子进程。
 * <p>
 * {@code Process.destroyForcibly()} 只会终止我们直接 {@code start()} 的那个进程
 * (通常是 {@code cmd.exe /c}),它派生出的真正命令(npm/node/server 等)不会被一起
 * 杀掉,会变成脱离管控的孤儿。本工具先枚举并终止全部 {@code descendants()},再杀根,
 * 保证整棵进程树被清理。
 * <p>
 * 用于:用户主动取消轮次、前台命令超时/中断、KillShell 终止后台命令、应用关闭兜底。
 */
public final class ProcessTrees {

    private static final Logger log = LoggerFactory.getLogger(ProcessTrees.class);

    private ProcessTrees() {
    }

    /** 强杀整棵进程树:先杀所有子孙(自底向上不强求,destroyForcibly 即可),再杀根。 */
    public static void killTree(Process process) {
        if (process == null) {
            return;
        }
        try {
            // 先在杀根之前枚举子孙 —— 一旦根被杀,子孙可能被系统重挂到别处而枚举不到
            process.descendants().forEach(h -> {
                try {
                    h.destroyForcibly();
                } catch (RuntimeException ignore) {
                    // 子进程可能在枚举与终止之间已自行退出,忽略
                }
            });
        } catch (RuntimeException e) {
            log.warn("枚举子进程失败,仅终止根进程: {}", e.getMessage());
        }
        process.destroyForcibly();
    }
}
