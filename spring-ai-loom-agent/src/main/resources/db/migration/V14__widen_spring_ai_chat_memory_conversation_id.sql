-- =============================================================
-- 扩展 SPRING_AI_CHAT_MEMORY.conversation_id 列长度
-- =============================================================
-- 背景：Spring AI 1.1.x 的 JdbcChatMemoryRepository 通过
--      `initialize-schema=always` 在 classpath:schema-h2.sql 里创建
--      SPRING_AI_CHAT_MEMORY 表时把 conversation_id 定义为 VARCHAR(36)。
--      而 loom-agent 的子任务会用
--      "{parentConversationId}--sub--{subTaskId}" 作为 conversation_id
--      写自己的 ChatMemory（参 SubTaskRequest.memoryConversationId()）：
--      父会话 ID 通常 24~32 字符 + "--sub--" (7 字符) + UUID (36 字符)
--      = 67+ 字符，触发 "Value too long for column ... VARCHAR(36)"。
--
-- 时机：Flyway 的 FlywayInitializer 被 Spring Boot 的
--       DataSourceInitializerDependsOnPostProcessor 自动添加 @DependsOn
--       到所有 DataSourceScriptDatabaseInitializer 上 (含 Spring AI 的
--       JdbcChatMemoryRepositorySchemaInitializer)，所以 Flyway 一定在
--       Spring AI 之后跑，V14 看到的表已是 VARCHAR(36)。
--
-- 防御：当从全新空库启动时,Spring AI 的 lazy init 还没跑过(没有 chat 请求),
--      SPRING_AI_CHAT_MEMORY 还不存在,Flyway 会在 V14 上失败。所以这里先
--      防御性建表(用 Spring AI 自己的 schema),再 ALTER 列宽。
-- =============================================================

CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36) NOT NULL,
    content LONGVARCHAR NOT NULL,
    type VARCHAR(10) NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE SPRING_AI_CHAT_MEMORY ALTER COLUMN conversation_id VARCHAR(255);