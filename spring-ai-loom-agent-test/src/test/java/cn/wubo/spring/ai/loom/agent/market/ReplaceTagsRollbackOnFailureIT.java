package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.knowledge.market.KnowledgeTagService;
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
 * MUST-FIX-2 验证:{@link KnowledgeTagService#replaceTags(String, String, java.util.List)}
 * 必须回滚到调用前的状态(原 tag 不丢)。
 * <p>
 * 修复前 {@code replaceTags} 没有事务边界:DELETE 单独 {@code jdbcTemplate.update(...)}
 * 调用已经 auto-commit,如果后续 {@code addTags} 失败 (FK 违反、字段超长等),
 * KB 的 tag 被静默清零 — admin PUT 返回 4xx/5xx,但 DB 端"老 tag 没了"是事实。
 * <p>
 * 修复后 {@code replaceTags} 加 {@code @Transactional},DELETE / addTags 在同一事务:
 * addTags 抛错触发整体回滚,原 tag 仍在。
 *
 * <p>触发 addTags 失败的方式:tag 字段是 {@code VARCHAR(64)},提供长度 > 64 的字符串
 * 让 H2 在 MERGE 时报 data integrity violation — 与生产环境 admin 误填超长 tag 路径一致。
 *
 * <p>走 service 直接验证(不走 router)—
 * router 的 IllegalArgumentException catch 块会把 addTags 的 SQL error 翻译成 500,
 * 但 DB 端行为需要绕过 router,直接测 service 才看得出 rollback。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("MUST-FIX-2 — replaceTags 失败时回滚到原状态")
class ReplaceTagsRollbackOnFailureIT {

    @Autowired KnowledgeTagService tagService;
    @Autowired JdbcTemplate jdbc;

    private String kbId;
    private final List<String> cleanupKbIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 每个测试用一个新 KB,避免 tag 行残留。
        // 用 numeric-style id (与 MarketAcceptanceIT 同款),避免 H2 把 VARCHAR 当 BIGINT 转换时报 22018
        // (loom_market_knowledge_review.market_id / loom_market_knowledge_stats.market_id 是 BIGINT,
        //  schema 已记录的 KB-vs-BIGINT 不对称;numeric id 走通 graceful-degradation 路径)。
        kbId = String.valueOf(System.nanoTime() & 0x7FFFFFFFL);
        jdbc.update(
                "INSERT INTO loom_market_knowledge (id, username, name, description, category, status, created_by_kind) " +
                        "VALUES (?, ?, ?, ?, ?, 'APPROVED', 'USER')",
                kbId, "author-fix2", "rollback-kb-" + kbId, "desc", "cat-fix2");
        cleanupKbIds.add(kbId);
    }

    @AfterEach
    void tearDown() {
        // 清掉所有用过的 KB (CASCADE 自动清 tag 行)
        for (String id : cleanupKbIds) {
            jdbc.update("DELETE FROM loom_market_knowledge WHERE id = ?", id);
        }
        cleanupKbIds.clear();
    }

    @Test
    @DisplayName("replaceTags 中 addTags 失败 → 原 tag 仍存在(rollback)")
    void replaceTagsRollbackPreservesOriginalTagsOnFailure() {
        // 1. 灌一个原 tag
        tagService.addTags(KnowledgeTagService.MARKET_KIND_KNOWLEDGE, kbId,
                List.of("java"));
        List<String> before = tagService.listTags(
                KnowledgeTagService.MARKET_KIND_KNOWLEDGE, kbId);
        assertEquals(List.of("java"), before, "原 tag 必须存在");

        // 2. 构造一个会失败的 replaceTags 调用:新 list 含一个超长 tag (VARCHAR(64) 限)
        //    'a' x 100 必然超过 64 → MERGE 时 H2 报 integrity violation
        String tooLong = "a".repeat(100);
        List<String> newTags = List.of("spring", tooLong);

        // 3. 调用必须抛 DataAccessException
        DataAccessException ex = assertThrows(DataAccessException.class,
                () -> tagService.replaceTags(
                        KnowledgeTagService.MARKET_KIND_KNOWLEDGE, kbId, newTags),
                "超长 tag 必须触发 DataAccessException (VARCHAR(64) 违反)");
        assertNotNull(ex);

        // 4. 关键断言:rollback 后原 tag "java" 仍在
        List<String> after = tagService.listTags(
                KnowledgeTagService.MARKET_KIND_KNOWLEDGE, kbId);
        assertEquals(List.of("java"), after,
                "rollback 必须保留原 tag; got " + after);
        assertTrue(after.contains("java"),
                "原 tag 必须仍在 (未回滚则被 DELETE 清掉)");
    }

    @Test
    @DisplayName("replaceTags 全成功 → 旧 tag 被替换(回归 sanity)")
    void replaceTagsSuccessReplacesOldTags() {
        // seed: java + spring
        tagService.addTags(KnowledgeTagService.MARKET_KIND_KNOWLEDGE, kbId,
                List.of("java", "spring"));

        // replace → 改成 python + go (无超长,应当成功)
        tagService.replaceTags(KnowledgeTagService.MARKET_KIND_KNOWLEDGE, kbId,
                List.of("python", "go"));

        List<String> after = tagService.listTags(
                KnowledgeTagService.MARKET_KIND_KNOWLEDGE, kbId);
        assertEquals(List.of("go", "python"), after, // alphabetical order
                "成功路径:旧 tag 被替换");
    }

    @Test
    @DisplayName("replaceTags 空 list → 原 tag 被全清(逻辑回归)")
    void replaceTagsEmptyListClearsAll() {
        // seed
        tagService.addTags(KnowledgeTagService.MARKET_KIND_KNOWLEDGE, kbId,
                List.of("java", "spring"));

        // replace with []
        tagService.replaceTags(KnowledgeTagService.MARKET_KIND_KNOWLEDGE, kbId,
                List.of());

        List<String> after = tagService.listTags(
                KnowledgeTagService.MARKET_KIND_KNOWLEDGE, kbId);
        assertTrue(after.isEmpty(),
                "空 list replace 必须清空所有 tag; got " + after);
    }
}