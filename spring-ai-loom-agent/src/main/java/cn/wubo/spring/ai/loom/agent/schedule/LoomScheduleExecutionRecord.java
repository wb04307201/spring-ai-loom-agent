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
 *
 * <p>{@code subtaskId} (2026-11-08, c' 修复): the id of the sub-task this fire
 * dispatched, so {@code ConversationFlowService.loadScheduleFires} can pair
 * execution rows with {@code loom_subtask_history} rows <em>exactly</em> instead
 * of guessing by ±5s time proximity. NULL on rows written before the column
 * existed — the flow falls back to the time-window pairing for those.</p>
 */
public record LoomScheduleExecutionRecord(
        Long executionId, // null until inserted (IDENTITY column)
        String taskName,
        Instant fireTime,
        long durationMs,
        boolean success,
        String errorMessage, // null when success=true
        String firedBy, // 'SCHEDULER' / 'MANUAL'
        String subtaskId // id of the dispatched sub-task; null on pre-c' rows
) {
    public static final String FIRED_BY_SCHEDULER = "SCHEDULER";
    public static final String FIRED_BY_MANUAL = "MANUAL";

    /** Backward-compatible constructor: subtaskId defaults to null (unknown). */
    public LoomScheduleExecutionRecord(Long executionId, String taskName, Instant fireTime,
                                       long durationMs, boolean success, String errorMessage,
                                       String firedBy) {
        this(executionId, taskName, fireTime, durationMs, success, errorMessage, firedBy, null);
    }
}