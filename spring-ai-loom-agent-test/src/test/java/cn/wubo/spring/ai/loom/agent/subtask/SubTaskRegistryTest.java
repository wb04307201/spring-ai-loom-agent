package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class SubTaskRegistryTest {

    private SubTaskRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SubTaskRegistry(8, 100);
    }

    @Test
    void registerAndQuery() {
        String id = registry.register("alice", "conv-1", "do X");

        assertThat(id).isNotBlank();
        SubTaskRegistry.SubTaskRecord r = registry.get(id);
        assertThat(r.username()).isEqualTo("alice");
        assertThat(r.conversationId()).isEqualTo("conv-1");
        assertThat(r.prompt()).isEqualTo("do X");
        assertThat(r.status()).isEqualTo(SubTaskStatus.RUNNING);
        assertThat(r.startedAt()).isGreaterThan(0L);
    }

    @Test
    void markFinishedMovesToHistory() {
        String id = registry.register("alice", "conv-1", "p");
        registry.markFinished(id, SubTaskStatus.COMPLETED, "result", null);

        assertThat(registry.get(id).status()).isEqualTo(SubTaskStatus.COMPLETED);
        assertThat(registry.get(id).resultText()).isEqualTo("result");
        // Same id should appear in history listing
        assertThat(registry.listHistory("alice", 10))
                .extracting(SubTaskRegistry.SubTaskRecord::subTaskId)
                .contains(id);
    }

    @Test
    void listActiveFilteredByUsername() {
        registry.register("alice", "conv-1", "p1");
        registry.register("alice", "conv-2", "p2");
        registry.register("bob",   "conv-3", "p3");

        assertThat(registry.listActive("alice")).hasSize(2);
        assertThat(registry.listActive("bob")).hasSize(1);
        assertThat(registry.listActive("nonexistent")).isEmpty();
    }

    @Test
    void killAllByConversationCancelsPendingFuturesAndCountsCancelled() {
        String id1 = registry.register("alice", "conv-1", "p1");
        String id2 = registry.register("alice", "conv-1", "p2");
        String id3 = registry.register("alice", "conv-2", "p3");

        CompletableFuture<?> f1 = new CompletableFuture<>();
        CompletableFuture<?> f2 = new CompletableFuture<>();
        registry.attachFuture(id1, f1);
        registry.attachFuture(id2, f2);

        int killed = registry.killAllByConversation("conv-1");

        assertThat(killed).isEqualTo(2);
        assertThat(f1.isCancelled()).isTrue();
        assertThat(f2.isCancelled()).isTrue();
        assertThat(registry.get(id1).status()).isEqualTo(SubTaskStatus.CANCELLED);
        assertThat(registry.get(id3).status()).isEqualTo(SubTaskStatus.RUNNING);  // untouched
    }

    @Test
    void killByIdCancelsAndMarksCancelled() {
        String id = registry.register("alice", "conv-1", "p");
        CompletableFuture<?> f = new CompletableFuture<>();
        registry.attachFuture(id, f);

        boolean ok = registry.kill("alice", id);

        assertThat(ok).isTrue();
        assertThat(f.isCancelled()).isTrue();
        assertThat(registry.get(id).status()).isEqualTo(SubTaskStatus.CANCELLED);
    }

    @Test
    void historyIsBounded() {
        registry = new SubTaskRegistry(2, 3);
        for (int i = 0; i < 5; i++) {
            String id = registry.register("alice", "conv", "p" + i);
            registry.markFinished(id, SubTaskStatus.COMPLETED, "r" + i, null);
        }
        assertThat(registry.listHistory("alice", 100)).hasSize(3);
    }

    @Test
    void maxConcurrentLimitsActiveRegistration() {
        registry = new SubTaskRegistry(2, 100);
        registry.register("alice", "c1", "p1");
        registry.register("alice", "c2", "p2");

        try {
            registry.register("alice", "c3", "p3");
            // expect IllegalStateException — but AssertJ .isInstanceOf works for the message
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> registry.register("alice", "c4", "p4"));
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    void gettersOnMissingReturnNullOrEmpty() {
        assertThat(registry.get(UUID.randomUUID().toString())).isNull();
        assertThat(registry.kill("alice", "nonexistent")).isFalse();
    }

    @Test
    void killRejectsDifferentUsernameWithoutCancelling() {
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        registry = new SubTaskRegistry(8, 100, id -> callCount.incrementAndGet());
        String id = registry.register("alice", "conv-1", "p1");

        boolean killed = registry.kill("bob", id);

        assertThat(killed).isFalse();
        assertThat(callCount.get()).isZero();
        assertThat(registry.get(id).status()).isEqualTo(SubTaskStatus.RUNNING);
    }

    @Test
    void killInvokesCancelHookWithSubTaskId() {
        java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        registry = new SubTaskRegistry(8, 100, id -> {
            captured.set(id);
            callCount.incrementAndGet();
        });

        String id = registry.register("alice", "conv-1", "p1");
        boolean killed = registry.kill("alice", id);

        assertThat(killed).isTrue();
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(captured.get()).isEqualTo(id);
        // After kill(), the record moved to history with status CANCELLED.
        assertThat(registry.get(id).status())
                .isEqualTo(cn.wubo.spring.ai.loom.agent.model.SubTaskStatus.CANCELLED);
    }

    @Test
    void killFallsBackToAttachFutureWhenNoHookRegistered() {
        // No cancel hook — uses legacy attachFuture path. Future is not attached here,
        // so kill() should still mark the record CANCELLED without NPE.
        registry = new SubTaskRegistry(8, 100);   // 2-arg ctor: no hook
        String id = registry.register("alice", "conv-1", "p1");

        boolean killed = registry.kill("alice", id);

        assertThat(killed).isTrue();
        assertThat(registry.get(id).status())
                .isEqualTo(cn.wubo.spring.ai.loom.agent.model.SubTaskStatus.CANCELLED);
    }

    @Test
    void killAllByConversationToleratesNullConversationId() {
        registry = new SubTaskRegistry(8, 100, id -> {});
        registry.register("alice", "conv-1", "p1");

        int n = registry.killAllByConversation(null);

        assertThat(n).isZero();   // defensive — no NPE
    }
}