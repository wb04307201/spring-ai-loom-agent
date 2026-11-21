package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.model.ToolCallLog;
import cn.wubo.spring.ai.loom.agent.tool.ILoomChatSteeringRepository;
import cn.wubo.spring.ai.loom.agent.tool.IToolCallLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 10 / A7(G7)：admin 流水 B2 时间戳重写的 steering 豁免回归锁。
 *
 * <p><b>缺陷背景</b>：{@code ConversationFlowService} 的 B2 修复把<strong>所有</strong>
 * USER 事件 ts 重写为 {@code conversation.createdAt + i*1ms}（原意：memory USER 行
 * 时间戳是流式处理"结束"时刻，会晚于本轮 tool_call 造成倒序）。但 steering 插话的
 * USER 行落库时刻就是真实注入时刻（会话中段），一律拉回 createdAt ⇒ 插话排到本轮
 * 所有工具调用之前，<strong>时间线彻底倒序</strong>（A7）。
 *
 * <p><b>修复语义</b>：B2 重写前按 {@code loom_chat_steering} 审计表豁免 ——
 * content 逐字相等 + 事件 ts 与 injected_at 全局就近贪心配对（双向 consume-once，
 * 仿 {@code ChatReasoningHistory.pair} 先例），命中行保留真实 ts 并附 {@code steering=true}；
 * 未命中行（turn-initial）沿用 createdAt + i*1ms 锚定（且 +1ms 只被非豁免行消耗，
 * 无 steering 时与改造前逐 ms 一致）。S4：匹配只看 content+时刻，前序行是 TOOL
 * （内层 drain）还是 ASSISTANT（finalize 续跑）两类注入场景统一成立。
 *
 * <p><b>变异自证</b>：去掉豁免（恢复全量重写）⇒ 用例 1/2/3/4/6 全红；
 * 去掉 consume-once ⇒ repeated_same_text 用例红；去掉容差 ⇒ 同文本 turn-initial
 * 误配用例红。
 *
 * <p>时区（R22 修订）：chat_memory.timestamp 与 loom_chat_steering.injected_at 都是
 * TIMESTAMP 无时区、同 JVM {@code Timestamp.from ↔ getTimestamp().toInstant()} 往返一致，
 * 直接比 Instant 无需归一（与 loom_tool_call_log 的 WITH TIME ZONE 不同源）。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("Task 10:admin 流水 B2 豁免 steering USER(A7/G7/S4)")
class ConversationFlowSteeringTimelineTest {

    @Autowired
    private ConversationFlowService flowService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ILoomChatSteeringRepository steeringRepository;

    @Autowired
    private IToolCallLogRepository toolCallLogRepository;

    private static final String USER = "steer-flow-user";

    /** 固定基准（全整微秒，避免 H2 TIMESTAMP(6) 截断引入的等值断言抖动）。 */
    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");

    private String conv;

    private String newConv() {
        conv = "steer-flow-" + UUID.randomUUID();
        // created_at 远早于任何行 ts → B2 锚定必然触发（用例靠这点检测倒序）
        jdbcTemplate.update(
                "MERGE INTO user_conversation (username, conversation_id, created_at, updated_at) "
                        + "KEY (username, conversation_id) VALUES (?, ?, ?, ?)",
                USER, conv, Timestamp.from(T0), Timestamp.from(T0.plusSeconds(300)));
        return conv;
    }

