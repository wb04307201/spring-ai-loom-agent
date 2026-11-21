package cn.wubo.spring.ai.loom.agent.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Task 7(spec 2026-11-21 agentic-loop-steering)外层续跑 + 护栏回归锁。
 *
 * <p>真 {@link ChatClient} 链 + 桩模型驱动完整重入(非 mock chain),锁定:
 * <ul>
 *   <li>inbox 非空 → 续跑(递归 adviseStream 重入,迭代+1,emitIteration,残留注入下一迭代 prompt);</li>
 *   <li><b>A1/G2</b>:inbox 空 → 不续跑(单迭代);</li>
 *   <li>护栏:maxContinuations 触顶 / run 活跃超时(D-3 排除人等待)/ stop → 收口,残留 NOT_INJECTED 终帧;</li>
 *   <li>收口竞态(A2/Review Focus 2):closeAndRemaining 与在途 offer 竞态无第三态;reopen 接受续跑期新 steering。</li>
 * </ul>
 * 变异:finalize 不查 inbox ⇒ continuation 用例红。
 */
@DisplayName("Task 7:外层 inbox 驱动续跑 + 护栏 + 收口竞态(A2)")
class ContinuationGuardrailTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String CONV = "conv-cont";

    // ==================== fixtures ====================

    private static final class Captured {
        final List<Message> userRows = new CopyOnWriteArrayList<>();
        final List<Message> assistantRows = new CopyOnWriteArrayList<>();
        final List<String> acks = new CopyOnWriteArrayList<>();
        final List<String> terminalAcks = new CopyOnWriteArrayList<>();
        final List<Integer> iterations = new CopyOnWriteArrayList<>();
    }

    private static StreamRunContext ctx(SteeringInbox inbox, Captured c, java.util.function.BooleanSupplier stop) {
        return new StreamRunContext(inbox, new MemoryWriter() {
            @Override
            public void writeUser(Message m) {
                c.userRows.add(m);
            }

            @Override
            public void writeAssistant(Message m) {
                c.assistantRows.add(m);
            }
        }, s -> {
        }, c.acks::add, c.iterations::add, stop, c.terminalAcks::add);
    }

    /** 桩模型:每轮返回纯文本 "a{index}"(无 tool_call),可按轮次向 inbox 注入 steering、可睡眠。 */
    private static final class SteeringStubModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        SteeringInbox inbox;
        final Map<Integer, String> offerTextOnCall = new ConcurrentHashMap<>();
        volatile long sleepMs;

        @Override
        public ChatResponse call(Prompt prompt) {
            int n = calls.incrementAndGet();
            prompts.add(prompt);
            String offer = offerTextOnCall.get(n);
            if (offer != null && inbox != null) {
                inbox.offer("m" + n, offer);
            }
            return usageResponse(n);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            int n = calls.incrementAndGet();
            prompts.add(prompt);
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String offer = offerTextOnCall.get(n);
            if (offer != null && inbox != null) {
                inbox.offer("m" + n, offer);
            }
            return Flux.just(usageResponse(n));
        }

        private static ChatResponse usageResponse(int n) {
            ChatResponse base = new ChatResponse(List.of(new Generation(new AssistantMessage("a" + (n - 1)))));
            // 每轮携带 usage(10/5/15),供 S3 续跑逐迭代 usage 行断言;未挂 usage advisor 的用例忽略。
            return ChatResponse.builder().from(base)
                    .metadata(org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                            .usage(new org.springframework.ai.chat.metadata.DefaultUsage(10, 5, 15))
                            .build())
                    .build();
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    private static List<ChatResponse> run(ChatModel model, StreamRunContext ctx,
                                          LoomAgenticLoopAdvisor advisor) {
        ChatClient client = ChatClient.builder(model).build();
        return client.prompt().user("原任务")
                .advisors(advisor)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONV))
                .advisors(a -> a.param(StreamRunContext.CONTEXT_KEY, ctx))
                .stream().chatResponse().collectList().block(TIMEOUT);
    }

    // ==================== 续跑 ====================

    @Test
    @DisplayName("inbox 非空 → 续跑:迭代+1、emitIteration、残留注入下一迭代 prompt(ASSISTANT 后 USER)")
    void finalize_nonempty_inbox_continues() {
        SteeringInbox inbox = new SteeringInbox();
        inbox.offer("m0", "补充0");
        Captured c = new Captured();
        StreamRunContext ctx = ctx(inbox, c, () -> false);
        SteeringStubModel model = new SteeringStubModel();
        model.inbox = inbox;

        run(model, ctx, LoomAgenticLoopAdvisor.builder().build());

        assertThat(model.calls.get()).as("续跑 → 2 个外层迭代").isEqualTo(2);
        assertThat(c.iterations).as("续跑迭代帧(idx>0)").containsExactly(1);
        assertThat(c.acks).as("残留注入转正").containsExactly("m0");
        assertThat(c.terminalAcks).isEmpty();
        assertThat(c.assistantRows).extracting(Message::getText).containsExactly("a0", "a1");
        assertThat(c.userRows).extracting(Message::getText).containsExactly("补充0");
        // 续跑 prompt:末轮 history + 本迭代 ASSISTANT(a0) + steering USER(补充0)
        Prompt second = model.prompts.get(1);
        List<Message> ins = second.getInstructions();
        assertThat(ins.get(ins.size() - 1)).isInstanceOf(UserMessage.class);
        assertThat(ins.get(ins.size() - 1).getText()).isEqualTo("补充0");
        assertThat(ins.get(ins.size() - 2)).isInstanceOf(AssistantMessage.class);
        assertThat(ins.get(ins.size() - 2).getText()).isEqualTo("a0");
    }

    @Test
    @DisplayName("A1/G2:inbox 空 → 单迭代收口,零续跑、零 iteration/ack 帧")
    void finalize_empty_inbox_ends() {
        SteeringInbox inbox = new SteeringInbox();
        Captured c = new Captured();
        StreamRunContext ctx = ctx(inbox, c, () -> false);
        SteeringStubModel model = new SteeringStubModel();
        model.inbox = inbox;

        run(model, ctx, LoomAgenticLoopAdvisor.builder().build());

        assertThat(model.calls.get()).isEqualTo(1);
        assertThat(c.iterations).isEmpty();
        assertThat(c.acks).isEmpty();
        assertThat(c.assistantRows).extracting(Message::getText).containsExactly("a0");
        assertThat(c.userRows).isEmpty();
        assertThat(inbox.isClosing()).as("收口后 inbox 关闭(后续 steer 被拒)").isTrue();
    }

    @Test
    @DisplayName("续跑期 reopen 接受新 steering:迭代1 注入的 m2 被迭代2 消费(每迭代新接受窗口)")
    void continuation_reopen_accepts_new_steering() {
        SteeringInbox inbox = new SteeringInbox();
        inbox.offer("m0", "补充0");
        Captured c = new Captured();
        StreamRunContext ctx = ctx(inbox, c, () -> false);
        SteeringStubModel model = new SteeringStubModel();
        model.inbox = inbox;
        model.offerTextOnCall.put(2, "补充2"); // 续跑迭代1 期间用户再插话

        run(model, ctx, LoomAgenticLoopAdvisor.builder().build());

        assertThat(model.calls.get()).as("m0 续跑 + m2 再续跑 = 3 迭代").isEqualTo(3);
        assertThat(c.acks).containsExactly("m0", "m2");
        assertThat(c.iterations).containsExactly(1, 2);
        assertThat(c.userRows).extracting(Message::getText).containsExactly("补充0", "补充2");
        assertThat(c.assistantRows).extracting(Message::getText).containsExactly("a0", "a1", "a2");
    }

    // ==================== 护栏 ====================

    @Test
    @DisplayName("maxContinuations 触顶 → 收口,残留 NOT_INJECTED 终帧(迭代==max 即停)")
    void max_continuations_stops() {
        SteeringInbox inbox = new SteeringInbox();
        Captured c = new Captured();
        StreamRunContext ctx = ctx(inbox, c, () -> false);
        SteeringStubModel model = new SteeringStubModel();
        model.inbox = inbox;
        model.offerTextOnCall.put(1, "补充1");
        model.offerTextOnCall.put(2, "补充2");

        run(model, ctx, LoomAgenticLoopAdvisor.builder().maxContinuations(1).build());

        // idx0 drain m1(0<1 续跑)→ idx1 drain m2(1>=1 停)
        assertThat(model.calls.get()).isEqualTo(2);
        assertThat(c.acks).containsExactly("m1");
        assertThat(c.terminalAcks).as("触顶残留可见终态").containsExactly("m2");
        assertThat(c.iterations).containsExactly(1);
    }

    @Test
    @DisplayName("stop 信号 → 边界收口,残留 NOT_INJECTED 终帧,零续跑")
    void stop_signal_ends_at_boundary() {
        SteeringInbox inbox = new SteeringInbox();
        inbox.offer("m0", "补充0");
        Captured c = new Captured();
        StreamRunContext ctx = ctx(inbox, c, () -> true);
        SteeringStubModel model = new SteeringStubModel();
        model.inbox = inbox;

        run(model, ctx, LoomAgenticLoopAdvisor.builder().build());

        assertThat(model.calls.get()).isEqualTo(1);
        assertThat(c.terminalAcks).containsExactly("m0");
        assertThat(c.acks).isEmpty();
        assertThat(c.iterations).isEmpty();
    }

    @Test
    @DisplayName("D-3:run 活跃超时收口;pauseClock 人等待区间不计(墙钟超预算仍续跑)")
    void run_timeout_excludes_human_wait() {
        // (a) 活跃超时:模型轮睡眠 1100ms 计入活跃 → activeElapsed>=1000 → 收口 + 终帧
        SteeringInbox inboxA = new SteeringInbox();
        inboxA.offer("mA", "活跃超时");
        Captured ca = new Captured();
        StreamRunContext ctxA = ctx(inboxA, ca, () -> false);
        SteeringStubModel modelA = new SteeringStubModel();
        modelA.inbox = inboxA;
        modelA.sleepMs = 1100;
        run(modelA, ctxA, LoomAgenticLoopAdvisor.builder().runTimeoutSeconds(1).build());
        assertThat(modelA.calls.get()).as("活跃超预算 → 不续跑").isEqualTo(1);
        assertThat(ca.terminalAcks).containsExactly("mA");

        // (b) 人等待排除:pauseClock 后模型轮睡眠 1100ms(墙钟)但活跃≈0 → 仍续跑
        SteeringInbox inboxB = new SteeringInbox();
        inboxB.offer("mB", "人等待");
        Captured cb = new Captured();
        StreamRunContext ctxB = ctx(inboxB, cb, () -> false);
        ctxB.pauseClock(); // 模拟 askUser 人等待暂停(Task 8 接线,此处直接消费活跃语义)
        SteeringStubModel modelB = new SteeringStubModel();
        modelB.inbox = inboxB;
        modelB.sleepMs = 1100;
        run(modelB, ctxB, LoomAgenticLoopAdvisor.builder().runTimeoutSeconds(1).build());
        assertThat(ctxB.activeElapsedMs()).as("暂停区间不计入活跃").isLessThan(1000);
        assertThat(modelB.calls.get()).as("活跃未超预算 → 续跑").isEqualTo(2);
        assertThat(cb.acks).containsExactly("mB");
        assertThat(cb.terminalAcks).isEmpty();
    }

    // ==================== 收口竞态(A2/Review Focus 2) ====================

    @Test
    @DisplayName("收口竞态:在途 offer 定格于 closeAndRemaining 窗口 → 撤单 false,无孤儿(A2 无第三态)")
    void finalize_race_offer_during_close_no_loss() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Boolean> offerResult = new AtomicReference<>();
        SteeringInbox inbox = new SteeringInbox() {
            @Override
            void beforeEnqueue(SteeringInbox.SteeringMessage msg) {
                if ("inflight".equals(msg.messageId())) {
                    arrived.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        Captured c = new Captured();
        StreamRunContext ctx = ctx(inbox, c, () -> false);
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();

        Thread offerer = new Thread(() -> offerResult.set(inbox.offer("inflight", "竞态")));
        offerer.start();
        assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();

        // finalize 收口(空 inbox 否则):closeAndRemaining 读不到定格中的 inflight → 空残留 → 不续跑不 reopen
        ChatClientResponse resp = ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("a0")))))
                .context(Map.of(StreamRunContext.CONTEXT_KEY, ctx))
                .build();
        advisor.doFinalizeLoop(resp, mock(CallAdvisorChain.class));

        release.countDown();
        offerer.join(5000);

        assertThat(offerResult.get())
                .as("A2:收口后入队必须撤单 false(拒绝即可见),不得 true 却蒸发")
                .isFalse();
        assertThat(inbox.drain()).as("收口后无复活孤儿").isEmpty();
        assertThat(inbox.depth()).isZero();
        assertThat(c.acks).as("竞态消息未被注入").isEmpty();
    }

    @Test
    @DisplayName("terminate 后 reopen 拒绝复活:stop/断连永久收口不被续跑重开(R11)")
    void terminate_blocks_reopen() {
        SteeringInbox inbox = new SteeringInbox();
        inbox.offer("m0", "x");
        assertThat(inbox.closeAndRemaining()).extracting(SteeringInbox.SteeringMessage::messageId).containsExactly("m0");
        assertThat(inbox.reopen()).as("迭代收口可重开").isTrue();
        assertThat(inbox.offer("m1", "y")).isTrue();
        inbox.terminate();
        assertThat(inbox.reopen()).as("永久终止后不得复活").isFalse();
        assertThat(inbox.offer("m2", "z")).isFalse();
        assertThat(inbox.isClosing()).isTrue();
        assertThat(inbox.terminateAndRemaining())
                .extracting(SteeringInbox.SteeringMessage::messageId).containsExactly("m1");
    }

    // ==================== S3 续跑 usage + call 路径对称 ====================

    @Test
    @DisplayName("S3:续跑逐迭代各落 1 行 usage(copy(this) 重入覆盖 order=100 usage advisor,无双计)")
    void usage_recorded_per_continuation_iteration() {
        SteeringInbox inbox = new SteeringInbox();
        inbox.offer("m0", "补充0");
        Captured c = new Captured();
        StreamRunContext ctx = ctx(inbox, c, () -> false);
        SteeringStubModel model = new SteeringStubModel();
        model.inbox = inbox;
        cn.wubo.spring.ai.loom.agent.token.ChatUsageService usage =
                mock(cn.wubo.spring.ai.loom.agent.token.ChatUsageService.class);

        ChatClient client = ChatClient.builder(model).build();
        client.prompt().user("原任务")
                .advisors(new cn.wubo.spring.ai.loom.agent.token.ChatUsageRecordingAdvisor(usage, CONV, "bob", 100))
                .advisors(LoomAgenticLoopAdvisor.builder().build())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONV))
                .advisors(a -> a.param(StreamRunContext.CONTEXT_KEY, ctx))
                .stream().chatResponse().collectList().block(TIMEOUT);

        assertThat(model.calls.get()).isEqualTo(2);
        // 2 迭代 = 2 模型轮 = 2 行 usage,各为轮原值(10/5/15),无累计形态双计
        org.mockito.Mockito.verify(usage, org.mockito.Mockito.times(2))
                .record(org.mockito.ArgumentMatchers.eq(CONV), org.mockito.ArgumentMatchers.eq("bob"),
                        org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(5L),
                        org.mockito.ArgumentMatchers.eq(15L));
        org.mockito.Mockito.verify(usage, org.mockito.Mockito.times(2))
                .record(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("call 路径对称:adviseCall 续跑同样重入完整循环(迭代+1,残留注入)")
    void call_path_continuation_symmetric() {
        SteeringInbox inbox = new SteeringInbox();
        inbox.offer("m0", "补充0");
        Captured c = new Captured();
        StreamRunContext ctx = ctx(inbox, c, () -> false);
        SteeringStubModel model = new SteeringStubModel();
        model.inbox = inbox;

        ChatClient client = ChatClient.builder(model).build();
        ChatResponse resp = client.prompt().user("原任务")
                .advisors(LoomAgenticLoopAdvisor.builder().build())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONV))
                .advisors(a -> a.param(StreamRunContext.CONTEXT_KEY, ctx))
                .call().chatResponse();

        assertThat(resp.getResult().getOutput().getText()).isEqualTo("a1");
        assertThat(model.calls.get()).as("call 路径续跑 → 2 迭代").isEqualTo(2);
        assertThat(c.iterations).containsExactly(1);
        assertThat(c.acks).containsExactly("m0");
        assertThat(c.assistantRows).extracting(Message::getText).containsExactly("a0", "a1");
        Prompt second = model.prompts.get(1);
        List<Message> ins = second.getInstructions();
        assertThat(ins.get(ins.size() - 1).getText()).isEqualTo("补充0");
    }
}