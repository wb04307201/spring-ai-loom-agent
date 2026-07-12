-- =============================================================
-- Spring AI LoomAgent — consolidated database schema
-- =============================================================
-- 一站式初始化脚本：知识库 / 文件 / 用户 / 角色 / MCP / Skill 市场。
-- 重新部署：删除 .local/datasource/db 即可让 Flyway 从 V1 重跑。
-- =============================================================


-- ============== 知识库 ==============

CREATE TABLE knowledge
(
    id       VARCHAR(64) PRIMARY KEY,
    username VARCHAR(64)  NOT NULL,
    name     VARCHAR(255) NOT NULL,
    CONSTRAINT uk_username_name UNIQUE (username, name)
);
CREATE INDEX idx_knowledge_username ON knowledge(username);

CREATE TABLE knowledge_file
(
    knowledge_id VARCHAR(64) NOT NULL,
    file_id      VARCHAR(64) NOT NULL,
    PRIMARY KEY (knowledge_id, file_id)
);
CREATE INDEX idx_kf_file_id ON knowledge_file(file_id);


-- ============== 文件 ==============

CREATE TABLE file_info
(
    id            VARCHAR(64) PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    knowledge_id  VARCHAR(64)  NULL,
    file_name     VARCHAR(255) NOT NULL,
    size          BIGINT       NOT NULL,
    upload_time   TIMESTAMP    NOT NULL,
    path          VARCHAR(500),
    usage         VARCHAR(20)  NOT NULL,
    mime_type     VARCHAR(256) NOT NULL DEFAULT 'application/octet-stream'
);
CREATE INDEX idx_file_username ON file_info(username);

CREATE TABLE file_document
(
    file_id     VARCHAR(64) NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (file_id, document_id)
);
CREATE INDEX idx_fd_document_id ON file_document(document_id);


-- ============== 用户 ==============

CREATE TABLE user_info
(
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    username  VARCHAR(64)  NOT NULL UNIQUE,
    nickname  VARCHAR(64)  NOT NULL,
    password  VARCHAR(255) NOT NULL,
    type      VARCHAR(20)  NOT NULL CHECK (type IN ('ADMIN', 'USER'))
);
CREATE INDEX idx_user_info_username ON user_info(username);


-- ============== 用户会话 ==============

CREATE TABLE user_conversation
(
    username        VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    deleted_at      TIMESTAMP   NULL,
    content_cleaned BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (username, conversation_id)
);
CREATE INDEX idx_uc_conversation_id ON user_conversation(conversation_id);
CREATE INDEX idx_user_conv_deleted ON user_conversation(username, deleted_at);


-- ============== Token 用量 ==============

CREATE TABLE chat_token_usage
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL,
    username        VARCHAR(64)  NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    prompt_tokens   INTEGER      NOT NULL DEFAULT 0,
    completion_tokens INTEGER    NOT NULL DEFAULT 0,
    total_tokens    INTEGER      NOT NULL DEFAULT 0,
    model           VARCHAR(64),
    duration_ms     INTEGER,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_token_user_time ON chat_token_usage(username, created_at);
CREATE INDEX idx_token_conv ON chat_token_usage(conversation_id);
CREATE INDEX idx_token_time ON chat_token_usage(created_at);


-- ============== Skill 系统（旧的 per-user 表，被 Skill 市场取代） ==============
-- 旧表保留，代码不再读，仅供回滚参考

CREATE TABLE skill
(
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    load        BOOLEAN DEFAULT TRUE,
    content     TEXT,
    username    VARCHAR(64)  NOT NULL,
    PRIMARY KEY (name, username)
);


-- =============================================================
-- 角色 / 权限（RBAC）
-- =============================================================

CREATE TABLE role
(
    code        VARCHAR(32) PRIMARY KEY,
    name        VARCHAR(64) NOT NULL,
    is_system   BOOLEAN     NOT NULL DEFAULT FALSE,
    description TEXT,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);


CREATE TABLE user_role
(
    username  VARCHAR(64) NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    PRIMARY KEY (username, role_code)
);


-- =============================================================
-- MCP 服务元数据
-- =============================================================

CREATE TABLE mcp_server
(
    name         VARCHAR(128) PRIMARY KEY,
    title        VARCHAR(128),
    description  TEXT,
    is_active    BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_mcp_server_active ON mcp_server(is_active);


CREATE TABLE mcp_tool
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    mcp_name   VARCHAR(128) NOT NULL,
    name       VARCHAR(128) NOT NULL,
    description TEXT,
    sort_order INT         NOT NULL DEFAULT 0,
    UNIQUE (mcp_name, name)
);


CREATE TABLE role_mcp
(
    role_code      VARCHAR(32)  NOT NULL,
    mcp_name       VARCHAR(128) NOT NULL,
    sort_order     INT          NOT NULL DEFAULT 0,
    default_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    PRIMARY KEY (role_code, mcp_name)
);
CREATE INDEX idx_role_mcp_code ON role_mcp(role_code, sort_order);


