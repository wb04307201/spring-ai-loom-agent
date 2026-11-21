package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.token.ChatUsageRecordingAdvisor;
import cn.wubo.spring.ai.loom.agent.token.ChatUsageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * S3(usage 逐轮落库,无 UsageAccumulator 双计)回归锁(spec 2026-11-21 Task 6)。
 *
 * <p><b>机制(已按 spring-ai-client-chat 2.0.2 sources 核实)</b>:
 * {@code ToolCallingAdvisor} 每个内层模型轮经
 * {@code callAdvisorChain.copy(this).nextCall/nextStream(processedChatClientRequest)}
 * (ToolCallingAdvisor:167/293)重入 —— {@code copy(this)} 返回的是链上本 advisor
 * <b>之后</b>的全部 advisor。{@link ChatUsageRecordingAdvisor}(order=100)数值大于
 * loop advisor(order=HIGHEST+300,负数)→ 位于 loop <b>下游</b> ⇒ 每轮模型调用都过它,
 * 每轮聚合响应各落 1 行 usage。Task 7 的续跑 re-entry 同走 copy,逐外层迭代各 1 行的
 * 机制由此天然成立。
 *
 * <p><b>双计风险与排除</b>:{@code UsageAccumulator} 把跨轮累计和戳进响应
 * ({@code applyAccumulatedUsage}/{@code applyPreviousAccumulatedUsageToChunk}),但戳的
 * 是 loop advisor <b>上游侧</b>的响应;usage advisor 在下游聚合的是<b>该轮原始</b>
 * ChatResponse(其 metadata usage 即本轮 provider 报值)→ 落库行 = 逐轮原值,
 * 行合计 = 真实总量(40),无 45/55 形态双计。
 */
@DisplayName("S3:usage 逐模型轮落库,无 UsageAccumulator 双计(Task 6)")
class UsageNoDoubleCountTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String CONV = "conv-u";
    private static final String USER = "bob";

    @Test
    @DisplayName("单迭代 × 2 模型轮(tool 轮 + 末轮):恰 2 行 usage,值为各轮原值(非累计戳)")
    void usage_rows_equal_model_rounds_no_double() {
        ChatUsageService usage = mock(ChatUsageService.class);
        ToolCallThenFinalModel model = new ToolCallThenFinalModel();

        // 复刻 DefaultChat.stream 的 advisor 挂载形态(无 ctx:A1 骨架路径)
        ChatClient client = ChatClient.builder(model).build();
        List<ChatResponse> responses = client.prompt().user("查一下")
                .toolCallbacks(new NoopTool())
                .advisors(new ChatUsageRecordingAdvisor(usage, CONV, USER, 100))
                .advisors(LoomAgenticLoopAdvisor.builder().build())
                .advisors(a -> a.param("conversationId", CONV))
                .stream().chatResponse().collectList().block(TIMEOUT);

        assertThat(responses).isNotEmpty();
        assertThat(model.streamCalls.get()).as("2 个模型轮(tool 轮 + 末轮)").isEqualTo(2);

        // 每轮 1 行:轮1 = (10,5,15),轮2 = (20,5,25);合计 40 = 真实总量,无双计
        verify(usage, times(1)).record(eq(CONV), eq(USER), eq(10L), eq(5L), eq(15L));
        verify(usage, times(1)).record(eq(CONV), eq(USER), eq(20L), eq(5L), eq(25L));
        // 不存在累计形态(30,10,40 之类)的第三条重复行
        verify(usage, times(2)).record(anyString(), anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("对照:无 loop advisor(auto-register 默认 ToolCallingAdvisor)行数/值同 —— S1 替换零漂移(A1)")
    void default_tool_calling_advisor_produces_same_usage_rows() {
        ChatUsageService usage = mock(ChatUsageService.class);
        ToolCallThenFinalModel model = new ToolCallThenFinalModel();

        ChatClient client = ChatClient.builder(model).build();
        client.prompt().user("查一下")
                .toolCallbacks(new NoopTool())
                .advisors(new ChatUsageRecordingAdvisor(usage, CONV, USER, 100))
                .advisors(a -> a.param("conversationId", CONV))
                .stream().chatResponse().collectList().block(TIMEOUT);

        assertThat(model.streamCalls.get()).isEqualTo(2);
        verify(usage, times(1)).record(eq(CONV), eq(USER), eq(10L), eq(5L), eq(15L));
        verify(usage, times(1)).record(eq(CONV), eq(USER), eq(20L), eq(5L), eq(25L));
        verify(usage, times(2)).record(anyString(), anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("结构锁:ChatUsageRecordingAdvisor(order=100)在 loop advisor(HIGHEST+300)下游 → copy 重入覆盖它")
    void usage_advisor_is_downstream_of_loop_advisor() {
        Advisor usageAdvisor = new ChatUsageRecordingAdvisor(mock(ChatUsageService.class), CONV, USER, 100);
        Advisor loopAdvisor = LoomAgenticLoopAdvisor.builder().build();
        // ToolCallingAdvisor.copy(this) 取 order 更大(更下游)的 advisor;
        // 100 > HIGHEST_PRECEDENCE+300 ⇒ 每次 copy 重入都再次穿过 usage advisor。
        assertThat(usageAdvisor.getOrder()).isGreaterThan(loopAdvisor.getOrder());
    }

    // ==================== fixtures ====================

    /** 一个恒返回固定文本的工具,让 loop 走完 tool 轮。 */
    private static final class NoopTool implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("noop")
                    .description("noop tool for S3 usage test")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return "ok";
        }
    }

    /**
     * 流式两段桩模型:首轮流式回 tool_call(usage 10/5/15),prompt 含 ToolResponseMessage 后
     * 回最终文本(usage 20/5/25)。usage 挂在<b>单 chunk</b>的 metadata 上
     * (模拟 provider 的 per-call usage 帧)。
     */
    private static final class ToolCallThenFinalModel implements ChatModel {

        final AtomicInteger streamCalls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("stream only");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            int round = streamCalls.incrementAndGet();
            if (round == 1) {
                AssistantMessage toolCall = AssistantMessage.builder()
                        .content("thinking")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("tc-1", "function", "noop", "{}")))
                        .build();
                return Flux.just(withUsage(new ChatResponse(List.of(new Generation(toolCall))), 10, 5, 15));
            }
            return Flux.just(withUsage(
                    new ChatResponse(List.of(new Generation(new AssistantMessage("最终答案")))), 20, 5, 25));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private static ChatResponse withUsage(ChatResponse base, int p, int c, int t) {
            return ChatResponse.builder()
                    .from(base)
                    .metadata(org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                            .usage(new org.springframework.ai.chat.metadata.DefaultUsage(p, c, t))
                            .build())
                    .build();
        }
    }
}
