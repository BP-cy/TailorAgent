package com.changy.tailoragent.app;

import com.changy.tailoragent.common.exception.BusinessException;
import com.changy.tailoragent.web.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 卸载编排 —— 生成一个「等应用退出后再执行」的清理脚本并启动它,随后由 {@code AppController} 触发应用优雅退出。
 *
 * <p><b>为什么要外部脚本</b>:运行中的进程删不掉自己锁定的文件(jcef-bundle 的 Chromium DLL、
 * 打开的 SQLite db、程序 exe 本身),且 MSI 卸载要移除 {@code Program Files} 下的本体。因此必须
 * 「先退出应用,再由一个独立存活的脚本去卸载 + 删数据」。
 *
 * <p><b>关键坑:清理脚本绝不能经 {@code CommandRunner} 启动。</b>{@code ChildProcessGuard} 建了带
 * {@code KILL_ON_JOB_CLOSE} 的 Windows Job Object,{@code CommandRunner} 启动的每个进程都会被
 * {@code assign} 进该 Job —— JVM 一退出,Job 内进程被 OS 连带杀死,脚本就永远跑不成。故这里用
 * <b>裸 {@link ProcessBuilder}</b> 启动(不 assign 进 Job);JVM 自身不在 Job 内,其普通子进程不继承 Job,
 * 脚本得以在应用退出后存活。
 */
@Service
public class UninstallService {

    private static final Logger log = LoggerFactory.getLogger(UninstallService.class);

    /**
     * 生成并启动清理脚本。<b>不</b>负责退出应用 —— 退出由调用方(AppController)在响应返回后延迟触发。
     *
     * @param deleteData true=删除整个数据目录({@code %LOCALAPPDATA%\TailorAgent});
     *                   false=仅删可再生的 {@code jcef-bundle} 缓存,保留用户数据供重装恢复。
     * @throws BusinessException 非打包安装态(开发/绿色包)—— 避免误删项目目录数据。
     */
    public void uninstall(boolean deleteData) {
        boolean packaged = System.getProperty("jpackage.app-path") != null;
        if (!packaged) {
            throw new BusinessException("卸载功能仅在打包安装版(.msi)中可用;开发运行模式下不执行卸载。");
        }

        Optional<String> productCode = WindowsUninstallSupport.findProductCode();
        if (productCode.isEmpty()) {
            log.warn("未在注册表找到 TailorAgent 的 ProductCode,将仅清理本地数据、不调用 msiexec");
        }

        long appPid = ProcessHandle.current().pid();
        Path dataDir = AppPaths.dataDir().toAbsolutePath();
        String script = buildScript(appPid, productCode.orElse(null), dataDir, deleteData);

        try {
            Path batFile = Files.createTempFile("tailoragent-uninstall-", ".bat");
            Files.writeString(batFile, script, StandardCharsets.US_ASCII);
            launchDetached(batFile);
            log.info("卸载清理脚本已启动: {} (deleteData={}, productCode={})",
                    batFile, deleteData, productCode.orElse("<none>"));
        } catch (IOException e) {
            log.error("生成/启动卸载脚本失败", e);
            throw new BusinessException("启动卸载失败: " + e.getMessage());
        }
    }

    /**
     * 裸 {@link ProcessBuilder} 启动脚本(绕开 {@code CommandRunner}/Job Object)。
     * 用 {@code cmd /c start} 以独立最小化窗口拉起,立即返回,脱离本进程的 I/O 与生命周期。
     */
    private void launchDetached(Path batFile) throws IOException {
        new ProcessBuilder("cmd.exe", "/c", "start", "", "/min",
                "cmd", "/c", batFile.toAbsolutePath().toString())
                .start();
    }

    /**
     * 生成清理脚本(全 ASCII/英文注释,避免 cmd 代码页乱码)。
     * 逻辑:轮询等本应用 JVM 进程退出 → msiexec 卸载本体 → 删数据(带重试) → 自删脚本。
     */
    private String buildScript(long appPid, String productCode, Path dataDir, boolean deleteData) {
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\r\n");
        sb.append("cd /d \"%TEMP%\"\r\n");
        // 等待本应用进程退出,释放文件/DLL 句柄(最多 ~30s 兜底)
        sb.append("set /a n=0\r\n");
        sb.append(":waitloop\r\n");
        sb.append("tasklist /FI \"PID eq ").append(appPid).append("\" 2>nul | findstr /I \"")
          .append(appPid).append("\" >nul\r\n");
        sb.append("if %errorlevel%==0 (\r\n");
        sb.append("  set /a n+=1\r\n");
        sb.append("  if %n% lss 30 ( timeout /t 1 /nobreak >nul & goto waitloop )\r\n");
        sb.append(")\r\n");
        // 再稍等,确保 JCEF Helper 子进程退出、解除对 jcef-bundle DLL 的占用
        sb.append("timeout /t 2 /nobreak >nul\r\n");
        // 卸载程序本体(per-machine 会弹 UAC);无 ProductCode 时跳过
        if (productCode != null && !productCode.isBlank()) {
            sb.append("msiexec /x ").append(productCode).append("\r\n");
        }
        // 数据清理:jcef-bundle 缓存两分支都删;删整目录仅在 deleteData
        String jcef = dataDir.resolve("jcef-bundle").toString();
        appendRmdirWithRetry(sb, jcef);
        if (deleteData) {
            appendRmdirWithRetry(sb, dataDir.toString());
        }
        // 自删脚本
        sb.append("del \"%~f0\"\r\n");
        return sb.toString();
    }

    /** 追加一段带 3 次重试的 rmdir(兜底残留进程短暂锁定目录)。 */
    private void appendRmdirWithRetry(StringBuilder sb, String dir) {
        sb.append("for /l %%i in (1,1,3) do (\r\n");
        sb.append("  if exist \"").append(dir).append("\" (\r\n");
        sb.append("    rmdir /s /q \"").append(dir).append("\" 2>nul\r\n");
        sb.append("    if exist \"").append(dir).append("\" timeout /t 1 /nobreak >nul\r\n");
        sb.append("  )\r\n");
        sb.append(")\r\n");
    }
}
