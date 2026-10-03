package cn.wubo.spring.ai.loom.agent.web.http;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REST 读面脱敏（spec §4.3 约束 2）。
 *
 * <p>LLM 走不到这条路径，但 admin 将来要在 UI 上查看 profile —— 一旦明文吐给
 * 浏览器，devtools / 扩展 / 代理都能拿到 API 凭据。
 */
@DisplayName("HTTP profile 读面脱敏")
class HttpProfileRouterMaskingIT {

    private static final String SECRET = "sk-live-READ-ME-99999";
    private static final String LIST_URI = "/spring/ai/loom/api/http/profiles";

    @AfterEach
    void clearUser() {
        UserContextHolder.clear();
    }

    /** IRoleService#getVisibleToolsForUser 返回 List<String>。 */
    private static HttpManageGuard guardGranting(String... groups) {
        IRoleService roleService = mock(IRoleService.class);
        when(roleService.getVisibleToolsForUser(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of(groups));
        return new HttpManageGuard(roleService);
    }

    private static ServerRequest get(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        req.setServletPath(uri);
        return ServerRequest.create(req, List.of(new MappingJackson2HttpMessageConverter()));
    }

    private static ServerResponse invoke(RouterFunction<ServerResponse> router, String uri) throws Exception {
        ServerRequest request = get(uri);
        return router.route(request).orElseThrow().handle(request);
    }

    private static LoomAgentProperties props(Path tmp) {
        LoomAgentProperties p = new LoomAgentProperties();
        p.setUsersBasePath(tmp.toString());
        p.getHttp().setAllowedDomains(List.of("example.com"));
        return p;
    }

    @Test
    @DisplayName("GET /profiles 的 token 是 ***，非明文")
    @SuppressWarnings("unchecked")
    void tokenIsMasked(@TempDir Path tmp) throws Exception {
        UserContextHolder.setCurrentUser("alice");
        LoomAgentProperties props = props(tmp);

        var p = new cn.wubo.loom.http.core.profile.Profile();
        p.setName("prod");
        p.getAuth().setType("bearer");   // addProfile 跑 ProfileValidator,type 必填
        p.getAuth().setToken(SECRET);
        HttpRouterSupport.engineFor("alice", props).addProfile(p);

        RouterFunction<ServerResponse> router =
                new HttpProfileRouter().httpProfileRouter(guardGranting(HttpManageGuard.GROUP), props);

        ServerResponse response = invoke(router, LIST_URI);
        assertThat(response.statusCode().value()).isEqualTo(200);

        List<Map<String, Object>> body =
                (List<Map<String, Object>>) ((EntityResponse<?>) response).entity();

        assertThat(body).hasSize(1);
        assertThat(body.get(0)).containsEntry("name", "prod");
        Map<String, Object> auth = (Map<String, Object>) body.get(0).get("auth");
        assertThat(auth).containsEntry("token", HttpRouterSupport.MASK);
        assertThat(auth.get("token")).isNotEqualTo(SECRET);
    }

    @Test
    @DisplayName("未授权用户 GET /profiles → 403")
    void unauthorizedGets403(@TempDir Path tmp) throws Exception {
        UserContextHolder.setCurrentUser("mallory");
        RouterFunction<ServerResponse> router =
                new HttpProfileRouter().httpProfileRouter(guardGranting(), props(tmp));

        ServerResponse response = invoke(router, LIST_URI);

        assertThat(response.statusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("未登录（username 为 null）→ 403，不泄露任何 profile")
    void anonymousGets403(@TempDir Path tmp) throws Exception {
        UserContextHolder.clear();   // 不设置 —— 模拟未登录
        RouterFunction<ServerResponse> router =
                new HttpProfileRouter().httpProfileRouter(guardGranting(HttpManageGuard.GROUP), props(tmp));

        ServerResponse response = invoke(router, LIST_URI);

        assertThat(response.statusCode().value()).isEqualTo(403);
    }
}
