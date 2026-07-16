package com.changy.tailoragent.ModelConfig.controller;

import com.changy.tailoragent.ModelConfig.config.ProviderPreset;
import com.changy.tailoragent.ModelConfig.dto.AppConfig;
import com.changy.tailoragent.ModelConfig.dto.ChatModel;
import com.changy.tailoragent.ModelConfig.dto.EmbeddingModelConfig;
import com.changy.tailoragent.ModelConfig.dto.ProviderInfo;
import com.changy.tailoragent.ModelConfig.service.AppConfigService;
import com.changy.tailoragent.ModelConfig.service.EmbeddingModelManager;
import com.changy.tailoragent.ModelConfig.service.ModelManager;
import com.changy.tailoragent.common.exception.BusinessException;
import com.changy.tailoragent.common.response.ApiResponse;
import com.changy.tailoragent.web.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

import javax.swing.*;
import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;

/**
 * 应用配置 API —— 管理 app-config.json 的读写。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    private final AppConfigService configService;
    private final ModelManager modelManager;
    private final EmbeddingModelManager embeddingModelManager;

    public ConfigController(AppConfigService configService,
                            ModelManager modelManager,
                            EmbeddingModelManager embeddingModelManager) {
        this.configService = configService;
        this.modelManager = modelManager;
        this.embeddingModelManager = embeddingModelManager;
    }

    /** 获取完整 AI 配置（含可用模型列表） */
    @GetMapping
    public ApiResponse<AppConfig> getConfig() {
        return ApiResponse.success(configService.getConfig());
    }

    /** 全量保存 AI 配置（前端传来完整模型列表） */
    @PutMapping
    public ApiResponse<AppConfig> saveConfig(@RequestBody AppConfig incoming) {
        normalizeEmbeddingBatchSize(incoming);
        configService.saveConfig(incoming);
        return ApiResponse.success(configService.getConfig());
    }

    private static void normalizeEmbeddingBatchSize(AppConfig incoming) {
        if (incoming == null || incoming.getEmbeddingModel() == null) {
            return;
        }
        Integer configured = incoming.getEmbeddingModel().getBatchSize();
        if (configured == null) {
            incoming.getEmbeddingModel().setBatchSize(EmbeddingModelConfig.DEFAULT_BATCH_SIZE);
            return;
        }
        if (configured < 1 || configured > EmbeddingModelConfig.MAX_BATCH_SIZE) {
            throw new BusinessException("单次向量化条数必须在 1 到 "
                    + EmbeddingModelConfig.MAX_BATCH_SIZE + " 之间");
        }
    }

    /**
     * 测试对话模型连通性 —— 用给定的 baseUrl/apiKey/modelName 向 API 真发一句「你好」,
     * 能拿到非空回复即视为可用。前端添加模型时先调用此接口,通过才写入配置。
     * <p>
     * 返回 code=1 表示连通;code=-1 表示失败,message 为可读的失败原因。
     */
    @PostMapping("/test-connection")
    public ApiResponse<Void> testConnection(@RequestBody ChatModel cfg) {
        if (cfg == null || cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank()
                || cfg.getModelName() == null || cfg.getModelName().isBlank()) {
            return ApiResponse.error("缺少 Base URL 或模型名");
        }
        try {
            ChatClient client = modelManager.getOrCreate(cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModelName());
            String reply = client.prompt().user("你好").call().content();
            if (reply == null || reply.isBlank()) {
                return ApiResponse.error("模型无响应");
            }
            log.info("模型连接测试成功: model={}", cfg.getModelName());
            return ApiResponse.success("连接正常");
        } catch (Exception e) {
            String msg = rootMessage(e);
            log.warn("模型连接测试失败: model={}, err={}", cfg.getModelName(), msg);
            return ApiResponse.error("连接失败: " + msg);
        }
    }

    /** 测试知识库 Embedding 端点，并返回服务实际输出的向量维度。 */
    @PostMapping("/test-embedding")
    public ApiResponse<Integer> testEmbedding(@RequestBody EmbeddingModelConfig cfg) {
        if (cfg == null || !cfg.isConfigured()) {
            return ApiResponse.error("缺少 Base URL 或模型名");
        }
        try {
            float[] vector = embeddingModelManager.openSession(cfg).embed("知识库向量连接测试");
            return ApiResponse.success("连接正常，向量维度 " + vector.length, vector.length);
        } catch (Exception e) {
            return ApiResponse.error("连接失败: " + rootMessage(e));
        }
    }

    /** 取异常链最底层的可读信息,避免把一长串包装异常抛给前端 */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
    }

    /**
     * 获取所有预设厂商列表 —— 前端下拉框数据源。
     * 用户添加模型时可选择预设厂商快速填充 baseUrl。
     */
    @GetMapping("/providers")
    public ApiResponse<List<ProviderInfo>> providers() {
        List<ProviderInfo> list = Arrays.stream(ProviderPreset.values())
                .map(ProviderPreset::toInfo)
                .toList();
        return ApiResponse.success(list);
    }

    /**
     * 打开原生文件夹选择对话框，将选中路径写入配置并返回更新后的配置。
     * 使用 Swing JFileChooser，仅在桌面环境下有效。
     */
    @PostMapping("/select-working-dir")
    public ApiResponse<AppConfig> selectWorkingDir() {
        try {
            String[] result = new String[1];
            if (EventQueue.isDispatchThread()) {
                result[0] = openDirChooser();
            } else {
                SwingUtilities.invokeAndWait(() -> result[0] = openDirChooser());
            }
            String dir = result[0];
            if (dir == null || dir.isBlank()) {
                return ApiResponse.success(configService.getConfig());
            }
            AppConfig config = configService.getConfig();
            config.setWorkingDir(dir);
            configService.saveConfig(config);
            log.info("工作目录已更新: {}", dir);
            return ApiResponse.success(configService.getConfig());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApiResponse.success(configService.getConfig());
        } catch (InvocationTargetException e) {
            log.error("打开文件夹选择对话框失败", e.getCause());
            return ApiResponse.error("打开文件夹选择对话框失败: " + e.getCause().getMessage());
        }
    }

    /**
     * 新建工作区:在 {@link AppPaths#workspaceContainerDir()} 下创建
     * {@code ws-<年>-<月>-<日>-<时间戳>} 目录,设为 workingDir 并持久化,返回更新后的配置。
     * 供前端「新建工作区」入口调用;开始对话前先建好目录,使工具沙箱即时可用。
     */
    @PostMapping("/new-working-dir")
    public ApiResponse<AppConfig> newWorkingDir() {
        try {
            java.time.LocalDate d = java.time.LocalDate.now();
            String name = "ws-" + d.getYear() + "-" + d.getMonthValue() + "-"
                    + d.getDayOfMonth() + "-" + System.currentTimeMillis();
            java.nio.file.Path ws = AppPaths.workspaceContainerDir().resolve(name);
            java.nio.file.Files.createDirectories(ws);
            AppConfig config = configService.getConfig();
            config.setWorkingDir(ws.toAbsolutePath().toString());
            configService.saveConfig(config);
            log.info("已新建工作区: {}", ws);
            return ApiResponse.success(configService.getConfig());
        } catch (java.io.IOException e) {
            log.error("新建工作区失败", e);
            return ApiResponse.error("新建工作区失败: " + e.getMessage());
        }
    }

    private String openDirChooser() {
        // 切到 Windows 系统 Look-and-Feel,让 JFileChooser 尽量贴近原生外观
        // (仍是 Swing 绘制,非资源管理器原生对话框;只为观感更接近 Windows)
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            log.debug("设置系统 Look-and-Feel 失败,回退默认外观: {}", e.getMessage());
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择工作目录");
        // 如果已有工作目录，打开时定位到该目录
        String current = configService.getConfig().getWorkingDir();
        if (current != null && !current.isBlank()) {
            java.io.File f = new java.io.File(current);
            if (f.isDirectory()) {
                chooser.setCurrentDirectory(f);
            }
        }
        int result = chooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            java.io.File selected = chooser.getSelectedFile();
            return selected != null ? selected.getAbsolutePath() : null;
        }
        return null;
    }
}
