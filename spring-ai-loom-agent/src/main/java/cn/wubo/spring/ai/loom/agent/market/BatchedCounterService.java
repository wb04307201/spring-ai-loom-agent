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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用、table-agnostic 计数器批处理器(skill / KB 市场统计、聊天 usage 等)。
 * <p>
 * 热路径调用方(技能拉取、KB 搜索、内容浏览)在每次事件上调用
 * {@link #increment(String, String, Long, String, String)};本 service 把增量
 * 缓存在内存里,定时 flush 成一次 {@code UPDATE <table> SET <cntCol> = <cntCol>
 * + ?, <lastCol> = CURRENT_TIMESTAMP WHERE <keyCol> = ?} —— 把
 * {@code market_skill_stats} / {@code loom_market_knowledge_stats} 这种
 * 高频写入表的写压力大幅压低。
 * </p>
 *
 * <h2>设计要点</h2>
 * <ul>
 *   <li><b>Buffer</b>:{@link ConcurrentHashMap} key 是
 *       {@code "<table>:<keyCol>:<key>:<cntCol>"},同一行的并发
 *       {@code increment} 合并到一个 {@link AtomicLong} delta ——
 *       无丢更新、无 per-event 分配。</li>
 *   <li><b>Schedule</b>:独立的 {@link Scheduled @Scheduled} 方法
 *       ({@code fixedDelay = 30s}) 调用 {@link #flush()},便于测试绕过
 *       Spring scheduler 直接驱动。</li>
 *   <li><b>Shutdown drain</b>:{@link PreDestroy @PreDestroy} 收尾时再调一次
 *       {@link #flush()},保证容器关闭时在飞的增量不丢。</li>
 *   <li><b>Failure policy</b>:执行器失败 WARN 重试一次,二次仍失败直接丢弃
 *       —— 无 outbox 表,best-effort / eventually-consistent,统计本身不要求
 *       强一致(任务简报里已明确接受该 trade-off)。</li>
 *   <li><b>Identifier trust</b>:table / column 名直接拼 SQL,不做参数化
 *       ({@link JdbcTemplate} 只参数化值,不参数化标识符)。调用方都是
 *       loom-agent 内部固定 schema 路径,用户输入永远到不了这里。</li>
 *   <li><b>Dedicated discard</b>:{@link #discard(String, String, Long, String)}
 *       允许 {@code AbstractMarketStatsService#resetStats} 等 admin "重置"
 *       路径精准丢弃某个 (table, keyCol, key, cntCol) tuple 的在飞 delta,
 *       不影响其他行。</li>
 * </ul>
 *
 * <h2>协作者 contract</h2>
 * <ul>
 *   <li>{@code @EnableScheduling} 必须存在于某 configuration 类上,
 *       否则 {@link #scheduledFlush()} 的 30s 定时器不触发 —— 消费者项目
 *       只能依赖 {@link PreDestroy} / shutdown hook,丢数据窗口
 *       显著放大。</li>
 *   <li>当前 loom-agent 的
 *       {@code LoomAgentConfiguration.StorageConfiguration} 已
 *       自动声明 {@code @EnableScheduling}(M3+ T0.2 加入),开箱即用,
 *       消费者无需重复声明;自定义 {@code StorageConfiguration} 子类时
 *       请保留该注解或自行 {@code @Import}。</li>
 *   <li>{@link PreDestroy}:容器销毁时同步阻塞调用
 *       {@link #flush()},尽力把最后一窗增量写盘;若 JVM 被 SIGKILL
 *       或 OOM kill 则该次兜底失效,丢数不可避免。</li>
 * </ul>
 *
 * <h2>已知限制</h2>
 * <ul>
 *   <li><b>非 cluster-shared</b>:Buffer 是进程内 in-memory state,集群部署
 *       下每个 JVM 实例独立累计、各自 flush,不会跨节点合并 —— 这是
 *       best-effort 设计取舍,集群节点数变化不影响可用性。</li>
 *   <li><b>重启即丢</b>:重启后 buffer 为空,定时器与 {@code @PreDestroy}
 *       触发前不在内存中的增量不会"补 flush"。当前数据库累计行计数即
 *       重启时的基线。</li>
 *   <li><b>不参与事务</b>:flush 是独立的 {@code jdbcTemplate.update} 序列,
 *       不绑定业务事务;若业务事务回滚,统计增量不会被撤回(同样 best-effort)。</li>
 *   <li><b>drop 是 silent</b>:二次执行器失败的 batch 仅打 WARN 日志,不抛
 *       异常 —— 调用方与上游请求都看不到丢失。</li>
 * </ul>
 *
 * <h2>注册方式</h2>
 * <p>
 * 在 {@code LoomAgentConfiguration.StorageConfiguration} 注册为
 * {@code @Bean}(带 {@code @ConditionalOnMissingBean}),消费者可通过
 * 自定义实现替换(例如加上 Micrometer 指标或 outbox 表)。
 * </p>
 *
 * <h2>测试</h2>
 * <p>
 * 单元测试用 fake {@link FlushExecutor} 构造,无 Spring context / Flyway /
 * 真实 DB。参见 {@code spring-ai-loom-agent-test} 模块下的
 * {@code BatchedCounterServiceTest}。
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
        // Single compute() holds the bin lock for the WHOLE read-modify-write:
        // 1. If the entry exists, reuse it.
        // 2. Otherwise create a fresh one.
        // 3. AtomicLong.incrementAndGet on its delta.
        // 4. Return the entry so it stays in the buffer.
        //
        // Why NOT computeIfAbsent + incrementAndGet? The two-step version
        // has a race window: computeIfAbsent releases the bin lock after
        // returning the entry, so a concurrent flush's compute(K) can
        // detach the entry BEFORE our incrementAndGet lands. The
        // increment would then update a BufferEntry that's no longer in
        // the buffer — the delta is silently dropped. Combining into one
        // compute() makes the create-or-reuse + increment atomic with
        // respect to drainBuffer's detach-and-snapshot.
        buffer.compute(bk, (k, v) -> {
            BufferEntry entry = (v != null) ? v : new BufferEntry(lastCol);
            entry.delta.incrementAndGet();
            return entry;
        });
    }

    /**
     * Discard the buffered delta for a specific {@code (table, keyCol, key,
     * cntCol)} tuple without flushing it to the DB. Used by
     * {@link AbstractMarketStatsService#resetStats(Long, long, java.time.LocalDateTime)}
     * so an admin "reset" wipes both the persisted count and any in-flight
     * increments that haven't been flushed yet — without nuking other rows
     * in the same buffer.
     * <p>
     * Concurrent-safety mirrors {@link #increment}: a single {@code compute()}
     * holds the bin lock for the whole detach, so a concurrent
     * {@code increment()} on the same key blocks until our lambda returns
     * {@code null} (detach succeeds) or returns the original entry (nothing
     * to discard). Either way, the discard is atomic with respect to
     * concurrent increments on the same key.
     * </p>
     *
     * @return {@code true} if a buffered delta was discarded; {@code false}
     *         if the key was not in the buffer (already flushed, never
     *         incremented, or different cntCol).
     */
    public boolean discard(String table, String keyCol, Long key, String cntCol) {
        String bk = table + ":" + keyCol + ":" + key + ":" + cntCol;
        AtomicBoolean discarded = new AtomicBoolean(false);
        buffer.compute(bk, (k, v) -> {
            if (v == null) {
                return null;
            }
            discarded.set(true);
            return null; // detach: next increment on this key creates a fresh entry
        });
        return discarded.get();
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
     *
     * <p><b>Race fix:</b> naive "snapshot delta, then remove entry" loses
     * concurrent increments between the two reads — the entry gets removed
     * from the map but its post-snapshot delta is dropped on the floor.
     * We use {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)
     * compute()} which holds the bin lock for the key being drained, so any
     * concurrent {@code increment()} on the same key is blocked until our
     * lambda returns {@code null}, at which point the increment sees a
     * missing entry and creates a fresh one via {@code computeIfAbsent}.
     * The atomic read-modify-write is what guarantees no delta is lost.</p>
     */
    private List<Update> drainBuffer() {
        List<Update> updates = new ArrayList<>();
        // Snapshot the current key set — keys that appear after this point
        // are NEW entries created by concurrent increment() calls; we leave
        // them in the buffer for the next flush.
        for (String key : new ArrayList<>(buffer.keySet())) {
            // Atomic detach-and-drain: compute() holds the bin lock for THIS
            // key, so any concurrent increment() against the same key blocks
            // until our lambda returns null, then creates a fresh entry via
            // computeIfAbsent — never mixing its delta into ours.
            buffer.compute(key, (k, v) -> {
                if (v == null) {
                    // Another drain (or expiry) got here first — nothing to do.
                    return null;
                }
                String[] parts = k.split(":", 4);
                // parts = [table, keyCol, key, cntCol]; lastCol lives in the entry.
                // Atomic read of delta is safe here: the bin lock is held,
                // so no concurrent increment() can land between our read and
                // our return-null (which detaches the entry).
                updates.add(new Update(
                        parts[0],
                        parts[1],
                        Long.parseLong(parts[2]),
                        parts[3],
                        v.delta.get(),
                        v.lastCol
                ));
                return null; // detach: the next increment will computeIfAbsent a fresh entry
            });
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

    /**
     * Package-private test hook: returns the number of distinct buffered
     * entries. Lets tests assert that the buffer is fully drained after a
     * flush. Not part of the public API — do not call from production code.
     */
    int bufferSizeForTest() {
        return buffer.size();
    }
}
