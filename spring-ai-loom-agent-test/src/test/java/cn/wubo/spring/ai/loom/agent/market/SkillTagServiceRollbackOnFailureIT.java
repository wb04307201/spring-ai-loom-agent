package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.skill.market.SkillTagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 T4 — {@link ReplaceTagsRollbackOnFailureIT} 的 skill 孪生:
 * {@link SkillTagService#replaceTags(String, Long, java.util.List)}
 * 必须回滚到调用前的状态(原 tag 不丢)。
 * <p>
 * {@code replaceTags} 带方法级 {@code @Transactional}:DELETE / addTags 在同一事务,
 * addTags 抛错触发整体回滚,原 tag 仍在 — 与 KB 端 MUST-FIX-2 ruling 一致。
 *
 * <p>触发 addTags 失败的方式:tag 字段是 {@code VARCHAR(64)},提供长度 > 64 的字符串
 * 让 H2 在 MERGE 时报 data integrity violation — 与生产环境 admin 误填超长 tag 路径一致。
 *
 * <p>走 service 直接验证(不走 router)—
 * router 的 RuntimeException catch 块会把 addTags 的 SQL error 翻译成 500,
 * 但 DB 端行为需要绕过 router,直接测 service 才看得出 rollback。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("M4 T4 — skill replaceTags 失败时回滚到原状态")
class SkillTagServiceRollbackOnFailureIT {

    @Autowired SkillTagService tagService;
    @Autowired JdbcTemplate jdbc;

    private Long skillId;
    private final List<Long> cleanupIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 每个测试用一个新 skill,避免 tag 行残留。
        String name = "rollback-skill-" + System.nanoTime();
        jdbc.update(
                "INSERT INTO market_skill (name, description, content, author, status, reviewed_at, reviewed_by) " +
                        "VALUES (?, ?, ?, ?, 'APPROVED', CURRENT_TIMESTAMP, ?)",
                name, "desc", "content", "author-t4-rollback", "author-t4-rollback");
        skillId = jdbc.queryForObject(
                "SELECT id FROM market_skill WHERE author = ? AND name = ?",
                Long.class, "author-t4-rollback", name);
        cleanupIds.add(skillId);
    }

    @AfterEach
    void tearDown() {
        // 清掉所有用过的 skill (CASCADE 自动清 tag 行)
        for (Long id : cleanupIds) {
            jdbc.update("DELETE FROM market_skill WHERE id = ?", id);
        }
        cleanupIds.clear();
    }

    @Test
    @DisplayName("replaceTags 中 addTags 失败 → 原 tag 仍存在(rollback)")
    void replaceTagsRollbackPreservesOriginalTagsOnFailure() {
        // 1. 灌一个原 tag
        tagService.addTags(SkillTagService.MARKET_KIND_SKILL, skillId,
                List.of("java"));
        List<String> before = tagService.listTags(
                SkillTagService.MARKET_KIND_SKILL, skillId);
        assertEquals(List.of("java"), before, "原 tag 必须存在");

        // 2. 构造一个会失败的 replaceTags 调用:新 list 含一个超长 tag (VARCHAR(64) 限)
        //    'a' x 100 必然超过 64 → MERGE 时 H2 报 integrity violation
        String tooLong = "a".repeat(100);
        List<String> newTags = List.of("spring", tooLong);

        // 3. 调用必须抛 DataAccessException
        DataAccessException ex = assertThrows(DataAccessException.class,
                () -> tagService.replaceTags(
                        SkillTagService.MARKET_KIND_SKILL, skillId, newTags),
                "超长 tag 必须触发 DataAccessException (VARCHAR(64) 违反)");
        assertNotNull(ex);

        // 4. 关键断言:rollback 后原 tag "java" 仍在
        List<String> after = tagService.listTags(
                SkillTagService.MARKET_KIND_SKILL, skillId);
        assertEquals(List.of("java"), after,
                "rollback 必须保留原 tag; got " + after);
        assertTrue(after.contains("java"),
                "原 tag 必须仍在 (未回滚则被 DELETE 清掉)");
    }

    @Test
    @DisplayName("replaceTags 全成功 → 旧 tag 被替换(回归 sanity)")
    void replaceTagsSuccessReplacesOldTags() {
        // seed: java + spring
        tagService.addTags(SkillTagService.MARKET_KIND_SKILL, skillId,
                List.of("java", "spring"));

        // replace → 改成 python + go (无超长,应当成功)
        tagService.replaceTags(SkillTagService.MARKET_KIND_SKILL, skillId,
                List.of("python", "go"));

        List<String> after = tagService.listTags(
                SkillTagService.MARKET_KIND_SKILL, skillId);
        assertEquals(List.of("go", "python"), after, // alphabetical order
                "成功路径:旧 tag 被替换");
    }

    @Test
    @DisplayName("replaceTags 空 list → 原 tag 被全清(逻辑回归)")
    void replaceTagsEmptyListClearsAll() {
        // seed
        tagService.addTags(SkillTagService.MARKET_KIND_SKILL, skillId,
                List.of("java", "spring"));

        // replace with []
        tagService.replaceTags(SkillTagService.MARKET_KIND_SKILL, skillId,
                List.of());

        List<String> after = tagService.listTags(
                SkillTagService.MARKET_KIND_SKILL, skillId);
        assertTrue(after.isEmpty(),
                "空 list replace 必须清空所有 tag; got " + after);
    }

    @Test
    @DisplayName("addTags/replaceTags 对不存在的 skill → IllegalArgumentException")
    void unknownSkillIdThrowsIllegalArgument() {
        Long ghostId = 999999999999L;
        assertThrows(IllegalArgumentException.class,
                () -> tagService.addTags(SkillTagService.MARKET_KIND_SKILL, ghostId, List.of("x")),
                "addTags 存在性预检必须抛 IAE");
        assertThrows(IllegalArgumentException.class,
                () -> tagService.replaceTags(SkillTagService.MARKET_KIND_SKILL, ghostId, List.of("x")),
                "replaceTags 存在性预检必须抛 IAE");
    }
}
