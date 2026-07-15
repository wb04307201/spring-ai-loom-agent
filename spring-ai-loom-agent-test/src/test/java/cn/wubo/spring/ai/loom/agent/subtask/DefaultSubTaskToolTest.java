package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
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
        assertThat(registry.listHistory("alice", 10)).hasSize(1);
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
}
