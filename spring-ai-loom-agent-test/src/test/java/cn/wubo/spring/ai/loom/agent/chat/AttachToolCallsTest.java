package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.model.ToolCallLog;
import cn.wubo.spring.ai.loom.agent.tool.IToolCallLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 历史会话注入工具调用的回归锁。
 *
 * <p><b>缺陷背景</b>：主聊天界面回看历史走 {@code GET /conversation/{id}}，
 * 该接口只返回 {@code SPRING_AI_CHAT_MEMORY} 的内容 —— 而这张表**没有 metadata 列**
 * （schema 只有 conversation_id / content / type / timestamp / sequence_id），
 * 工具调用信息完全不在其中。后果：用户回看历史时，askUser 卡片与子任务 chip
 * 完全消失，连"调过什么工具"都看不到。
 *
 * <p><b>修复思路</b>：仿 {@link ChatReasoningHistory#attachThinking} 的注入模式，
 * 从 {@code loom_tool_call_log} 读出工具调用，作为独立条目插入消息列表。
 */
@DisplayName("历史会话工具调用注入")
class AttachToolCallsTest {

    private static ToolCallLog log(String callId, String name, String args, String result,
                                   boolean isError, long durationMs, Instant ts) {
        return new ToolCallLog(
                null, "conv-1", "user-1", callId, name,
                args, result, isError, durationMs, ts);
    }

    /** 最小可用的假仓库 —— 只实现本测试需要的方法。 */
    private static IToolCallLogRepository repoOf(List<ToolCallLog> rows) {
        return new IToolCallLogRepository() {
            @Override
            public ToolCallLog save(ToolCallLog log) { throw new UnsupportedOperationException(); }

            @Override
            public List<ToolCallLog> findByConversationId(String conversationId) { return rows; }

            @Override
            public java.util.Optional<ToolCallLog> findByCallId(String conversationId, String toolCallId) {
                return rows.stream().filter(r -> toolCallId.equals(r.toolCallId())).findFirst();
            }
        };
    }

    @Test
    @DisplayName("工具调用作为独立条目插入，携带渲染所需的完整字段")
    void toolCallsBecomeEntries() {
        Instant t0 = Instant.parse("2026-10-06T10:00:00Z");
        List<Message> messages = List.of();   // 本用例只验注入，不依赖 chat memory

        List<Map<String, Object>> out = ToolCallHistory.inject(
                messages,
                repoOf(List.of(
                        log("call-1", "getCurrentTime", "{\"tz\":\"Asia/Shanghai\"}",
                                "当前时间：2026-10-06 18:41:52", false, 12, t0),
                        log("call-2", "askUser",
                                "{\"question\":\"午餐吃面条还是米饭？\",\"header\":\"午餐选择\"," +
                                        "\"optionsJson\":\"[{\\\"label\\\":\\\"面条\\\"},{\\\"label\\\":\\\"米饭\\\"}]\"," +
                                        "\"multiSelect\":false}",
                                "[用户已回答] 面条", false, 97817, t0.plusSeconds(60)))));

        assertThat(out)
                .as("两个工具调用应注入为两个独立条目")
                .hasSize(2);

        Map<String, Object> first = out.get(0);
        assertThat(first.get("messageType")).isEqualTo("TOOL_CALL");
        assertThat(first.get("toolName")).isEqualTo("getCurrentTime");
        assertThat(first.get("toolCallId")).isEqualTo("call-1");
        assertThat(first.get("result")).isEqualTo("当前时间：2026-10-06 18:41:52");
        assertThat(first.get("isError")).isEqualTo(false);
        assertThat(first.get("durationMs")).isEqualTo(12L);
        assertThat(first.get("arguments")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("askUser 的 optionsJson 必须完整保留（否则卡片渲染不出选项）")
    void askUserOptionsSurviveTruncation() {
        Instant t0 = Instant.parse("2026-10-06T10:00:00Z");
        // optionsJson 必须自身超过 MAX_FIELD_CHARS(256)，否则豁免失效时它也不会被截断，
        // 用例就测不出豁免逻辑。手写文案长度难控（前几版 91/199/232 都太短），
        // 故用循环程序化生成，并把长度作为断言显式锁住。
        StringBuilder ob = new StringBuilder("[");
        for (int i = 0; i < 12; i++) {
            if (i > 0) ob.append(",");
            ob.append("{\"label\":\"选项").append(i)
                    .append("\",\"description\":\"这是第").append(i)
                    .append("个选项的详细说明文字,用于撑长度确保超过截断阈值\"}");
        }
        ob.append("]");
        String opts = ob.toString();
        // 用超长的 question 把整个 argumentsJson 撑过 MAX_ARGS_CHARS(1024) 阈值，
        // 强制进入截断分支。
        String longQuestion = "请仔细考虑以下背景信息后再回答：".repeat(60);
        String argsJson = "{\"question\":\"" + longQuestion + "\",\"optionsJson\":\""
                + opts.replace("\"", "\\\"") + "\",\"multiSelect\":false}";

        assertThat(argsJson.length())
                .as("测试数据必须超过 MAX_ARGS_CHARS，否则测不到截断路径")
                .isGreaterThan(1_024);
        assertThat(opts.length())
                .as("optionsJson 必须超过 MAX_FIELD_CHARS，否则豁免失效时它也逃过截断，用例测不出豁免")
                .isGreaterThan(256);

        List<Map<String, Object>> out = ToolCallHistory.inject(
                List.of(),
                repoOf(List.of(log("call-ask", "askUser", argsJson,
                        "[用户已回答] 面条", false, 100, t0))));

        Map<String, Object> args = (Map<String, Object>) out.get(0).get("arguments");
        String optionsJson = String.valueOf(args.get("optionsJson"));

        assertThat(optionsJson)
                .as("askUser 的 optionsJson 必须完整 —— 截断会导致卡片选项丢失")
                .contains("选项0")
                .contains("选项11")
                .isEqualTo(opts);

        // 同一条里，超长的 question 应被截断（证明截断分支确实执行了，
        // 上面的完整保留不是因为整条都没被处理）
        assertThat(String.valueOf(args.get("question")).length())
                .as("非豁免字段应被截断 —— 证明截断分支真的执行过")
                .isLessThanOrEqualTo(256);
    }

    @Test
    @DisplayName("超长 result 被截断并标记 truncated")
    void oversizedResultIsTruncatedAndMarked() {
        Instant t0 = Instant.parse("2026-10-06T10:00:00Z");
        String huge = "X".repeat(50_000);

        List<Map<String, Object>> out = ToolCallHistory.inject(
                List.of(),
                repoOf(List.of(log("call-big", "sequentialthinking", "{}", huge, false, 5, t0))));

        Map<String, Object> e = out.get(0);
        String result = String.valueOf(e.get("result"));

        assertThat(result.length())
                .as("result 应被截断到 2KB 上限附近，防止历史接口响应膨胀")
                .isLessThan(3_000);
        assertThat(e.get("truncated"))
                .as("截断必须被标记，否则用户会以为工具只返回了这么多")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("无工具调用的会话 ⇒ 不注入任何条目（防空转哨兵）")
    void noToolCallsInjectsNothing() {
        List<Map<String, Object>> out = ToolCallHistory.inject(List.of(), repoOf(List.of()));
        assertThat(out)
                .as("空仓库不应凭空产生条目 —— 证明上面的断言不是因为条目恒定存在")
                .isEmpty();
    }
}
