-- V3.0__knowledge_market.sql
-- 知识库存储抽象：新增 loom_file_content 表用于数据库模式下的文件内容存储

CREATE TABLE IF NOT EXISTS loom_file_content (
    file_id VARCHAR(36) PRIMARY KEY,
    content BLOB NOT NULL,
    mime_type VARCHAR(100),
    knowledge_id VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_loom_file_content_knowledge_id ON loom_file_content(knowledge_id);
