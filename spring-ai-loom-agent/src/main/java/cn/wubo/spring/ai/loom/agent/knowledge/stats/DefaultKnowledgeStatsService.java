package cn.wubo.spring.ai.loom.agent.knowledge.stats;

import cn.wubo.spring.ai.loom.agent.market.AbstractMarketStatsService;
import cn.wubo.spring.ai.loom.agent.market.BatchedCounterService;
import cn.wubo.spring.ai.loom.agent.market.StatsRow;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Stats for Knowledge-Base marketplace content.
 * <p>
 * Backing table: {@code loom_market_knowledge_stats}
 * <pre>
 *   market_id        VARCHAR(36) PRIMARY KEY,   -- FK -> loom_market_knowledge(id) (UUID)
 *   search_count     BIGINT NOT NULL DEFAULT 0,
 *   last_searched_at TIMESTAMP
 * </pre>
 *
 * <p><b>Schema (post-T1.1):</b> the {@code loom_market_knowledge_stats.market_id}
 * column is now {@code VARCHAR(36)} UUID — matching the parent
 * {@code loom_market_knowledge.id}. String-keyed path is the canonical, no
 * longer a graceful-degradation workaround.</p>
 *
 * <p>This service owns exactly ONE counter column ({@code search_count}) —
 * the {@code kind} parameter on
 * {@link cn.wubo.spring.ai.loom.agent.market.IMarketContentStatsService#incrementStat}
 * is documentation-only; callers should pass {@code "SEARCH"} for clarity in
 * logs.</p>
 *
 * <p>Type parameter pinned to {@code String} (UUID PK). Backing counter is
 * dispatched to {@link BatchedCounterService#increment(String, String, String, String, String)}
 * — the String-key overload added in T1.5. The {@code DefaultKnowledgeTool.searchKnowledge}
 * call-site no longer wraps in {@code try { Long.parseLong } catch NFE}:
 * the String UUID goes straight through the BatchedCounterService pipeline
 * to a {@code WHERE market_id = ?} SQL bind.</p>
 *
 * <p>Wiring: registered as a Spring {@code @Bean} in
 * {@code LoomAgentConfiguration.StorageConfiguration}.</p>
 */
public class DefaultKnowledgeStatsService extends AbstractMarketStatsService<String> {

    public static final String TABLE = "loom_market_knowledge_stats";
    public static final String KEY_COL = "market_id";
    public static final String COUNT_COL = "search_count";
    public static final String LAST_COL = "last_searched_at";

    public DefaultKnowledgeStatsService(JdbcTemplate jdbc, BatchedCounterService batchedCounterService) {
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
    protected void bufferIncrement(String marketId) {
        batchedCounterService.increment(TABLE, KEY_COL, marketId, COUNT_COL, LAST_COL);
    }

    @Override
    protected void ensureStatsRowExists(String marketId) {
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
    protected StatsRow<String> readStatsRow(String marketId) {
        return jdbc.queryForObject(
                "SELECT " + KEY_COL + ", " + COUNT_COL + ", " + LAST_COL
                        + " FROM " + TABLE + " WHERE " + KEY_COL + " = ?",
                (rs, n) -> {
                    Timestamp ts = rs.getTimestamp(LAST_COL);
                    LocalDateTime lastAt = (ts == null) ? null : ts.toLocalDateTime();
                    long count = rs.getLong(COUNT_COL);
                    // KB market_id is VARCHAR(36) UUID (post-T1.1), so we read it
                    // back as String — NOT rs.getLong() which would coerce to 0L
                    // and lose the id value.
                    return new StatsRow<>(rs.getString(KEY_COL), count, lastAt);
                },
                marketId);
    }

    @Override
    protected StatsRow<String> emptyStatsRow(String marketId) {
        return new StatsRow<>(marketId, 0L, null);
    }

    @Override
    protected void discardBuffer(String marketId) {
        if (batchedCounterService.discard(TABLE, KEY_COL, marketId, COUNT_COL)) {
            log.debug("{}: discarded buffered delta before reset for {}={}",
                    getClass().getSimpleName(), TABLE, marketId);
        }
    }

    @Override
    protected void writeReset(String marketId, long newCount, Timestamp ts) {
        jdbc.update(
                "UPDATE " + TABLE
                        + " SET " + COUNT_COL + " = ?, " + LAST_COL + " = ?"
                        + " WHERE " + KEY_COL + " = ?",
                newCount, ts, marketId);
    }
}
