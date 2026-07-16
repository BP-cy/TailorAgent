package com.changy.tailoragent.tool.knowledge;

import com.changy.tailoragent.env.CommandRunner;
import com.changy.tailoragent.tool.knowledge.KnowledgeVisualizationToolCatalog.ParameterSpec;
import com.changy.tailoragent.tool.knowledge.KnowledgeVisualizationToolCatalog.RendererSpec;
import com.changy.tailoragent.tool.support.DataStructureVisualizerProvisioner;
import com.changy.tailoragent.web.AppPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/**
 * 知识库 AI 编辑专用的数据结构可视化工具集。
 *
 * <p>对模型暴露的是与内置 Skill 一一对应的独立工具，例如 {@code kb_draw_array}、
 * {@code kb_draw_dag} 和 {@code kb_draw_skip_list}。每个工具都有 renderer 专属 JSON Schema，模型直接
 * 提交真实参数名，不再经过容易猜错的 {@code renderer/args/kwargs/engine} 通用入口。</p>
 *
 * <p>所有工具最终复用同一条受控执行链：只允许 catalog 中的 renderer，不接受脚本路径、命令行或输出
 * 目录；图片强制写入 {@link AppPaths#mediaDir()}，成功时只返回可插入 Markdown 的
 * {@code /media/...png} 路径。</p>
 */
