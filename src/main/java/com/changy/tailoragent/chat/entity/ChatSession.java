package com.changy.tailoragent.chat.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 会话实体，映射 chat_session 表。
 * <p>
 * 会话是最顶层容器:一个会话 → 多个轮次 {@link ChatTurn} → 多条事件 {@link ChatEvent}。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatSession implements Serializable {

    private Integer id;
    /** 会话标题(取首条用户消息截断生成) */
    private String  title;
    /** ISO 8601 创建时间 */
    private String  createdAt;
    /** ISO 8601 最后更新时间(每轮对话后刷新,用于侧边栏排序) */
    private String  updatedAt;
}
