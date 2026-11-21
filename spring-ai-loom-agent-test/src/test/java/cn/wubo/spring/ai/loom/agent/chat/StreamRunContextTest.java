package cn.wubo.spring.ai.loom.agent.chat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StreamRunContext} 单测 —— 每轮流上下文的基础语义回归锁。
 *
 * <p>锁定行为：reasoning 缓冲 drain 即清空、空缓冲不落库(A1：无思考不写行)、
 * 迭代序号从 0 起、活跃计时排除暂停区间(D-3)、perIterationMode 默认 false
 * (子任务/legacy after() 据此单写)。
 */
class StreamRunContextTest {

    @Test
    void reasoning_drain_resets_buffer() {
        List<String> flushed = new ArrayList<>();
        StreamRunContext c = ctx(flushed::add);
        c.appendReasoning("ab");
        c.appendReasoning("cd");
        assertThat(c.drainReasoning()).isEqualTo("abcd");
        assertThat(c.drainReasoning()).isEmpty();
    }

    @Test
    void flushReasoning_skips_when_empty() {
        List<String> flushed = new ArrayList<>();
        StreamRunContext c = ctx(flushed::add);
        c.flushReasoning();
        assertThat(flushed).isEmpty(); // 空缓冲不落库(A1：无思考不写行)
    }

    @Test
    void iteration_counter_starts_zero_increments() {
        StreamRunContext c = ctx(s -> {});
        assertThat(c.nextIteration()).isZero();
        assertThat(c.nextIteration()).isEqualTo(1);
    }

    @Test
    void active_clock_excludes_paused() throws Exception {
        StreamRunContext c = ctx(s -> {});
        c.pauseClock();
        Thread.sleep(80); // 模拟人等待 —— 暂停区间必须不计入活跃时长(D-3)
        c.resumeClock();
        assertThat(c.activeElapsedMs()).isLessThan(50);
    }

    @Test
    void perIterationMode_default_false() {
        assertThat(ctx(s -> {}).perIterationMode()).isFalse(); // 子任务/legacy after() 据此单写
    }

    @Test
    void markPerIterationMode_flips_to_true() {
        StreamRunContext c = ctx(s -> {});
        c.markPerIterationMode();
        assertThat(c.perIterationMode()).isTrue();
    }

    @Test
    void appendReasoning_ignores_null_delta() {
        StreamRunContext c = ctx(s -> {});
        c.appendReasoning(null); // 非思考帧的 reasoning_content 为 null,不得 NPE 打断主流
        c.appendReasoning("x");
        assertThat(c.drainReasoning()).isEqualTo("x");
    }

    @Test
    void flushReasoning_consumes_buffer_when_nonempty() {
        List<String> flushed = new ArrayList<>();
        StreamRunContext c = ctx(flushed::add);
        c.appendReasoning("thinking");
        c.flushReasoning();
        assertThat(flushed).containsExactly("thinking");
        assertThat(c.drainReasoning()).isEmpty(); // flush 即 drain,缓冲不留残留
    }

    @Test
    void emitAck_and_emitIteration_forward_to_emitters() {
        List<String> acks = new ArrayList<>();
        List<Integer> iterations = new ArrayList<>();
        StreamRunContext c = new StreamRunContext(new SteeringInbox(), noopMemory(), s -> {
        }, acks::add, iterations::add, () -> false);
        c.emitAck("m1");
        c.emitIteration(3);
        assertThat(acks).containsExactly("m1");
        assertThat(iterations).containsExactly(3);
    }

    @Test
    void isStopped_reflects_stopSignal() {
        AtomicBoolean stopped = new AtomicBoolean(false);
        StreamRunContext c = new StreamRunContext(new SteeringInbox(), noopMemory(), s -> {
        }, s -> {
        }, i -> {
        }, stopped::get);
        assertThat(c.isStopped()).isFalse();
        stopped.set(true);
        assertThat(c.isStopped()).isTrue();
    }

    @Test
    void accessors_expose_inbox_and_memory() {
        SteeringInbox inbox = new SteeringInbox();
        StreamRunContext c = new StreamRunContext(inbox, noopMemory(), s -> {
        }, s -> {
        }, i -> {
        }, () -> false);
        assertThat(c.inbox()).isSameAs(inbox);
        assertThat(c.memory()).isNotNull();
    }

    /** 测试专用构造：memory/ack/iteration no-op,stopSignal 恒 false,reasoningFlush 为被测消费。 */
    private static StreamRunContext ctx(Consumer<String> reasoningFlush) {
        return new StreamRunContext(new SteeringInbox(), noopMemory(), reasoningFlush, s -> {
        }, i -> {
        }, () -> false);
    }

    private static MemoryWriter noopMemory() {
        return new MemoryWriter() {
            @Override
            public void writeUser(org.springframework.ai.chat.messages.Message m) {
            }

            @Override
            public void writeAssistant(org.springframework.ai.chat.messages.Message m) {
            }
        };
    }
}
