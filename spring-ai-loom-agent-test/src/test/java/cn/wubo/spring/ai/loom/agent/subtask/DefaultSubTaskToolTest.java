package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DefaultSubTaskToolTest {

    @Test
    void registersRunsAndReportsCompletedResult() {
        SubTaskRegistry registry = new SubTaskRegistry(8, 100);
        ISubTaskExecutor executor = mock(ISubTaskExecutor.class);

        when(executor.execute(any(SubTaskRequest.class))).thenAnswer(inv -> {
            SubTaskRequest req = inv.getArgument(0);
            return SubTaskResult.completed(req, 0L, System.currentTimeMillis(), "ok-text");
        });

        DefaultSubTaskTool tool = new DefaultSubTaskTool(executor, registry);
        ToolContext ctx = new ToolContext(Map.of(
                "username", "alice",
                "parentConversationId", "conv-1"));

        String result = tool.startSubTask("do X", null, ctx);

        assertThat(result).contains("ok-text");
        assertThat(result).contains("conv-1");
        // Registry writes happen INSIDE DefaultSubTaskExecutor.execute() (moved
        // out of this wrapper in Fix E). This test mocks the executor, so the
        // real registry-write path is not exercised here — see
        // SubTaskAndScheduleHistoryIntegrationTest for an end-to-end version.
        verify(executor).execute(any(SubTaskRequest.class));
    }

    @Test
    void reportsFailureAndSaysSoToMain() {
        SubTaskRegistry registry = new SubTaskRegistry(8, 100);
        ISubTaskExecutor executor = mock(ISubTaskExecutor.class);

        when(executor.execute(any(SubTaskRequest.class))).thenAnswer(inv -> {
            SubTaskRequest req = inv.getArgument(0);
            return SubTaskResult.failed(req, 0L, System.currentTimeMillis(), "boom");
        });

        DefaultSubTaskTool tool = new DefaultSubTaskTool(executor, registry);
        ToolContext ctx = new ToolContext(Map.of(
                "username", "alice",
                "parentConversationId", "conv-1"));

        String result = tool.startSubTask("do X", null, ctx);

        assertThat(result).contains("boom");
        assertThat(result).contains("失败");
    }

    @Test
    void reportsCancellationToMain() {
        SubTaskRegistry registry = new SubTaskRegistry(8, 100);
        ISubTaskExecutor executor = mock(ISubTaskExecutor.class);

        when(executor.execute(any(SubTaskRequest.class))).thenAnswer(inv -> {
            SubTaskRequest req = inv.getArgument(0);
            return SubTaskResult.cancelled(req, 0L, System.currentTimeMillis());
        });

        DefaultSubTaskTool tool = new DefaultSubTaskTool(executor, registry);
        ToolContext ctx = new ToolContext(Map.of(
                "username", "alice",
                "parentConversationId", "conv-1"));

        String result = tool.startSubTask("do X", null, ctx);

        assertThat(result).contains("取消");
    }

    @Test
    void listSubTasksShowsActiveTasksForCurrentConversation() {
        SubTaskRegistry registry = new SubTaskRegistry(8, 100, id -> {});
        ISubTaskExecutor executor = mock(ISubTaskExecutor.class);
        DefaultSubTaskTool tool = new DefaultSubTaskTool(executor, registry);

        // Register 2 active tasks in conv-1, 1 in conv-2
        registry.registerWithId("sub-1", "alice", "conv-1", "task 1");
        registry.registerWithId("sub-2", "alice", "conv-1", "task 2");
        registry.registerWithId("sub-3", "alice", "conv-2", "task 3");

        ToolContext ctx1 = new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-1"));
        String result = tool.listSubTasks(ctx1);

        assertThat(result).contains("sub-1");
        assertThat(result).contains("sub-2");
        assertThat(result).doesNotContain("sub-3"); // different conversation
    }

    @Test
    void listSubTasksReturnsEmptyMessageWhenNoneActive() {
        SubTaskRegistry registry = new SubTaskRegistry(8, 100);
        ISubTaskExecutor executor = mock(ISubTaskExecutor.class);
        DefaultSubTaskTool tool = new DefaultSubTaskTool(executor, registry);

        ToolContext ctx = new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-1"));
        String result = tool.listSubTasks(ctx);

        assertThat(result).contains("无运行中的子任务");
    }

    @Test
    void cancelSubTaskRejectsCrossConversationAttempts() {
        SubTaskRegistry registry = new SubTaskRegistry(8, 100, id -> {});
        ISubTaskExecutor executor = mock(ISubTaskExecutor.class);
        DefaultSubTaskTool tool = new DefaultSubTaskTool(executor, registry);

        registry.registerWithId("sub-1", "alice", "conv-1", "task 1");

        // Try to cancel from conv-2 — should fail
        ToolContext ctx2 = new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-2"));
        String result = tool.cancelSubTask("sub-1", ctx2);

        assertThat(result).contains("失败");
        assertThat(result).contains("未找到");
        // Task should still be running
        assertThat(registry.get("sub-1").status()).isEqualTo(SubTaskStatus.RUNNING);
    }

    @Test
    void cancelSubTaskWorksForSameConversation() {
        SubTaskRegistry registry = new SubTaskRegistry(8, 100, id -> {});
        ISubTaskExecutor executor = mock(ISubTaskExecutor.class);
        DefaultSubTaskTool tool = new DefaultSubTaskTool(executor, registry);

        registry.registerWithId("sub-1", "alice", "conv-1", "task 1");

        ToolContext ctx1 = new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-1"));
        String result = tool.cancelSubTask("sub-1", ctx1);

        assertThat(result).contains("已取消");
        assertThat(result).contains("sub-1");
        assertThat(registry.get("sub-1").status()).isEqualTo(SubTaskStatus.CANCELLED);
    }

    @Test
    void cancelSubTaskRejectsUnknownId() {
        SubTaskRegistry registry = new SubTaskRegistry(8, 100);
        ISubTaskExecutor executor = mock(ISubTaskExecutor.class);
        DefaultSubTaskTool tool = new DefaultSubTaskTool(executor, registry);

        ToolContext ctx = new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-1"));
        String result = tool.cancelSubTask("non-existent-id", ctx);

        assertThat(result).contains("失败");
        assertThat(result).contains("未找到");
    }

    @Test
    void cancelSubTaskRejectsBlankId() {
        SubTaskRegistry registry = new SubTaskRegistry(8, 100);
        ISubTaskExecutor executor = mock(ISubTaskExecutor.class);
        DefaultSubTaskTool tool = new DefaultSubTaskTool(executor, registry);

        ToolContext ctx = new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-1"));
        String result = tool.cancelSubTask("", ctx);

        assertThat(result).contains("失败");
    }

    @Test
    void cancelSubTaskRejectsNonRunningTask() {
        SubTaskRegistry registry = new SubTaskRegistry(8, 100, id -> {});
        ISubTaskExecutor executor = mock(ISubTaskExecutor.class);
        DefaultSubTaskTool tool = new DefaultSubTaskTool(executor, registry);

        // Register and complete the task
        registry.registerWithId("sub-1", "alice", "conv-1", "task 1");
        registry.markFinished("sub-1", SubTaskStatus.COMPLETED, "done", null);

        ToolContext ctx = new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-1"));
        String result = tool.cancelSubTask("sub-1", ctx);

        assertThat(result).contains("失败");
        assertThat(result).contains("不在运行中");
    }

    @Test
    void getSubTaskHistoryReturnsHistoryForCurrentConversation() {
        SubTaskRegistry registry = new SubTaskRegistry(8, 100, id -> {});
        ISubTaskExecutor executor = mock(ISubTaskExecutor.class);
        DefaultSubTaskTool tool = new DefaultSubTaskTool(executor, registry);

        // conv-1: completed + failed
        registry.registerWithId("sub-1", "alice", "conv-1", "task 1");
        registry.markFinished("sub-1", SubTaskStatus.COMPLETED, "result 1", null);

        // conv-2: completed (should not show in conv-1 query)
        registry.registerWithId("sub-2", "alice", "conv-2", "task 2");
        registry.markFinished("sub-2", SubTaskStatus.COMPLETED, "result 2", null);

        // conv-1: failed
        registry.registerWithId("sub-3", "alice", "conv-1", "task 3");
        registry.markFinished("sub-3", SubTaskStatus.FAILED, null, "error msg");

        ToolContext ctx1 = new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-1"));
        String result = tool.getSubTaskHistory(10, ctx1);

        assertThat(result).contains("sub-1");
        assertThat(result).contains("sub-3");
        assertThat(result).doesNotContain("sub-2");
        assertThat(result).contains("error msg");
    }

    @Test
    void getSubTaskHistoryReturnsEmptyMessageWhenNone() {
        SubTaskRegistry registry = new SubTaskRegistry(8, 100);
        ISubTaskExecutor executor = mock(ISubTaskExecutor.class);
        DefaultSubTaskTool tool = new DefaultSubTaskTool(executor, registry);

        ToolContext ctx = new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-1"));
        String result = tool.getSubTaskHistory(10, ctx);

        assertThat(result).contains("无子任务历史");
    }

    @Test
    void getSubTaskHistoryDefaultsLimitTo10WhenNull() {
        SubTaskRegistry registry = new SubTaskRegistry(8, 100);
        ISubTaskExecutor executor = mock(ISubTaskExecutor.class);
        DefaultSubTaskTool tool = new DefaultSubTaskTool(executor, registry);

        ToolContext ctx = new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-1"));
        // Should not throw; returns empty result
        String result = tool.getSubTaskHistory(null, ctx);

        assertThat(result).contains("无子任务历史");
    }
}
