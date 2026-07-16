package cn.wubo.spring.ai.loom.agent.schedule;

import cn.wubo.flex.schedule.core.ExecutionRecord;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.TaskInfo;
import cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DefaultScheduleToolTest {

    private FlexScheduledTaskService flexService;
    private ISubTaskExecutor executor;
    private DefaultScheduleTool tool;

    @BeforeEach
    void setUp() {
        flexService = mock(FlexScheduledTaskService.class);
        executor = mock(ISubTaskExecutor.class);
        tool = new DefaultScheduleTool(flexService, executor);
    }

    @Test
    void createScheduleNamespacesByUsernameAndConv() {
        // task(...) returns a mocked TaskBuilder; the fluent chain returns it and
        // register(...) is a no-op on the mock. We only assert task(full) was
        // invoked with the namespaced name and the success message is returned.
        cn.wubo.flex.schedule.core.TaskBuilder builder = mock(cn.wubo.flex.schedule.core.TaskBuilder.class);
        when(flexService.task(any())).thenReturn(builder);
        when(builder.fixedDelay(any())).thenReturn(builder);
        ToolContext ctx = new ToolContext(Map.of(
                "username", "alice",
                "parentConversationId", "conv-1"));

        String response = tool.createSchedule("remind", "fixed_delay", "600", "say hi", ctx);

        assertThat(response).contains("remind");
        verify(flexService).task(contains("loom-sched-alice-conv-1-remind"));
    }

    @Test
    void createSchedulePropagatesLimitExceptionAsFriendlyMessage() {
        ToolContext ctx = new ToolContext(Map.of(
                "username", "alice",
                "parentConversationId", "conv-1"));

        when(flexService.task(any())).thenThrow(new RuntimeException("trigger interval too small"));

        String response = tool.createSchedule("x", "fixed_delay", "1", "p", ctx);

        assertThat(response).contains("失败");
        assertThat(response).contains("trigger interval too small");
    }

    @Test
    void cancelScheduleCallsCancelOnNamespacedName() {
        ToolContext ctx = new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-1"));
        tool.cancelSchedule("remind", ctx);
        verify(flexService).cancel("loom-sched-alice-conv-1-remind");
    }

    @Test
    void listSchedulesFiltersByNamespacePrefix() {
        ToolContext ctx = new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-1"));
        when(flexService.listTasks()).thenReturn(List.of(
                new TaskInfo("loom-sched-alice-conv-1-remind", "FIXED_DELAY", null),
                new TaskInfo("loom-sched-alice-conv-2-other", "FIXED_DELAY", null),
                new TaskInfo("loom-sched-bob-conv-1-foo", "CRON", null)));

        String response = tool.listSchedules(ctx);
        assertThat(response).contains("remind");
        assertThat(response).contains("other");
        assertThat(response).doesNotContain("foo");
    }

    @Test
    void getScheduleHistoryCallsHistoryWithLimit() {
        ToolContext ctx = new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-1"));
        when(flexService.getExecutionHistory(eq("loom-sched-alice-conv-1-remind"), eq(10)))
                .thenReturn(List.<ExecutionRecord>of());

        String response = tool.getScheduleHistory("remind", 10, ctx);
        assertThat(response).contains("remind");
        verify(flexService).getExecutionHistory("loom-sched-alice-conv-1-remind", 10);
    }

    @Test
    void getScheduleHistoryDefaultsLimitTo20WhenNull() {
        ToolContext ctx = new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-1"));
        when(flexService.getExecutionHistory(any(), eq(20))).thenReturn(List.of());
        tool.getScheduleHistory("remind", null, ctx);
        verify(flexService).getExecutionHistory(any(), eq(20));
    }
}
