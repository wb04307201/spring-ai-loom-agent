package cn.wubo.spring.ai.loom.agent.rbac;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * DEFECT-Q3-1 回归锁(第四轮全面测试发现):
 * {@link DefaultRoleService#delete(String)} 的显式级联清理清单(B.2.1 fix)只覆盖
 * user_role / role_mcp / role_tool,漏了后加的两张授权子表 role_skill(M4 技能授权)
 * 与 loom_role_knowledge(知识库市场授权),且两表在 V1.0 中也没有 FK ON DELETE CASCADE。
 *
 * <p>后果(已活体复现):删角色后 role_skill 残留 dangling 行;用同一 code 重建角色时,
 * 陈旧技能/知识库授权静默复活并同步给新成员(role_skill → user_skill ROLE_GRANTED)。
 *
 * <p>本测试锁死 delete() 必须对全部 5 张子表 + role 本表发 DELETE;
 * 真 DB 的复活场景回归见 RoleDeleteCascadeIT。
 */
@DisplayName("DefaultRoleService.delete 级联清理清单(DEFECT-Q3-1)")
class DefaultRoleServiceDeleteCascadeTest {

    private JdbcTemplate jdbcTemplate;
    private DefaultRoleService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new DefaultRoleService(jdbcTemplate, mock(IMcpServerAdmin.class));
    }

    @Test
    @DisplayName("delete() 清理全部 5 张子表 + role 本表")
    void deleteCleansAllChildTables() {
        service.delete("temp-role");

        // B.2.1 既有三张
        verify(jdbcTemplate).update("DELETE FROM user_role WHERE role_code = ?", "temp-role");
        verify(jdbcTemplate).update("DELETE FROM role_mcp WHERE role_code = ?", "temp-role");
        verify(jdbcTemplate).update("DELETE FROM role_tool WHERE role_code = ?", "temp-role");
        // DEFECT-Q3-1 修复补上的两张授权子表
        verify(jdbcTemplate).update("DELETE FROM role_skill WHERE role_code = ?", "temp-role");
        verify(jdbcTemplate).update("DELETE FROM loom_role_knowledge WHERE role_code = ?", "temp-role");
        // role 本表(is_system=FALSE 守卫)
        verify(jdbcTemplate).update("DELETE FROM role WHERE code = ? AND is_system = FALSE", "temp-role");
    }
}
