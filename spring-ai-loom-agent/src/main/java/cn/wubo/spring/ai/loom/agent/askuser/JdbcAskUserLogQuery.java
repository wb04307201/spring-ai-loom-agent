package cn.wubo.spring.ai.loom.agent.askuser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * {@link IAskUserLogQuery} 默认实现:直查 loom_tool_call_log。
 * 解析辅助方法均为 static 包内可测(AskUserLogParsingTest),畸形数据一律兜底不抛。
 */
public class JdbcAskUserLogQuery implements IAskUserLogQuery {

    /** 单次查询条数上限(spec §2.2:limit 钳制防滥用)。 */
    static final int MAX_LIMIT = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BASE_SQL =
            "select log_id, conversation_id, username, arguments_json, result_text, "
                    + "duration_ms, created_at from loom_tool_call_log where tool_name = 'askUser' ";

    private final JdbcTemplate jdbcTemplate;

    public JdbcAskUserLogQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<AskUserLogRecord> recent(int limit, int offset, String usernameOrNull) {
        int eff = Math.max(1, Math.min(limit, MAX_LIMIT));
        int off = Math.max(0, offset);
        boolean byUser = usernameOrNull != null && !usernameOrNull.isBlank();
        String sql = BASE_SQL
                + (byUser ? "and username = ? " : "")
                + "order by created_at desc, log_id desc limit ? offset ?";
        // H2 支持 LIMIT/OFFSET ?(仓库先例:DefaultKnowledgeMarketService L289 / KnowledgeTagService L151)
        return byUser
                ? jdbcTemplate.query(sql, JdbcAskUserLogQuery::mapRow, usernameOrNull, eff, off)
                : jdbcTemplate.query(sql, JdbcAskUserLogQuery::mapRow, eff, off);
    }

    private static AskUserLogRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        String args = rs.getString("arguments_json");
        // 生产形态:MethodToolCallback 把 @Tool 方法的 String 返回值 JSON 序列化成
        // 带引号的 string literal 后 LoggingToolCallback 才落库 → 先规范化再解析
        String result = unwrapJsonString(rs.getString("result_text"));
        String status = deriveStatus(result);
        long duration = rs.getObject("duration_ms") == null ? 0L : rs.getLong("duration_ms");
        java.time.Instant createdAt = rs.getTimestamp("created_at") == null
                ? java.time.Instant.EPOCH
                : rs.getTimestamp("created_at").toInstant();
        return new AskUserLogRecord(
                rs.getLong("log_id"),
                rs.getString("conversation_id"),
                rs.getString("username"),
                parseQuestion(args),
                parseHeader(args),
                extractAnswer(result),
                status,
                duration,
                createdAt);
    }

    /**
     * 规范化 result_text 的两种存储形态:
     * 生产 = JSON string literal(首字符 ASCII 34,Spring AI MethodToolCallback
     * 对 String 返回值做 JSON 序列化的产物);IT/裸文本 = 原始字符串。
     * 引号开头 → Jackson 解出原始字符串;解失败(引号不闭合等畸形)原样返回;null 安全。
     */
    static String unwrapJsonString(String text) {
        if (text == null || !text.startsWith("\"")) return text;
        try {
            return MAPPER.readValue(text, String.class);
        } catch (Exception e) {
            return text; // 畸形引号形态兜底,不抛
        }
    }

    /** result_text 前缀 → status(前缀逐字对齐 DefaultAskUserTool 返回值,spec §2.2)。 */
    static String deriveStatus(String resultText) {
        resultText = unwrapJsonString(resultText);
        if (resultText == null || resultText.isEmpty()) return "UNKNOWN";
        if (resultText.startsWith("[用户已回答]")) return "ANSWERED";
        if (resultText.startsWith("[用户未作答]")) {
            // 同为"未作答":超时 vs 用户主动停止(cancelAll 哨兵),靠正文细分
            return resultText.contains("已停止") ? "CANCELLED" : "TIMEOUT";
        }
        if (resultText.startsWith("[提问被中断]")) return "CANCELLED";
        if (resultText.startsWith("[提问失败]")) return "FAILED";
        return "UNKNOWN";
    }

    /** ANSWERED 时取前缀之后的正文;其余状态 null。 */
    static String extractAnswer(String resultText) {
        resultText = unwrapJsonString(resultText);
        if (resultText == null) return null;
        String prefix = "[用户已回答] ";
        if (!resultText.startsWith(prefix)) return null;
        String answer = resultText.substring(prefix.length()).trim();
        return answer.isEmpty() ? null : answer;
    }

    static String parseQuestion(String argumentsJson) {
        JsonNode node = readTree(argumentsJson);
        if (node == null) return "(解析失败)";
        JsonNode q = node.get("question");
        return q == null || q.asText("").isBlank() ? "(解析失败)" : q.asText();
    }

    static String parseHeader(String argumentsJson) {
        JsonNode node = readTree(argumentsJson);
        if (node == null) return null;
        JsonNode h = node.get("header");
        return h == null || h.asText("").isBlank() ? null : h.asText();
    }

    private static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null; // 畸形 JSON 兜底,不抛(spec §2.2)
        }
    }
}
