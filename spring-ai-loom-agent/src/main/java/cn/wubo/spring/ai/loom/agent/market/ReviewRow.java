package cn.wubo.spring.ai.loom.agent.market;

import java.time.LocalDateTime;

/**
 * Review row returned from {@link IMarketContentReviewService}.
 *
 * <p>Generic over the market-id type (M3+ T1.7 gap / R2 — mirrors the T1.5
 * {@link StatsRow} pattern): {@code Long} for skill reviews
 * ({@code market_skill_review.market_skill_id} is {@code BIGINT}),
 * {@code String} (UUID) for KB reviews
 * ({@code loom_market_knowledge_review.market_id} is {@code VARCHAR(36)}
 * post-T1.1 schema migration).
 *
 * @param marketId  the market-content id; {@code Long} for skill,
 *                  {@code String} (UUID) for KB
 * @param username  reviewer username (second PK segment)
 * @param rating    1-5 star rating
 * @param comment   review comment (nullable)
 * @param editCount number of times the review was updated (max 1 per contract)
 * @param createdAt row creation timestamp
 * @param updatedAt last modification timestamp
 * @param <K>       market-id type
 */
public record ReviewRow<K>(
    K marketId,
    String username,
    int rating,
    String comment,
    int editCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
