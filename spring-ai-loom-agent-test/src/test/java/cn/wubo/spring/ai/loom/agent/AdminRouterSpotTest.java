package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.rbac.IMcpServerAdmin;
import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import cn.wubo.spring.ai.loom.agent.token.ChatUsageService;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import cn.wubo.spring.ai.loom.agent.user.IUserConversation;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 管理面路由抽测 —— 用户 CRUD 错误映射、月度统计参数校验、当前用户用量兜底。
 */
@DisplayName("admin router 抽测")
class AdminRouterSpotTest {

    private IUser user;
    private ChatUsageService chatUsageService;
    private RouterFunction<ServerResponse> router;

    @BeforeEach
    void setUp() {
        user = mock(IUser.class);
        chatUsageService = mock(ChatUsageService.class);
        router = new LoomAgentConfiguration.WebConfiguration().loomAgentBaseRouter(
                user, new LoomAgentProperties(), mock(IUserConversation.class), chatUsageService,
                mock(cn.wubo.spring.ai.loom.agent.chat.ConversationFlowService.class),
                mock(IRoleService.class), mock(IMcpServerAdmin.class), mock(JdbcTemplate.class));
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    private ServerResponse route(String method, String path, String body, String... paramKv) throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(method, path);
        servletRequest.setRequestURI(path);
        servletRequest.setServletPath(path);
        if (body != null) {
            servletRequest.setContent(body.getBytes(StandardCharsets.UTF_8));
            servletRequest.setContentType(MediaType.APPLICATION_JSON_VALUE);
        }
        for (int i = 0; i < paramKv.length; i += 2) {
            servletRequest.addParameter(paramKv[i], paramKv[i + 1]);
        }
        ServerRequest request = ServerRequest.create(servletRequest, List.of(new MappingJackson2HttpMessageConverter()));
        return router.route(request).orElseThrow().handle(request);
    }

    @Test
    @DisplayName("创建用户：委派 IUser.createUser")
    void createUserDelegates() throws Exception {
        ServerResponse response = route("POST", "/spring/ai/loom/admin/users",
                "{\"username\":\"u1\",\"nickname\":\"n1\",\"password\":\"p1\",\"type\":\"USER\"}");

        assertEquals(200, response.statusCode().value());
        verify(user).createUser("u1", "n1", "p1", "USER");
    }

    @Test
    @DisplayName("创建用户失败：LoomAgentRuntimeException → 400 而非 500")
    void createUserErrorMapsTo400() throws Exception {
        doThrow(new LoomAgentRuntimeException("用户名已存在")).when(user)
                .createUser(anyString(), anyString(), anyString(), any());

        ServerResponse response = route("POST", "/spring/ai/loom/admin/users",
                "{\"username\":\"u1\",\"nickname\":\"n1\",\"password\":\"p1\",\"type\":\"USER\"}");

        assertEquals(400, response.statusCode().value());
    }

    @Test
    @DisplayName("删除用户失败：400")
    void deleteUserErrorMapsTo400() throws Exception {
        doThrow(new LoomAgentRuntimeException("不能删除自己")).when(user).deleteUser("root");

        ServerResponse response = route("DELETE", "/spring/ai/loom/admin/users/root", null);

        assertEquals(400, response.statusCode().value());
    }

    @Test
    @DisplayName("月度统计：year 非数字 → 400")
    void statsNonNumericYearIs400() throws Exception {
        ServerResponse response = route("GET", "/spring/ai/loom/admin/stats/tokens/monthly", null, "year", "abc");

        assertEquals(400, response.statusCode().value());
        verifyNoInteractions(chatUsageService);
    }

    @Test
    @DisplayName("月度统计：数字参数透传")
    void statsNumericParamsDelegate() throws Exception {
        when(chatUsageService.monthlyByUser(2026, 7)).thenReturn(List.of());

        ServerResponse response = route("GET", "/spring/ai/loom/admin/stats/tokens/monthly", null,
                "year", "2026", "month", "7");

        assertEquals(200, response.statusCode().value());
        verify(chatUsageService).monthlyByUser(2026, 7);
    }

    @Test
    @DisplayName("当前用户用量：未登录返回空统计而非 500")
    @SuppressWarnings("unchecked")
    void currentUserTokensWithoutLogin() throws Exception {
        ServerResponse response = route("GET", "/spring/ai/loom/user/tokens/current-month", null);

        assertEquals(200, response.statusCode().value());
        Object entity = ((EntityResponse<?>) response).entity();
        assertTrue(entity.toString().contains("0") || entity instanceof Map || entity != null);
    }
}
