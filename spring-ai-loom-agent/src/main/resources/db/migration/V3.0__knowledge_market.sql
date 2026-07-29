-- V3.0__knowledge_market.sql
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
