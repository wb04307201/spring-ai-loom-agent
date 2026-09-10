package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import cn.wubo.spring.ai.loom.agent.tool.git.IGitTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 子任务 RBAC 过滤真 DB 端到端(spec 2026-09-10-subtask-rbac-filter §4)。
 * <p>
 * 证明 role_tool 表授权 → 子任务工具集的真实链路:未授权 tool_git 时 IGitTool 不进列表,
 * 授权后进列表。mock 的是 ChatClient(拦截 prompt→call 链、捕 spec.tools 实参);
 * executor / CapabilityService / IRoleService / embedTools 都是容器真 bean。
 * <p>
 * 本 IT 加载完整 {@link LoomAgentTestApplication} 上下文 —— 若 Task 3 的 {@code @Lazy}
 * 破环没做对,容器启动即 {@code BeanCurrentlyInCreationException},本类直接 error(充当启动回归门)。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("子任务 RBAC 过滤 IT")
class SubTaskRbacFilterIT {

    @MockBean
    private ChatClient chatClient;   // @Qualifier("chatClient") 的同一 bean 被替换

    @Autowired
    private ISubTaskExecutor executor;

    @Autowired
    private IRoleService roleService;

    @Autowired
    private JdbcTemplate jdbc;

    private static final String ROLE = "rbac-it-role";
    private static final String USER = "rbac-it-user";

    private void cleanup() {
        jdbc.update("DELETE FROM loom_subtask_history WHERE username = ?", USER);
        jdbc.update("DELETE FROM user_role WHERE role_code = ?", ROLE);
        jdbc.update("DELETE FROM role_tool WHERE role_code = ?", ROLE);
        jdbc.update("DELETE FROM role_mcp WHERE role_code = ?", ROLE);
        jdbc.update("DELETE FROM role_skill WHERE role_code = ?", ROLE);
        jdbc.update("DELETE FROM loom_role_knowledge WHERE role_code = ?", ROLE);
        jdbc.update("DELETE FROM role WHERE code = ?", ROLE);
        jdbc.update("DELETE FROM user_info WHERE username = ?", USER);
    }

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.update("INSERT INTO user_info (username, nickname, password, type) VALUES (?,?,?,?)",
                USER, "RbacIT", "x", "USER");
        roleService.create(ROLE, "RBAC IT 角色", "subtask rbac filter", null);
        roleService.setUserRoles(USER, List.of(ROLE));
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    /**
     * Stub 真正被链式使用的调用:prompt()→call()→chatResponse()→getResult()→getOutput()→getText()。
     * doExecute 里 spec.user/system/advisors/toolContext/tools/toolCallbacks 全是语句调用
     * (返回值被忽略),不 stub 也不会 NPE,且 tools() 照样被 mock 记录供 verify。
     */
    private ChatClient.ChatClientRequestSpec stubChain() {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        ChatResponse resp = mock(ChatResponse.class);
        Generation gen = mock(Generation.class);
        AssistantMessage msg = mock(AssistantMessage.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(resp);
        when(resp.getResult()).thenReturn(gen);
        when(gen.getOutput()).thenReturn(msg);
        when(msg.getText()).thenReturn("done");
        return spec;
    }

    private List<Object> captureTools(ChatClient.ChatClientRequestSpec spec, String subId) {
        executor.execute(new SubTaskRequest(subId, "conv-it", null, USER, "do", null, false));
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(spec).tools(captor.capture());
        return java.util.Arrays.asList(captor.getValue());
    }

    @Test
    @DisplayName("未授权 tool_git → 子任务工具集不含 IGitTool;授权后 → 含")
    void gitToolFollowsRoleGrant() {
        // 授权前:role_tool 空 → git 不在子任务工具集
        ChatClient.ChatClientRequestSpec spec1 = stubChain();
        List<Object> beforeTools = captureTools(spec1, "it-before");
        // 防空过断言(fix round 1):captured 数组若为空,noneMatch 平凡通过 ——
        // 先正向 pin universal 工具(ITimeTool 恒在 visibleToolGroupsFor)确实进了子任务集。
        assertThat(beforeTools)
                .as("universal 工具(ITimeTool)必须恒在子任务工具集(防空过断言)")
                .anyMatch(t -> t instanceof cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool);
        assertThat(beforeTools)
                .as("未授权 tool_git 时 IGitTool 不得进子任务工具集(RBAC 绕过修复)")
                .noneMatch(t -> t instanceof IGitTool);

        // 授权 tool_git 后:git 进子任务工具集
        roleService.setRoleTools(ROLE, List.of(new IRoleService.RoleToolItem("tool_git", true)));
        ChatClient.ChatClientRequestSpec spec2 = stubChain();
        List<Object> afterTools = captureTools(spec2, "it-after");
        assertThat(afterTools)
                .as("授权 tool_git 后 IGitTool 应进子任务工具集")
                .anyMatch(t -> t instanceof IGitTool);
    }
}