@Component
public class KnowledgeVisualizeDataStructureTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVisualizeDataStructureTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ParameterizedTypeReference<Map<String, Object>> TOOL_INPUT_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final Duration RENDER_TIMEOUT = Duration.ofSeconds(90);
    private static final int MAX_SPEC_BYTES = 2 * 1024 * 1024;
    private static final Set<String> RESERVED_KWARGS = Set.of(
            "filename", "output", "ax", "axes", "theme");

    private final DataStructureVisualizerProvisioner provisioner;
    private final CommandRunner commandRunner;
    private final ToolCallback[] toolCallbacks;

    public KnowledgeVisualizeDataStructureTool(DataStructureVisualizerProvisioner provisioner,
                                               CommandRunner commandRunner) {
        this.provisioner = provisioner;
        this.commandRunner = commandRunner;
        this.toolCallbacks = KnowledgeVisualizationToolCatalog.renderers().stream()
                .map(this::createToolCallback)
                .toArray(ToolCallback[]::new);
    }

    /** 返回 42 个 modern renderer 工具和 1 个 legacy-only skip-list 工具。 */
    public ToolCallback[] getToolCallbacks() {
        return toolCallbacks.clone();
    }

    private ToolCallback createToolCallback(RendererSpec renderer) {
        Function<Map<String, Object>, String> function = input -> invokeRendererTool(renderer, input);
        return FunctionToolCallback.builder(renderer.toolName(), function)
                .description(renderer.description()
                        + "。调用前加载 visualize-data-structures Skill；成功时只返回 /media/...png 路径。")
                .inputType(TOOL_INPUT_TYPE)
                .inputSchema(KnowledgeVisualizationToolCatalog.inputSchema(renderer))
                // FunctionToolCallback 默认会把 String 再编码成 JSON 字符串；路径工具必须原样返回。
                .toolCallResultConverter((result, returnType) -> result == null ? "" : result.toString())
                .build();
    }

    /** 把 renderer 专属的扁平工具参数转换为受控 Python 调用规格。 */
    private String invokeRendererTool(RendererSpec renderer, Map<String, Object> rawInput) {
        Map<String, Object> input = rawInput == null ? Map.of() : rawInput;
        Set<String> unknown = new TreeSet<>(input.keySet());
        unknown.removeAll(renderer.parameterNames());
        if (!unknown.isEmpty()) {
            return "错误: " + renderer.toolName() + " 不支持参数 " + unknown + "。请按工具 schema 修正。";
        }

        for (ParameterSpec parameter : renderer.parameters()) {
            if (!parameter.required()) {
                continue;
            }
            if (!input.containsKey(parameter.name())) {
                return "错误: " + renderer.toolName() + " 缺少必需参数 " + parameter.name() + "。";
            }
            if (input.get(parameter.name()) == null && !parameter.nullable()) {
                return "错误: " + renderer.toolName() + " 的必需参数 " + parameter.name() + " 不能为 null。";
            }
        }

        Object rawFileName = input.get("file_name");
        if (rawFileName != null && !(rawFileName instanceof String)) {
            return "错误: file_name 必须是字符串。";
        }

        Map<String, Object> kwargs = new LinkedHashMap<>();
        for (ParameterSpec parameter : renderer.parameters()) {
            if (!parameter.name().equals("file_name") && input.containsKey(parameter.name())) {
                kwargs.put(parameter.name(), input.get(parameter.name()));
            }
        }
        return visualize(renderer.renderer(), List.of(), kwargs, renderer.engine(), (String) rawFileName);
    }

    /**
     * 内部统一渲染入口；不带 {@code @Tool}，因此不会再把通用 renderer/args/kwargs 契约暴露给模型。
     * 保留为 public 便于针对受控执行链做单元测试。
     */
    public String visualize(String renderer,
                            List<Object> args,
                            Map<String, Object> kwargs,
                            String engine,
                            String fileName) {
        if (renderer == null || renderer.isBlank()) {
            return "错误: renderer 不能为空。";
        }
        String normalizedEngine = engine == null || engine.isBlank()
                ? KnowledgeVisualizationToolCatalog.MODERN_ENGINE
                : engine.strip().toLowerCase(Locale.ROOT);
        if (!normalizedEngine.equals(KnowledgeVisualizationToolCatalog.MODERN_ENGINE)
                && !normalizedEngine.equals(KnowledgeVisualizationToolCatalog.LEGACY_ENGINE)) {
            return "错误: engine 只能是 modern 或 legacy。";
        }
        String normalizedRenderer = renderer.strip();
        boolean allowlisted = KnowledgeVisualizationToolCatalog.renderers().stream()
                .anyMatch(item -> item.engine().equals(normalizedEngine)
                        && item.renderer().equals(normalizedRenderer));
        if (!allowlisted) {
            return "错误: renderer " + normalizedRenderer + " 未在 " + normalizedEngine + " 工具 allowlist 中。";
        }
        if (kwargs != null) {
            for (String reserved : RESERVED_KWARGS) {
                if (kwargs.containsKey(reserved)) {
                    return "错误: renderer 参数不允许包含 " + reserved + "，该参数由工具管理。";
                }
            }
        }

        Path scriptsDir = provisioner.scriptsDir().orElse(null);
        if (scriptsDir == null) {
            return "错误: 内置数据结构可视化脚本不可用。";
        }

        try {
            Files.createDirectories(AppPaths.mediaDir());
            String safeName = normalizeFileName(fileName);
            Path output = AppPaths.mediaDir().resolve(safeName).toAbsolutePath().normalize();
            Path mediaRoot = AppPaths.mediaDir().toAbsolutePath().normalize();
            if (!output.startsWith(mediaRoot)) {
                return "错误: 图片输出路径越出媒体目录。";
            }

            String normalizedSpec = buildSpecification(
                    normalizedEngine, normalizedRenderer, args, kwargs, output);
            if (normalizedSpec.getBytes(StandardCharsets.UTF_8).length > MAX_SPEC_BYTES) {
                return "错误: 绘图规格过大，不能超过 2 MiB。";
            }
            Path rendererScript = scriptsDir.resolve("render.py").toAbsolutePath().normalize();
            // Windows 命令行长度约 32K，不能把最大 2 MiB 的 JSON 直接放进 --json 参数。
            // 临时规格只写到工具私有脚本目录，路径不由模型控制，并在调用结束后立即删除。
            Path specFile = Files.createTempFile(scriptsDir, "render-spec-", ".json");
            CommandRunner.Result result;
            try {
                Files.writeString(specFile, normalizedSpec, StandardCharsets.UTF_8);
                result = runPython(rendererScript, specFile, mediaRoot);
            } finally {
                Files.deleteIfExists(specFile);
            }
            if (!result.ok()) {
                Files.deleteIfExists(output);
                if (result.timedOut()) {
                    return "错误: 数据结构图片生成超时。";
                }
                String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
                return "错误: 数据结构图片生成失败: " + truncate(detail, 2000);
            }
            if (!Files.isRegularFile(output) || Files.size(output) == 0) {
                Files.deleteIfExists(output);
                return "错误: 渲染器未生成预期图片。";
            }

            // 工具结果只返回 Markdown 可直接使用的同源 URL，避免模型误插本机绝对路径。
            return "/media/" + safeName;
        } catch (Exception e) {
            log.warn("生成数据结构图片失败: {}", e.getMessage(), e);
            return "错误: 数据结构图片生成失败: " + e.getMessage();
        }
    }

    private CommandRunner.Result runPython(Path renderer, Path specFile, Path workingDir) {
        List<List<String>> candidates = List.of(
                List.of("python", renderer.toString(), "--spec", specFile.toString()),
                List.of("py", "-3", renderer.toString(), "--spec", specFile.toString()),
                List.of("python3", renderer.toString(), "--spec", specFile.toString()));
        CommandRunner.Result last = null;
        for (List<String> command : candidates) {
            last = commandRunner.runExec(RENDER_TIMEOUT, workingDir, StandardCharsets.UTF_8, command);
            // -1 表示进程未能启动；此时尝试下一个常见 Python 启动器。
            if (last.exitCode() != -1) {
                return last;
            }
        }
        return last;
    }

    private String buildSpecification(String engine,
                                      String renderer,
                                      List<Object> args,
                                      Map<String, Object> kwargs,
                                      Path output) throws Exception {
        ObjectNode object = MAPPER.createObjectNode();
        object.put("engine", engine);
        object.put("renderer", renderer);
        object.set("args", MAPPER.valueToTree(args == null ? List.of() : args));
        object.set("kwargs", MAPPER.valueToTree(kwargs == null ? Map.of() : kwargs));
        object.put("output", output.toString());
        return MAPPER.writeValueAsString(object);
    }

    private String normalizeFileName(String raw) {
        String base = raw == null ? "" : raw.trim();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isBlank()) {
            base = "data-structure-" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (base.length() > 80) {
            base = base.substring(0, 80).replaceAll("-+$", "");
        }
        // 同名时生成唯一后缀，避免无意覆盖编辑器中已经引用的旧图片。
        String candidate = base + ".png";
        if (Files.exists(AppPaths.mediaDir().resolve(candidate))) {
            candidate = base + "-" + UUID.randomUUID().toString().substring(0, 8) + ".png";
        }
        return candidate;
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "未知错误（请确认已安装 Python 3.10+、Matplotlib；legacy 渲染还需要 NumPy）";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "…";
    }
}
