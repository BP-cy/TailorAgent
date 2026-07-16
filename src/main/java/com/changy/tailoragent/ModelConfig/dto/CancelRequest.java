package com.changy.tailoragent.ModelConfig.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 取消对话请求 —— 主动停止某一正在运行的轮次。
 * <p>
 * {@code turnId} 由前端在 SSE {@code start} 事件中获得,精确定位要停止的那一轮。
 */
@Data
public class CancelRequest {

    /** 要取消的轮次 id */
    @NotNull(message = "turnId 不能为空")
    private Integer turnId;
}
