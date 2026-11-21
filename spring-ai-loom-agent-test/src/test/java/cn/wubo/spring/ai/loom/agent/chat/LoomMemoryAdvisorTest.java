package cn.wubo.spring.ai.loom.agent.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.MemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LoomMemoryAdvisor} 单测(spec 2026-11-21 D-1 / A1 锁)。
 *
 * <p>锁定四条不变式:
 * <ol>
 *   <li>order = HIGHEST_PRECEDENCE+200,严格上游于 ToolCallingAdvisor(+300)
 *       —— Issue1 根因锁(spec §5.10)。</li>
 *   <li>implements {@link MemoryAdvisor} —— DefaultChatClient auto-register
 *       的 instanceof 探测依赖它。</li>
 *   <li>before() 写 turn-initial USER(与 MessageChatMemoryAdvisor 逐行为一致)。</li>
 *   <li>after() 条件写:context 无 StreamRunContext(子任务 .call() / legacy)
 *       → 单写聚合 ASSISTANT(A1 延伸);有 StreamRunContext 且 perIterationMode
 *       → 整段跳过(逐迭代行已由 loop advisor 写,防双写)。</li>
 * </ol>
 */
class LoomMemoryAdvisorTest {

    private static final String CONV_ID = "conv-1";

    @Test
    void order_upstream_of_tool_calling() {
        assertThat(new LoomMemoryAdvisor(mock(ChatMemory.class)).getOrder())
                .isLessThan(Ordered.HIGHEST_PRECEDENCE + 300);
    }

    @Test
    void implements_MemoryAdvisor() {
        assertThat(new LoomMemoryAdvisor(mock(ChatMemory.class))).isInstanceOf(MemoryAdvisor.class);
    }

    @Test
    void before_writes_turn_initial_user() {
        ChatMemory mem = mock(ChatMemory.class);
        when(mem.get(CONV_ID)).thenReturn(List.of());
        LoomMemoryAdvisor a = new LoomMemoryAdvisor(mem);
        ChatClientRequest req = requestWithUser("hi", CONV_ID);
        a.before(req, mock(AdvisorChain.class));
        verify(mem).add(eq(CONV_ID), argThat((Message m) -> m instanceof UserMessage));
    }

    @Test
    void after_writes_single_assistant_when_no_context() {
        // 子任务/legacy 路径:无 StreamRunContext → after 写一条 ASSISTANT(A1 延伸)
        ChatMemory mem = mock(ChatMemory.class);
        new LoomMemoryAdvisor(mem).after(responseWithAssistant("ok"), mock(AdvisorChain.class));
        verify(mem).add(eq(CONV_ID), argThat((List<Message> list) -> list.size() == 1
                && list.get(0) instanceof AssistantMessage));
    }

    @Test
    void after_skips_when_perIterationMode() {
        // context 含 StreamRunContext 且 markPerIterationMode → after 不写(防双写)
        ChatMemory mem = mock(ChatMemory.class);
        StreamRunContext c = ctx();
        c.markPerIterationMode();
        ChatClientResponse resp = responseWithAssistantAndContext("ok", c);
        ChatClientResponse returned = new LoomMemoryAdvisor(mem).after(resp, mock(AdvisorChain.class));
        verify(mem, never()).add(anyString(), anyList());
        verify(mem, never()).add(anyString(), any(Message.class));
        assertThat(returned).isSameAs(resp); // 跳过路径必须原样透传 response,不改写链上数据
    }

    @Test
    void after_writes_when_streamRunContext_present_but_not_perIteration() {
        // StreamRunContext 在场但 loop advisor 尚未 mark(主对话 Task 6 接线前的中间态、
        // 或 loop 在第 0 迭代前 abort)→ 仍按 legacy 单写,记忆不得静默丢失
        ChatMemory mem = mock(ChatMemory.class);
        StreamRunContext c = ctx(); // perIterationMode 默认 false
        new LoomMemoryAdvisor(mem).after(responseWithAssistantAndContext("ok", c), mock(AdvisorChain.class));
        verify(mem).add(eq(CONV_ID), argThat((List<Message> list) -> list.size() == 1));
    }

    // ==================== helpers ====================

    private static ChatClientRequest requestWithUser(String text, String conversationId) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage(text)))
                .context(Map.of(ChatMemory.CONVERSATION_ID, conversationId))
                .build();
    }

    private static ChatClientResponse responseWithAssistant(String text) {
        return responseWithAssistantAndContext(text, null);
    }

    private static ChatClientResponse responseWithAssistantAndContext(String text, StreamRunContext runContext) {
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))));
        ChatClientResponse.Builder builder = ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(Map.of(ChatMemory.CONVERSATION_ID, CONV_ID));
        if (runContext != null) {
            builder = builder.context(StreamRunContext.CONTEXT_KEY, runContext);
        }
        return builder.build();
    }

    /** 与 StreamRunContextTest 同款测试构造:memory/ack/iteration no-op,stopSignal 恒 false。 */
    private static StreamRunContext ctx() {
        return new StreamRunContext(new SteeringInbox(), new MemoryWriter() {
            @Override
            public void writeUser(Message m) {
            }

            @Override
            public void writeAssistant(Message m) {
            }
        }, s -> {
        }, s -> {
        }, i -> {
        }, () -> false);
    }
}
