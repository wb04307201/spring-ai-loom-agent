package cn.wubo.spring.ai.loom.agent.tool;

import cn.wubo.spring.ai.loom.agent.model.ToolCallLog;

import java.util.List;

public interface IToolCallLogRepository {

    /**
     * 写入一条工具调用日志（id 由数据库自增，{@code logId} 入库后被填回）。
     */
    ToolCallLog save(ToolCallLog log);

    /**
     * 按 conversationId 查所有工具调用日志（按 created_at 升序）。{@code page}/{@code size} 分页
     * 由调用方控制（service 层）；这里直接拿全集返回，便于 service 内存里分页 + 合并。
     */
    List<ToolCallLog> findByConversationId(String conversationId);

    /**
     * 按 (username, conversationId, toolCallId) 查唯一记录（用于写入前去重）。
     */
    java.util.Optional<ToolCallLog> findByCallId(String conversationId, String toolCallId);
}
