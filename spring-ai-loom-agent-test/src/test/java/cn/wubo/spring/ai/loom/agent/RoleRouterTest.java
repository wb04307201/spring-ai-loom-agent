package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.model.RoleInfo;
import cn.wubo.spring.ai.loom.agent.rbac.IMcpServerAdmin;
import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import cn.wubo.spring.ai.loom.agent.token.ChatUsageService;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import cn.wubo.spring.ai.loom.agent.user.IUserConversation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pins the 4xx error handling on POST /admin/roles and DELETE /admin/roles/{code}.
 *
 * <p>Bug history: the admin role endpoints previously surfaced every validation
 * failure (blank code/name, duplicate code, missing role, system role deletion)
 * as HTTP 500 because the router let {@link LoomAgentRuntimeException} escape.
 * The fix maps {@code getStatusCode()} onto the response status; this test
 * exercises the router in isolation to lock that contract in place.</p>
 */
class RoleRouterTest {

    private IRoleService roleService;
    private RouterFunction<ServerResponse> router;

    @BeforeEach
    void setUp() {
        roleService = mock(IRoleService.class);
        LoomAgentConfiguration.WebConfiguration web = new LoomAgentConfiguration.WebConfiguration();
        router = web.loomAgentBaseRouter(
                mock(IUser.class),
                new LoomAgentProperties(),
                mock(IUserConversation.class),
                mock(ChatUsageService.class),
                mock(cn.wubo.spring.ai.loom.agent.chat.ConversationFlowService.class),
                roleService,
                mock(IMcpServerAdmin.class),
                mock(JdbcTemplate.class),
                mock(cn.wubo.spring.ai.loom.agent.capability.CapabilityService.class));
    }

    @AfterEach
    void tearDown() {
        reset(roleService);
    }

    @Test
    void createBlankCode_returns400() throws Exception {
        when(roleService.create(eq(""), anyString(), any(), any()))
                .thenThrow(new LoomAgentRuntimeException(400, "角色 code 和 name 必填"));

        ServerResponse response = route("POST", "/spring/ai/loom/admin/roles",
                "{\"code\":\"\",\"name\":\"x\"}");

        assertThat(response.statusCode().value()).isEqualTo(400);
    }

    @Test
    void createBlankName_returns400() throws Exception {
        when(roleService.create(anyString(), eq(""), any(), any()))
                .thenThrow(new LoomAgentRuntimeException(400, "角色 code 和 name 必填"));

        ServerResponse response = route("POST", "/spring/ai/loom/admin/roles",
                "{\"code\":\"tmp-x\",\"name\":\"\"}");

        assertThat(response.statusCode().value()).isEqualTo(400);
    }

    @Test
    void createDuplicateCode_returns409() throws Exception {
        when(roleService.create(eq("dup"), anyString(), any(), any()))
                .thenThrow(new LoomAgentRuntimeException(409, "角色 code 已存在: dup"));

        ServerResponse response = route("POST", "/spring/ai/loom/admin/roles",
                "{\"code\":\"dup\",\"name\":\"dup\"}");

        assertThat(response.statusCode().value()).isEqualTo(409);
    }

    @Test
    void deleteMissingRole_returns404() throws Exception {
        doThrow(new LoomAgentRuntimeException(404, "角色不存在: ghost"))
                .when(roleService).deleteOrThrow("ghost");

        ServerResponse response = route("DELETE", "/spring/ai/loom/admin/roles/ghost", null);

        assertThat(response.statusCode().value()).isEqualTo(404);
    }

    @Test
    void deleteSystemRole_returns400() throws Exception {
        doThrow(new LoomAgentRuntimeException(400, "系统角色不可删除: ADMIN"))
                .when(roleService).deleteOrThrow("ADMIN");

        ServerResponse response = route("DELETE", "/spring/ai/loom/admin/roles/ADMIN", null);

        assertThat(response.statusCode().value()).isEqualTo(400);
    }

    @Test
    void listRoles_returns200WithBody() throws Exception {
        when(roleService.list()).thenReturn(List.of(
                new RoleInfo("ADMIN", "管理员", true, "system"),
                new RoleInfo("USER", "用户", true, "system")));

        ServerResponse response = route("GET", "/spring/ai/loom/admin/roles", null);

        assertThat(response.statusCode().value()).isEqualTo(200);
        Object entity = ((EntityResponse<?>) response).entity();
        assertThat(entity).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<RoleInfo> roles = (List<RoleInfo>) entity;
        assertThat(roles).hasSize(2);
        assertThat(roles.get(0).code()).isEqualTo("ADMIN");
    }

    @Test
    void deleteExistingRole_returns200() throws Exception {
        ServerResponse response = route("DELETE", "/spring/ai/loom/admin/roles/tmp-role", null);

        assertThat(response.statusCode().value()).isEqualTo(200);
        verify(roleService).deleteOrThrow("tmp-role");
    }

    private ServerResponse route(String method, String path, String body) throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(method, path);
        servletRequest.setContentType("application/json");
        if (body != null) {
            servletRequest.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        ServerRequest request = ServerRequest.create(
                servletRequest, List.of(new MappingJackson2HttpMessageConverter()));
        return router.route(request).orElseThrow().handle(request);
    }
}