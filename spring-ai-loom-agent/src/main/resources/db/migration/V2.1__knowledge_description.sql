-- V2.1__knowledge_description.sql

-- 知识库增加描述字段
ALTER TABLE knowledge ADD COLUMN description VARCHAR(500) NULL;

-- 对话增加启用知识库ID列表（JSON格式存储）
ALTER TABLE user_conversation ADD COLUMN enabled_knowledge_ids VARCHAR(1000) NULL;
