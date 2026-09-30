-- =============================================================
-- Issue #1 修复 (2026-09-30): 给 SPRING_AI_CHAT_MEMORY 加 sequence_id 列
-- =============================================================
-- Spring AI 2.0 的 JdbcChatMemoryRepository 用 sequence_id BIGINT 排序
-- (替代 1.x 的 timestamp 排序 — 跨库一致,解决 MySQL/MariaDB TIMESTAMP 秒级精度
-- 导致同秒消息乱序的问题)。Loom 的 V1.0__init.sql 用了 1.x 旧 schema
-- (conversation_id, content, type, timestamp),没 sequence_id 列,
-- Spring AI 2.0 的查询 `... ORDER BY sequence_id` 直接报
-- BadSqlGrammarException → Issue #1 现象(本质根因是 ToolCallingAdvisor
-- 关闭内部 history,叠加 schema 不匹配才导致 LLM 收到残缺请求)。
--
-- Migration 步骤(参照 Spring AI 官方升级文档,H2 适配):
--   1. 加 sequence_id 列(允许 NULL 临时)
--   2. 按 conversation_id + timestamp 顺序回填 sequence_id(0-based)
--      用相关子查询 (H2 兼容;PostgreSQL 官方文档用 WITH ... UPDATE FROM)
--   3. 设 NOT NULL
--   4. 加 (conversation_id, sequence_id) 联合索引

ALTER TABLE SPRING_AI_CHAT_MEMORY ADD COLUMN sequence_id BIGINT;

-- 相关子查询: 同一 conversation_id 内,timestamp <= 当前行的行数减 1 = sequence_id
-- 同 timestamp 行共享 sequence_id(原 timestamp 排序也是不确定的,语义不变)
UPDATE SPRING_AI_CHAT_MEMORY
SET sequence_id = (
  SELECT COUNT(*) - 1
  FROM SPRING_AI_CHAT_MEMORY t2
  WHERE t2.conversation_id = SPRING_AI_CHAT_MEMORY.conversation_id
    AND t2.timestamp <= SPRING_AI_CHAT_MEMORY.timestamp
);

ALTER TABLE SPRING_AI_CHAT_MEMORY ALTER COLUMN sequence_id SET NOT NULL;

CREATE INDEX SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX
  ON SPRING_AI_CHAT_MEMORY(conversation_id, sequence_id);
