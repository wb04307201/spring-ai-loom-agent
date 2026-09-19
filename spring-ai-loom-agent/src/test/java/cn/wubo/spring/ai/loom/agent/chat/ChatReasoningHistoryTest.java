package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.token.ChatUsageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 历史对话按轮渲染思考(方案 B)回归锁:
 * <ul>
 *   <li>saveReasoning 每轮 append 一行(seq 递增),不再覆盖</li>
 *   <li>listReasoning 按 seq 返回全部轮次;getReasoning 返回最新一轮</li>
 *   <li>pair() 纯函数:每条 reasoning 绑到时间最近且在容差内的 ASSISTANT,每条最多用一次</li>
 *   <li>attachThinking() 把配对思考注入 AssistantMessage metadata.thinking</li>
 * </ul>
 */
class ChatReasoningHistoryTest {

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private ChatUsageService chatUsageService;
    private ChatReasoningHistory history;
    private String convId;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:reasoning-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new DriverManagerDataSource(url, "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE loom_chat_reasoning (
                conversation_id VARCHAR(255) NOT NULL,
                seq INT NOT NULL,
                reasoning_text CLOB,
                created_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
                updated_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
                PRIMARY KEY (conversation_id, seq)
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE spring_ai_chat_memory (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                conversation_id VARCHAR(255),
                content CLOB,
                type VARCHAR(16),
                timestamp TIMESTAMP(9) WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
            )
            """);
        chatUsageService = new ChatUsageService(jdbcTemplate);
        history = new ChatReasoningHistory(jdbcTemplate, chatUsageService);
        convId = "conv-" + System.nanoTime();
    }

    // ---------- ChatUsageService: append 语义 ----------

    @Test
    void saveReasoning_appends_one_row_per_turn_with_increasing_seq() {
        chatUsageService.saveReasoning(convId, "第一轮思考");
        chatUsageService.saveReasoning(convId, "第二轮思考");
        chatUsageService.saveReasoning(convId, "第三轮思考");

        List<ChatUsageService.ReasoningRow> rows = chatUsageService.listReasoning(convId);
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(ChatUsageService.ReasoningRow::seq).containsExactly(1, 2, 3);
        assertThat(rows).extracting(ChatUsageService.ReasoningRow::reasoningText)
                .containsExactly("第一轮思考", "第二轮思考", "第三轮思考");
    }

    @Test
    void getReasoning_returns_latest_turn() {
        chatUsageService.saveReasoning(convId, "旧思考");
        chatUsageService.saveReasoning(convId, "最新思考");

        assertThat(chatUsageService.getReasoning(convId)).isEqualTo("最新思考");
    }

    @Test
    void saveReasoning_blank_inputs_are_skipped() {
        chatUsageService.saveReasoning(convId, "  ");
        chatUsageService.saveReasoning(null, "x");

        assertThat(chatUsageService.listReasoning(convId)).isEmpty();
    }

    // ---------- pair(): 纯函数配对 ----------

    @Test
    void pair_binds_each_reasoning_to_nearest_assistant_within_tolerance() {
        Instant t0 = Instant.parse("2026-09-19T10:00:00Z");
        List<Instant> assistants = List.of(t0, t0.plusSeconds(60), t0.plusSeconds(120));
        List<ChatUsageService.ReasoningRow> reasonings = List.of(
                new ChatUsageService.ReasoningRow(1, "r1", t0.plusMillis(300)),
                new ChatUsageService.ReasoningRow(2, "r2", t0.plusSeconds(60).plusMillis(500)),
                new ChatUsageService.ReasoningRow(3, "r3", t0.plusSeconds(120).plusMillis(200)));

        Map<Integer, String> bound = ChatReasoningHistory.pair(assistants, reasonings, Duration.ofSeconds(10));

        assertThat(bound).containsExactlyInAnyOrderEntriesOf(Map.of(0, "r1", 1, "r2", 2, "r3"));
    }

    @Test
    void pair_skips_reasoning_outside_tolerance() {
        Instant t0 = Instant.parse("2026-09-19T10:00:00Z");
        List<Instant> assistants = List.of(t0);
        List<ChatUsageService.ReasoningRow> reasonings = List.of(
                new ChatUsageService.ReasoningRow(1, "太久了", t0.plusSeconds(30)));

        assertThat(ChatReasoningHistory.pair(assistants, reasonings, Duration.ofSeconds(10))).isEmpty();
    }

    @Test
    void pair_uses_each_reasoning_and_assistant_at_most_once() {
        Instant t0 = Instant.parse("2026-09-19T10:00:00Z");
        // 两条 reasoning 都离 assistant#0 最近,但 #0 只能绑一条;另一条容差内有 assistant#1 则绑 #1,否则丢弃
        List<Instant> assistants = List.of(t0, t0.plusSeconds(8));
        List<ChatUsageService.ReasoningRow> reasonings = List.of(
                new ChatUsageService.ReasoningRow(1, "r1", t0.plusMillis(100)),
                new ChatUsageService.ReasoningRow(2, "r2", t0.plusMillis(200)));

        Map<Integer, String> bound = ChatReasoningHistory.pair(assistants, reasonings, Duration.ofSeconds(10));

        assertThat(bound).hasSize(2);
        assertThat(bound.get(0)).isEqualTo("r1");   // 更近的赢
        assertThat(bound.get(1)).isEqualTo("r2");
    }

    @Test
    void pair_handles_assistant_rows_without_reasoning() {
        Instant t0 = Instant.parse("2026-09-19T10:00:00Z");
        List<Instant> assistants = List.of(t0, t0.plusSeconds(60));
        List<ChatUsageService.ReasoningRow> reasonings = List.of(
                new ChatUsageService.ReasoningRow(1, "只有第二轮想了", t0.plusSeconds(60).plusMillis(100)));

        Map<Integer, String> bound = ChatReasoningHistory.pair(assistants, reasonings, Duration.ofSeconds(10));

        assertThat(bound).containsExactly(Map.entry(1, "只有第二轮想了"));
    }

    // ---------- attachThinking(): 端到端(真 H2) ----------

    @Test
    void attachThinking_injects_metadata_into_matching_assistant_messages() {
        Instant t1 = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MICROS);
        Instant t2 = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        insertMemory(convId, "USER", "第一问", t1.minusSeconds(5));
        insertMemory(convId, "ASSISTANT", "第一答", t1);
        insertMemory(convId, "USER", "第二问", t2.minusSeconds(5));
        insertMemory(convId, "ASSISTANT", "第二答", t2);
        // reasoning 落库时刻 = 流结束,与 ASSISTANT 行写入几乎同时(这里模拟 +300ms)
        insertReasoning(convId, 1, "第一轮思考", t1.plusMillis(300));
        insertReasoning(convId, 2, "第二轮思考", t2.plusMillis(300));

        List<Message> messages = List.of(
                new UserMessage("第一问"),
                new AssistantMessage("第一答"),
                new UserMessage("第二问"),
                new AssistantMessage("第二答"));

        List<Message> out = history.attachThinking(convId, messages);

        assertThat(out).hasSize(4);
        assertThat(thinkingOf(out.get(1))).isEqualTo("第一轮思考");
        assertThat(thinkingOf(out.get(3))).isEqualTo("第二轮思考");
        // USER 消息不受影响
        assertThat(out.get(0).getText()).isEqualTo("第一问");
        // 原消息文本保留
        assertThat(out.get(1).getText()).isEqualTo("第一答");
    }

    @Test
    void attachThinking_without_reasoning_rows_returns_messages_untouched() {
        List<Message> messages = new ArrayList<>(List.of(
                new UserMessage("问"), new AssistantMessage("答")));

        List<Message> out = history.attachThinking(convId, messages);

        assertThat(out).hasSize(2);
        assertThat(thinkingOf(out.get(1))).isNull();
    }

    private static String thinkingOf(Message m) {
        Object t = m.getMetadata().get("thinking");
        return t == null ? null : t.toString();
    }

    private void insertMemory(String conversationId, String type, String content, Instant ts) {
        jdbcTemplate.update("insert into spring_ai_chat_memory (conversation_id, content, type, timestamp) values (?, ?, ?, ?)",
                conversationId, content, type, Timestamp.from(ts));
    }

    private void insertReasoning(String conversationId, int seq, String text, Instant ts) {
        jdbcTemplate.update("insert into loom_chat_reasoning (conversation_id, seq, reasoning_text, created_at, updated_at) values (?, ?, ?, ?, ?)",
                conversationId, seq, text, Timestamp.from(ts), Timestamp.from(ts));
    }
}
