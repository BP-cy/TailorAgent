package com.changy.tailoragent.chat.mapper;

import com.changy.tailoragent.chat.entity.ChatEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 事件 MyBatis Mapper。
 * XML 映射文件:classpath:mapper/ChatEventMapper.xml
 */
@Mapper
public interface ChatEventMapper {

    /** 插入事件,useGeneratedKeys 回填 id */
    int insert(ChatEvent event);

    /** 按会话查整条事件流,自增 id 升序(唯一定序键) */
    List<ChatEvent> findBySession(@Param("sessionId") Integer sessionId);

    /** 轮次内已有事件数 —— 用于生成冗余 seq */
    int countByTurn(@Param("turnId") Integer turnId);

    /** 删除某轮次的全部事件 —— 供后续「回退一轮」功能使用 */
    int deleteByTurn(@Param("turnId") Integer turnId);

    /** 删除整会话的全部事件 —— 删除会话时级联使用 */
    int deleteBySession(@Param("sessionId") Integer sessionId);
}
