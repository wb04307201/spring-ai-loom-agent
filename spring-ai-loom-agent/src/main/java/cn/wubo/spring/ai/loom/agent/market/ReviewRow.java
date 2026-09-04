package cn.wubo.spring.ai.loom.agent.market;

import java.time.LocalDateTime;

public record ReviewRow(
    Long marketId,
    String username,
    int rating,
    String comment,
    int editCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
