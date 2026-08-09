-- =============================================================
-- Spring AI LoomAgent — consolidated database schema (one-shot init)
-- =============================================================
-- 一站式初始化脚本：合并原 + + + + 五个迁移。
-- 新装环境：删除 .local/datasource/db 即可让 Flyway 从 V1 重跑。
-- 旧装环境：已通过增量迁移（V1-V5）完成 schema 升级，无需重新跑此合并脚本。
--
-- 合并要点：
-- 1. market_skill.version / user_skill.market_version 字段已移除
-- （ 之前 schema 仍含这些列，新装环境直接没有）
-- 2. market_skill 唯一约束改为 (author, name)（原是三元组）
-- 3. skill / chat_token_usage 等早期表保留（原文 + 兼容）
-- =============================================================


-- ============== 知识库 ==============

CREATE TABLE knowledge
(
 id VARCHAR(64) PRIMARY KEY,
 username VARCHAR(64) NOT NULL,
 name VARCHAR(255) NOT NULL,
 CONSTRAINT uk_username_name UNIQUE (username, name)
);
CREATE INDEX idx_knowledge_username ON knowledge(username);

CREATE TABLE knowledge_file
(
 knowledge_id VARCHAR(64) NOT NULL,
 file_id VARCHAR(64) NOT NULL,
 PRIMARY KEY (knowledge_id, file_id)
);
CREATE INDEX idx_kf_file_id ON knowledge_file(file_id);


-- ============== 文件 ==============

CREATE TABLE file_info
(
 id VARCHAR(64) PRIMARY KEY,
 username VARCHAR(64) NOT NULL,
 knowledge_id VARCHAR(64) NULL,
 file_name VARCHAR(255) NOT NULL,
 size BIGINT NOT NULL,
 upload_time TIMESTAMP NOT NULL,
 path VARCHAR(500),
 usage VARCHAR(20) NOT NULL,
 mime_type VARCHAR(256) NOT NULL DEFAULT 'application/octet-stream'
);
CREATE INDEX idx_file_username ON file_info(username);

CREATE TABLE file_document
(
 file_id VARCHAR(64) NOT NULL,
 document_id VARCHAR(64) NOT NULL,
 PRIMARY KEY (file_id, document_id)
);
CREATE INDEX idx_fd_document_id ON file_document(document_id);


-- ============== 用户 ==============

CREATE TABLE user_info
(
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(64) NOT NULL UNIQUE,
 nickname VARCHAR(64) NOT NULL,
 password VARCHAR(255) NOT NULL,
 type VARCHAR(20) NOT NULL CHECK (type IN ('ADMIN', 'USER'))
);
CREATE INDEX idx_user_info_username ON user_info(username);


-- ============== 用户会话 ==============

CREATE TABLE user_conversation
(
 username VARCHAR(64) NOT NULL,
 conversation_id VARCHAR(64) NOT NULL,
 deleted_at TIMESTAMP NULL,
 content_cleaned BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY (username, conversation_id)
);
CREATE INDEX idx_uc_conversation_id ON user_conversation(conversation_id);
CREATE INDEX idx_user_conv_deleted ON user_conversation(username, deleted_at);


-- ============== Token 用量 ==============

