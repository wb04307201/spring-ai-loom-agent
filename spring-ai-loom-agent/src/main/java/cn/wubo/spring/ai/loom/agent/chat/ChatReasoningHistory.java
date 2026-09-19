package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.token.ChatUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 历史对话按轮渲染思考：把 {@code loom_chat_reasoning} 的每轮思考绑定回
 * {@code GET /conversation/{id}} 返回的 ASSISTANT 消息（metadata.thinking），
 * 前端历史视图据此填充每条助手气泡的思考折叠区。
 *
 * <p><b>配对策略 = 时间戳就近</b>：每轮的 ASSISTANT 记忆行（LastChunk advisor
 * doFinally）与 reasoning 行（SseController onComplete）都在同一流结束时刻落库，
 * 相差毫秒级。按 |Δt| 全局最近优先贪心配对，容差 {@link #TOLERANCE}，每条
 * reasoning / 每个 ASSISTANT 至多使用一次。不用序号配对的原因：带工具调用的
 * 轮次会产生多条 ASSISTANT 行，序号会错位。
 */
@Service
public class ChatReasoningHistory {

    private static final Logger log = LoggerFactory.getLogger(ChatReasoningHistory.class);

    /** reasoning.created_at 与 ASSISTANT 行 timestamp 的最大配对间隔。 */
    static final Duration TOLERANCE = Duration.ofSeconds(10);

    private final JdbcTemplate jdbcTemplate;
    private final ChatUsageService chatUsageService;

    public ChatReasoningHistory(JdbcTemplate jdbcTemplate, ChatUsageService chatUsageService) {
        this.jdbcTemplate = jdbcTemplate;
        this.chatUsageService = chatUsageService;
    }

    /**
     * 纯函数：reasoning 行 ↔ ASSISTANT 时间戳（按序号）就近配对。
     *
     * @param assistantTimestamps 第 i 个元素 = 历史消息里第 i 条 ASSISTANT 的落库时间
     * @return key = assistant 序号，value = 该轮的思考文本
     */
    public static Map<Integer, String> pair(List<Instant> assistantTimestamps,
                                            List<ChatUsageService.ReasoningRow> reasonings,
                                            Duration tolerance) {
        Map<Integer, String> bound = new HashMap<>();
        if (assistantTimestamps == null || reasonings == null
                || assistantTimestamps.isEmpty() || reasonings.isEmpty()) {
            return bound;
        }
        // 全部候选 (Δt, reasoningIdx, assistantIdx) 按 Δt 升序，贪心取互不冲突的最近对
        record Candidate(long distMillis, int rIdx, int aIdx) {}
        List<Candidate> candidates = new ArrayList<>();
        for (int r = 0; r < reasonings.size(); r++) {
            Instant rTs = reasonings.get(r).createdAt();
            if (rTs == null) continue;
            for (int a = 0; a < assistantTimestamps.size(); a++) {
                Instant aTs = assistantTimestamps.get(a);
                if (aTs == null) continue;
                long d = Math.abs(Duration.between(aTs, rTs).toMillis());
                if (d <= tolerance.toMillis()) {
                    candidates.add(new Candidate(d, r, a));
                }
            }
        }
        candidates.sort(Comparator.comparingLong(Candidate::distMillis));
        Set<Integer> usedR = new HashSet<>();
        Set<Integer> usedA = new HashSet<>();
        for (Candidate c : candidates) {
            // 先双查再双加：被拒绝的候选不得消耗 used 标记（&& 短路会误伤）
            if (!usedR.contains(c.rIdx()) && !usedA.contains(c.aIdx())) {
                usedR.add(c.rIdx());
                usedA.add(c.aIdx());
                bound.put(c.aIdx(), reasonings.get(c.rIdx()).reasoningText());
            }
        }
        return bound;
    }

    /**
     * 给历史消息列表中配对成功的 AssistantMessage 注入 {@code metadata.thinking}。
     * 无 reasoning / 无配对时原样返回，绝不抛异常（历史查看不因思考缺失而失败）。
     */
    public List<Message> attachThinking(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return messages;
        try {
            List<ChatUsageService.ReasoningRow> reasonings = chatUsageService.listReasoning(conversationId);
            if (reasonings.isEmpty()) return messages;

            // ASSISTANT 记忆行的落库时间（与 chatMemoryRepository 读取顺序一致：timestamp 升序）
            List<Instant> assistantTimestamps = jdbcTemplate.query(
                    "SELECT timestamp FROM spring_ai_chat_memory " +
                            "WHERE conversation_id = ? AND type = 'ASSISTANT' ORDER BY timestamp",
                    (rs, n) -> rs.getTimestamp("timestamp") == null ? null : rs.getTimestamp("timestamp").toInstant(),
                    conversationId);

            Map<Integer, String> bound = pair(assistantTimestamps, reasonings, TOLERANCE);
            if (bound.isEmpty()) return messages;

            List<Message> out = new ArrayList<>(messages.size());
            int assistantIdx = 0;
            for (Message m : messages) {
                if (m instanceof AssistantMessage am) {
                    String thinking = bound.get(assistantIdx++);
                    if (thinking != null && !thinking.isBlank()) {
                        Map<String, Object> meta = new HashMap<>(am.getMetadata());
                        meta.put("thinking", thinking);
                        out.add(AssistantMessage.builder()
                                .content(am.getText() == null ? "" : am.getText())
                                .properties(meta)
                                .toolCalls(am.getToolCalls())
                                .media(am.getMedia())
                                .build());
                        continue;
                    }
                }
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("attachThinking 失败，按无思考返回: conv={} err={}", conversationId, e.getMessage());
            return messages;
        }
    }
}
