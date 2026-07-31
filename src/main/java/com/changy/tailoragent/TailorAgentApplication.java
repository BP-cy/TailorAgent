package com.changy.tailoragent;

import com.changy.tailoragent.desktop.BrowserWindow;
import com.changy.tailoragent.desktop.JcefSetup;
import org.cef.CefApp;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@SpringBootApplication
public class TailorAgentApplication {

    public static void main(String[] args) throws Exception {
        // 必须关闭 headless：否则 AWT/JCEF 无法创建窗口。
        SpringApplication app = new SpringApplication(TailorAgentApplication.class);
        app.setHeadless(false);

        // 在后台线程并行初始化 JCEF（解压原生二进制 + 加载 Chromium），
        // 与 Spring Boot 启动同时进行，启动耗时 = max(Spring, JCEF) 而非两者之和。
        // 打包训练（CDS 训练运行）时用 -Dtailoragent.skip-jcef=true 跳过 JCEF，
        // 避免在打包机上拉起 Chromium 进程；此时仅启动 Spring 上下文后退出。
        boolean skipJcef = Boolean.getBoolean("tailoragent.skip-jcef");
        ExecutorService jcefExecutor = null;
        Future<CefApp> cefFuture = null;
        if (!skipJcef) {
            jcefExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "jcef-init");
                t.setDaemon(false);
                return t;
            });
            cefFuture = jcefExecutor.submit(JcefSetup::createCefApp);
            jcefExecutor.shutdown(); // 不再接受新任务，线程在 createCefApp 完成后自行结束
        }

        // Spring Boot 在主线程启动（阻塞至就绪）
        ConfigurableApplicationContext ctx = app.run(args);

        // server.port=0 时，真实端口在启动后写入 environment 的 local.server.port。
        Integer port = ctx.getEnvironment().getProperty("local.server.port", Integer.class);
        if (port == null) {
            throw new IllegalStateException("无法获取内嵌服务器端口，JCEF 窗口无法加载页面");
        }

        // 仅训练运行：不等待 JCEF、不创建窗口。通常 -Dspring.context.exit=onRefresh
        // 已在 context 刷新完成后触发 System.exit，此处作为兜底。
        if (skipJcef) {
            return;
        }

        // 等待 JCEF 初始化完成（若 Spring 启动较慢，此处几乎无需等待）
        CefApp cefApp;
        try {
            cefApp = cefFuture.get();
        } catch (ExecutionException e) {
            throw new RuntimeException("JCEF 初始化失败", e.getCause());
        }

        // 在主线程创建浏览器窗口（窗口本身在 EDT 上构建）
        BrowserWindow.launch(ctx, port, cefApp);
    }

}