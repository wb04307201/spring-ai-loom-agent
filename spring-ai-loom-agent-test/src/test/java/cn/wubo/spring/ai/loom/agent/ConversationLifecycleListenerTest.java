package cn.wubo.spring.ai.loom.agent;

import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.TaskInfo;
import cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Verifies {@link LoomAgentConfiguration#cleanupConversationResources} — the logic run
 * by DELETE /spring/ai/loom/conversation/{id} before soft-deleting the mapping.
 */
class ConversationLifecycleListenerTest {

    private SubTaskRegistry registry;
    private FlexScheduledTaskService flexService;

    @BeforeEach
    void setUp() {
        registry = new SubTaskRegistry(8, 100);
        flexService = mock(FlexScheduledTaskService.class);
        when(flexService.listTasks()).thenReturn(List.of(
                new TaskInfo("loom-sched-alice-conv-1-remind", "FIXED_DELAY", null),
                new TaskInfo("loom-sched-alice-conv-2-other", "FIXED_DELAY", null),
                new TaskInfo("loom-sched-bob-conv-1-foo", "CRON", null)
        ));
    }

    @Test
    void deleteConvStopsMatchingSubtasksAndSchedules() {
        String id = registry.register("alice", "conv-1", "p1");
        CompletableFuture<?> f = new CompletableFuture<>();
        registry.attachFuture(id, f);

        // No persistence repositories wired → all four persistence counters are 0.
        int[] cleaned = LoomAgentConfiguration.cleanupConversationResources(
                "conv-1", "alice", registry, flexService, null, null, null);

        // 1 subtask killed, 1 schedule cancelled (only alice/conv-1), no persisted rows;
        // the just-cancelled task is also removed from the in-memory history deque.
        assertThat(cleaned).containsExactly(1, 1, 0, 0, 0, 1);
        assertThat(f.isCancelled()).isTrue();
        verify(flexService).cancel("loom-sched-alice-conv-1-remind");
        verify(flexService, never()).cancel("loom-sched-alice-conv-2-other");
        verify(flexService, never()).cancel("loom-sched-bob-conv-1-foo");
    }

    @Test
    void toleratesMissingSubtaskRegistryAndSchedule() {
        // Both optional dependencies absent — no exception, zero counts.
        int[] cleaned = LoomAgentConfiguration.cleanupConversationResources(
                "conv-1", "alice", null, null, null, null, null);
        assertThat(cleaned).containsExactly(0, 0, 0, 0, 0, 0);
    }

    @Test
    void doesNotCancelOtherUsersSchedulesForSameConvName() {
        int[] cleaned = LoomAgentConfiguration.cleanupConversationResources(
                "conv-1", "bob", null, flexService, null, null, null);
        // Only bob's conv-1 schedule cancelled, not alice's.
        assertThat(cleaned).containsExactly(0, 1, 0, 0, 0, 0);
        verify(flexService).cancel("loom-sched-bob-conv-1-foo");
        verify(flexService, never()).cancel("loom-sched-alice-conv-1-remind");
    }
}
