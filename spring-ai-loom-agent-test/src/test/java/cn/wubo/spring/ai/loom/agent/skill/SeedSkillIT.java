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
    void twoOfficialSeedSkillsExist() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select name, status, is_official, created_by_kind, category, author "
                        + "from market_skill where author = 'system' order by name");
        assertThat(rows).hasSize(2);
        for (Map<String, Object> row : rows) {
            assertThat(String.valueOf(row.get("status"))).isEqualTo("APPROVED");
            assertThat(row.get("is_official")).isEqualTo(Boolean.TRUE);
            assertThat(String.valueOf(row.get("created_by_kind"))).isEqualTo("ADMIN");
            assertThat(String.valueOf(row.get("category"))).isEqualTo("表达沟通");
        }
        assertThat(rows).extracting(r -> String.valueOf(r.get("name")))
                .containsExactly("STAR-IJ 讲清一件事", "靶心人公式 讲好一个故事");
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
