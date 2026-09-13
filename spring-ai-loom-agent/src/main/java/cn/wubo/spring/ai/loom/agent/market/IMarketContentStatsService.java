package cn.wubo.spring.ai.loom.agent.market;

import java.time.LocalDateTime;

/**
 * 市场内容 stats 接口(M3+ T1.4 + T1.5)。
 *
 * <p>类型参数 {@code <K>} 表示 market id 形态,允许两端用各自的主键类型:
 * <ul>
 *   <li>{@code Long} — skill stats(BIGINT 主键,沿用历史 Long 路径)</li>
 *   <li>{@code String} — KB stats(VARCHAR(36) UUID 主键,T1.1 schema 迁移后),
 *       String 路径<b>走真路径</b>——直接传给 {@link BatchedCounterService} 的
 *       String-key overload,<b>不再有 Long.parseLong 静默 no-op</b></li>
 * </ul>
 *
 * <p>对应实现:
 * <ul>
 *   <li>{@link cn.wubo.spring.ai.loom.agent.skill.stats.DefaultSkillStatsService}
 *       implements {@code IMarketContentStatsService<Long>}</li>
 *   <li>{@link cn.wubo.spring.ai.loom.agent.knowledge.stats.DefaultKnowledgeStatsService}
 *       implements {@code IMarketContentStatsService<String>}</li>
 * </ul>
 *
 * <p>{@link #incrementStat(K, String)} 的 {@code kind} 参数是 documentation-only:
 * 每个实现只对应一个 counter 列(skill {@code pull_count} / KB {@code search_count}),
 * 调用方应传 canonical 常量({@code "PULL"} / {@code "SEARCH"})以便日志 grep。
 *
 * @param <K> market id 类型:Long for skill,String (UUID) for KB
 */
public interface IMarketContentStatsService<K> {

    /**
     * Increment the counter for {@code marketId}. The {@code kind} parameter is
     * documentation-only — the column is hard-coded by the implementation.
     *
     * <p>Side effects (delegated to {@link AbstractMarketStatsService}):
     * <ul>
     *   <li>Synchronously ensures a stats row exists (H2 {@code MERGE INTO}).</li>
     *   <li>Buffers a delta of +1 in {@link BatchedCounterService}; the actual
     *       UPDATE is flushed every 30s (or on shutdown).</li>
     * </ul>
     *
     * <p>Best-effort by contract: implementation MUST NOT propagate exceptions
     * to the caller (stats bookkeeping must never break the main path).
     */
    void incrementStat(K marketId, String kind);

    /**
     * Read the current stats row. If no row exists yet, returns a zero
     * {@link StatsRow} with {@code lastAt=null} — the lazy-upsert path
     * guarantees a row will exist after the first {@link #incrementStat},
     * but reads before the first increment are valid (no row → no traffic).
     *
     * <p>For UUID-backed (KB) stats: {@code marketId} in the returned row is
     * the String UUID as stored (post-T1.1 schema migration).
     */
    StatsRow<K> getStats(K marketId);

    /**
     * Reset the counter for {@code marketId} to a specific value (admin use).
     * Implementations should ensure the stats row exists (lazy-upsert) before
     * updating it, so an admin can reset a market-content item even if no
     * increment has ever landed yet. Also discards any buffered in-flight
     * delta for this {@code (table, keyCol, key, cntCol)} tuple so the admin
     * reset is not silently overwritten by the next scheduled flush.
     *
     * @param marketId  the market-content id
     * @param newCount  the new counter value (admin typically passes 0)
     * @param newLastAt timestamp to record for the most-recent event; pass
     *                  {@code null} for canonical "reset" semantics
     */
    void resetStats(K marketId, long newCount, LocalDateTime newLastAt);
}
