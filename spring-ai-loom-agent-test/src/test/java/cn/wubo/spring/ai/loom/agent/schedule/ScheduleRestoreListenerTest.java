package cn.wubo.spring.ai.loom.agent.schedule;

import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.TaskBuilder;
import cn.wubo.flex.schedule.core.TaskLimits;
import cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies {@link ScheduleRestoreListener#restoreOnStartup()} covers the 4 documented
 * scenarios: empty repo, fresh record (re-registered with stored createdAt),
 * expired record (deleted not re-registered), paused record (pause() called after
 * register).
 */
class ScheduleRestoreListenerTest {

    private FlexScheduledTaskService flexService;
    private TaskBuilder builder;
    private ILoomScheduleTriggerRepository repo;
    private ISubTaskExecutor subTaskExecutor;
    private TaskLimits limits72h;
    private ScheduleRestoreListener listener;

    @BeforeEach
    void setUp() {
        flexService = mock(FlexScheduledTaskService.class);
        builder = mock(TaskBuilder.class);
        repo = mock(ILoomScheduleTriggerRepository.class);
        subTaskExecutor = mock(ISubTaskExecutor.class);
        // 72h max-lifetime, STRICT mode — matches the loom-agent test app's defaults.
        limits72h = new TaskLimits(Duration.ofMinutes(10), Duration.ofHours(72), TaskLimits.Mode.STRICT);
        when(flexService.task(any())).thenReturn(builder);
        when(builder.cron(any())).thenReturn(builder);
        when(builder.fixedDelay(any(Duration.class))).thenReturn(builder);
        when(builder.fixedDelay(any(Duration.class), any(Duration.class))).thenReturn(builder);
        when(builder.fixedRate(any(Duration.class))).thenReturn(builder);
        when(builder.fixedRate(any(Duration.class), any(Duration.class))).thenReturn(builder);
        when(builder.oneShot(any(Duration.class))).thenReturn(builder);
        when(builder.createdAt(any(Instant.class))).thenReturn(builder);

        listener = new ScheduleRestoreListener(flexService, repo, subTaskExecutor, limits72h);
    }

    private static LoomScheduleTriggerRecord record(String taskName,
                                                    String scheduleType,
                                                    Instant createdAt,
                                                    boolean paused) {
        return new LoomScheduleTriggerRecord(
                taskName,
                scheduleType,
                "cron".equals(scheduleType) ? "0 * * * * *" : null,
                ("fixed_delay".equals(scheduleType) || "fixed_rate".equals(scheduleType)) ? 600L : null,
                null,
                "one_shot".equals(scheduleType) ? 10L : null,
                "say hi from " + taskName,
                "alice",
                "conv-1",
                paused,
                createdAt,
                createdAt);
    }

    @Test
    void restore_emptyRepo_makesNoSchedulingCalls() {
        when(repo.findAll()).thenReturn(List.of());

        listener.restoreOnStartup();

        verify(repo).findAll();
        verifyNoInteractions(flexService);
        verify(repo, never()).delete(any());
    }

    @Test
    void restore_freshCronRecord_appliesCronAndStampsOriginalCreatedAt() {
        Instant tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10));
        when(repo.findAll()).thenReturn(List.of(record("remind", "cron", tenMinutesAgo, false)));

        listener.restoreOnStartup();

        InOrder inOrder = inOrder(flexService, builder);
        inOrder.verify(flexService).task("remind");
        inOrder.verify(builder).cron("0 * * * * *");
        inOrder.verify(builder).createdAt(tenMinutesAgo);
        inOrder.verify(builder).register(any(Runnable.class));
        inOrder.verifyNoMoreInteractions();

        verify(repo, never()).delete(any());
        verify(flexService, never()).pause(any());
    }

    @Test
    void restore_freshFixedDelayRecord_appliesFixedDelay() {
        Instant recently = Instant.now().minus(Duration.ofMinutes(5));
        when(repo.findAll()).thenReturn(List.of(record("remind", "fixed_delay", recently, false)));

        listener.restoreOnStartup();

        verify(flexService).task("remind");
        verify(builder).fixedDelay(eq(Duration.ofSeconds(600L)), any(Duration.class));
        verify(builder).createdAt(recently);
        verify(builder).register(any(Runnable.class));
        verify(repo, never()).delete(any());
    }

    @Test
    void restore_expiredRecord_deletesRowAndDoesNotReschedule() {
        Instant eightyHoursAgo = Instant.now().minus(Duration.ofHours(80));
        when(repo.findAll()).thenReturn(List.of(record("old", "cron", eightyHoursAgo, false)));

        listener.restoreOnStartup();

        verify(repo).delete("old");
        verify(flexService, never()).task(any());
        verify(builder, never()).register(any(Runnable.class));
    }

    @Test
    void restore_pausedRecord_callsPauseAfterRegister() {
        Instant recently = Instant.now().minus(Duration.ofMinutes(2));
        when(repo.findAll()).thenReturn(List.of(record("paused-1", "cron", recently, true)));

        listener.restoreOnStartup();

        InOrder order = inOrder(flexService, builder);
        order.verify(flexService).task("paused-1");
        order.verify(builder).register(any(Runnable.class));
        order.verify(flexService).pause("paused-1");
        order.verifyNoMoreInteractions();
    }

    @Test
    void restore_oneShotRecord_appliesOneShotDelay() {
        Instant recently = Instant.now().minus(Duration.ofMinutes(1));
        when(repo.findAll()).thenReturn(List.of(record("fire-once", "one_shot", recently, false)));

        listener.restoreOnStartup();

        verify(flexService).task("fire-once");
        verify(builder).oneShot(Duration.ofSeconds(10L));
        verify(builder).createdAt(recently);
        verify(builder).register(any(Runnable.class));
    }

    @Test
    void restore_mixedRecords_respectsPerRowLifecycle() {
        Instant fresh = Instant.now().minus(Duration.ofMinutes(5));
        Instant stale = Instant.now().minus(Duration.ofHours(80));
        Instant pausedFresh = Instant.now().minus(Duration.ofMinutes(2));
        when(repo.findAll()).thenReturn(List.of(
                record("kept", "cron", fresh, false),
                record("stale", "cron", stale, false),
                record("paused-1", "cron", pausedFresh, true)
        ));

        listener.restoreOnStartup();

        // fresh: registered, NOT paused, NOT deleted
        verify(builder, times(1)).createdAt(fresh);
        verify(repo, never()).delete("kept");
        verify(flexService, never()).pause("kept");

        // stale: deleted, no TaskBuilder.register call attributed to it
        verify(repo).delete("stale");
        verify(builder, never()).createdAt(stale);

        // paused: registered, then paused
        verify(builder).createdAt(pausedFresh);
        verify(flexService).pause("paused-1");
    }

    @Test
    void restore_disabledLimits_acceptsEvenVeryOldTasks() {
        Instant ancient = Instant.now().minus(Duration.ofDays(365));
        when(repo.findAll()).thenReturn(List.of(record("ancient", "cron", ancient, false)));

        ScheduleRestoreListener permissiveListener = new ScheduleRestoreListener(
                flexService, repo, subTaskExecutor, TaskLimits.DISABLED);
        permissiveListener.restoreOnStartup();

        verify(builder).createdAt(ancient);
        verify(builder).register(any(Runnable.class));
        verify(repo, never()).delete(any());
    }

    @Test
    void restore_unknownScheduleType_logsFailureAndContinues() {
        Instant recently = Instant.now().minus(Duration.ofMinutes(1));
        when(repo.findAll()).thenReturn(List.of(
                new LoomScheduleTriggerRecord(
                        "bad", "banana", null, null, null, null,
                        "x", "alice", "conv-1", false, recently, recently)
        ));

        // Should NOT throw — listener swallows per-row failures so a single bad row
        // doesn't block the rest.
        listener.restoreOnStartup();

        verify(builder, never()).register(any(Runnable.class));
        verify(repo, never()).delete(any());
    }
}
