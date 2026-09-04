package cn.wubo.spring.ai.loom.agent.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Template base for content-level market stats. Encapsulates the
 * "increment on a single counter column + read the current row back" pattern
 * for marketplace content (Skill pulls, KB searches, content views, ...).
 *
 * <h2>Two responsibilities</h2>
 * <ol>
 *   <li><b>Buffered write</b> via {@link BatchedCounterService}: hot-path callers
 *       (skill pulls, KB searches) call {@link #incrementStat(Long, String)}
 *       and the delta is added to an in-memory {@code BufferEntry} keyed by
 *       {@code (table, keyCol, key, cntCol)}. The scheduler flushes the buffer
 *       every 30s, drastically reducing write pressure on
 *       {@code market_skill_stats} / {@code loom_market_knowledge_stats} during
 *       chat bursts.</li>
 *   <li><b>Synchronous read</b> via {@link JdbcTemplate}: {@link #getStats(Long)}
 *       queries the row directly. Reads are infrequent (one per UI render) so
 *       no buffering is warranted — see the latest flushed state.</li>
 * </ol>
 *
 * <h2>The "kind" parameter is documentation-only</h2>
 * Each subclass owns exactly ONE counter column (e.g. {@code pull_count} for
 * Skill, {@code search_count} for KB). The {@code kind} parameter on
 * {@link #incrementStat(Long, String)} is accepted for API symmetry with
 * {@link IMarketContentStatsService} but ignored by the implementation —
 * subclass templates pin the column. Callers should pass the canonical
 * constant ({@code "PULL"} / {@code "SEARCH"}) for clarity in logs.
 *
 * <h2>Stats row creation: lazy upsert via {@code MERGE INTO}</h2>
 * {@link BatchedCounterService#increment} flushes an
 * {@code UPDATE <table> SET <cnt> = <cnt> + ? WHERE <key> = ?} —
 * if the row doesn't exist, the UPDATE matches zero rows and the buffered
 * delta is silently lost. To make the counter self-healing, we run a small
 * {@code MERGE INTO <table> (<keyCol>) KEY(<keyCol>) VALUES (?)} on every
 * increment to guarantee the row exists before the buffered flush fires.
 *
 * <p>Why {@code MERGE INTO} instead of pre-creating rows at
 * {@code market_skill / loom_market_knowledge} creation time? Lazy upsert is
 * simpler (no coupling between market-content writes and stats writes),
 * self-healing (a manually-deleted row gets re-created on next increment),
 * and the per-increment cost is one tiny UPSERT — well below the cost of the
 * 30s flush the buffered counter eventually drives. {@code MERGE INTO ... KEY}
 * is H2-compatible (also works on PostgreSQL/MySQL with dialect tweaks in
 * the future), unlike MySQL-specific
 * {@code INSERT ... ON DUPLICATE KEY UPDATE}.</p>
 *
 * <p>Subclasses can override {@link #ensureStatsRowExists(Long)} if a future
 * schema uses a composite PK or wants a non-keyed upsert — the default
 * implementation is sufficient for {@code market_skill_stats} (PK on
 * {@code market_skill_id}) and {@code loom_market_knowledge_stats} (PK on
 * {@code market_id}).</p>
 *
 * @param <M> market-content id type — Long for Skill, Long for KB (despite
 *            {@code loom_market_knowledge.id} being VARCHAR(36), the stats
 *            table uses BIGINT — see schema).
 */
public abstract class AbstractMarketStatsService<M> implements IMarketContentStatsService {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final JdbcTemplate jdbc;
    protected final BatchedCounterService batchedCounterService;

    protected AbstractMarketStatsService(JdbcTemplate jdbc, BatchedCounterService batchedCounterService) {
        this.jdbc = jdbc;
        this.batchedCounterService = batchedCounterService;
    }

    /** Stats table name — e.g. {@code market_skill_stats}. */
    protected abstract String tableName();

    /** PK column on the stats table — e.g. {@code market_skill_id} / {@code market_id}. */
    protected abstract String keyCol();

    /** Counter column being incremented — e.g. {@code pull_count} / {@code search_count}. */
    protected abstract String countCol();

    /** Timestamp column updated on every increment — e.g. {@code last_pulled_at} / {@code last_searched_at}. */
    protected abstract String lastCol();

    /**
     * Increment the counter for {@code marketId}. The {@code kind} parameter is
     * documentation-only — the column is hard-coded by the subclass.
     * <p>
     * Side effects:
     * <ul>
     *   <li>Synchronously ensures a stats row exists (H2 {@code MERGE INTO}).</li>
     *   <li>Buffers a delta of +1 in {@link BatchedCounterService}; the actual
     *       UPDATE is flushed every 30s (or on shutdown).</li>
     * </ul>
     */
    @Override
    public void incrementStat(Long marketId, String kind) {
        if (marketId == null) {
            return;
        }
        try {
            ensureStatsRowExists(marketId);
        } catch (Exception e) {
            // Don't propagate — stats are best-effort and the brief explicitly
            // accepts eventual consistency. Log and continue; the buffered
            // increment below may still succeed if the row gets created by a
            // concurrent path or the next flush attempt.
            log.warn("{}: ensureStatsRowExists failed for {} id={} ({}); buffered increment will be dropped if MERGE row missing at flush time",
                    getClass().getSimpleName(), tableName(), marketId, e.getMessage());
        }
        // Document the kind for grep-ability; column is hard-coded by subclass.
        log.debug("{}: buffer +1 kind={} {}={} (col={})",
                getClass().getSimpleName(), kind, tableName(), marketId, countCol());
        batchedCounterService.increment(tableName(), keyCol(), marketId, countCol(), lastCol());
    }

    /**
     * Read the current stats row. If no row exists yet, returns a zero
     * {@link StatsRow} with {@code lastAt=null} — the lazy-upsert path
     * guarantees a row will exist after the first {@link #incrementStat},
     * but reads before the first increment are valid (no row → no traffic).
     */
    @Override
    public StatsRow getStats(Long marketId) {
        if (marketId == null) {
            return new StatsRow(null, 0L, null);
        }
        try {
            return jdbc.queryForObject(
                    "SELECT " + keyCol() + ", " + countCol() + ", " + lastCol()
                            + " FROM " + tableName() + " WHERE " + keyCol() + " = ?",
                    (rs, n) -> {
                        Timestamp ts = rs.getTimestamp(lastCol());
                        LocalDateTime lastAt = (ts == null) ? null : ts.toLocalDateTime();
                        long count = rs.getLong(countCol());
                        return new StatsRow(rs.getLong(keyCol()), count, lastAt);
                    },
                    marketId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return new StatsRow(marketId, 0L, null);
        }
    }

    /**
     * Reset the counter to a specific value (admin use). Default
     * implementation sets both the counter and the timestamp to the supplied
     * values; pass {@code newCount=0} and {@code newLastAt=null} for the
     * canonical "reset" semantics. If no row exists yet, the lazy-upsert
     * ensures one is created with the requested values.
     */
    public void resetStats(Long marketId, long newCount, LocalDateTime newLastAt) {
        if (marketId == null) {
            return;
        }
        ensureStatsRowExists(marketId);
        Timestamp ts = (newLastAt == null) ? null : Timestamp.valueOf(newLastAt);
        jdbc.update(
                "UPDATE " + tableName()
                        + " SET " + countCol() + " = ?, " + lastCol() + " = ?"
                        + " WHERE " + keyCol() + " = ?",
                newCount, ts, marketId);
    }

    /**
     * Default upsert — H2 {@code MERGE INTO} keyed on the PK column. When
     * the row exists, the {@code (keyCol)} column is "set" to its current
     * value (no-op) and the other columns are left untouched. When the row
     * is missing, all columns take their schema defaults
     * ({@code count=0}, {@code last=NULL}).
     */
    protected void ensureStatsRowExists(Long marketId) {
        // SQL identifier concatenation is intentional — these come from trusted
        // loom-agent code paths with fixed schemas; parameter binding only
        // covers the value. Mirrors the same rationale in
        // BatchedCounterService's JdbcFlushExecutor.
        String sql = "MERGE INTO " + tableName()
                + " (" + keyCol() + ")"
                + " KEY(" + keyCol() + ")"
                + " VALUES (?)";
        jdbc.update(sql, marketId);
    }
}