CREATE TABLE chat_token_usage
(
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 conversation_id VARCHAR(64) NOT NULL,
 username VARCHAR(64) NOT NULL,
 role VARCHAR(16) NOT NULL,
 prompt_tokens INTEGER NOT NULL DEFAULT 0,
 completion_tokens INTEGER NOT NULL DEFAULT 0,
 total_tokens INTEGER NOT NULL DEFAULT 0,
 model VARCHAR(64),
 duration_ms INTEGER,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_token_user_time ON chat_token_usage(username, created_at);
CREATE INDEX idx_token_conv ON chat_token_usage(conversation_id);
CREATE INDEX idx_token_time ON chat_token_usage(created_at);


-- ============== Skill 系统（旧的 per-user 表，被 Skill 市场取代） ==============
-- 旧表保留，代码不再读，仅供回滚参考

CREATE TABLE skill
(
 name VARCHAR(255) NOT NULL,
 description TEXT,
 load BOOLEAN DEFAULT TRUE,
 content TEXT,
 username VARCHAR(64) NOT NULL,
 PRIMARY KEY (name, username)
);


-- =============================================================
-- 角色 / 权限（RBAC）
-- =============================================================

CREATE TABLE role
(
 code VARCHAR(32) PRIMARY KEY,
 name VARCHAR(64) NOT NULL,
 is_system BOOLEAN NOT NULL DEFAULT FALSE,
 description TEXT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);


CREATE TABLE user_role
(
 username VARCHAR(64) NOT NULL,
 role_code VARCHAR(32) NOT NULL,
 PRIMARY KEY (username, role_code)
);


-- =============================================================
-- MCP 服务元数据
-- =============================================================

CREATE TABLE mcp_server
(
 name VARCHAR(128) PRIMARY KEY,
 title VARCHAR(128),
 description TEXT,
 is_active BOOLEAN NOT NULL DEFAULT FALSE,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_mcp_server_active ON mcp_server(is_active);


CREATE TABLE mcp_tool
(
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 mcp_name VARCHAR(128) NOT NULL,
 name VARCHAR(128) NOT NULL,
 description TEXT,
 sort_order INT NOT NULL DEFAULT 0,
 UNIQUE (mcp_name, name)
);


CREATE TABLE role_mcp
(
 role_code VARCHAR(32) NOT NULL,
 mcp_name VARCHAR(128) NOT NULL,
 sort_order INT NOT NULL DEFAULT 0,
 default_enabled BOOLEAN NOT NULL DEFAULT TRUE,
 PRIMARY KEY (role_code, mcp_name)
);
CREATE INDEX idx_role_mcp_code ON role_mcp(role_code, sort_order);


-- =============================================================
-- Skill 市场 + 用户 Skill 实例 + 角色授权
-- =============================================================

-- 公共仓库：每条记录是某个作者对某个技能某个版本的一次发布
CREATE TABLE market_skill
(
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(128) NOT NULL,
 description TEXT,
 content TEXT NOT NULL,
 author VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL, -- PENDING / APPROVED / REJECTED
 submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 reviewed_at TIMESTAMP NULL,
 reviewed_by VARCHAR(64) NULL,
 review_comment TEXT NULL,
 UNIQUE (author, name)
);
CREATE INDEX idx_market_skill_status ON market_skill(status);
CREATE INDEX idx_market_skill_approved ON market_skill(status, name, author);


-- 用户本地 Skill 实例
CREATE TABLE user_skill
(
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(64) NOT NULL,
 name VARCHAR(128) NOT NULL,
 description TEXT,
 content TEXT NOT NULL,
 source VARCHAR(16) NOT NULL, -- USER_CREATED / MARKET_PULLED / ROLE_GRANTED
 market_skill_id BIGINT NULL, -- 关联 market_skill.id（自建为 NULL）
 default_loaded BOOLEAN NOT NULL DEFAULT FALSE,
 locked BOOLEAN NOT NULL DEFAULT FALSE, -- true = ROLE_GRANTED 只读
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (username, name)
);
CREATE INDEX idx_user_skill_username ON user_skill(username);
CREATE INDEX idx_user_skill_source ON user_skill(username, source);


-- 角色授权（指向具体的市场 skill id）
CREATE TABLE role_skill
(
 role_code VARCHAR(32) NOT NULL,
 market_skill_id BIGINT NOT NULL,
 sort_order INT NOT NULL DEFAULT 0,
 default_loaded BOOLEAN NOT NULL DEFAULT TRUE,
 PRIMARY KEY (role_code, market_skill_id)
);
CREATE INDEX idx_role_skill_code ON role_skill(role_code, sort_order);


-- =============================================================
-- 默认管理员
-- 账号 wb04307201 / 密码 123456（BCrypt cost=10），登录后请立即改密
-- =============================================================

INSERT INTO user_info (username, nickname, password, type)
SELECT 'wb04307201', '吴博', '$2a$10$gZ0zgDHCVQrZueMmiiZc4u5aP1SVnjA7sy623noNR4lCqMhr/Edzy', 'ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM user_info WHERE username = 'wb04307201');


-- 本迁移把历史增量 V12~V17 合并成单一脚本。__init.sql（基础表 + 默认 admin）
-- 保持不动；本文件只追加 引入的新表 / 列。
--
-- - loom_scheduled_task 定时任务声明（原 V13）
-- - loom_schedule_execution 定时任务执行历史（原 V16）
-- - loom_subtask_history 子任务执行历史（原 V15）
-- - user_conversation 三列 侧边栏元数据 title/created_at/updated_at（原 V17，ALTER）
-- - SPRING_AI_CHAT_MEMORY conversation_id 加宽到 255（原 V14）
--
-- 已废弃：旧 V12 的 flex_scheduled_task 不再创建 —— flex-schedule 1.x 已改为纯内存
-- 实现（InMemoryTaskRepository），无 JdbcTaskRepository，该表零引用。这里保留
-- 一条防御性 DROP IF EXISTS 以清理可能残留的旧表。
-- =============================================================


-- ============== 清理已废弃的 flex_scheduled_task（原 V12） ==============
DROP TABLE IF EXISTS flex_scheduled_task;
DROP INDEX IF EXISTS idx_flex_scheduled_task_created_at;


-- =============================================================
-- 定时任务：声明表（原 V13）
-- =============================================================
-- loom-agent 自己持久化 prompt 触发的定时子任务声明。flex-schedule 1.x 纯内存,
-- 重启即丢,所以启动时由 ScheduleRestoreListener 读本表并通过 flex-schedule 的
-- TaskBuilder.createdAt(...).register(...) 重灌,保留原 createdAt 以便 max-lifetime
-- (默认 72h)上限跨重启仍生效。

CREATE TABLE loom_scheduled_task (
 task_name VARCHAR(255) PRIMARY KEY,
 schedule_type VARCHAR(20) NOT NULL, -- 'cron' | 'fixed_delay' | 'fixed_rate' | 'one_shot'
 cron_expression VARCHAR(100), -- when schedule_type = 'cron'
 interval_seconds BIGINT, -- when schedule_type IN ('fixed_delay', 'fixed_rate')
 initial_delay_seconds BIGINT, -- nullable
 one_shot_delay_seconds BIGINT, -- when schedule_type = 'one_shot'
 prompt CLOB NOT NULL, -- the sub-task prompt run on each fire
 username VARCHAR(64) NOT NULL,
 conversation_id VARCHAR(64) NOT NULL,
 paused BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP(9) WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_loom_scheduled_task_user_conv
 ON loom_scheduled_task(username, conversation_id);
CREATE INDEX idx_loom_scheduled_task_created_at
 ON loom_scheduled_task(created_at);


-- =============================================================
-- 定时任务：执行历史（原 V16）
-- =============================================================
-- 每次触发(成功 / 失败 / 异常)写一行。与 loom_scheduled_task 解耦:
-- - loom_scheduled_task -- "用户注册了什么 schedule"
-- - loom_schedule_execution -- "每次触发发生了什么"
-- 无 FK：one_shot 取消 / 过期会删声明行,但执行历史保留。

CREATE TABLE loom_schedule_execution (
 execution_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
 task_name VARCHAR(255) NOT NULL,
 fire_time TIMESTAMP(9) WITH TIME ZONE NOT NULL,
 duration_ms BIGINT NOT NULL,
 success BOOLEAN NOT NULL,
 error_message CLOB,
 fired_by VARCHAR(16) NOT NULL DEFAULT 'SCHEDULER' -- 'SCHEDULER' / 'MANUAL'(预留)
);
CREATE INDEX idx_loom_schedule_execution_task_fire
 ON loom_schedule_execution(task_name, fire_time DESC);
CREATE INDEX idx_loom_schedule_execution_fire
 ON loom_schedule_execution(fire_time);


-- =============================================================
-- 子任务执行历史（原 V15）
-- =============================================================
-- SubTaskRegistry.markFinished 走 writeHook 双写到这张表,重启不丢;
-- 前端按 (username, conversation_id) 看"这个对话发起的所有子任务"。
-- status 沿用 SubTaskStatus 枚举(RUNNING/COMPLETED/FAILED/CANCELLED),
-- 用 VARCHAR(16) 而非 CHECK 以便未来扩展枚举值。

CREATE TABLE loom_subtask_history (
 subtask_id VARCHAR(64) PRIMARY KEY,
 username VARCHAR(64) NOT NULL,
 conversation_id VARCHAR(64) NOT NULL,
 prompt CLOB NOT NULL,
 status VARCHAR(16) NOT NULL,
 started_at BIGINT NOT NULL, -- System.currentTimeMillis
 finished_at BIGINT NOT NULL,
 error_message CLOB,
 result_text CLOB
);
CREATE INDEX idx_loom_subtask_history_user_conv
 ON loom_subtask_history(username, conversation_id);
CREATE INDEX idx_loom_subtask_history_user_finished
 ON loom_subtask_history(username, finished_at DESC);


-- =============================================================
-- 会话侧边栏元数据（原 V17）
-- =============================================================
-- 给 建的 user_conversation 追加三列：持久化空会话 + 用户自定义标题。

ALTER TABLE user_conversation ADD COLUMN title VARCHAR(100);
ALTER TABLE user_conversation ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE user_conversation ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX idx_user_conv_created ON user_conversation(username, deleted_at, created_at DESC);


-- =============================================================
-- 扩展 SPRING_AI_CHAT_MEMORY.conversation_id 列宽（原 V14）
-- =============================================================
-- Spring AI 的 JdbcChatMemoryRepository(initialize-schema=always)把
-- conversation_id 建成 VARCHAR(36)。而子任务用
-- "{parentConversationId}--sub--{subTaskId}" 作为 conversation_id(67+ 字符),
-- 会触发 "Value too long for column ... VARCHAR(36)"。
--
-- Flyway 一定在 Spring AI 的 schema init 之后跑(DataSourceInitializerDependsOn-
-- PostProcessor 加了 @DependsOn),所以这里看到的表已是 VARCHAR(36)。全新空库若
-- 还没发过 chat 请求,该表尚不存在 —— 先防御性建表(Spring AI 同款 schema),再 ALTER。

CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
 conversation_id VARCHAR(36) NOT NULL,
 content LONGVARCHAR NOT NULL,
 type VARCHAR(10) NOT NULL,
 timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
ALTER TABLE SPRING_AI_CHAT_MEMORY ALTER COLUMN conversation_id VARCHAR(255);


-- 知识库增加描述字段
ALTER TABLE knowledge ADD COLUMN description VARCHAR(500) NULL;

-- 对话增加启用知识库ID列表（JSON格式存储）
ALTER TABLE user_conversation ADD COLUMN enabled_knowledge_ids VARCHAR(1000) NULL;

-- 知识库存储抽象：新增 loom_file_content 表用于数据库模式下的文件内容存储
-- 知识库市场：新增 loom_market_knowledge / loom_user_knowledge / loom_role_knowledge 表

CREATE TABLE IF NOT EXISTS loom_file_content (
 file_id VARCHAR(36) PRIMARY KEY,
 content BLOB NOT NULL,
 mime_type VARCHAR(100),
 knowledge_id VARCHAR(64),
 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_loom_file_content_knowledge_id ON loom_file_content(knowledge_id);

-- =============================================================
-- 知识库市场：市场知识库条目
-- =============================================================

CREATE TABLE IF NOT EXISTS loom_market_knowledge (
 id VARCHAR(36) PRIMARY KEY,
 username VARCHAR(64) NOT NULL,
 name VARCHAR(200) NOT NULL,
 description TEXT,
 status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
 submitted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 reviewed_at TIMESTAMP,
 reviewed_by VARCHAR(64),
 review_comment TEXT,
 UNIQUE(username, name)
);

CREATE INDEX IF NOT EXISTS idx_loom_market_knowledge_status ON loom_market_knowledge(status);
CREATE INDEX IF NOT EXISTS idx_loom_market_knowledge_username ON loom_market_knowledge(username);

-- =============================================================
-- 知识库市场：用户订阅的知识库
-- =============================================================

CREATE TABLE IF NOT EXISTS loom_user_knowledge (
 username VARCHAR(64) NOT NULL,
 market_knowledge_id VARCHAR(36) NOT NULL,
 source VARCHAR(20) NOT NULL CHECK (source IN ('USER_CREATED', 'MARKET_PULLED', 'ROLE_GRANTED')),
 locked BOOLEAN DEFAULT FALSE,
 PRIMARY KEY (username, market_knowledge_id)
);

-- =============================================================
-- 知识库市场：角色 - 知识库关联
-- =============================================================

CREATE TABLE IF NOT EXISTS loom_role_knowledge (
 role_code VARCHAR(50) NOT NULL,
 market_knowledge_id VARCHAR(36) NOT NULL,
 default_enabled BOOLEAN DEFAULT FALSE,
 sort_order INT DEFAULT 0,
 PRIMARY KEY (role_code, market_knowledge_id)
);

CREATE INDEX IF NOT EXISTS idx_loom_role_knowledge_role ON loom_role_knowledge(role_code);


-- 背景：控制台 conversation.html 重写为「单次会话全量时间线」视图，
-- 需 tool_call / subtask / schedule 等多源数据。当前 token_usage 表只记录
-- turn 级别的 token 统计，缺少 tool 调用入参/返回，无法在控制台展示完整
-- 对话流转。
--
-- 改动：
-- 1. 新建 loom_tool_call_log 工具调用入参/返回/耗时（替代从 chat_memory.metadata
-- 反向解析 JSON 的脆弱方式）
-- 2. DROP token_usage 旧 turn 统计表（后统计从 chat_memory + tool_call_log
-- 实时聚合，不再维护重复数据）
-- 3. 加索引 tool_call_log 按 conversation_id + created_at 复合索引，
-- 支持 conversation.html 时间线 / 分页高效查询
-- =============================================================


-- ============== 1. 新建 loom_tool_call_log ==============
CREATE TABLE loom_tool_call_log (
 log_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
 conversation_id VARCHAR(255) NOT NULL,
 username VARCHAR(64) NOT NULL,
 tool_call_id VARCHAR(128) NOT NULL, -- Spring AI tool call id，用于和 ToolResponseMessage 关联
 tool_name VARCHAR(128) NOT NULL,
 arguments_json CLOB, -- 工具入参 JSON（截断 64KB）
 result_text CLOB, -- 工具返回值（截断 64KB）
 result_is_error BOOLEAN NOT NULL DEFAULT FALSE, -- 是否错误返回
 duration_ms BIGINT,
 created_at TIMESTAMP(9) WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_loom_tool_call_log_conv_time
 ON loom_tool_call_log(conversation_id, created_at);
CREATE INDEX idx_loom_tool_call_log_user_time
 ON loom_tool_call_log(username, created_at DESC);


-- ============== 2. DROP token_usage ==============
-- 旧 turn 级 token 统计表。后：
-- - stats.html 全局月度 token 从 chat_memory 实时聚合
-- - user.html 用户最近 6 月 从 chat_memory 实时聚合
-- - conversation.html 单会话 从 chat_memory 实时聚合
-- chat_memory 中 AssistantMessage.metadata 已存 usage metadata
-- （prompt_tokens / completion_tokens / total_tokens），无需重复落库。
DROP TABLE IF EXISTS token_usage;
DROP INDEX IF EXISTS idx_token_usage_conv_time;
DROP INDEX IF EXISTS idx_token_usage_user_time;


-- ============== 3. 新建 loom_chat_usage（ 合并） ==============
-- 原计划从 chat_memory 反推 usage metadata，交付时发现 AssistantMessage.content
-- 只持久化文本、metadata 未落库 → 改显式记录。每次 LLM 流响应在 SseController
-- 写一行，ChatUsageService 查这张表（不依赖 chat_memory 反推）。
CREATE TABLE loom_chat_usage (
 log_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
 conversation_id VARCHAR(255) NOT NULL,
 username VARCHAR(64) NOT NULL,
 prompt_tokens BIGINT NOT NULL DEFAULT 0,
 completion_tokens BIGINT NOT NULL DEFAULT 0,
 total_tokens BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(9) WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_loom_chat_usage_user_time
 ON loom_chat_usage(username, created_at DESC);
CREATE INDEX idx_loom_chat_usage_conv_time
 ON loom_chat_usage(conversation_id, created_at);


-- ============== 4. 新建 loom_chat_reasoning（ 合并） ==============
-- 背景：chat_memory 同样不持久化 metadata.reasoningContent（DashScope
-- enable_thinking 模式下的 AI 思考）。SseController 在流结束 doOnComplete
-- 时一次性写完整 reasoning；ConversationFlowService 优先读这张表，绑到
-- conversation.html 第一条 ASSISTANT 卡片的"思考"折叠区。
CREATE TABLE loom_chat_reasoning (
 conversation_id VARCHAR(255) PRIMARY KEY,
 reasoning_text CLOB,
 created_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP(9) WITH TIME ZONE NOT NULL
);
