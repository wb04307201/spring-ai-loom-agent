package cn.wubo.spring.ai.loom.agent.schedule;

import java.time.Instant;

/**
 * Persistent representation of a single loom-agent schedule fire event.
 *
 * <p>One row per fire attempt — successful, failed, or exception. Inserted by
 * {@link ScheduleRestoreListener#runAsSubTask} after the sub-task callback
 * completes (success or failure). Distinct from the schedule's
 * <em>declaration</em> ({@link LoomScheduleTriggerRecord}, which lives in
 * {@code loom_scheduled_task}).</p>
 *
 * <p>Multiple rows can exist for a single {@code taskName} (cron / fixed
 * schedules fire repeatedly). The {@code (task_name, fire_time)} composite
 * index supports "show me the last N fires of this task" queries efficiently.</p>
 */
public record LoomScheduleExecutionRecord(
        Long executionId,        // null until inserted (IDENTITY column)
        String taskName,
        Instant fireTime,
        long durationMs,
        boolean success,
        String errorMessage,     // null when success=true
        String firedBy           // 'SCHEDULER' / 'MANUAL'
) {
    public static final String FIRED_BY_SCHEDULER = "SCHEDULER";
    public static final String FIRED_BY_MANUAL = "MANUAL";
}