package cn.wubo.spring.ai.loom.agent.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.ToolAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link LoomAgenticLoopAdvisor} 骨架的 A1 退化 + S1 抑制回归锁(spec 2026-11-21 Task 5)。
 *
 * <p><b>A1 退化</b>:Task 5 仅骨架 —— 四个 loop 钩子全部委托 super。请求 context 无
 * {@link StreamRunContext}(子任务/自定义 IChat 路径)或有但 inbox 为空(主对话 Task 6
 * 接线前)→ 循环行为与默认 {@link ToolCallingAdvisor} 逐语义一致:一次模型调用、
 * finalize 原样透传、<b>零续跑模型调用</b>、finalize 钩子对 chain 零交互(不触发
 * chain.copy 重入)。
 *
 * <p><b>S1(已按 2.0.2 sources 核实,本类以真 DefaultChatClient 复验)</b>:
 * {@code DefaultChatClient.autoRegisterToolCallingAdvisor()} 以
 * {@code advisors.anyMatch(a -> a instanceof ToolAdvisor)} 判定是否跳过默认注册;
 * 本类 extends ToolCallingAdvisor(implements ToolAdvisor)→ 逐请求挂载后链上
 * <b>恰好一个 ToolAdvisor 且就是本类</b>。若 Spring AI 将来改变抑制逻辑导致双
 * ToolAdvisor,{@code validateSingleToolAdvisor()} 会在 stream()/call() 时抛
 * IllegalStateException,真链用例即刻变红。
 */
