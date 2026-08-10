package cn.wubo.spring.ai.loom.agent.tool;

import cn.wubo.spring.ai.loom.agent.model.ToolCallLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

public class JdbcToolCallLogRepository implements IToolCallLogRepository {

    private static final RowMapper<ToolCallLog> ROW_MAPPER = (rs, n) -> new ToolCallLog(
            rs.getLong("log_id"),
            rs.getString("conversation_id"),
            rs.getString("username"),
            rs.getString("tool_call_id"),
            rs.getString("tool_name"),
            rs.getString("arguments_json"),
            rs.getString("result_text"),
            rs.getBoolean("result_is_error"),
            (Long) rs.getObject("duration_ms"),
            rs.getTimestamp("created_at").toInstant());
    private final JdbcTemplate jdbcTemplate;

    public JdbcToolCallLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ToolCallLog save(ToolCallLog log) {
        // 写入前去重（同一 conversation_id + tool_call_id 多次出现时只在第一次写）
        Optional<ToolCallLog> existing = findByCallId(log.conversationId(), log.toolCallId());
        if (existing.isPresent()) return existing.get();

        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "insert into loom_tool_call_log (conversation_id, username, tool_call_id, " +
                            "tool_name, arguments_json, result_text, result_is_error, duration_ms, created_at) " +
                            "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, log.conversationId());
            ps.setString(2, log.username());
            ps.setString(3, log.toolCallId());
            ps.setString(4, log.toolName());
            ps.setString(5, log.argumentsJson());
            ps.setString(6, log.resultText());
            ps.setBoolean(7, log.resultIsError());
            if (log.durationMs() != null) ps.setLong(8, log.durationMs());
            else ps.setNull(8, java.sql.Types.BIGINT);
            ps.setTimestamp(9, Timestamp.from(log.createdAt()));
            return ps;
        }, kh);

        long id = kh.getKey().longValue();
        return new ToolCallLog(id, log.conversationId(), log.username(),
                log.toolCallId(), log.toolName(), log.argumentsJson(),
                log.resultText(), log.resultIsError(), log.durationMs(), log.createdAt());
    }

    @Override
    public List<ToolCallLog> findByConversationId(String conversationId) {
        return jdbcTemplate.query(
                "select log_id, conversation_id, username, tool_call_id, tool_name, " +
                        "arguments_json, result_text, result_is_error, duration_ms, created_at " +
                        "from loom_tool_call_log where conversation_id = ? order by created_at, log_id",
                ROW_MAPPER, conversationId);
    }

    @Override
    public Optional<ToolCallLog> findByCallId(String conversationId, String toolCallId) {
        List<ToolCallLog> rows = jdbcTemplate.query(
                "select log_id, conversation_id, username, tool_call_id, tool_name, " +
                        "arguments_json, result_text, result_is_error, duration_ms, created_at " +
                        "from loom_tool_call_log where conversation_id = ? and tool_call_id = ? limit 1",
                ROW_MAPPER, conversationId, toolCallId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
