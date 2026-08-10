package cn.wubo.spring.ai.loom.agent.tool;

import cn.wubo.spring.ai.loom.agent.model.ToolCallLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link JdbcToolCallLogRepository} against H2.
 * Pins:
 * <ul>
 *   <li>save() inserts a new row and returns the generated id.</li>
 *   <li>save() with duplicate (conversation_id, tool_call_id) is a no-op (dedupe).</li>
 *   <li>findByConversationId returns rows ordered by created_at.</li>
 *   <li>findByCallId returns the row or empty.</li>
 * </ul>
 */
class JdbcToolCallLogRepositoryTest {

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private JdbcToolCallLogRepository repo;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:tool-log-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new DriverManagerDataSource(url, "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE loom_tool_call_log (
                log_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                conversation_id VARCHAR(255),
                username VARCHAR(64),
                tool_call_id VARCHAR(128),
                tool_name VARCHAR(128),
                arguments_json CLOB,
                result_text CLOB,
                result_is_error BOOLEAN,
                duration_ms BIGINT,
                created_at TIMESTAMP
            )
            """);
        repo = new JdbcToolCallLogRepository(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        if (dataSource instanceof DriverManagerDataSource ds) {
            try { ds.getConnection().close(); } catch (Exception ignored) {}
        }
    }

    private ToolCallLog sample(String convId, String callId, String toolName) {
        return new ToolCallLog(null, convId, "alice", callId, toolName,
                "{\"q\":\"hi\"}", "ok", false, 12L, Instant.now());
    }

    @Test
    void save_inserts_and_returns_generated_id() {
        ToolCallLog saved = repo.save(sample("c1", "wrap-1", "echo"));
        assertThat(saved.logId()).isNotNull();
        assertThat(saved.logId()).isPositive();

        Optional<ToolCallLog> found = repo.findByCallId("c1", "wrap-1");
        assertThat(found).isPresent();
        assertThat(found.get().toolName()).isEqualTo("echo");
        assertThat(found.get().username()).isEqualTo("alice");
    }

    @Test
    void save_dedupes_duplicate_callId() {
        ToolCallLog first = repo.save(sample("c1", "wrap-dup", "echo"));
        // 第二次 save 同样的 (conversation_id, tool_call_id) — 应 no-op 并返回原 row
        ToolCallLog second = repo.save(sample("c1", "wrap-dup", "echo"));

        assertThat(second.logId()).isEqualTo(first.logId());
        List<ToolCallLog> rows = repo.findByConversationId("c1");
        assertThat(rows).hasSize(1);
    }

    @Test
    void findByConversationId_returns_in_insertion_order() {
        repo.save(sample("c1", "wrap-1", "echo"));
        // sleep 1ms 让 created_at 有差异
        sleep(5);
        repo.save(sample("c1", "wrap-2", "search"));
        sleep(5);
        repo.save(sample("c1", "wrap-3", "fetch"));

        List<ToolCallLog> rows = repo.findByConversationId("c1");
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(ToolCallLog::toolCallId)
                .containsExactly("wrap-1", "wrap-2", "wrap-3");
    }

    @Test
    void findByConversationId_returns_empty_for_unknown() {
        assertThat(repo.findByConversationId("nope")).isEmpty();
    }

    @Test
    void findByCallId_returns_empty_for_unknown() {
        assertThat(repo.findByCallId("c1", "wrap-nope")).isEmpty();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}