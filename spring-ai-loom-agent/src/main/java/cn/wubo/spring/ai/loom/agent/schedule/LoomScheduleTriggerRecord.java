package cn.wubo.spring.ai.loom.agent.schedule;

import java.time.Instant;

/**
 * Persistent representation of a loom-agent scheduled sub-task.
 *
 * <p>One row per {@code loom-sched-{username}-{conversationId}-{name}} scheduled
 * task. The {@link #scheduleType()} + expression fields (any one of
 * {@link #cronExpression()} / {@link #intervalSeconds()} / {@link #oneShotDelaySeconds()})
 * describe the trigger shape; {@link #prompt()} is the text delegated to a sub-task
 * executor on each fire.</p>
 *
 * <p>{@link #createdAt()} is honored on startup restore so the flex-schedule
 * {@code max-lifetime} ceiling still fires when expected (otherwise every restart
 * would reset the 72h clock). See
 * {@link cn.wubo.spring.ai.loom.agent.schedule.ScheduleRestoreListener}.</p>
 */
public record LoomScheduleTriggerRecord(
        String taskName,
        String scheduleType,
        String cronExpression,
        Long intervalSeconds,
        Long initialDelaySeconds,
        Long oneShotDelaySeconds,
        String prompt,
        String username,
        String conversationId,
        boolean paused,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Task types this record can carry. Matches the values flex-schedule's TaskBuilder dispatches on.
     */
    public static final String TYPE_CRON = "cron";
    public static final String TYPE_FIXED_DELAY = "fixed_delay";
    public static final String TYPE_FIXED_RATE = "fixed_rate";
    public static final String TYPE_ONE_SHOT = "one_shot";
}
