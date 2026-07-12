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
        Instant deletedAt,
        Boolean contentCleaned
) {
}
