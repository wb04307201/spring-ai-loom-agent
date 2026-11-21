package cn.wubo.spring.ai.loom.agent.chat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SteeringInbox} 单测 —— 续跑 steering 收件箱的并发语义回归锁。
 *
 * <p>锁定的行为(spec 2026-11-21 A2 + 429 语义)：
 * <ul>
 *   <li>FIFO 顺序注入,drain 后 depth 归零；</li>
 *   <li>closing CAS 收口后 offer 一律拒绝(续跑窗口已关,不再接受"聊聊这个")；</li>
 *   <li>深度上限 20 —— 超限 offer 返回 false(路由层映射 429,防 spam)；</li>
 *   <li>并发 offer/drain 零丢失零重复(2×100 条,消息即用户输入,丢一条 = 吞用户的话)。</li>
 * </ul>
 */
class SteeringInboxTest {

    @Test
    void offer_drain_fifo() {
        SteeringInbox b = new SteeringInbox();
        assertThat(b.offer("m1", "a")).isTrue();
        assertThat(b.offer("m2", "b")).isTrue();
        assertThat(b.drain()).extracting(SteeringInbox.SteeringMessage::messageId).containsExactly("m1", "m2");
        assertThat(b.depth()).isZero();
    }

    @Test
    void offer_rejected_when_closing() {
        SteeringInbox b = new SteeringInbox();
        b.close();
        assertThat(b.offer("m1", "a")).isFalse();
        assertThat(b.isClosing()).isTrue();
    }

    @Test
    void offer_rejected_at_depth_20() {
        SteeringInbox b = new SteeringInbox();
        for (int i = 0; i < 20; i++) {
            assertThat(b.offer("m" + i, "x")).isTrue();
        }
        assertThat(b.offer("over", "x")).isFalse(); // 429 语义
    }

    @Test
    void closeAndRemaining_returns_undrained() {
        SteeringInbox b = new SteeringInbox();
        b.offer("m1", "a");
        assertThat(b.closeAndRemaining()).extracting(SteeringInbox.SteeringMessage::messageId).containsExactly("m1");
        assertThat(b.isClosing()).isTrue();
    }

    @Test
    void concurrent_offer_drain_no_loss() throws Exception {
        // 2 线程各 offer 100 条(深度满时按 429 语义退避重试同一条),并发 drain
        // 收集总数=200,无重复 messageId(A2)
        SteeringInbox b = new SteeringInbox();
        int perThread = 100;
        int threads = 2;
        ConcurrentLinkedQueue<String> collected = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean offersRunning = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);

        List<Future<?>> offerFutures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int threadId = t;
            offerFutures.add(pool.submit(() -> {
                awaitQuietly(start);
                for (int i = 0; i < perThread; i++) {
                    String id = "t" + threadId + "-m" + i;
                    // 队列被 drain 腾出空间后同一条重试成功 —— 重试不得产生重复入队
                    while (!b.offer(id, "x")) {
                        Thread.yield();
                    }
                }
            }));
        }
        Future<?> drainFuture = pool.submit(() -> {
            awaitQuietly(start);
            while (true) {
                for (SteeringInbox.SteeringMessage m : b.drain()) {
                    collected.add(m.messageId());
                }
                if (!offersRunning.get() && b.depth() == 0 && b.drain().isEmpty()) {
                    return;
                }
                sleepQuietly(1);
            }
        });

        start.countDown();
        for (Future<?> f : offerFutures) {
            f.get(15, TimeUnit.SECONDS); // 超时即说明 offer 被永久饿死
        }
        offersRunning.set(false);
        pool.shutdown();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS))
                .as("drain 线程应在 offer 全部结束且队列清空后退出").isTrue();
        drainFuture.get(1, TimeUnit.SECONDS); // 重抛 drain 线程异常

        assertThat(collected).hasSize(threads * perThread);
        Set<String> unique = new HashSet<>(collected);
        assertThat(unique).as("无重复 messageId(A2)").hasSize(threads * perThread);
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < perThread; i++) {
                assertThat(unique).contains("t" + t + "-m" + i);
            }
        }
        assertThat(b.depth()).isZero();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
