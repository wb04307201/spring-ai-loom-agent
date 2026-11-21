package cn.wubo.spring.ai.loom.agent.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.content.MediaContent;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史回放装配器(spec 2026-11-21 agentic-loop-steering Task 10 / #3 / §5.10)——
 * 把 {@code GET spring/ai/loom/conversation/{id}} 的 Message 条目与
 * {@link ToolCallHistory#inject} 的 TOOL_CALL 条目按时间戳**服务端归位交错**,
 * 取代旧的「消息原序 + 工具条目全部追加末尾」(旧行为让多轮 run 的工具全落进
 * 最后一个气泡,即缺陷 #3)。
 *
 * <p><b>Message 条目形态</b>:原 Message 对象改为 {@link LinkedHashMap} 包装,
 * 键沿用 Jackson 对 Message bean 的序列化形态 —— {@code text / messageType /
 * metadata / media},AssistantMessage 另带 {@code toolCalls};
 * **additive** 顶层 {@code timestamp}(ISO-8601 字符串,旧前端未知键自然忽略,
 * 取代 spec 之前"Message 形状冻结"的裁定,spec C17)。
 *
 * <p><b>时间戳来源</b>(主路径 JDBC,仿 {@link ChatReasoningHistory} 先例):
 * {@code SELECT timestamp FROM spring_ai_chat_memory WHERE conversation_id = ?
 * ORDER BY sequence_id} —— {@code JdbcChatMemoryRepository.findByConversationId}
 * 正是按 sequence_id 取行并滤掉 TOOL 行(MessageRowMapper 对 TOOL 返回 null),
 * 行数一致才做按下标对齐;行数不等(消费方换过非 JDBC memory 仓储等异常态)
 * 则逐条回退到 Spring AI 仓储读取时写入消息 metadata 的行时间戳
 * ({@link JdbcChatMemoryRepository#CONVERSATION_TS});仍取不到 → 该条目无
 * timestamp,排到末尾(等价旧「追加末尾」,不猜时间)。
 * 列类型 {@code TIMESTAMP}(无时区),与审计表 {@code injected_at} 同基准,
 * 同 JVM 内 {@code Timestamp.from}/{@code toInstant} 往返一致(R22 修订:
 * 仅与 {@code loom_tool_call_log.created_at} TIMESTAMP WITH TIME ZONE 交叉比较时
 * 两边都转 Instant 再比,见 {@link ToolCallHistory#createdAtOf})。
 *
 * <p><b>排序规则</b>:稳定排序(同刻保插入顺序,消息条目先于工具条目 ——
 * 无工具/单迭代无 steering 会话的输出与改造前逐条一致,A1);
 * timestamp 为 null 的条目(nullsLast)落末尾。
 *
 * <p><b>与 renderMessages 的关系(Task 14 对接,已核 app.js:1357-1417)</b>:
 * 旧前端把 TOOL_CALL 挂到 `_lastBotBubble || mainContent`,交错后迭代 1 的工具
 * 排在其 ASSISTANT 文本行之前 → 旧前端下工具条渲染在气泡上方而非气泡内 ——
 * 这是 spec C17 明令的过渡态,气泡归位在 Task 14 前端改造后完成;
 * 消息条目相对顺序(USER 恒在其 ASSISTANT 之前)不变,分组按 USER 行切分
 * (§5.6/§5.10)因此语义成立。
 */
public final class ChatHistoryAssembler {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryAssembler.class);

    private ChatHistoryAssembler() {
    }

    /**
     * 装配历史回放响应体。
     *
     * @param jdbcTemplate   用于 chat_memory 行时间戳查询;null 或查询失败逐条降级 metadata
     * @param conversationId 会话 id
     * @param messages       attachThinking 之后的消息列表(顺序 = 仓储读取顺序 = sequence_id)
     * @param toolEntries    {@link ToolCallHistory#inject} 的工具条目(可为 null)
     * @return 交错合并后的条目列表(全部为 Map;additive 顶层 timestamp)
     */
    public static List<Map<String, Object>> assemble(JdbcTemplate jdbcTemplate,
                                                     String conversationId,
                                                     List<Message> messages,
                                                     List<Map<String, Object>> toolEntries) {
        List<Message> msgs = messages == null ? List.of() : messages;
        List<Map<String, Object>> tools = toolEntries == null ? List.of() : toolEntries;

        List<Instant> rowTs = queryMemoryTimestamps(jdbcTemplate, conversationId, msgs.size());

        // 稳定排序:先消息(仓储原序),后工具条目;比较键 = 时间戳(null 排末尾)
        record Timed(Instant ts, Map<String, Object> entry) {
        }
        List<Timed> timed = new ArrayList<>(msgs.size() + tools.size());
        int i = 0;
        for (Message m : msgs) {
            Instant ts = (rowTs != null && i < rowTs.size()) ? rowTs.get(i) : null;
            if (ts == null) {
                ts = metadataTimestamp(m); // JDBC 对齐失败 / 该行缺值 → metadata 兜底
            }
            timed.add(new Timed(ts, toEntry(m, ts)));
            i++;
        }
        for (Map<String, Object> t : tools) {
            // 形状冻结:TOOL_CALL 条目原样透传,不添加不改写(spec 接口约定)
            timed.add(new Timed(ToolCallHistory.createdAtOf(t), t));
        }
        timed.sort(Comparator.comparing(Timed::ts, Comparator.nullsLast(Comparator.naturalOrder())));

        List<Map<String, Object>> out = new ArrayList<>(timed.size());
        for (Timed t : timed) {
            out.add(t.entry());
        }
        return out;
    }

    /**
     * Message → wire 形态的 Map。键与原 Jackson bean 序列化一致
     * (text/messageType/metadata,MediaContent 消息带 media,AssistantMessage 带 toolCalls),
     * 外加 additive 顶层 {@code timestamp}(ISO-8601;ts 为 null 时不放该键)。
     */
    private static Map<String, Object> toEntry(Message m, Instant ts) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("text", m.getText());
        entry.put("messageType", m.getMessageType() == null ? null : m.getMessageType().name());
        entry.put("metadata", m.getMetadata());
        if (m instanceof MediaContent mc) {
            entry.put("media", mc.getMedia());
        }
        if (m instanceof AssistantMessage am) {
            entry.put("toolCalls", am.getToolCalls());
        }
        if (ts != null) {
            entry.put("timestamp", ts.toString());
        }
        return entry;
    }

    /**
     * chat_memory 行时间戳(与消息列表按序对齐)。
     *
     * <p>过滤条件必须复刻 {@code JdbcChatMemoryRepository.findByConversationId} 的行为:
     * 其 MessageRowMapper 把 TOOL 行映射为 null 并被过滤,故这里同样排除
     * {@code type = 'TOOL'},否则行数不等、按下标对齐会整体错位(错位比没
     * timestamp 更糟 —— 会把 B2 同款倒序经由时间戳带进回放)。
     *
     * @return 对齐成功的时间戳列表;行数不等 / 查询失败 / 入参缺失 → null(调用方逐条降级)
     */
    private static List<Instant> queryMemoryTimestamps(JdbcTemplate jdbcTemplate,
                                                       String conversationId,
                                                       int messageCount) {
        if (jdbcTemplate == null || conversationId == null || conversationId.isBlank() || messageCount == 0) {
            return null;
        }
        try {
            List<Instant> ts = jdbcTemplate.query(
                    "SELECT timestamp FROM spring_ai_chat_memory "
                            + "WHERE conversation_id = ? AND type <> 'TOOL' ORDER BY sequence_id",
                    (rs, n) -> {
                        java.sql.Timestamp t = rs.getTimestamp(1);
                        return t == null ? null : t.toInstant();
                    },
                    conversationId);
            if (ts.size() != messageCount) {
                log.warn("chat_memory 时间戳行数({})与消息数({})不等,时间戳降级逐条 metadata 兜底: conv={}",
                        ts.size(), messageCount, conversationId);
                return null;
            }
            return ts;
        } catch (Exception e) {
            // 与 ToolCallHistory.inject 同策略:装配失败不炸历史回看主流程,降级无服务端 ts
            log.warn("chat_memory 时间戳查询失败,时间戳降级逐条 metadata 兜底: conv={} err={}: {}",
                    conversationId, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 单条消息 metadata 里的行时间戳兜底:
     * {@code JdbcChatMemoryRepository}(2.0.0+)读取行时把 timestamp 列放进
     * metadata 的 {@link JdbcChatMemoryRepository#CONVERSATION_TS} 键。
     */
    private static Instant metadataTimestamp(Message m) {
        if (m == null || m.getMetadata() == null) {
            return null;
        }
        Object v = m.getMetadata().get(JdbcChatMemoryRepository.CONVERSATION_TS);
        return v instanceof Instant instant ? instant : null;
    }
}
