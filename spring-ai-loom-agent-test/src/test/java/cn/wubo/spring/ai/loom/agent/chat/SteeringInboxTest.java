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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SteeringInbox} 单测 —— 续跑 steering 收件箱的并发语义回归锁。
 *
 * <p>锁定的行为(spec 2026-11-21 A2 + 429 语义)：
 * <ul>
 *   <li>FIFO 顺序注入,drain 后 depth 归零；</li>
 *   <li>closing CAS 收口后 offer 一律拒绝(续跑窗口已关,不再接受"聊聊这个")；</li>
 *   <li>深度上限 20 —— 超限 offer 返回 false(路由层映射 429,防 spam)；</li>
 *   <li>并发 offer/drain 零丢失零重复(2×100 条,消息即用户输入,丢一条 = 吞用户的话)；</li>
 *   <li>A2 第三态排除：不得出现「offer 返回 true 但消息既不消费也不可见」——
 *       收口与在途 offer 的 stranding 窗口由入队后复查 + 身份撤单闭合
 *       ({@link #inflight_offer_not_stranded_after_close} + 压力守恒不变式)。</li>
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

    @Test
    void inflight_offer_not_stranded_after_close() throws Exception {
        // stranding 竞态窗口锁定(A2):offer 已过 closing.get() 检查、CAS 占额成功、尚未
        // queue.add 时被抢占,closeAndRemaining 置 closing + drain(此后读侧永久终止,模拟
        // "终帧已 emit,无人再读")之后该 offer 才恢复入队 —— 修复前它返回 true(=已排队)
        // 却永远读不到,恰是 A2 排除的第三态「既不消费也不可见」,契约说谎。
        // 窗口只有纳秒级(源码两条语句之间),纯靠 OS 抢占概率性复现实测不可靠(8 轮×30ms
        // churn 零命中)⇒ 用行为中性的包私有 seam beforeEnqueue(占额后、入队前回调,
        // 生产恒 no-op)把竞态线程确定性定格在窗口内。
        // 修后契约:恢复入队时复查 closing ⇒ 原子撤单返回 false(拒绝,与 429 同款
        // 「拒绝即可见处置」通道);撤单失败 ⇔ 已被 drain 取走 ⇒ 返回 true 且消息已被读。
        // 两分支都落 A2 允许态,零大锁。
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Boolean> offerResult = new AtomicReference<>();

        SteeringInbox b = new SteeringInbox() {
            @Override
            void beforeEnqueue(SteeringInbox.SteeringMessage msg) {
                if ("inflight".equals(msg.messageId())) {
                    arrived.countDown();   // 已定格在竞态窗口内
                    awaitQuietly(release); // 直到「收口 + 终帧」整体完成才被放行
                }
            }
        };

        // 对照:普通 offer 经同一 seam 不受影响(messageId 过滤)
        assertThat(b.offer("m0", "ok")).isTrue();
        assertThat(b.drain()).extracting(SteeringInbox.SteeringMessage::messageId).containsExactly("m0");

        Thread offerer = new Thread(() -> offerResult.set(b.offer("inflight", "x")));
        offerer.start();
        assertThat(arrived.await(5, TimeUnit.SECONDS))
                .as("offerer 应已过 closing 检查 + CAS 占额、停在入队前的 seam 上").isTrue();

        // 收口 + 终帧:closeAndRemaining 是读侧最后一次读取 —— inflight 尚未入队,残留读不到它
        assertThat(b.closeAndRemaining()).as("竞态消息还停在窗口内,收口残留应为空").isEmpty();

        release.countDown(); // 放行:offerer 恢复后把 inflight 落进已无人再读的队列
        offerer.join(5_000);

        assertThat(offerResult.get())
                .as("A2:不得出现「返回 true 但消息蒸发」第三态 —— 收口后入队必须撤单返回 false")
                .isFalse();
        // 修复前的可观察孤儿在此显式锁死(防「测试没碰到真路径」式假绿):
        assertThat(b.drain())
                .as("收口后队列不得有复活残留(修复前 inflight 正是孤儿)").isEmpty();
        assertThat(b.depth())
                .as("撤单须回滚 CAS 预约,深度归零(修复前滞留 1)").isZero();
    }

    @Test
    void stranding_stress_zero_evaporation() throws Exception {
        // seam 用例的补充压力锁:6 spammer + 2 drainer 真实抢占混跑收口,
        // 断言 accepted 计数 == read 计数(消息唯一入队唯一读走,蒸发 ⇔ 计数缺口)。
        // 概率性交错(纳秒级窗口,修复前实测未必命中,自证依据是上面的 seam 用例),
        // 但回归期锁死总量守恒不变式,防止未来改动引入新的收口丢消息路径。
        for (int trial = 0; trial < 8; trial++) {
            runStrandingTrial(trial);
        }
    }

    private void runStrandingTrial(int trial) throws Exception {
        SteeringInbox b = new SteeringInbox();
        LongAdder accepted = new LongAdder(); // offer 返回 true 的条数
        LongAdder read = new LongAdder();     // 被 drain/closeAndRemaining 实际读走的条数
        AtomicBoolean spam = new AtomicBoolean(true);
        AtomicBoolean draining = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int t = 0; t < 6; t++) {
                pool.submit(() -> {
                    while (spam.get()) {
                        // 零分配入参(Node/record 在窗口内才 new):非窗口路径压到最短 ⇒ 窗口占空比最大
                        if (b.offer("m", "x")) {
                            accepted.increment();
                        }
                    }
                });
            }
            for (int d = 0; d < 2; d++) {
                pool.submit(() -> {
                    while (draining.get()) {
                        read.add(b.drain().size()); // 忙排空:队列近空,spammer 恒走完整成功路径
                    }
                });
            }
            Thread.sleep(30); // churn:≥2 个时间片,尝试让线程被定格在「占额→入队」窗口内
            draining.set(false);
            Thread.sleep(2); // 在途 drain 收尾 —— 读侧就此终止(终帧已 emit)
            read.add(b.closeAndRemaining().size()); // 终止后唯一一次也是最后一次读取
            Thread.sleep(80); // 给被抢占的在途 offer 充分时间恢复
            spam.set(false);
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS))
                    .as("trial %d: 线程应在停旗后退出", trial).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(accepted.sum())
                .as("trial %d: offer 返回 true 的条数必须等于实际被读走条数,零蒸发(A2:返回 true = 已排队)")
                .isEqualTo(read.sum());
        assertThat(b.depth())
                .as("trial %d: 收口后深度归零,无滞留预约/残留", trial).isZero();
    }

    // ===== retract —— 用户主动撤回排队消息(2026-11-24 steering-retract spec §3/§6) =====

    @Test
    void retract_removes_queued_and_releases_depth() {
        SteeringInbox inbox = new SteeringInbox();
        assertThat(inbox.offer("m-1", "第一条")).isTrue();
        assertThat(inbox.offer("m-2", "第二条")).isTrue();
        assertThat(inbox.retract("m-1")).isTrue();
        assertThat(inbox.depth()).isEqualTo(1);
        assertThat(inbox.drain()).extracting(SteeringInbox.SteeringMessage::messageId)
                .containsExactly("m-2"); // FIFO 残余不受影响
    }

    @Test
    void retract_unknown_id_returns_false() {
        SteeringInbox empty = new SteeringInbox();
        assertThat(empty.retract("nope")).isFalse();
        assertThat(empty.depth()).isZero();

        SteeringInbox inbox = new SteeringInbox();
        assertThat(inbox.offer("m-1", "a")).isTrue();
        assertThat(inbox.offer("m-2", "b")).isTrue();
        assertThat(inbox.retract("m-99")).isFalse();
        assertThat(inbox.depth()).as("撤回未命中不得释放名额").isEqualTo(2);
        assertThat(inbox.drain()).extracting(SteeringInbox.SteeringMessage::messageId)
                .as("队列内容不受未命中撤回影响").containsExactly("m-1", "m-2");
    }

    @Test
    void retract_after_drain_returns_false() {
        SteeringInbox inbox = new SteeringInbox();
        assertThat(inbox.offer("m-1", "a")).isTrue();
        assertThat(inbox.drain()).hasSize(1);
        assertThat(inbox.retract("m-1"))
                .as("已被 drain 读走(G3:注入后不可撤回)→ false").isFalse();
        assertThat(inbox.depth()).isZero();
    }

    @Test
    void retract_loses_race_to_drain() {
        // 竞态定格(spec §6.1,仿 beforeEnqueue seam):retract 定位节点后、queue.remove 前,
        // drain 的 poll 抢先 —— ConcurrentLinkedQueue 保证每节点恰被移除一次,只有一个赢。
        // holder 数组把线程确定性定格在窗口内(纳秒级概率抢占实测不可复现,同
        // inflight_offer_not_stranded_after_close 的裁定)。
        final SteeringInbox[] holder = new SteeringInbox[1];
        AtomicReference<List<SteeringInbox.SteeringMessage>> drainedRef = new AtomicReference<>();
        SteeringInbox inbox = new SteeringInbox() {
            @Override
            void beforeRetractRemove(SteeringInbox.SteeringMessage msg) {
                drainedRef.set(holder[0].drain()); // 同步重入:poll 先取走本节点
            }
        };
        holder[0] = inbox;
        assertThat(inbox.offer("m-1", "第一条")).isTrue();

        assertThat(inbox.retract("m-1"))
                .as("drain 赢 poll ⇒ remove 失败 ⇒ 撤回被拒(消息已被读走,前端交给 ack 裁决)")
                .isFalse();
        assertThat(drainedRef.get()).extracting(SteeringInbox.SteeringMessage::messageId)
                .as("A2:消息被 drain 读走(已注入 = 可见处置),绝不静默吞").containsExactly("m-1");
        assertThat(inbox.depth()).as("深度由 poll 释放(poll XOR remove 恰一次)").isZero();
        assertThat(inbox.drain()).isEmpty();
    }

    @Test
    void retract_loses_race_to_terminate() {
        // 同上 seam,hook 内改调 terminateAndRemaining(stop/断连路径)→ terminate 赢,
        // 残留出现在 terminate 返回值中(NOT_INJECTED 终帧可见处置),撤回返 false。
        final SteeringInbox[] holder = new SteeringInbox[1];
        AtomicReference<List<SteeringInbox.SteeringMessage>> remainingRef = new AtomicReference<>();
        SteeringInbox inbox = new SteeringInbox() {
            @Override
            void beforeRetractRemove(SteeringInbox.SteeringMessage msg) {
                remainingRef.set(holder[0].terminateAndRemaining());
            }
        };
        holder[0] = inbox;
        assertThat(inbox.offer("m-1", "第一条")).isTrue();

        assertThat(inbox.retract("m-1"))
                .as("terminate 赢 ⇒ remove 失败 ⇒ 撤回被拒").isFalse();
        assertThat(remainingRef.get()).extracting(SteeringInbox.SteeringMessage::messageId)
                .as("A2:残留在 terminate 返回值中(路由层据此 emit NOT_INJECTED 终帧)").containsExactly("m-1");
        assertThat(inbox.depth()).isZero();
        assertThat(inbox.isTerminated()).isTrue();
    }

    @Test
    void retract_wins_race_then_drain_empty() {
        // 反向定格(无 seam 即确定性):撤回的 remove 先赢 ⇒ 消息绝不再进模型。
        SteeringInbox inbox = new SteeringInbox();
        assertThat(inbox.offer("m-1", "第一条")).isTrue();
        assertThat(inbox.retract("m-1")).isTrue();
        assertThat(inbox.drain()).as("撤回赢,消息绝不进模型").isEmpty();
        assertThat(inbox.depth()).isZero();
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
