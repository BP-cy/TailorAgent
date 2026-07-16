package com.changy.tailoragent.ModelConfig.event;

import com.changy.tailoragent.ModelConfig.dto.AppConfig;

/**
 * 应用配置变更事件 —— {@code AppConfigService.saveConfig()} 写盘后发布。
 * <p>
 * 解耦配置写入方与依赖配置的下游（如 MCP 客户端同步）：下游用
 * {@code @EventListener(ConfigChangedEvent.class)} 订阅，无需反向依赖 mcp 模块。
 * 携带最新配置快照，订阅方可直接取用。
 *
 * @param config 保存后的最新配置
 */
public record ConfigChangedEvent(AppConfig config) {
}
