package com.changy.tailoragent.env;

import com.changy.tailoragent.env.dto.RuntimeStatusDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地运行时检测 —— Node.js（npx 依赖）与 uv（uvx 依赖）。
 * <p>
 * 用 {@code <cmd> --version} 探测，经 {@link CommandRunner} 走 {@code cmd.exe /c} +
 * 增强 PATH，确保解析路径与 MCP stdio 启动一致。按需检测（每次取最新，
 * 避免装完后仍显示旧结果）。
 */
@Service
public class RuntimeDetectionService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeDetectionService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** 匹配形如 v20.11.1 / 0.5.7 的版本号 */
    private static final Pattern VERSION = Pattern.compile("v?(\\d+\\.\\d+\\.\\d+\\S*)");

    private final CommandRunner runner;

    public RuntimeDetectionService(CommandRunner runner) {
        this.runner = runner;
    }

    /** 检测全部支持的运行时 */
    public List<RuntimeStatusDto> detectAll() {
        return List.of(
                detect("node", "Node.js", "node"),
                detect("uv", "uv", "uv")
        );
    }

    private RuntimeStatusDto detect(String id, String displayName, String command) {
        String checked = command + " --version";
        CommandRunner.Result r = runner.run(TIMEOUT, command, "--version");
        boolean installed = r.ok() && r.stdout() != null && !r.stdout().isBlank();
        String version = installed ? parseVersion(r.stdout()) : null;
        if (!installed) {
            log.info("运行时未检测到: {} ({})", displayName, r.timedOut() ? "超时" : "exit=" + r.exitCode());
        }
        return new RuntimeStatusDto(id, displayName, installed, version, checked);
    }

    /** 从 --version 输出里提取版本号，提取不到则返回原始首行 */
    private String parseVersion(String out) {
        String firstLine = out.lines().findFirst().orElse(out).trim();
        Matcher m = VERSION.matcher(firstLine);
        return m.find() ? m.group(1) : firstLine;
    }
}
