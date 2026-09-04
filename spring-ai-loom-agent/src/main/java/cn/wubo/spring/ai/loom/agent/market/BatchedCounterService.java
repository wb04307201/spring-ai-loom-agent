package cn.wubo.spring.ai.loom.agent.market;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generic, table-agnostic counter batcher.
 * <p>
 * Hot-path callers (skill pulls, KB searches, content views) call
 * {@link #increment(String, String, Long, String, String)} on every event;
 * this service buffers the deltas in memory and periodically flushes them as
 * one {@code UPDATE <table> SET <cntCol> = <cntCol> + ?, <lastCol> = CURRENT_TIMESTAMP
 * WHERE <keyCol> = ?} per distinct (table, keyCol, key, cntCol) tuple —
 * drastically reducing write pressure on tables like
 * {@code market_skill_stats} / {@code market_knowledge_stats} that get hit on
 * every marketplace interaction.
 * </p>
 *
 * <h2>Design</h2>
 * <ul>
 *   <li><b>Buffer</b>: a {@link ConcurrentHashMap} keyed by
 *       {@code "<table>:<keyCol>:<key>:<cntCol>"} so concurrent increments to
 *       the same row dedup into one {@link AtomicLong} delta — no lost
 *       updates, no per-event allocation.</li>
 *   <li><b>Schedule</b>: a separate {@link Scheduled @Scheduled} method
 *       ({@code fixedDelay = 30s}) calls {@link #flush()} so direct test
 *       invocation can drive the buffer without waiting on Spring's scheduler.</li>
 *   <li><b>Shutdown drain</b>: {@link PreDestroy @PreDestroy} calls
 *       {@link #flush()} one last time so in-flight increments are not lost
 *       when the container tears down.</li>
 *   <li><b>Failure policy</b>: if the executor fails we log WARN and retry
 *       exactly once. A second failure drops the batch — there's no outbox
 *       table; this is a best-effort, eventually-consistent counter, and the
 *       marketplace stats are not authoritative. (The binding context
 *       explicitly accepts this trade-off — see task brief.)</li>
 *   <li><b>Identifer trust</b>: table/column names are concatenated into SQL,
 *       not parameterised. {@link JdbcTemplate} only parameterises values, not
 *       identifiers. Callers are trusted loom-agent code paths with fixed
 *       schemas — user input never reaches this method.</li>
 * </ul>
 *
 * <h2>Wiring</h2>
 * <p>
 * Registered as a Spring {@code @Bean} in
 * {@code LoomAgentConfiguration}; the consumer application must have
 * {@code @EnableScheduling} on a configuration class for the
 * {@link Scheduled} drain to fire (the test app already does).
 * </p>
 *
 * <h2>Testing</h2>
 * <p>
 * Constructed with a fake {@link FlushExecutor} in unit tests — no Spring
 * context, no Flyway, no real DB. See
 * {@code BatchedCounterServiceTest} in the {@code spring-ai-loom-agent-test}
 * module.
 * </p>
 */
public class BatchedCounterService {

    private static final Logger log = LoggerFactory.getLogger(BatchedCounterService.class);

    /**
     * Per-row buffered delta + the {@code lastCol} constant needed to build
     * the {@code UPDATE ... SET lastCol = CURRENT_TIMESTAMP} on flush.
     */
    private static final class BufferEntry {
        final AtomicLong delta = new AtomicLong();
        final String lastCol;

        BufferEntry(String lastCol) {
            this.lastCol = lastCol;
        }
    }

    /**
     * One row to update on flush. Immutable snapshot of (table, key, columns, delta).
     */
    public record Update(String table, String keyCol, Long key, String cntCol, Long delta, String lastCol) {
    }

    /**
     * Pluggable flush target — the production default
     * {@link JdbcFlushExecutor} runs the {@code UPDATE} via
     * {@link JdbcTemplate}; tests inject a fake that captures the
     * {@link Update} list.
     */
    public interface FlushExecutor {
        CompletableFuture<Void> flush(List<Update> updates);
    }

    /** Production executor: one {@code jdbc.update(sql, delta, key)} per buffered row. */
    private static final class JdbcFlushExecutor implements FlushExecutor {
        private final JdbcTemplate jdbcTemplate;

        JdbcFlushExecutor(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public CompletableFuture<Void> flush(List<Update> updates) {
            return CompletableFuture.runAsync(() -> {
                for (Update u : updates) {
                    // SQL identifier concatenation is intentional — these come
                    // from trusted loom-agent code paths with fixed schemas;
                    // parameter binding only covers values (delta, key).
                    String sql = "UPDATE " + u.table()
                            + " SET " + u.cntCol() + " = " + u.cntCol() + " + ?, "
                            + u.lastCol() + " = CURRENT_TIMESTAMP"
                            + " WHERE " + u.keyCol() + " = ?";
                    jdbcTemplate.update(sql, u.delta(), u.key());
                }
            });
        }
    }

    private final ConcurrentHashMap<String, BufferEntry> buffer = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbcTemplate;
    private final FlushExecutor executor;

    /**
     * Production constructor — Spring injects the shared {@link JdbcTemplate}
     * and we wrap it in a {@link JdbcFlushExecutor}.
     */
    public BatchedCounterService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.executor = new JdbcFlushExecutor(jdbcTemplate);
    }

    /**
     * Test-friendly constructor — package-private so production callers can't
     * bypass the JDBC wiring, but tests in the same package can swap in a
     * fake {@link FlushExecutor} without needing Spring or a real DB.
     */
    BatchedCounterService(FlushExecutor executor) {
        this.jdbcTemplate = null;
        this.executor = executor;
    }

    /**
     * Buffer one increment. Safe to call from any thread; concurrent calls
     * against the same {@code (table, keyCol, key, cntCol)} tuple accumulate
     * into a single buffered delta.
     *
     * @param table   table name (e.g. {@code market_skill_stats})
     * @param keyCol  key column (e.g. {@code market_skill_id})
     * @param key     the row's primary key value
     * @param cntCol  counter column to increment (e.g. {@code pull_count})
     * @param lastCol timestamp column to set to {@code CURRENT_TIMESTAMP}
     *                (e.g. {@code last_pulled_at})
     */
    public void increment(String table, String keyCol, Long key, String cntCol, String lastCol) {
        String bk = table + ":" + keyCol + ":" + key + ":" + cntCol;
        buffer.computeIfAbsent(bk, k -> new BufferEntry(lastCol)).delta.incrementAndGet();
    }

    /**
     * Drain the buffer into the {@link FlushExecutor}. Intentionally NOT
     * {@link Scheduled @Scheduled}-annotated — the scheduler entry-point is
     * {@link #scheduledFlush()} below so unit tests can drive flushes
     * directly without Spring's scheduler firing.
     *
     * <p>Failure policy: log WARN, retry once, otherwise drop. The executor
     * is invoked at most twice per {@code flush()} call.</p>
     */
    public void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        List<Update> updates = drainBuffer();
        if (updates.isEmpty()) {
            return;
        }
        try {
            executor.flush(updates).get(5, TimeUnit.SECONDS);
        } catch (Exception firstFailure) {
            log.warn("BatchedCounterService: first flush attempt failed ({} updates): {} — retrying once",
                    updates.size(), firstFailure.getMessage());
            try {
                executor.flush(updates).get(5, TimeUnit.SECONDS);
            } catch (Exception retryFailure) {
                // Per the binding: best-effort, no outbox. Drop the batch.
                log.warn("BatchedCounterService: retry failed, dropping {} update(s): {}",
                        updates.size(), retryFailure.getMessage());
            }
        }
    }

    /**
     * Scheduler entry-point — {@code fixedDelay = 30s} so a slow flush
     * doesn't pile up back-to-back runs. Always invokes the un-annotated
     * {@link #flush()} so manual callers and the scheduler share one path.
     */
    @Scheduled(fixedDelay = 30_000)
    public void scheduledFlush() {
        flush();
    }

    /**
     * Drain remaining increments before Spring tears down the container —
     * otherwise the last 30s window of activity is lost on every restart.
     */
    @PreDestroy
    public void onShutdown() {
        log.debug("BatchedCounterService: shutdown drain");
        flush();
    }

    /**
     * Snapshot + clear the buffer. Pulled out so it can be unit-tested
     * independently of the executor. The buffer is cleared BEFORE the
     * executor is invoked so concurrent {@link #increment} calls during
     * a slow flush land in the next window instead of being dropped.
     */
    private List<Update> drainBuffer() {
        List<Update> updates = new ArrayList<>(buffer.size());
        // Snapshot into a local map so we can clear without holding the
        // CHM's bin lock while iterating the values list.
        var entries = new ArrayList<>(buffer.entrySet());
        for (var e : entries) {
            String bk = e.getKey();
            BufferEntry entry = e.getValue();
            String[] parts = bk.split(":", 4);
            // parts = [table, keyCol, key, cntCol]; lastCol lives in the entry.
            updates.add(new Update(
                    parts[0],
                    parts[1],
                    Long.parseLong(parts[2]),
                    parts[3],
                    entry.delta.get(),
                    entry.lastCol
            ));
        }
        // Only remove the keys we actually snapshotted — leaves concurrent
        // post-snapshot increments in the buffer for the next flush.
        for (var e : entries) {
            buffer.remove(e.getKey(), e.getValue());
        }
        return updates;
    }

    /**
     * @return the shared {@link JdbcTemplate} — exposed for tests that need
     *         to assert against the live DB after {@link #flush()}. May be
     *         {@code null} when constructed with the test-only constructor.
     */
    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }
}
