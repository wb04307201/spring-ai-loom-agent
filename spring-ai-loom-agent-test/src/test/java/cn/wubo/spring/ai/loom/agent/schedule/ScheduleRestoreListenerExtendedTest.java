package cn.wubo.spring.ai.loom.agent.schedule;

import cn.wubo.flex.schedule.core.ExecutionRecord;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.TaskBuilder;
import cn.wubo.flex.schedule.core.TaskLimits;
import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Extended coverage for {@link ScheduleRestoreListener} — branches the original
 * {@link ScheduleRestoreListenerTest} left open:
 * <ul>
 *   <li><b>WARN mode</b> &mdash; expired rows are NOT deleted but ARE registered
 *       (lifetime math continues but the listener opts to keep the task alive).</li>
 *   <li><b>Orphan filter</b> &mdash; rows whose owner is missing from
 *       {@code user_info} are deleted without re-register.</li>
 *   <li><b>Orphan filter disabled</b> &mdash; when no {@link JdbcTemplate} is
 *       wired (legacy constructor), all rows are restored without owner
 *       checking.</li>
 *   <li><b>one_shot skip-on-already-fired</b> &mdash; when
 *       {@link ILoomScheduleExecutionRepository} reports an existing execution
 *       row for a {@code one_shot} task, the listener leaves the declaration
 *       alone and skips {@code register} so the task doesn't re-fire after a
 *       restart.</li>
 *   <li><b>Sub-task restore path</b> &mdash; the {@code Runnable} registered by
 *       the listener re-dispatches via {@link ISubTaskExecutor} and dual-writes
 *       the outcome to {@link ILoomScheduleExecutionRepository}; on FAILED the
 *       row is written with success=false.</li>
 *   <li><b>executionRepo == null branch</b> &mdash; listener's
 *       {@code recordExecution} logs a warn and returns when no execution repo
 *       is wired (older {@code @Bean} shapes).</li>
 * </ul>
 */
class ScheduleRestoreListenerExtendedTest {

    private FlexScheduledTaskService flexService;
    private TaskBuilder builder;
    private ILoomScheduleTriggerRepository repo;
    private ILoomScheduleExecutionRepository execRepo;
    private ISubTaskExecutor subTaskExecutor;
    private JdbcTemplate userJdbcTemplate;

    @BeforeEach
    void setUp() {
        flexService = mock(FlexScheduledTaskService.class);
        builder = mock(TaskBuilder.class);
        repo = mock(ILoomScheduleTriggerRepository.class);
        execRepo = mock(ILoomScheduleExecutionRepository.class);
        subTaskExecutor = mock(ISubTaskExecutor.class);
        userJdbcTemplate = mock(JdbcTemplate.class);

        // All fluent TaskBuilder setters return the builder so the chain can be
        // driven by mocks without throwing NPE.
        when(flexService.task(any())).thenReturn(builder);
        when(builder.cron(any())).thenReturn(builder);
        when(builder.fixedDelay(any(Duration.class))).thenReturn(builder);
        when(builder.fixedDelay(any(Duration.class), any(Duration.class))).thenReturn(builder);
        when(builder.fixedRate(any(Duration.class))).thenReturn(builder);
        when(builder.fixedRate(any(Duration.class), any(Duration.class))).thenReturn(builder);
        when(builder.oneShot(any(Duration.class))).thenReturn(builder);
        when(builder.createdAt(any(Instant.class))).thenReturn(builder);
    }

    private static LoomScheduleTriggerRecord record(
            String taskName,
            String scheduleType,
            Instant createdAt,
            String username,
            boolean paused) {
        return new LoomScheduleTriggerRecord(
                taskName, scheduleType,
                "cron".equals(scheduleType) ? "0 * * * * *" : null,
                ("fixed_delay".equals(scheduleType) || "fixed_rate".equals(scheduleType)) ? 600L : null,
                null,
                "one_shot".equals(scheduleType) ? 30L : null,
                "do work",
                username, "conv-1", paused, createdAt, createdAt);
    }

    private ScheduleRestoreListener listener(TaskLimits limits) {
        when(userJdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("alice", "bob"));
        return new ScheduleRestoreListener(
                flexService, repo, subTaskExecutor, limits, execRepo, 1000, userJdbcTemplate);
    }

