package com.changy.tailoragent.knowledge.dto;

import java.util.List;

/**
 * 知识库 AI 编辑请求。
 *
 * @param docPath    当前打开的文档相对路径（如 MD/工作/报告.md）
 * @param instruction 用户的编辑指令
 * @param modelIndex 所用模型在配置列表中的索引（空则默认 0）
 * @param editId     前端生成的本轮编辑 id，用于用户主动取消时精确定位（空则后端兜底生成）
 * @param history    本轮之前的对话历史（提供跨轮记忆，仅随请求带上、<b>不落库</b>；空则无历史）
 */
public record KbEditRequest(String docPath, String instruction, Integer modelIndex, String editId,
                            List<Message> history) {

    /** 一条历史消息：role 为 {@code user} / {@code assistant}，content 为纯文本。 */
    public record Message(String role, String content) {
    }
}
