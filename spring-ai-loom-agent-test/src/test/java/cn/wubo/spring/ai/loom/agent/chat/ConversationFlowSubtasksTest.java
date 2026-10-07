package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SUBTASK 事件接线回归锁。
 *
 * <p><b>缺陷背景</b>：{@code ConversationFlowService.loadSubtasks} 是死代码 ——
 * 它构造的 SUBTASK 事件含 {@code status / durationMs / result / error} 等完整字段，
 * 但 {@code flow()} 只调了 {@code loadScheduleFires}，从未调用它。
 * 后果：子任务在流水视图里只剩一行 {@code TOOL_CALL name=startSubTask}，
 * 丢失状态与耗时。实测 {@code loom_subtask_history} 有 2 条 COMPLETED 记录
 * 却产生 0 个 SUBTASK 事件。
 *
 * <p><b>变异自证</b>：把 {@code flow()} 里新增的 {@code loadSubtasks} 调用注释掉，
 * 本用例必须变红。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("ConversationFlow SUBTASK 事件接线（死代码回归锁）")
class ConversationFlowSubtasksTest {

    @Autowired
    private ConversationFlowService flowService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String CONV = "flow-subtask-test-" + UUID.randomUUID();
    private static final String USER = "flow-subtask-user";

    private void seedSubtask(String id, String status, long startedAt, long finishedAt, String result) {
        jdbcTemplate.update(
                "INSERT INTO loom_subtask_history " +
                        "(subtask_id, username, conversation_id, prompt, status, started_at, finished_at, error_message, result_text) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, USER, CONV, "测试子任务 prompt", status, startedAt, finishedAt, null, result);
    }

    private void seedConversation() {
        jdbcTemplate.update(
                "MERGE INTO user_conversation (username, conversation_id) KEY (username, conversation_id) VALUES (?, ?)",
                USER, CONV);
    }

    @Test
    @DisplayName("loom_subtask_history 有记录 ⇒ flow 必须产生 SUBTASK 事件")
    void subtaskHistoryProducesSubtaskEvent() {
        seedConversation();
        long t0 = System.currentTimeMillis();
        seedSubtask("st-" + UUID.randomUUID(), "COMPLETED", t0, t0 + 2572, "REST 是一种基于 HTTP 的架构风格");

        ConversationFlowService.FlowResult result = flowService.flow(
                CONV, USER, 0, 50, Set.of("SUBTASK"));

        List<ConversationFlowService.Event> subtaskEvents = result.events().stream()
                .filter(e -> "SUBTASK".equals(e.type()))
                .toList();

        assertThat(subtaskEvents)
                .as("loadSubtasks 必须被接线 —— 否则子任务信息在流水里完全丢失")
                .hasSize(1);

        Map<String, Object> data = subtaskEvents.get(0).data();
        assertThat(data)
                .as("SUBTASK 事件必须携带渲染 chip 所需的完整字段")
                .containsKeys("id", "prompt", "status", "startedAt", "finishedAt",
                        "durationMs", "error", "result");
        assertThat(data.get("status")).isEqualTo("COMPLETED");
        assertThat(data.get("durationMs")).isEqualTo(2572L);
        assertThat(String.valueOf(data.get("result"))).contains("REST");
    }

    @Test
    @DisplayName("无子任务记录的会话 ⇒ 不产生 SUBTASK 事件（防空转哨兵）")
    void noSubtaskHistoryProducesNoEvent() {
        String emptyConv = "flow-empty-" + UUID.randomUUID();
        jdbcTemplate.update(
                "MERGE INTO user_conversation (username, conversation_id) KEY (username, conversation_id) VALUES (?, ?)",
                USER, emptyConv);

        ConversationFlowService.FlowResult result = flowService.flow(
                emptyConv, USER, 0, 50, Set.of("SUBTASK"));

        assertThat(result.events().stream().filter(e -> "SUBTASK".equals(e.type())).toList())
                .as("空会话不应凭空产生 SUBTASK 事件 —— 证明上一条断言不是因为事件恒定存在")
                .isEmpty();
    }
}