    @Test
    void restore_warnMode_keepsExpiredRowAndRegisters() {
        TaskLimits warn = new TaskLimits(Duration.ofMinutes(10), Duration.ofHours(72), TaskLimits.Mode.WARN);
        ScheduleRestoreListener permissive = listener(warn);

        Instant eightyHoursAgo = Instant.now().minus(Duration.ofHours(80));
        when(repo.findAll()).thenReturn(List.of(record("warn-keep", "cron", eightyHoursAgo, "alice", false)));

        permissive.restoreOnStartup();

        // Should NOT delete — WARN mode opts to preserve the row.
        verify(repo, never()).delete(any());
        // Should register — max-lifetime check returns false in WARN mode.
        verify(builder).createdAt(eightyHoursAgo);
        verify(builder).register(any(Runnable.class));
    }

    @Test
    void restore_offMode_alwaysKeepsRowRegardlessOfAge() {
        TaskLimits off = new TaskLimits(Duration.ofMinutes(10), Duration.ofHours(1), TaskLimits.Mode.OFF);
        ScheduleRestoreListener offListener = listener(off);

        Instant wayOld = Instant.now().minus(Duration.ofDays(365));
        when(repo.findAll()).thenReturn(List.of(record("off-keep", "cron", wayOld, "alice", false)));

        offListener.restoreOnStartup();

        verify(repo, never()).delete(any());
        verify(builder).register(any(Runnable.class));
    }

    @Test
    void restore_orphanRowInUserInfo_dropsWithoutRegister() {
        ScheduleRestoreListener l = listener(
                new TaskLimits(Duration.ofMinutes(10), Duration.ofHours(72), TaskLimits.Mode.STRICT));
        // user_info has bob but NOT alice — override AFTER listener() so the
        // helper's default ['alice','bob'] stubbing doesn't mask our intent.
        when(userJdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("bob"));

        when(repo.findAll()).thenReturn(List.of(
                record("alice-orphan", "cron", Instant.now(), "alice", false)));

        l.restoreOnStartup();

        verify(repo).delete("alice-orphan");
        verify(builder, never()).register(any(Runnable.class));
    }

    @Test
    void restore_ownerExistsInUserInfo_keepsAndRegisters() {
        when(userJdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("alice"));
        ScheduleRestoreListener l = listener(
                new TaskLimits(Duration.ofMinutes(10), Duration.ofHours(72), TaskLimits.Mode.STRICT));

        when(repo.findAll()).thenReturn(List.of(
                record("alice-keep", "cron", Instant.now().minus(Duration.ofMinutes(5)), "alice", false)));

        l.restoreOnStartup();

        verify(repo, never()).delete(any());
        verify(builder).register(any(Runnable.class));
    }

