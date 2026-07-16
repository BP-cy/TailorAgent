package com.changy.tailoragent.ModelConfig.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 对话请求。
 * <p>
 * 会话持久化后,历史由后端从数据库投影,前端不再回传整段 messages,
 * 每轮只发送本轮用户输入 {@code content}。
 * <ul>
 *   <li>{@code sessionId} 为 null 表示新建会话;非 null 表示在已有会话内追加一轮。</li>
 *   <li>{@code modelIndex} 指定用 {@link AppConfig#getAvailableChatModels()} 中第几个模型,
 *       对应前端 ChatPanel 下拉框的索引。</li>
 * </ul>
 */
@Data
public class ChatRequest {

    /** 目标会话 id;为 null 表示新建会话 */
    private Integer sessionId;

    /** 用户本轮输入内容 */
    @NotBlank(message = "消息内容不能为空")
    private String content;

    /** 可用对话模型列表中的索引,默认 0(第一个) */
    private int modelIndex = 0;
}
