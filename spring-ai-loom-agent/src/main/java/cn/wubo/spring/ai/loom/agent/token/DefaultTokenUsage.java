package cn.wubo.spring.ai.loom.agent.token;

import cn.wubo.spring.ai.loom.agent.model.ConversationTokenStat;
import cn.wubo.spring.ai.loom.agent.model.CurrentMonthTokenStat;
import cn.wubo.spring.ai.loom.agent.model.MonthlyTokenStat;
import cn.wubo.spring.ai.loom.agent.model.TurnTokenStat;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public class DefaultTokenUsage implements ITokenUsage {

    private final JdbcTemplate jdbcTemplate;

    public DefaultTokenUsage(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(String conversationId, String username, String role,
                       int promptTokens, int completionTokens, int totalTokens,
                       String model, Integer durationMs) {
        if (conversationId == null || username == null) return;
        jdbcTemplate.update(
                "insert into chat_token_usage (conversation_id, username, role, " +
                        "prompt_tokens, completion_tokens, total_tokens, model, duration_ms) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?)",
                conversationId, username, role,
                promptTokens, completionTokens, totalTokens, model, durationMs);
    }

    @Override
    public List<MonthlyTokenStat> monthlyByUser(int year, int month) {
        LocalDateTime from = LocalDateTime.of(year, month, 1, 0, 0);
        LocalDateTime to = from.plusMonths(1);
        Instant fromI = from.atZone(ZoneId.systemDefault()).toInstant();
        Instant toI = to.atZone(ZoneId.systemDefault()).toInstant();
        return jdbcTemplate.query(
                "select username, sum(total_tokens) as total_tokens, " +
                        "sum(prompt_tokens) as prompt_tokens, sum(completion_tokens) as completion_tokens, " +
                        "count(*) as call_count " +
                        "from chat_token_usage where created_at >= ? and created_at < ? and role = 'ASSISTANT' " +
                        "group by username order by total_tokens desc",
                (rs, rowNum) -> new MonthlyTokenStat(
                        rs.getString("username"),
                        rs.getLong("total_tokens"),
                        rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens"),
                        rs.getLong("call_count")),
                Timestamp.from(fromI), Timestamp.from(toI));
    }

    @Override
    public List<ConversationTokenStat> byUser(String username, Instant from, Instant to) {
        return jdbcTemplate.query(
                "select t.conversation_id, sum(t.total_tokens) as total_tokens, count(*) as call_count, " +
                        "min(t.created_at) as first_at, max(t.created_at) as last_at " +
                        "from chat_token_usage t where t.username = ? and t.role = 'ASSISTANT' " +
                        "and t.created_at >= ? and t.created_at < ? " +
                        "group by t.conversation_id order by last_at desc",
                (rs, rowNum) -> {
                    String convId = rs.getString("conversation_id");
                    String preview = previewFor(convId);
                    Timestamp firstAt = rs.getTimestamp("first_at");
                    return new ConversationTokenStat(convId, username, preview,
                            firstAt == null ? null : firstAt.toInstant(),
                            rs.getLong("total_tokens"),
                            rs.getLong("call_count"));
                },
                username, Timestamp.from(from), Timestamp.from(to));
    }

    @Override
    public List<TurnTokenStat> byConversation(String conversationId) {
        return jdbcTemplate.query(
                "select conversation_id, username, role, model, prompt_tokens, completion_tokens, " +
                        "total_tokens, duration_ms, created_at " +
                        "from chat_token_usage where conversation_id = ? order by created_at",
                (rs, rowNum) -> new TurnTokenStat(
                        rs.getString("conversation_id"),
                        rs.getString("username"),
                        rs.getString("role"),
                        rs.getString("model"),
                        rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens"),
                        rs.getLong("total_tokens"),
                        (Integer) rs.getObject("duration_ms"),
                        rs.getTimestamp("created_at").toInstant()),
                conversationId);
    }

    @Override
    public CurrentMonthTokenStat currentMonthForUser(String username) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime to = from.plusMonths(1);
        Instant fromI = from.atZone(ZoneId.systemDefault()).toInstant();
        Instant toI = to.atZone(ZoneId.systemDefault()).toInstant();
        return jdbcTemplate.queryForObject(
                "select coalesce(sum(total_tokens), 0) as total, " +
                        "coalesce(sum(prompt_tokens), 0) as prompt, " +
                        "coalesce(sum(completion_tokens), 0) as completion, " +
                        "count(*) as calls " +
                        "from chat_token_usage where username = ? and role = 'ASSISTANT' " +
                        "and created_at >= ? and created_at < ?",
                (rs, rowNum) -> {
                    long total = rs.getLong("total");
                    long calls = rs.getLong("calls");
                    return new CurrentMonthTokenStat(username, total, rs.getLong("prompt"),
                            rs.getLong("completion"), calls, calls == 0 ? 0 : (double) total / calls);
                },
                username, Timestamp.from(fromI), Timestamp.from(toI));
    }

    private String previewFor(String conversationId) {
        try {
            String preview = jdbcTemplate.queryForObject(
                    "select content from spring_ai_chat_memory " +
                            "where conversation_id = ? and type = 'USER' " +
                            "order by timestamp limit 1",
                    String.class, conversationId);
            if (preview == null) return "(无内容)";
            return preview.length() > 30 ? preview.substring(0, 30) + "..." : preview;
        } catch (Exception e) {
            return "(无内容)";
        }
    }
}
