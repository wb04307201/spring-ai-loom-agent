package cn.wubo.spring.ai.loom.agent.model;

import java.time.Instant;

/**
 * 管理员视角的会话视图：包含元数据 + content 状态 + 单会话统计指标。
 *
 * <p>stats 字段（messageCount / totalTokens / toolCallCount / subtaskCount /
 * scheduleCount / errorCount）在 {@link cn.wubo.spring.ai.loom.agent.user.DefaultUserConversation#adminListByUsername}
 * 里用 6 个标量子查询一次性聚合，避免 N+1（N 个会话只跑 1 次 SQL）。
 */
public record AdminConversationView(
 String conversationId,
 String username,
 String nickname,
 String preview,
 Instant createdAt,
 Instant updatedAt, /* ：最后活跃时间 */
 Instant deletedAt,
 Boolean contentCleaned,
 /* 单会话 mini stats（user.html 表格"预览"列下方显示） */
 long messageCount,
 long totalTokens,
 long toolCallCount,
 long subtaskCount,
 long scheduleCount,
 long errorCount
) {
}
