package cn.wubo.spring.ai.loom.agent.schedule;

import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.TaskBuilder;
import cn.wubo.flex.schedule.core.TaskLimits;
import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
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

    public ScheduleRestoreListener(FlexScheduledTaskService flexService,
                                   ILoomScheduleTriggerRepository repo,
                                   ISubTaskExecutor subTaskExecutor,
                                   TaskLimits taskLimits) {
        this.flexService = flexService;
        this.repo = repo;
        this.subTaskExecutor = subTaskExecutor;
        this.taskLimits = taskLimits != null ? taskLimits : TaskLimits.DISABLED;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreOnStartup() {
        List<LoomScheduleTriggerRecord> records = repo.findAll();
        log.info("Restoring {} loom-scheduled tasks from {}", records.size(), repo.getClass().getSimpleName());

        int restored = 0;
        int droppedExpired = 0;
        int failed = 0;

        for (LoomScheduleTriggerRecord r : records) {
            try {
                if (isExpired(r.taskName(), r.createdAt())) {
                    repo.delete(r.taskName());
                    droppedExpired++;
                    log.info("Dropping expired scheduled task [{}] (createdAt={})",
                            r.taskName(), r.createdAt());
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
        try {
            subTaskExecutor.execute(req);
        } catch (Exception e) {
            log.error("调度子任务执行失败: task={}, sub={}", r.taskName(), id, e);
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
