package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.capability.CapabilityService;
import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import cn.wubo.spring.ai.loom.agent.subtask.DefaultSubTaskExecutor;
import cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry;
import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.MemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.ToolAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A5/E1 回归锁:子任务 advisor 链绝不包含 {@link LoomAgenticLoopAdvisor}
 * (spec 2026-11-21 Task 5,brief Step 3 + Step 4 变异目标)。
 *
 * <p><b>为什么必须锁</b>:loop advisor 是 ToolCallingAdvisor 子类(implements ToolAdvisor),
 * 主 {@code ChatClient} bean 被子任务链复用({@code DefaultSubTaskExecutor} 拿同一 bean 调
 * {@code prompt().call()})。若有人把 loop advisor 改成 defaultAdvisor / 共享 bean
 * (brief Step 4 的变异:挪进 {@code LoomAgentConfiguration#chatClient} 的
 * {@code builder.defaultAdvisors(...)}),子任务链会无 SSE 语义地继承它 —— 用例 2 变红。
 *
 * <p>三层锁:
 * <ol>
 *   <li>真 ChatClient bean 的 defaultAdvisors(经 {@code prompt()} 拷贝后反射读取)
 *       不含 loop advisor、且不含<b>任何</b> ToolAdvisor —— defaultAdvisor 形态从根上排除;</li>
 *   <li>{@code DefaultSubTaskExecutor} 实际挂进子任务 spec 的 advisor(varargs)与
 *       advisor param(consumer)均与 loop advisor / StreamRunContext 无关;</li>
 *   <li>S1 对照:真 bean + per-request loop advisor 组链后,链上恰好一个 ToolAdvisor
 *       且就是本类(auto-register 被抑制;与 NoOp 测试的 StubChatModel 真链端到端互补,
 *       这里不打网络,只反射数 advisor)。</li>
 * </ol>
 */
@SpringBootTest
@DisplayName("A5:子任务链排除 LoomAgenticLoopAdvisor + S1 主链单 ToolAdvisor")
class SubTaskLoopExclusionTest {

    /** 真 test 应用上下文里的 ChatClient bean(LoomAgentConfiguration#chatClient 产物)。 */
    @Autowired
    private ChatClient chatClient;

    // ==================== 锁 1:bean 的 defaultAdvisors 干净 ====================

    @Test
    @DisplayName("ChatClient bean 的 defaultAdvisors 不含 loop advisor / 任何 ToolAdvisor")
    void chatClient_bean_default_advisors_have_no_loop_advisor() throws Exception {
        // prompt() 返回 defaultRequest 的拷贝(2.0.2 copy 构造器逐字段复制 advisors),
        // 未加任何 per-request advisor → 反射读到的就是 defaultAdvisors 原样
        List<Advisor> defaults = advisorsOf(chatClient.prompt());

        assertThat(defaults).as("bean defaultAdvisors 现状(LoomMemoryAdvisor + SimpleLoggerAdvisor)")
                .isNotEmpty();
        assertThat(defaults).as("A5 根:loop advisor 绝不可做成 defaultAdvisor —— 子任务复用同一 bean 会继承它")
                .noneMatch(a -> a instanceof LoomAgenticLoopAdvisor);
        assertThat(defaults).as("defaultAdvisors 里有 ToolAdvisor 会让子任务链也跳 auto-register,行为漂移")
                .noneMatch(a -> a instanceof ToolAdvisor);
    }

    // ==================== 锁 2:executor 挂进子任务 spec 的内容 ====================

    @Test
    @DisplayName("DefaultSubTaskExecutor 挂进子任务 spec 的 advisor/param 不含 loop advisor / StreamRunContext")
    @SuppressWarnings("unchecked")
    void subtask_executor_spec_has_no_loop_advisor() {
        ChatClient mockClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        MemoryAdvisor memoryAdvisor = mock(MemoryAdvisor.class);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        IMcp mcp = mock(IMcp.class);
        List<IEmbedTool> embedTools = List.of();
        CapabilityService capabilityService = new CapabilityService(embedTools, mcp,
                mock(cn.wubo.spring.ai.loom.agent.rbac.IRoleService.class));

        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        org.springframework.ai.chat.model.ChatResponse chatResponse =
                mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.springframework.ai.chat.model.Generation generation =
                mock(org.springframework.ai.chat.model.Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage msg =
                mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        when(mockClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.advisors(any(Advisor[].class))).thenReturn(spec);
        when(spec.toolContext(anyMap())).thenReturn(spec);
        when(spec.tools(any(Object[].class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(msg);
        when(msg.getText()).thenReturn("sub done");

        DefaultSubTaskExecutor target = new DefaultSubTaskExecutor(
                mockClient, memoryAdvisor, pool, mcp, embedTools,
                new SubTaskRegistry(8, 100), capabilityService);

        SubTaskResult result = target.execute(
                new SubTaskRequest("sub-a5", "conv-a5", null, "alice", "do X", null, false));
        assertThat(result.status()).as("先证子任务流程真的跑完,否则下面的 verify 是空转").isEqualTo(SubTaskStatus.COMPLETED);

        // varargs 通路:executor 直接挂的 advisor(只有 memoryAdvisor)
        ArgumentCaptor<Advisor> advisorCaptor = ArgumentCaptor.forClass(Advisor.class);
        verify(spec, atLeastOnce()).advisors(advisorCaptor.capture());
        List<Advisor> mounted = advisorCaptor.getAllValues();
        assertThat(mounted).as("子任务 spec 挂的 advisor 里不许出现 loop advisor")
                .noneMatch(a -> a instanceof LoomAgenticLoopAdvisor);
        assertThat(mounted).as("连 ToolAdvisor marker 都不许出现:出现即子任务被换成了自管 tool 循环")
                .noneMatch(a -> a instanceof ToolAdvisor);
        assertThat(mounted).as("memoryAdvisor 必须仍在(子任务记忆不受本次改动影响)").containsExactly(memoryAdvisor);

        // consumer 通路:executor 传的 advisor param 只允许会话 id
        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> consumerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(spec, atLeastOnce()).advisors(consumerCaptor.capture());
        List<String> paramKeys = new ArrayList<>();
        List<Advisor> paramAdvisors = new ArrayList<>();
        for (Consumer<ChatClient.AdvisorSpec> consumer : consumerCaptor.getAllValues()) {
            consumer.accept(new ChatClient.AdvisorSpec() {
                @Override
                public ChatClient.AdvisorSpec param(String k, Object v) {
                    paramKeys.add(k);
                    return this;
                }

                @Override
                public ChatClient.AdvisorSpec params(Map<String, Object> p) {
                    paramKeys.addAll(p.keySet());
                    return this;
                }

                @Override
                public ChatClient.AdvisorSpec advisors(Advisor... advisors) {
                    paramAdvisors.addAll(List.of(advisors));
                    return this;
                }

                @Override
                public ChatClient.AdvisorSpec advisors(List<Advisor> advisors) {
                    paramAdvisors.addAll(advisors);
                    return this;
                }
            });
        }
        assertThat(paramKeys).as("子任务 advisor param 只有 ChatMemory 会话 id(隔离命名空间 {conv}--sub--{id})")
                .containsExactly(ChatMemory.CONVERSATION_ID)
                .doesNotContain(StreamRunContext.CONTEXT_KEY);
        assertThat(paramAdvisors).noneMatch(a -> a instanceof LoomAgenticLoopAdvisor);
    }

    // ==================== 锁 3:S1 对照(per-request 挂载形态) ====================

    @Test
    @DisplayName("S1:真 bean + per-request loop advisor → 链上恰好一个 ToolAdvisor 且是本类")
    void main_chat_per_request_mount_yields_exactly_one_tool_advisor() throws Exception {
        // 复刻 DefaultChat.stream 的挂载形态(不打网络:只组 spec、反射数 advisor;
        // validateSingleToolAdvisor 的真执行在 NoOpTest 的 StubChatModel 真链用例)
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .advisors(LoomAgenticLoopAdvisor.builder().build())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "conv-s1"));

        List<Advisor> toolAdvisors = advisorsOf(spec).stream()
                .filter(a -> a instanceof ToolAdvisor).toList();
        assertThat(toolAdvisors).as("auto-register 被 ToolAdvisor marker 抑制 → 主链恰好一个")
                .hasSize(1);
        assertThat(toolAdvisors.get(0)).isInstanceOf(LoomAgenticLoopAdvisor.class);
    }

    // ==================== helpers ====================

    /** 反射读 DefaultChatClientRequestSpec 的私有 advisors 链(字段名钉死于 2.0.2 源 jar)。 */
    @SuppressWarnings("unchecked")
    private static List<Advisor> advisorsOf(ChatClient.ChatClientRequestSpec spec) throws Exception {
        Field f = spec.getClass().getDeclaredField("advisors");
        f.setAccessible(true);
        return (List<Advisor>) f.get(spec);
    }
}
