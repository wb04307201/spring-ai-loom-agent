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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.servlet.function.ServerResponse.ok;

/**
 * Task 7 IT — RBAC guard semantics for the HTTP write surface.
 *
 * <p><b>RBAC contract</b> (R1): {@link HttpManageGuard#isAllowed} MUST return
 * {@code true} only when the user has {@link HttpManageGuard#GROUP}
 * ({@code tool_http_manage}) in {@link IRoleService#getVisibleToolsForUser}
 * for their username. Anonymous → 403; unauthorized → 403; granted → allow.
 *
 * <p><b>Why direct router invocation, not MockMvc</b>: the project's
 * AuthenticationFilter is cookie-based and depends on the full servlet stack;
 * router tests in this repo use {@link LoomAgentTestUtil#safeRoute} with
 * {@link UserContextHolder#setCurrentUser} set in {@code @BeforeEach} — the
 * established pattern (see {@code AskUserRouterTest}, {@code AdminRouterSpotTest}).
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("HTTP 写面 RBAC IT —— 未授权必须 403")
class HttpManageRbacIT {

    @Autowired private IRoleService roleService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoomAgentProperties properties;

    /** Constructed in {@code @BeforeEach}; matches {@code AdminRouterSpotTest} pattern. */
    private HttpManageGuard guard;

    private static final String USER = "http-rbac-user";
    private static final String ROLE = "http-rbac-role";

    @BeforeEach
    void setUp() {
        UserContextHolder.setCurrentUser(USER);
        guard = new HttpManageGuard(roleService);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        revokeAll();
        jdbc.update("DELETE FROM role_tool WHERE role_code = ?", ROLE);
        jdbc.update("DELETE FROM role WHERE code = ?", ROLE);
        jdbc.update("DELETE FROM user_info WHERE username = ?", USER);
    }

    private void revokeAll() {
        jdbc.update("DELETE FROM user_role WHERE username = ?", USER);
    }

    /** H2-safe grant (no {@code ON DUPLICATE KEY UPDATE} — H2 doesn't support it). */
    private void grantManage() {
        // 1. ensure user exists
        Integer userExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_info WHERE username = ?", Integer.class, USER);
        if (userExists == null || userExists == 0) {
            jdbc.update("INSERT INTO user_info(username, nickname, password, type) VALUES (?, ?, ?, ?)",
                    USER, "rbac", "x", "USER");
        }
        // 2. ensure role exists (is_system=FALSE so delete won't refuse later)
        Integer roleExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role WHERE code = ?", Integer.class, ROLE);
        if (roleExists == null || roleExists == 0) {
            jdbc.update("INSERT INTO role(code, name, is_system, description) VALUES (?, ?, FALSE, ?)",
                    ROLE, "rbac-role", "test");
        }
        // 3. clean & insert role_tool + user_role
        jdbc.update("DELETE FROM role_tool WHERE role_code = ?", ROLE);
        jdbc.update("INSERT INTO role_tool(role_code, group_name, sort_order, default_enabled) VALUES (?, ?, 0, TRUE)",
                ROLE, HttpManageGuard.GROUP);
        jdbc.update("DELETE FROM user_role WHERE username = ?", USER);
        jdbc.update("INSERT INTO user_role(role_code, username) VALUES (?, ?)", ROLE, USER);
    }

    @Test
    @DisplayName("授权后 guard 放行（直接验 guard 语义，不依赖 servlet stack）")
    void grantedUserPassesGuard() {
        revokeAll();
        assertThat(guard.isAllowed(USER)).isFalse();
        grantManage();
        assertThat(guard.isAllowed(USER)).isTrue();
    }

    @Test
    @DisplayName("匿名（UserContextHolder 无用户）→ guard 拒绝")
    void anonymousIsForbidden() {
        UserContextHolder.clear();
        assertThat(guard.isAllowed(null)).isFalse();
    }

    @Test
    @DisplayName("空用户名 → guard 拒绝")
    void blankUsernameIsForbidden() {
        assertThat(guard.isAllowed("")).isFalse();
        assertThat(guard.isAllowed("   ")).isFalse();
    }

    @Test
    @DisplayName("授权用户调 router 的 GET /api/http/profiles → 200；未授权 → 403")
    void routerEnforcesGuard() throws Exception {
        // Build router manually with the real guard + properties
        RouterFunction<ServerResponse> profileRouter = new HttpProfileRouter()
                .httpProfileRouter(guard, properties);
        RouterFunction<ServerResponse> systemRouter = new HttpSystemRouter()
                .httpSystemRouter(guard, properties);

        // Unauthenticated path: no UserContextHolder
        UserContextHolder.clear();
        ServerResponse anon = LoomAgentTestUtil.safeRoute(profileRouter, "GET",
                "/spring/ai/loom/api/http/profiles", null);
        assertThat(anon).isNotNull();
        assertThat(anon.statusCode().value()).isEqualTo(403);

        // Unauthorized path: USER logged in but no role
        UserContextHolder.setCurrentUser(USER);
        revokeAll();
        ServerResponse noGrant = LoomAgentTestUtil.safeRoute(profileRouter, "GET",
                "/spring/ai/loom/api/http/profiles", null);
        assertThat(noGrant).isNotNull();
        assertThat(noGrant.statusCode().value()).isEqualTo(403);

        // Granted path: role + tool_http_manage → 200
        grantManage();
        ServerResponse granted = LoomAgentTestUtil.safeRoute(profileRouter, "GET",
                "/spring/ai/loom/api/http/profiles", null);
        assertThat(granted).isNotNull();
        assertThat(granted.statusCode().value()).isEqualTo(200);

        // Granted but on the system router too
        ServerResponse grantedSys = LoomAgentTestUtil.safeRoute(systemRouter, "GET",
                "/spring/ai/loom/api/http/systems", null);
        assertThat(grantedSys).isNotNull();
        assertThat(grantedSys.statusCode().value()).isEqualTo(200);

        // Silence unused warning on ok() reference — it's here to keep
        // the import set explicit and remind future readers of the
        // ServerResponse body shape contract.
        assertThat(ok()).isNotNull();
        // explicit Map import smoke
        assertThat(Map.of("k", "v")).containsEntry("k", "v");
        assertThat(List.of()).isEmpty();
    }

    /**
     * F1 回归锁:授权必须走得到「控制台保存」这条路,且保存不会抹掉它。
     *
     * <p>{@code setRoleTools} 是 {@code DELETE + INSERT} 全量替换,而
     * {@code roles.js} 只提交 {@code /admin/capabilities} 渲染出的组 ——
     * 在 {@code tool_http_manage} 补上 {@code @ToolGroup} 之前,它既不在渲染列表里,
     * 也不在任何 group 校验之外,于是「用 SQL 授了权 → 控制台保存一次 → 授权消失」。
     * 这里用真实的 {@code IRoleService.setRoleTools}(控制台 PUT 的同一实现)授权,
     * 再模拟一次控制台保存(提交当前可见组全集),断言授权仍在、guard 仍放行。
     */
    @Test
    @DisplayName("经 setRoleTools 授权(控制台同路径)+ 再次保存后授权不丢")
    void grantViaRoleServiceSurvivesResave() {
        ensureRoleAndUser();
        revokeAll();

        // 1) 控制台首次保存:只勾了写面这一项
        roleService.setRoleTools(ROLE,
                List.of(new IRoleService.RoleToolItem(HttpManageGuard.GROUP, true)));
        jdbc.update("INSERT INTO user_role(role_code, username) VALUES (?, ?)", ROLE, USER);
        assertThat(guard.isAllowed(USER)).isTrue();

        // 2) 控制台再次保存:提交「当前可见组全集」——模拟把别的工具组也勾上后保存
        roleService.setRoleTools(ROLE, List.of(
                new IRoleService.RoleToolItem("tool_git", true),
                new IRoleService.RoleToolItem(HttpManageGuard.GROUP, true),
                new IRoleService.RoleToolItem("tool_http", true)));

        // 3) 授权仍在,且 guard 仍放行
        assertThat(roleService.getVisibleToolsForUser(USER))
                .contains(HttpManageGuard.GROUP, "tool_git", "tool_http");
        assertThat(guard.isAllowed(USER)).isTrue();

        // 4) 反向:控制台把写面取消勾选并保存 → 立即失权(不是只增不减的粘性授权)
        roleService.setRoleTools(ROLE,
                List.of(new IRoleService.RoleToolItem("tool_git", true)));
        assertThat(guard.isAllowed(USER)).isFalse();
    }

    /** 建 role + user 行(不碰 role_tool / user_role),供 setRoleTools 路径测试复用。 */
    private void ensureRoleAndUser() {
        Integer userExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_info WHERE username = ?", Integer.class, USER);
        if (userExists == null || userExists == 0) {
            jdbc.update("INSERT INTO user_info(username, nickname, password, type) VALUES (?, ?, ?, ?)",
                    USER, "rbac", "x", "USER");
        }
        Integer roleExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role WHERE code = ?", Integer.class, ROLE);
        if (roleExists == null || roleExists == 0) {
            jdbc.update("INSERT INTO role(code, name, is_system, description) VALUES (?, ?, FALSE, ?)",
                    ROLE, "rbac-role", "test");
        }
        jdbc.update("DELETE FROM role_tool WHERE role_code = ?", ROLE);
    }
}
