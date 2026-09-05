package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.skill.DefaultSkillMarketService;
import cn.wubo.spring.ai.loom.agent.skill.review.DefaultSkillReviewService;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试: {@link DefaultSkillReviewService} + 公共 listReviews / aggregate。
 * <p>
 * 镜像 T16 模式(SpringBootTest + 真实 JdbcTemplate),覆盖 T17:
 * <ol>
 *   <li>{@code submit} / MERGE INTO upsert — 首次提交 INSERT,二次提交(同 user) UPDATE;</li>
 *   <li>{@code listReviews} — 按 market_skill_id 过滤 + 分页 + count;</li>
 *   <li>{@code aggregate} — 排除 admin 自评(spec § 9.2 第 5 行),返回 {count, avg};</li>
 *   <li>{@code update} — 第一次允许并自增 edit_count,第二次抛 403;</li>
 *   <li>{@code deleteAsAdmin} — 删除指定 (market_id, username) 行,不残留。</li>
 * </ol>
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("DefaultSkillReviewService IT — T17")
class DefaultSkillReviewServiceIT {

    @Autowired DefaultSkillReviewService reviewService;
    @Autowired DefaultSkillMarketService skillMarketService;
    @Autowired IUser user;
    @Autowired JdbcTemplate jdbc;

    private static final String ADMIN_USER = "reviewadmin";
    private static final String ADMIN_PASS = "review-pwd-123";

    @BeforeEach
    void setUp() {
        try {
            user.createUser(ADMIN_USER, "Review Admin", ADMIN_PASS, "ADMIN");
        } catch (RuntimeException ignored) {
            // already exists — fine
        }
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    /**
     * submit 首次写入 — 行被创建,rating/comment/edit_count 与时间戳都正确。
     * <p>edit_count 默认 0(SMALLINT DEFAULT 0);created_at 与 updated_at 同期。
     */
    @Test
    @DisplayName("submit 首次 INSERT,edit_count=0")
    void submitFirstTimeInserts() {
        Long skillId = createSkill();
        ReviewRow<Long> row = reviewService.submit(skillId, "alice", new ReviewSubmitRequest(5, "great"));

        assertNotNull(row);
        assertEquals(skillId, row.marketId());
        assertEquals("alice", row.username());
        assertEquals(5, row.rating());
        assertEquals("great", row.comment());
        assertEquals(0, row.editCount(), "first submit must have edit_count=0");
        assertNotNull(row.createdAt());
        assertNotNull(row.updatedAt());
    }

    /**
     * submit 二次 (同 user 同 skill) — MERGE INTO 走 UPDATE 分支,
     * rating/comment 被覆盖,edit_count 保留为 0(不重新计数 — 提交即上架,
     * 不计入修改额度;只有 {@code update} 才会 +1)。
     */
    @Test
    @DisplayName("submit 二次: MERGE INTO UPDATE,rating/comment 被覆盖,edit_count 仍 0")
    void submitSecondTimeUpdates() {
        Long skillId = createSkill();
        reviewService.submit(skillId, "alice", new ReviewSubmitRequest(3, "first"));
        ReviewRow<Long> second = reviewService.submit(skillId, "alice", new ReviewSubmitRequest(5, "second"));

        assertEquals(5, second.rating());
        assertEquals("second", second.comment());
        assertEquals(0, second.editCount(), "submit (not update) must not bump edit_count");

        // DB 里只有 1 行 (alice 对该 skill)
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM market_skill_review WHERE market_skill_id = ? AND username = ?",
                Integer.class, skillId, "alice");
        assertEquals(1, rowCount);
    }

    /** listReviews: 多用户 → 多行,按 created_at DESC 排序,count 准确。 */
    @Test
    @DisplayName("listReviews 多用户分页 + count")
    void listReviewsPaged() {
        Long skillId = createSkill();
        for (int i = 1; i <= 5; i++) {
            reviewService.submit(skillId, "user" + i, new ReviewSubmitRequest(i, "comment-" + i));
        }

        Page<ReviewRow<Long>> page0 = reviewService.listReviews(skillId, 0, 3);
        assertEquals(5L, page0.total());
        assertEquals(3, page0.items().size());
        assertEquals(0, page0.page());
        assertEquals(3, page0.size());

        Page<ReviewRow<Long>> page1 = reviewService.listReviews(skillId, 1, 3);
        assertEquals(5L, page1.total());
        assertEquals(2, page1.items().size(), "second page should have remaining 2 rows");
    }

