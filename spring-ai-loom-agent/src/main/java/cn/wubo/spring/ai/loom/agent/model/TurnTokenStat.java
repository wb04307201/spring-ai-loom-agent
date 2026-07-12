package cn.wubo.spring.ai.loom.agent.model;

import java.time.Instant;

public record TurnTokenStat(
        String conversationId,
        String username,
        String role,
        String model,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        Integer durationMs,
        Instant createdAt
) {
}