    /** content 存纯文本（JdbcChatMemoryRepository 2.0.2 即写 message.getText()；flow 端 readContentNode 解析失败自动回退 raw）。 */
    private void seedMemory(String type, String content, Instant ts, int seq) {
        jdbcTemplate.update(
                "INSERT INTO spring_ai_chat_memory (conversation_id, content, type, timestamp, sequence_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                conv, content, type, Timestamp.from(ts), seq);
    }

    private void seedToolCall(String callId, String name, Instant createdAt) {
        toolCallLogRepository.save(new ToolCallLog(null, conv, USER, callId, name,
                "{}", "ok", false, 5L, createdAt));
    }

    private void seedSteering(String text, Instant injectedAt, int seq) {
        steeringRepository.record(conv, USER, text, injectedAt, seq);
    }

    private List<ConversationFlowService.Event> flow() {
        return flowService.flow(conv, USER, 0, 100,
                Set.of("USER", "ASSISTANT", "TOOL_CALL")).events();
    }

    private static ConversationFlowService.Event userEvent(List<ConversationFlowService.Event> events, String content) {
        return events.stream()
                .filter(e -> "USER".equals(e.type()) && content.equals(e.data().get("content")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 USER 事件: " + content));
    }

    private static int indexOf(List<ConversationFlowService.Event> events, ConversationFlowService.Event e) {
        return events.indexOf(e);
    }

    // ==================== 用例 1：steering USER 保留注入时刻 ====================

    @Test
    @DisplayName("steering USER 事件 ts = 真实注入时刻，不被拉回 createdAt（内层 drain：前序 TOOL 行）")
    void steering_user_keeps_injection_ts_not_createdAt() {
        newConv();
        seedMemory("USER", "原始任务", T0.plusSeconds(60), 1);     // turn-initial，将被锚定
        seedToolCall("tc-1", "askUser", T0.plusSeconds(90));        // 本轮工具调用
        seedMemory("USER", "改用蓝绿", T0.plusSeconds(95), 2);     // 插话（tool_result 就位后注入）
        seedSteering("改用蓝绿", T0.plusSeconds(95).plusMillis(50), 1);
        seedMemory("ASSISTANT", "已切换", T0.plusSeconds(120), 3);

        List<ConversationFlowService.Event> events = flow();

        ConversationFlowService.Event steer = userEvent(events, "改用蓝绿");
        assertThat(steer.ts())
                .as("A7：插话保留真实落库时刻（≈injectedAt），不得被 B2 拉回 createdAt")
                .isEqualTo(T0.plusSeconds(95));
        assertThat(steer.ts()).isNotEqualTo(T0).isNotEqualTo(T0.plusMillis(1));
        assertThat(steer.data())
                .as("豁免行附 steering=true 徽章标记（Task 16 消费）")
                .containsEntry("steering", true);
    }

    // ==================== 用例 2：turn-initial 锚定不回归 ====================

    @Test
    @DisplayName("非 steering 的 turn-initial USER 仍按 createdAt + i*1ms 锚定（原 B2 不回归）")
    void turn_initial_user_still_anchored() {
        newConv();
        seedMemory("USER", "第一问", T0.plusSeconds(60), 1);
        seedMemory("ASSISTANT", "第一答", T0.plusSeconds(70), 2);
        seedMemory("USER", "第二问", T0.plusSeconds(200), 3);
        seedMemory("ASSISTANT", "第二答", T0.plusSeconds(210), 4);
        // 零审计行 → 全部走原 B2 路径

        List<ConversationFlowService.Event> events = flow();

        assertThat(userEvent(events, "第一问").ts()).isEqualTo(T0);
        assertThat(userEvent(events, "第二问").ts()).isEqualTo(T0.plusMillis(1));
        assertThat(userEvent(events, "第一问").data())
                .as("无审计行的 turn-initial 不得带 steering 标记")
                .doesNotContainKey("steering");
        assertThat(userEvent(events, "第二问").data()).doesNotContainKey("steering");
    }

    // ==================== 用例 3：S4 finalize 续跑注入（前序是 ASSISTANT） ====================

    @Test
    @DisplayName("S4：finalize 续跑注入（前序行是 ASSISTANT）匹配同样成立、不倒序")
    void finalize_continuation_injection_matched() {
        newConv();
        seedMemory("USER", "写个报告", T0.plusSeconds(60), 1);
        seedMemory("ASSISTANT", "迭代一文本", T0.plusSeconds(120), 2);   // 前序是 ASSISTANT
        seedMemory("USER", "第三节删掉", T0.plusSeconds(125), 3);        // 续跑残留注入
        seedSteering("第三节删掉", T0.plusSeconds(125).plusMillis(30), 1);
        seedMemory("ASSISTANT", "完成", T0.plusSeconds(200), 4);

        List<ConversationFlowService.Event> events = flow();

        ConversationFlowService.Event a1 = events.stream()
                .filter(e -> "ASSISTANT".equals(e.type()) && "迭代一文本".equals(e.data().get("content")))
                .findFirst().orElseThrow();
        ConversationFlowService.Event steer = userEvent(events, "第三节删掉");
        ConversationFlowService.Event a2 = events.stream()
                .filter(e -> "ASSISTANT".equals(e.type()) && "完成".equals(e.data().get("content")))
                .findFirst().orElseThrow();

        assertThat(steer.ts()).isEqualTo(T0.plusSeconds(125));
        assertThat(indexOf(events, steer))
                .as("插话排在前序 ASSISTANT 之后")
                .isGreaterThan(indexOf(events, a1));
        assertThat(indexOf(events, steer))
                .as("插话排在后续 ASSISTANT 之前（末轮 ASSISTANT 锚 updatedAt 仍在其后）")
                .isLessThan(indexOf(events, a2));
    }

    // ==================== 用例 4：整体时序无倒序 ====================

    @Test
    @DisplayName("插话 USER 排在其前序 ASSISTANT/TOOL_CALL 之后、其后续 TOOL_CALL 之前，全表升序")
    void timeline_sorted_no_inversion() {
        newConv();
        seedMemory("USER", "原始任务", T0.plusSeconds(60), 1);
        seedToolCall("tc-a", "readTextFile", T0.plusSeconds(80));
        seedMemory("ASSISTANT", "迭代一文本", T0.plusSeconds(90), 2);
        seedToolCall("tc-b", "askUser", T0.plusSeconds(95));
        seedMemory("USER", "方向A", T0.plusSeconds(96), 3);
        seedSteering("方向A", T0.plusSeconds(96).plusMillis(20), 1);
        seedToolCall("tc-c", "writeFile", T0.plusSeconds(110));
        seedMemory("ASSISTANT", "完成", T0.plusSeconds(130), 4);

        List<ConversationFlowService.Event> events = flow();

        // 全局不变式：ts 非降序
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).ts())
                    .as("事件流必须按 ts 升序（idx=%d）", i)
                    .isAfterOrEqualTo(events.get(i - 1).ts());
        }

        ConversationFlowService.Event steer = userEvent(events, "方向A");
        ConversationFlowService.Event tcB = events.stream()
                .filter(e -> "TOOL_CALL".equals(e.type()) && "tc-b".equals(e.data().get("id")))
                .findFirst().orElseThrow();
        ConversationFlowService.Event tcC = events.stream()
                .filter(e -> "TOOL_CALL".equals(e.type()) && "tc-c".equals(e.data().get("id")))
                .findFirst().orElseThrow();
        assertThat(indexOf(events, steer)).isGreaterThan(indexOf(events, tcB));
        assertThat(indexOf(events, steer)).isLessThan(indexOf(events, tcC));
    }

