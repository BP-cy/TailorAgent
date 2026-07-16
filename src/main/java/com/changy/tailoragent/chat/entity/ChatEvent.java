package com.changy.tailoragent.chat.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 事件实体,映射 chat_event 表 —— 轮次内的原子消息单元,异构。
 * <p>
 * 设计要点:
 * <ul>
 *   <li><b>顺序</b>:整会话事件流的唯一定序键是自增 {@link #id};
 *       {@link #turnId}/{@link #seq} 为冗余字段,仅供前端按轮分组,不参与排序。</li>
 *   <li><b>异构</b>:单表 + {@link #type} 判别 + {@link #payload}(JSON)承载类型相关数据,
 *       新增工具类型零迁移。工具名、读/写/改操作放进 payload,不开新 type。</li>
 *   <li><b>双轴</b>:{@link #role} 是投影轴(决定喂模型时的 API 消息角色 + 前端左右),
 *       {@link #type} 是渲染轴(决定前端用哪个组件)。</li>
 * </ul>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatEvent implements Serializable {

    private Integer id;
    private Integer turnId;
    private Integer sessionId;
    /** 轮次内顺序(冗余,不参与查询排序) */
    private Integer seq;
    /** 投影轴:user / assistant / tool / system */
    private String  role;
    /** 渲染轴:text / tool_call / tool_result */
    private String  type;
    /** 文本类内容(text 类型用) */
    private String  content;
    /** JSON:类型相关结构(工具名 tool / 操作 op / 参数 args / 结果 result / 关联 callId) */
    private String  payload;
    /** 工具类状态:pending / running / success / error(文本类为 null) */
    private String  status;
    /** ISO 8601 创建时间 */
    private String  createdAt;
}
