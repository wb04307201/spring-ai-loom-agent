package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.eq;

class DefaultSubTaskExecutorTest {

    private ChatClient chatClient;
    private MessageChatMemoryAdvisor memoryAdvisor;
    private ThreadPoolExecutor executor;
    private IMcp mcp;
    private java.util.List<cn.wubo.spring.ai.loom.agent.tool.IEmbedTool> embedTools;
    private SubTaskRegistry subTaskRegistry;
    private DefaultSubTaskExecutor target;
    private cn.wubo.spring.ai.loom.agent.rbac.IRoleService roleService;
    private cn.wubo.spring.ai.loom.agent.capability.CapabilityService capabilityService;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        memoryAdvisor = mock(MessageChatMemoryAdvisor.class);
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        mcp = mock(IMcp.class);
        embedTools = java.util.Collections.emptyList();
        // Pass a real (in-memory) registry now that DefaultSubTaskExecutor
        // registers/markFinished at every call site.
        subTaskRegistry = new SubTaskRegistry(8, 100);
        // RBAC 过滤用真 CapabilityService(mock IRoleService 做数据源)——
        // mock CapabilityService 会把过滤逻辑 stub 掉,断言沦为 mock 回音(spec §4)。
        roleService = mock(cn.wubo.spring.ai.loom.agent.rbac.IRoleService.class);
        capabilityService = new cn.wubo.spring.ai.loom.agent.capability.CapabilityService(
                embedTools, mcp, roleService);
        target = new DefaultSubTaskExecutor(chatClient, memoryAdvisor, executor, mcp,
                embedTools, subTaskRegistry, capabilityService);
    }

    /** 重建 executor + CapabilityService(两者必须共享同一 embedTools 列表 —— universalToolGroups 靠扫它)。 */
    private void rebuildWithTools(cn.wubo.spring.ai.loom.agent.tool.IEmbedTool... tools) {
        embedTools = java.util.List.of(tools);
        capabilityService = new cn.wubo.spring.ai.loom.agent.capability.CapabilityService(
                embedTools, mcp, roleService);
        target = new DefaultSubTaskExecutor(chatClient, memoryAdvisor, executor, mcp,
                embedTools, subTaskRegistry, capabilityService);
    }

    /** 完整 stub chat 链,返回可做 verify 的 spec mock(链路 stub 模式逐字抄自本测试类既有 happy-path / excludesAskUser 用例,勿自创变体)。 */
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
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
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

    private SubTaskRequest req(String user) {
        return new SubTaskRequest("sub-" + user, "conv-" + user, null, user, "do X", null, false);
    }

    @Test
    void executesAndReturnsCompletedResultOnHappyPath() throws Exception {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        org.springframework.ai.chat.model.ChatResponse chatResponse =
                mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.springframework.ai.chat.model.Generation generation =
                mock(org.springframework.ai.chat.model.Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage msg =
                mock(org.springframework.ai.chat.messages.AssistantMessage.class);

        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.advisors(memoryAdvisor)).thenReturn(spec);
        when(spec.toolContext(any(Map.class))).thenReturn(spec);
        when(spec.toolCallbacks(any(ToolCallbackProvider[].class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(msg);
        when(msg.getText()).thenReturn("sub task done");

        SubTaskRequest req = new SubTaskRequest("sub-1", "conv-1", null, "alice",
                "do X", null, false);

        SubTaskResult result = target.execute(req);

        assertThat(result.status()).isEqualTo(SubTaskStatus.COMPLETED);
        assertThat(result.text()).isEqualTo("sub task done");
        assertThat(result.errorMessage()).isEmpty();
        assertThat(result.subTaskId()).isEqualTo("sub-1");

        // Verify the executor propagated the tool context and asked the mcp layer for
        // callbacks (the peer-flagged critical bug).
        verify(spec).toolContext(argThat(props ->
                props != null
                        && "alice".equals(props.get("username"))
                        && "conv-1".equals(props.get("parentConversationId"))));
        verify(mcp).getVisibleToolCallbackProvider(eq("alice"), any());
    }

    @Test
    void returnsFailedOnException() {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.advisors(memoryAdvisor)).thenReturn(spec);
        when(spec.toolContext(any(Map.class))).thenReturn(spec);
        when(spec.call()).thenThrow(new RuntimeException("boom"));

        SubTaskRequest req = new SubTaskRequest("sub-2", "conv-2", null, "bob",
                "do Y", null, false);

        SubTaskResult result = target.execute(req);

        assertThat(result.status()).isEqualTo(SubTaskStatus.FAILED);
        assertThat(result.errorMessage()).contains("boom");
    }

    @Test
    void cancelInterruptsRunningWorker() throws Exception {
        // A slow LLM call simulation: worker blocks on a latch until cancel() fires.
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.advisors(memoryAdvisor)).thenReturn(spec);
        when(spec.toolContext(any(Map.class))).thenReturn(spec);
        when(spec.call()).thenAnswer(inv -> {
            try {
                blocker.await(5, TimeUnit.SECONDS);
                return null;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                workerInterrupted.countDown();
                throw ie;
            }
        });

        SubTaskRequest req = new SubTaskRequest("sub-3", "conv-3", null, "carol",
                "slow request", null, false);

        // Run execute() on a background thread so we can cancel() while it's running.
        Thread submitter = new Thread(() -> target.execute(req));
        submitter.setDaemon(true);
        submitter.start();

        // Give the worker time to enter spec.call() and start awaiting the latch.
        Thread.sleep(150);

        boolean cancelledOk = target.cancel("sub-3");
        // Safety net: free the latch in case cancel didn't reach (test would still pass
        // because the assertion relies on workerInterrupted, not blocker timing).
        blocker.countDown();

        submitter.join(2000);
        assertThat(cancelledOk).isTrue();
        assertThat(workerInterrupted.getCount()).isZero(); // worker was interrupted inside spec.call()
    }

    @Test
    void subTaskToolListExcludesAskUserAndSelfTools() {
        // #1 AskUser(spec D6):子任务 LLM 看不到 askUser(schema 级排除),
        // 同时保留既有 ISubTaskTool/IScheduleTool 防递归排除的回归断言。
        // 两层过滤正交:4 个工具全是 universal → 通过 RBAC 关;instanceof 关再剔 3 个自身工具 → 断言不变
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        org.springframework.ai.chat.model.ChatResponse chatResponse =
                mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.springframework.ai.chat.model.Generation generation =
                mock(org.springframework.ai.chat.model.Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage msg =
                mock(org.springframework.ai.chat.messages.AssistantMessage.class);

        cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool timeTool =
                mock(cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool.class);
        cn.wubo.spring.ai.loom.agent.askuser.IAskUserTool askTool =
                mock(cn.wubo.spring.ai.loom.agent.askuser.IAskUserTool.class);
        ISubTaskTool selfTool = mock(ISubTaskTool.class);
        cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool schedTool =
                mock(cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool.class);

        rebuildWithTools(timeTool, askTool, selfTool, schedTool);

        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.advisors(memoryAdvisor)).thenReturn(spec);
        when(spec.toolContext(any(Map.class))).thenReturn(spec);
        when(spec.tools(any(Object[].class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(msg);
        when(msg.getText()).thenReturn("done");

        SubTaskRequest req = new SubTaskRequest("sub-f", "conv-f", null, "alice",
                "do X", null, false);
        target.execute(req);

        org.mockito.ArgumentCaptor<Object[]> captor =
                org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(spec).tools(captor.capture());
        assertThat(captor.getValue()).containsExactly(timeTool);
    }

    @Test
    void subTaskToolListExcludesUnauthorizedRbacTools() {
        // spec 2026-09-10-subtask-rbac-filter:未授权 RBAC 工具不进子任务工具集
        var timeTool = mock(cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool.class);
        var gitTool = mock(cn.wubo.spring.ai.loom.agent.tool.git.IGitTool.class);
        var renderTool = mock(cn.wubo.spring.ai.loom.agent.tool.render.IHtmlRenderTool.class);
        rebuildWithTools(timeTool, gitTool, renderTool);
        var spec = stubChain("ok");

        SubTaskResult result = target.execute(req("alice"));

        assertThat(result.status()).isEqualTo(SubTaskStatus.COMPLETED);
        org.mockito.ArgumentCaptor<Object[]> captor =
                org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(spec).tools(captor.capture());
        // time = universal 保留;git/render = RBAC 未授权(roleService 默认空)剔除
        assertThat(captor.getValue()).containsExactly(timeTool);
    }

    @Test
    void subTaskToolListIncludesAuthorizedRbacTools() {
        var timeTool = mock(cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool.class);
        var gitTool = mock(cn.wubo.spring.ai.loom.agent.tool.git.IGitTool.class);
        var renderTool = mock(cn.wubo.spring.ai.loom.agent.tool.render.IHtmlRenderTool.class);
        rebuildWithTools(timeTool, gitTool, renderTool);
        when(roleService.getVisibleToolsForUser("alice")).thenReturn(java.util.List.of("tool_git"));
        var spec = stubChain("ok");

        target.execute(req("alice"));

        org.mockito.ArgumentCaptor<Object[]> captor =
                org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(spec).tools(captor.capture());
        // 授权 tool_git → git 进列;render 仍未授权;保 embedTools 原序
        assertThat(captor.getValue()).containsExactly(timeTool, gitTool);
    }

    @Test
    void warnLoggedOncePerUserAndDroppedSet() {
        // D6:WARN 按 (username, droppedSet) 去重;换 user → 新 key → 重新 WARN
        var timeTool = mock(cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool.class);
        var gitTool = mock(cn.wubo.spring.ai.loom.agent.tool.git.IGitTool.class);
        rebuildWithTools(timeTool, gitTool);
        stubChain("ok");

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(DefaultSubTaskExecutor.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            target.execute(req("alice"));
            target.execute(req("alice"));   // 同 user 同 dropped([tool_git]) → 第二次只 DEBUG
            assertThat(countRbacWarns(appender)).isEqualTo(1);

            target.execute(req("bob"));     // 换 user → 新 dedup key → 重新 WARN
            assertThat(countRbacWarns(appender)).isEqualTo(2);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static long countRbacWarns(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                // 只数 RBAC 过滤的 WARN —— 同 subTaskId 重复 execute 会触发 registry 的
                // "already registered" WARN,靠消息内容区分
                .filter(e -> e.getFormattedMessage().contains("Sub-task RBAC filter"))
                .count();
    }
}