    // ==================== 用例 5：同文本重复插话 就近 + consume-once ====================

    @Test
    @DisplayName("同文本重复 steering：两条各自就近配对、各自豁免（consume-once 不误伤第二条）")
    void repeated_same_text_steering_both_exempted() {
        newConv();
        seedMemory("USER", "原始任务", T0.plusSeconds(10), 1);
        seedMemory("USER", "继续", T0.plusSeconds(60), 2);   // 第一次插话
        seedSteering("继续", T0.plusSeconds(60).plusMillis(200), 1);
        seedMemory("ASSISTANT", "迭代一", T0.plusSeconds(70), 3);
        seedMemory("USER", "继续", T0.plusSeconds(120), 4);  // 第二次插话（同文本）
        seedSteering("继续", T0.plusSeconds(120).plusMillis(100), 2);
        seedMemory("ASSISTANT", "完成", T0.plusSeconds(150), 5);

        List<ConversationFlowService.Event> events = flow();

        List<ConversationFlowService.Event> steers = events.stream()
                .filter(e -> "USER".equals(e.type()) && "继续".equals(e.data().get("content")))
                .toList();
        assertThat(steers).hasSize(2);
        assertThat(steers.get(0).ts())
                .as("第一次插话按就近配到自己的审计行，保留真实 ts")
                .isEqualTo(T0.plusSeconds(60));
        assertThat(steers.get(1).ts())
                .as("第二次插话不得因 consume 缺失被拉回 createdAt+1ms")
                .isEqualTo(T0.plusSeconds(120));
        assertThat(steers).allSatisfy(e -> assertThat(e.data()).containsEntry("steering", true));
    }

