package cn.wubo.spring.ai.loom.agent.tool;

import cn.wubo.spring.ai.loom.agent.model.ToolCallLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LoggingToolCallback} 的 JSON 容错回归锁(2026-11-09)。
 *
 * <p><b>缺陷背景</b>:Spring AI 2.x 共享 JsonMapper 不可变,旧的"反射改 mapper 开
 * ALLOW_COMMENTS"从未在 2.x 生效 ⇒ qwen 工具调用 JSON 带 {@code //} 注释时
 * MethodToolCallback 解析炸(JsonParseException: Unexpected character ('/')),
 * 整条工具链断。修法:在本层(所有 embed + MCP ToolCallback 的唯一包装点)调用
 * {@link JsonLenient#sanitize(String)} 后再委托,注释永远到不了解析器。
 *
 * <p><b>变异方向</b>:删掉 LoggingToolCallback 里的 sanitize 调用 ⇒
 * capture 到的 arguments 仍是原始带注释文本 ⇒ 两用例红。
 */
@DisplayName("LoggingToolCallback 委托前剥注释(qwen 带注释工具参数容错)")
class LoggingToolCallbackJsonSanitizeTest {

    /** 记录收到的 arguments 原样,充当 MethodToolCallback 替身。 */
    private static final class CapturingToolCallback implements ToolCallback {
        final List<String> received = new ArrayList<>();
        @Override public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder().name("dummy").description("d").inputSchema("{}").build();
        }
        @Override public String call(String arguments) { received.add(arguments); return "ok"; }
        @Override public String call(String arguments, ToolContext toolContext) { received.add(arguments); return "ok"; }
    }

    private static final class RecordingRepository implements IToolCallLogRepository {
        final List<ToolCallLog> saved = new ArrayList<>();
        @Override public ToolCallLog save(ToolCallLog log) { saved.add(log); return log; }
        @Override public List<ToolCallLog> findByConversationId(String conversationId) { return saved; }
        @Override public java.util.Optional<ToolCallLog> findByCallId(String conversationId, String toolCallId) {
            return java.util.Optional.empty();
        }
    }

    private CapturingToolCallback delegate;
    private RecordingRepository repo;
    private LoggingToolCallback wrapped;

    @BeforeEach
    void setUp() {
        delegate = new CapturingToolCallback();
        repo = new RecordingRepository();
        wrapped = new LoggingToolCallback(delegate, "conv-1", "user-1", repo);
    }

    @Test
    @DisplayName("带 // 注释的 arguments 被剥净后才到 delegate")
    void lineCommentStrippedBeforeDelegate() {
        String raw = "{\"path\": \"a.txt\", // qwen note\n \"head\": 5}";
        wrapped.call(raw, new ToolContext(Map.of()));

        assertThat(delegate.received).hasSize(1);
        assertThat(delegate.received.get(0))
                .as("delegate(→MethodToolCallback→Spring AI 严格 JSON 解析)必须收到无注释文本")
                .doesNotContain("qwen note")
                .doesNotContain("//")
                .contains("\"path\": \"a.txt\"")
                .contains("\"head\": 5");
        // 审计日志记录原始入参(审计保真:用户/LLM 实际发了什么)
        assertThat(repo.saved).hasSize(1);
        assertThat(repo.saved.get(0).argumentsJson()).contains("qwen note");
    }

    @Test
    @DisplayName("干净 JSON 不受影响;字符串内 URL 的 // 不被误剥")
    void cleanJsonAndUrlsUntouched() {
        String clean = "{\"url\":\"http://a/b\"}";
        wrapped.call(clean, new ToolContext(Map.of()));
        assertThat(delegate.received.get(0)).isEqualTo(clean);
    }

    @Test
    @DisplayName("null arguments 透传(null 语义不被 sanitize 改变)")
    void nullArgumentsPassThrough() {
        wrapped.call((String) null, new ToolContext(Map.of()));
        assertThat(delegate.received).containsExactly((String) null);
    }
}
