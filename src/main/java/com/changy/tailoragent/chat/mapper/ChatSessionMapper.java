package com.changy.tailoragent.chat.mapper;

import com.changy.tailoragent.chat.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会话 MyBatis Mapper。
 * XML 映射文件:classpath:mapper/ChatSessionMapper.xml
 */
@Mapper
public interface ChatSessionMapper {

    /** 列出全部会话,按最后更新时间倒序(侧边栏列表) */
    List<ChatSession> findAll();

    ChatSession findById(@Param("id") Integer id);

    /** 插入会话,useGeneratedKeys 回填 id */
    int insert(ChatSession session);

    /** 刷新最后更新时间(每轮对话后调用,使会话在列表中置顶) */
    int touchUpdatedAt(@Param("id") Integer id, @Param("updatedAt") String updatedAt);

    int deleteById(@Param("id") Integer id);
}
