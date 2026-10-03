package cn.wubo.spring.ai.loom.agent.web.http;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import cn.wubo.spring.ai.loom.agent.testutil.LoomAgentTestUtil;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 7 IT — Profile write surface + read-side masking contract (R2).
 *
 * <p>Lock-down: a profile created with a bearer token MUST NOT have that token
 * leak into any subsequent GET response; the auth.token field MUST be replaced
 * by the static mask. Likewise for auth.password and auth.value.
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("HTTP profile 路由 IT —— 写面 + 读面脱敏")
class HttpProfileRouterIT {

    @Autowired private IRoleService roleService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoomAgentProperties properties;

    private HttpManageGuard guard;

    private static final String USER = "http-profile-user";
    private static final String ROLE = "http-profile-role";

    @TempDir
    static Path tmpBase;

    @BeforeEach
    void setUp() {
        UserContextHolder.setCurrentUser(USER);
        guard = new HttpManageGuard(roleService);
        // redirect usersBasePath to a tmp dir for isolation
        properties.setUsersBasePath(tmpBase.toString());
        // ensure user + role + grant
        Integer userExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_info WHERE username = ?", Integer.class, USER);
        if (userExists == null || userExists == 0) {
            jdbc.update("INSERT INTO user_info(username, nickname, password, type) VALUES (?, ?, ?, ?)",
                    USER, "profile-it", "x", "USER");
        }
        Integer roleExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role WHERE code = ?", Integer.class, ROLE);
        if (roleExists == null || roleExists == 0) {
            jdbc.update("INSERT INTO role(code, name, is_system, description) VALUES (?, ?, FALSE, ?)",
                    ROLE, "profile-role", "test");
        }
        jdbc.update("DELETE FROM role_tool WHERE role_code = ?", ROLE);
        jdbc.update("INSERT INTO role_tool(role_code, group_name, sort_order, default_enabled) VALUES (?, ?, 0, TRUE)",
                ROLE, HttpManageGuard.GROUP);
        jdbc.update("DELETE FROM user_role WHERE username = ?", USER);
        jdbc.update("INSERT INTO user_role(role_code, username) VALUES (?, ?)", ROLE, USER);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        jdbc.update("DELETE FROM user_role WHERE username = ?", USER);
        jdbc.update("DELETE FROM role_tool WHERE role_code = ?", ROLE);
        jdbc.update("DELETE FROM role WHERE code = ?", ROLE);
        jdbc.update("DELETE FROM user_info WHERE username = ?", USER);
    }

    private RouterFunction<ServerResponse> router() {
        return new HttpProfileRouter().httpProfileRouter(guard, properties);
    }

    @Test
    @DisplayName("写面创建含 bearer token 的 profile → GET 列表的 auth.token=***，原文绝不出现")
    void createThenListMasksBearerToken() throws Exception {
        String createBody = """
                {
                  "name":"prod",
                  "description":"prod profile",
                  "baseUrl":"https://api.example.com",
                  "auth":{"type":"bearer","token":"super-secret-token-XYZ"}
                }
                """;
        ServerResponse created = LoomAgentTestUtil.safeRoute(router(), "POST",
                "/spring/ai/loom/api/http/profiles", createBody);
        assertThat(created).isNotNull();
        assertThat(created.statusCode().value()).isEqualTo(200);

        ServerResponse list = LoomAgentTestUtil.safeRoute(router(), "GET",
                "/spring/ai/loom/api/http/profiles", null);
        assertThat(list).isNotNull();
        assertThat(list.statusCode().value()).isEqualTo(200);
        String body = String.valueOf(((org.springframework.web.servlet.function.EntityResponse<?>) list).entity());
        assertThat(body)
                .as("list 响应 body 不应泄漏 token 原文")
                .doesNotContain("super-secret-token-XYZ");
        assertThat(body)
                .as("list 响应 body 必须含脱敏标记")
                .contains("***");
    }

    @Test
    @DisplayName("未登录 → 创建 / 删除均 403")
    void unauthenticatedWritesAreForbidden() throws Exception {
        UserContextHolder.clear();
        ServerResponse post = LoomAgentTestUtil.safeRoute(router(), "POST",
                "/spring/ai/loom/api/http/profiles",
                "{\"name\":\"p\",\"baseUrl\":\"https://x.example.com\"}");
        assertThat(post).isNotNull();
        assertThat(post.statusCode().value()).isEqualTo(403);

        ServerResponse del = LoomAgentTestUtil.safeRoute(router(), "DELETE",
                "/spring/ai/loom/api/http/profiles/p", null);
        assertThat(del).isNotNull();
        assertThat(del.statusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("name 缺失 → 400")
    void missingNameReturns400() throws Exception {
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router(), "POST",
                "/spring/ai/loom/api/http/profiles", "{\"baseUrl\":\"https://x.example.com\"}");
        assertThat(resp).isNotNull();
        assertThat(resp.statusCode().value()).isEqualTo(400);
    }

    // ===== I-2: path-variable traversal 校验 =====

    @Test
    @DisplayName("DELETE path-variable 含 '..' → 400 + InvalidPathVariable(防目录穿越)")
    void deleteWithTraversalPathReturns400() throws Exception {
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router(), "DELETE",
                "/spring/ai/loom/api/http/profiles/..%2F..%2Fetc%2Fpasswd", null);
        assertThat(resp).isNotNull();
        assertThat(resp.statusCode().value()).isEqualTo(400);
        String body = String.valueOf(((org.springframework.web.servlet.function.EntityResponse<?>) resp).entity());
        assertThat(body).contains("InvalidPathVariable");
    }

    @Test
    @DisplayName("PUT path-variable 含 '/' → 400 + InvalidPathVariable")
    void putWithSlashInNameReturns400() throws Exception {
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router(), "PUT",
                "/spring/ai/loom/api/http/profiles/sub%2Fdir", "{\"description\":\"x\"}");
        assertThat(resp).isNotNull();
        assertThat(resp.statusCode().value()).isEqualTo(400);
        String body = String.valueOf(((org.springframework.web.servlet.function.EntityResponse<?>) resp).entity());
        assertThat(body).contains("InvalidPathVariable");
    }

    @Test
    @DisplayName("合法 name 正常通过校验(校验不该误伤合法字符)")
    void legitimateNamePassesValidation() throws Exception {
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router(), "DELETE",
                "/spring/ai/loom/api/http/profiles/normal-name_123", null);
        assertThat(resp).isNotNull();
        // 校验通过 → 进入 engine → name 不存在 → engine 返回 error JSON,200 状态
        assertThat(resp.statusCode().value()).isEqualTo(200);
    }
}
