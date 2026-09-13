package cn.wubo.spring.ai.loom.agent.rbac;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.model.RoleSkillItem;
import cn.wubo.spring.ai.loom.agent.skill.ISkillRoleAdmin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEFECT-Q3-1 真 DB 回归(第四轮全面测试发现的"陈旧授权复活"场景):
 * 删角色 → role_skill 必须随 role 一起清掉;用同一 code 重建角色 →
 * 新用户不得通过 role_skill→user_skill 自动同步继承旧授权。
 *
 * <p>缺陷历史:DefaultRoleService.delete() 的 B.2.1 显式清理只覆盖
 * user_role/role_mcp/role_tool,V1.0 FK CASCADE 也只覆盖那三表 →
 * role_skill dangling 行在角色 code 复用时被自动同步捡走,授权复活。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("角色删除级联 IT(DEFECT-Q3-1)")
class RoleDeleteCascadeIT {

    @Autowired
    private IRoleService roleService;

    @Autowired
    private ISkillRoleAdmin skillRoleAdmin;

    @Autowired
    private cn.wubo.spring.ai.loom.agent.skill.ISkillStorage skillStorage;

    @Autowired
    private JdbcTemplate jdbc;

    private static final String ROLE = "q3-cascade-it";

    private void cleanup() {
        jdbc.update("DELETE FROM user_skill WHERE username = 'q3-user' AND source = 'ROLE_GRANTED'");
        jdbc.update("DELETE FROM user_role WHERE role_code = ?", ROLE);
        jdbc.update("DELETE FROM role_skill WHERE role_code = ?", ROLE);
        jdbc.update("DELETE FROM role_mcp WHERE role_code = ?", ROLE);
        jdbc.update("DELETE FROM role WHERE code = ?", ROLE);
        jdbc.update("DELETE FROM user_info WHERE username = 'q3-user'");
    }

    @Test
    @DisplayName("删角色清 role_skill;重建同 code 后新用户不继承旧授权")
    void deleteRolePurgesSkillGrantsAndRecreateDoesNotResurrect() {
        cleanup();
        try {
            // 官方种子技能 id(V1.0 种子,清库 IT gate 必然存在)
            Long seedSkillId = jdbc.queryForObject(
                    "SELECT id FROM market_skill WHERE author='system' AND name='STAR-IJ 讲清一件事'",
                    Long.class);
            assertThat(seedSkillId).isNotNull();

            // 1. 建角色 + 授权种子技能
            roleService.create(ROLE, "Q3级联IT", "delete cascade regression", null);
            skillRoleAdmin.setRoleSkills(ROLE, List.of(new RoleSkillItem(seedSkillId, true)));
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM role_skill WHERE role_code = ?", Integer.class, ROLE))
                    .isEqualTo(1);

            // 2. 删角色 → role 与 role_skill 都必须清 0(dangling 即缺陷)
            roleService.deleteOrThrow(ROLE);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM role WHERE code = ?", Integer.class, ROLE)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM role_skill WHERE role_code = ?", Integer.class, ROLE))
                    .as("role_skill 必须随角色删除清掉(DEFECT-Q3-1)")
                    .isZero();

            // 3. 重建同 code(不授权任何技能)+ 新用户分配
            jdbc.update("INSERT INTO user_info (username, nickname, password, type) VALUES ('q3-user','Q3','x','USER')");
            roleService.create(ROLE, "Q3级联IT重建", "recreated", null);
            roleService.setUserRoles("q3-user", List.of(ROLE));

            // 4. 触发 role_skill→user_skill 自动同步(ISkillStorage.sync,复活场景的实际路径)
            skillStorage.sync("q3-user");
            List<String> granted = jdbc.queryForList(
                    "SELECT name FROM user_skill WHERE username='q3-user' AND source='ROLE_GRANTED'",
                    String.class);
            assertThat(granted)
                    .as("重建同 code 角色不得复活旧技能授权(DEFECT-Q3-1 复活场景)")
                    .doesNotContain("STAR-IJ 讲清一件事");
        } finally {
            cleanup();
        }
    }
}