    @Test
    void restore_jdbcTemplateThrows_fallsBackToNoFilter() {
        // Backend hiccup — must not drop everything.
        when(userJdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenThrow(new RuntimeException("DB down"));
        ScheduleRestoreListener l = listener(
                new TaskLimits(Duration.ofMinutes(10), Duration.ofHours(72), TaskLimits.Mode.STRICT));

        when(repo.findAll()).thenReturn(List.of(
                record("alice-keep", "cron", Instant.now().minus(Duration.ofMinutes(5)), "alice", false)));

        l.restoreOnStartup();

        // Falls back to "all usernames known" — keep the row.
        verify(repo, never()).delete(any());
        verify(builder).register(any(Runnable.class));
    }

    @Test
    void restore_legacyConstructorWithoutJdbcTemplate_skipsOrphanCheck() {
        ScheduleRestoreListener legacy = new ScheduleRestoreListener(
                flexService, repo, subTaskExecutor,
                TaskLimits.DISABLED, execRepo, 1000);
        // No userJdbcTemplate → knownUsernames is empty → orphans "pass through".

        when(repo.findAll()).thenReturn(List.of(
                record("alice-keep", "cron", Instant.now().minus(Duration.ofMinutes(5)), "alice", false),
                record("missing-user", "cron", Instant.now().minus(Duration.ofMinutes(5)), "ghost", false)
        ));

        legacy.restoreOnStartup();

        // Neither row is treated as orphan.
        verify(repo, never()).delete(any());
        verify(builder, times(2)).register(any(Runnable.class));
    }

    @Test
    void restore_oneShotAlreadyFired_skipsRegisterButKeepsDeclarationRow() {
        // executionRepo reports the task already fired (1 row in loom_schedule_execution).
        when(execRepo.countByTaskName("fired-once")).thenReturn(1);

        ScheduleRestoreListener l = listener(
                new TaskLimits(Duration.ofMinutes(10), Duration.ofHours(72), TaskLimits.Mode.STRICT));

        when(repo.findAll()).thenReturn(List.of(
                record("fired-once", "one_shot",
                        Instant.now().minus(Duration.ofMinutes(2)), "alice", false)));

        l.restoreOnStartup();

        // Declaration row preserved (history IS the audit trail).
        verify(repo, never()).delete(any());
        // But we do NOT re-register — that would fire the one_shot again 30s after restart.
        verify(builder, never()).oneShot(any(Duration.class));
        verify(builder, never()).register(any(Runnable.class));
    }

    @Test
    void restore_oneShotNotYetFired_registersNormally() {
        // executionRepo says no rows → the task hasn't fired yet → restore normally.
        when(execRepo.countByTaskName("pending-once")).thenReturn(0);

        ScheduleRestoreListener l = listener(
                new TaskLimits(Duration.ofMinutes(10), Duration.ofHours(72), TaskLimits.Mode.STRICT));

        when(repo.findAll()).thenReturn(List.of(
                record("pending-once", "one_shot",
                        Instant.now().minus(Duration.ofMinutes(1)), "alice", false)));

        l.restoreOnStartup();

        verify(builder).oneShot(any(Duration.class));
        verify(builder).register(any(Runnable.class));
    }

    @Test
    void runAsSubTask_successPath_persistsExecutionRecord() {
        ScheduleRestoreListener l = listener(
                new TaskLimits(Duration.ofMinutes(10), Duration.ofHours(72), TaskLimits.Mode.STRICT));

        // Sub-task completes OK.
        SubTaskRequest req = new SubTaskRequest("sub-id", "conv-1", null, "alice", "p", null, true);
        when(subTaskExecutor.execute(any())).thenReturn(
                SubTaskResult.completed(req, System.currentTimeMillis() - 10, System.currentTimeMillis(), "ok"));

        record("alive-task", "cron",
                Instant.now().minus(Duration.ofMinutes(1)), "alice", false);

        // Drive the runnable the listener would have registered.
        Runnable runnable = (Runnable) captureRegisteredRunnable(l, "alive-task");

        runnable.run();

        ArgumentCaptor<LoomScheduleExecutionRecord> saved = ArgumentCaptor.forClass(LoomScheduleExecutionRecord.class);
        verify(execRepo).save(saved.capture());
        assertThat(saved.getValue().taskName()).isEqualTo("alive-task");
        assertThat(saved.getValue().success()).isTrue();
        // The listener initializes errorMessage = null on success and only
        // mutates it in the failure branch, so a stored "success" row has no
        // error message at all (verified by the H2 integration test
        // JdbcLoomScheduleExecutionRepositoryTest.save_setsIdentityColumnAndDefaultsFiredByToScheduler
        // which confirms the column is NULL).
        assertThat(saved.getValue().errorMessage()).isNull();
        verify(execRepo).trimTaskHistory(eq("alive-task"), anyInt());
    }

    @Test
    void runAsSubTask_failedPath_persistsExecutionRecordWithFailure() {
        ScheduleRestoreListener l = listener(
                new TaskLimits(Duration.ofMinutes(10), Duration.ofHours(72), TaskLimits.Mode.STRICT));

        // Sub-task reports FAILED.
        SubTaskRequest req = new SubTaskRequest("sub-id", "conv-1", null, "alice", "p", null, true);
        when(subTaskExecutor.execute(any())).thenReturn(
                SubTaskResult.failed(req, System.currentTimeMillis() - 10, System.currentTimeMillis(), "boom"));

        record("failed-task", "cron",
                Instant.now().minus(Duration.ofMinutes(1)), "alice", false);

        Runnable runnable = (Runnable) captureRegisteredRunnable(l, "failed-task");

        runnable.run();

        ArgumentCaptor<LoomScheduleExecutionRecord> saved = ArgumentCaptor.forClass(LoomScheduleExecutionRecord.class);
        verify(execRepo).save(saved.capture());
        assertThat(saved.getValue().success()).isFalse();
        assertThat(saved.getValue().errorMessage()).contains("boom");
    }

    @Test
    void runAsSubTask_exceptionPath_stillPersistsExecutionRecord() {
        ScheduleRestoreListener l = listener(
                new TaskLimits(Duration.ofMinutes(10), Duration.ofHours(72), TaskLimits.Mode.STRICT));

        // Sub-task executor itself blows up.
        when(subTaskExecutor.execute(any())).thenThrow(new RuntimeException("harness failure"));

        record("kaboom-task", "cron",
                Instant.now().minus(Duration.ofMinutes(1)), "alice", false);

        Runnable runnable = (Runnable) captureRegisteredRunnable(l, "kaboom-task");

        runnable.run();   // must NOT throw — restores are best-effort.

        ArgumentCaptor<LoomScheduleExecutionRecord> saved = ArgumentCaptor.forClass(LoomScheduleExecutionRecord.class);
        verify(execRepo).save(saved.capture());
        assertThat(saved.getValue().success()).isFalse();
        assertThat(saved.getValue().errorMessage()).contains("harness failure");
    }

    @Test
    void restore_executionRepoIsNull_warnsAndSkipsRecordExecution() {
        // Construct listener with the "legacy" 4-arg constructor: no execution repo.
        ScheduleRestoreListener l = new ScheduleRestoreListener(
                flexService, repo, subTaskExecutor, TaskLimits.DISABLED);

        // Sub-task succeeds.
        SubTaskRequest req = new SubTaskRequest("sub-id", "conv-1", null, "alice", "p", null, true);
        when(subTaskExecutor.execute(any())).thenReturn(
                SubTaskResult.completed(req, System.currentTimeMillis() - 10, System.currentTimeMillis(), "ok"));

        record("legacy-task", "cron",
                Instant.now().minus(Duration.ofMinutes(1)), "alice", false);

        Runnable runnable = (Runnable) captureRegisteredRunnable(l, "legacy-task");

        // Doesn't throw — recordExecution early-returns when executionRepo is null.
        runnable.run();

        // No exec-repo interaction at all.
        verifyNoInteractions(execRepo);
    }

    @Test
    void restore_mixedRowsAcrossAllPaths_keepsEachOnItsOwnTrack() {
        when(userJdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("alice"));
        ScheduleRestoreListener l = listener(
                new TaskLimits(Duration.ofMinutes(10), Duration.ofHours(72), TaskLimits.Mode.STRICT));

        Instant recent = Instant.now().minus(Duration.ofMinutes(2));
        Instant stale = Instant.now().minus(Duration.ofHours(80));
        when(repo.findAll()).thenReturn(List.of(
                record("kept", "cron", recent, "alice", false),
                record("orphan", "cron", recent, "ghost", false),
                record("stale", "cron", stale, "alice", false),
                record("fired-once", "one_shot", recent, "alice", false)
        ));
        when(execRepo.countByTaskName("fired-once")).thenReturn(1);

        l.restoreOnStartup();

        // kept: registered
        verify(builder, times(1)).createdAt(recent);
        // orphan: deleted
        verify(repo).delete("orphan");
        // stale: deleted
        verify(repo).delete("stale");
        // fired-once: NOT re-registered, NOT deleted
        verify(builder, never()).oneShot(any(Duration.class));
        verify(repo, never()).delete("fired-once");
    }

    // Helpers

    /** Invoke listener.restoreOnStartup() and capture the Runnable the builder
     *  would have registered. Mockito forbids {@code doNothing().when(x).foo(captor.capture())}
     *  (capture is only reliable on verify) so we register the runnable through
     *  the verify-capture idiom: drive restore, then capture the runnable the
     *  builder.register(…) call received.
     */
    private Runnable captureRegisteredRunnable(ScheduleRestoreListener l, String taskName) {
        when(repo.findAll()).thenReturn(List.of(
                record(taskName, "cron",
                        Instant.now().minus(Duration.ofMinutes(1)), "alice", false)
        ));
        l.restoreOnStartup();
        ArgumentCaptor<Runnable> reg = ArgumentCaptor.forClass(Runnable.class);
        verify(builder).register(reg.capture());
        return reg.getValue();
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
