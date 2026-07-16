package com.changy.tailoragent.chat.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 轮次实体,映射 chat_turn 表。
 * <p>
 * 一个轮次 = 一次用户输入 + 智能体对它的全部响应活动(可能含多条事件)。
 * 轮次承载聚合状态({@link #status}),这是它独立于单条事件存在的理由。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatTurn implements Serializable {

    private Integer id;
    private Integer sessionId;
    /** 会话内第几轮(冗余,不参与查询排序) */
    private Integer seq;
    /** 场景类型:qa(问答) / task(任务执行) */
    private String  kind;
    /** 聚合状态:running / done / error / cancelled */
    private String  status;
    /** 预留冗余:本轮使用的模型,当前不填充 */
    private String  model;
    /** 预留冗余:token 用量 / 耗时(JSON),当前不填充 */
    private String  usageJson;
    /** ISO 8601 创建时间 */
    private String  createdAt;
}
