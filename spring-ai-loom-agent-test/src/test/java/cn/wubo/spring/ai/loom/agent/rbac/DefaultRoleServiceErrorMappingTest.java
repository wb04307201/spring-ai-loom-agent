package cn.wubo.spring.ai.loom.agent.rbac;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins that the service layer translates "FK target missing" into a 4xx
 * {@link LoomAgentRuntimeException} instead of letting
 * {@link DataIntegrityViolationException} escape as a 500.
 *
 * <p>Bug history: M6 end-to-end testing (2026-08-31) found two paths where the
 * router + service let the JDBC {@code DataIntegrityViolationException} propagate,
 * so admin endpoints returned 500 instead of 400:
 * <ul>
 *   <li>{@code PUT /admin/users/{u}/roles} body with non-existent role code</li>
 *   <li>{@code PUT /admin/roles/{code}/tools} for a non-existent role</li>
 * </ul>
 *
 * <p>The router already maps {@code LoomAgentRuntimeException.getStatusCode()}
 * to the HTTP response (see {@code RoleRouterTest}); this test only needs to
 * pin the service-layer contract.
 */
class DefaultRoleServiceErrorMappingTest {

    private JdbcTemplate jdbcTemplate;
    private DefaultRoleService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        IMcpServerAdmin mcpAdmin = mock(IMcpServerAdmin.class);
        service = new DefaultRoleService(jdbcTemplate, mcpAdmin);
    }

    @Nested
    @DisplayName("setUserRoles")
    class SetUserRoles {

        @Test
        @DisplayName("不存在的 user → LoomAgentRuntimeException(400, 用户不存在)")
        void missingUser_throws400() {
            // findUserType → EmptyResultDataAccessException → 服务返回 null → 抛 LoomAgentRuntimeException
            when(jdbcTemplate.queryForObject(eq("SELECT type FROM user_info WHERE username = ?"),
                    eq(String.class), any(Object[].class)))
                    .thenThrow(new EmptyResultDataAccessException(1));

            assertThatThrownBy(() -> service.setUserRoles("ghost", List.of("dev")))
                    .isInstanceOf(LoomAgentRuntimeException.class)
                    .satisfies(e -> assertThat(((LoomAgentRuntimeException) e).getStatusCode()).isEqualTo(400));
        }

        @Test
        @DisplayName("user 存在 + role_code 不存在 → 不应抛 DataIntegrityViolation(500),应抛 LoomAgentRuntimeException(400)")
        void missingRoleCode_throws400NotDataIntegrity() {
            // user 存在
            when(jdbcTemplate.queryForObject(eq("SELECT type FROM user_info WHERE username = ?"),
                    eq(String.class), any(Object[].class)))
                    .thenReturn("USER");
            // role 不存在(roleExists → COUNT=0)
            when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM role WHERE code = ?"),
                    eq(Integer.class), any(Object[].class)))
                    .thenReturn(0);

            assertThatThrownBy(() -> service.setUserRoles("alice", List.of("no_such_role")))
                    .isInstanceOf(LoomAgentRuntimeException.class)
                    .satisfies(e -> assertThat(((LoomAgentRuntimeException) e).getStatusCode()).isEqualTo(400));
        }

        @Test
        @DisplayName("user 存在 + 合法 role → 正常(无异常)")
        void happyPath_noException() {
            when(jdbcTemplate.queryForObject(eq("SELECT type FROM user_info WHERE username = ?"),
                    eq(String.class), any(Object[].class)))
                    .thenReturn("USER");
            when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM role WHERE code = ?"),
                    eq(Integer.class), any(Object[].class)))
                    .thenReturn(1);

            service.setUserRoles("alice", List.of("dev"));

            verify(jdbcTemplate).update(eq("DELETE FROM user_role WHERE username = ?"), eq("alice"));
            verify(jdbcTemplate).update(eq("INSERT INTO user_role (username, role_code) VALUES (?, ?)"),
                    eq("alice"), eq("dev"));
        }
    }

    @Nested
    @DisplayName("setRoleTools")
    class SetRoleTools {

        @Test
        @DisplayName("role code 不存在 → 不应抛 DataIntegrityViolation(500),应抛 LoomAgentRuntimeException(404)")
        void missingRole_throws4xxNotDataIntegrity() {
            when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM role WHERE code = ?"),
                    eq(Integer.class), any(Object[].class)))
                    .thenReturn(0);

            assertThatThrownBy(() -> service.setRoleTools("no_such_role",
                    List.of(new IRoleService.RoleToolItem("tool_git", true))))
                    .isInstanceOf(LoomAgentRuntimeException.class)
                    .satisfies(e -> assertThat(((LoomAgentRuntimeException) e).getStatusCode()).isEqualTo(404));
        }

        @Test
        @DisplayName("role 存在 + 合法 tool → 正常")
        void happyPath_noException() {
            when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM role WHERE code = ?"),
                    eq(Integer.class), any(Object[].class)))
                    .thenReturn(1);

            service.setRoleTools("dev", List.of(
                    new IRoleService.RoleToolItem("tool_git", true),
                    new IRoleService.RoleToolItem("tool_maven", false)));

            verify(jdbcTemplate).update(eq("DELETE FROM role_tool WHERE role_code = ?"), eq("dev"));
            verify(jdbcTemplate).update(eq("INSERT INTO role_tool (role_code, group_name, sort_order, default_enabled) VALUES (?, ?, ?, ?)"),
                    eq("dev"), eq("tool_git"), eq(0), eq(true));
            verify(jdbcTemplate).update(eq("INSERT INTO role_tool (role_code, group_name, sort_order, default_enabled) VALUES (?, ?, ?, ?)"),
                    eq("dev"), eq("tool_maven"), eq(1), eq(false));
        }

        @Test
        @DisplayName("role 存在 + items=null → 只 DELETE 不 INSERT(不应抛任何异常)")
        void nullItems_noException() {
            when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM role WHERE code = ?"),
                    eq(Integer.class), any(Object[].class)))
                    .thenReturn(1);

            service.setRoleTools("dev", null);

            verify(jdbcTemplate).update(eq("DELETE FROM role_tool WHERE role_code = ?"), eq("dev"));
            verify(jdbcTemplate, never()).update(eq("INSERT INTO role_tool (role_code, group_name, sort_order, default_enabled) VALUES (?, ?, ?, ?)"),
                    anyString(), anyString(), any(Integer.class), any(Boolean.class));
        }
    }

    @Nested
    @DisplayName("setUserRolesOrSkipAdmin")
    class SetUserRolesOrSkipAdmin {

        @Test
        @DisplayName("不存在的 user → 400")
        void missingUser_throws400() {
            when(jdbcTemplate.queryForObject(eq("SELECT type FROM user_info WHERE username = ?"),
                    eq(String.class), any(Object[].class)))
                    .thenThrow(new EmptyResultDataAccessException(1));

            assertThatThrownBy(() -> service.setUserRolesOrSkipAdmin("ghost", List.of()))
                    .isInstanceOf(LoomAgentRuntimeException.class)
                    .satisfies(e -> assertThat(((LoomAgentRuntimeException) e).getStatusCode()).isEqualTo(400));
        }

        @Test
        @DisplayName("user 存在 + 不存在 role_code → 400")
        void missingRoleCode_throws400() {
            when(jdbcTemplate.queryForObject(eq("SELECT type FROM user_info WHERE username = ?"),
                    eq(String.class), any(Object[].class)))
                    .thenReturn("USER");
            when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM role WHERE code = ?"),
                    eq(Integer.class), any(Object[].class)))
                    .thenReturn(0);

            assertThatThrownBy(() -> service.setUserRolesOrSkipAdmin("alice", List.of("no_such_role")))
                    .isInstanceOf(LoomAgentRuntimeException.class)
                    .satisfies(e -> assertThat(((LoomAgentRuntimeException) e).getStatusCode()).isEqualTo(400));
        }
    }
}