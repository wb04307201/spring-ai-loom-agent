-- Persistent storage for flex-schedule tasks (H2).
-- Created both here (loom migration) and at runtime in JdbcTaskRepository.ensureSchema()
-- so the table works whether or not Flyway runs first.
-- Column types/names mirror JdbcTaskRepository.DDL exactly; TIMESTAMP(9) WITH TIME ZONE
-- preserves nanosecond precision in H2.

CREATE TABLE IF NOT EXISTS flex_scheduled_task (
    task_name VARCHAR(255) PRIMARY KEY,
    type VARCHAR(20) NOT NULL,
    cron_expression VARCHAR(100),
    timezone VARCHAR(50),
    interval_ms BIGINT,
    initial_delay_ms BIGINT,
    delay_ms BIGINT,
    timeout_ms BIGINT,
    retry_policy_json CLOB,
    bean_name VARCHAR(255),
    method_name VARCHAR(255),
    method_params_json CLOB,
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(9) WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_flex_scheduled_task_created_at
    ON flex_scheduled_task(created_at);