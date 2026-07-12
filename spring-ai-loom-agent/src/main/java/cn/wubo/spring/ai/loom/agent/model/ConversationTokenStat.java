package cn.wubo.spring.ai.loom.agent.model;

import java.time.Instant;

public record ConversationTokenStat(
        String conversationId,
        String username,
        String preview,
        Instant createdAt,
        long totalTokens,
        long callCount
) {
}
