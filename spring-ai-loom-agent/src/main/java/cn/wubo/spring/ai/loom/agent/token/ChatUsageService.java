package cn.wubo.spring.ai.loom.agent.token;

import cn.wubo.spring.ai.loom.agent.model.CurrentMonthTokenStat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 替代 方案：直接读 {@code loom_chat_usage} 表。
 *
 * <p>假设 {@code SPRING_AI_CHAT_MEMORY.content} 序列化为 JSON，可从中反推
 * AssistantMessage 的 metadata.usage / toolCalls / model 等。但实际 Spring AI
 * 只持久化 content 字符串，metadata 未落库 → 的 parseUsage 全部返回 null，
 * stats / user / conversation / 我的用量 全部显示 0。
 *
 * <p> 起：每次 ChatResponse 在 SseController 处显式记录 usage 到
 * {@code loom_chat_usage}，本服务只读这张表。
 *
 * <p>不做缓存：统计查询频次低；不引入额外缓存层。
 */
@Component
public class ChatUsageService {

    private static final Logger log = LoggerFactory.getLogger(ChatUsageService.class);

    private final JdbcTemplate jdbcTemplate;

    public ChatUsageService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * SseController 流处理调用：每条 ChatResponse 收到有效 usage 时写一行。
     */
    public void record(String conversationId, String username, long prompt, long completion, long total) {
        if (total <= 0 && prompt <= 0 && completion <= 0) return;
        if (username == null || username.isBlank()) return;
        jdbcTemplate.update(
                "INSERT INTO loom_chat_usage " +
                        "(conversation_id, username, prompt_tokens, completion_tokens, total_tokens, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                conversationId, username, prompt, completion, total, Timestamp.from(Instant.now()));
    }

    /**
     * ：保存一次对话的 AI 思考内容（DashScope enable_thinking 模式下 metadata
     * reasoningContent 累积）。一条对话一条最终记录，conversation_id 是主键。
     * 同一会话多次调用会覆盖（upsert）。
     */
    public void saveReasoning(String conversationId, String reasoningText) {
        if (conversationId == null || conversationId.isBlank()) return;
        if (reasoningText == null || reasoningText.isBlank()) return;
        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbcTemplate.update(
                "UPDATE loom_chat_reasoning SET reasoning_text = ?, updated_at = ? WHERE conversation_id = ?",
                reasoningText, now, conversationId);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO loom_chat_reasoning (conversation_id, reasoning_text, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    conversationId, reasoningText, now, now);
        }
    }

    /**
     * ：读一次对话的 AI 思考。返回 null 表示没有。
     */
    public String getReasoning(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return null;
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT reasoning_text FROM loom_chat_reasoning WHERE conversation_id = ?",
                    String.class, conversationId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 全局月度统计（按用户聚合）。
     */
    public List<UserUsage> monthlyByUser(int year, int month) {
        Map<String, long[]> agg = new HashMap<>();
        jdbcTemplate.query(
                "select username, sum(prompt_tokens), sum(completion_tokens), sum(total_tokens), count(*) " +
                        "from loom_chat_usage " +
                        "where YEAR(created_at) = ? and MONTH(created_at) = ? " +
                        "group by username",
                rs -> {
                    String u = rs.getString(1);
                    long prompt = rs.getLong(2);
                    long completion = rs.getLong(3);
                    long total = rs.getLong(4);
                    long calls = rs.getLong(5);
                    long[] a = agg.computeIfAbsent(u, k -> new long[4]);
                    a[0] += total;
                    a[1] += prompt;
                    a[2] += completion;
                    a[3] += calls;
                },
                year, month);
        return agg.entrySet().stream()
                .map(e -> new UserUsage(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]))
                .sorted((a, b) -> Long.compare(b.totalTokens(), a.totalTokens()))
                .toList();
    }

    /**
     * 用户最近 6 个月 token 用量（user.html 顶部柱状图）。
     * 即使中间月份缺记录也要返回 6 条，totalTokens=0 即可。
     */
    public List<MonthlyUsage> recent6MonthsForUser(String username) {
        Instant toI = LocalDate.now().plusMonths(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant fromI = LocalDate.now().withDayOfMonth(1).minusMonths(5).atStartOfDay(ZoneId.systemDefault()).toInstant();

        Map<String, long[]> agg = new HashMap<>(); // yyyy-M -> [total, calls]
        jdbcTemplate.query(
                "select created_at, total_tokens from loom_chat_usage " +
                        "where username = ? and created_at >= ? and created_at < ?",
                rs -> {
                    LocalDateTime dt = LocalDateTime.ofInstant(rs.getTimestamp(1).toInstant(), ZoneId.systemDefault());
                    String k = dt.getYear() + "-" + dt.getMonthValue();
                    long[] a = agg.computeIfAbsent(k, x -> new long[2]);
                    a[0] += rs.getLong(2);
                    a[1] += 1;
                },
                username, Timestamp.from(fromI), Timestamp.from(toI));

        // 把 6 个月都补齐，缺记录的填 0
        List<MonthlyUsage> out = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusMonths(i);
            String k = d.getYear() + "-" + d.getMonthValue();
            long[] a = agg.getOrDefault(k, new long[]{0, 0});
            out.add(new MonthlyUsage(d.getYear(), d.getMonthValue(), a[0], a[1]));
        }
        return out;
    }

    /**
     * 单会话 token 统计（conversation.html 顶部卡片）。
     */
    public ConversationUsage byConversation(String conversationId) {
        long[] sums = new long[4]; // total, prompt, completion, calls
        jdbcTemplate.query(
                "select sum(prompt_tokens), sum(completion_tokens), sum(total_tokens), count(*) " +
                        "from loom_chat_usage where conversation_id = ?",
                rs -> {
                    sums[1] = rs.getLong(1);
                    sums[2] = rs.getLong(2);
                    sums[0] = rs.getLong(3);
                    sums[3] = rs.getLong(4);
                },
                conversationId);
        return new ConversationUsage(conversationId, sums[3], sums[0], sums[1], sums[2]);
    }

    /**
     * 当前用户的本月 token 用量（聊天界面"我的用量"模态框）。
     */
    public CurrentMonthTokenStat currentMonthForUser(String username) {
        if (username == null || username.isBlank()) {
            return new CurrentMonthTokenStat("", 0, 0, 0, 0, 0);
        }
        LocalDate now = LocalDate.now();
        long[] sums = new long[4]; // total, prompt, completion, calls
        jdbcTemplate.query(
                "select sum(prompt_tokens), sum(completion_tokens), sum(total_tokens), count(*) " +
                        "from loom_chat_usage " +
                        "where username = ? and YEAR(created_at) = ? and MONTH(created_at) = ?",
                rs -> {
                    sums[1] = rs.getLong(1);
                    sums[2] = rs.getLong(2);
                    sums[0] = rs.getLong(3);
                    sums[3] = rs.getLong(4);
                },
                username, now.getYear(), now.getMonthValue());
        long total = sums[0];
        long prompt = sums[1];
        long completion = sums[2];
        long calls = sums[3];
        double avg = calls == 0 ? 0 : (double) total / calls;
        return new CurrentMonthTokenStat(username, total, prompt, completion, (int) calls, avg);
    }

    public record UserUsage(String username, long totalTokens, long promptTokens, long completionTokens,
                            long callCount) {
    }

    public record MonthlyUsage(int year, int month, long totalTokens, long callCount) {
    }

    public record ConversationUsage(String conversationId, long callCount, long totalTokens, long promptTokens,
                                    long completionTokens) {
    }
}
