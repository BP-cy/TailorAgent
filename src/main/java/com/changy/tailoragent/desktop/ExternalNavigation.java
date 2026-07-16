package com.changy.tailoragent.desktop;

import org.cef.browser.CefBrowser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * 嵌入式浏览器的顶层导航策略：应用同源地址留在 JCEF，外部网页交给系统浏览器，
 * 其他协议一律阻止。
 */
final class ExternalNavigation {

    private static final Logger log = LoggerFactory.getLogger(ExternalNavigation.class);

    private final URI appOrigin;

    ExternalNavigation(String appUrl) {
        URI origin = URI.create(appUrl);
        if (!isWebUri(origin) || origin.getHost() == null) {
            throw new IllegalArgumentException("应用地址必须是合法的 HTTP(S) URL");
        }
        this.appOrigin = origin;
    }

    /** 当前主 frame 导航：返回 true 表示 JCEF 必须取消本次导航。 */
    boolean handleCurrentNavigation(String targetUrl) {
        NavigationAction action = classify(targetUrl);
        if (action == NavigationAction.ALLOW_IN_APP) {
            return false;
        }
        if (action == NavigationAction.OPEN_EXTERNALLY) {
            openInSystemBrowser(URI.create(targetUrl));
        } else {
            logBlockedNavigation(targetUrl);
        }
        return true;
    }

    /**
     * 新窗口/新标签请求始终由这里消费，禁止 JCEF 创建第二个浏览器窗口。
     * 同源地址改在现有主浏览器中打开，外部网页交给系统浏览器。
     */
    boolean handleNewWindow(CefBrowser browser, String targetUrl) {
        NavigationAction action = classify(targetUrl);
        if (action == NavigationAction.ALLOW_IN_APP) {
            browser.loadURL(targetUrl);
        } else if (action == NavigationAction.OPEN_EXTERNALLY) {
            openInSystemBrowser(URI.create(targetUrl));
        } else {
            logBlockedNavigation(targetUrl);
        }
        return true;
    }

    NavigationAction classify(String targetUrl) {
        if (targetUrl == null || targetUrl.isBlank()) {
            return NavigationAction.BLOCK;
        }

        final URI target;
        try {
            target = URI.create(targetUrl);
        } catch (IllegalArgumentException ex) {
            return NavigationAction.BLOCK;
        }

        if (!isWebUri(target) || target.getHost() == null) {
            return NavigationAction.BLOCK;
        }
        if (isSameOrigin(target)) {
            return NavigationAction.ALLOW_IN_APP;
        }
        return NavigationAction.OPEN_EXTERNALLY;
    }

    private boolean isSameOrigin(URI target) {
        return target.getUserInfo() == null
                && appOrigin.getScheme().equalsIgnoreCase(target.getScheme())
                && appOrigin.getHost().equalsIgnoreCase(target.getHost())
                && effectivePort(appOrigin) == effectivePort(target);
    }

    private static boolean isWebUri(URI uri) {
        String scheme = uri.getScheme();
        return scheme != null
                && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }

    private static void openInSystemBrowser(URI uri) {
        // Desktop.browse 可能触发系统级调用，放到虚拟线程避免阻塞 JCEF 回调线程。
        Thread.ofVirtual().name("external-browser-open").start(() -> {
            try {
                if (!Desktop.isDesktopSupported()) {
                    log.warn("当前桌面环境不支持打开系统浏览器");
                    return;
                }
                Desktop desktop = Desktop.getDesktop();
                if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                    log.warn("当前桌面环境不支持 BROWSE 操作");
                    return;
                }
                desktop.browse(uri);
            } catch (IOException | RuntimeException ex) {
                // 不记录完整 URL，避免把查询参数中的敏感信息写入日志。
                log.warn("无法使用系统浏览器打开外部地址 {}：{}", describeOrigin(uri), ex.getMessage());
            }
        });
    }

    private static void logBlockedNavigation(String targetUrl) {
        String scheme = "未知";
        try {
            URI uri = URI.create(targetUrl == null ? "" : targetUrl);
            if (uri.getScheme() != null) {
                scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            }
        } catch (IllegalArgumentException ignored) {
            // 非法 URL 只记录通用提示，不回显原始内容。
        }
        log.warn("已阻止 JCEF 顶层导航，协议：{}", scheme);
    }

    private static String describeOrigin(URI uri) {
        String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
        return uri.getScheme() + "://" + uri.getHost() + port;
    }

    enum NavigationAction {
        ALLOW_IN_APP,
        OPEN_EXTERNALLY,
        BLOCK
    }
}
