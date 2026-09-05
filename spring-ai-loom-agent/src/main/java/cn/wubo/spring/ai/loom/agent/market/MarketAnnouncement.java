package cn.wubo.spring.ai.loom.agent.market;

import java.time.LocalDateTime;

/**
 * 市场公告行 — 对应 {@code market_content_announcement} 表。
 *
 * <p>M3+ R3:{@code marketId} 是 String — B1 真修后 {@code market_id} 列为
 * VARCHAR(36),SKILL(BIGINT 起源,以十进制字符串存储)与 KNOWLEDGE(UUID)
 * 共用同一列类型,无需 Long↔VARCHAR 强转。
 */
public record MarketAnnouncement(
    String marketKind,
    String marketId,
    String title,
    String body,
    LocalDateTime createdAt
) {}
