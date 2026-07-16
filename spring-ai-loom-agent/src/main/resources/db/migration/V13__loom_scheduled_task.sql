-- Persistent storage for loom-agent's prompt-triggered scheduled sub-tasks.
--
-- Path B migration: drop the flex-schedule-owned flex_scheduled_task (V12) which
-- was never actually written to (the JdbcTaskRepository impl was removed from the
-- flex-schedule library in the same release). loom-agent now owns its own table
-- whose columns fit the prompt-trigger model:
--
--   {username, conversation_id, prompt, schedule_type + expression}
--
-- Restoration is handled at startup by ScheduleRestoreListener
-- (cn.wubo.spring.ai.loom.agent.schedule.ScheduleRestoreListener) which reads
-- this table once on ApplicationReadyEvent and rehydrates the in-memory scheduler
-- via flex-schedule's TaskBuilder.createdAt(...).register(...) chain, preserving
-- original createdAt so the flex-schedule max-lifetime ceiling (72h) still applies.

DROP TABLE IF EXISTS flex_scheduled_task;
DROP INDEX IF EXISTS idx_flex_scheduled_task_created_at;

CREATE TABLE loom_scheduled_task (
    task_name             VARCHAR(255) PRIMARY KEY,
    schedule_type         VARCHAR(20) NOT NULL,                     -- 'cron' | 'fixed_delay' | 'fixed_rate' | 'one_shot'
    cron_expression       VARCHAR(100),                              -- when schedule_type = 'cron'
    interval_seconds      BIGINT,                                    -- when schedule_type IN ('fixed_delay', 'fixed_rate')
    initial_delay_seconds BIGINT,                                    -- nullable
    one_shot_delay_seconds BIGINT,                                   -- when schedule_type = 'one_shot'
    prompt                CLOB NOT NULL,                             -- the sub-task prompt run on each fire
    username              VARCHAR(64) NOT NULL,
    conversation_id       VARCHAR(64) NOT NULL,
    paused                BOOLEAN NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP(9) WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_loom_scheduled_task_user_conv
    ON loom_scheduled_task(username, conversation_id);

CREATE INDEX idx_loom_scheduled_task_created_at
    ON loom_scheduled_task(created_at);
