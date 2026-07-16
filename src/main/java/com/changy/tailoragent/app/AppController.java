package com.changy.tailoragent.app;

import com.changy.tailoragent.common.response.ApiResponse;
import org.cef.CefApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用级操作接口(生命周期相关)。
 */
@RestController
@RequestMapping("/api/app")
public class AppController {

    private static final Logger log = LoggerFactory.getLogger(AppController.class);

    private final UninstallService uninstallService;
    private final ConfigurableApplicationContext ctx;

    public AppController(UninstallService uninstallService, ConfigurableApplicationContext ctx) {
        this.uninstallService = uninstallService;
        this.ctx = ctx;
    }

    /** 卸载请求体。 */
    public record UninstallRequest(boolean deleteData) {
    }

    /**
     * 卸载本应用:先启动一个独立存活的清理脚本(等应用退出后执行 msiexec 卸载 + 按需删数据),
     * 立即返回响应,再延迟触发应用优雅退出(让 HTTP 响应先 flush 到前端)。
     *
     * @param req deleteData=true 删除全部本地数据;false 仅清理 jcef-bundle 缓存、保留数据。
     */
    @PostMapping("/uninstall")
    public ApiResponse<Void> uninstall(@RequestBody UninstallRequest req) {
        uninstallService.uninstall(req.deleteData());
        scheduleShutdown();
        return ApiResponse.success("正在卸载,应用即将关闭");
    }

    /**
     * 延迟触发优雅退出:复刻 {@code BrowserWindow.windowClosing} —— 先关 Spring 上下文
     * (释放 SQLite 等文件句柄,使脚本能删数据),再 dispose JCEF(触发 TERMINATED → System.exit(0))。
     */
    private void scheduleShutdown() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(800); // 让本次 HTTP 响应先返回前端
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("卸载流程:开始关闭应用");
            try {
                ctx.close();
            } catch (Exception e) {
                log.warn("关闭 Spring 上下文异常: {}", e.getMessage());
            }
            try {
                CefApp.getInstance().dispose(); // → TERMINATED → System.exit(0)
            } catch (Exception e) {
                log.warn("dispose JCEF 异常,强制退出: {}", e.getMessage());
                System.exit(0);
            }
        }, "app-uninstall-shutdown");
        t.setDaemon(true);
        t.start();
    }
}
