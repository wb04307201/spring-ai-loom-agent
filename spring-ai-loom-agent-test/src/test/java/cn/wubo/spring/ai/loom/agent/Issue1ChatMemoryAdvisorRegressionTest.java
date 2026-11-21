package cn.wubo.spring.ai.loom.agent;

import org.junit.jupiter.api.Test;
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
 * <p>Fix (2026-09-30): switch the bean to Spring AI 2.0's
 * {@code MessageChatMemoryAdvisor} (default order
 * = {@code Ordered.HIGHEST_PRECEDENCE + 200}, UPSTREAM of ToolCallingAdvisor's
 * default +300). The auto-register then sees MemoryAdvisor upstream,
 * keeps {@code conversationHistoryEnabled=true}, and the full conversation
 * history (system + user + assistant tool_use + tool_result) is sent on every
 * iteration.
 *
 * <p><b>Spec-authorized contract evolution (2026-11-21, D-1 / ruling R1)</b>:
 * the bean is now Loom's own {@code LoomMemoryAdvisor} (upstream
 * {@code MessageChatMemoryAdvisor} is final and cannot take over the
 * per-iteration ASSISTANT write the agentic loop needs). This test therefore
 * pins the <b>invariant</b> that actually caused Issue #1 — order upstream of
 * ToolCallingAdvisor + {@code MemoryAdvisor} marker — rather than the exact
 * class name. {@code LoomMemoryAdvisor} satisfies both (verified against
 * Spring AI 2.0.2 sources). Behavior parity of the no-context path is locked
 * by {@code LoomMemoryAdvisorTest} + {@code DefaultSubTaskExecutorTest}.
 *
 * <p>What this test pins down:
 * <ol>
 *   <li>The injected advisor bean implements {@code MemoryAdvisor} (required
 *       for the auto-register heuristic to see it).</li>
 *   <li>Its order is strictly less than {@code Ordered.HIGHEST_PRECEDENCE + 300}
 *       (the default ToolCallingAdvisor order), so it sits upstream and
 *       {@code hasDownstreamMemoryAdvisor=false}.</li>
 *   <li>Anti-revert: the bean is NOT the old {@code LastChunkMessageChatMemoryAdvisor}
 *       (order=0, downstream MemoryAdvisor) — if anyone wires it back, Issue #1
 *       returns and this fails.</li>
 * </ol>
 */
@SpringBootTest
class Issue1ChatMemoryAdvisorRegressionTest {

    @Autowired
    @Qualifier("messageChatMemoryAdvisor")
    private MemoryAdvisor messageChatMemoryAdvisor;

    @Test
    void messageChatMemoryAdvisor_is_not_legacy_last_chunk_advisor() {
        // Anti-revert guard (R1): LastChunkMessageChatMemoryAdvisor (order=0, downstream)
        // must never be wired back. Checked by class simple name so the assertion still
        // compiles after the obsolete class was deleted from this repository.
        assertThat(messageChatMemoryAdvisor.getClass().getSimpleName())
                .as("LastChunkMessageChatMemoryAdvisor (order=0) would re-introduce Issue #1")
                .isNotEqualTo("LastChunkMessageChatMemoryAdvisor");
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
                .as("memory advisor order (%d) must be upstream of ToolCallingAdvisor default order (%d) so Spring AI 2.0 keeps ToolCallingAdvisor.conversationHistoryEnabled=true.",
                        advisorOrder, toolCallingAdvisorDefaultOrder)
                .isLessThan(toolCallingAdvisorDefaultOrder);
    }
}
