package cn.wubo.spring.ai.loom.agent.market;

import java.time.LocalDateTime;

public record MarketAnnouncement(
    String marketKind,
    Long marketId,
    String title,
    String body,
    LocalDateTime createdAt
) {}
