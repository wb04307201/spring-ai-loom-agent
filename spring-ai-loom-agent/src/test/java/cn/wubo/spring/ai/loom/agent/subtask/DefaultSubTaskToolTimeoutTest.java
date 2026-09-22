package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.capability.CapabilityService;
import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DefaultSubTaskTool.timeout 契约回归测试(spec § 5.6 B)
 * <p>
 * 背景:端到端测试 2026-09-21 复现 Qwen 代理对 ~700 行 HTML 一次生成
 * 15+ 分钟无响应,主对话同步等待卡死。本测试验证:
 * <ol>
 *   <li>T1 — 超时后 execute() 返回 SubTaskResult 含可读诊断文本</li>
 *   <li>T2 — 正常执行路径仍能完成(不超时则原结果回传)</li>
 *   <li>T3 — 超时配置回退(subTaskProperty=null → 默认 600s)</li>
 * </ol>
 */
@DisplayName("DefaultSubTaskTool.timeout 契约回归测试")
class DefaultSubTaskToolTimeoutTest {

    private ChatClient chatClient;
    private MessageChatMemoryAdvisor memoryAdvisor;
    private ExecutorService pool;
    private IMcp mcp;
    private List<IEmbedTool> embedTools;
    private SubTaskRegistry subTaskRegistry;
    private CapabilityService capabilityService;
    private LoomAgentProperties properties;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        memoryAdvisor = mock(MessageChatMemoryAdvisor.class);
        mcp = mock(IMcp.class);
        embedTools = Collections.emptyList();
        subTaskRegistry = new SubTaskRegistry(8, 100);
        // 真 CapabilityService(mock IRoleService 作数据源)—— 与现有 DefaultSubTaskExecutorTest
        // 同样的设置,避免 mock 把过滤逻辑 stub 掉导致断言失效。
        cn.wubo.spring.ai.loom.agent.rbac.IRoleService roleService =
                mock(cn.wubo.spring.ai.loom.agent.rbac.IRoleService.class);
        capabilityService = new CapabilityService(embedTools, mcp, roleService);
        pool = Executors.newFixedThreadPool(2);

        // 默认短超时便于测试
        properties = new LoomAgentProperties();
        properties.getSubtask().setTimeoutSeconds(1);
    }

    /**
     * 完整 stub chat 链,使 happy-path 不抛 NPE。
     * 链路 stub 模式(逐字抄自 DefaultSubTaskExecutorTest 既有用例)。
     */
    private ChatClient.ChatClientRequestSpec stubChain(String reply) {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        org.springframework.ai.chat.model.ChatResponse chatResponse =
                mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.springframework.ai.chat.model.Generation generation =
                mock(org.springframework.ai.chat.model.Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage msg =
                mock(org.springframework.ai.chat.messages.AssistantMessage.class);
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
        return spec;
    }

    /**
     * 构建最小可用的 SubTaskRequest。SubTaskRequest 是 record,
     * 构造器签名 (subTaskId, parentConversationId, parentSubTaskId, username, prompt, systemContext, fromScheduler)。
     */
    private SubTaskRequest buildRequest() {
        return new SubTaskRequest("sub-timeout-test", "conv-timeout", null, "alice",
                "test prompt", null, false);
    }

    @Test
    @DisplayName("T1: 超时后 execute() 返回可读诊断文本(spec § 5.4 B2)")
    void timeoutReturnsReadableDiagnostic() {
        // 通过 8-arg 构造器传入 properties(短超时 1 秒)
        DefaultSubTaskExecutor executor = new DefaultSubTaskExecutor(
                chatClient, memoryAdvisor, pool, mcp, embedTools,
                subTaskRegistry, capabilityService, properties.getSubtask());

        // 让 chatClient.prompt() 返回 mock spec,然后 spec.call() 阻塞 5 秒
        // —— 超过 timeoutSeconds=1 → 应触发 TimeoutException 路径
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.advisors(memoryAdvisor)).thenReturn(spec);
        when(spec.toolContext(any(Map.class))).thenReturn(spec);
        when(spec.call()).thenAnswer(invocation -> {
            Thread.sleep(5000);
            return null;
        });

        SubTaskRequest req = buildRequest();
        long start = System.currentTimeMillis();
        SubTaskResult result = executor.execute(req);
        long elapsed = System.currentTimeMillis() - start;

        // 应在 ~1 秒(timeoutSeconds)内返回,而不是等满 5 秒
        assertTrue(elapsed < 4500,
                "execute() should return at timeout, not wait full sleep, elapsed=" + elapsed);
        assertNotNull(result, "result must not be null");
        assertEquals(SubTaskStatus.FAILED, result.status(),
                "status should be FAILED on timeout");
        assertNotNull(result.errorMessage(), "errorMessage should not be null");
        assertTrue(result.errorMessage().contains("子任务超时"),
                "errorMessage should contain '子任务超时', actual: " + result.errorMessage());
        assertTrue(result.errorMessage().contains("1 秒"),
                "errorMessage should include the timeoutSeconds value, actual: " + result.errorMessage());
        assertTrue(result.errorMessage().contains("自动取消"),
                "errorMessage should contain '自动取消', actual: " + result.errorMessage());
    }

    @Test
    @DisplayName("T2: 正常执行路径仍返回 SubTaskResult.text() 原文(spec § 5.4 B3)")
    void normalExecutionReturnsResult() {
        // 8-arg 构造器(短超时 1 秒,但 chatClient.call() 立即返回,不会触发超时)
        DefaultSubTaskExecutor executor = new DefaultSubTaskExecutor(
                chatClient, memoryAdvisor, pool, mcp, embedTools,
                subTaskRegistry, capabilityService, properties.getSubtask());

        // 模拟快速返回(不睡眠)
        String expectedText = "正常返回的子任务结果";
        stubChain(expectedText);

        SubTaskRequest req = buildRequest();
        long start = System.currentTimeMillis();
        SubTaskResult result = executor.execute(req);
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(result, "result must not be null");
        assertEquals(SubTaskStatus.COMPLETED, result.status(),
                "正常路径 status 应为 COMPLETED,实际:" + result.status());
        assertEquals(expectedText, result.text(),
                "正常路径 text 应回传 stubChain 设定的字符串");
        assertTrue(elapsed < 1000,
                "正常路径应立即返回,不应触发超时,elapsed=" + elapsed);
    }

    @Test
    @DisplayName("T3: 超时配置回退(subTaskProperty=null → 默认 600s,无 NullPointerException)")
    void nullSubTaskPropertyUsesDefault() {
        // 7-arg 构造器(无 SubTaskProperty)→ 应该使用默认 600s 且不抛 NPE
        DefaultSubTaskExecutor executor = new DefaultSubTaskExecutor(
                chatClient, memoryAdvisor, pool, mcp, embedTools,
                subTaskRegistry, capabilityService);

        stubChain("ok");

        SubTaskRequest req = buildRequest();
        SubTaskResult result = executor.execute(req);
        assertNotNull(result, "result must not be null even with null SubTaskProperty");
        // 不依赖具体 status,只要能返回非空即可 —— 关键是 subTaskProperty=null 不能让 execute() 崩
    }
}
