package com.changy.tailoragent.knowledge.dto;

/**
 * 取消知识库 AI 编辑请求 —— 主动停止某一正在运行的编辑轮次。
 *
 * @param editId 要取消的编辑 id（由前端在发起编辑时生成，并经 SSE {@code start} 事件回传确认）
 */
public record KbCancelRequest(String editId) {
}
