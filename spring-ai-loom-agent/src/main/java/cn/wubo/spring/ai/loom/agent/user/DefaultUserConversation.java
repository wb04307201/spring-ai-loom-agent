package cn.wubo.spring.ai.loom.agent.user;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.AdminConversationView;
import cn.wubo.spring.ai.loom.agent.model.ConversationRecord;
import cn.wubo.spring.ai.loom.agent.model.UserConversationRecord;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.cache.Cache;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DefaultUserConversation implements IUserConversation {

    private final JdbcTemplate jdbcTemplate;
    private final ChatMemory chatMemory;
    private final Cache sessionCache;

    public DefaultUserConversation(JdbcTemplate jdbcTemplate, ChatMemory chatMemory, Cache sessionCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.chatMemory = chatMemory;
        this.sessionCache = sessionCache;
    }

    @Override
    public List<ConversationRecord> getList() {
        String username = UserContextHolder.getCurrentUser();
        if (username == null) return List.of();
        return jdbcTemplate.query(
                "select conversation_id, title, created_at, updated_at " +
                        "from user_conversation where username = ? and deleted_at is null " +
                        "order by created_at desc, conversation_id desc",
                (rs, rowNum) -> {
                    String conversationId = rs.getString("conversation_id");
                    String title = rs.getString("title");
                    if (title == null || title.isBlank()) title = buildPreview(conversationId);
                    return new ConversationRecord(
                            conversationId,
                            title,
                            toInstant(rs.getTimestamp("created_at")),
                            toInstant(rs.getTimestamp("updated_at")));
                },
                username);
    }

    private String buildPreview(String conversationId) {
        try {
            List<Message> messages = chatMemory.get(conversationId);
            if (messages == null || messages.isEmpty()) return "新对话";
            for (int i = messages.size() - 1; i >= 0; i--) {
                String text = messages.get(i).getText();
                if (text != null && !text.isBlank()) {
                    return text.length() > 20 ? text.substring(0, 20) : text;
                }
            }
            return "新对话";
        } catch (Exception e) {
            return "新对话";
        }
    }

    @Override
    public boolean exists(UserConversationRecord userConversationRecord) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from user_conversation where username = ? and conversation_id = ?",
                    Integer.class,
                    userConversationRecord.username(), userConversationRecord.conversationId());
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public int insert(UserConversationRecord userConversationRecord) {
        if (exists(userConversationRecord)) {
            // 软删后再插入相当于"恢复"——清掉 deleted_at
            return jdbcTemplate.update(
                    "update user_conversation set deleted_at = null where username = ? and conversation_id = ?",
                    userConversationRecord.username(), userConversationRecord.conversationId());
        }
        return jdbcTemplate.update(
                "insert into user_conversation (username, conversation_id) values (?, ?)",
                userConversationRecord.username(), userConversationRecord.conversationId());
    }

    @Override
    public ConversationRecord create(String title) {
        String username = UserContextHolder.getCurrentUser();
        if (username == null) throw new LoomAgentRuntimeException("未登录");
        String normalizedTitle = normalizeTitle(title);
        String conversationId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "insert into user_conversation (username, conversation_id, title) values (?, ?, ?)",
                username, conversationId, normalizedTitle);
        // Read back the row so createdAt/updatedAt reflect the DB's CURRENT_TIMESTAMP
        // (not this JVM's clock). Without this, an immediate listConversations() after
        // create() could surface ordering/equality mismatches between in-memory and DB.
        return jdbcTemplate.queryForObject(
                "select conversation_id, title, created_at, updated_at " +
                        "from user_conversation where username = ? and conversation_id = ?",
                (rs, rowNum) -> new ConversationRecord(
                        rs.getString("conversation_id"),
                        rs.getString("title"),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("updated_at"))),
                username, conversationId);
    }

    @Override
    public int rename(String conversationId, String title) {
        String username = UserContextHolder.getCurrentUser();
        if (username == null) return 0;
        String normalizedTitle = normalizeTitle(title);
        return jdbcTemplate.update(
                "update user_conversation set title = ?, updated_at = current_timestamp " +
                        "where username = ? and conversation_id = ? and deleted_at is null",
                normalizedTitle, username, conversationId);
    }

    private static String normalizeTitle(String title) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty()) normalized = "新对话";
        if (normalized.length() > 100) normalized = normalized.substring(0, 100);
        return normalized;
    }

    @Override
    public int deleteById(String conversationId) {
        String username = UserContextHolder.getCurrentUser();
        if (username == null) return 0;
        return jdbcTemplate.update(
                "update user_conversation set deleted_at = current_timestamp " +
                        "where username = ? and conversation_id = ? and deleted_at is null",
                username, conversationId);
    }

    @Override
    public List<AdminConversationView> adminListByUsername(String username) {
        return jdbcTemplate.query(
                "select uc.conversation_id, uc.username, uc.deleted_at, uc.content_cleaned " +
                        "from user_conversation uc where uc.username = ? order by uc.deleted_at desc nulls first",
                (rs, rowNum) -> {
                    String convId = rs.getString("conversation_id");
                    Instant deletedAt = toInstant(rs.getTimestamp("deleted_at"));
                    Boolean cleaned = rs.getBoolean("content_cleaned");
                    String preview = cleaned ? "(内容已清理)" : buildPreview(convId);
                    return new AdminConversationView(convId, rs.getString("username"),
                            null, preview, null, deletedAt, cleaned);
                }, username);
    }

    @Override
    public List<AdminConversationView> listAllCleanable() {
        return jdbcTemplate.query(
                "select conversation_id, username, deleted_at, content_cleaned " +
                        "from user_conversation where content_cleaned = false " +
                        "order by username, conversation_id",
                (rs, rowNum) -> {
                    String convId = rs.getString("conversation_id");
                    Instant deletedAt = toInstant(rs.getTimestamp("deleted_at"));
                    String preview = deletedAt == null ? "(正常, 清理将转为软删)" : buildPreview(convId);
                    return new AdminConversationView(
                            convId,
                            rs.getString("username"),
                            null,
                            preview,
                            null,
                            deletedAt,
                            rs.getBoolean("content_cleaned"));
                });
    }

    @Override
    public int cleanContentForUserConv(String username, String conversationId) {
        // 1. 通过 ChatMemory.clear 删除该 conversation 的全部消息
        chatMemory.clear(conversationId);
        // 2. 标记 content_cleaned=true（如果还没 deleted_at，也设上）
        int updated = jdbcTemplate.update(
                "update user_conversation set content_cleaned = true, " +
                        "deleted_at = coalesce(deleted_at, current_timestamp) " +
                        "where username = ? and conversation_id = ?",
                username, conversationId);
        if (updated == 0) {
            throw new LoomAgentRuntimeException("会话不存在或已被清理");
        }
        return 1;
    }

    private UserConversationRecord mapUserConversationRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new UserConversationRecord(rs.getString("username"), rs.getString("conversation_id"));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