-- =============================================================
-- Skill 市场 + 用户 Skill 实例 + 角色授权
-- =============================================================

-- 公共仓库：每条记录是某个作者对某个技能某个版本的一次发布
CREATE TABLE market_skill
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(128) NOT NULL,
    description    TEXT,
    content        TEXT         NOT NULL,
    version        VARCHAR(32)  NOT NULL,
    author         VARCHAR(64)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,         -- PENDING / APPROVED / REJECTED
    submitted_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at    TIMESTAMP    NULL,
    reviewed_by    VARCHAR(64)  NULL,
    review_comment TEXT         NULL,
    UNIQUE (author, name, version)
);
CREATE INDEX idx_market_skill_status   ON market_skill(status);
CREATE INDEX idx_market_skill_approved ON market_skill(status, name, author);


-- 用户本地 Skill 实例
CREATE TABLE user_skill
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    content         TEXT         NOT NULL,
    source          VARCHAR(16)  NOT NULL,         -- USER_CREATED / MARKET_PULLED / ROLE_GRANTED
    market_skill_id BIGINT       NULL,             -- 关联 market_skill.id（自建为 NULL）
    market_version  VARCHAR(32)  NULL,             -- 拉取/授权时的版本号
    default_loaded  BOOLEAN      NOT NULL DEFAULT FALSE,
    locked          BOOLEAN      NOT NULL DEFAULT FALSE,   -- true = ROLE_GRANTED 只读
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (username, name)
);
CREATE INDEX idx_user_skill_username ON user_skill(username);
CREATE INDEX idx_user_skill_source   ON user_skill(username, source);


-- 角色授权（指向具体的市场 skill id，含版本）
CREATE TABLE role_skill
(
    role_code       VARCHAR(32) NOT NULL,
    market_skill_id BIGINT      NOT NULL,
    sort_order      INT         NOT NULL DEFAULT 0,
    default_loaded  BOOLEAN     NOT NULL DEFAULT TRUE,
    PRIMARY KEY (role_code, market_skill_id)
);
CREATE INDEX idx_role_skill_code ON role_skill(role_code, sort_order);


-- =============================================================
-- 种子数据
-- =============================================================

-- 初始管理员：账号 wb04307201 / 密码 123456（BCrypt cost=10），登录后请立即改密
INSERT INTO user_info (username, nickname, password, type)
SELECT 'wb04307201', '吴博', '$2a$10$gZ0zgDHCVQrZueMmiiZc4u5aP1SVnjA7sy623noNR4lCqMhr/Edzy', 'ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM user_info WHERE username = 'wb04307201');


-- 6 个内嵌系统 Skill seed 进市场（author='system'，version='1.0.0'，status='APPROVED'）
-- content 字段以 "classpath:skills/xxx" 占位；读取时由 SkillContentResolver 解析为真实内容

INSERT INTO market_skill (name, description, content, version, author, status, reviewed_at, reviewed_by) VALUES
('网络月度事件报告',
 '用户说"梳理 xxx 月度事件/年度时间线/事件复盘/xxx 的网络事件"时触发：围绕 {topic} 按月梳理当年的重要事件，做跨月因果关联与下一年趋势预判，产出 HTML 报告并返回预览链接（{topic} 来自用户当前对话）',
 'classpath:skills/news-watch.st',
 '1.0.0', 'system', 'APPROVED', CURRENT_TIMESTAMP, 'system'),

('http测试',
 '用户说"测试/调用 xxx 接口/http 探测/服务连通性"时触发：自定义 http 服务测试',
 'classpath:skills/http.st',
 '1.0.0', 'system', 'APPROVED', CURRENT_TIMESTAMP, 'system'),

('测试保存、下载、预览1',
 '测试保存、下载、预览1',
 '你能说明勾股定理么？并保存成md，并给我下载和和预览地址',
 '1.0.0', 'system', 'APPROVED', CURRENT_TIMESTAMP, 'system'),

('测试保存、下载、预览2',
 '测试保存、下载、预览2',
 '你能说明微积分么？并保存成html，并给我下载和和预览地址',
 '1.0.0', 'system', 'APPROVED', CURRENT_TIMESTAMP, 'system'),

('部署项目',
 '用户说"部署/上线/跑起来/启动 xxx 项目"时触发：从 git 仓库拉取代码 → 编译 → 构建 Docker 镜像 → 启动容器 → 返回访问链接，支持 maven / npm / npm-frontend / pip 多栈',
 'classpath:skills/package-docker.st',
 '1.0.0', 'system', 'APPROVED', CURRENT_TIMESTAMP, 'system'),

('测试自动E2E功能验证',
 '测试使用浏览器对功能进行E2E测试，并生成验证报告',
 'classpath:skills/e2e.st',
 '1.0.0', 'system', 'APPROVED', CURRENT_TIMESTAMP, 'system');
