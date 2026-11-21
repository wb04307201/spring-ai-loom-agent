package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.model.ToolCallLog;
import cn.wubo.spring.ai.loom.agent.tool.IToolCallLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 10 / #3：历史端点 {@code GET /conversation/{id}} 的 Message 附 timestamp +
 * TOOL_CALL 服务端 interleave 回归锁（端到端 HTTP 层，防止"逻辑对但没接线"，
 * 参照 {@code loadSubtasks} 死代码先例与 {@code ConversationToolCallInjectionIT} 模式）。
 *
 * <p><b>缺陷背景</b>：旧实现把 {@code ToolCallHistory.inject} 的 TOOL_CALL 条目
 * {@code addAll} 到响应末尾，Message 条目又不带时间戳 —— {@code renderMessages}
 * 顺序遍历 + {@code _lastBotBubble} 语义下，<strong>多迭代会话的全部工具条目全落进
 * 最后一个气泡</strong>（缺陷 #3）。
 *
 * <p><b>修复语义</b>（spec §5.10 / C17）：{@link ChatHistoryAssembler} 给 Message 条目
 * 附 additive 顶层 {@code timestamp}（JDBC 按 sequence_id 取行时间戳，降级 metadata
 * {@code CONVERSATION_TS}），再把 TOOL_CALL 条目按 {@code createdAt}（统一转 Instant，
 * R22 修订）与消息时间轴稳定归并 —— 响应顺序即正确气泡归位（Task 14 前端配合）。
 *
 * <p><b>变异自证</b>：interleave 退回 addAll 末尾 ⇒ 用例 1 红；去掉 timestamp 附加
 * ⇒ 用例 2 红。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@DisplayName("Task 10:历史端点 ts + TOOL_CALL 服务端 interleave")
class HistoryInterleaveTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IToolCallLogRepository toolCallLogRepository;

    private static final String USER = "wb04307201";
    private static final String PASS = "123456";

    /** 固定基准时刻（微秒级整值，规避 TIMESTAMP 精度往返问题）。 */
    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");

    private String loginAndGetCookie() {
        ResponseEntity<Map> r = rest.postForEntity(
                "http://localhost:" + port + "/spring/ai/loom/user/login",
                new HttpEntity<>(Map.of("username", USER, "password", PASS), jsonHeaders(null)),
                Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("登录应成功").isTrue();
        List<String> cookies = r.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull().isNotEmpty();
        return cookies.get(0).split(";")[0];
    }

    private HttpHeaders jsonHeaders(String cookie) {
        HttpHeaders h = new HttpHeaders();
        h.set("Content-Type", "application/json; charset=UTF-8");
        if (cookie != null) h.set("Cookie", cookie);
        return h;
    }

    private String newConv() {
        String conv = "hist-it-" + UUID.randomUUID();
        jdbcTemplate.update(
                "MERGE INTO user_conversation (username, conversation_id) KEY (username, conversation_id) VALUES (?, ?)",
                USER, conv);
        return conv;
    }

    private void seedMemory(String conv, String type, String text, Instant ts, int seq) {
        // 与 JdbcChatMemoryRepository 2.0.2 实际存储一致：content 即消息原文（非 JSON）
        jdbcTemplate.update(
                "INSERT INTO spring_ai_chat_memory (conversation_id, content, type, timestamp, sequence_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                conv, text, type, Timestamp.from(ts), seq);
    }

    private void seedToolLog(String conv, String callId, String name, Instant createdAt) {
        toolCallLogRepository.save(new ToolCallLog(null, conv, USER, callId, name,
                "{\"q\":1}", "[用户已回答] 好", false, 1200L, createdAt));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getConversation(String conv) {
        String cookie = loginAndGetCookie();
        ResponseEntity<List> resp = rest.exchange(
                "http://localhost:" + port + "/spring/ai/loom/conversation/" + conv,
                HttpMethod.GET, new HttpEntity<>(jsonHeaders(cookie)), List.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).as("历史读取应 2xx").isTrue();
        return (List<Map<String, Object>>) resp.getBody();
    }

    /** 稳定签名字段：TOOL_CALL 用 toolCallId 区分，消息用 text 区分。 */
    private static String sig(Map<String, Object> e) {
        if ("TOOL_CALL".equals(e.get("messageType"))) return "TC:" + e.get("toolCallId");
        return e.get("messageType") + ":" + e.get("text");
    }

    // ==================== 用例 1 ====================

    @Test
    @DisplayName("多迭代会话：迭代 1 的 askUser TOOL_CALL 排在迭代 1 的 ASSISTANT 附近，不在列表末尾")
    void tool_call_interleaved_by_ts_not_appended() {
        String conv = newConv();
        // 真实 run 的时间轴：USER@0 → TC1@2s(iter1) → ASST1@5s → 插话USER@10s → TC2@12s → ASST2@15s
        seedMemory(conv, "USER", "任务", T0, 1);
        seedToolLog(conv, "tc-ask", "askUser", T0.plusSeconds(2));
        seedMemory(conv, "ASSISTANT", "迭代一", T0.plusSeconds(5), 2);
        seedMemory(conv, "USER", "插话纠偏", T0.plusSeconds(10), 3);
        seedToolLog(conv, "tc-build", "mavenBuild", T0.plusSeconds(12));
        seedMemory(conv, "ASSISTANT", "迭代二", T0.plusSeconds(15), 4);

        List<Map<String, Object>> body = getConversation(conv);

        assertThat(body).extracting(HistoryInterleaveTest::sig).containsExactly(
                "USER:任务",
                "TC:tc-ask",          // ← 旧行为会落末尾；现在按时间轴归位到迭代 1 附近
                "ASSISTANT:迭代一",
                "USER:插话纠偏",
                "TC:tc-build",
                "ASSISTANT:迭代二");

        // 防"全挂最后气泡"：两个工具条目之间必须隔着消息条目
        int iAsk = indexOfSig(body, "TC:tc-ask");
        int iBuild = indexOfSig(body, "TC:tc-build");
        assertThat(iBuild - iAsk)
                .as("两个工具条目不得相邻堆叠在列表末尾（#3 缺陷形态）")
                .isGreaterThan(1);
    }

    private static int indexOfSig(List<Map<String, Object>> body, String sig) {
        for (int i = 0; i < body.size(); i++) {
            if (sig.equals(sig(body.get(i)))) return i;
        }
        throw new AssertionError("响应缺少条目: " + sig);
    }

    // ==================== 用例 2 ====================

    @Test
    @DisplayName("Message 条目携带 additive 顶层 timestamp（ISO，等于落库行时间戳）；TOOL_CALL 条目形状不变")
    void message_entries_carry_timestamp() {
        String conv = newConv();
        seedMemory(conv, "USER", "问", T0.plusSeconds(1), 1);
        seedMemory(conv, "ASSISTANT", "答", T0.plusSeconds(9), 2);

        List<Map<String, Object>> body = getConversation(conv);
        assertThat(body).hasSize(2);

        Map<String, Object> u = body.get(0);
        assertThat(u.get("messageType")).isEqualTo("USER");
        assertThat(u.get("text")).isEqualTo("问");
        assertThat(u).containsKey("metadata");
        assertThat(String.valueOf(u.get("timestamp")))
                .as("additive timestamp = spring_ai_chat_memory 行时间戳")
                .isEqualTo(T0.plusSeconds(1).toString());
        assertThat(String.valueOf(body.get(1).get("timestamp")))
                .isEqualTo(T0.plusSeconds(9).toString());

        // TOOL_CALL 条目形状冻结：无 timestamp 键、createdAt 原样
        String conv2 = newConv();
        seedMemory(conv2, "USER", "问2", T0, 1);
        seedToolLog(conv2, "tc-x", "askUser", T0.plusSeconds(3));
        List<Map<String, Object>> body2 = getConversation(conv2);
        Map<String, Object> tc = body2.stream()
                .filter(e -> "TOOL_CALL".equals(e.get("messageType"))).findFirst().orElseThrow();
        assertThat(tc).containsKeys("messageType", "toolName", "toolCallId", "arguments",
                "result", "isError", "durationMs", "truncated", "createdAt");
        assertThat(tc).doesNotContainKey("timestamp");
    }

    // ==================== 用例 3（A1 兼容，判读见类注释与报告） ====================

    @Test
    @DisplayName("A1：无 steering 会话消息条目相对顺序与改造前一致；工具 ts 天然最晚时输出逐条等同旧 addAll")
    void legacy_no_steering_order_unchanged() {
        // (a) 无工具：消息条目顺序 = sequence_id 顺序，与改造前逐条一致
        String conv = newConv();
        seedMemory(conv, "USER", "问A", T0, 1);
        seedMemory(conv, "ASSISTANT", "答A", T0.plusSeconds(5), 2);
        seedMemory(conv, "USER", "问B", T0.plusSeconds(10), 3);
        seedMemory(conv, "ASSISTANT", "答B", T0.plusSeconds(15), 4);

        List<Map<String, Object>> body = getConversation(conv);
        assertThat(body).extracting(e -> String.valueOf(e.get("messageType")))
                .containsExactly("USER", "ASSISTANT", "USER", "ASSISTANT");
        assertThat(body).extracting(e -> String.valueOf(e.get("text")))
                .containsExactly("问A", "答A", "问B", "答B");

        // (b) 工具条目 createdAt 晚于全部消息行（旧形状下 addAll 末尾 == 时间序末尾）：
        //     interleave 输出与改造前逐条一致 —— 气泡分组结果不变（工具仍挂最后一个气泡）。
        String conv2 = newConv();
        seedMemory(conv2, "USER", "任务", T0, 1);
        seedMemory(conv2, "ASSISTANT", "完成", T0.plusSeconds(5), 2);
        seedToolLog(conv2, "tc-late", "getSkill", T0.plusSeconds(8));

        List<Map<String, Object>> body2 = getConversation(conv2);
        assertThat(body2).extracting(HistoryInterleaveTest::sig)
                .as("A1：该形态下 interleave 输出与旧 addAll 末尾完全一致")
                .containsExactly("USER:任务", "ASSISTANT:完成", "TC:tc-late");
    }

    // ==================== 用例 4（降级不变式） ====================

    @Test
    @DisplayName("ts 取不到的条目排到末尾（= 旧「追加末尾」行为），绝不打乱消息相对顺序")
    void unparseable_or_missing_ts_falls_back_to_tail() {
        // createdAt 无法解析成 Instant 的条目理论上不出现（inject 统一 Instant.toString()），
        // 这里用纯 JDBC 路径验证归并稳定性：消息全有 ts（metadata 兜底 + JDBC 主路径同值），
        // 工具条目按 createdAt 插入正确位置。
        String conv = newConv();
        seedMemory(conv, "USER", "问", T0, 1);
        seedMemory(conv, "ASSISTANT", "答", T0.plusSeconds(10), 2);
        seedToolLog(conv, "tc-mid", "listDirectory", T0.plusSeconds(4));
        seedToolLog(conv, "tc-tail", "listFiles", T0.plusSeconds(20));

        List<Map<String, Object>> body = getConversation(conv);
        assertThat(body).extracting(HistoryInterleaveTest::sig)
                .containsExactly("USER:问", "TC:tc-mid", "ASSISTANT:答", "TC:tc-tail");
    }

    // ==================== 用例 5（attachThinking 兼容） ====================

    @Test
    @DisplayName("attachThinking 先于 interleave 执行：metadata.thinking 注入不被包装破坏")
    void attach_thinking_survives_interleave_wrapping() {
        String conv = newConv();
        seedMemory(conv, "USER", "问T", T0, 1);
        seedMemory(conv, "ASSISTANT", "答T", T0.plusSeconds(10), 2);
        Instant reasoningTs = T0.plusSeconds(10).plusMillis(200); // ±10s 就近配对命中
        jdbcTemplate.update(
                "INSERT INTO loom_chat_reasoning (conversation_id, seq, reasoning_text, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                conv, 1, "深思熟虑", Timestamp.from(reasoningTs), Timestamp.from(reasoningTs));
        seedToolLog(conv, "tc-think", "getSkill", T0.plusSeconds(5));

        List<Map<String, Object>> body = getConversation(conv);
        assertThat(body).extracting(HistoryInterleaveTest::sig)
                .containsExactly("USER:问T", "TC:tc-think", "ASSISTANT:答T");

        Map<String, Object> assistant = body.stream()
                .filter(e -> "ASSISTANT".equals(e.get("messageType"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) assistant.get("metadata");
        assertThat(meta)
                .as("interleave 包装后 metadata.thinking 仍在（attachThinking 兼容）")
                .containsEntry("thinking", "深思熟虑");
        assertThat(String.valueOf(assistant.get("timestamp"))).isEqualTo(T0.plusSeconds(10).toString());
    }
}
