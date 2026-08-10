package cn.wubo.spring.ai.loom.agent.schedule;

import java.time.Instant;
import java.util.List;

/**
 * Persistence boundary for loom-agent's schedule <em>execution event log</em>.
 * <p>
 * Distinct from {@link ILoomScheduleTriggerRepository}, which stores the schedule's
 * <em>declaration</em> (cron expression, prompt, etc.). This repository stores
 * one row per fire attempt so that history survives application restarts and
 * one_shot completions no longer wipe out the audit trail.
 * </p>
 */
public interface ILoomScheduleExecutionRepository {

    /**
     * Insert a new execution row. The {@code executionId} is assigned by the DB.
     */
    void save(LoomScheduleExecutionRecord record);

    /**
     * Newest-first executions for the given task, capped at {@code limit}.
     */
    List<LoomScheduleExecutionRecord> findByTaskName(String taskName, int limit);

    /**
     * Total execution count for a task — used to drive the per-task max-history trim.
     */
    int countByTaskName(String taskName);

    /**
     * Per-task trim: keep at most {@code keepLast} newest rows for {@code taskName},
     * delete the rest. Idempotent. Returns the number of rows deleted.
     */
    int trimTaskHistory(String taskName, int keepLast);

    /**
     * Delete every row with {@code fire_time < cutoff}. Returns affected row count.
     */
    int deleteOlderThan(Instant cutoff);

    /**
     * Cascade-delete every execution row whose task_name starts with
     * {@code loom-sched-{username}-{conversationId}-}. Used when a conversation
     * is deleted so its schedule audit trail goes with it. Returns affected row count.
     */
    int deleteByUserAndConversation(String username, String conversationId);

    /**
     * Defensive schema bootstrap (mirrors V16).
     */
    void ensureSchema();
}