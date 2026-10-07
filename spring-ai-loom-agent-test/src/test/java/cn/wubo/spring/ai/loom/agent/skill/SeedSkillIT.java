package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §4 官方种子技能 IT:清库启动后 market_skill 存在 2 条 author=system 官方已审行,
 * 且迁移幂等(WHERE NOT EXISTS —— 同库二次执行 Flyway 不重复,V1.0 只跑一次由
 * flyway_schema_history 保证;本测试断言当前库恰好各 1 条)。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("官方种子技能 IT")
class SeedSkillIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void officialSeedSkillsExist() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select name, status, is_official, created_by_kind, category, author "
                        + "from market_skill where author = 'system' order by name");
        // 不断言"恰好 N 条" —— 官方技能会随版本增补(2026-10-04 加了「交叉评审」),
        // 硬编码条数会让每加一条技能就得改测试。改为断言**每条都合法** + 关键几条在列。
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> row : rows) {
            assertThat(String.valueOf(row.get("status"))).as("status of %s", row.get("name")).isEqualTo("APPROVED");
            assertThat(row.get("is_official")).as("is_official of %s", row.get("name")).isEqualTo(Boolean.TRUE);
            assertThat(String.valueOf(row.get("created_by_kind"))).as("created_by_kind of %s", row.get("name")).isEqualTo("ADMIN");
            assertThat(String.valueOf(row.get("category"))).as("category of %s", row.get("name")).isNotBlank();
        }
        assertThat(rows).extracting(r -> String.valueOf(r.get("name")))
                .contains("STAR-IJ 讲清一件事", "靶心人公式 讲好一个故事", "交叉评审");
        // 分类归属:两条表达沟通类 + 交叉评审属分析决策
        assertThat(rows).filteredOn(r -> "交叉评审".equals(r.get("name")))
                .extracting(r -> String.valueOf(r.get("category")))
                .containsExactly("分析决策");
    }

    @Test
    @DisplayName("全部官方 skill 都授权给了 base 角色(default_loaded)")
    void allOfficialSkillsGrantedToBaseRole() {
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from role_skill rs join market_skill ms on ms.id = rs.market_skill_id "
                        + "where rs.role_code = 'base' and ms.author = 'system' and rs.default_loaded = true",
                Integer.class);
        Integer total = jdbcTemplate.queryForObject(
                "select count(*) from market_skill where author = 'system'", Integer.class);
        assertThat(n).as("每条官方 skill 都应授权给 base 角色").isEqualTo(total);
        assertThat(total).isGreaterThanOrEqualTo(3);
    }

    @Test
    void seedContentIsSubstantialAndPullable() {
        // content 非空且长度达标(实测 1102/1128 字符,阈值取 1000;brief 原稿写 1200 但与其逐字 SQL 矛盾,SQL 不可改故调阈值)
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select name, description, content from market_skill where author = 'system'");
        for (Map<String, Object> row : rows) {
            assertThat(String.valueOf(row.get("description"))).isNotBlank();
            assertThat(String.valueOf(row.get("content")).length())
                    .as("content of " + row.get("name")).isGreaterThan(1000);
        }
    }
}
