package cn.wubo.spring.ai.loom.agent.market;

import java.time.LocalDateTime;

public interface IMarketContentStatsService {
    void incrementStat(Long marketId, String kind);
    StatsRow getStats(Long marketId);

    /**
     * Reset the counter for {@code marketId} to a specific value (admin use).
     * Implementations should ensure the stats row exists (lazy-upsert) before
     * updating it, so an admin can reset a market-content item even if no
     * increment has ever landed yet.
     *
     * @param marketId the market-content id
     * @param newCount the new counter value (admin typically passes 0)
     * @param newLastAt timestamp to record for the most-recent event; pass
     *                 {@code null} for canonical "reset" semantics
     */
    void resetStats(Long marketId, long newCount, LocalDateTime newLastAt);
}