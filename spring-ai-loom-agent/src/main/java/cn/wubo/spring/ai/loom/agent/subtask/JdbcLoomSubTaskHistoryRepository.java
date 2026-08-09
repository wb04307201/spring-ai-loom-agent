package cn.wubo.spring.ai.loom.agent.subtask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * H2 / JDBC-backed {@link ILoomSubTaskHistoryRepository}.
 * <p>
 * Persists {@link SubTaskRegistry.SubTaskRecord} rows across application
 * restarts. Schema is also declared as a Flyway migration
 * ({@code __subtask_and_schedule.sql} — table {@code loom_subtask_history}); this class additionally calls
 * {@link #ensureSchema()} defensively so the table is created even when
 * Flyway is bypassed (e.g. integration tests with bare H2 URLs).
 * </p>
 */
public class JdbcLoomSubTaskHistoryRepository implements ILoomSubTaskHistoryRepository {

 private static final Logger log = LoggerFactory.getLogger(JdbcLoomSubTaskHistoryRepository.class);

 public static final String TABLE_NAME = "loom_subtask_history";

 private static final String DDL = "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ("
 + " subtask_id VARCHAR(64) PRIMARY KEY,"
 + " username VARCHAR(64) NOT NULL,"
 + " conversation_id VARCHAR(64) NOT NULL,"
 + " prompt CLOB NOT NULL,"
 + " status VARCHAR(16) NOT NULL,"
 + " started_at BIGINT NOT NULL,"
 + " finished_at BIGINT NOT NULL,"
 + " error_message CLOB,"
 + " result_text CLOB"
 + ")";

 private static final String DDL_INDEX_USER_CONV =
 "CREATE INDEX IF NOT EXISTS idx_loom_subtask_history_user_conv"
 + " ON " + TABLE_NAME + "(username, conversation_id)";

 private static final String DDL_INDEX_USER_FINISHED =
 "CREATE INDEX IF NOT EXISTS idx_loom_subtask_history_user_finished"
 + " ON " + TABLE_NAME + "(username, finished_at DESC)";

 private static final String COLUMNS =
 " subtask_id, username, conversation_id, prompt, status,"
 + " started_at, finished_at, error_message, result_text";

 private static final String MERGE_SQL =
 "MERGE INTO " + TABLE_NAME + " (" + COLUMNS + ")"
 + " KEY(subtask_id)"
 + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

 private final JdbcTemplate jdbc;

 public JdbcLoomSubTaskHistoryRepository(DataSource dataSource) {
 this.jdbc = new JdbcTemplate(dataSource);
 }

 public JdbcLoomSubTaskHistoryRepository(JdbcTemplate jdbc) {
 this.jdbc = jdbc;
 }

 @Override
 public void ensureSchema() {
 jdbc.execute(DDL);
 jdbc.execute(DDL_INDEX_USER_CONV);
 jdbc.execute(DDL_INDEX_USER_FINISHED);
 log.debug("JdbcLoomSubTaskHistoryRepository schema ensured at {}", TABLE_NAME);
 }

 @Override
 public void save(SubTaskRegistry.SubTaskRecord r) {
 jdbc.update(MERGE_SQL,
 r.subTaskId(),
 r.username() == null ? "" : r.username(),
 r.conversationId() == null ? "" : r.conversationId(),
 r.prompt() == null ? "" : r.prompt(),
 r.status().name(),
 r.startedAt(),
 r.finishedAt(),
 r.errorMessage(),
 r.resultText());
 }

 @Override
 public List<SubTaskRegistry.SubTaskRecord> findByUsernameAndConversation(String username, String conversationId, int limit) {
 if (username == null || username.isBlank()) return List.of();
 String conv = conversationId == null ? "" : conversationId;
 return jdbc.query(
 "SELECT " + COLUMNS + " FROM " + TABLE_NAME
 + " WHERE username = ? AND conversation_id = ?"
 + " ORDER BY finished_at DESC"
 + (limit > 0 ? " LIMIT " + limit : ""),
 (rs, rowNum) -> mapRow(rs),
 username, conv);
 }

 @Override
 public List<SubTaskRegistry.SubTaskRecord> findByUsername(String username, int limit) {
 if (username == null || username.isBlank()) return List.of();
 return jdbc.query(
 "SELECT " + COLUMNS + " FROM " + TABLE_NAME
 + " WHERE username = ?"
 + " ORDER BY finished_at DESC"
 + (limit > 0 ? " LIMIT " + limit : ""),
 (rs, rowNum) -> mapRow(rs),
 username);
 }

 @Override
 public Set<String> findAllUsernames() {
 Set<String> out = new HashSet<>();
 jdbc.query("SELECT DISTINCT username FROM " + TABLE_NAME,
 (rs, rowNum) -> rs.getString(1))
 .forEach(out::add);
 return out;
 }

 @Override
 public int deleteAllByConversation(String username, String conversationId) {
 if (username == null || username.isBlank() || conversationId == null || conversationId.isBlank()) {
 return 0;
 }
 return jdbc.update(
 "DELETE FROM " + TABLE_NAME + " WHERE username = ? AND conversation_id = ?",
 username, conversationId);
 }

 @Override
 public boolean deleteById(String username, String subTaskId) {
 if (username == null || username.isBlank() || subTaskId == null || subTaskId.isBlank()) {
 return false;
 }
 // Scope by username so a USER can't delete another USER's history row
 // by guessing its UUID. (BUG-aligned with BUG-12/13 family.)
 int n = jdbc.update(
 "DELETE FROM " + TABLE_NAME + " WHERE username = ? AND subtask_id = ?",
 username, subTaskId);
 return n > 0;
 }

 private static SubTaskRegistry.SubTaskRecord mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
 return new SubTaskRegistry.SubTaskRecord(
 rs.getString("subtask_id"),
 rs.getString("username"),
 rs.getString("conversation_id"),
 rs.getString("prompt"),
 cn.wubo.spring.ai.loom.agent.model.SubTaskStatus.valueOf(rs.getString("status")),
 rs.getLong("started_at"),
 rs.getLong("finished_at"),
 rs.getString("error_message"),
 rs.getString("result_text"),
 null); // future 不持久化 —— 纯运行期对象
 }
}