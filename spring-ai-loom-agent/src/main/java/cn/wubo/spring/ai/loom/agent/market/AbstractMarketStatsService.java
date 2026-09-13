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
 *       (skill pulls, KB searches) call {@link #incrementStat(Object, String)}
 *       and the delta is added to an in-memory {@code BufferEntry} keyed by
 *       {@code (table, keyCol, key, cntCol)}. The scheduler flushes the buffer
 *       every 30s, drastically reducing write pressure on
 *       {@code market_skill_stats} / {@code loom_market_knowledge_stats} during
 *       chat bursts.</li>
 *   <li><b>Synchronous read</b> via {@link JdbcTemplate}:
 *       {@link #getStats(Object)} queries the row directly. Reads are
 *       infrequent (one per UI render) so no buffering is warranted —
 *       see the latest flushed state.</li>
 * </ol>
 *
 * <h2>Type parameter {@code K}</h2>
 * <p>Generic over the market-id type — {@code Long} for skill stats (PK is
 * {@code BIGINT}), {@code String} (UUID) for KB stats (PK is {@code VARCHAR(36)}
 * post-T1.1 schema migration). Subclasses pin the type and implement the
 * typed abstract helpers ({@link #ensureStatsRowExists(Object)},
 * {@link #bufferIncrement(Object)}, {@link #readStatsRow(Object)},
 * {@link #emptyStatsRow(Object)}, {@link #discardBuffer(Object)},
 * {@link #writeReset(Object, long, Timestamp)}) — those methods take the
 * concrete {@code K}, while the shared flow here operates on {@code K} as a
 * black box.</p>
 *
 * <h2>The "kind" parameter is documentation-only</h2>
 * Each subclass owns exactly ONE counter column (e.g. {@code pull_count} for
 * Skill, {@code search_count} for KB). The {@code kind} parameter on
 * {@link #incrementStat(Object, String)} is accepted for API symmetry with
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
 * @param <K> market-content id type — {@code Long} for Skill,
 *            {@code String} (UUID) for KB (post-T1.1 schema migration)
 */
public abstract class AbstractMarketStatsService<K> implements IMarketContentStatsService<K> {

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
     * Buffer a delta of +1 for {@code marketId} via {@link BatchedCounterService}.
     * Subclasses dispatch to the appropriate {@code increment(...)} overload
     * based on the concrete {@code K} type (Long / String).
     */
    protected abstract void bufferIncrement(K marketId);

    /**
     * H2 {@code MERGE INTO} lazy-upsert keyed on the PK column. When the row
     * exists, the {@code (keyCol)} column is "set" to its current value
     * (no-op) and the other columns are left untouched. When the row is
     * missing, all columns take their schema defaults
     * ({@code count=0}, {@code last=NULL}).
     */
    protected abstract void ensureStatsRowExists(K marketId);

    /** Read the existing stats row from the DB; throw {@link org.springframework.dao.EmptyResultDataAccessException} if no row. */
    protected abstract StatsRow<K> readStatsRow(K marketId);

    /** Return the "no row yet" sentinel for {@link #getStats(Object)} on empty reads. */
    protected abstract StatsRow<K> emptyStatsRow(K marketId);

    /** Discard any pending buffered delta for {@code marketId}. */
    protected abstract void discardBuffer(K marketId);

    /** Direct {@code UPDATE ... SET count = ?, last = ?} for the admin reset path. */
    protected abstract void writeReset(K marketId, long newCount, Timestamp ts);

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
    public void incrementStat(K marketId, String kind) {
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
        bufferIncrement(marketId);
    }

    /**
     * Read the current stats row. If no row exists yet, returns a zero
     * {@link StatsRow} with {@code lastAt=null} — the lazy-upsert path
     * guarantees a row will exist after the first {@link #incrementStat},
     * but reads before the first increment are valid (no row → no traffic).
     */
    @Override
    public StatsRow<K> getStats(K marketId) {
        if (marketId == null) {
            return emptyStatsRow(null);
        }
        try {
            return readStatsRow(marketId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return emptyStatsRow(marketId);
        }
    }

    /**
     * Reset the counter to a specific value (admin use). Default
     * implementation sets both the counter and the timestamp to the supplied
     * values; pass {@code newCount=0} and {@code newLastAt=null} for the
     * canonical "reset" semantics. If no row exists yet, the lazy-upsert
     * ensures one is created with the requested values.
     *
     * <p><b>Fix-up B2:</b> before the direct UPDATE, we discard any buffered
     * delta for this specific {@code (table, keyCol, key, cntCol)} tuple.
     * Otherwise an admin "reset" call could be silently overwritten 0–30s
     * later when the {@link BatchedCounterService#scheduledFlush()} drains
     * pending increments — the admin's request would appear to be a no-op
     * even though it succeeded. We use the single-key discard path so
     * unrelated counters in the same buffer keep their queued increments.</p>
     */
    @Override
    public void resetStats(K marketId, long newCount, LocalDateTime newLastAt) {
        if (marketId == null) {
            return;
        }
        // Drop pending buffered deltas for this exact tuple — the direct
        // UPDATE below will overwrite whatever the flush would have written,
        // so leaving the buffer would corrupt the admin's intent.
        discardBuffer(marketId);
        ensureStatsRowExists(marketId);
        Timestamp ts = (newLastAt == null) ? null : Timestamp.valueOf(newLastAt);
        writeReset(marketId, newCount, ts);
    }
}