    /**
     * aggregate — 普通用户的评分被计入,admin 自评被排除。
     * 准备 3 个普通用户 (rating 3 / 4 / 5) + 1 个 admin (rating 1),期望 count=3, avg=4.0。
     */
    @Test
    @DisplayName("aggregate 排除 admin 自评: count / avg 仅算 USER")
    void aggregateExcludesAdminReview() {
        Long skillId = createSkill();
        seedUser("normal1", "USER");
        seedUser("normal2", "USER");
        seedUser("normal3", "USER");
        // admin 在 setUp() 已 seed

        reviewService.submit(skillId, "normal1", new ReviewSubmitRequest(3, "ok"));
        reviewService.submit(skillId, "normal2", new ReviewSubmitRequest(4, "good"));
        reviewService.submit(skillId, "normal3", new ReviewSubmitRequest(5, "great"));
        reviewService.submit(skillId, ADMIN_USER, new ReviewSubmitRequest(1, "admin self-rate"));

        RatingAggregate agg = reviewService.aggregate(skillId);
        assertEquals(3L, agg.count(), "admin 自评必须排除");
        assertEquals(4.0, agg.avg(), 0.001, "avg = (3+4+5)/3 = 4.0");
    }

    /** 无任何评价 → aggregate 返回 {0L, 0.0} (COALESCE 把空集合 AVG 抹平)。 */
    @Test
    @DisplayName("aggregate 空集合: 返回 {0L, 0.0}")
    void aggregateEmptyReturnsZeros() {
        Long skillId = createSkill();
        RatingAggregate agg = reviewService.aggregate(skillId);
        assertEquals(0L, agg.count());
        assertEquals(0.0, agg.avg(), 0.001);
    }

    /** update 第一次允许 + 自增 edit_count。 */
    @Test
    @DisplayName("update 第一次允许,edit_count +1 = 1")
    void updateFirstTimeAllowed() {
        Long skillId = createSkill();
        reviewService.submit(skillId, "alice", new ReviewSubmitRequest(3, "first"));
        ReviewRow<Long> after = reviewService.update(skillId, "alice", new ReviewUpdateRequest(5, "edited"));

        assertEquals(5, after.rating());
        assertEquals("edited", after.comment());
        assertEquals(1, after.editCount(), "update must bump edit_count to 1");
    }

    /** update 第二次抛 403 — 评价只能修改一次。 */
    @Test
    @DisplayName("update 第二次抛 403,error 文案提示「只能修改一次」")
    void updateSecondTimeBlocked() {
        Long skillId = createSkill();
        reviewService.submit(skillId, "alice", new ReviewSubmitRequest(3, "first"));
        reviewService.update(skillId, "alice", new ReviewUpdateRequest(5, "edited once"));

        LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
                () -> reviewService.update(skillId, "alice", new ReviewUpdateRequest(1, "second edit")));
        assertEquals(403, ex.getStatusCode());
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("只能修改一次"),
                "error message must mention 只能修改一次; got: " + ex.getMessage());

        // edit_count 仍是 1(被第二次抛错前没有写入)
        Integer editCount = jdbc.queryForObject(
                "SELECT edit_count FROM market_skill_review WHERE market_skill_id = ? AND username = ?",
                Integer.class, skillId, "alice");
        assertEquals(1, editCount);
    }

    /** update 不存在的 (market_id, username) → 抛 404。 */
    @Test
    @DisplayName("update 不存在的评价 → 404")
    void updateNonExistentThrows404() {
        Long skillId = createSkill();
        LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
                () -> reviewService.update(skillId, "ghost", new ReviewUpdateRequest(3, "n/a")));
        assertEquals(404, ex.getStatusCode());
    }

    /** deleteAsAdmin — 删除指定 (market_id, username) 行,不影响同 skill 的其它评价。 */
    @Test
    @DisplayName("deleteAsAdmin 删除指定 (market, user) 行,不影响其他人")
    void deleteAsAdminRemovesOneRow() {
        Long skillId = createSkill();
        reviewService.submit(skillId, "alice", new ReviewSubmitRequest(3, "a"));
        reviewService.submit(skillId, "bob", new ReviewSubmitRequest(4, "b"));

        reviewService.deleteAsAdmin(skillId, "alice");

        assertEquals(1L, reviewService.listReviews(skillId, 0, 10).total());
        Page<ReviewRow<Long>> remaining = reviewService.listReviews(skillId, 0, 10);
        assertEquals("bob", remaining.items().get(0).username());
    }

    /* ===== helpers ===== */

    private Long createSkill() {
        return skillMarketService.create("author", new MarketCreateRequest(
                "review-skill-" + System.nanoTime(), "d", "c", null)).id();
    }

    private void seedUser(String username, String type) {
        try {
            user.createUser(username, "n", "pwd-" + username, type);
        } catch (RuntimeException ignored) {
            // already exists — fine
        }
    }
}
