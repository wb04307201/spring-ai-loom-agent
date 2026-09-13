package cn.wubo.spring.ai.loom.agent.market;

import java.time.LocalDateTime;

/**
 * Stats row returned from {@link IMarketContentStatsService#getStats}.
 *
 * <p>Generic over the market-id type: {@code Long} for skill stats (PK is
 * {@code BIGINT}), {@code String} (UUID) for KB stats (PK is {@code VARCHAR(36)}
 * post-T1.1). Type parameter is erased to {@code Object} at runtime; callers
 * that need type-specific behavior should pin {@code K} at the call-site.
 *
 * @param marketId                 the market-content id; {@code Long} for skill,
 *                                 {@code String} (UUID) for KB. {@code null} only
 *                                 for the "no key supplied" sentinel.
 * @param pullCountOrSearchCount   the single counter column value
 *                                 ({@code pull_count} for skill, {@code search_count} for KB).
 * @param lastAt                   timestamp of the most recent increment/reset;
 *                                 {@code null} if the row has never been touched.
 * @param <K>                      market-id type
 */
public record StatsRow<K>(K marketId, long pullCountOrSearchCount, LocalDateTime lastAt) {
}
