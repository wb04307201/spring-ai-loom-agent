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
--
-- M3+ T5.1 — 源码组织拆分（policy A）
-- ---------------------------------
-- CLAUDE.md 政策：项目只跑全新库；不接受已有实例上增量升级 schema。
-- 因此本文件保持 V1.0__init.sql 单文件，fresh init 一次跑全。
-- 拆分靠 SQL 注释段标记，不靠多文件：
--
--   § 1  知识库 / 文件 / 用户 / 用户会话 / Token 用量
--   § 2  Skill 系统（旧 per-user 表，被 Skill 市场取代）
--   § 3  RBAC + Role / Skill / Knowledge / MCP 关联
--   § 4  Skill + Knowledge 市场（M0/M1/M2 升级后）
--   § 5  公告 / Stats / Review / Tag 等市场辅助表
--   § 6  Schedule / SubTask / File content / Tool call log / Chat reasoning / Chat usage
--   § 7  M3+ technical debt cleanup（spec §4.1 + §4.2：A12 加 updated_at + B1 market_id BIGINT→VARCHAR(36)）
--   § 8  清理已废弃的 flex_scheduled_task（原 V12）
--   § 9  Flyway baseline 标记（仅新装生效）
--
-- 各段用 `-- ===== § N: ... =====` 分隔，可读性 + grep 友好。
-- 若未来政策调整为多文件 Flyway 增量演进，本文件可直接 split；
-- 当前 §N 标号与未来 V_<n>__<name>.sql 文件名一一对应。
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


-- =============================================================
-- 本地工具组 RBAC：role_tool（原 V2.1）
-- =============================================================
-- 镜像 role_mcp 的设计：
--   - role_code    : 业务角色代码（与 role 表 code 对齐）
--   - group_name   : 本地工具组 slug（@ToolGroup 注解值 + "tool_" 前缀，
--                    例如 IFileTool(@ToolGroup("file")) → group_name = "tool_file"）
--   - default_enabled: TRUE → 用户的聊天面板 checkbox 默认勾选（用户可手动取消）
--   - sort_order   : 角色授权列表的展示顺序
--
-- 设计要点：
--   1. group_name 用 VARCHAR(64) 容纳 "tool_" 前缀（最多 32+1+32）。
--   2. 镜像 role_mcp 的 default_enabled 默认 TRUE。
--   3. 不 seed 默认 admin 授权（admin 账号首次部署后必须手动进控制台授权）—— Q12 决定。
--   4. 不创建 user_tool_enabled 表：用户级 checkbox 状态由前端 state 持有。
-- =============================================================

CREATE TABLE role_tool
(
    role_code       VARCHAR(32)  NOT NULL,
    group_name      VARCHAR(64)  NOT NULL,
    sort_order      INT          NOT NULL DEFAULT 0,
    default_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (role_code, group_name)
);
CREATE INDEX idx_role_tool_code ON role_tool(role_code, sort_order);


-- =============================================================
-- Role 删除 cascade（原 V2.2，B.2.1 修复）
-- =============================================================
-- user_role / role_mcp / role_tool 三张子表对 role.code 都没有 FK 约束,
-- 也没有应用层 cascade。删除 role 时,这三张表的子行变成 dangling 引用。
--
-- 修复:
--   1. 加 FK 约束 + ON DELETE CASCADE,DB 层保证
--   2. 应用层 deleteOrThrow 显式 DELETE 子表(防御性 + SQL 可见)
-- 对已运行实例也兼容(只是补加约束,不影响已有数据)。
-- =============================================================

-- 1. user_role.role_code → role.code
ALTER TABLE user_role
    ADD CONSTRAINT fk_user_role_role
    FOREIGN KEY (role_code) REFERENCES role(code) ON DELETE CASCADE;

-- 2. role_mcp.role_code → role.code
ALTER TABLE role_mcp
    ADD CONSTRAINT fk_role_mcp_role
    FOREIGN KEY (role_code) REFERENCES role(code) ON DELETE CASCADE;

-- 3. role_tool.role_code → role.code
ALTER TABLE role_tool
    ADD CONSTRAINT fk_role_tool_role
    FOREIGN KEY (role_code) REFERENCES role(code) ON DELETE CASCADE;


