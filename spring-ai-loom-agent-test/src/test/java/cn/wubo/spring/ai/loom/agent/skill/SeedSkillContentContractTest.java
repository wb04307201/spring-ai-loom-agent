package cn.wubo.spring.ai.loom.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §4 官方种子技能内容契约(spec 2026-09-08-askuser-followups-design.md §4.2 三铁律):
 * ① 不硬编码工具名(种子段不得出现 askUser 字面量 —— 描述"提问能力",LLM 从
 *    function schema 自选,Spring AI 按方法名注册,写死易与实际注册名不符);
 * ② 防 qwen 自白死循环(必须含反自白 + "不得代替用户作答" + "立即汇总"纪律);
 * ③ 纯文本(不得含裸 HTML 标签)。
 * 另锁:官方已审字段(author=system / APPROVED / is_official / ADMIN / 表达沟通)+ 幂等 WHERE NOT EXISTS。
 */
@DisplayName("V1.0 官方种子技能契约")
class SeedSkillContentContractTest {

    private static final String MARKER = "官方种子技能：一问一答表达训练";

    private String seedSegment() throws IOException {
        try (var in = getClass().getResourceAsStream("/db/migration/V1.0__init.sql")) {
            assertThat(in).isNotNull();
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int idx = sql.indexOf(MARKER);
            assertThat(idx).as("V1.0 必须含种子段 marker: " + MARKER).isGreaterThan(0);
            return sql.substring(idx);
        }
    }

    @Test
    void bothSkillsSeededAsOfficialApproved() throws IOException {
        String seg = seedSegment();
        assertThat(seg).contains("'STAR-IJ 讲清一件事'");
        assertThat(seg).contains("'靶心人公式 讲好一个故事'");
        assertThat(seg).contains("'system', 'APPROVED'");
        assertThat(seg).contains("'ADMIN', '表达沟通'");
        // 幂等:每条 INSERT 都带 WHERE NOT EXISTS
        assertThat(seg).contains("WHERE NOT EXISTS (SELECT 1 FROM market_skill WHERE author = 'system' AND name = 'STAR-IJ 讲清一件事')");
        assertThat(seg).contains("WHERE NOT EXISTS (SELECT 1 FROM market_skill WHERE author = 'system' AND name = '靶心人公式 讲好一个故事')");
    }

    @Test
    void noHardcodedToolNames() throws IOException {
        // 铁律①:种子段不得出现 askUser 字面量(工具名从 function schema 自选)
        assertThat(seedSegment()).doesNotContain("askUser");
    }

    @Test
    void antiSelfTalkDisciplinePresent() throws IOException {
        String seg = seedSegment();
        // 铁律②:三条纪律逐字锁死(两个技能都必须有)
        assertThat(seg).contains("不要描述你打算做什么");
        assertThat(seg).contains("不得代替用户作答");
        assertThat(seg).contains("信息足够时立即进入汇总");
    }

    @Test
    void plainTextNoRawHtml() throws IOException {
        // 铁律③:纯文本,无裸 HTML 标签(链接用 markdown)
        assertThat(seedSegment()).doesNotContain("<a href");
        assertThat(seedSegment()).doesNotContain("<div");
        assertThat(seedSegment()).doesNotContain("<br");
    }

    @Test
    void starIjHasSixStepsAndTargetHasSeven() throws IOException {
        String seg = seedSegment();
        // STAR-IJ 六步维度名
        for (String dim : new String[]{"情境", "任务", "行动", "结果", "项目价值", "个人成长"}) {
            assertThat(seg).as("STAR-IJ 必含维度: " + dim).contains(dim);
        }
        // 靶心人七步(原词"转弯"非"转折",spec D7)
        for (String step : new String[]{"目标", "阻碍", "努力", "意外", "转弯", "结局"}) {
            assertThat(seg).as("靶心人必含步骤: " + step).contains(step);
        }
        assertThat(seg).contains("努力人公式");
        assertThat(seg).contains("意外人公式");
    }
}
