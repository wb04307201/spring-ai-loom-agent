-- =============================================================
-- V5.1：显式保存 AI 思考（reasoning / thinking）内容
-- =============================================================
-- 背景：chat_memory 的 AssistantMessage.content 只持久化文本，
-- metadata（其中 DashScope 在 enable_thinking 模式下写入的 reasoningContent）
-- 没有落库 → admin conversation.html 的"思考"折叠区永远空。
--
-- 改动：新建 loom_chat_reasoning，按 conversation_id 主键（一次对话一条最终
-- 思考），SseController 在流结束 doFinally / doOnComplete 时一次性写库。
-- ConversationFlowService 优先读这张表，没有再回退原 chat_memory 反推。
-- =============================================================

CREATE TABLE loom_chat_reasoning (
    conversation_id VARCHAR(255) PRIMARY KEY,
    reasoning_text  CLOB,
    created_at      TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(9) WITH TIME ZONE NOT NULL
);
