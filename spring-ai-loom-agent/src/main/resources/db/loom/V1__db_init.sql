-- 知识库表
CREATE TABLE knowledge
(
    id       VARCHAR(64) PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    name     VARCHAR(255) NOT NULL,
    CONSTRAINT uk_username_name UNIQUE (username, name)
);

CREATE INDEX idx_knowledge_username ON knowledge(username);

-- 知识库文件关联表
CREATE TABLE knowledge_file
(
    knowledge_id VARCHAR(64) NOT NULL,
    file_id      VARCHAR(64) NOT NULL,
    PRIMARY KEY (knowledge_id, file_id)
);

CREATE INDEX idx_kf_file_id ON knowledge_file(file_id);

-- 文件信息表
CREATE TABLE file_info
(
    id          VARCHAR(64) PRIMARY KEY,
    username    VARCHAR(64) NOT NULL,
    knowledge_id    VARCHAR(64) NULL,
    file_name   VARCHAR(255) NOT NULL,
    size        BIGINT NOT NULL,
    upload_time TIMESTAMP NOT NULL,
    path  VARCHAR(500),
    usage VARCHAR(20) NOT NULL,
    mime_type VARCHAR(256) NOT NULL DEFAULT 'application/octet-stream'
);

CREATE INDEX idx_file_username ON file_info(username);

-- 文件文档关联表
CREATE TABLE file_document
(
    file_id     VARCHAR(64) NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (file_id, document_id)
);

CREATE INDEX idx_fd_document_id ON file_document(document_id);

-- 用户会话关联表
CREATE TABLE user_conversation
(
    username        VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (username, conversation_id)
);

CREATE INDEX idx_uc_conversation_id ON user_conversation(conversation_id);


CREATE TABLE skill
(
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    load        BOOLEAN DEFAULT TRUE,
    content     TEXT,
    username    VARCHAR(64)  NOT NULL,
    PRIMARY KEY (name, username)
);

