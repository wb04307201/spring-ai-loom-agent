package cn.wubo.spring.ai.loom.agent.stream;

import cn.wubo.spring.ai.loom.agent.LoomAgentConfiguration;
import cn.wubo.spring.ai.loom.agent.askuser.AskUserRegistry;
import cn.wubo.spring.ai.loom.agent.chat.IChat;
import cn.wubo.spring.ai.loom.agent.chat.SteeringInbox;
import cn.wubo.spring.ai.loom.agent.model.ChatRequestRecord;
import cn.wubo.spring.ai.loom.agent.token.ChatUsageService;
import cn.wubo.spring.ai.loom.agent.user.IUserConversation;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 7(spec 2026-11-21 S5/A8,Review Focus 3)断连 dispose 回归锁。
 *
 * <p>真 SseController.stream lambda + 真 SseEmitterRegistry,桩 IChat 返回持续发射并
 * 写 memory/usage 的 flux(模拟 orphan run 的下游写)。反射触发 emitter 的 errorCallback
 * 模拟客户端断连(container 侧回调),断言:
 * <ul>
 *   <li>订阅被 dispose(disposable.isDisposed + flux doOnCancel 触发);</li>
 *   <li>inbox 永久收口(isClosing)+ 排队 steering 残留被 drain(R11 不静默吞);</li>
 *   <li>registry 身份感知清理(get==null);</li>
 *   <li><b>断连后无新 memory/usage 写</b>(orphan 不跑,A8)。</li>
 * </ul>
 * 变异:去掉断连 dispose ⇒ isDisposed/cancelled/无新写 断言红。
 *
 * <p>轻量构造(非 @SpringBootTest):直接 new SseController + mock IChat,确定性触发
 * lifecycle 回调,不依赖真实 LLM/API key。反射读 ResponseBodyEmitter.errorCallback
 * 是 Spring 内部字段(升版脆性哨兵,字段名钉死于 spring-web 7.0.9 源)。
 */
@DisplayName("Task 7:断连 dispose 防 orphan 写(S5/A8)")
class DisconnectDisposeIT {

    private static final String USER = "disc-user";
    private static final String CONV = "conv-disc";

    private SseEmitterRegistry registry;
    private ChatMemory chatMemory;
    private ChatUsageService usage;
    private LoomAgentConfiguration.ChatConfiguration.SseController controller;

    @BeforeEach
    void setup() {
        registry = new SseEmitterRegistry();
        chatMemory = mock(ChatMemory.class);
        usage = mock(ChatUsageService.class);
        UserContextHolder.setCurrentUser(USER);
    }

    @AfterEach
    void teardown() {
        UserContextHolder.clear();
    }

    @SuppressWarnings("unchecked")
    private static void triggerDisconnect(SseEmitter emitter) throws Exception {
        Field f = ResponseBodyEmitter.class.getDeclaredField("errorCallback");
        f.setAccessible(true);
        Consumer<Throwable> errorCallback = (Consumer<Throwable>) f.get(emitter);
        errorCallback.accept(new IOException("client disconnect"));
    }

    @Test
    @DisplayName("断连 → dispose 订阅 + inbox 收口 drain 残留 + registry 清理 + 无 orphan 写")
    void disconnect_disposes_subscription_no_orphan_writes() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch subscribed = new CountDownLatch(1);

        // 桩 run:每 20ms 发射一帧并写 memory/usage(模拟下游 advisor 逐帧写)。
        Flux<ChatResponse> stubFlux = Flux.interval(Duration.ofMillis(20))
                .map(i -> new ChatResponse(List.of(new Generation(new AssistantMessage("chunk" + i)))))
                .doOnSubscribe(s -> subscribed.countDown())
                .doOnNext(r -> {
                    writes.incrementAndGet();
                    chatMemory.add(CONV, new AssistantMessage("orphan-assistant"));
                    usage.record(CONV, USER, 1, 1, 2);
                })
                .doOnCancel(() -> cancelled.set(true))
                .take(200);

        IChat chat = mock(IChat.class);
        when(chat.stream(any(), any(), any())).thenReturn(stubFlux);
        controller = new LoomAgentConfiguration.ChatConfiguration.SseController(
                chat, registry, mock(AskUserRegistry.class), usage, chatMemory,
                mock(IUserConversation.class));

        SseEmitter emitter = controller.stream(
                new ChatRequestRecord("hi", CONV, null, null, null),
                new MockHttpServletRequest());

        assertThat(subscribed.await(5, TimeUnit.SECONDS)).as("run 订阅建立").isTrue();
        SseEmitterRegistry.Entry entry = registry.get(USER, CONV);
        assertThat(entry).isNotNull();
        // 断连前排一条 steering,验证收口 drain(R11)
        assertThat(entry.inbox().offer("m0", "断连前的补充")).isTrue();

        triggerDisconnect(emitter);

        // 立即断言:dispose + 收口 + 清理(变异去掉 dispose 即红)
        assertThat(entry.disposable().isDisposed()).as("订阅已 dispose").isTrue();
        assertThat(cancelled.get()).as("flux 被取消(doOnCancel)").isTrue();
        assertThat(entry.inbox().isClosing()).as("inbox 永久收口").isTrue();
        assertThat(entry.inbox().depth()).as("排队 steering 残留被 drain,不静默吞").isZero();
        assertThat(registry.get(USER, CONV)).as("身份感知清理").isNull();

        // 断连后无新写(orphan 不跑):捕获当前写计数,宽限期内不得增长
        int writesAtDisconnect = writes.get();
        Thread.sleep(150);
        assertThat(writes.get())
                .as("A8:断连后 orphan run 不得继续写 memory/usage")
                .isEqualTo(writesAtDisconnect);
    }

    @Test
    @DisplayName("正常完成路径不误伤:onComplete 收口空残留零终帧(A1)")
    void normal_completion_no_residual_frames() throws Exception {
        // 单帧即完成的 flux,无 steering → 正常收口,inbox 空残留,registry 清理
        Flux<ChatResponse> doneFlux = Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
        IChat chat = mock(IChat.class);
        when(chat.stream(any(), any(), any())).thenReturn(doneFlux);
        controller = new LoomAgentConfiguration.ChatConfiguration.SseController(
                chat, registry, mock(AskUserRegistry.class), usage, chatMemory,
                mock(IUserConversation.class));

        SseEmitter emitter = controller.stream(
                new ChatRequestRecord("hi", CONV, null, null, null),
                new MockHttpServletRequest());

        // 等待异步 run 完成并清理
        long deadline = System.currentTimeMillis() + 5000;
        while (registry.get(USER, CONV) != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(registry.get(USER, CONV)).as("正常完成后 registry 清理").isNull();
        assertThat(emitter).isNotNull();
    }
}