package cn.wubo.spring.ai.loom.agent.schedule;

import cn.wubo.flex.schedule.core.DefaultFlexScheduledTaskService;
import cn.wubo.flex.schedule.core.ExecutionRecord;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.InMemoryExecutionHistory;
import cn.wubo.flex.schedule.core.TaskLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test for the new flex-schedule 1.2.2 APIs that loom-agent relies
 * on after the upgrade:
 * <ul>
 *   <li>{@link cn.wubo.flex.schedule.core.TaskBuilder#createdAt(Instant)} &mdash;
 *       stamps the logical creation time on the dispatcher entry so the
 *       max-lifetime math continues across restarts.</li>
 *   <li>{@link cn.wubo.flex.schedule.core.FlexScheduledTaskService#setCreatedAt(String, Instant)} &mdash;
 *       the underlying entry-mutation API called by TaskBuilder.</li>
 *   <li>{@link InMemoryExecutionHistory} &mdash; the auto-wired default
 *       {@link cn.wubo.flex.schedule.core.ExecutionHistory} bean, which
 *       replaced the silent {@code NOOP} in 1.2.2.</li>
 * </ul>
 *
 * <p>The test stands up a real {@link FlexScheduledTaskRegistrar} + service
 * (no mocking) so a misconfigured integration with the new fluent chain
 * surfaces here rather than in production.</p>
 */
class FlexScheduleCreatedAtChainTest {

    private ThreadPoolTaskScheduler scheduler;
    private FlexScheduledTaskRegistrar registrar;
    private FlexScheduledTaskService service;
    private InMemoryExecutionHistory history;

    @BeforeEach
    void setUp() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("createdAt-test-");
        scheduler.initialize();

        registrar = new FlexScheduledTaskRegistrar(scheduler, 2L);
        history = new InMemoryExecutionHistory(100);
        registrar.setExecutionHistory(history);

        service = new DefaultFlexScheduledTaskService(registrar);
    }

    @Test
    void taskBuilder_createdAt_isObservableViaTaskDetail() {
        Instant originalCreatedAt = Instant.now().minus(Duration.ofHours(50));
        service.task("loom-sched-alice-conv-1-remind")
                .cron("0 * * * * *")
                .createdAt(originalCreatedAt)
                .register(() -> { /* no-op */ });

        var detail = service.getTaskDetail("loom-sched-alice-conv-1-remind");
        assertThat(detail).isPresent();
        // TaskBuilder.createdAt → registrar.setCreatedAt → TaskDetail.createdAt
        assertThat(detail.get().createdAt()).isNotNull();
        long deltaSec = Math.abs(java.time.Duration.between(
                detail.get().createdAt(), originalCreatedAt).toSeconds());
        assertThat(deltaSec).isLessThan(2);
    }

    @Test
    void taskBuilder_oneShotFires_AndExecutionHistoryRecordsTheOutcome() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        service.task("loom-sched-alice-conv-1-fire-once")
                .oneShot(Duration.ofMillis(50))
                .register(latch::countDown);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        // Wait for the in-memory history to settle.
        Thread.sleep(150);

        List<ExecutionRecord> rows = service.getExecutionHistory("loom-sched-alice-conv-1-fire-once", 10);
        assertThat(rows)
                .as("flex-schedule 1.2.2 wires InMemoryExecutionHistory by default; "
                        + "without the LoomFlexExecutionHistoryRegistrar bridge this would be empty.")
                .isNotEmpty();
        assertThat(rows.get(0).success()).isTrue();
    }

    @Test
    void setCreatedAt_afterRegistration_appliesToExistingEntry() {
        service.task("late-bound").cron("0 * * * * *").register(() -> { });
        Instant override = Instant.now().minus(Duration.ofMinutes(7));
        service.setCreatedAt("late-bound", override);

        var detail = service.getTaskDetail("late-bound");
        assertThat(detail).isPresent();
        long deltaSec = Math.abs(java.time.Duration.between(
                detail.get().createdAt(), override).toSeconds());
        assertThat(deltaSec).isLessThan(2);
    }

    @Test
    void setCreatedAt_onUnknownTask_isNoOpNotException() {
        // New in 1.2.2 — computeIfPresent makes this safe.
        service.setCreatedAt("does-not-exist", Instant.now());
        assertThat(service.exists("does-not-exist")).isFalse();
    }

    /**
     * If we install a permissive TaskLimits at the registrar level and craft a
     * task whose createdAt is past the max-lifetime ceiling, the runtime's
     * native execution should still fire it (LimitsChecker is what enforces the
     * cap on fire). This confirms the path that the loom-agent
     * {@code ScheduleRestoreListener} relies on (TaskBuilder.createdAt + strapping
     * TaskLimits) works end-to-end.
     */
    @Test
    void taskWithPastCreatedAt_stillExecutesAtItsCronTick() throws InterruptedException {
        Instant wayOld = Instant.now().minus(Duration.ofDays(365));
        CountDownLatch latch = new CountDownLatch(1);
        service.task("ancient-task")
                .cron("* * * * * *")                  // every second
                .createdAt(wayOld)                     // far past max-lifetime
                .register(latch::countDown);

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * When the registrar holds a TaskLimits with a finite max-lifetime, a
     * re-register with a past createdAt should land in the dispatcher's taskMap
     * (the runtime only enforces max-lifetime on fire, not on register).
     */
    @Test
    void taskLimits_maxLifetime_zeroDoesNotPreventRegistration() {
        TaskLimits capsZero = new TaskLimits(Duration.ofMinutes(10), Duration.ZERO, TaskLimits.Mode.STRICT);
        // 1.2.2 takes TaskLimits via the constructor — there's no setLimits() setter.
        FlexScheduledTaskRegistrar limitsAware = new FlexScheduledTaskRegistrar(scheduler, 2L, capsZero);
        limitsAware.setExecutionHistory(history);
        FlexScheduledTaskService limitedService = new DefaultFlexScheduledTaskService(limitsAware);

        Instant wayOld = Instant.now().minus(Duration.ofDays(10));
        limitedService.task("caps-task")
                .cron("0 * * * * *")
                .createdAt(wayOld)
                .register(() -> { });

        assertThat(limitedService.exists("caps-task")).isTrue();
    }

    @Test
    void taskBuilderBuilder_throwsWhenNoScheduleConfigured() {
        // register() with no cron/fixedDelay/fixedRate/oneShot is a contract error.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.task("bad-task").register(() -> { })
        ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No scheduling type configured");
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        try {
            registrar.destroy();
        } catch (Exception ignored) { }
        scheduler.shutdown();
    }
}
