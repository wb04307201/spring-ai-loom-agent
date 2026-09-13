package cn.wubo.spring.ai.loom.agent.skill.stats;

import cn.wubo.spring.ai.loom.agent.market.AbstractMarketStatsService;
import cn.wubo.spring.ai.loom.agent.market.BatchedCounterService;
import cn.wubo.spring.ai.loom.agent.market.StatsRow;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Stats for Skill marketplace content.
 * <p>
 * Backing table: {@code market_skill_stats}
 * <pre>
 *   market_skill_id BIGINT PRIMARY KEY,   -- FK -> market_skill(id)
 *   pull_count      BIGINT NOT NULL DEFAULT 0,
 *   last_pulled_at  TIMESTAMP
 * </pre>
 *
 * <p>This service owns exactly ONE counter column ({@code pull_count}) —
 * the {@code kind} parameter on
 * {@link cn.wubo.spring.ai.loom.agent.market.IMarketContentStatsService#incrementStat}
 * is documentation-only; callers should pass {@code "PULL"} for clarity in
 * logs. {@link AbstractMarketStatsService} ignores the value and routes the
 * delta to {@code pull_count} unconditionally.</p>
 *
 * <p>Type parameter pinned to {@code Long} (BIGINT PK, no migration required).
 * Backing counter is dispatched to {@link BatchedCounterService#increment(String, String, Long, String, String)}
 * — the legacy Long-key overload.</p>
 *
 * <p>Wiring: registered as a Spring {@code @Bean} in
 * {@code LoomAgentConfiguration.StorageConfiguration}. Spring picks this up
 * automatically; consumers don't need to touch the bean name.</p>
 */
public class DefaultSkillStatsService extends AbstractMarketStatsService<Long> {

    public static final String TABLE = "market_skill_stats";
    public static final String KEY_COL = "market_skill_id";
    public static final String COUNT_COL = "pull_count";
    public static final String LAST_COL = "last_pulled_at";

    public DefaultSkillStatsService(JdbcTemplate jdbc, BatchedCounterService batchedCounterService) {
        super(jdbc, batchedCounterService);
    }

    @Override
    protected String tableName() {
        return TABLE;
    }

    @Override
    protected String keyCol() {
        return KEY_COL;
    }

    @Override
    protected String countCol() {
        return COUNT_COL;
    }

    @Override
    protected String lastCol() {
        return LAST_COL;
    }

    @Override
    protected void bufferIncrement(Long marketId) {
        batchedCounterService.increment(TABLE, KEY_COL, marketId, COUNT_COL, LAST_COL);
    }

    @Override
    protected void ensureStatsRowExists(Long marketId) {
        // SQL identifier concatenation is intentional — these come from trusted
        // loom-agent code paths with fixed schemas; parameter binding only
        // covers the value. Mirrors the same rationale in
        // BatchedCounterService's JdbcFlushExecutor.
        String sql = "MERGE INTO " + TABLE
                + " (" + KEY_COL + ")"
                + " KEY(" + KEY_COL + ")"
                + " VALUES (?)";
        jdbc.update(sql, marketId);
    }

    @Override
    protected StatsRow<Long> readStatsRow(Long marketId) {
        return jdbc.queryForObject(
                "SELECT " + KEY_COL + ", " + COUNT_COL + ", " + LAST_COL
                        + " FROM " + TABLE + " WHERE " + KEY_COL + " = ?",
                (rs, n) -> {
                    Timestamp ts = rs.getTimestamp(LAST_COL);
                    LocalDateTime lastAt = (ts == null) ? null : ts.toLocalDateTime();
                    long count = rs.getLong(COUNT_COL);
                    return new StatsRow<>(rs.getLong(KEY_COL), count, lastAt);
                },
                marketId);
    }

    @Override
    protected StatsRow<Long> emptyStatsRow(Long marketId) {
        return new StatsRow<>(marketId, 0L, null);
    }

    @Override
    protected void discardBuffer(Long marketId) {
        if (batchedCounterService.discard(TABLE, KEY_COL, marketId, COUNT_COL)) {
            log.debug("{}: discarded buffered delta before reset for {}={}",
                    getClass().getSimpleName(), TABLE, marketId);
        }
    }

    @Override
    protected void writeReset(Long marketId, long newCount, Timestamp ts) {
        jdbc.update(
                "UPDATE " + TABLE
                        + " SET " + COUNT_COL + " = ?, " + LAST_COL + " = ?"
                        + " WHERE " + KEY_COL + " = ?",
                newCount, ts, marketId);
    }
}