-- =============================================================
-- V2.3: user_role.username 加 FK + CASCADE 修 P0.3.3 bug
-- =============================================================
-- 背景:user_role.username 之前没 FK,删除 user 时 user_role 行
-- 留为 orphan。补上 FK,让 user 删除自动清理 user_role。

-- 1) Cleanup pre-existing orphan user_role rows (safety)
DELETE FROM user_role
WHERE username NOT IN (SELECT username FROM user_info);

-- 2) Add FK constraint (CASCADE so user delete also drops user_role)
ALTER TABLE user_role
    ADD CONSTRAINT fk_user_role_user
    FOREIGN KEY (username) REFERENCES user_info(username) ON DELETE CASCADE;


-- =============================================================
-- V2.4: M6 清理 role_tool 表里 6 个 universal 工具的历史授权记录
-- =============================================================
-- 背景:M6 引入 @ToolGroup(defaultGranted=true) 机制,把
--   schedule / subtask / knowledge / time / skill / file
-- 6 个工具标记为"平台默认能力",对所有登录用户可见,与 role_tool
-- RBAC 完全解耦。admin UI 不展示这 6 个工具的可增删入口,数据库
-- 端保留旧行只会带来误导("为什么这个 role 还显示 schedule 已被
-- 授权但我又没法移除它?")。
--
-- 本 migration 一次性 DELETE 这 6 个 group_name 的所有 role_tool
-- 行。后续 INSERT 也由 CapabilityService.defaultGranted 硬约束
-- 不写入;DDL 层不强制阻止(保留 schema 灵活性),但代码路径上
-- 不会再有新增。
--
-- 安全:DELETE 而非 TRUNCATE,只动指定 group_name;如果某些环境
-- 期望保留这些记录做审计,可注释掉本文件后手动跑。
-- =============================================================

DELETE FROM role_tool
WHERE group_name IN (
    'tool_schedule',
    'tool_subtask',
    'tool_knowledge',
    'tool_time',
    'tool_skill',
    'tool_file'
);

-- ==== M0 market upgrade (spec § 4) ====

-- 公共列(skill + knowledge 两表都加)
ALTER TABLE market_skill          ADD COLUMN is_official     BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE loom_market_knowledge ADD COLUMN is_official     BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE market_skill          ADD COLUMN featured_rank   INT NOT NULL DEFAULT 0;
ALTER TABLE loom_market_knowledge ADD COLUMN featured_rank   INT NOT NULL DEFAULT 0;

ALTER TABLE market_skill          ADD COLUMN category        VARCHAR(64);
ALTER TABLE loom_market_knowledge ADD COLUMN category        VARCHAR(64);

ALTER TABLE market_skill          ADD COLUMN created_by_kind VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE loom_market_knowledge ADD COLUMN created_by_kind VARCHAR(16) NOT NULL DEFAULT 'USER';

-- 附表
CREATE TABLE market_skill_stats (
  market_skill_id BIGINT PRIMARY KEY,
  pull_count BIGINT NOT NULL DEFAULT 0,
  last_pulled_at TIMESTAMP,
  FOREIGN KEY (market_skill_id) REFERENCES market_skill(id) ON DELETE CASCADE
);

CREATE TABLE market_skill_review (
  market_skill_id BIGINT NOT NULL,
  username VARCHAR(64) NOT NULL,
  rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
  comment TEXT,
  edit_count SMALLINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (market_skill_id, username),
  FOREIGN KEY (market_skill_id) REFERENCES market_skill(id) ON DELETE CASCADE
);
CREATE INDEX idx_market_skill_review ON market_skill_review(username);

CREATE TABLE loom_market_knowledge_stats (
  market_id BIGINT PRIMARY KEY,
  search_count BIGINT NOT NULL DEFAULT 0,
  last_searched_at TIMESTAMP,
  FOREIGN KEY (market_id) REFERENCES loom_market_knowledge(id) ON DELETE CASCADE
);

CREATE TABLE loom_market_knowledge_review (
  market_id BIGINT NOT NULL,
  username VARCHAR(64) NOT NULL,
  rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
  comment TEXT,
  edit_count SMALLINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (market_id, username),
  FOREIGN KEY (market_id) REFERENCES loom_market_knowledge(id) ON DELETE CASCADE
);
CREATE INDEX idx_market_kb_review ON loom_market_knowledge_review(username);

CREATE TABLE market_content_announcement (
  market_kind VARCHAR(16) NOT NULL,
  market_id BIGINT NOT NULL,
  title VARCHAR(128) NOT NULL,
  body TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (market_kind, market_id)
);

-- 索引建议(性能)
CREATE INDEX idx_market_skill_official_rank   ON market_skill(is_official DESC, featured_rank DESC);
CREATE INDEX idx_market_kb_official_rank      ON loom_market_knowledge(is_official DESC, featured_rank DESC);
CREATE INDEX idx_market_skill_status_approved ON market_skill(status, is_official DESC, featured_rank DESC);
CREATE INDEX idx_market_kb_status_approved    ON loom_market_knowledge(status, is_official DESC, featured_rank DESC);
CREATE INDEX idx_market_skill_category        ON market_skill(category);
CREATE INDEX idx_market_kb_category           ON loom_market_knowledge(category);

ALTER TABLE loom_user_knowledge ADD COLUMN access_count BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_user_knowledge_access_check ON loom_user_knowledge(username, market_knowledge_id, access_count);

-- M2 T20: KB 多对多 tag — spec § 4.2 / § M2。
-- market_id 与 loom_market_knowledge.id 对齐(VARCHAR(36) UUID,NOT BIGINT)。
-- 删除 KB 时通过 FK ON DELETE CASCADE 自动清理 tag 行。
CREATE TABLE loom_market_knowledge_tag (
  market_id VARCHAR(36) NOT NULL,
  tag       VARCHAR(64) NOT NULL,
  PRIMARY KEY (market_id, tag),
  FOREIGN KEY (market_id) REFERENCES loom_market_knowledge(id) ON DELETE CASCADE
);
CREATE INDEX idx_market_kb_tag ON loom_market_knowledge_tag(tag);

-- =============================================================
-- ==== M3+ technical debt cleanup (spec § 4.1 + § 4.2) ====
-- B1 真修:market_content_announcement / loom_market_knowledge_stats /
--          loom_market_knowledge_review 三张表的 market_id 历史定义成 BIGINT,
--          与 loom_market_knowledge.id (VARCHAR(36) UUID) 类型不一致。
--          统一为 VARCHAR(36)。
-- A12 列错位:market_skill / loom_user_knowledge 缺 updated_at 时间戳。
-- =============================================================

-- A12: 添加 updated_at 列
ALTER TABLE market_skill        ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE loom_user_knowledge ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- B1: market_id 类型对齐 VARCHAR(36)
-- H2 2.3.232 直接 ALTER COLUMN 类型即可,FK 在原位保留,无需 DROP/ADD。
ALTER TABLE market_content_announcement   ALTER COLUMN market_id VARCHAR(36);
ALTER TABLE loom_market_knowledge_stats  ALTER COLUMN market_id VARCHAR(36);
ALTER TABLE loom_market_knowledge_review ALTER COLUMN market_id VARCHAR(36);

-- =============================================================
-- ==== M4 market enhancements (T4: skill tag system) ====
-- M4: 技能多对多 tag —— 镜像 loom_market_knowledge_tag(列名随 skill 附表约定 market_skill_id)。
-- market_skill_id 与 market_skill.id 对齐(BIGINT,NOT VARCHAR)。
-- 删除 skill 时通过 FK ON DELETE CASCADE 自动清理 tag 行。
-- =============================================================
CREATE TABLE market_skill_tag (
  market_skill_id BIGINT NOT NULL,
  tag             VARCHAR(64) NOT NULL,
  PRIMARY KEY (market_skill_id, tag),
  FOREIGN KEY (market_skill_id) REFERENCES market_skill(id) ON DELETE CASCADE
);
CREATE INDEX idx_market_skill_tag ON market_skill_tag(tag);

-- =============================================================
-- #4 市场审批流:REJECTED 行重投时旧行归档表(只增不删,供追溯)
-- 主表 UNIQUE(author/username, name) 不动;重投 = 旧行挪进 archive + 主表新建 PENDING 行(新 id)
-- 归档行保留原主键值(非自增),便于与原行对应。
-- =============================================================
CREATE TABLE market_skill_archive (
  id BIGINT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  description TEXT,
  content TEXT NOT NULL,
  author VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  submitted_at TIMESTAMP NOT NULL,
  reviewed_at TIMESTAMP NULL,
  reviewed_by VARCHAR(64) NULL,
  review_comment TEXT NULL,
  archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE loom_market_knowledge_archive (
  id VARCHAR(36) PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  name VARCHAR(200) NOT NULL,
  description TEXT,
  status VARCHAR(20) NOT NULL,
  submitted_at TIMESTAMP,
  reviewed_at TIMESTAMP,
  reviewed_by VARCHAR(64),
  review_comment TEXT,
  archived_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =============================================================
-- #3 H2 向量存储:H2JVectorStore 持久层(向量 + 文档 + metadata)
-- 替代旧 ~/.loom/jvector-index/{docs,ids}.json;启动 ApplicationReadyEvent
-- 时全量 hydrate 进内存 JVector HNSW 图,消灭启动 re-embed。
-- 不加 username/knowledge_id 列(隔离档 A:knowledgeId 在 metadata_json 内,
-- SpEL 后过滤照旧)。dim 列是换 embedding 模型守卫(不匹配行加载时跳过)。
-- =============================================================
CREATE TABLE IF NOT EXISTS loom_vector_store (
  document_id   VARCHAR(64) PRIMARY KEY,
  content       CLOB NOT NULL,
  metadata_json CLOB NOT NULL,
  embedding     BLOB NOT NULL,
  dim           INT NOT NULL,
  score         DOUBLE,
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_loom_vector_store_created ON loom_vector_store(created_at);

-- =============================================================
-- ==== 官方种子技能：一问一答表达训练(#4 follow-up,spec 2026-09-08)====
-- 两条 market_skill:author=system / APPROVED / is_official / created_by_kind=ADMIN
-- / category=表达沟通。fresh-DB 政策:本段随全新库执行一次;
-- WHERE NOT EXISTS 幂等(镜像默认 admin 种子先例),UNIQUE(author,name) 双保险。
-- 内容三铁律:①不硬编码工具名(只描述"提问能力");②防 qwen 自白死循环;③纯文本。
-- =============================================================

INSERT INTO market_skill (name, description, content, author, status,
                          reviewed_at, reviewed_by,
                          is_official, created_by_kind, category)
SELECT 'STAR-IJ 讲清一件事',
       '用户想「讲清楚一件事」(项目复盘 / 绩效述职 / 面试准备 / 经验沉淀)时触发：按 情境-任务-行动-结果-项目价值-个人成长 六步一问一答收集信息，汇总成结构化叙述与 30 秒电梯稿',
       '用户想「讲清楚一件事」（项目复盘 / 绩效述职 / 面试准备 / 经验沉淀）时触发本技能。
你的任务：按 STAR-IJ 六步（S情境 - T任务 - A行动 - R结果 - I项目价值 - J个人成长）逐步向用户提问，每步一次提问，收集完成后汇总成结构化叙述。

⛔ 执行纪律（必读，否则任务失败）：
1. 不要描述你打算做什么 ——「我将为您梳理 / 接下来我会问」这类自白没有意义，直接发起提问。
2. 每次提问后必须等待用户真实回答，不得代替用户作答、不得自问自答、不得把引导选项当成用户的选择。
3. 一次只问一步。用户答完当前步骤再问下一步，不要一次抛出多个问题。
4. 信息足够时立即进入汇总，不要为凑步数反复追问。

提问方式：使用你的提问能力（向用户发起带选项的提问卡片），每步提供 3-4 个引导选项并允许自由输入。用户选择或输入后，仅在「行动 / 结果」两步可追问一轮细节，然后进入下一步。

六步提问流程：
第 1 步 S 情境：这件事发生的背景是什么？（时间、场合、当时的局面）
  引导选项示例：项目启动期 / 攻坚期 / 收尾复盘 / 自由描述
第 2 步 T 任务：你在其中承担什么角色、要达成什么目标？
  引导选项示例：整体负责人 / 核心执行 / 协作支持 / 自由描述
第 3 步 A 行动：你具体做了哪些关键动作？（引导用户拆成 2-4 个动作，可多选，可追问一轮细节）
  引导选项示例：方案设计 / 资源协调 / 技术攻坚 / 沟通推进
第 4 步 R 结果：取得了什么可量化的成果？
  追问策略：用户答得笼统时，追问一次「有没有具体数字？对比之前改善多少？」，只追问一次。
第 5 步 I 项目价值：这件事对团队 / 业务 / 用户产生了什么意义？
  引导选项示例：提效 / 降本 / 增收 / 风险控制 / 体验改善
第 6 步 J 个人成长：你从中学到了什么、哪些能力得到提升？
  引导选项示例：方法论沉淀 / 技术突破 / 协作能力 / 认知升级

汇总产出（六步答完后立即输出，不要再提问）：
1. 结构化叙述：按 S/T/A/R/I/J 六段展开，每段 2-4 句，保留用户原话中的关键数字与细节。
2. 一句话版本：30 秒电梯稿，突出结果与价值。
3. 如用户说明了用途（面试 / 述职 / 复盘），按该场景调整语气与详略。

边界处理：
- 用户某步拒答或答「不知道」：记录该步为「略过」，继续下一步，汇总时如实标注。
- 用户跑题：温和拉回当前步骤的问题。
- 用户中途要求直接汇总：用已收集的信息立即汇总，缺失步骤标注「未提供」。',
       'system', 'APPROVED', CURRENT_TIMESTAMP, 'system',
       TRUE, 'ADMIN', '表达沟通'
WHERE NOT EXISTS (SELECT 1 FROM market_skill WHERE author = 'system' AND name = 'STAR-IJ 讲清一件事');

INSERT INTO market_skill (name, description, content, author, status,
                          reviewed_at, reviewed_by,
                          is_official, created_by_kind, category)
SELECT '靶心人公式 讲好一个故事',
       '用户想「讲好一个故事」(品牌故事 / 演讲 / 个人经历分享 / 短视频脚本)时触发：按 目标-阻碍-努力-结果-意外-转弯-结局 七步一问一答引导，汇总成有张力的故事与故事骨架',
       '用户想「讲好一个故事」（品牌故事 / 演讲 / 个人经历分享 / 短视频脚本）时触发本技能。
你的任务：按「靶心人公式」七步逐步向用户提问，收集完成后汇总成一个有张力的故事。

靶心人公式七步：目标 → 阻碍 → 努力 → 结果 → 意外 → 转弯 → 结局。
（注意：第六步的原词是「转弯」，指意外给主角或局面带来的转变，不是简单的「转折」。）

⛔ 执行纪律（必读，否则任务失败）：
1. 不要描述你打算做什么 ——「我将帮您打磨故事」这类自白没有意义，直接发起提问。
2. 每次提问后必须等待用户真实回答，不得代替用户作答、不得自问自答、不得虚构用户没说的细节。
3. 一次只问一步，用户答完再问下一步。
4. 信息足够时立即进入汇总产出，不要为凑满七步硬追问。

提问方式：使用你的提问能力（向用户发起带选项的提问卡片），每步提供引导选项并允许自由输入。第 1 步提问的背景说明里顺带确认故事主角与场合（如「这是你自己的经历，还是品牌 / 产品的故事？」），不要为此单独多问一轮。

七步提问流程：
第 1 步 目标：主角想要什么？（一句话目标，越具体越好）
第 2 步 阻碍：什么在阻挡主角？（人 / 事 / 环境 / 自身局限）
第 3 步 努力：主角为克服阻碍做了什么？（可追问 1-2 轮细节，好故事需要具体动作）
第 4 步 结果：努力的直接结果如何？（常见是没成功或只部分成功 —— 这正是故事的张力所在，如实收集，不要美化）
第 5 步 意外：出现了什么意料之外的事？
第 6 步 转弯：这个意外让主角或局面发生了什么转变？（认知、策略、关系的转变）
第 7 步 结局：最终如何收场？你希望听众记住什么？

快速变体（按素材复杂度自选，并在提问前一句话告知用户所用版本）：
- 努力人公式（4 步）：目标 → 阻碍 → 努力 → 结局。适合简单场景、时间有限的用户。
- 意外人公式（4 步）：目标 → 意外 → 转弯 → 结局。适合反转突出的故事。
判断依据：第 4、5 步若用户表示「没有意外 / 一切顺利」，主动建议改用努力人公式，不硬编七步。

汇总产出（收集完成后立即输出，不要再提问）：
1. 连贯故事文本：300-600 字，按七步（或所选变体）推进，保留用户原话的关键细节，在「意外 → 转弯」处放慢节奏制造张力。
2. 故事骨架：每步一行，供用户二次创作或做 PPT 大纲。

边界处理：
- 用户某步拒答或答「不知道」：记录该步为「略过」，继续下一步，汇总时如实标注或自然过渡。
- 用户跑题：温和拉回当前步骤的问题。
- 用户中途要求直接成稿：用已收集的信息立即汇总，缺失步骤以合理过渡带过并标注「未提供细节」。',
       'system', 'APPROVED', CURRENT_TIMESTAMP, 'system',
       TRUE, 'ADMIN', '表达沟通'
WHERE NOT EXISTS (SELECT 1 FROM market_skill WHERE author = 'system' AND name = '靶心人公式 讲好一个故事');

-- =============================================================
-- ==== 默认基础角色 base(4 常用 MCP + 2 官方表达技能)+ 授予默认 admin ====
-- 放在 market_skill 种子之后:role_skill 按名查 market_skill.id(不硬编码自增值)。
-- role_mcp.mcp_name = 运行时 SDK client 名(spring-ai-mcp-client - X),无 FK 到
-- mcp_server;getVisibleMcpsForUser 运行时与活跃 client 名匹配,mcp_server 元数据
-- (title/description)在 V1.1 seed。is_system=FALSE(可在控制台删除,CASCADE 清子表)。
-- default_enabled / default_loaded = TRUE(聊天面板默认勾选启动 / 技能默认加载)。
-- 全部 INSERT...SELECT...WHERE NOT EXISTS 幂等(镜像上方 admin / market_skill 种子风格)。
-- =============================================================

INSERT INTO role (code, name, is_system, description)
SELECT 'base', '基础角色', FALSE, '默认基础角色:4 个常用 MCP + 2 个官方表达技能,默认启用/加载'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE code = 'base');

INSERT INTO role_mcp (role_code, mcp_name, sort_order, default_enabled)
SELECT 'base', 'spring-ai-mcp-client - sequential-thinking', 0, TRUE
WHERE NOT EXISTS (SELECT 1 FROM role_mcp WHERE role_code = 'base' AND mcp_name = 'spring-ai-mcp-client - sequential-thinking');

INSERT INTO role_mcp (role_code, mcp_name, sort_order, default_enabled)
SELECT 'base', 'spring-ai-mcp-client - bing-search', 1, TRUE
WHERE NOT EXISTS (SELECT 1 FROM role_mcp WHERE role_code = 'base' AND mcp_name = 'spring-ai-mcp-client - bing-search');

INSERT INTO role_mcp (role_code, mcp_name, sort_order, default_enabled)
SELECT 'base', 'spring-ai-mcp-client - memory', 2, TRUE
WHERE NOT EXISTS (SELECT 1 FROM role_mcp WHERE role_code = 'base' AND mcp_name = 'spring-ai-mcp-client - memory');

INSERT INTO role_mcp (role_code, mcp_name, sort_order, default_enabled)
SELECT 'base', 'spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch', 3, TRUE
WHERE NOT EXISTS (SELECT 1 FROM role_mcp WHERE role_code = 'base' AND mcp_name = 'spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch');

INSERT INTO role_skill (role_code, market_skill_id, sort_order, default_loaded)
SELECT 'base', ms.id, 0, TRUE FROM market_skill ms
WHERE ms.author = 'system' AND ms.name = '靶心人公式 讲好一个故事'
  AND NOT EXISTS (SELECT 1 FROM role_skill rs WHERE rs.role_code = 'base' AND rs.market_skill_id = ms.id);

INSERT INTO role_skill (role_code, market_skill_id, sort_order, default_loaded)
SELECT 'base', ms.id, 1, TRUE FROM market_skill ms
WHERE ms.author = 'system' AND ms.name = 'STAR-IJ 讲清一件事'
  AND NOT EXISTS (SELECT 1 FROM role_skill rs WHERE rs.role_code = 'base' AND rs.market_skill_id = ms.id);

INSERT INTO user_role (username, role_code)
SELECT 'wb04307201', 'base'
WHERE NOT EXISTS (SELECT 1 FROM user_role WHERE username = 'wb04307201' AND role_code = 'base');
