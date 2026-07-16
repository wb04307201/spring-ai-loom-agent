package cn.wubo.spring.ai.loom.agent.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * H2 / JDBC-backed {@link ILoomScheduleTriggerRepository}.
 * <p>
 * Persists {@link LoomScheduleTriggerRecord} rows across application restarts.
 * Schema is also declared as a Flyway migration
 * ({@code V13__loom_scheduled_task.sql}); this class additionally calls
 * {@link #ensureSchema()} defensively so the table is created even when Flyway
 * is bypassed (e.g. integration tests with bare H2 URLs).
 * </p>
 */
public class JdbcLoomScheduleTriggerRepository implements ILoomScheduleTriggerRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcLoomScheduleTriggerRepository.class);

    public static final String TABLE_NAME = "loom_scheduled_task";

    private static final String DDL = "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ("
            + " task_name             VARCHAR(255) PRIMARY KEY,"
            + " schedule_type         VARCHAR(20)  NOT NULL,"
            + " cron_expression       VARCHAR(100),"
            + " interval_seconds      BIGINT,"
            + " initial_delay_seconds BIGINT,"
            + " one_shot_delay_seconds BIGINT,"
            + " prompt                CLOB NOT NULL,"
            + " username              VARCHAR(64) NOT NULL,"
            + " conversation_id       VARCHAR(64) NOT NULL,"
            + " paused                BOOLEAN NOT NULL DEFAULT FALSE,"
            + " created_at            TIMESTAMP(9) WITH TIME ZONE NOT NULL,"
            + " updated_at            TIMESTAMP(9) WITH TIME ZONE NOT NULL"
            + ")";

    private static final String DDL_INDEX_USER_CONV =
            "CREATE INDEX IF NOT EXISTS idx_loom_scheduled_task_user_conv"
                    + " ON " + TABLE_NAME + "(username, conversation_id)";

    private static final String DDL_INDEX_CREATED_AT =
            "CREATE INDEX IF NOT EXISTS idx_loom_scheduled_task_created_at"
                    + " ON " + TABLE_NAME + "(created_at)";

    private static final String MERGE_SQL =
            "MERGE INTO " + TABLE_NAME + " (task_name, schedule_type, cron_expression,"
                    + " interval_seconds, initial_delay_seconds, one_shot_delay_seconds,"
                    + " prompt, username, conversation_id, paused, created_at, updated_at)"
                    + " KEY(task_name)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    public JdbcLoomScheduleTriggerRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public JdbcLoomScheduleTriggerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates the persistence table and indexes if absent. Safe to invoke multiple
     * times — Flyway (V13) is the canonical schema source in production; this is
     * a defensive belt-and-suspenders for non-Flyway callers.
     */
    public void ensureSchema() {
        jdbc.execute(DDL);
        jdbc.execute(DDL_INDEX_USER_CONV);
        jdbc.execute(DDL_INDEX_CREATED_AT);
        log.debug("JdbcLoomScheduleTriggerRepository schema ensured at {}", TABLE_NAME);
    }

    @Override
    public void save(LoomScheduleTriggerRecord r) {
        jdbc.update(MERGE_SQL,
                r.taskName(),
                r.scheduleType(),
                r.cronExpression(),
                r.intervalSeconds(),
                r.initialDelaySeconds(),
                r.oneShotDelaySeconds(),
                r.prompt(),
                r.username(),
                r.conversationId(),
                r.paused(),
                Timestamp.from(r.createdAt()),
                Timestamp.from(r.updatedAt()));
    }

    @Override
    public Optional<LoomScheduleTriggerRecord> findByName(String taskName) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT task_name, schedule_type, cron_expression, interval_seconds,"
                            + " initial_delay_seconds, one_shot_delay_seconds, prompt,"
                            + " username, conversation_id, paused, created_at, updated_at"
                            + " FROM " + TABLE_NAME + " WHERE task_name = ?",
                    (rs, rowNum) -> mapRow(rs),
                    taskName));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<LoomScheduleTriggerRecord> findAll() {
        return jdbc.query(
                "SELECT task_name, schedule_type, cron_expression, interval_seconds,"
                        + " initial_delay_seconds, one_shot_delay_seconds, prompt,"
                        + " username, conversation_id, paused, created_at, updated_at"
                        + " FROM " + TABLE_NAME + " ORDER BY task_name",
                (rs, rowNum) -> mapRow(rs))
                .stream()
                .sorted(Comparator.comparing(LoomScheduleTriggerRecord::taskName))
                .toList();
    }

    @Override
    public List<LoomScheduleTriggerRecord> findByUserAndConv(String username, String conversationId) {
        return jdbc.query(
                "SELECT task_name, schedule_type, cron_expression, interval_seconds,"
                        + " initial_delay_seconds, one_shot_delay_seconds, prompt,"
                        + " username, conversation_id, paused, created_at, updated_at"
                        + " FROM " + TABLE_NAME
                        + " WHERE username = ? AND conversation_id = ?"
                        + " ORDER BY task_name",
                (rs, rowNum) -> mapRow(rs),
                username, conversationId)
                .stream()
                .sorted(Comparator.comparing(LoomScheduleTriggerRecord::taskName))
                .toList();
    }

    @Override
    public int delete(String taskName) {
        return jdbc.update("DELETE FROM " + TABLE_NAME + " WHERE task_name = ?", taskName);
    }

    @Override
    public int deleteAllForConversation(String username, String conversationId) {
        return jdbc.update(
                "DELETE FROM " + TABLE_NAME + " WHERE username = ? AND conversation_id = ?",
                username, conversationId);
    }

    @Override
    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE_NAME, Integer.class);
        return n != null ? n : 0;
    }

    @Override
    public boolean exists(String taskName) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE_NAME + " WHERE task_name = ?",
                Integer.class, taskName);
        return n != null && n > 0;
    }

    private static LoomScheduleTriggerRecord mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
        return new LoomScheduleTriggerRecord(
                rs.getString("task_name"),
                rs.getString("schedule_type"),
                rs.getString("cron_expression"),
                (Long) rs.getObject("interval_seconds"),
                (Long) rs.getObject("initial_delay_seconds"),
                (Long) rs.getObject("one_shot_delay_seconds"),
                rs.getString("prompt"),
                rs.getString("username"),
                rs.getString("conversation_id"),
                rs.getBoolean("paused"),
                createdAt,
                updatedAt);
    }
}
