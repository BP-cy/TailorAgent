package com.changy.tailoragent.desktop;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.network.CefRequest;
import org.springframework.context.ConfigurableApplicationContext;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * 应用主窗口：一个嵌入了 JCEF 浏览器的 Swing {@link JFrame}，加载本地后端提供的 SPA 页面。
 */
public final class BrowserWindow {

    private BrowserWindow() {
    }

    /**
     * 使用已初始化的 {@link CefApp} 创建浏览器窗口（JCEF 已在外部并行初始化）。
     *
     * @param ctx    Spring 上下文，窗口关闭时一并关闭（停止内嵌服务器）
     * @param port   后端实际监听端口
     * @param cefApp 已初始化完成的 CefApp 实例
     */
    public static void launch(ConfigurableApplicationContext ctx, int port, CefApp cefApp) throws Exception {
        CefClient client = cefApp.createClient();
        String url = "http://127.0.0.1:" + port + "/";
        ExternalNavigation navigation = new ExternalNavigation(url);

        // 普通顶层导航：同源地址留在应用内，外部网页交给 Windows 默认浏览器。
        client.addRequestHandler(new CefRequestHandlerAdapter() {
            @Override
            public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request,
                                          boolean userGesture, boolean isRedirect) {
                if (frame == null || !frame.isMain()) {
                    return false;
                }
                return navigation.handleCurrentNavigation(request == null ? null : request.getURL());
            }

            @Override
            public boolean onOpenURLFromTab(CefBrowser browser, CefFrame frame,
                                            String targetUrl, boolean userGesture) {
                return navigation.handleNewWindow(browser, targetUrl);
            }
        });

        // target=_blank/window.open 等请求不创建第二个 JCEF 窗口。
        client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override
            public boolean onBeforePopup(CefBrowser browser, CefFrame frame,
                                         String targetUrl, String targetFrameName) {
                return navigation.handleNewWindow(browser, targetUrl);
            }
        });

        CefBrowser browser = client.createBrowser(url, false, false);
        var windowIcons = AppIcon.loadImages();

        // 创建并显示窗口必须在 EDT 上。
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("TailorAgent");
            if (!windowIcons.isEmpty()) {
                // 多尺寸列表让 Windows 在标题栏、任务栏和 Alt+Tab 中选择最匹配的图标。
                frame.setIconImages(windowIcons);
            }
            frame.getContentPane().add(browser.getUIComponent(), BorderLayout.CENTER);
            frame.setSize(1280, 800);
            frame.setLocationRelativeTo(null);
            // 自行处理关闭：先关 Spring 上下文，再 dispose JCEF（触发 TERMINATED → System.exit）。
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    ctx.close();
                    CefApp.getInstance().dispose();
                }
            });
            frame.setVisible(true);
        });
    }
}
