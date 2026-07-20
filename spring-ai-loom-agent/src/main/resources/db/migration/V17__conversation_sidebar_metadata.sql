-- Conversation sidebar metadata: persist empty conversations and user-defined titles.
ALTER TABLE user_conversation ADD COLUMN title VARCHAR(100);
ALTER TABLE user_conversation ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE user_conversation ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX idx_user_conv_created ON user_conversation(username, deleted_at, created_at DESC);
