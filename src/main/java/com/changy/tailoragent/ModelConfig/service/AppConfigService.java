package com.changy.tailoragent.ModelConfig.service;

import com.changy.tailoragent.ModelConfig.dto.AppConfig;
import com.changy.tailoragent.ModelConfig.event.ConfigChangedEvent;
import com.changy.tailoragent.web.AppPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 应用 AI 配置服务 —— 管理 AppPaths.dataDir()/app-config.json 的读写。
 * <p>
 * 桌面应用单用户场景，不需要分布式配置中心。配置文件放在数据目录下，
 * 与 SQLite 数据库同级（参见 {@link com.changy.tailoragent.Document.config.DataSourceConfig}）。
 * <p>
 * 线程安全：桌面应用 UI 单线程操作，{@code synchronized} 足够。
 */
@Service
public class AppConfigService {

    private static final Logger log = LoggerFactory.getLogger(AppConfigService.class);
    private static final String FILE_NAME = "app-config.json";
    private static final AppConfig DEFAULTS = new AppConfig();

    private final ObjectMapper objectMapper;
    private final Path configFile;
    private final ApplicationEventPublisher eventPublisher;
    private volatile AppConfig cached;

    public AppConfigService(ApplicationEventPublisher eventPublisher) {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.configFile = AppPaths.dataDir().resolve(FILE_NAME);
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    void init() {
        Path parent = configFile.getParent();
        if (parent != null && !parent.toFile().mkdirs() && !parent.toFile().isDirectory()) {
            log.warn("无法创建配置目录: {}", parent);
        }
        cached = loadConfig();
        log.info("应用配置已加载: 对话模型={}个, OCR模型={}个",
                cached.getAvailableChatModels().size(), cached.getAvailableOCRModels().size());
    }

    /** 获取当前配置 */
    public AppConfig getConfig() {
        return cached;
    }

    /** 全量更新配置并持久化 */
    public synchronized void saveConfig(AppConfig incoming) {
        AppConfig toSave = incoming != null ? incoming : DEFAULTS;
        writeConfig(toSave);
        cached = toSave;
        log.info("应用配置已更新: 对话模型={}个, OCR模型={}个",
                toSave.getAvailableChatModels().size(), toSave.getAvailableOCRModels().size());
        // 发布配置变更事件 —— 下游（如 MCP 客户端同步）订阅后做幂等 diff，
        // 与具体改了哪部分无关；无变化时 diff 自然空转。
        eventPublisher.publishEvent(new ConfigChangedEvent(toSave));
    }

    private AppConfig loadConfig() {
        if (!Files.isRegularFile(configFile)) {
            log.info("配置文件不存在，使用默认配置: {}", configFile);
            writeConfig(DEFAULTS);
            return new AppConfig();
        }
        try {
            String raw = Files.readString(configFile);
            return objectMapper.readValue(raw, AppConfig.class);
        } catch (IOException e) {
            log.warn("配置文件读取失败，使用默认配置: {}", e.getMessage());
            return new AppConfig();
        }
    }

    private void writeConfig(AppConfig config) {
        try {
            Path tmp = configFile.resolveSibling(FILE_NAME + ".tmp");
            objectMapper.writeValue(tmp.toFile(), config);
            try {
                Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("保存配置文件失败: {}", e.getMessage(), e);
            throw new RuntimeException("无法保存配置文件", e);
        }
    }
}
