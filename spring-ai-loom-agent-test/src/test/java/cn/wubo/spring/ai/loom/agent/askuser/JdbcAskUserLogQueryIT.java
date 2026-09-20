package cn.wubo.spring.ai.loom.agent.askuser;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §2 真 DB 往返:loom_tool_call_log 插入 askUser 行 → recent() 读回并正确解析。
 * 走 LoomAgentTestApplication 全上下文(清库 IT gate 的一员)。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("askUser 日志查询 IT")
class JdbcAskUserLogQueryIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IAskUserLogQuery askUserLogQuery;

    private String conv;

    @BeforeEach
    void seed() {
        conv = "conv-asklog-" + System.nanoTime();
        String user = "u-" + System.nanoTime();
        // 一答一超时两行 —— result_text 用带引号 JSON string literal 形态,
        // 模拟生产 LoggingToolCallback 落库形态(Fix round 1:MethodToolCallback
        // 对 @Tool String 返回值先做 JSON 序列化)
        insert(user, conv + "-1",
                "{\"question\":\"用哪种数据库?\",\"header\":\"选型\"}",
                "\"[用户已回答] MySQL\"", 42_000L, Instant.now().minusSeconds(120));
        insert(user, conv + "-2",
                "{\"question\":\"部署到哪个环境?\"}",
                "\"[用户未作答] 用户未在 5 分钟内作答。请基于现有信息自行合理决策并继续,或改用其他方式推进。\"",
                300_000L, Instant.now().minusSeconds(60));
        // 干扰行:非 askUser 工具,不得混入结果
        jdbcTemplate.update("insert into loom_tool_call_log (conversation_id, username, "
                        + "tool_call_id, tool_name, arguments_json, result_text, result_is_error, "
                        + "duration_ms, created_at) values (?,?,?,?,?,?,?,?,?)",
                conv + "-3", user, "call-noise", "getCurrentTime", "{}", "2026-09-08", false, 5L,
                Timestamp.from(Instant.now()));
        this.username = user;
    }

    private String username;

    private void insert(String user, String conversationId, String args, String result,
                        long durationMs, Instant createdAt) {
        jdbcTemplate.update("insert into loom_tool_call_log (conversation_id, username, "
                        + "tool_call_id, tool_name, arguments_json, result_text, result_is_error, "
                        + "duration_ms, created_at) values (?,?,?,?,?,?,?,?,?)",
                conversationId, user, "call-" + System.nanoTime(), "askUser", args, result,
                false, durationMs, Timestamp.from(createdAt));
    }

    @Test
    void recentReturnsParsedRowsNewestFirstFilteredByUser() {
        List<AskUserLogRecord> rows = askUserLogQuery.recent(50, 0, username);
        assertThat(rows).hasSize(2);
        // created_at desc:超时行(60s 前)在已答行(120s 前)之前
        assertThat(rows.get(0).status()).isEqualTo("TIMEOUT");
        assertThat(rows.get(0).question()).isEqualTo("部署到哪个环境?");
        assertThat(rows.get(0).header()).isNull();
        assertThat(rows.get(0).durationMs()).isEqualTo(300_000L);
        assertThat(rows.get(1).status()).isEqualTo("ANSWERED");
        assertThat(rows.get(1).question()).isEqualTo("用哪种数据库?");
        assertThat(rows.get(1).header()).isEqualTo("选型");
        assertThat(rows.get(1).answerText()).isEqualTo("MySQL");
        // 干扰行(getCurrentTime)不在结果里
        assertThat(rows).allMatch(r -> r.conversationId().startsWith(conv));
    }

    @Test
    void limitClampedToOneMinimum() {
        List<AskUserLogRecord> rows = askUserLogQuery.recent(0, 0, username);
        assertThat(rows).hasSize(1); // 钳制到 1
    }

    @Test
    void offsetSkipsFirstN() {
        // offset=1 → 跳过最新的 1 行(TIMEOUT),返回剩余 1 行(ANSWERED)
        List<AskUserLogRecord> rows = askUserLogQuery.recent(50, 1, username);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo("ANSWERED");
        assertThat(rows.get(0).question()).isEqualTo("用哪种数据库?");
    }

    @Test
    void offsetBeyondTotalReturnsEmpty() {
        List<AskUserLogRecord> rows = askUserLogQuery.recent(50, 999, username);
        assertThat(rows).isEmpty();
    }

    @Test
    void negativeOffsetClampedToZero() {
        // 负数 offset 钳制为 0,行为等同 offset=0
        List<AskUserLogRecord> rows = askUserLogQuery.recent(50, -5, username);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).status()).isEqualTo("TIMEOUT");
    }
}
