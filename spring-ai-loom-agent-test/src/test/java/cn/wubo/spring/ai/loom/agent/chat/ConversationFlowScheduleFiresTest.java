package cn.wubo.spring.ai.loom.agent.chat;

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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCHEDULE_FIRE 审计渲染回归锁（2026-11-08 a+b+c' 修复）。
 *
 * <p><b>缺陷背景</b>：{@code loadScheduleFires} 用 INNER JOIN
 * {@code loom_scheduled_task ⋈ loom_schedule_execution} 构造 SCHEDULE_FIRE 事件，
 * 而所有取消路径（LLM 工具 / REST / cancel-all）都必须删声明行（防重启复活）。
 * 后果：<b>任务一旦取消，执行审计行成孤儿，SCHEDULE_FIRE 永远渲染不出来</b>，
 * {@code loom_subtask_history} 的行退化成无上下文裸 SUBTASK（实测
 * e2e-sched2-1794093614 会话 cancel-all 后 9 条定时触发子任务全部裸渲染）。
 *
 * <p><b>修复语义</b>：
 * <ol>
 * <li>(a) 审计渲染只依赖审计表：execution 直查（task_name LIKE 前缀），
 * 声明表降级为 LEFT JOIN 补 prompt；</li>
 * <li>(c') execution 行新增 {@code subtask_id} 列 → 配对精确化（不再依赖 ±5s
 * 时间窗猜测）；subtask_id 为 NULL 的历史行回退时间窗配对。</li>
 * </ol>
 *
 * <p><b>变异自证</b>：把 loadScheduleFires 的 LEFT JOIN 改回 INNER JOIN，
 * 用例 1 必须变红；删掉 subtask_id 精确配对分支，用例 2 必须变红。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("ConversationFlow SCHEDULE_FIRE 审计渲染（取消后仍可渲染 + 精确配对）")
class ConversationFlowScheduleFiresTest {

    @Autowired
    private ConversationFlowService flowService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String USER = "flow-fire-user";

    private String conv;
    private String taskName;

    @BeforeEach
    void setUp() {
        conv = "flow-fire-" + UUID.randomUUID();
        taskName = "loom-sched-" + USER + "-" + conv + "-daily";
        jdbcTemplate.update(
                "MERGE INTO user_conversation (username, conversation_id) KEY (username, conversation_id) VALUES (?, ?)",
                USER, conv);
    }

    /**
     * 直接以 SQL 播种 execution 审计行 —— 不经声明表，模拟「任务已取消、
     * 声明行已删、审计行保留」的目标状态。subtask_id 列由本次修复引入。
     */
    private void seedExecution(String subtaskId, Instant fireTime, boolean success) {
        jdbcTemplate.update(
                "INSERT INTO loom_schedule_execution " +
                        "(task_name, fire_time, duration_ms, success, error_message, fired_by, subtask_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                taskName, Timestamp.from(fireTime), 1234L, success,
                success ? null : "boom", "SCHEDULER", subtaskId);
    }

    private void seedSubtask(String id, long startedAt, String prompt, String result) {
        jdbcTemplate.update(
                "INSERT INTO loom_subtask_history " +
                        "(subtask_id, username, conversation_id, prompt, status, started_at, finished_at, error_message, result_text) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, USER, conv, prompt, "COMPLETED", startedAt, startedAt + 2000, null, result);
    }

    @Test
    @DisplayName("声明行已删（取消后）⇒ SCHEDULE_FIRE 仍从 execution 审计行渲染")
    void firesRenderAfterDeclarationRowDeleted() {
        Instant fire = Instant.now();
        String sid = "st-" + UUID.randomUUID();
        seedExecution(sid, fire, true);
        seedSubtask(sid, fire.toEpochMilli(), "每日报告 prompt", "报告正文");
        // 注意：故意不 INSERT loom_scheduled_task —— 模拟取消后的真实状态

        ConversationFlowService.FlowResult result = flowService.flow(
                conv, USER, 0, 50, Set.of("SCHEDULE_FIRE", "SUBTASK"));

        List<ConversationFlowService.Event> fires = result.events().stream()
                .filter(e -> "SCHEDULE_FIRE".equals(e.type()))
                .toList();
        assertThat(fires)
                .as("审计渲染不得依赖活体声明行 —— INNER JOIN 会让取消后的执行历史从流水里消失")
                .hasSize(1);

        Map<String, Object> data = fires.get(0).data();
        assertThat(data.get("taskName")).isEqualTo(taskName);
        assertThat(data.get("subtaskId")).isEqualTo(sid);
        assertThat(String.valueOf(data.get("subtaskResult"))).contains("报告正文");
        // prompt：声明行没了 → 回退用配对 subtask 行的 prompt，渲染不缺上下文
        assertThat(String.valueOf(data.get("prompt"))).contains("每日报告");

        // 配上的 subtask 必须被 consumedBySchedule 吃掉，不得重复渲染为裸 SUBTASK
        assertThat(result.events().stream().filter(e -> "SUBTASK".equals(e.type())).toList())
                .as("schedule 触发的子任务由 SCHEDULE_FIRE 统一承载")
                .isEmpty();
    }

    @Test
    @DisplayName("subtask_id 精确配对：超出 ±5s 时间窗也能配上")
    void pairsBySubtaskIdOutsideTimeWindow() {
        Instant fire = Instant.now().minusSeconds(120);
        String sid = "st-" + UUID.randomUUID();
        seedExecution(sid, fire, true);
        // 子任务 started_at 比 fire_time 晚 60 秒 —— 旧 ±5s 窗口配不上，
        // 精确 subtask_id 配对必须配上
        seedSubtask(sid, fire.plusSeconds(60).toEpochMilli(), "p", "配对结果R");

        ConversationFlowService.FlowResult result = flowService.flow(
                conv, USER, 0, 50, Set.of("SCHEDULE_FIRE", "SUBTASK"));

        List<ConversationFlowService.Event> fires = result.events().stream()
                .filter(e -> "SCHEDULE_FIRE".equals(e.type()))
                .toList();
        assertThat(fires).hasSize(1);
        assertThat(fires.get(0).data().get("subtaskId"))
                .as("subtask_id 精确配对不受时间窗限制")
                .isEqualTo(sid);
        // 防空转哨兵：subtaskStatus/subtaskResult 只能来自「配对成功的 subtask 行」——
        // 若精确配对分支被禁用（matched==null），这两个字段缺失，本断言必须变红。
        // （只断言 subtaskId 会被 else 分支的 id 回填骗过 —— 变异测试实证过）
        assertThat(fires.get(0).data())
                .as("精确配对必须真的把 subtask 行合并进事件")
                .containsEntry("subtaskStatus", "COMPLETED");
        assertThat(String.valueOf(fires.get(0).data().get("subtaskResult")))
                .contains("配对结果R");
        assertThat(result.events().stream().filter(e -> "SUBTASK".equals(e.type())).toList())
                .isEmpty();
    }

    @Test
    @DisplayName("shortName 精确剥离命名空间前缀：任务名自身含连字符不得截半")
    void shortNamePreservesDashesInTaskName() {
        // 命名空间 = loom-sched-{user}-{conv}-，前缀由方法参数精确拼出；
        // 旧实现 lastIndexOf('-') 会把 "audit-verify" 截成 "verify"
        //（admin 流水页实测「⏰ verify 触发」），与真叫 verify 的任务无法区分。
        // 本用例的 user/conv 自身也含连字符（flow-fire-user / flow-fire-uuid），
        // 同时锁死「前缀段含 dash 也不影响剥离」的健壮性。
        String dashedTask = "loom-sched-" + USER + "-" + conv + "-audit-verify";
        Instant fire = Instant.now();
        String sid = "st-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO loom_schedule_execution " +
                        "(task_name, fire_time, duration_ms, success, error_message, fired_by, subtask_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                dashedTask, Timestamp.from(fire), 100L, true, null, "SCHEDULER", sid);

        ConversationFlowService.FlowResult result = flowService.flow(
                conv, USER, 0, 50, Set.of("SCHEDULE_FIRE"));

        List<ConversationFlowService.Event> fires = result.events().stream()
                .filter(e -> "SCHEDULE_FIRE".equals(e.type()))
                .toList();
        assertThat(fires).hasSize(1);
        assertThat(fires.get(0).data().get("shortName"))
                .as("shortName 必须是完整的用户任务名（含连字符），不是最后一段")
                .isEqualTo("audit-verify");
    }

    @Test
    @DisplayName("subtask_id 为 NULL 的历史行 ⇒ 回退 ±5s 时间窗配对")
    void legacyNullSubtaskIdFallsBackToTimeWindow() {
        Instant fire = Instant.now();
        String sid = "st-" + UUID.randomUUID();
        seedExecution(null, fire, true); // 旧数据：无 subtask_id
        seedSubtask(sid, fire.toEpochMilli() + 100, "p", "legacy 结果");

        ConversationFlowService.FlowResult result = flowService.flow(
                conv, USER, 0, 50, Set.of("SCHEDULE_FIRE", "SUBTASK"));

        List<ConversationFlowService.Event> fires = result.events().stream()
                .filter(e -> "SCHEDULE_FIRE".equals(e.type()))
                .toList();
        assertThat(fires).hasSize(1);
        assertThat(fires.get(0).data().get("subtaskId"))
                .as("NULL subtask_id 回退时间窗，±5s 内的子任务照常配对")
                .isEqualTo(sid);
    }
}
