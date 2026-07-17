-- =============================================================
-- Persistent storage for loom-agent sub-task execution history.
-- =============================================================
-- 背景：之前的 SubTaskRegistry 把 history 完全放在 JVM 内存 (per-user
--      ArrayDeque, maxHistory=200),重启即丢。这次新增 REST 路由按 conversationId
--      过滤 + 把历史持久化到 H2,这样:
--        - 重启后历史不丢
--        - 前端可以按父对话维度看"这个对话发起的所有子任务"
--
-- 写路径：SubTaskRegistry.markFinished 走 writeHook (Consumer<SubTaskRecord>)
--         双写到这张表。读取直接走 SQL,带 (username, conversation_id) 复合索引
--         和 (finished_at DESC) 单列索引以支持"最近 N 条"分页。
--
-- status 列沿用 cn.wubo.spring.ai.loom.agent.model.SubTaskStatus 枚举
-- (RUNNING / COMPLETED / FAILED / CANCELLED)。schema 用 VARCHAR(16)
-- 而非 CHECK 约束,以便未来扩展枚举值而不写新迁移。
-- =============================================================

CREATE TABLE loom_subtask_history (
    subtask_id      VARCHAR(64)  PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,
    prompt          CLOB         NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    started_at      BIGINT       NOT NULL,                -- System.currentTimeMillis
    finished_at     BIGINT       NOT NULL,
    error_message   CLOB,
    result_text     CLOB
);

CREATE INDEX idx_loom_subtask_history_user_conv
    ON loom_subtask_history(username, conversation_id);

CREATE INDEX idx_loom_subtask_history_user_finished
    ON loom_subtask_history(username, finished_at DESC);