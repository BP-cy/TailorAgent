package com.changy.tailoragent.chat.mapper;

import com.changy.tailoragent.chat.entity.ChatTurn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 轮次 MyBatis Mapper。
 * XML 映射文件:classpath:mapper/ChatTurnMapper.xml
 */
@Mapper
public interface ChatTurnMapper {

    /** 插入轮次,useGeneratedKeys 回填 id */
    int insert(ChatTurn turn);

    /** 更新轮次状态(running → done / error / cancelled) */
    int updateStatus(@Param("id") Integer id, @Param("status") String status);

    /** 回填本轮使用的模型与 token 用量(usage_json),用于上下文占比展示 */
    int updateUsage(@Param("id") Integer id,
                    @Param("model") String model,
                    @Param("usageJson") String usageJson);

    /** 取该会话最新一条非空 usage_json(供切换会话时回读上下文占用) */
    String findLatestUsageBySession(@Param("sessionId") Integer sessionId);

    /** 会话内已有轮次数 —— 用于生成冗余 seq */
    int countBySession(@Param("sessionId") Integer sessionId);

    /** 查询所有 running 状态的轮次 —— 供启动恢复使用 */
    List<ChatTurn> findByStatus(@Param("status") String status);

    /** 删除整会话的全部轮次 —— 删除会话时级联使用 */
    int deleteBySession(@Param("sessionId") Integer sessionId);
}
