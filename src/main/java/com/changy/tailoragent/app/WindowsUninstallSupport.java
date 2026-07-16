package com.changy.tailoragent.app;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 解析本应用 MSI 安装包的 ProductCode —— 供「设置内卸载」调 {@code msiexec /x {ProductCode}}。
 *
 * <p>jpackage 打的 MSI 没有独立卸载 exe,卸载统一走 Windows Installer({@code msiexec /x})。
 * 每次 jpackage 构建都会随机生成新的 ProductCode(除非固定 {@code --win-upgrade-uuid}),
 * 因此这里<b>运行时从注册表读当前已装产品的 ProductCode</b>,不依赖打包脚本是否固定 UUID。
 *
 * <p>扫描标准的 Uninstall 注册表位置(per-machine 的 64/32 位视图 + per-user),
 * 找 {@code DisplayName} 以 {@code TailorAgent} 开头的子键;MSI 产品的子键名即 ProductCode(形如 {@code {GUID}})。
 * 用 JNA 的 {@link Advapi32Util}(依赖 jna-platform,项目已引入)。
 */
public final class WindowsUninstallSupport {

    private static final Logger log = LoggerFactory.getLogger(WindowsUninstallSupport.class);

    /** 展示名前缀 —— 与 {@code package.bat} 的 {@code --name TailorAgent} 一致。 */
    private static final String DISPLAY_NAME_PREFIX = "TailorAgent";

    /** 待扫描的 (根键, Uninstall 路径) 列表:HKLM 64 位视图、HKLM WOW6432 32 位视图、HKCU per-user。 */
    private static final String UNINSTALL_PATH = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall";
    private static final String UNINSTALL_PATH_WOW = "SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall";

    private WindowsUninstallSupport() {
    }

    /**
     * 查找本应用的 MSI ProductCode。
     *
     * @return ProductCode(形如 {@code {XXXXXXXX-....}});未找到(开发态 / app-image / 非 Windows)返回 {@link Optional#empty()}。
     */
    public static Optional<String> findProductCode() {
        Optional<String> code = scan(WinReg.HKEY_LOCAL_MACHINE, UNINSTALL_PATH);
        if (code.isEmpty()) {
            code = scan(WinReg.HKEY_LOCAL_MACHINE, UNINSTALL_PATH_WOW);
        }
        if (code.isEmpty()) {
            code = scan(WinReg.HKEY_CURRENT_USER, UNINSTALL_PATH);
        }
        return code;
    }

    /** 扫描某个 Uninstall 路径下的所有子键,匹配 DisplayName 前缀,命中返回子键名(ProductCode)。 */
    private static Optional<String> scan(WinReg.HKEY root, String path) {
        try {
            if (!Advapi32Util.registryKeyExists(root, path)) {
                return Optional.empty();
            }
            String[] subKeys = Advapi32Util.registryGetKeys(root, path);
            for (String sub : subKeys) {
                String keyPath = path + "\\" + sub;
                try {
                    if (!Advapi32Util.registryValueExists(root, keyPath, "DisplayName")) {
                        continue;
                    }
                    String displayName = Advapi32Util.registryGetStringValue(root, keyPath, "DisplayName");
                    if (displayName != null && displayName.startsWith(DISPLAY_NAME_PREFIX)) {
                        log.info("找到卸载项: {} → ProductCode={}", displayName, sub);
                        return Optional.of(sub);
                    }
                } catch (RuntimeException e) {
                    // 单个子键读失败(权限/损坏)不影响整体扫描
                    log.debug("读取卸载子键失败: {} ({})", keyPath, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.warn("扫描注册表 Uninstall 失败: {} ({})", path, e.getMessage());
        }
        return Optional.empty();
    }
}
