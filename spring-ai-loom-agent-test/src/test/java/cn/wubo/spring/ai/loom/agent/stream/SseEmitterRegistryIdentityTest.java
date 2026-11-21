package cn.wubo.spring.ai.loom.agent.stream;

import cn.wubo.spring.ai.loom.agent.stream.SseEmitterRegistry.Entry;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Task 3 回归锁：SseEmitterRegistry 的 Entry 身份感知 autoCleanup（#6 竞态）、
 * Entry 的 SteeringInbox 载体、以及 steer 入口的拒绝语义。
 *
 * <p>注：register(...) 内部 new Entry（含新 inbox），测试用 {@link #registerFresh}
 * 注册后从 {@code get} 取回<b>在册</b> Entry 引用 —— 只有它是 steer/stop/autoCleanup
 * 身份判定真正操作的实例。
 */
class SseEmitterRegistryIdentityTest {

    /** 注册一条 mock emitter/disposable + no-op onStop 的流，返回在册 Entry。 */
    private static Entry registerFresh(SseEmitterRegistry r) {
        r.register("u", "c", mock(SseEmitter.class), mock(Disposable.class), () -> {
        });
        return r.get("u", "c");
    }

    @Test
    void autoCleanup_does_not_remove_newer_entry() {
        SseEmitterRegistry r = new SseEmitterRegistry();
        Entry old = registerFresh(r);   // 旧流
        r.register("u", "c", mock(SseEmitter.class), mock(Disposable.class), () -> {
        });                             // 覆盖(新 run 注册)
        Entry fresh = r.get("u", "c");
        r.autoCleanup("u", "c", old);   // 旧流 onComplete
        assertThat(r.get("u", "c")).isSameAs(fresh); // 新 run Entry 不被误删(#6)
    }

    @Test
    void autoCleanup_removes_only_the_matching_entry() {
        SseEmitterRegistry r = new SseEmitterRegistry();
        Entry e = registerFresh(r);
        r.autoCleanup("u", "c", e);     // 身份命中 → 真删
        assertThat(r.get("u", "c")).isNull();
        assertThat(r.activeCount()).isZero();
    }

    @Test
    void autoCleanup_uses_reference_identity_not_record_value_equality() {
        // 变异哨兵：Entry 是 record,equals 为值相等。构造一个与在册 Entry <b>值相等但引用不同</b>
        // 的副本(五分量全同,含同一 inbox 引用)—— 若实现误用 CHM.remove(c, expected)(值 equals),
        // 副本会"命中"并误删在册 Entry,本用例即红。正确实现只认引用同一对象。
        SseEmitterRegistry r = new SseEmitterRegistry();
        Entry e = registerFresh(r);
        Entry valueTwin = new Entry(e.emitter(), e.disposable(), e.startMs(), e.onStop(), e.inbox());
        assertThat(valueTwin).isEqualTo(e);   // 前提：确实值相等
        assertThat(valueTwin).isNotSameAs(e); // 但引用不同
        r.autoCleanup("u", "c", valueTwin);
        assertThat(r.get("u", "c")).isSameAs(e); // 在册 Entry 不能被值相等的副本顶掉
    }

    @Test
    void autoCleanup_two_arg_still_removes_unconditionally() {
        // A1 回归：现有 SSE lambda 仍走 2-arg（无条件），Task 6/7 才切 3-arg
        SseEmitterRegistry r = new SseEmitterRegistry();
        registerFresh(r);
        Entry other = registerFresh(r); // 覆盖
        r.autoCleanup("u", "c");
        assertThat(r.get("u", "c")).isNull();
        assertThat(other.inbox()).isNotNull(); // Entry 携带 inbox(兼容面不破坏)
    }

    @Test
    void steer_offers_to_active_inbox() {
        SseEmitterRegistry r = new SseEmitterRegistry();
        Entry e = registerFresh(r);
        assertThat(r.steer("u", "c", "m1", "hi")).isTrue();
        assertThat(e.inbox().depth()).isEqualTo(1);
        assertThat(e.inbox().drain()).singleElement()
                .satisfies(m -> {
                    assertThat(m.messageId()).isEqualTo("m1");
                    assertThat(m.text()).isEqualTo("hi");
                });
    }

    @Test
    void steer_false_when_no_run() {
        assertThat(new SseEmitterRegistry().steer("u", "c", "m1", "hi")).isFalse();
    }

    @Test
    void steer_false_after_stop() {
        SseEmitterRegistry r = new SseEmitterRegistry();
        Entry e = registerFresh(r);
        assertThat(r.stop("u", "c")).isTrue();
        // Entry 已从册移除
        assertThat(r.steer("u", "c", "m1", "hi")).isFalse();
        // stop 已关闭在册 Entry 的 inbox（在途 steer 的 offer 一律拒绝）
        assertThat(e.inbox().isClosing()).isTrue();
        assertThat(e.inbox().offer("m2", "late")).isFalse();
        assertThat(e.inbox().depth()).isZero();
    }

    @Test
    void steer_false_when_inbox_closing_while_entry_still_registered() {
        // steer 的 closing 分支：Entry 在册但 inbox 已收口 → false（不是 offer 进队列）
        SseEmitterRegistry r = new SseEmitterRegistry();
        Entry e = registerFresh(r);
        e.inbox().close();
        assertThat(r.steer("u", "c", "m1", "hi")).isFalse();
        assertThat(e.inbox().depth()).isZero();
    }
}
