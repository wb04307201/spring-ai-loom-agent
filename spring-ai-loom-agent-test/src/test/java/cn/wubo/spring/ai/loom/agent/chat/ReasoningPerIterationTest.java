package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.capability.CapabilityService;
import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge;
import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.model.ChatRequestRecord;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import cn.wubo.spring.ai.loom.agent.token.ChatUsageService;
import cn.wubo.spring.ai.loom.agent.tool.IToolCallLogRepository;
import cn.wubo.spring.ai.loom.agent.user.IUserConversation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LoomAgenticLoopAdvisor} 迭代收口写的 reasoning 落库回归锁
 * (spec 2026-11-21 agentic-loop-steering Task 6 / S3)。
 *
 * <p>锁定三条语义:
 * <ol>
 *   <li><b>多迭代</b>:每个外层迭代结束恰好一行 reasoning,内容 = 该迭代思考文本
 *       (非合并);</li>
 *   <li><b>A1 单迭代</b>:无 steering 单迭代 → saveReasoning 恰 1 次,内容 = 整轮累积
 *       (与改造前 SSE onComplete 单 flush 逐字节等价);</li>
 *   <li><b>无思考 0 行</b>:reasoning 缓冲为空不落库。</li>
 * </ol>
 * 外加两条接线锁:
 * <ul>
 *   <li><b>R14</b>:真 DefaultChatClient 流式链中 {@link LoomMemoryAdvisor#after} 经响应
 *       context 读到同一 {@link StreamRunContext} 引用且 perIterationMode 已置位 → 跳过
 *       聚合单写(ASSISTANT 行恒 1,无双写);</li>
 *   <li><b>R15</b>:DefaultChat 挂载的 loop advisor 的 ToolCallingManager 持客户端级
 *       ObservationRegistry(per-request builder 默认绑 NOOP,不注入则工具 span 静默降级)。</li>
 * </ol>
 *
 * <p><b>变异自证</b>:删 {@code LoomAgenticLoopAdvisor.finalizeIteration} 内
 * {@code ctx.flushReasoning()}(= reasoning 退回整条流 onComplete 单 flush 形态)
 * ⇒ {@code multi_iteration_*} 立即变红。
 */
@DisplayName("reasoning 按外层迭代落库(Task 6/S3) + R14 真链 context 传播 + R15 观测注入")
class ReasoningPerIterationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String CONV = "conv-r";

    // ==================== 单元:迭代边界 → 每迭代一行 reasoning ====================

    @Test
    @DisplayName("多迭代:saveReasoning 每迭代恰 1 次,各含该迭代文本(非合并);ASSISTANT 行逐迭代写")
    void multi_iteration_writes_one_reasoning_row_per_iteration() {
        ChatUsageService usage = mock(ChatUsageService.class);
        List<Message> assistantRows = new ArrayList<>();
        StreamRunContext ctx = ctx(capturingWriter(assistantRows), text -> usage.saveReasoning(CONV, text));
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();

        // 外层迭代 0:SSE lambda 已把该迭代思考 delta 追加进 ctx 缓冲
        ctx.appendReasoning("think-0");
        // delta 状态机长度在边界必须复位(Task 6 移入 ctx;Task 7 续跑重入起点复用)
        ctx.setLastReasoningLen(7);
        advisor.doFinalizeLoop(responseWith(ctx, "answer-0"), mock(CallAdvisorChain.class));

        // 外层迭代 1(续跑,边界语义同路)
        ctx.appendReasoning("think-1");
        ctx.setLastReasoningLen(3);
        advisor.doFinalizeLoop(responseWith(ctx, "answer-1"), mock(CallAdvisorChain.class));

        ArgumentCaptor<String> rows = ArgumentCaptor.forClass(String.class);
        verify(usage, times(2)).saveReasoning(eq(CONV), rows.capture());
        assertThat(rows.getAllValues()).as("每迭代一行,内容非合并")
                .containsExactly("think-0", "think-1");
        assertThat(assistantRows).as("ASSISTANT 逐迭代行")
                .extracting(Message::getText).containsExactly("answer-0", "answer-1");
        assertThat(ctx.getLastReasoningLen()).as("迭代边界复位 delta 累积长度").isZero();
        assertThat(ctx.perIterationMode()).isTrue();
    }

    @Test
    @DisplayName("A1 单迭代(无 steering):saveReasoning 恰 1 次,内容=整轮累积(与改造前同)")
    void single_iteration_no_steering_writes_one_row_like_today() {
        ChatUsageService usage = mock(ChatUsageService.class);
        List<Message> assistantRows = new ArrayList<>();
        StreamRunContext ctx = ctx(capturingWriter(assistantRows), text -> usage.saveReasoning(CONV, text));
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();

        // 单迭代内跨轮持续 append(改造前即整轮一行)
        ctx.appendReasoning("part-A");
        ctx.appendReasoning("part-B");
        advisor.doFinalizeLoop(responseWith(ctx, "final answer"), mock(CallAdvisorChain.class));

        ArgumentCaptor<String> row = ArgumentCaptor.forClass(String.class);
        verify(usage, times(1)).saveReasoning(eq(CONV), row.capture());
        assertThat(row.getValue()).as("整轮累积一行,与改造前 SSE onComplete 单 flush 等价")
                .isEqualTo("part-Apart-B");
        assertThat(assistantRows).hasSize(1);
    }

    @Test
    @DisplayName("无思考:saveReasoning 0 次(空缓冲不写行),ASSISTANT 仍逐迭代 1 行")
    void empty_reasoning_writes_no_row() {
        ChatUsageService usage = mock(ChatUsageService.class);
        List<Message> assistantRows = new ArrayList<>();
        StreamRunContext ctx = ctx(capturingWriter(assistantRows), text -> usage.saveReasoning(CONV, text));
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();

        advisor.doFinalizeLoop(responseWith(ctx, "plain answer"), mock(CallAdvisorChain.class));

        verify(usage, never()).saveReasoning(anyString(), anyString());
        assertThat(assistantRows).hasSize(1);
    }

    @Test
    @DisplayName("R17 空文本末轮(Qwen self-talk):generations 存在 → 仍写恰 1 ASSISTANT 行(与 legacy after() 等量)")
    void empty_assistant_text_still_writes_one_row_like_legacy() {
        ChatUsageService usage = mock(ChatUsageService.class);
        List<Message> assistantRows = new ArrayList<>();
        StreamRunContext ctx = ctx(capturingWriter(assistantRows), text -> usage.saveReasoning(CONV, text));
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();

        // 可达路径:provider 以空 content + finishReason=STOP 结束(self-talk 失败模式)。
        // 改造前 legacy after() 对每 generation 无条件写行(JdbcChatMemoryRepository 无
        // empty-text 过滤)→ 改造后必须同样恰 1 行空文本 ASSISTANT,否则 A1 破功(0 行)。
        ctx.appendReasoning("self-talk-think");
        advisor.doFinalizeLoop(responseWith(ctx, ""), mock(CallAdvisorChain.class));

        assertThat(assistantRows).as("空文本仍须写 1 ASSISTANT 行(等量于 legacy)").hasSize(1);
        assertThat(assistantRows.get(0)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) assistantRows.get(0)).getText()).isEmpty();
        // reasoning 正常落库,不受 ASSISTANT 文本影响
        verify(usage, times(1)).saveReasoning(CONV, "self-talk-think");
        assertThat(ctx.perIterationMode()).isTrue();
    }

    @Test
    @DisplayName("ctx 缺席(子任务/legacy):doFinalizeLoop 纯 super —— 零写、chain 零交互、响应同实例")
    void null_context_finalize_pure_super() {
        ChatUsageService usage = mock(ChatUsageService.class);
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientResponse resp = responseWithTextOnly("pong");

        ChatClientResponse out = advisor.doFinalizeLoop(resp, chain);

        assertThat(out).isSameAs(resp);
        org.mockito.Mockito.verifyNoInteractions(usage, chain);
    }

    @Test
    @DisplayName("流式收口:doAfterStream 缓存末轮聚合响应 → doFinalizeLoopStream 写该迭代 ASSISTANT + reasoning")
    void stream_finalize_hook_writes_iteration_from_cached_round_response() {
        ChatUsageService usage = mock(ChatUsageService.class);
        List<Message> assistantRows = new ArrayList<>();
        StreamRunContext ctx = ctx(capturingWriter(assistantRows), text -> usage.saveReasoning(CONV, text));
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();

        ctx.appendReasoning("stream-think");
        advisor.doAfterStream(responseWith(ctx, "stream-answer"),
                mock(org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain.class));
        // Task 6 无续跑重入:super 透传同一 Flux
        Flux<ChatClientResponse> tail = advisor.doFinalizeLoopStream(Flux.empty(),
                mock(org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain.class));
        assertThat(tail.collectList().block(TIMEOUT)).isEmpty();

        verify(usage, times(1)).saveReasoning(CONV, "stream-think");
        assertThat(assistantRows).hasSize(1);
        assertThat(assistantRows.get(0).getText()).isEqualTo("stream-answer");
        assertThat(ctx.perIterationMode()).isTrue();
    }

    // ==================== R14:真 DefaultChatClient 链 —— after() 经响应 context 见 ctx ====================

    @Test
    @DisplayName("R14 真链:after() 读到 perIterationMode ctx → 跳过聚合单写;记忆恒 1 USER + 1 ASSISTANT(无双写)")
    void R14_real_chain_after_sees_ctx_and_writes_exactly_one_assistant_row() {
        RecordingMemory mem = new RecordingMemory();
        ChatUsageService usage = mock(ChatUsageService.class);
        StreamRunContext ctx = new StreamRunContext(
                new SteeringInbox(),
                new MemoryWriter() {
                    @Override
                    public void writeUser(Message m) {
                        mem.add(CONV, m);
                    }

                    @Override
                    public void writeAssistant(Message m) {
                        mem.add(CONV, m);
                    }
                },
                text -> usage.saveReasoning(CONV, text),
                s -> {
                },
                i -> {
                },
                () -> false);

        // 复刻 DefaultChat.stream 挂载形态(SSE lambda 已把 ctx 塞进 advisor param);
        // reasoning 缓冲由 lambda delta 状态机喂入 —— 此处模拟一段思考
        ctx.appendReasoning("thinking-block");
        ChatClient client = ChatClient.builder(new PongStreamModel()).build();
        // collectList 而非 blockFirst:必须让流完整跑完(aggregator doOnComplete 触发
        // after()/doFinalizeLoopStream —— blockFirst 取消流会让收口写整体缺席,是测试自身的问题)
        List<String> content = client.prompt().user("hi")
                .advisors(new LoomMemoryAdvisor(mem))
                .advisors(LoomAgenticLoopAdvisor.builder().build())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONV))
                .advisors(a -> a.param(StreamRunContext.CONTEXT_KEY, ctx))
                .stream().content().collectList().block(TIMEOUT);
        assertThat(String.join("", content)).isEqualTo("pong");

        // after() 读到 ctx+perIterationMode → 跳过聚合单写:add(String, List) 重载 0 次
        assertThat(mem.mergedWrites).as("after() 聚合单写不得发生(双写)")
                .isEmpty();
        // 单条写恰好 2 行:before() 的 turn-initial USER + loop advisor 迭代收口 ASSISTANT
        assertThat(mem.singleWrites).extracting(Message::getMessageType)
                .containsExactly(MessageType.USER, MessageType.ASSISTANT);
        assertThat(mem.singleWrites.get(1).getText()).isEqualTo("pong");
        // reasoning 恰 1 行(S3 单迭代 = A1 一行);由 loop advisor 迭代收口写
        verify(usage, times(1)).saveReasoning(CONV, "thinking-block");
        assertThat(ctx.perIterationMode()).isTrue();
    }

    @Test
    @DisplayName("R14 对照:ctx 不进 advisor param → perIterationMode 不置位,after() 维持聚合单写(A1)")
    void R14_without_ctx_param_after_writes_merged_assistant() {
        RecordingMemory mem = new RecordingMemory();
        ChatClient client = ChatClient.builder(new PongStreamModel()).build();

        client.prompt().user("hi")
                .advisors(new LoomMemoryAdvisor(mem))
                .advisors(LoomAgenticLoopAdvisor.builder().build())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONV))
                .stream().content().collectList().block(TIMEOUT);

        // 无 ctx:finalize 纯 super(零迭代写);after() 走 legacy 聚合单写 ——
        // mergedWrites 恰 1 批 [ASSISTANT],singleWrites 仅 before() 的 USER(A1 单写语义)
        assertThat(mem.mergedWrites).hasSize(1);
        assertThat(mem.mergedWrites.get(0)).singleElement()
                .satisfies(m -> {
                    assertThat(m).isInstanceOf(AssistantMessage.class);
                    assertThat(m.getText()).isEqualTo("pong");
                });
        assertThat(mem.singleWrites).extracting(Message::getMessageType)
                .containsExactly(MessageType.USER);
    }

    // ==================== R15:loop advisor 的 ToolCallingManager 持客户端级 ObservationRegistry ====================

    @Test
    @DisplayName("R15:12-arg 构造注入 ObservationRegistry → 挂载 advisor 的 ToolCallingManager 持同实例(非 NOOP)")
    void R15_default_chat_mounts_loop_advisor_with_client_observation_registry() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        MountCapture capture = mountDefaultChatAndCaptureAdvisor(registry);

        Object manager = fieldOf(capture.loopAdvisor, ToolCallingAdvisor.class, "toolCallingManager");
        Object advisorRegistry = fieldOf(manager, manager.getClass(), "observationRegistry");
        assertThat(advisorRegistry)
                .as("R15:per-request 挂载必须携带客户端级 ObservationRegistry,否则工具 span 降级 NOOP")
                .isSameAs(registry);
    }

    @Test
    @DisplayName("R15 对照:11-arg 旧构造(消费方直连)退 NOOP —— 与 Spring AI 默认一致,行为不劣化")
    void R15_legacy_constructor_falls_back_to_noop_registry() throws Exception {
        MountCapture capture = mountDefaultChatAndCaptureAdvisor(null);

        Object manager = fieldOf(capture.loopAdvisor, ToolCallingAdvisor.class, "toolCallingManager");
        Object advisorRegistry = fieldOf(manager, manager.getClass(), "observationRegistry");
        assertThat(advisorRegistry).isSameAs(ObservationRegistry.NOOP);
    }

    // ==================== helpers ====================

    private record MountCapture(LoomAgenticLoopAdvisor loopAdvisor) {
    }

    /** 全 mock 依赖驱动真实 DefaultChat.stream,捕获挂到 requestSpec 的 loop advisor。 */
    private static MountCapture mountDefaultChatAndCaptureAdvisor(@Nullable ObservationRegistry registry) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.toolCallbacks(any(ToolCallback[].class))).thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.advisors(any(Advisor[].class))).thenReturn(spec);
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.<ChatResponse>empty());

        IMcp mcp = mock(IMcp.class);
        when(mcp.getVisibleToolCallbackProvider(anyString(), any())).thenReturn(null);
        CapabilityService capabilityService = mock(CapabilityService.class);
        when(capabilityService.allowedCapabilityIdsFor(anyString(), any())).thenReturn(Set.of());
        when(capabilityService.filterEmbedToolsByCapabilityIds(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(capabilityService.visibleToolGroupsFor(anyString())).thenReturn(Set.of());
        ISkillStorage skillStorage = mock(ISkillStorage.class);
        when(skillStorage.list(anyString())).thenReturn(List.of());
        IKnowledge knowledge = mock(IKnowledge.class);
        when(knowledge.list(anyString())).thenReturn(List.of());
        IUserConversation userConversation = mock(IUserConversation.class);
        when(userConversation.exists(any())).thenReturn(true);
        LoomAgentProperties properties = new LoomAgentProperties();

        DefaultChat chat = registry == null
                ? new DefaultChat(chatClient, mcp, List.of(), userConversation, mock(IFile.class),
                        skillStorage, knowledge, properties, mock(IToolCallLogRepository.class),
                        capabilityService, mock(ChatUsageService.class))
                : new DefaultChat(chatClient, mcp, List.of(), userConversation, mock(IFile.class),
                        skillStorage, knowledge, properties, mock(IToolCallLogRepository.class),
                        capabilityService, mock(ChatUsageService.class), registry);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(8080);
        chat.stream(new ChatRequestRecord("hi", CONV, null, null, null), "alice", request);

        ArgumentCaptor<Advisor> mounted = ArgumentCaptor.forClass(Advisor.class);
        verify(spec, atLeastOnce()).advisors(mounted.capture());
        LoomAgenticLoopAdvisor loopAdvisor = mounted.getAllValues().stream()
                .filter(a -> a instanceof LoomAgenticLoopAdvisor)
                .map(a -> (LoomAgenticLoopAdvisor) a)
                .findFirst().orElseThrow(() -> new AssertionError("loop advisor 未挂载"));
        return new MountCapture(loopAdvisor);
    }

    private static Object fieldOf(Object target, Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static StreamRunContext ctx(MemoryWriter writer, Consumer<String> reasoningFlush) {
        return new StreamRunContext(new SteeringInbox(), writer, reasoningFlush,
                s -> {
                },
                i -> {
                },
                () -> false);
    }

    private static MemoryWriter capturingWriter(List<Message> assistantRows) {
        return new MemoryWriter() {
            @Override
            public void writeUser(Message m) {
            }

            @Override
            public void writeAssistant(Message m) {
                assistantRows.add(m);
            }
        };
    }

    /** 聚合响应 + advisor context 携带 ctx(R14 传播形态:终端 advisor 以 request context 建响应 context)。 */
    private static ChatClientResponse responseWith(StreamRunContext ctx, String text) {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))))
                .context(Map.of(StreamRunContext.CONTEXT_KEY, ctx,
                        ChatMemory.CONVERSATION_ID, CONV))
                .build();
    }

    private static ChatClientResponse responseWithTextOnly(String text) {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))))
                .context(Map.of(ChatMemory.CONVERSATION_ID, CONV))
                .build();
    }

    /**
     * 记录型 ChatMemory fake:两个 add 重载分开记 ——
     * {@code add(String, Message)} = 逐条写(turn-initial USER / loop 迭代 ASSISTANT),
     * {@code add(String, List)} = LoomMemoryAdvisor.after() 的聚合单写。
     * 双写/漏写在两个列表上直接可见,不受 Mockito 重载 + 接口 default 委托的干扰。
     */
    private static final class RecordingMemory implements ChatMemory {
        final List<Message> singleWrites = new ArrayList<>();
        final List<List<Message>> mergedWrites = new ArrayList<>();

        @Override
        public void add(String conversationId, Message message) {
            singleWrites.add(message);
        }

        @Override
        public void add(String conversationId, List<Message> messages) {
            mergedWrites.add(List.copyOf(messages));
        }

        @Override
        public List<Message> get(String conversationId) {
            return List.of();
        }

        @Override
        public void clear(String conversationId) {
        }
    }

    /** 只回 "pong" 的流式桩模型(options 必须 ToolCallingChatOptions,否则 loop advisor 直接 skip)。 */
    private static final class PongStreamModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return pong();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(pong());
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private static ChatResponse pong() {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("pong"))));
        }
    }
}