@DisplayName("LoomAgenticLoopAdvisor 骨架:A1 纯 super 退化 + S1 单 ToolAdvisor")
class LoomAgenticLoopAdvisorNoOpTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    // ==================== 类型前提(S1 抑制机制) ====================

    @Test
    @DisplayName("骨架 advisor 是 ToolCallingAdvisor + ToolAdvisor(S1 抑制的前提)")
    void advisor_is_tool_calling_advisor_and_tool_advisor() {
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();
        assertThat(advisor).isInstanceOf(ToolCallingAdvisor.class);
        // autoRegisterToolCallingAdvisor 靠这个 marker 抑制默认注册 —— 若哪天
        // ToolCallingAdvisor 不再 implements ToolAdvisor,此断言变红即契约破裂
        assertThat(advisor).isInstanceOf(ToolAdvisor.class);
    }

    @Test
    @DisplayName("builder 默认 order = ToolCallingAdvisor.DEFAULT_ORDER(HIGHEST+300)")
    void order_is_tool_calling_default() {
        assertThat(LoomAgenticLoopAdvisor.builder().build().getOrder())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 300)
                .isEqualTo(ToolCallingAdvisor.DEFAULT_ORDER);
    }

    // ==================== A1:钩子全委托 super(直接调用) ====================

    @Test
    @DisplayName("doFinalizeLoop 委托 super:原样透传 response,对 chain 零交互(无续跑重入)")
    void finalize_call_hook_delegates_super_no_continuation() {
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();
        ChatClientResponse resp = responseWith("done");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        ChatClientResponse out = advisor.doFinalizeLoop(resp, chain);

        assertThat(out).isSameAs(resp); // super 语义:恒等透传
        // A1:无 steering 时 finalize 绝不允许借 chain.copy/nextCall 重入模型
        verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("doFinalizeLoopStream 委托 super:同一 Flux 透传,对 chain 零交互")
    void finalize_stream_hook_delegates_super_no_continuation() {
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();
        Flux<ChatClientResponse> flux = Flux.just(responseWith("done"));
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        Flux<ChatClientResponse> out = advisor.doFinalizeLoopStream(flux, chain);

        assertThat(out).isSameAs(flux);
        verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("doGetNextInstructionsForToolCall 委托 super:conversationHistoryEnabled=true → 全量 history")
    void next_instructions_call_hook_delegates_super_full_history() {
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();
        ToolExecutionResult ter = toolExecutionResult();
        ChatClientRequest req = requestWith(Map.of()); // null context(A1/子任务)

        List<Message> out = advisor.doGetNextInstructionsForToolCall(req, responseWith("x"), ter);

        // super 语义(Issue1 不变式:history 全量保留,不截断)
        assertThat(out).isSameAs(ter.conversationHistory());
    }

    @Test
    @DisplayName("doGetNextInstructionsForToolCallStream 委托 super:同样全量 history")
    void next_instructions_stream_hook_delegates_super_full_history() {
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();
        ToolExecutionResult ter = toolExecutionResult();
        ChatClientRequest req = requestWith(Map.of());

        List<Message> out = advisor.doGetNextInstructionsForToolCallStream(req, responseWith("x"), ter);

        assertThat(out).isSameAs(ter.conversationHistory());
    }

    // ==================== A1:adviseCall 端到端(mock chain,精确调用计数) ====================

    @Test
    @DisplayName("adviseCall null context:单次模型调用后直接收尾,无续跑(== super)")
    void adviseCall_null_context_pure_super_single_model_call() {
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.copy(any())).thenReturn(chain);
        when(chain.nextCall(any())).thenReturn(responseWith("pong"));

        ChatClientResponse out = advisor.adviseCall(
                requestWithToolCalling(Map.of()), chain);

        assertThat(out.chatResponse().getResult().getOutput().getText()).isEqualTo("pong");
        verify(chain, times(1)).nextCall(any()); // 无工具调用 → 一次即止,零续跑
    }

    @Test
    @DisplayName("adviseCall 空 inbox 的 StreamRunContext:仍纯 super,零额外模型调用")
    void adviseCall_empty_inbox_no_continuation_extra_model_calls() {
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();
        StreamRunContext ctx = new StreamRunContext(
                new SteeringInbox(), noopWriter(), s -> { }, s -> { }, i -> { }, () -> false);

        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.copy(any())).thenReturn(chain);
        when(chain.nextCall(any())).thenReturn(responseWith("pong"));

        ChatClientResponse out = advisor.adviseCall(
                requestWithToolCalling(Map.of(StreamRunContext.CONTEXT_KEY, ctx)), chain);

        assertThat(out.chatResponse().getResult().getOutput().getText()).isEqualTo("pong");
        verify(chain, times(1)).nextCall(any()); // 骨架期:context 在场也不得续跑
        assertThat(ctx.inbox().drain()).as("空 inbox 不得被伪造出注入").isEmpty();
    }

    // ==================== S1:真 DefaultChatClient 主链恰好一个 ToolAdvisor ====================

    @Test
    @DisplayName("S1:逐请求挂载 advisor 后真流式链恰好一个 ToolAdvisor,stream 不抛(抑制 auto-register)")
    void real_client_stream_has_exactly_one_tool_advisor() throws Exception {
        StubChatModel model = new StubChatModel();
        ChatClient client = ChatClient.builder(model).build();

        // 模拟 DefaultChat.stream 的挂载形态:per-request advisor + CONVERSATION_ID param;
        // 本 task 无 StreamRunContext attribute → advisor context null → 纯 super(A1)
        ChatClient.ChatClientRequestSpec spec = client.prompt().user("hi")
                .advisors(LoomAgenticLoopAdvisor.builder().build())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "conv-s1"));

        String content = spec.stream().content().blockFirst(TIMEOUT);

        assertThat(content).isEqualTo("pong");
        // validateSingleToolAdvisor 未抛(走到这里就说明 ≤1);再精确数:恰好 1 且是本类
        List<Advisor> toolAdvisors = advisorsOf(spec).stream()
                .filter(a -> a instanceof ToolAdvisor).toList();
        assertThat(toolAdvisors).hasSize(1);
        assertThat(toolAdvisors.get(0)).isInstanceOf(LoomAgenticLoopAdvisor.class);
        // 模型只被调 1 次:双 ToolAdvisor 嵌套会导致轮次翻车的形态在此锁死
        assertThat(model.streamCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("S1:同步 call() 路径同样恰好一个 ToolAdvisor(与 stream 同一 buildAdvisorChain 逻辑)")
    void real_client_call_has_exactly_one_tool_advisor() throws Exception {
        StubChatModel model = new StubChatModel();
        ChatClient client = ChatClient.builder(model).build();

        ChatClient.ChatClientRequestSpec spec = client.prompt().user("hi")
                .advisors(LoomAgenticLoopAdvisor.builder().build())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "conv-s1"));

        ChatResponse resp = spec.call().chatResponse();

        assertThat(resp.getResult().getOutput().getText()).isEqualTo("pong");
        List<Advisor> toolAdvisors = advisorsOf(spec).stream()
                .filter(a -> a instanceof ToolAdvisor).toList();
        assertThat(toolAdvisors).hasSize(1);
        assertThat(toolAdvisors.get(0)).isInstanceOf(LoomAgenticLoopAdvisor.class);
        assertThat(model.callCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("A5 对照:不挂 advisor 的真链由 auto-register 补默认 ToolCallingAdvisor(非本类,子任务路径形态)")
    void real_client_without_advisor_gets_plain_default_tool_calling_advisor() throws Exception {
        StubChatModel model = new StubChatModel();
        ChatClient client = ChatClient.builder(model).build();

        ChatClient.ChatClientRequestSpec spec = client.prompt().user("hi")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "conv-sub"));

        spec.call().chatResponse();

        List<Advisor> toolAdvisors = advisorsOf(spec).stream()
                .filter(a -> a instanceof ToolAdvisor).toList();
        assertThat(toolAdvisors).hasSize(1);
        // 默认注册的裸 ToolCallingAdvisor —— 子任务链应保持这一形态(不继承 loop advisor)
        assertThat(toolAdvisors.get(0)).isExactlyInstanceOf(ToolCallingAdvisor.class);
    }

    // ==================== helpers ====================

    /** 反射读 DefaultChatClientRequestSpec 的私有 advisors 链(2.0.2 字段名钉死于源 jar)。 */
    @SuppressWarnings("unchecked")
    private static List<Advisor> advisorsOf(ChatClient.ChatClientRequestSpec spec) throws Exception {
        Field f = spec.getClass().getDeclaredField("advisors");
        f.setAccessible(true);
        return (List<Advisor>) f.get(spec);
    }

    private static ChatClientRequest requestWith(Map<String, Object> context) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage("hi"))))
                .context(context)
                .build();
    }

    /** options 必须是 ToolCallingChatOptions —— 否则 ToolCallingAdvisor 直接 skip,测不到循环。 */
    private static ChatClientRequest requestWithToolCalling(Map<String, Object> context) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage("hi")), ToolCallingChatOptions.builder().build()))
                .context(context)
                .build();
    }

    private static ChatClientResponse responseWith(String text) {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))))
                .context(Map.of())
                .build();
    }

    private static ToolExecutionResult toolExecutionResult() {
        return ToolExecutionResult.builder()
                .conversationHistory(List.of(
                        new UserMessage("q"),
                        new AssistantMessage("a"),
                        new AssistantMessage("tool-result")))
                .build();
    }

    private static MemoryWriter noopWriter() {
        return new MemoryWriter() {
            @Override
            public void writeUser(Message m) {
            }

            @Override
            public void writeAssistant(Message m) {
            }
        };
    }

    /** 只回纯文本("pong")的桩模型:计数 call()/stream();options 用 ToolCallingChatOptions 让 advisor 进入循环。 */
    private static final class StubChatModel implements ChatModel {
        final AtomicInteger callCalls = new AtomicInteger();
        final AtomicInteger streamCalls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            callCalls.incrementAndGet();
            return pong();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streamCalls.incrementAndGet();
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
