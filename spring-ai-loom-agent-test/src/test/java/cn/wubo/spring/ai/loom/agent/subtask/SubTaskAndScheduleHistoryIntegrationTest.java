package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.TaskBuilder;
import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor;
import cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Real-Spring-context proof that the schedule → sub-task → history pipeline
 * actually works end-to-end. The user reported "I don't see history records,
 * not sure if it executed successfully" — this test makes a one_shot schedule
 * fire in a real Spring Boot context with real Flyway + real DB, then asserts
 * the sub-task shows up in {@link SubTaskRegistry#listHistory} and the
 * schedule's execution history increments.
 */
@Slf4j
@SpringBootTest(classes = LoomAgentTestApplication.class)
@TestPropertySource(properties = {
        // Use a per-test-run H2 file so we don't collide with the live server
        // (or with parallel test runs). Tests run in a forked Maven JVM so
        // they own their own DB lock.
        "spring.datasource.url=jdbc:h2:file:./target/test-ds/db;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE",
        "spring.ai.loom.agent.file-base-path=./target/test-file-base",
        "spring.ai.loom.agent.knowledge-base-path=./target/test-knowledge-base"
})
class SubTaskAndScheduleHistoryIntegrationTest {

    @Autowired
    FlexScheduledTaskService flexService;

    @Autowired
    ISubTaskExecutor subTaskExecutor;

    @Autowired
    SubTaskRegistry subTaskRegistry;

    @Test
    void oneShot_schedule_fires_subTask_and_populates_history() throws Exception {
        String conversationId = "test-conv-" + UUID.randomUUID();
        String username = "test-user-history";
        String taskName = "loom-sched-" + username + "-" + conversationId + "-oneshot";
        String prompt = "echo:" + System.currentTimeMillis();

        AtomicReference<SubTaskResult> capturedResult = new AtomicReference<>();
        CountDownLatch fired = new CountDownLatch(1);

        TaskBuilder b = flexService.task(taskName);
        b.oneShot(Duration.ofMillis(500))
                .register(() -> {
                    try {
                        String subId = UUID.randomUUID().toString();
                        SubTaskRequest req = new SubTaskRequest(
                                subId, conversationId, null, username, prompt, null, true);
                        SubTaskResult result = subTaskExecutor.execute(req);
                        capturedResult.set(result);
                        // Mirror production behaviour (DefaultScheduleTool.runAsSubTask
                        // post-Fix-E): re-throw on FAILED so the schedule's
                        // instrument() catch records success=false instead of
                        // silently reporting success for a failed sub-task.
                        if (result.status() == SubTaskStatus.FAILED) {
                            throw new RuntimeException("sub-task failed: " + result.errorMessage());
                        }
                    } finally {
                        fired.countDown();
                    }
                });

        // Phase 1: schedule fires, lambda runs, sub-task executes.
        // Without a DASHSCOPE_API_KEY env var, the inner ChatClient.call
        // throws and the sub-task ends up FAILED instead of COMPLETED — both
        // are valid outcomes for this test; what we care about is that the
        // history record exists.
        // NOTE: with a key set, the sub-task makes a REAL LLM round-trip whose
        // latency is externally variable (observed ~12s idle, more under
        // rate-limiting). awaitility returns as soon as the call completes, so
        // a generous 60s cap only costs wall-clock on genuinely slow calls
        // while eliminating the false-timeout flake seen at the old 20s cap.
        await().atMost(60, TimeUnit.SECONDS).until(() -> fired.getCount() == 0);

        // Phase 2: sub-task is archived into history (terminal status set,
        // moved out of active). The prompt we sent is the lookup key.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var histRecords = subTaskRegistry.listHistory(username, 100);
            assertThat(histRecords).as("history should contain the executed sub-task")
                    .anySatisfy(r -> {
                        assertThat(r.prompt()).isEqualTo(prompt);
                        assertThat(r.status())
                                .as("sub-task should be in a terminal state")
                                .isIn(SubTaskStatus.COMPLETED, SubTaskStatus.FAILED);
                        assertThat(r.startedAt()).isPositive();
                        assertThat(r.finishedAt()).isPositive();
                    });
        });

        SubTaskResult result = capturedResult.get();
        assertThat(result).isNotNull();
        assertThat(result.status()).isIn(SubTaskStatus.COMPLETED, SubTaskStatus.FAILED);
        log.info("Sub-task terminal state: status={}, subId={}", result.status(), result.subTaskId());

        // Phase 3 (Fix D verification): schedule execution history has 1 record.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var execHistory = flexService.getExecutionHistory(taskName, 100);
            assertThat(execHistory).as("schedule execution history should record the fire")
                    .hasSize(1);
            var only = execHistory.get(0);
            assertThat(only.taskName()).isEqualTo(taskName);
            assertThat(only.success()).isEqualTo(result.status() == SubTaskStatus.COMPLETED);
        });
        var exec = flexService.getExecutionHistory(taskName, 10);
        log.info("Schedule execution history entries: {}", exec);
    }
}
