package cn.wubo.spring.ai.loom.agent.schedule;

import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.TaskBuilder;
import cn.wubo.flex.schedule.core.TaskLimits;
import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Restores loom-agent's prompt-triggered scheduled sub-tasks on application startup.
 *
 * <p>Why a loom-agent-owned listener (instead of flex-schedule's
 * {@code FlexScheduledTaskRegistrar.restoreTasks()})?</p>
 * <ul>
 *   <li>flex-schedule's restore path requires {@code beanName+methodName} on the
 *       stored task definition — it skips any lambda-style task with a WARN log.
 *       loom-agent's triggers are pure lambdas (prompt captured in closure).</li>
 *   <li>flex-schedule's restore path cannot handle {@code ONE_SHOT} tasks
 *       (its {@code scheduleByType} switch has no {@code ONE_SHOT} arm).</li>
 *   <li>The schema is now loom-agent-owned ({@code loom_scheduled_task} with prompt
 *       columns) so the repository reads {@link LoomScheduleTriggerRecord} not
 *       {@code TaskDefinition}.</li>
 * </ul>
 *
 * <p>The listener runs on {@link ApplicationReadyEvent} (after the Spring context
 * is fully wired but before user traffic flows). For each persisted row it:
 * <ol>
 *   <li>Calls {@link TaskLimits#isExpired(String, java.time.Instant)} to enforce
 *       the 72h {@code max-lifetime} ceiling — expired rows are deleted
 *       (not re-registered).</li>
 *   <li>Rebuilds a {@link TaskBuilder} chain via flex-schedule's
 *       {@code FlexScheduledTaskService.task(name).{cron|fixedDelay|fixedRate|oneShot}(...)}
 *       and threads the original {@code createdAt} through
 *       {@link TaskBuilder#createdAt(java.time.Instant)} so the lifetime math
 *       continues across restarts.</li>
 *   <li>Replays paused state via {@code flexService.pause(name)}.</li>
 * </ol>
 * Per-task failures are swallowed with a WARN so one bad row doesn't block the
 * rest from being restored.
 */
public class ScheduleRestoreListener {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRestoreListener.class);

    private final FlexScheduledTaskService flexService;
    private final ILoomScheduleTriggerRepository repo;
    private final ISubTaskExecutor subTaskExecutor;
    private final TaskLimits taskLimits;
    private final ILoomScheduleExecutionRepository executionRepo;
    private final org.springframework.jdbc.core.JdbcTemplate userJdbcTemplate;
    /** Per-task execution history cap (newest N kept). Default 1000. */
    private final int maxExecutionsPerTask;

    public ScheduleRestoreListener(FlexScheduledTaskService flexService,
                                   ILoomScheduleTriggerRepository repo,
                                   ISubTaskExecutor subTaskExecutor,
                                   TaskLimits taskLimits,
                                   ILoomScheduleExecutionRepository executionRepo,
                                   int maxExecutionsPerTask) {
        this(flexService, repo, subTaskExecutor, taskLimits, executionRepo, maxExecutionsPerTask, null);
    }

    public ScheduleRestoreListener(FlexScheduledTaskService flexService,
                                   ILoomScheduleTriggerRepository repo,
                                   ISubTaskExecutor subTaskExecutor,
                                   TaskLimits taskLimits,
                                   ILoomScheduleExecutionRepository executionRepo,
                                   int maxExecutionsPerTask,
                                   org.springframework.jdbc.core.JdbcTemplate userJdbcTemplate) {
        this.flexService = flexService;
        this.repo = repo;
        this.subTaskExecutor = subTaskExecutor;
        this.taskLimits = taskLimits != null ? taskLimits : TaskLimits.DISABLED;
        this.executionRepo = executionRepo;
        this.maxExecutionsPerTask = Math.max(10, maxExecutionsPerTask);
        this.userJdbcTemplate = userJdbcTemplate;
    }

    /** Backward-compatible constructor for callers that haven't wired the execution repo yet. */
    public ScheduleRestoreListener(FlexScheduledTaskService flexService,
                                   ILoomScheduleTriggerRepository repo,
                                   ISubTaskExecutor subTaskExecutor,
                                   TaskLimits taskLimits) {
        this(flexService, repo, subTaskExecutor, taskLimits, null, 1000, null);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreOnStartup() {
        List<LoomScheduleTriggerRecord> records = repo.findAll();
        log.info("Restoring {} loom-scheduled tasks from {}", records.size(), repo.getClass().getSimpleName());

        int restored = 0;
        int droppedExpired = 0;
        int droppedOrphan = 0;
        int failed = 0;

        java.util.Set<String> knownUsernames = loadKnownUsernames();   // null = no filter
        for (LoomScheduleTriggerRecord r : records) {
            // Orphan check skipped entirely when there is no JdbcTemplate wired
            // (legacy / test-only constructor). Returning an empty Set would
            // conflate "I have no source of truth" with "I know every username
            // is missing" → would nuke every restored task.
            if (knownUsernames != null
                    && r.username() != null
                    && !knownUsernames.contains(r.username())) {
                // Skip tasks whose owner no longer exists. Without this filter,
                // a deleted user would have their scheduled prompts fired under
                // whichever account happens to be logged in at restart time.
                repo.delete(r.taskName());
                droppedOrphan++;
                log.warn("Dropping scheduled task [{}] whose owner [{}] no longer exists",
                        r.taskName(), r.username());
                continue;
            }
            try {
                if (isExpired(r.taskName(), r.createdAt())) {
                    repo.delete(r.taskName());
                    droppedExpired++;
                    log.info("Dropping expired scheduled task [{}] (createdAt={})",
                            r.taskName(), r.createdAt());
                    continue;
                }

                // one_shot that already fired before this restart: do NOT re-register.
                // Without this guard, every restart would re-fire the one_shot 30s
                // after the restart (since `b.oneShot(delay).register(...)` always
                // fires after `delay` regardless of history). The execution log
                // already records the original fire — that's our durable audit trail.
                if (LoomScheduleTriggerRecord.TYPE_ONE_SHOT.equals(r.scheduleType())
                        && executionRepo != null
                        && executionRepo.countByTaskName(r.taskName()) > 0) {
                    log.info("Skipping restore of one_shot [{}] — already fired " +
                                    "(execution rows present in loom_schedule_execution)",
                            r.taskName());
                    restored++;   // the row was "restored" in the sense that H2 still has it
                    continue;
                }

                TaskBuilder b = flexService.task(r.taskName());
                applySchedule(b, r);
                b.createdAt(r.createdAt());
                b.register(() -> runAsSubTask(r));

                if (r.paused()) {
                    flexService.pause(r.taskName());
                }

                restored++;
                log.info("Restored scheduled task [{}] (type={}, paused={})",
                        r.taskName(), r.scheduleType(), r.paused());
            } catch (Exception e) {
                failed++;
                log.warn("Failed to restore scheduled task [{}]: {}",
                        r.taskName(), e.getMessage(), e);
            }
        }

        log.info("Schedule restore complete: restored={}, droppedExpired={}, failed={}",
                restored, droppedExpired, failed);
    }

    /**
     * Heuristic retained for documentation; no longer used by restore since V16
     * introduced {@code loom_schedule_execution} (which keeps the audit trail
     * separate from the schedule declaration). The declaration row is kept across
     * restarts even for one_shots that already fired — flex-schedule's limits
     * checker rejects re-registers that violate min-interval / max-lifetime, so
     * the row is effectively read-only from the runtime's perspective.
     */
    @Deprecated
    private boolean isOneShotOrphan(LoomScheduleTriggerRecord r) {
        Long delay = r.oneShotDelaySeconds();
        if (delay == null) return false;
        Duration expectedWindow = Duration.ofSeconds(delay).plus(Duration.ofMinutes(1));
        Duration age = Duration.between(r.createdAt(), Instant.now());
        return age.compareTo(expectedWindow) > 0;
    }

    private static void applySchedule(TaskBuilder b, LoomScheduleTriggerRecord r) {
        switch (r.scheduleType()) {
            case LoomScheduleTriggerRecord.TYPE_CRON -> b.cron(r.cronExpression());
            case LoomScheduleTriggerRecord.TYPE_FIXED_DELAY ->
                    b.fixedDelay(secondsOrZero(r.intervalSeconds()),
                            secondsOrZero(r.initialDelaySeconds()));
            case LoomScheduleTriggerRecord.TYPE_FIXED_RATE ->
                    b.fixedRate(secondsOrZero(r.intervalSeconds()),
                            secondsOrZero(r.initialDelaySeconds()));
            case LoomScheduleTriggerRecord.TYPE_ONE_SHOT ->
                    b.oneShot(secondsOrZero(r.oneShotDelaySeconds()));
            default ->
                    throw new IllegalArgumentException("Unknown schedule type: " + r.scheduleType());
        }
    }

    private static Duration secondsOrZero(Long seconds) {
        return Duration.ofSeconds(seconds == null ? 0L : seconds);
    }

    private void runAsSubTask(LoomScheduleTriggerRecord r) {
        String id = UUID.randomUUID().toString();
        SubTaskRequest req = new SubTaskRequest(
                id, r.conversationId(), null, r.username(), r.prompt(), null, true);
        long startMs = System.currentTimeMillis();
        Instant fireTime = Instant.now();
        boolean success = false;
        String errorMessage = null;
        try {
            SubTaskResult result = subTaskExecutor.execute(req);
            if (result.status() == SubTaskStatus.FAILED || result.status() == SubTaskStatus.CANCELLED) {
                errorMessage = result.errorMessage();
                throw new RuntimeException("恢复的调度子任务未完成: " + errorMessage);
            }
            success = true;
        } catch (Exception e) {
            errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("调度子任务执行失败: task={}, sub={}", r.taskName(), id, e);
        } finally {
            long durationMs = System.currentTimeMillis() - startMs;
            recordExecution(r, fireTime, durationMs, success, errorMessage);
        }
    }

    /**
     * Persist a fire event to {@code loom_schedule_execution} and trim per-task
     * history to the configured cap. The schedule's <em>declaration</em> row
     * ({@code loom_scheduled_task}) is left untouched — earlier iterations used
     * to delete it for {@code one_shot} after firing, but that wiped the audit
     * trail entirely. The declaration stays; executions accumulate; the daily
     * cleanup task ({@link ScheduleExecutionCleanup}) prunes old rows.
     */
    private void recordExecution(LoomScheduleTriggerRecord r,
                                 Instant fireTime,
                                 long durationMs,
                                 boolean success,
                                 String errorMessage) {
        if (executionRepo == null) {
            log.warn("recordExecution called but executionRepo is null — task [{}]", r.taskName());
            return;
        }
        log.info("Recording schedule execution: task={}, durationMs={}, success={}",
                r.taskName(), durationMs, success);
        try {
            executionRepo.save(new LoomScheduleExecutionRecord(
                    null,
                    r.taskName(),
                    fireTime,
                    durationMs,
                    success,
                    errorMessage,
                    LoomScheduleExecutionRecord.FIRED_BY_SCHEDULER));
            int n = executionRepo.trimTaskHistory(r.taskName(), maxExecutionsPerTask);
            if (n > 0) {
                log.debug("Trimmed {} old execution row(s) for task [{}]", n, r.taskName());
            }
        } catch (Exception e) {
            log.warn("Failed to record schedule execution [{}]: {}", r.taskName(), e.getMessage(), e);
        }
    }

    /**
     * Read distinct usernames from {@code user_info} so the restore loop can drop
     * tasks whose owner has been deleted (avoids firing a deleted user's prompt
     * under whichever account the next restart sees).
     *
     * @return the set of known usernames, or {@code null} if no {@link JdbcTemplate}
     *         was wired (signaling "no source of truth — skip the orphan check
     *         entirely"). An empty set returned from a live query still triggers
     *         the filter (every row counts as orphan), which is the correct
     *         semantics when the underlying query succeeded with no rows.
     */
    private java.util.Set<String> loadKnownUsernames() {
        if (userJdbcTemplate == null) {
            return null;   // sentinel: caller skips the orphan check entirely
        }
        try {
            return new java.util.HashSet<>(userJdbcTemplate.queryForList(
                    "SELECT username FROM user_info", String.class));
        } catch (Exception e) {
            log.warn("loadKnownUsernames failed; will fall back to no orphan filter: {}",
                    e.getMessage());
            return null;   // query failed → opt out rather than nuke everything
        }
    }

    /**
     * Mirrors {@code cn.wubo.flex.schedule.core.LimitsChecker#isExpired} but lives
     * here because {@code LimitsChecker} is package-private to flex-schedule.
     * Behavior matches: STRICT → true if past max-lifetime (caller deletes);
     * WARN → logs and returns false (allowed); OFF/no-limit → false.
     */
    private boolean isExpired(String taskName, Instant createdAt) {
        if (!taskLimits.isEnforcing() || !taskLimits.hasMaxLifetime()) return false;
        Duration age = Duration.between(createdAt, Instant.now());
        if (age.compareTo(taskLimits.maxLifetime()) < 0) return false;
        switch (taskLimits.mode()) {
            case STRICT -> {
                log.info("Task [{}] exceeded max lifetime {} (age={}), drop on restore",
                        taskName, taskLimits.maxLifetime(), age);
                return true;
            }
            case WARN -> {
                log.warn("Task [{}] exceeded max lifetime {} (age={}) — allowed due to mode=warn",
                        taskName, taskLimits.maxLifetime(), age);
                return false;
            }
            case OFF -> { /* unreachable: isEnforcing() returned false */ }
        }
        return false;
    }
}
