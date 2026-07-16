package com.changy.tailoragent.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 从 classpath 母版生成 Swing/Windows 需要的多尺寸窗口图标。 */
final class AppIcon {

    private static final Logger log = LoggerFactory.getLogger(AppIcon.class);
    private static final String RESOURCE_PATH = "/icons/app-icon.png";
    private static final int[] WINDOW_ICON_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    private AppIcon() {
    }

    /**
     * 返回从同一 PNG 母版高质量缩放的多尺寸图标；资源异常时只记录警告，不阻止主窗口启动。
     */
    static List<Image> loadImages() {
        try (InputStream input = AppIcon.class.getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                log.warn("应用图标资源不存在: {}", RESOURCE_PATH);
                return List.of();
            }
            BufferedImage source = ImageIO.read(input);
            if (source == null) {
                log.warn("应用图标资源不是可识别的图片: {}", RESOURCE_PATH);
                return List.of();
            }

            List<Image> icons = new ArrayList<>(WINDOW_ICON_SIZES.length);
            for (int size : WINDOW_ICON_SIZES) {
                icons.add(scaleToSquare(source, size));
            }
            return List.copyOf(icons);
        } catch (IOException e) {
            log.warn("读取应用图标失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static BufferedImage scaleToSquare(BufferedImage source, int size) {
        BufferedImage target = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            double factor = Math.min((double) size / source.getWidth(),
                    (double) size / source.getHeight());
            int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
            int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
            int x = (size - width) / 2;
            int y = (size - height) / 2;
            graphics.drawImage(source, x, y, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }
}
