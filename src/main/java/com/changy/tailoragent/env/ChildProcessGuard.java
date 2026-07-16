package com.changy.tailoragent.env;

import com.changy.tailoragent.tool.support.ProcessTrees;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子进程兜底清理 —— 保证应用进程一旦消失,由它 spawn 的所有进程(bash 命令及其子孙,
 * 如常驻 dev server)都被清理,不留孤儿。
 *
 * <p><b>两道防线</b>:
 * <ol>
 *   <li><b>Windows Job Object(KILL_ON_JOB_CLOSE)</b> —— 强保证。开机建一个 Job,
 *       每个 spawn 的进程都 {@code AssignProcessToJobObject}(其子孙自动继承同一 Job)。
 *       本进程持有 Job 句柄;当本进程<em>以任何方式</em>消失(正常退出 / 崩溃 / 任务管理器强杀),
 *       Job 的最后一个句柄随之关闭,OS 立即终止 Job 内所有进程。<b>故意不关闭该句柄</b>,
 *       让其生命周期与 JVM 绑定。用 JNA 调 kernel32(JDK 21 的 FFM 仍是 preview)。</li>
 *   <li><b>优雅退出钩子</b>(@PreDestroy + ShutdownHook)—— 正常关闭时主动 killTree,
 *       与 Job 互为冗余;Job 初始化失败(非 Windows 等)时作为唯一防线。</li>
 * </ol>
 * 轮次的 DB 状态一致性另由启动恢复(running→cancelled)兜底。
 */
@Component
public class ChildProcessGuard {

    private static final Logger log = LoggerFactory.getLogger(ChildProcessGuard.class);

    /** SetInformationJobObject 信息类:JobObjectExtendedLimitInformation */
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    /** 限制位:Job 句柄全部关闭时终止 Job 内所有进程 */
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    /** OpenProcess 访问权限:分配到 Job 所需 */
    private static final int PROCESS_TERMINATE = 0x0001;
    private static final int PROCESS_SET_QUOTA = 0x0100;
    /** JOBOBJECT_EXTENDED_LIMIT_INFORMATION 在 64 位下的大小;LimitFlags 位于偏移 16 */
    private static final int EXT_LIMIT_INFO_SIZE = 144;
    private static final int LIMIT_FLAGS_OFFSET = 16;

    /** 仅映射本类需要的 4 个 kernel32 函数 */
    private interface Kernel32Job extends StdCallLibrary {
        Kernel32Job INSTANCE = Native.load("kernel32", Kernel32Job.class, W32APIOptions.DEFAULT_OPTIONS);

        HANDLE CreateJobObjectW(Pointer lpJobAttributes, WString lpName);

        boolean SetInformationJobObject(HANDLE hJob, int infoClass, Pointer info, int infoLength);

        boolean AssignProcessToJobObject(HANDLE hJob, HANDLE hProcess);

        HANDLE OpenProcess(int desiredAccess, boolean inheritHandle, int processId);

        boolean CloseHandle(HANDLE handle);
    }

    /** 当前存活的、由本应用 spawn 的进程集合(优雅退出兜底用) */
    private final Set<Process> tracked = ConcurrentHashMap.newKeySet();

    /** Job 句柄;持有至 JVM 退出,故意不关闭以触发 KILL_ON_JOB_CLOSE。null 表示初始化失败 */
    private HANDLE jobHandle;

    @PostConstruct
    void init() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::killAll, "child-process-guard"));
        initJobObject();
    }

    private void initJobObject() {
        try {
            HANDLE job = Kernel32Job.INSTANCE.CreateJobObjectW(null, null);
            if (job == null) {
                log.warn("CreateJobObject 失败,强杀兜底降级为仅优雅退出清理");
                return;
            }
            // 只需写 BasicLimitInformation.LimitFlags(偏移 16)即可启用 KILL_ON_JOB_CLOSE
            Memory info = new Memory(EXT_LIMIT_INFO_SIZE);
            info.clear();
            info.setInt(LIMIT_FLAGS_OFFSET, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);
            boolean ok = Kernel32Job.INSTANCE.SetInformationJobObject(
                    job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, info, EXT_LIMIT_INFO_SIZE);
            if (!ok) {
                log.warn("SetInformationJobObject 失败,强杀兜底降级为仅优雅退出清理");
                return;
            }
            this.jobHandle = job;
            log.info("Job Object 已就绪:KILL_ON_JOB_CLOSE 启用,子进程将随应用退出(含强杀)自动清理");
        } catch (Throwable t) {
            log.warn("Job Object 初始化失败,强杀兜底降级为仅优雅退出清理: {}", t.toString());
        }
    }

    /**
     * 登记一个已启动的进程:纳入 Job(强保证)并加入优雅退出清理集合。
     * 进程自行结束后会自动从集合移除。失败仅告警,不影响命令执行。
     */
    public void assign(Process process) {
        if (process == null) {
            return;
        }
        tracked.add(process);
        process.onExit().thenRun(() -> tracked.remove(process));
        assignToJob(process);
    }

    private void assignToJob(Process process) {
        if (jobHandle == null || !process.isAlive()) {
            return;
        }
        try {
            HANDLE h = Kernel32Job.INSTANCE.OpenProcess(
                    PROCESS_TERMINATE | PROCESS_SET_QUOTA, false, (int) process.pid());
            if (h == null) {
                log.warn("OpenProcess 失败,进程未纳入 Job: pid={}", process.pid());
                return;
            }
            try {
                if (!Kernel32Job.INSTANCE.AssignProcessToJobObject(jobHandle, h)) {
                    log.warn("AssignProcessToJobObject 失败: pid={}", process.pid());
                }
            } finally {
                Kernel32Job.INSTANCE.CloseHandle(h);
            }
        } catch (Throwable t) {
            log.warn("纳入 Job 失败: {}", t.toString());
        }
    }

    /** Spring 上下文关闭时清理(优雅退出路径之一)。 */
    @PreDestroy
    void onContextClose() {
        killAll();
    }

    /** 杀掉所有仍存活的被跟踪进程的整棵进程树。 */
    private void killAll() {
        int killed = 0;
        for (Process p : tracked) {
            if (p.isAlive()) {
                ProcessTrees.killTree(p);
                killed++;
            }
        }
        tracked.clear();
        if (killed > 0) {
            log.info("应用退出:已清理 {} 个残留子进程树", killed);
        }
    }
}
