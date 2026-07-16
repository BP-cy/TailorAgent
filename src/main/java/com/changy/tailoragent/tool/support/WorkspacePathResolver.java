package com.changy.tailoragent.tool.support;

import com.changy.tailoragent.ModelConfig.service.AppConfigService;
import com.changy.tailoragent.web.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 路径解析与安全收口 —— 所有文件/搜索工具的入参路径都必须先过这里。
 * <p>
 * 对应 Claude Code 内置工具里的 {@code expandPath} + UNC 防护,并在其上加入<b>工作区边界</b>:
 * <ul>
 *   <li><b>相对路径挂到工作区根</b>(而非进程 CWD,否则 {@code mvn spring-boot:run} 下会落到项目根);</li>
 *   <li>展开 {@code ~} 为用户主目录;转绝对并 {@code normalize()}(消除 {@code ..} 穿越);</li>
 *   <li>拒绝 UNC 路径({@code \\server\share}),防 Windows 上 SMB/NTLM 凭据外泄;</li>
 *   <li>{@link #resolveForWrite} 额外强制结果<b>必须落在工作区根内</b> —— 给 Write/Edit 用,
 *       杜绝 agent 误写工作区外的文件。Read/Glob/Grep/Bash 走不限制的 {@link #resolve}。</li>
 * </ul>
 * <p>
 * <b>工作区根 = 用户在前端选择/新建的 {@code config.workingDir}</b>(每次调用实时读取,切换即时生效),
 * 这才是用户认知里的工作区。仅在用户尚未设置(首次安装)时,兜底到容器目录
 * {@link AppPaths#workspaceContainerDir()}({@code AppPaths.dataDir()/workspace}),保证工具不硬失败。
 */
@Component
public class WorkspacePathResolver {

    private static final Logger log = LoggerFactory.getLogger(WorkspacePathResolver.class);

    private final AppConfigService appConfigService;

    public WorkspacePathResolver(AppConfigService appConfigService) {
        this.appConfigService = appConfigService;
    }

    /**
     * 当前工作区根:取 {@code config.workingDir},为空则兜底到容器目录。
     * 每次调用实时读取,使前端切换工作区后立即对工具生效。已绝对化 + normalize,并尽力确保目录存在。
     */
    private Path currentRoot() {
        String configured = appConfigService.getConfig().getWorkingDir();
        Path root = (configured == null || configured.isBlank())
                ? AppPaths.workspaceContainerDir()
                : Paths.get(configured);
        root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.warn("创建工作区目录失败: {} ({})", root, e.getMessage());
        }
        return root;
    }

    /** 工作区根目录(已绝对化);Glob/Grep/Bash 省略 path 时的回退目录。 */
    public Path defaultRoot() {
        return currentRoot();
    }

    /**
     * 读取/检索用解析:相对路径挂到工作区根下;<b>不</b>强制包含(允许读工作区外的文件,
     * 因为 Read/Grep 常需查看用户其它项目或系统文件),仅做 UNC 防护。
     *
     * @throws ToolInputException 路径为空或为 UNC
     */
    public Path resolve(String raw) {
        return toAbsolute(raw, currentRoot());
    }

    /**
     * 写入/编辑用解析:在 {@link #resolve} 基础上,强制结果<b>必须落在工作区根内</b>,
     * 否则抛错。供 Write/Edit 调用,防止 agent 改动工作区之外的任何文件。
     *
     * @throws ToolInputException 路径为空 / 为 UNC / 越出工作区根
     */
    public Path resolveForWrite(String raw) {
        Path root = currentRoot();
        Path p = toAbsolute(raw, root);
        if (!p.startsWith(root)) {
            throw new ToolInputException(
                    "出于安全考虑,只能在工作区目录内创建或修改文件。\n工作区根: " + root
                            + "\n目标路径: " + p
                            + "\n请使用工作区内的相对路径(如 \"index.html\")或位于工作区内的绝对路径。");
        }
        return p;
    }

    /** 公共解析:校验非空/非 UNC,展开 ~,相对路径挂到工作区根,转绝对并规范化。 */
    private Path toAbsolute(String raw, Path root) {
        if (raw == null || raw.isBlank()) {
            throw new ToolInputException("路径不能为空");
        }
        String expanded = expandHome(raw.trim());

        // UNC 防护:Windows 上对 \\server\share 做文件操作会触发 SMB 认证,可能外泄凭据。
        if (expanded.startsWith("\\\\") || expanded.startsWith("//")) {
            throw new ToolInputException("出于安全考虑,拒绝访问 UNC 网络路径: " + raw);
        }

        Path p = Paths.get(expanded);
        if (!p.isAbsolute()) {
            // 关键:相对路径挂到工作区根,而非进程 CWD(避免落到项目根)
            p = root.resolve(p);
        }
        return p.normalize();
    }

    /** {@code ~} / {@code ~/x} 展开为用户主目录。 */
    private String expandHome(String p) {
        if (p.equals("~")) {
            return System.getProperty("user.home");
        }
        if (p.startsWith("~/") || p.startsWith("~\\")) {
            return System.getProperty("user.home") + p.substring(1);
        }
        return p;
    }
}
