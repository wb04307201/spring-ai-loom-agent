package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.tool.IToolCallLogRepository;
import cn.wubo.spring.ai.loom.agent.model.ToolCallLog;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /conversation/{id}} 工具调用注入的端到端回归锁。
 *
 * <p>锁住的是**接口契约**：历史会话响应里必须出现 {@code messageType=TOOL_CALL}
 * 的条目，且携带渲染 askUser 卡片所需的 {@code arguments}。
 *
 * <p>只测 {@link ToolCallHistory} 单元逻辑是不够的 —— 它可能写对了但**没接线**
 * （本项目 {@code loadSubtasks} 就是前车之鉴：逻辑完整却从未被调用）。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@DisplayName("GET /conversation/{id} 工具调用注入（接口契约）")
class ConversationToolCallInjectionIT {

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

    private String loginAndGetCookie() {
        ResponseEntity<Map> r = rest.postForEntity(
                "http://localhost:" + port + "/spring/ai/loom/user/login",
                new HttpEntity<>(Map.of("username", USER, "password", PASS),
                        jsonHeaders(null)),
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

    @Test
    @DisplayName("会话有工具调用记录 ⇒ 响应必须含 TOOL_CALL 条目")
    void toolCallsAppearInConversationHistory() {
        String convId = "conv-tool-" + UUID.randomUUID();
        // 建会话归属（否则接口按跨用户读拒绝）
        jdbcTemplate.update(
                "MERGE INTO user_conversation (username, conversation_id) KEY (username, conversation_id) VALUES (?, ?)",
                USER, convId);
        // 写一条工具调用记录
        toolCallLogRepository.save(new ToolCallLog(
                null, convId, USER, "wrap-" + UUID.randomUUID(), "askUser",
                "{\"question\":\"午餐吃面条还是米饭？\",\"header\":\"午餐选择\"," +
                        "\"optionsJson\":\"[{\\\"label\\\":\\\"面条\\\"},{\\\"label\\\":\\\"米饭\\\"}]\"," +
                        "\"multiSelect\":false}",
                "[用户已回答] 面条", false, 97817L, Instant.now()));

        String cookie = loginAndGetCookie();
        ResponseEntity<List> resp = rest.exchange(
                "http://localhost:" + port + "/spring/ai/loom/conversation/" + convId,
                HttpMethod.GET, new HttpEntity<>(jsonHeaders(cookie)), List.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        List<Map<String, Object>> body = resp.getBody();
        assertThat(body).isNotNull();

        List<Map<String, Object>> toolEntries = body.stream()
                .filter(e -> "TOOL_CALL".equals(e.get("messageType")))
                .toList();

        assertThat(toolEntries)
                .as("接口必须把 loom_tool_call_log 的记录注入响应 —— 否则历史回看看不到工具调用")
                .hasSize(1);

        Map<String, Object> e = toolEntries.get(0);
        assertThat(e.get("toolName")).isEqualTo("askUser");
        assertThat(e.get("durationMs")).isEqualTo(97817);
        assertThat(e.get("result")).asString().contains("面条");

        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) e.get("arguments");
        assertThat(args).as("askUser 的 optionsJson 必须可解析,否则卡片渲染不出选项")
                .containsKey("optionsJson");
        assertThat(String.valueOf(args.get("optionsJson"))).contains("面条").contains("米饭");
    }

    @Test
    @DisplayName("会话无工具调用 ⇒ 响应不含 TOOL_CALL 条目（防空转哨兵）")
    void conversationWithoutToolCallsHasNoToolEntries() {
        String convId = "conv-notool-" + UUID.randomUUID();
        jdbcTemplate.update(
                "MERGE INTO user_conversation (username, conversation_id) KEY (username, conversation_id) VALUES (?, ?)",
                USER, convId);

        String cookie = loginAndGetCookie();
        ResponseEntity<List> resp = rest.exchange(
                "http://localhost:" + port + "/spring/ai/loom/conversation/" + convId,
                HttpMethod.GET, new HttpEntity<>(jsonHeaders(cookie)), List.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        List<Map<String, Object>> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.stream().filter(e -> "TOOL_CALL".equals(e.get("messageType"))).toList())
                .as("空会话不应凭空产生 TOOL_CALL 条目")
                .isEmpty();
    }
}