    @Test
    @DisplayName("同文本双事件单审计行：仅时刻最近者豁免，另一条照旧锚定（consume-once 锁）")
    void single_audit_row_matches_only_nearest_event() {
        newConv();
        seedMemory("USER", "原始任务", T0.plusSeconds(10), 1);
        seedMemory("USER", "停", T0.plusSeconds(90), 2);     // 距审计行 400ms —— 应豁免
        seedMemory("USER", "停", T0.plusSeconds(95), 3);     // 距审计行 4.6s —— 行已被消耗，应锚定
        seedSteering("停", T0.plusSeconds(90).plusMillis(400), 1);
        seedMemory("ASSISTANT", "停了", T0.plusSeconds(120), 4);

        List<ConversationFlowService.Event> events = flow();

        List<ConversationFlowService.Event> stopEvents = events.stream()
                .filter(e -> "USER".equals(e.type()) && "停".equals(e.data().get("content")))
                .toList();
        assertThat(stopEvents).hasSize(2);
        // 排序后：锚定行（createdAt+1ms）在前，豁免行（真实 ts T0+90s）在后
        ConversationFlowService.Event anchored = stopEvents.stream()
                .filter(e -> !Boolean.TRUE.equals(e.data().get("steering")))
                .findFirst().orElseThrow();
        ConversationFlowService.Event exempted = stopEvents.stream()
                .filter(e -> Boolean.TRUE.equals(e.data().get("steering")))
                .findFirst().orElseThrow();
        assertThat(anchored.ts())
                .as("较远者不得复用同一审计行（consume-once），仍走 createdAt + i*1ms 锚定")
                .isEqualTo(T0.plusMillis(1));
        assertThat(exempted.ts()).as("更近者豁免，保留真实 ts").isEqualTo(T0.plusSeconds(90));
    }

    // ==================== 用例 6：容差防同文本 turn-initial 误配 ====================

    @Test
    @DisplayName("turn-initial 与 steering 同文本但时刻超容差：turn-initial 不误豁免")
    void same_text_turn_initial_not_falsely_exempted() {
        newConv();
        // turn-initial USER 恰好与 30s 后的插话同文本：|Δ|=30s > 10s 容差 → 不得配对
        seedMemory("USER", "继续", T0.plusSeconds(60), 1);
        seedMemory("ASSISTANT", "迭代一", T0.plusSeconds(70), 2);
        seedMemory("USER", "继续", T0.plusSeconds(90), 3);   // 真插话
        seedSteering("继续", T0.plusSeconds(90).plusMillis(10), 1);
        seedMemory("ASSISTANT", "迭代二", T0.plusSeconds(100), 4);

        List<ConversationFlowService.Event> events = flow();

        List<ConversationFlowService.Event> sameText = events.stream()
                .filter(e -> "USER".equals(e.type()) && "继续".equals(e.data().get("content")))
                .toList();
        assertThat(sameText).hasSize(2);
        assertThat(sameText.get(0).ts())
                .as("turn-initial（时刻远）仍被锚定，不被 10s 容差外的审计行误伤")
                .isEqualTo(T0);
        assertThat(sameText.get(0).data()).doesNotContainKey("steering");
        assertThat(sameText.get(1).ts())
                .as("真插话（毫秒级近邻）豁免保留真实 ts")
                .isEqualTo(T0.plusSeconds(90));
        assertThat(sameText.get(1).data()).containsEntry("steering", true);
    }

    // ==================== 哨兵：无 steering 会话零影响（A1） ====================

    @Test
    @DisplayName("零审计行会话：B2 行为与改造前逐 ms 一致（防空转哨兵，配合用例 2）")
    void no_steering_rows_legacy_behavior() {
        newConv();
        seedMemory("USER", "问一", T0.plusSeconds(60), 1);
        seedToolCall("tc-1", "timeTool", T0.plusSeconds(30));   // 制造 tool ts < USER ts 的旧场景
        seedMemory("ASSISTANT", "答一", T0.plusSeconds(90), 2);

        List<ConversationFlowService.Event> events = flow();

        // 旧 B2 语义：USER 锚 createdAt（即便 tool 行在前），ASSISTANT 末行锚 updatedAt
        assertThat(userEvent(events, "问一").ts()).isEqualTo(T0);
        ConversationFlowService.Event a = events.stream()
                .filter(e -> "ASSISTANT".equals(e.type())).findFirst().orElseThrow();
        assertThat(a.ts()).as("最后一条 ASSISTANT 仍锚 updatedAt（不动）").isEqualTo(T0.plusSeconds(300));
        assertThat(userEvent(events, "问一").data()).doesNotContainKey("steering");
    }
}
