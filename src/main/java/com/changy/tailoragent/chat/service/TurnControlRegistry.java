package com.changy.tailoragent.chat.service;

import com.changy.tailoragent.tool.support.ProcessTrees;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 轮次取消注册表 —— 让「正在运行的某一轮对话」可被用户主动、立即停止。
 *
 * <p>一轮对话执行链路上有三处需要分别中断,缺一不可:
 * <ol>
 *   <li><b>正在跑的工具进程</b>(前台 bash):跑在 Reactor 的 boundedElastic 线程上,
 *       光中断消费线程唤不醒它的 {@code process.waitFor()},必须拿到 Process 引用直接强杀进程树;</li>
 *   <li><b>到模型的 HTTP 流</b>:{@code dispose()} 订阅,立刻断开连接、停止计费;</li>
 *   <li><b>阻塞消费的后台线程</b>:{@code future.cancel(true)} 中断,唤醒它去走取消收尾。</li>
 * </ol>
 *
 * <p>按 {@code turnId} 索引(前端在 SSE {@code start} 事件里拿到 turnId);取消接口据此精确定位一轮。
 * 真正的 DB 收尾(轮次置 cancelled)与前端通知(cancelled 事件)由该轮自己的后台线程在被唤醒后完成,
 * 避免与执行线程产生双重收尾竞态。
 */
@Component
public class TurnControlRegistry {

    private static final Logger log = LoggerFactory.getLogger(TurnControlRegistry.class);

    /** ToolContext 中存放本轮 {@link RunHandle} 的键 —— 供工具(BashTool)登记正在跑的前台进程 */
    public static final String CTX_KEY = "tailoragent.turnHandle";

    private final Map<Integer, RunHandle> handles = new ConcurrentHashMap<>();

    /** 登记一轮(turn 创建后立即调用) */
    public void register(Integer turnId, RunHandle handle) {
        if (turnId != null) {
            handles.put(turnId, handle);
        }
    }

    /** 移除一轮(轮次收尾后调用,无论成功/失败/取消) */
    public void remove(Integer turnId) {
        if (turnId != null) {
            handles.remove(turnId);
        }
    }

    /**
     * 取消一轮:立即强杀前台工具进程树、断开模型流、中断后台线程。
     *
     * @return true 表示该轮在跑并已触发取消;false 表示找不到(可能已结束)
     */
    public boolean cancel(Integer turnId) {
        RunHandle handle = turnId == null ? null : handles.get(turnId);
        if (handle == null) {
            return false;
        }
        log.info("用户请求取消轮次: turnId={}", turnId);
        handle.abort();
        return true;
    }

    /**
     * 一轮对话的可取消句柄 —— 持有该轮的后台线程 Future、模型流订阅、当前前台工具进程与取消标志。
     */
    public static final class RunHandle {

        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile Future<?> future;
        private volatile Disposable stream;
        /** 当前正在执行的前台工具进程(由 BashTool 在执行前后登记/清除);取消时强杀其进程树 */
        private final AtomicReference<Process> foreground = new AtomicReference<>();

        public void setFuture(Future<?> future) {
            this.future = future;
        }

        /** 关联模型流订阅,使取消能立即断流 */
        public void setStream(Disposable stream) {
            this.stream = stream;
        }

        /** 登记当前前台工具进程(执行 bash 前) */
        public void setForeground(Process process) {
            foreground.set(process);
        }

        /** 清除前台工具进程登记(bash 执行结束后) */
        public void clearForeground() {
            foreground.set(null);
        }

        /** 本轮是否已被取消 */
        public boolean isCancelled() {
            return cancelled.get();
        }

        /**
         * 执行取消(由取消接口线程调用,同步完成"杀进程 + 断流",随后中断消费线程让其走收尾):
         * 顺序刻意为先杀进程、再断流、最后中断线程,保证工具进程不会在断流后还残留。
         */
        void abort() {
            cancelled.set(true);
            Process p = foreground.getAndSet(null);
            if (p != null) {
                ProcessTrees.killTree(p);
            }
            Disposable d = stream;
            if (d != null && !d.isDisposed()) {
                d.dispose();
            }
            Future<?> f = future;
            if (f != null) {
                f.cancel(true);
            }
        }
    }
}
