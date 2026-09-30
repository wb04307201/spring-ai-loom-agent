package cn.wubo.spring.ai.loom.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.MemoryAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression lock for Issue #1 (2026-09-30): Spring AI 2.0 multi-turn tool_use
 * hallucination bug.
 *
 * <p>Root cause: Loom wired {@code LastChunkMessageChatMemoryAdvisor} (extends
 * {@code BaseChatMemoryAdvisor extends MemoryAdvisor}) with order=0. Spring AI
 * 2.0's {@code DefaultChatClient.autoRegisterToolCallingAdvisor} detected a
 * "downstream MemoryAdvisor" and set
 * {@code ToolCallingAdvisor.conversationHistoryEnabled=false}, dropping the
 * original user question and assistant tool_use between iterations. The LLM
 * hallucinated generic capability-list answers instead of responding to the
 * user.
 *
 * <p>Fix: switch the bean to Spring AI 2.0's
 * {@code MessageChatMemoryAdvisor} (default order
 * = {@code Ordered.HIGHEST_PRECEDENCE + 200}, UPSTREAM of ToolCallingAdvisor's
 * default +300). The auto-register then sees MemoryAdvisor upstream,
 * keeps {@code conversationHistoryEnabled=true}, and the full conversation
 * history (system + user + assistant tool_use + tool_result) is sent on every
 * iteration.
 *
 * <p>What this test pins down:
 * <ol>
 *   <li>The injected advisor bean is Spring AI's
 *       {@code MessageChatMemoryAdvisor}, not Loom's old
 *       {@code LastChunkMessageChatMemoryAdvisor}.</li>
 *   <li>It implements {@code MemoryAdvisor} (required for the
 *       auto-register heuristic to see it).</li>
 *   <li>Its order is strictly less than {@code Ordered.HIGHEST_PRECEDENCE + 300}
 *       (the default ToolCallingAdvisor order), so it sits upstream and
 *       {@code hasDownstreamMemoryAdvisor=false}.</li>
 * </ol>
 *
 * <p>If anyone reverts to {@code LastChunkMessageChatMemoryAdvisor} (order=0,
 * a downstream MemoryAdvisor), this test fails and Issue #1 returns.
 */
@SpringBootTest
class Issue1ChatMemoryAdvisorRegressionTest {

    @Autowired
    @Qualifier("messageChatMemoryAdvisor")
    private MessageChatMemoryAdvisor messageChatMemoryAdvisor;

    @Test
    void messageChatMemoryAdvisor_is_spring_ai_default() {
        // Custom Loom advisor (LastChunkMessageChatMemoryAdvisor) must NOT be wired back.
        // Use class name string check so the test still compiles after the obsolete class is deleted.
        assertThat(messageChatMemoryAdvisor.getClass().getName())
                .as("must be Spring AI's MessageChatMemoryAdvisor, not Loom's custom advisor")
                .isEqualTo("org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor");
    }

    @Test
    void messageChatMemoryAdvisor_implements_MemoryAdvisor() {
        // ToolCallingAdvisor auto-register uses `instanceof MemoryAdvisor` to detect downstream history owners.
        assertThat(messageChatMemoryAdvisor).isInstanceOf(MemoryAdvisor.class);
    }

    @Test
    void messageChatMemoryAdvisor_order_is_upstream_of_tool_calling_advisor() {
        // ToolCallingAdvisor default order = Ordered.HIGHEST_PRECEDENCE + 300.
        // Our memory advisor must have order < that, otherwise Spring AI 2.0 sees it as downstream and disables ToolCallingAdvisor's history.
        int advisorOrder = messageChatMemoryAdvisor.getOrder();
        int toolCallingAdvisorDefaultOrder = Ordered.HIGHEST_PRECEDENCE + 300;
        assertThat(advisorOrder)
                .as("MessageChatMemoryAdvisor order (%d) must be upstream of ToolCallingAdvisor default order (%d) so Spring AI 2.0 keeps ToolCallingAdvisor.conversationHistoryEnabled=true.",
                        advisorOrder, toolCallingAdvisorDefaultOrder)
                .isLessThan(toolCallingAdvisorDefaultOrder);
    }
}
