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
 // 软删后的对话应当视为"已不存在"，否则 GET /conversation/{id} 路由
 // 的所有权校验会被绕过，命中空消息体后前端表现为"对话在但内容被清"，
 // 而非"已删除"——和 admin clean-batch 之后的语义混淆。
 try {
 Integer count = jdbcTemplate.queryForObject(
 "select count(*) from user_conversation " +
 "where username = ? and conversation_id = ? and deleted_at is null",
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
 if (username == null) throw new LoomAgentRuntimeException(401, "未登录");
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
 // 单次 SQL 拉所有会话 + 6 个标量子查询聚合 stats（按 conversation_id 过滤）
 // 子查询用 scalar correlated subquery，H2 支持且计划器会按主键索引加速。
 // 对 N 个会话：6N 次子查询被合并到 1 个 round-trip，整体仍是 1 次 SELECT。
 return jdbcTemplate.query(
 "select uc.conversation_id, uc.username, uc.deleted_at, uc.content_cleaned, " +
 "uc.created_at, uc.updated_at, " +
 "(select count(*) from spring_ai_chat_memory m where m.conversation_id = uc.conversation_id) as msg_count, " +
 "(select coalesce(sum(total_tokens), 0) from loom_chat_usage u where u.conversation_id = uc.conversation_id) as total_tokens, " +
 "(select count(*) from loom_tool_call_log t where t.conversation_id = uc.conversation_id) as tool_count, " +
 "(select count(*) from loom_subtask_history s where s.conversation_id = uc.conversation_id) as sub_count, " +
 "(select count(*) from loom_scheduled_task sch where sch.conversation_id = uc.conversation_id) as sch_count, " +
 "(select count(*) from loom_tool_call_log t where t.conversation_id = uc.conversation_id and t.result_is_error = true) as err_count " +
 "from user_conversation uc where uc.username = ? " +
 "order by uc.updated_at desc",
 (rs, rowNum) -> {
 String convId = rs.getString("conversation_id");
 Instant deletedAt = toInstant(rs.getTimestamp("deleted_at"));
 Boolean cleaned = rs.getBoolean("content_cleaned");
 String preview = cleaned ? "(内容已清理)" : buildPreview(convId);
 Instant createdAt = toInstant(rs.getTimestamp("created_at"));
 Instant updatedAt = toInstant(rs.getTimestamp("updated_at"));
 return new AdminConversationView(convId, rs.getString("username"),
 null, preview, createdAt, updatedAt, deletedAt, cleaned,
 rs.getLong("msg_count"),
 rs.getLong("total_tokens"),
 rs.getLong("tool_count"),
 rs.getLong("sub_count"),
 rs.getLong("sch_count"),
 rs.getLong("err_count"));
 }, username);
 }

 @Override
 public List<AdminConversationView> listAllCleanable() {
 return jdbcTemplate.query(
 "select conversation_id, username, deleted_at, content_cleaned, " +
 "created_at, updated_at " +
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
 toInstant(rs.getTimestamp("created_at")),
 toInstant(rs.getTimestamp("updated_at")),
 deletedAt,
 rs.getBoolean("content_cleaned"),
 0L, 0L, 0L, 0L, 0L, 0L);
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
