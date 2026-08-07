package cn.wubo.spring.ai.loom.agent.token;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * V4.0 替代原 {@link ITokenUsage}：从 {@code SPRING_AI_CHAT_MEMORY} 的 metadata
 * 实时聚合 token 统计。旧 {@code chat_token_usage} 表已 V4.0 删除。
 *
 * <p>实现说明：H2 2.3.232 不支持 {@code JSON_VALUE} / {@code JSONPATH} 函数
 * （H2 2.4+ 才支持 JSONPATH），而 V4.0 之前我们用的是 {@code JSON_VALUE}。
 * 改用 Java 端 ObjectMapper 解析 metadata CLOB → 内存聚合。
 *
 * <p>性能：admin 视图数据量小（chat_memory 总行数 + 单个用户的消息量都不大），
 * 全量扫 + Java 解析在毫秒级。后续若成为热点可换 PostgreSQL + JSON_PATH。
 *
 * <p>不做缓存：admin 统计查询频次低 + 全部走索引；不增加缓存层。
 */
@Component
public class ChatUsageService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ChatUsageService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 全局月度统计（按用户聚合）。只算 ASSISTANT 消息的 usage。
     */
    public List<UserUsage> monthlyByUser(int year, int month) {
        Instant fromI = java.time.LocalDateTime.of(year, month, 1, 0, 0)
                .atZone(java.time.ZoneId.systemDefault()).toInstant();
        Instant toI = fromI.plusSeconds(java.time.Duration.ofDays(31).toSeconds());
        Map<String, long[]> agg = new HashMap<>();
        for (UsageRow r : scanAssistantUsageBetween(fromI, toI)) {
            long[] a = agg.computeIfAbsent(r.username, k -> new long[4]); // [total, prompt, completion, calls]
            a[0] += r.total;
            a[1] += r.prompt;
            a[2] += r.completion;
            a[3] += 1;
        }
        return agg.entrySet().stream()
                .map(e -> new UserUsage(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]))
                .sorted((a, b) -> Long.compare(b.totalTokens(), a.totalTokens()))
                .toList();
    }

    /**
     * 用户最近 6 个月 token 用量（user.html 顶部柱状图）。
     */
    public List<MonthlyUsage> recent6MonthsForUser(String username) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now()
                .withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        Instant fromI = now.minusMonths(5).atZone(java.time.ZoneId.systemDefault()).toInstant();
        Instant toI = now.plusMonths(1).atZone(java.time.ZoneId.systemDefault()).toInstant();

        Map<String, long[]> agg = new HashMap<>(); // key: "yyyy-M" → [total, calls]
        for (UsageRow r : scanAssistantUsageBetween(fromI, toI)) {
            if (!username.equals(r.username)) continue;
            java.time.LocalDateTime dt = java.time.LocalDateTime.ofInstant(r.timestamp, java.time.ZoneId.systemDefault());
            String k = dt.getYear() + "-" + dt.getMonthValue();
            long[] a = agg.computeIfAbsent(k, x -> new long[2]);
            a[0] += r.total;
            a[1] += 1;
        }
        return agg.entrySet().stream()
                .map(e -> {
                    String[] parts = e.getKey().split("-");
                    return new MonthlyUsage(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                            e.getValue()[0], e.getValue()[1]);
                })
                .sorted((a, b) -> a.year() != b.year() ? Integer.compare(a.year(), b.year()) : Integer.compare(a.month(), b.month()))
                .toList();
    }

    /**
     * 单会话 token 统计（conversation.html 顶部卡片）。
     */
    public ConversationUsage byConversation(String conversationId) {
        long total = 0, prompt = 0, completion = 0;
        long calls = 0;
        Instant fromI = Instant.EPOCH;
        Instant toI = Instant.now().plusSeconds(60);
        for (UsageRow r : scanAssistantUsageBetween(fromI, toI)) {
            if (!conversationId.equals(r.conversationId)) continue;
            total += r.total;
            prompt += r.prompt;
            completion += r.completion;
            calls++;
        }
        return new ConversationUsage(conversationId, calls, total, prompt, completion);
    }

    /**
     * 公共扫描：从 chat_memory 读指定时间窗内的 ASSISTANT 消息 + 关联 user_conversation，
     * Java 端解析 content JSON 抽取 usage。
     *
     * <p>H2 schema 实际是：
     * <pre>
     *   CREATE TABLE SPRING_AI_CHAT_MEMORY (
     *       conversation_id VARCHAR(36), content LONGVARCHAR, type VARCHAR(10), timestamp TIMESTAMP
     *   )
     * </pre>
     * 没有 metadata 列——usage 存在 content（JSON 序列化 AssistantMessage）的
     * {@code usage} 字段里。
     */
    private List<UsageRow> scanAssistantUsageBetween(Instant from, Instant to) {
        Timestamp fromTs = Timestamp.from(from);
        Timestamp toTs = Timestamp.from(to);
        List<UsageRow> out = new ArrayList<>();
        jdbcTemplate.query(
                "select cm.conversation_id, uc.username, cm.content, cm.timestamp " +
                        "from spring_ai_chat_memory cm " +
                        "join user_conversation uc on uc.conversation_id = cm.conversation_id " +
                        "where cm.type = 'ASSISTANT' and cm.timestamp >= ? and cm.timestamp < ?",
                rs -> {
                    String convId = rs.getString("conversation_id");
                    String user = rs.getString("username");
                    String content = rs.getString("content");
                    java.sql.Timestamp ts = rs.getTimestamp("timestamp");
                    long[] usage = parseUsage(content);
                    if (usage == null) return;
                    out.add(new UsageRow(convId, user, ts.toInstant(), usage[0], usage[1], usage[2]));
                },
                fromTs, toTs);
        return out;
    }

    /**
     * 解析 AssistantMessage 序列化后的 JSON content，提取 usage。
     * content 结构：{ "toolCallId": null, "type": "assistant", "content": "...", "usage": { "totalTokens": ..., ... }, ... }。
     */
    private long[] parseUsage(String content) {
        if (content == null || content.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode usage = root.path("usage");
            if (usage.isMissingNode() || usage.isNull()) return null;
            long total = usage.path("totalTokens").asLong(0);
            long prompt = usage.path("promptTokens").asLong(0);
            long completion = usage.path("completionTokens").asLong(0);
            if (total == 0 && prompt == 0 && completion == 0) return null;
            return new long[]{total, prompt, completion};
        } catch (Exception e) {
            return null;
        }
    }

    public record UserUsage(String username, long totalTokens, long promptTokens, long completionTokens, long callCount) {}
    public record MonthlyUsage(int year, int month, long totalTokens, long callCount) {}
    public record ConversationUsage(String conversationId, long callCount, long totalTokens, long promptTokens, long completionTokens) {}

    private record UsageRow(String conversationId, String username, Instant timestamp, long total, long prompt, long completion) {}
}
