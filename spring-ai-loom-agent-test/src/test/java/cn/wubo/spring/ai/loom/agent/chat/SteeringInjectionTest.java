package cn.wubo.spring.ai.loom.agent.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 7(spec 2026-11-21 agentic-loop-steering)内层 steering 注入回归锁。
 *
 * <p>锁定语义:
 * <ul>
 *   <li><b>A4 注入位置</b>:steering USER 行在 super 的 tool_result 之后、下一轮模型
 *       调用之前 append 进 instructions(消息序列 [...assistant(tool_call), tool_result,
 *       USER(steering)]);</li>
 *   <li>注入即 {@code ctx.memory().writeUser}(USER 行落库,文本=steering 原文)+
 *       {@code ctx.emitAck(messageId)}(前端灰态转正);</li>
 *   <li><b>A1</b>:inbox 空 → 指令序列与 super 完全一致(同实例),无 ack、无 memory 写;</li>
 *   <li>D-3 内层查超时 + stop:触发则不注入(残留留 inbox 交外层收口可见处置)。</li>
 * </ul>
 * call 与 stream 两个钩子对称锁定。变异:去掉内层 drain ⇒ inject 用例红。
 */
@DisplayName("Task 7:内层 steering 注入(A4 tool_result 后 + ack + USER 行 + A1 空 inbox)")
class SteeringInjectionTest {

    private static final String CONV = "conv-inject";

    // ==================== fixtures ====================

    private record CtxFixture(StreamRunContext ctx,
                              List<Message> userRows,
                              List<String> acks,
                              List<String> terminalAcks,
                              AtomicBoolean stopped) {
    }

    private static CtxFixture fixture() {
        List<Message> userRows = new ArrayList<>();
        List<String> acks = new ArrayList<>();
        List<String> terminalAcks = new ArrayList<>();
        AtomicBoolean stopped = new AtomicBoolean(false);
        StreamRunContext ctx = new StreamRunContext(
                new SteeringInbox(),
                new MemoryWriter() {
                    @Override
                    public void writeUser(Message m) {
                        userRows.add(m);
                    }

                    @Override
                    public void writeAssistant(Message m) {
                    }
                },
                s -> {
                },
                acks::add,
                i -> {
                },
                stopped::get,
                terminalAcks::add);
        return new CtxFixture(ctx, userRows, acks, terminalAcks, stopped);
    }

