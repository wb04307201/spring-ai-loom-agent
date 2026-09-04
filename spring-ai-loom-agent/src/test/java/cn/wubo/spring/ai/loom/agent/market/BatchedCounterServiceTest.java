package cn.wubo.spring.ai.loom.agent.market;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BatchedCounterService}. Uses a fake
 * {@link BatchedCounterService.FlushExecutor} so we can drive {@code flush()}
 * synchronously without booting Spring or Flyway — the whole point of the
 * batched counter is that the SQL side-effect is hidden behind an executor
 * interface.
 *
 * <p>Pure unit test, no {@code @SpringBootTest}; sits alongside the other
 * pure-Java market unit tests like {@link MarketContentStatusTest}.</p>
 */
class BatchedCounterServiceTest {

    /**
     * Per the brief's {@code Step 15.1}: two {@code increment}s against the same
     * (table, keyCol, key, cntCol) tuple must collapse into ONE buffered entry,
     * and a single {@code flush()} must yield ONE call to the executor with
     * delta == 2.
     */
    @Test
    void incrementsAreBufferedAndFlushed() {
        AtomicReference<List<BatchedCounterService.Update>> captured = new AtomicReference<>();
        BatchedCounterService svc = new BatchedCounterService(updates -> {
            captured.set(updates);
            return CompletableFuture.completedFuture(null);
        });

        svc.increment("market_skill_stats", "market_skill_id", 5L, "pull_count", "last_pulled_at");
        svc.increment("market_skill_stats", "market_skill_id", 5L, "pull_count", "last_pulled_at");
        svc.flush();

        List<BatchedCounterService.Update> updates = captured.get();
        assertNotNull(updates, "executor should have been invoked");
        assertEquals(1, updates.size(), "same (table,key,cnt) must dedup to one update");
        BatchedCounterService.Update u = updates.get(0);
        assertEquals("market_skill_stats", u.table());
        assertEquals("market_skill_id", u.keyCol());
        assertEquals(5L, u.key());
        assertEquals("pull_count", u.cntCol());
        assertEquals("last_pulled_at", u.lastCol());
        assertEquals(2L, u.delta());
    }

    /**
     * Two distinct keys must produce TWO independent buffered entries.
     */
    @Test
    void differentKeysProduceSeparateUpdates() {
        AtomicReference<List<BatchedCounterService.Update>> captured = new AtomicReference<>();
        BatchedCounterService svc = new BatchedCounterService(updates -> {
            captured.set(updates);
            return CompletableFuture.completedFuture(null);
        });

        svc.increment("market_skill_stats", "market_skill_id", 1L, "pull_count", "last_pulled_at");
        svc.increment("market_skill_stats", "market_skill_id", 2L, "pull_count", "last_pulled_at");
        svc.flush();

        List<BatchedCounterService.Update> updates = captured.get();
        assertNotNull(updates);
        assertEquals(2, updates.size(), "distinct keys must produce distinct updates");
        long keys = updates.stream().mapToLong(BatchedCounterService.Update::key).sum();
        assertEquals(3L, keys);
    }

    /**
     * Different count columns on the same key are distinct counters.
     */
    @Test
    void differentCountColumnsAreDistinct() {
        AtomicReference<List<BatchedCounterService.Update>> captured = new AtomicReference<>();
        BatchedCounterService svc = new BatchedCounterService(updates -> {
            captured.set(updates);
            return CompletableFuture.completedFuture(null);
        });

        svc.increment("market_skill_stats", "market_skill_id", 5L, "pull_count", "last_pulled_at");
        svc.increment("market_skill_stats", "market_skill_id", 5L, "view_count", "last_viewed_at");
        svc.flush();

        List<BatchedCounterService.Update> updates = captured.get();
        assertNotNull(updates);
        assertEquals(2, updates.size(), "different count columns must not dedup");
    }

    /**
     * Empty buffer → no executor call at all (avoid pointless work every 30s
     * when there's nothing to write).
     */
    @Test
    void emptyBufferSkipsExecutor() {
        AtomicInteger calls = new AtomicInteger();
        BatchedCounterService svc = new BatchedCounterService(updates -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });

        svc.flush();

        assertEquals(0, calls.get(), "empty buffer must not invoke executor");
    }

    /**
     * The buffer must be cleared AFTER capturing the updates, so a second
     * {@code flush()} does not re-send the same writes.
     */
    @Test
    void flushClearsBufferSoItDoesNotReplay() {
        AtomicInteger calls = new AtomicInteger();
        BatchedCounterService svc = new BatchedCounterService(updates -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });

        svc.increment("t", "id", 1L, "c", "l");
        svc.flush();
        svc.flush();

        assertEquals(1, calls.get(), "second flush on empty buffer must be a no-op");
    }

    /**
     * Failure semantics per the binding: log WARN, retry once, otherwise drop.
     * Verifies the executor is invoked exactly twice (initial + retry) and
     * the {@code flush()} call itself does not throw — the scheduler thread
     * must keep ticking even if the DB hiccups.
     */
    @Test
    void retryOnceOnFailureThenDrop() {
        AtomicInteger calls = new AtomicInteger();
        BatchedCounterService svc = new BatchedCounterService(updates -> {
            int n = calls.incrementAndGet();
            // Both attempts fail — driver should stop and drop, not loop forever.
            return CompletableFuture.failedFuture(new RuntimeException("simulated db hiccup #" + n));
        });

        svc.increment("t", "id", 1L, "c", "l");
        svc.flush(); // must NOT throw

        assertEquals(2, calls.get(), "must attempt exactly twice (initial + one retry)");
    }

    /**
     * If the retry succeeds, the executor is invoked twice total and {@code flush()}
     * returns normally.
     */
    @Test
    void retryOnceOnFailureSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<List<BatchedCounterService.Update>> captured = new AtomicReference<>();
        BatchedCounterService svc = new BatchedCounterService(updates -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return CompletableFuture.failedFuture(new RuntimeException("transient"));
            }
            captured.set(updates);
            return CompletableFuture.completedFuture(null);
        });

        svc.increment("t", "id", 7L, "c", "l");
        svc.flush();

        assertEquals(2, calls.get());
        assertNotNull(captured.get(), "retry must have delivered the updates");
        assertEquals(1, captured.get().size());
        assertEquals(7L, captured.get().get(0).key());
    }

    /**
     * Concurrency check: many threads incrementing the same key must produce a
     * single buffered entry with delta equal to the total number of increments.
     * This guards against lost updates via {@code computeIfAbsent} +
     * {@code AtomicLong.incrementAndGet}.
     */
    @Test
    void concurrentIncrementsDedupSafely() throws Exception {
        AtomicReference<List<BatchedCounterService.Update>> captured = new AtomicReference<>();
        BatchedCounterService svc = new BatchedCounterService(updates -> {
            captured.set(updates);
            return CompletableFuture.completedFuture(null);
        });

        int threads = 16;
        int perThread = 250;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        svc.increment("t", "id", 42L, "c", "l");
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "batch-counter-test-" + i).start();
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "workers must finish");

        svc.flush();

        List<BatchedCounterService.Update> updates = captured.get();
        assertNotNull(updates);
        assertEquals(1, updates.size(), "concurrent increments on same key must dedup");
        assertEquals((long) threads * perThread, updates.get(0).delta(),
                "delta must equal total increments — no lost updates");
    }
}
