package cn.wubo.spring.ai.loom.agent.askuser;

import cn.wubo.spring.ai.loom.agent.model.AskUserEvent;
import cn.wubo.spring.ai.loom.agent.model.AskUserOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AskUserRegistryTest {

    private AskUserRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AskUserRegistry();
    }

    private AskUserRegistry.PendingQuestion pending(String qid, String user, String conv) {
        AskUserEvent ev = new AskUserEvent(qid, "问题", null, null,
                List.of(new AskUserOption("A", null), new AskUserOption("B", null)),
                false, false, 300L);
        return new AskUserRegistry.PendingQuestion(ev, user, conv,
                new CompletableFuture<>(), System.currentTimeMillis());
    }

    @Test
    void registerThenAnswerCompletesFuture() throws Exception {
        registry.register(pending("q-1", "alice", "conv-1"));
        boolean ok = registry.answer("q-1", "alice", "A");
        assertThat(ok).isTrue();
        assertThat(registry.get("q-1").answer().get(1, TimeUnit.SECONDS)).isEqualTo("A");
    }

    @Test
    void answerUnknownQuestionIdReturnsFalse() {
        assertThat(registry.answer("nope", "alice", "A")).isFalse();
        assertThat(registry.answer(null, "alice", "A")).isFalse();
    }

    @Test
    void answerWrongUsernameReturnsFalseAndDoesNotComplete() {
        registry.register(pending("q-2", "alice", "conv-1"));
        assertThat(registry.answer("q-2", "mallory", "A")).isFalse();
        assertThat(registry.get("q-2").answer().isDone()).isFalse();
    }

    @Test
    void secondAnswerReturnsFalseAfterFirstCompletes() {
        registry.register(pending("q-3", "alice", "conv-1"));
        assertThat(registry.answer("q-3", "alice", "A")).isTrue();
        assertThat(registry.answer("q-3", "alice", "B")).isFalse();
    }

    @Test
    void cancelAllCompletesSentinelAndClears() throws Exception {
        registry.register(pending("q-4", "alice", "conv-1"));
        CompletableFuture<String> future = registry.get("q-4").answer();
        int n = registry.cancelAll("alice", "conv-1");
        assertThat(n).isEqualTo(1);
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo(AskUserRegistry.CANCELLED_SENTINEL);
        assertThat(registry.get("q-4")).isNull();
    }

    @Test
    void cancelAllIgnoresOtherUserAndConversation() {
        registry.register(pending("q-5", "alice", "conv-1"));
        registry.register(pending("q-6", "bob", "conv-1"));
        registry.register(pending("q-7", "alice", "conv-2"));
        int n = registry.cancelAll("alice", "conv-1");
        assertThat(n).isEqualTo(1);
        assertThat(registry.get("q-5")).isNull();
        assertThat(registry.get("q-6")).isNotNull();
        assertThat(registry.get("q-7")).isNotNull();
    }

    @Test
    void removeIsIdempotentAndNullSafe() {
        registry.register(pending("q-8", "alice", "conv-1"));
        registry.remove("q-8");
        registry.remove("q-8");
        registry.remove(null);
        assertThat(registry.get("q-8")).isNull();
    }
}
