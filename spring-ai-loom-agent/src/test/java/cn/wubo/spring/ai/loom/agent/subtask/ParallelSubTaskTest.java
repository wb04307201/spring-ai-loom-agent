package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.capability.CapabilityService;
import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code start_sub_tasks} 批量并行子任务的契约回归锁。
 *
 * <p><b>为什么需要它</b>:Spring AI 2.0.1 的 {@code DefaultToolCallingManager} 用
 * {@code Iterator} <b>串行</b>遍历同一轮的 tool calls(反编译确认:只有
 * {@code iterator} + {@code goto} 循环,无 {@code parallelStream} / {@code ExecutorService})。
 * 这意味着 LLM 若按 skill 现有文案"同轮发 N 个 {@code start_sub_task}",实际会串行执行 ——
 * 2026-10-04 端到端实测证实:两个子任务耗时 55s 与 21s、陆续出现,而非同时。
 *
 * <p><b>本类的锁点</b>:一次工具调用传 N 个维度 ⇒ 内部全部提交线程池 ⇒
 * <b>真并行</b>(受池大小限制,超出则排队)⇒ 合并结果一次返回。LLM 不需要知道并发数。
 */
@DisplayName("start_sub_tasks 批量并行子任务")
class ParallelSubTaskTest {

    private ChatClient chatClient;
    private MessageChatMemoryAdvisor memoryAdvisor;
    private ExecutorService pool;
    private IMcp mcp;
    private SubTaskRegistry registry;
    private CapabilityService capabilityService;
    private LoomAgentProperties properties;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        memoryAdvisor = mock(MessageChatMemoryAdvisor.class);
        mcp = mock(IMcp.class);
        List<IEmbedTool> embedTools = Collections.emptyList();
        registry = new SubTaskRegistry(8, 100);
        capabilityService = new CapabilityService(embedTools, mcp,
                mock(cn.wubo.spring.ai.loom.agent.rbac.IRoleService.class));
        // 池大小 4 = 与生产默认 max-concurrent 一致
        pool = Executors.newFixedThreadPool(4);
        properties = new LoomAgentProperties();
        properties.getSubtask().setTimeoutSeconds(30);
        stubChain("ok");
    }

    /** 逐字沿用 DefaultSubTaskToolTimeoutTest 的 stub 链,使 happy path 不抛 NPE。 */
    private void stubChain(String reply) {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        var chatResponse = mock(org.springframework.ai.chat.model.ChatResponse.class);
        var generation = mock(org.springframework.ai.chat.model.Generation.class);
        var msg = mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.advisors(memoryAdvisor)).thenReturn(spec);
        when(spec.toolContext(any(Map.class))).thenReturn(spec);
        when(spec.tools(any(Object[].class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(msg);
        when(msg.getText()).thenReturn(reply);
    }

    private DefaultSubTaskTool tool() {
        ISubTaskExecutor executor = new DefaultSubTaskExecutor(
                chatClient, memoryAdvisor, pool, mcp, Collections.emptyList(),
                registry, capabilityService, properties.getSubtask());
        return new DefaultSubTaskTool(executor, registry);
    }

    private static ToolContext ctx(String user) {
        return new ToolContext(Map.of("username", user, "parentConversationId", "conv-1"));
    }

    // ==================== 契约 ====================

    @Test
    @DisplayName("R1: 一次调用传 3 个维度,返回结果按维度分段且全部出现")
    void returnsOneSectionPerDimension() {
        String out = tool().startSubTasks(
                List.of("维度A:技术可行性", "维度B:成本", "维度C:风险"),
                "共享上下文", ctx("alice"));

        assertThat(out)
                .as("每个维度都要在返回里出现,LLM 才能据此汇总")
                .contains("维度A").contains("维度B").contains("维度C");
        assertThat(out).contains("共享上下文提示").as("应标明用的是共享 systemContext");
    }

    @Test
    @DisplayName("R2: 真并行 —— 3 个各 sleep 300ms 的子任务,总耗时应远小于 900ms")
    void executesInParallel() {
        // 每个子任务都阻塞 300ms:串行需 ≥900ms,并行只需 ~300ms
        stubChain(null);
        when(chatClient.prompt()).thenAnswer(inv -> slowSpec(300));

        long t0 = System.currentTimeMillis();
        String out = tool().startSubTasks(
                List.of("d1", "d2", "d3"), null, ctx("alice"));
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(out).contains("d1").contains("d2").contains("d3");
        assertThat(elapsed)
                .as("并行下 3×300ms 应 ≈300ms;串行则 ≥900ms。实测 %dms", elapsed)
                .isLessThan(800L);
    }

    @Test
    @DisplayName("R3: 维度数超过池大小时排队而非报错,全部结果仍返回")
    void queuesBeyondPoolSize() {
        stubChain(null);
        when(chatClient.prompt()).thenAnswer(inv -> slowSpec(120));

        // 池=4,传 6 个 ⇒ 4 并行 + 2 排队,必须全部完成(不是拒绝)
        String out = tool().startSubTasks(
                List.of("q1", "q2", "q3", "q4", "q5", "q6"), null, ctx("alice"));

        for (int i = 1; i <= 6; i++) {
            assertThat(out).as("第 %d 个维度也必须完成(排队,非拒绝)", i).contains("q" + i);
        }
    }

    @Test
    @DisplayName("R4: 空列表 / 全空白维度被拒绝并给出可读提示,不静默返回空")
    void rejectsEmptyInput() {
        DefaultSubTaskTool t = tool();
        assertThat(t.startSubTasks(List.of(), null, ctx("alice")))
                .contains("不能为空");
        assertThat(t.startSubTasks(Arrays.asList("  ", ""), null, ctx("alice")))
                .as("全空白维度应被识别为无有效维度")
                .contains("不能为空");
    }

    @Test
    @DisplayName("R5: 单个维度失败不影响其他维度,失败原因在返回里可见")
    void isolatesFailurePerDimension() {
        // 第 2 个维度让 chat 抛异常
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(chatClient.prompt()).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n == 2) throw new RuntimeException("维度2炸了");
            return okSpec("维度内容" + n);
        });

        String out = tool().startSubTasks(
                List.of("ok1", "bad2", "ok3"), null, ctx("alice"));

        assertThat(out).contains("ok1").contains("ok3");
        assertThat(out).as("失败维度的编号与原因必须可见,LLM 才能在报告里如实标注")
                .contains("维度2").contains("维度2炸了");
    }

    @Test
    @DisplayName("R6: 工具被 Spring AI 注册(method 回调可见),@Tool description 不含并发数字")
    void isDiscoverableBySpringAi() {
        var tool = tool();
        // 该工具类有多个 @Tool 方法,按注册名筛出批量那个
        // (主库走 Spring AI 的 @Tool,注册名 = 方法名,本仓无 @Tool(name=) 覆盖)
        ToolCallback cb = java.util.Arrays.stream(
                        MethodToolCallbackProvider.builder().toolObjects(tool).build()
                                .getToolCallbacks())
                .filter(c -> "startSubTasks".equals(c.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "未注册 startSubTasks,现有:" + java.util.Arrays.stream(
                                MethodToolCallbackProvider.builder().toolObjects(tool).build()
                                        .getToolCallbacks())
                                .map(c -> c.getToolDefinition().name()).toList()));
        assertThat(cb.getToolDefinition().name()).isEqualTo("startSubTasks");

        String desc = cb.getToolDefinition().description();
        assertThat(desc)
                .as("并发度是部署配置(subtask.max-concurrent),不是提示词内容;"
                    + "写进 description 会让 LLM 按错误的假设规划维度")
                .doesNotContain("并行度").doesNotContain("并发").doesNotContain("个并行");
    }

    @Test
    @DisplayName("R7: 单任务 start_sub_task 保持原语义(不被本次改动破坏)")
    void singleSubTaskStillWorks() {
        String out = tool().startSubTask("单个任务", "ctx", ctx("alice"));
        assertThat(out).contains("子任务");
    }

    // ==================== helpers ====================

    private ChatClient.ChatClientRequestSpec okSpec(String reply) {
        var spec = mock(ChatClient.ChatClientRequestSpec.class);
        var callSpec = mock(ChatClient.CallResponseSpec.class);
        var resp = mock(org.springframework.ai.chat.model.ChatResponse.class);
        var gen = mock(org.springframework.ai.chat.model.Generation.class);
        var msg = mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.advisors(memoryAdvisor)).thenReturn(spec);
        when(spec.toolContext(any(Map.class))).thenReturn(spec);
        when(spec.tools(any(Object[].class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(resp);
        when(resp.getResult()).thenReturn(gen);
        when(gen.getOutput()).thenReturn(msg);
        when(msg.getText()).thenReturn(reply);
        return spec;
    }

    /** 返回一个"阻塞 ms 毫秒后完成"的 spec,用于实测并行度。 */
    private ChatClient.ChatClientRequestSpec slowSpec(long ms) {
        var spec = okSpec("done");
        var callSpec = mock(ChatClient.CallResponseSpec.class);
        when(spec.call()).thenAnswer(inv -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return callSpec;
        });
        return spec;
    }
}
