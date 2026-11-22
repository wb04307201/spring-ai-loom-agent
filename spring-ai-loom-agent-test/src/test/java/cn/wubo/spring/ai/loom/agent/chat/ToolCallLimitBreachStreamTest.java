package cn.wubo.spring.ai.loom.agent.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R35(2026-11-22 final review):工具调用上限 breach 的**可达性 + 流式降级安全**回归锁。
 *
 * <p><b>定性订正</b>:Task 7 ⑨ 曾把 returnDirect/ToolCallLimitExceededException breach 路径
 * 标为"dormant(仓内无使用)"—— 证伪:Spring AI 2.0.2 {@code DefaultToolCallingManager}
 * 默认携带**激活**上限(单工具 40 次 / 整轮 150 次 / {@code onLimitExceeded=THROW},常量哨兵
 * 用例锁定),长 agentic run 可真实触达 breach 路径。
 *
 * <p><b>锁定的形态</b>(真实 2.0.2 流式工具循环驱动,非 mock 链近似):limit 触顶时
 * {@code ToolCallingAdvisor.handleToolCallRecursion} 捕获 {@code ToolCallLimitExceededException},
 * 直接返回 breach 单帧,**不经过** {@code doFinalizeLoopStream} ⇒
 * {@code LoomAgenticLoopAdvisor.finalizeIteration} 与外层续跑整体跳过。降级安全三条:
 * <ol>
 *   <li>流正常 complete(breach 帧是普通文本 generation,无 error 截断);</li>
 *   <li>perIterationMode 未置位 → {@link LoomMemoryAdvisor#after} 兜底聚合单写 ASSISTANT
 *       (文本不丢、无双写);</li>
 *   <li>finalizeIteration 未跑 ⇒ reasoning 缓冲留在 ctx、触顶后才入队的 steering 残留留在
 *       inbox —— 恰好是 SseController onComplete({@code flushReasoning + terminateInbox})
 *       既有兜底通道可处置的形态(接线本身由 NoSteeringFrameSequenceContractTest /
 *       SseEmitterRegistry 既有锁覆盖,本类锁定其前提)。</li>
</ol>
 *
 * <p>变异自证:①若 breach 形态改为抛错截断流 ⇒ complete/帧断言红;②若收口钩子被强行跑在
 * breach 上 ⇒ mergedWrites 变空、assistantRows 出现、finalizeStreamCalls≠0 → 红;
 * ③上游若默认关闭上限 ⇒ 常量哨兵用例红。
 */
@DisplayName("R35:工具调用上限 breach 可达(2.0.2 默认激活)+ 流式降级安全回归锁")
class ToolCallLimitBreachStreamTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String CONV = "conv-limit-breach";
    private static final String TOOL = "loop-probe";

    // ==================== 定性哨兵:2.0.2 默认上限是激活的 ====================

    @Test
    @DisplayName("定性锁:DefaultToolCallingManager 默认上限 40/150(激活)—— 推翻'dormant'旧判定")
    void production_defaults_are_ACTIVE_not_dormant() {
        // 常量值 = breach 可达性的直接证据:非 0/非禁用 ⇒ 生产默认 manager 长 run 会真实触限。
        // ToolCallLimitBehavior 默认 THROW(DefaultToolCallingManager.Builder.onLimitExceeded),
        // 本类主用例用同构 manager(maxCallsPerTool(1))实证触限 → catch → breach 单帧形态。
        assertThat(DefaultToolCallingManager.DEFAULT_MAX_CALLS_PER_TOOL)
                .as("2.0.2 默认单工具上限(激活)")
                .isEqualTo(40);
        assertThat(DefaultToolCallingManager.DEFAULT_MAX_TOTAL_TOOL_CALLS)
                .as("2.0.2 默认整轮上限(激活)")
                .isEqualTo(150);
    }

    // ==================== breach 流式降级 ====================

    @Test
    @DisplayName("stream breach → 流正常 complete + breach 帧送达 + 收口钩子跳过 + after() 聚合单写兜底")
    void stream_breach_degrades_safely() {
        // 1. 真实 DefaultToolCallingManager,maxCallsPerTool(1):第二轮同工具调用即触限 THROW
        //    (与生产默认 40/150/THROW 同构,仅阈值缩小,避免测试跑 41 轮)。
        AtomicInteger toolInvocations = new AtomicInteger();
        ToolCallback probe = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(TOOL).description("loop probe for R35 breach test")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }

            @Override
            public String call(String toolInput) {
                toolInvocations.incrementAndGet();
                return "ok";
            }
        };
        ToolCallingManager manager = DefaultToolCallingManager.builder()
                .maxCallsPerTool(1)
                .build();

        // 2. 桩模型:每轮都请求调用 loop-probe(长 agentic run 形态);第 2 轮模型调用期间
        //    注入一条 steering —— 触顶后内层 drain 点(下一轮 next-instructions)永不再达,
        //    外层收口又被跳过 ⇒ 该残留必然留在 inbox(可见终态交 onComplete terminateInbox)。
        LoopingStubModel model = new LoopingStubModel();

        // 3. 生产形态 advisor:counting 子类锁定两个收口钩子在 breach 路径均未执行。
        CountingLoopAdvisor advisor = new CountingLoopAdvisor(manager);

        // 4. 记录型 ChatMemory + ctx(reasoning 缓冲 / ack 通道)。
        RecordingMemory memory = new RecordingMemory();
        SteeringInbox inbox = new SteeringInbox();
        model.inbox = inbox;
        model.offerOnCall = 2;
        List<String> acks = new CopyOnWriteArrayList();
        List<String> terminalAcks = new CopyOnWriteArrayList();
        List<Message> assistantRows = new CopyOnWriteArrayList();
        List<String> userRows = new CopyOnWriteArrayList();
        List<String> flushedReasoning = new CopyOnWriteArrayList();
        StreamRunContext ctx = new StreamRunContext(inbox, new MemoryWriter() {
            @Override
            public void writeUser(Message m) {
                userRows.add(m.getText());
            }

            @Override
            public void writeAssistant(Message m) {
                assistantRows.add(m);
            }
        }, flushedReasoning::add, acks::add, i -> {
        }, () -> false, terminalAcks::add);
        // 触顶轮之前 reasoning 已在缓冲(SseController onNext cumulative→delta 语义的等价注入)。
        ctx.appendReasoning("think-residual");

        List<ChatResponse> frames = ChatClient.builder(model).build().prompt()
                .user("原任务")
                .toolCallbacks(probe)
                .advisors(new LoomMemoryAdvisor(memory), advisor)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONV))
                .advisors(a -> a.param(StreamRunContext.CONTEXT_KEY, ctx))
                .stream().chatResponse().collectList().block(TIMEOUT);

        // —— 降级①:流正常 complete(上面 block 未抛),breach 文本作为普通帧送达。
        assertThat(frames).as("breach 后仍有输出帧送达(无 error 截断)").isNotEmpty();
        String lastText = frames.get(frames.size() - 1).getResult().getOutput().getText();
        assertThat(lastText)
                .as("末帧 = breach 文本(2.0.2 handleToolCallRecursion catch 语义)")
                .contains("Tool call limit (1) exceeded")
                .contains(TOOL);
        assertThat(toolInvocations.get())
                .as("第 1 次同工具调用执行成功,第 2 次触限前被拒 ⇒ 恰 1 次真实执行")
                .isEqualTo(1);
        assertThat(model.calls.get())
                .as("breach 终止递归 ⇒ 模型恰被调用 2 轮")
                .isEqualTo(2);

        // —— 降级②:外层收口钩子整体跳过(续跑/finalizeIteration 未跑)。
        assertThat(advisor.finalizeStreamCalls.get())
                .as("breach 路径不经过 doFinalizeLoopStream")
                .isZero();
        assertThat(advisor.finalizeCalls.get())
                .as("流式链不涉及 call 收口钩子")
                .isZero();
        assertThat(assistantRows)
                .as("finalizeIteration 未跑 ⇒ 逐迭代 ASSISTANT 零写(无双写半程)")
                .isEmpty();
        assertThat(ctx.perIterationMode())
                .as("perIterationMode 未置位")
                .isFalse();

        // —— 降级③:LoomMemoryAdvisor.after() 兜底聚合单写 ASSISTANT(breach 文本存活,不丢)。
        assertThat(memory.mergedAssistantWrites)
                .as("perIterationMode 未置位 → after() 走聚合单写兜底(ASSISTANT 文本不丢)")
                .hasSize(1);
        assertThat(memory.mergedAssistantWrites.get(0).getText())
                .contains("Tool call limit (1) exceeded");
        assertThat(memory.turnUserWrites)
                .as("turn-initial USER 行照常(before() 语义不变)")
                .hasSize(1);

        // —— 降级前提:reasoning 缓冲与 inbox 残留未被收口消费 ⇒ onComplete 兜底可处置。
        assertThat(flushedReasoning)
                .as("finalizeIteration 跳过 ⇒ 迭代边界 reasoning flush 未发生")
                .isEmpty();
        ctx.flushReasoning(); // 等价 SseController onComplete 的兜底 flush
        assertThat(flushedReasoning)
                .as("残量 reasoning 可被 onComplete 兜底通道完整落库(不丢)")
                .containsExactly("think-residual");

        assertThat(inbox.isClosing())
                .as("breach 未收口 inbox ⇒ 残留交 onComplete terminateInbox(R11/A2 通道可用)")
                .isFalse();
        assertThat(inbox.terminateAndRemaining())
                .as("触顶后才入队的 steering 残留仍在 inbox —— 不注入、不静默蒸发,可发 NOT_INJECTED 终帧")
                .extracting(SteeringInbox.SteeringMessage::messageId)
                .containsExactly("m-late");
        assertThat(acks)
                .as("残留从未被伪造注入(无 INJECTED ack)")
                .isEmpty();
        assertThat(userRows)
                .as("未注入 ⇒ 不经注入点写 USER 行(与审计表'行存在 ⇔ 注入发生'契约一致)")
                .isEmpty();
    }

    // ==================== fixtures ====================

    /** 每轮都返回 tool_call(loop-probe)的桩模型;可指定第 offerOnCall 轮调用时注入 steering。 */
    private static final class LoopingStubModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        volatile SteeringInbox inbox;
        volatile int offerOnCall = -1;

        @Override
        public ChatResponse call(Prompt prompt) {
            return next();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(next());
        }

        private ChatResponse next() {
            int n = calls.incrementAndGet();
            if (n == offerOnCall && inbox != null) {
                inbox.offer("m-late", "触顶后插话");
            }
            AssistantMessage output = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("tc-" + n, "function", TOOL, "{}")))
                    .build();
            return new ChatResponse(List.of(new Generation(output)));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    /** 锁定 breach 路径跳过两个收口钩子(call/stream),其余行为委托生产实现。 */
    private static final class CountingLoopAdvisor extends LoomAgenticLoopAdvisor {
        final AtomicInteger finalizeStreamCalls = new AtomicInteger();
        final AtomicInteger finalizeCalls = new AtomicInteger();

        CountingLoopAdvisor(ToolCallingManager manager) {
            // checker 与 ToolCallingAdvisor.DEFAULT_TOOL_EXECUTION_ELIGIBILITY_CHECKER 同语义
            super(manager, (ToolExecutionEligibilityChecker) cr -> cr != null && cr.hasToolCalls(),
                    ToolCallingAdvisor.DEFAULT_ORDER, true);
        }

        @Override
        protected Flux<ChatClientResponse> doFinalizeLoopStream(Flux<ChatClientResponse> flux,
                                                                 StreamAdvisorChain chain) {
            finalizeStreamCalls.incrementAndGet();
            return super.doFinalizeLoopStream(flux, chain);
        }

        @Override
        protected ChatClientResponse doFinalizeLoop(ChatClientResponse response, CallAdvisorChain chain) {
            finalizeCalls.incrementAndGet();
            return super.doFinalizeLoop(response, chain);
        }
    }

    /** 记录型 ChatMemory:区分 before() 的 turn USER 单写与 after() 的聚合 ASSISTANT 单写。 */
    private static final class RecordingMemory implements ChatMemory {
        final List<Message> turnUserWrites = new CopyOnWriteArrayList();
        final List<Message> mergedAssistantWrites = new CopyOnWriteArrayList();

        @Override
        public List<Message> get(String conversationId) {
            return List.of();
        }

        @Override
        public void add(String conversationId, List<Message> messages) {
            for (Message m : messages) {
                if (m.getMessageType() == MessageType.USER) {
                    turnUserWrites.add(m);
                } else if (m.getMessageType() == MessageType.ASSISTANT) {
                    mergedAssistantWrites.add(m);
                }
            }
        }

        @Override
        public void clear(String conversationId) {
        }
    }
}
