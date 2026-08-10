package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.model.RoleSkillItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DefaultSkillRoleAdmin} against H2.
 * Pins:
 * <ul>
 *   <li>setRoleSkills replaces all bindings (atomic replace).</li>
 *   <li>getRoleSkills returns ordered rows.</li>
 *   <li>setRoleSkills(null items) → clears bindings.</li>
 *   <li>default_loaded is normalized: null → true (default behavior).</li>
 * </ul>
 */
class DefaultSkillRoleAdminTest {

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private DefaultSkillRoleAdmin admin;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:role-skill-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new DriverManagerDataSource(url, "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE market_skill (
                id BIGINT PRIMARY KEY,
                name VARCHAR(128),
                description VARCHAR(512),
                content CLOB,
                author VARCHAR(64),
                status VARCHAR(32),
                submitted_at TIMESTAMP,
                reviewed_at TIMESTAMP,
                reviewed_by VARCHAR(64),
                review_comment CLOB
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE role_skill (
                role_code VARCHAR(64),
                market_skill_id BIGINT,
                sort_order INT,
                default_loaded BOOLEAN,
                PRIMARY KEY (role_code, market_skill_id)
            )
            """);
        // 2 个 market skills
        jdbcTemplate.update("insert into market_skill (id, name, status, submitted_at) values (?,?,?, CURRENT_TIMESTAMP)",
                1, "echo", "APPROVED");
        jdbcTemplate.update("insert into market_skill (id, name, status, submitted_at) values (?,?,?, CURRENT_TIMESTAMP)",
                2, "search", "APPROVED");
        admin = new DefaultSkillRoleAdmin(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        if (dataSource instanceof DriverManagerDataSource ds) {
            try { ds.getConnection().close(); } catch (Exception ignored) {}
        }
    }

    @Test
    void setRoleSkills_replaces_all_bindings_atomically() {
        admin.setRoleSkills("admin", List.of(
                new RoleSkillItem(1L, true),
                new RoleSkillItem(2L, false)
        ));
        assertThat(admin.getRoleSkills("admin"))
                .extracting(RoleSkillItem::marketSkillId, RoleSkillItem::defaultLoaded)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, true),
                        org.assertj.core.groups.Tuple.tuple(2L, false));

        // 重新设置 — 旧的应该被清空
        admin.setRoleSkills("admin", List.of(new RoleSkillItem(2L, true)));
        List<RoleSkillItem> after = admin.getRoleSkills("admin");
        assertThat(after).hasSize(1);
        assertThat(after.get(0).marketSkillId()).isEqualTo(2L);
        assertThat(after.get(0).defaultLoaded()).isTrue();
    }

    @Test
    void setRoleSkills_with_null_items_clears_bindings() {
        admin.setRoleSkills("admin", List.of(new RoleSkillItem(1L, true)));
        assertThat(admin.getRoleSkills("admin")).hasSize(1);

        admin.setRoleSkills("admin", null);
        assertThat(admin.getRoleSkills("admin")).isEmpty();
    }

    @Test
    void getRoleSkills_returns_empty_for_unknown_role() {
        assertThat(admin.getRoleSkills("nobody")).isEmpty();
    }

    @Test
    void setRoleSkills_normalizes_null_defaultLoaded_to_true() {
        // 显式 null defaultLoaded — 应被规范成 true（默认加载）
        admin.setRoleSkills("user", List.of(new RoleSkillItem(1L, null)));
        List<RoleSkillItem> rows = admin.getRoleSkills("user");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).defaultLoaded()).isTrue();
    }

    @Test
    void setRoleSkills_skips_null_or_invalid_items() {
        // 包含一个 null 和一个 marketSkillId 为 null 的 — 应被跳过
        admin.setRoleSkills("admin", java.util.Arrays.asList(
                new RoleSkillItem(null, true),
                null,
                new RoleSkillItem(1L, false)
        ));
        List<RoleSkillItem> rows = admin.getRoleSkills("admin");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).marketSkillId()).isEqualTo(1L);
    }
}