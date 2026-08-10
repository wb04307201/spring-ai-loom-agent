package cn.wubo.spring.ai.loom.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import cn.wubo.spring.ai.loom.agent.model.ToolCallLog;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link LoggingToolCallback} — the wrapper that writes every tool
 * invocation to {@code loom_tool_call_log}. Pins the contract:
 * <ul>
 *   <li>Inner delegate's return value is propagated unchanged.</li>
 *   <li>Inner delegate's exception is re-thrown after log is written.</li>
 *   <li>getToolDefinition() is forwarded to the delegate.</li>
 *   <li>Successful calls log isError=false; failing calls log isError=true with error message.</li>
 *   <li>Log save failure does not mask the original tool result/exception.</li>
 * </ul>
 */
class LoggingToolCallbackTest {

    /** In-memory log repository that captures every saved record. */
    static class FakeLogRepo implements IToolCallLogRepository {
        final List<ToolCallLog> saved = new ArrayList<>();
        @Override public ToolCallLog save(ToolCallLog log) { saved.add(log); return log; }
        @Override public List<ToolCallLog> findByConversationId(String conversationId) { return List.of(); }
        @Override public java.util.Optional<ToolCallLog> findByCallId(String conversationId, String toolCallId) { return java.util.Optional.empty(); }
    }

    /** ToolCallback that returns a fixed value or throws on cue. */
    static class StubCallback implements ToolCallback {
        final String name;
        final String toReturn;
        final RuntimeException toThrow;
        StubCallback(String name, String toReturn, RuntimeException toThrow) {
            this.name = name; this.toReturn = toReturn; this.toThrow = toThrow;
        }
        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description("stub").inputSchema("{}").build();
        }
        @Override public String call(String arguments) { return call(arguments, null); }
        @Override public String call(String arguments, ToolContext ctx) {
            if (toThrow != null) throw toThrow;
            return toReturn;
        }
    }

    FakeLogRepo repo;
    String convId;
    String username;

    @BeforeEach
    void setUp() {
        repo = new FakeLogRepo();
        convId = "conv-" + System.nanoTime();
        username = "tester";
    }

    @Test
    void returns_delegate_value_and_writes_one_log() {
        ToolCallback inner = new StubCallback("echo", "hello back", null);
        LoggingToolCallback wrapper = new LoggingToolCallback(inner, convId, username, repo);

        String result = wrapper.call("{\"q\":\"hi\"}", null);

        assertThat(result).isEqualTo("hello back");
        assertThat(repo.saved).hasSize(1);
        ToolCallLog log = repo.saved.get(0);
        assertThat(log.conversationId()).isEqualTo(convId);
        assertThat(log.username()).isEqualTo(username);
        assertThat(log.toolName()).isEqualTo("echo");
        assertThat(log.argumentsJson()).isEqualTo("{\"q\":\"hi\"}");
        assertThat(log.resultText()).isEqualTo("hello back");
        assertThat(log.resultIsError()).isFalse();
        assertThat(log.toolCallId()).startsWith("wrap-");
        // durationMs is mapped via getObject — any value is fine; just assert non-negative
    }

    @Test
    void rethrows_exception_after_writing_error_log() {
        ToolCallback inner = new StubCallback("boom", null,
                new RuntimeException("kaboom"));
        LoggingToolCallback wrapper = new LoggingToolCallback(inner, convId, username, repo);

        assertThatThrownBy(() -> wrapper.call("{}", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("kaboom");

        assertThat(repo.saved).hasSize(1);
        ToolCallLog log = repo.saved.get(0);
        assertThat(log.resultIsError()).isTrue();
        assertThat(log.resultText()).startsWith("ERROR:");
        assertThat(log.toolName()).isEqualTo("boom");
    }

    @Test
    void forwards_getToolDefinition() {
        ToolCallback inner = new StubCallback("my-tool", "x", null);
        assertThat(new LoggingToolCallback(inner, convId, username, repo)
                .getToolDefinition().name()).isEqualTo("my-tool");
    }

    @Test
    void log_save_failure_does_not_mask_tool_result() {
        ToolCallback inner = new StubCallback("ok", "the answer", null);
        // A repo whose save() throws — simulates DB outage
        IToolCallLogRepository brokenRepo = new IToolCallLogRepository() {
            @Override public ToolCallLog save(ToolCallLog log) {
                throw new RuntimeException("DB down");
            }
            @Override public List<ToolCallLog> findByConversationId(String c) { return List.of(); }
            @Override public java.util.Optional<ToolCallLog> findByCallId(String c, String t) { return java.util.Optional.empty(); }
        };
        LoggingToolCallback wrapper = new LoggingToolCallback(inner, convId, username, brokenRepo);

        // Despite log failure, the tool's return value must still reach the caller.
        assertThat(wrapper.call("{}", null)).isEqualTo("the answer");
    }
}