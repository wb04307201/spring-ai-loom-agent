package cn.wubo.spring.ai.loom.agent.tool;

import cn.wubo.spring.ai.loom.agent.model.SteeringAuditRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * H2 / JDBC-backed {@link ILoomChatSteeringRepository}（spec 2026-11-21
 * agentic-loop-steering §5.9，Task 9 / S4）。
 *
 * <p>Schema 主路径是 Flyway {@code V1.0__init.sql} 末尾的 {@code loom_chat_steering}
 * 段（项目只跑全新库）；{@link #ensureSchema()} 为幂等兜底（Flyway 被绕过 / 消费方
 * 自带库的部署），仿 {@code JdbcLoomScheduleExecutionRepository}：bean 装配时调用一次，
 * 重复调用零副作用。
 *
 * <p>本实现不吞 {@link #record} 异常 —— 聊天流保护在调用方
 * （{@code LoomAgenticLoopAdvisor} 注入点 try/catch + log.warn，与
 * ToolCallHistory / usage 落库同策略）；仓储保持纯 JDBC 语义，读侧异常如实上抛。
 */
public class JdbcLoomChatSteeringRepository implements ILoomChatSteeringRepository {

    public static final String TABLE_NAME = "loom_chat_steering";

    private static final Logger log = LoggerFactory.getLogger(JdbcLoomChatSteeringRepository.class);

    /** 与 V1.0 段同形（spec §5.9 原文），仅多 IF NOT EXISTS 做幂等兜底。 */
    private static final String DDL = "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ("
            + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
            + " conversation_id VARCHAR(255) NOT NULL,"
            + " username VARCHAR(64) NOT NULL,"
            + " message_text CLOB NOT NULL,"
            + " injected_at TIMESTAMP NOT NULL,"
            + " seq INT NOT NULL"
            + ")";

    /** Task 10 按会话读(历史交错 / B2 豁免判定)—— conversation_id 前缀复合索引。 */
    private static final String DDL_INDEX_CONV =
            "CREATE INDEX IF NOT EXISTS idx_loom_chat_steering_conv"
                    + " ON " + TABLE_NAME + "(conversation_id, injected_at, seq)";

    private static final String INSERT_SQL =
            "INSERT INTO " + TABLE_NAME
                    + " (conversation_id, username, message_text, injected_at, seq)"
                    + " VALUES (?, ?, ?, ?, ?)";

    private static final RowMapper<SteeringAuditRow> ROW_MAPPER = (rs, n) -> new SteeringAuditRow(
            rs.getLong("id"),
            rs.getString("conversation_id"),
            rs.getString("username"),
            rs.getString("message_text"),
            rs.getTimestamp("injected_at").toInstant(),
            rs.getInt("seq"));

    private final JdbcTemplate jdbc;

    public JdbcLoomChatSteeringRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等兜底建表（IF NOT EXISTS），仿 {@code JdbcLoomScheduleExecutionRepository}。
     * 主路径 schema 来自 V1.0 Flyway 段；本方法保证绕过 Flyway 的部署同样可写。
     */
    public void ensureSchema() {
        jdbc.execute(DDL);
        jdbc.execute(DDL_INDEX_CONV);
        log.debug("JdbcLoomChatSteeringRepository schema ensured at {}", TABLE_NAME);
    }

    @Override
    public void record(String convId, String username, String text, Instant injectedAt, int seq) {
        jdbc.update(INSERT_SQL, convId, username, text,
                Timestamp.from(injectedAt == null ? Instant.now() : injectedAt), seq);
    }

    @Override
    public List<SteeringAuditRow> findByConversationId(String convId) {
        if (convId == null || convId.isBlank()) {
            return List.of();
        }
        // injected_at 升序 + 同刻 seq 升序(seq 本 run 内单调,与时刻序一致)—— Task 10 交错契约
        return jdbc.query(
                "SELECT id, conversation_id, username, message_text, injected_at, seq"
                        + " FROM " + TABLE_NAME
                        + " WHERE conversation_id = ?"
                        + " ORDER BY injected_at, seq",
                ROW_MAPPER, convId);
    }
}