    /** super(conversationHistoryEnabled=true)返回的 history:tool_result 收尾。 */
    private static ToolExecutionResult toolResultWithHistory() {
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("tc-1", "function", "noop", "{}")))
                .build();
        ToolResponseMessage toolResult = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("tc-1", "noop", "ok")))
                .build();
        return ToolExecutionResult.builder()
                .conversationHistory(List.of(
                        new SystemMessage("sys"),
                        new UserMessage("原任务"),
                        toolCall,
                        toolResult))
                .build();
    }

    private static ChatClientRequest requestWith(StreamRunContext ctx) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage("原任务")), ToolCallingChatOptions.builder().build()))
                .context(Map.of(StreamRunContext.CONTEXT_KEY, ctx))
                .build();
    }

    private static ChatClientResponse responseWith(StreamRunContext ctx) {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("x")))))
                .context(Map.of(StreamRunContext.CONTEXT_KEY, ctx))
                .build();
    }

    // ==================== A4:注入位置(call + stream) ====================

    @Test
    @DisplayName("A4:call 钩子 —— USER(steering) 在 tool_result 之后 append,前缀与 super 完全一致")
    void inject_after_tool_result_before_next_model_call() {
        CtxFixture f = fixture();
        assertThat(f.ctx().inbox().offer("m-1", "先看看部署状态")).isTrue();
        ToolExecutionResult ter = toolResultWithHistory();
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();

        List<Message> out = advisor.doGetNextInstructionsForToolCall(
                requestWith(f.ctx()), responseWith(f.ctx()), ter);

        List<Message> superHistory = ter.conversationHistory();
        assertThat(out).hasSize(superHistory.size() + 1);
        assertThat(out.subList(0, superHistory.size()))
                .as("super 指令序列原样保留(Issue1 history 全量)")
                .containsExactlyElementsOf(superHistory);
        assertThat(out.get(superHistory.size() - 1))
                .as("A4 前提:super history 以 tool_result 收尾")
                .isInstanceOf(ToolResponseMessage.class);
        Message injected = out.get(out.size() - 1);
        assertThat(injected.getMessageType()).as("A4:steering 是 USER 行").isEqualTo(MessageType.USER);
        assertThat(injected.getText()).isEqualTo("先看看部署状态");
    }

    @Test
    @DisplayName("A4:stream 钩子对称 —— USER(steering) 同样在 tool_result 之后")
    void inject_after_tool_result_stream_variant() {
        CtxFixture f = fixture();
        f.ctx().inbox().offer("m-1", "改用 Docker 部署");
        ToolExecutionResult ter = toolResultWithHistory();
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();

        List<Message> out = advisor.doGetNextInstructionsForToolCallStream(
                requestWith(f.ctx()), responseWith(f.ctx()), ter);

        assertThat(out).hasSize(ter.conversationHistory().size() + 1);
        assertThat(out.get(out.size() - 1).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(out.get(out.size() - 1).getText()).isEqualTo("改用 Docker 部署");
        assertThat(f.acks()).containsExactly("m-1");
    }

    @Test
    @DisplayName("多条 steering 按 FIFO 注入,每条各一 USER 行 + 各一 ack")
    void multiple_messages_injected_fifo() {
        CtxFixture f = fixture();
        f.ctx().inbox().offer("m-1", "第一条");
        f.ctx().inbox().offer("m-2", "第二条 🚀");
        ToolExecutionResult ter = toolResultWithHistory();
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();

        List<Message> out = advisor.doGetNextInstructionsForToolCall(
                requestWith(f.ctx()), responseWith(f.ctx()), ter);

        assertThat(out.subList(ter.conversationHistory().size(), out.size()))
                .extracting(Message::getText).containsExactly("第一条", "第二条 🚀");
        assertThat(f.acks()).containsExactly("m-1", "m-2");
        assertThat(f.ctx().inbox().depth()).isZero();
    }

    // ==================== ack + memory ====================

    @Test
    @DisplayName("注入即 emitAck(messageId) —— 前端灰态转正通道")
    void emitAck_on_injection() {
        CtxFixture f = fixture();
        f.ctx().inbox().offer("ack-me", "补充说明");
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();

        advisor.doGetNextInstructionsForToolCall(
                requestWith(f.ctx()), responseWith(f.ctx()), toolResultWithHistory());

        assertThat(f.acks()).containsExactly("ack-me");
        assertThat(f.terminalAcks()).as("注入成功不得发未注入终帧").isEmpty();
    }

    @Test
    @DisplayName("注入即 ctx.memory().writeUser,文本 = steering 原文(emoji 完整)")
    void writes_steering_user_row_via_memory() {
        CtxFixture f = fixture();
        f.ctx().inbox().offer("m-1", "🚀部署到生产");
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();

        advisor.doGetNextInstructionsForToolCallStream(
                requestWith(f.ctx()), responseWith(f.ctx()), toolResultWithHistory());

        assertThat(f.userRows()).hasSize(1);
        assertThat(f.userRows().get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(f.userRows().get(0).getText()).isEqualTo("🚀部署到生产");
    }

    // ==================== A1:空 inbox ====================

    @Test
    @DisplayName("A1:inbox 空 → 指令序列与 super 完全一致(同实例),无 ack、无 memory 写")
    void empty_inbox_no_injection_no_ack() {
        CtxFixture f = fixture();
        ToolExecutionResult ter = toolResultWithHistory();
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();

        List<Message> out = advisor.doGetNextInstructionsForToolCall(
                requestWith(f.ctx()), responseWith(f.ctx()), ter);
        List<Message> outStream = advisor.doGetNextInstructionsForToolCallStream(
                requestWith(f.ctx()), responseWith(f.ctx()), ter);

        assertThat(out).isSameAs(ter.conversationHistory());
        assertThat(outStream).isSameAs(ter.conversationHistory());
        assertThat(f.acks()).isEmpty();
        assertThat(f.userRows()).isEmpty();
    }

    @Test
    @DisplayName("A1:ctx 缺席(子任务路径)→ 纯 super,即使 inbox 概念不存在也不炸")
    void null_context_pure_super() {
        ToolExecutionResult ter = toolResultWithHistory();
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();
        ChatClientRequest req = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage("q")), ToolCallingChatOptions.builder().build()))
                .context(Map.of())
                .build();
        ChatClientResponse resp = ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("x")))))
                .context(Map.of())
                .build();

        assertThat(advisor.doGetNextInstructionsForToolCall(req, resp, ter))
                .isSameAs(ter.conversationHistory());
        assertThat(advisor.doGetNextInstructionsForToolCallStream(req, resp, ter))
                .isSameAs(ter.conversationHistory());
    }

    // ==================== D-3 内层护栏 ====================

    @Test
    @DisplayName("D-3:stop 信号 → 内层不注入,消息留 inbox(交外层收口可见处置)")
    void stop_signal_blocks_inner_injection() {
        CtxFixture f = fixture();
        f.ctx().inbox().offer("m-1", "停一下");
        f.stopped().set(true);
        ToolExecutionResult ter = toolResultWithHistory();
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();

        List<Message> out = advisor.doGetNextInstructionsForToolCall(
                requestWith(f.ctx()), responseWith(f.ctx()), ter);

        assertThat(out).isSameAs(ter.conversationHistory());
        assertThat(f.acks()).isEmpty();
        assertThat(f.userRows()).isEmpty();
        assertThat(f.ctx().inbox().depth()).as("消息未被吞,留待收口终帧").isEqualTo(1);
    }

    @Test
    @DisplayName("D-3:run 活跃超时 → 内层不注入(runTimeoutSeconds=0 即刻触顶)")
    void run_timeout_blocks_inner_injection() {
        CtxFixture f = fixture();
        f.ctx().inbox().offer("m-1", "太晚了");
        ToolExecutionResult ter = toolResultWithHistory();
        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder()
                .runTimeoutSeconds(0)
                .build();

        List<Message> out = advisor.doGetNextInstructionsForToolCallStream(
                requestWith(f.ctx()), responseWith(f.ctx()), ter);

        assertThat(out).isSameAs(ter.conversationHistory());
        assertThat(f.acks()).isEmpty();
        assertThat(f.ctx().inbox().depth()).isEqualTo(1);
    }
}