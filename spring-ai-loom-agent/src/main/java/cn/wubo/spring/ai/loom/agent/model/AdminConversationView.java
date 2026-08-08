package cn.wubo.spring.ai.loom.agent.model;

import java.time.Instant;

/**
 * 管理员视角的会话视图：包含元数据 + content 状态。
 */
public record AdminConversationView(
        String conversationId,
        String username,
        String nickname,
        String preview,
        Instant createdAt,
        Instant updatedAt,  /* V5.4：最后活跃时间 */
        Instant deletedAt,
        Boolean contentCleaned
) {
}
