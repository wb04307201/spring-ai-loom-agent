package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.knowledge.review.DefaultKnowledgeReviewService;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试: {@link DefaultKnowledgeReviewService} + KB 严门槛。
 * <p>
 * KB review 与 Skill review 的主要差异: submit 时要求
 * {@code loom_user_knowledge.access_count >= 1} for
 * {@code (username, market_id)},否则抛 403 "请先访问过该知识库再评"。
 * 其它(listReviews / update / aggregate / deleteAsAdmin)继承自抽象基类,
 * 与 Skill 端完全对称。
 *
 * <p><b>真 UUID 路径(M3+ R2 / T1.7 gap 修复):</b>
 * {@code loom_market_knowledge_review.market_id} 自 T1.1 迁移后是
 * {@code VARCHAR(36)},与 {@code loom_market_knowledge.id} (UUID) 类型一致;
 * review 服务已泛型化为 {@code <String>}。测试全部使用
 * {@code UUID.randomUUID().toString()} 真实 UUID 灌
 * {@code loom_market_knowledge} / {@code loom_user_knowledge},review 调用
 * 直接以该 UUID String 为主键 — 旧的 numeric-style id 折衷
 * (Long 绑 VARCHAR(36) 隐式 coercion,曾导致 "评价 upsert 失败:行未写入"
 * 间歇性失败)已彻底移除。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("DefaultKnowledgeReviewService IT — T17 (R2: real-UUID <String> path)")
class DefaultKnowledgeReviewServiceIT {

    @Autowired DefaultKnowledgeReviewService reviewService;
    @Autowired IUser user;
    @Autowired JdbcTemplate jdbc;

    private static final String ADMIN_USER = "kbreviewadmin";
    private static final String ADMIN_PASS = "kb-review-pwd-123";

    @BeforeEach
    void setUp() {
        try {
            user.createUser(ADMIN_USER, "KB Review Admin", ADMIN_PASS, "ADMIN");
        } catch (RuntimeException ignored) {
            // already exists — fine
        }
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    /**
     * 严门槛 — 无 loom_user_knowledge 行 → submit 抛 403 + 提示文案。
     */
    @Test
    @DisplayName("submit 无 access 记录 → 403 「请先访问过该知识库再评」")
    void kbReviewRequiresPriorAccess() {
        String marketId = UUID.randomUUID().toString();
        seedMarketKb(marketId, "no-access-test-" + marketId, "alice");

        ReviewSubmitRequest req = new ReviewSubmitRequest(5, "good");
        LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
                () -> reviewService.submit(marketId, "bob", req));
        assertEquals(403, ex.getStatusCode());
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("请先访问过该知识库再评"),
                "error message must mention KB 严门槛 文案; got: " + ex.getMessage());

        // 评价未被写入
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM loom_market_knowledge_review WHERE market_id = ? AND username = ?",
                Integer.class, marketId, "bob");
        assertEquals(0, count, "无 access 的 submit 不能写入 review 行");
    }

    /**
     * 严门槛 — loom_user_knowledge 行存在但 access_count=0 → 仍然抛 403
     * (必须有 >=1 才算"访问过")。
     */
    @Test
    @DisplayName("submit 仅有 pull 但 access_count=0 → 仍 403")
    void kbReviewRequiresActualAccess() {
        String marketId = UUID.randomUUID().toString();
        seedMarketKb(marketId, "zero-access-test-" + marketId, "alice");
        seedUserKbRow("bob", marketId, 0L);

        ReviewSubmitRequest req = new ReviewSubmitRequest(4, "ok");
        LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
                () -> reviewService.submit(marketId, "bob", req));
        assertEquals(403, ex.getStatusCode());
    }

    /**
     * 严门槛 — access_count >= 1 → submit 通过,MERGE INTO upsert 写入。
     */
    @Test
    @DisplayName("submit 有 access 记录 → 正常 upsert")
    void kbReviewAcceptsAfterAccess() {
        String marketId = UUID.randomUUID().toString();
        seedMarketKb(marketId, "has-access-test-" + marketId, "alice");
        seedUserKbRow("bob", marketId, 1L);

        ReviewRow<String> row = reviewService.submit(marketId, "bob", new ReviewSubmitRequest(4, "works"));
        assertNotNull(row);
        assertEquals(marketId, row.marketId());
        assertEquals("bob", row.username());
        assertEquals(4, row.rating());
        assertEquals(0, row.editCount());

        // DB 行确认
        Integer dbCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM loom_market_knowledge_review WHERE market_id = ? AND username = ?",
                Integer.class, marketId, "bob");
        assertEquals(1, dbCount);
    }

    /** 二次 submit (同 user 同 kb) — MERGE INTO UPDATE,rating/comment 被覆盖。 */
    @Test
    @DisplayName("submit 二次: MERGE INTO UPDATE")
    void kbReviewSubmitSecondTimeUpdates() {
        String marketId = UUID.randomUUID().toString();
        seedMarketKb(marketId, "update-twice-" + marketId, "alice");
        seedUserKbRow("bob", marketId, 1L);

        reviewService.submit(marketId, "bob", new ReviewSubmitRequest(3, "first"));
        ReviewRow<String> second = reviewService.submit(marketId, "bob", new ReviewSubmitRequest(5, "second"));

        assertEquals(5, second.rating());
        assertEquals("second", second.comment());
        assertEquals(0, second.editCount());
    }

    /** update 第一次允许 + 自增 edit_count,无严门槛(只有 submit 卡门槛)。 */
    @Test
    @DisplayName("update 不再卡 KB 严门槛,第一次允许,edit_count +1")
    void kbReviewUpdateFirstTimeAllowed() {
        String marketId = UUID.randomUUID().toString();
        seedMarketKb(marketId, "update-test-" + marketId, "alice");
        seedUserKbRow("bob", marketId, 1L);

        reviewService.submit(marketId, "bob", new ReviewSubmitRequest(3, "first"));
        ReviewRow<String> after = reviewService.update(marketId, "bob", new ReviewUpdateRequest(5, "edited"));

        assertEquals(5, after.rating());
        assertEquals(1, after.editCount());
    }

    /** update 第二次抛 403 — 与 Skill 端对称。 */
    @Test
    @DisplayName("update 第二次抛 403")
    void kbReviewUpdateSecondTimeBlocked() {
        String marketId = UUID.randomUUID().toString();
        seedMarketKb(marketId, "block-twice-" + marketId, "alice");
        seedUserKbRow("bob", marketId, 1L);

        reviewService.submit(marketId, "bob", new ReviewSubmitRequest(3, "first"));
        reviewService.update(marketId, "bob", new ReviewUpdateRequest(5, "edited"));

        LoomAgentRuntimeException ex = assertThrows(LoomAgentRuntimeException.class,
                () -> reviewService.update(marketId, "bob", new ReviewUpdateRequest(1, "second edit")));
        assertEquals(403, ex.getStatusCode());
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("只能修改一次"),
                "error must mention 只能修改一次; got: " + ex.getMessage());
    }

    /**
     * aggregate 排除 admin 自评 — 与 Skill 端同款,SQL JOIN user_info u ...
     * 在 KB review 表上行为一致。
     */
    @Test
    @DisplayName("aggregate 排除 admin 自评 (KB 端)")
    void kbAggregateExcludesAdminReview() {
        String marketId = UUID.randomUUID().toString();
        seedMarketKb(marketId, "agg-test-" + marketId, "alice");
        seedUserKbRow("normal1", marketId, 1L);
        seedUserKbRow("normal2", marketId, 1L);
        seedUserKbRow("normal3", marketId, 1L);
        seedUserKbRow(ADMIN_USER, marketId, 1L);

        seedNormalUser("normal1");
        seedNormalUser("normal2");
        seedNormalUser("normal3");

        reviewService.submit(marketId, "normal1", new ReviewSubmitRequest(3, "ok"));
        reviewService.submit(marketId, "normal2", new ReviewSubmitRequest(4, "good"));
        reviewService.submit(marketId, "normal3", new ReviewSubmitRequest(5, "great"));
        reviewService.submit(marketId, ADMIN_USER, new ReviewSubmitRequest(1, "admin self"));

        RatingAggregate agg = reviewService.aggregate(marketId);
        assertEquals(3L, agg.count());
        assertEquals(4.0, agg.avg(), 0.001);
    }

    /** listReviews 多用户 + 分页 — 父类 listReviews,KB 端走同一抽象。 */
    @Test
    @DisplayName("listReviews 多用户 + 分页 + count (KB 端)")
    void kbListReviewsPaged() {
        String marketId = UUID.randomUUID().toString();
        seedMarketKb(marketId, "list-test-" + marketId, "alice");
        for (int i = 1; i <= 5; i++) {
            String u = "kbuser" + i;
            seedNormalUser(u);
            seedUserKbRow(u, marketId, 1L);
            reviewService.submit(marketId, u, new ReviewSubmitRequest(i, "c-" + i));
        }

        Page<ReviewRow<String>> page0 = reviewService.listReviews(marketId, 0, 3);
        assertEquals(5L, page0.total());
        assertEquals(3, page0.items().size());
    }

    /** deleteAsAdmin — KB 端走同一抽象。 */
    @Test
    @DisplayName("deleteAsAdmin 删除指定 (kb, user) 行 (KB 端)")
    void kbDeleteAsAdminRemovesOneRow() {
        String marketId = UUID.randomUUID().toString();
        seedMarketKb(marketId, "del-test-" + marketId, "alice");
        seedNormalUser("kbdel1");
        seedNormalUser("kbdel2");
        seedUserKbRow("kbdel1", marketId, 1L);
        seedUserKbRow("kbdel2", marketId, 1L);

        reviewService.submit(marketId, "kbdel1", new ReviewSubmitRequest(3, "a"));
        reviewService.submit(marketId, "kbdel2", new ReviewSubmitRequest(4, "b"));

        reviewService.deleteAsAdmin(marketId, "kbdel1");

        assertEquals(1L, reviewService.listReviews(marketId, 0, 10).total());
        Page<ReviewRow<String>> remaining = reviewService.listReviews(marketId, 0, 10);
        assertEquals("kbdel2", remaining.items().get(0).username());
    }

    /**
     * M3+ R2 新增 — 全 UUID round-trip(spec AT1):同一个真实 UUID 串起
     * submit → listReviews → aggregate → deleteAsAdmin,每一步都命中
     * VARCHAR(36) 真路径,证明 KB review 链端到端可用。
     */
    @Test
    @DisplayName("R2 — 全 UUID round-trip: submit → listReviews → aggregate → deleteAsAdmin (spec AT1)")
    void kbReviewFullUuidRoundTrip() {
        String marketId = UUID.randomUUID().toString();
        seedMarketKb(marketId, "roundtrip-" + marketId, "alice");
        seedNormalUser("kbtrip1");
        seedNormalUser("kbtrip2");
        seedUserKbRow("kbtrip1", marketId, 1L);
        seedUserKbRow("kbtrip2", marketId, 1L);

        // submit — 两个真实用户
        ReviewRow<String> r1 = reviewService.submit(marketId, "kbtrip1", new ReviewSubmitRequest(4, "nice kb"));
        ReviewRow<String> r2 = reviewService.submit(marketId, "kbtrip2", new ReviewSubmitRequest(2, "meh"));
        assertEquals(marketId, r1.marketId(), "review row must carry the real UUID back");
        assertEquals(marketId, r2.marketId());

        // listReviews — 命中同 UUID 的 2 行
        Page<ReviewRow<String>> page = reviewService.listReviews(marketId, 0, 10);
        assertEquals(2L, page.total());
        assertTrue(page.items().stream().allMatch(r -> marketId.equals(r.marketId())),
                "every listed row must carry the same UUID marketId");

        // aggregate — (4 + 2) / 2 = 3.0,count=2(都是 USER,无 admin 自评)
        RatingAggregate agg = reviewService.aggregate(marketId);
        assertEquals(2L, agg.count());
        assertEquals(3.0, agg.avg(), 0.001);

        // deleteAsAdmin — 删一行,剩一行,aggregate 随之变化
        reviewService.deleteAsAdmin(marketId, "kbtrip1");
        Page<ReviewRow<String>> after = reviewService.listReviews(marketId, 0, 10);
        assertEquals(1L, after.total());
        assertEquals("kbtrip2", after.items().get(0).username());
        RatingAggregate agg2 = reviewService.aggregate(marketId);
        assertEquals(1L, agg2.count());
        assertEquals(2.0, agg2.avg(), 0.001);
    }

    /* ===== helpers ===== */

    /**
     * 直接 INSERT 到 loom_market_knowledge — 用真实 UUID id
     * (R2: review 链已泛型化为 {@code <String>},UUID 是 canonical 形态)。
     */
    private void seedMarketKb(String marketId, String name, String author) {
        jdbc.update(
                "INSERT INTO loom_market_knowledge (id, username, name, description, category, status, created_by_kind) " +
                        "VALUES (?, ?, ?, ?, ?, 'APPROVED', 'USER')",
                marketId, author, name, "desc", "cat");
    }

    /**
     * 插入 loom_user_knowledge 行 — 含 access_count。模拟用户已订阅 KB 并 access 过。
     */
    private void seedUserKbRow(String username, String marketKnowledgeId, long accessCount) {
        // 先清掉可能存在的同主键行,避免重复主键冲突
        jdbc.update(
                "DELETE FROM loom_user_knowledge WHERE username = ? AND market_knowledge_id = ?",
                username, marketKnowledgeId);
        jdbc.update(
                "INSERT INTO loom_user_knowledge (username, market_knowledge_id, source, locked, access_count) " +
                        "VALUES (?, ?, 'MARKET_PULLED', FALSE, ?)",
                username, marketKnowledgeId, accessCount);
    }

    private void seedNormalUser(String username) {
        try {
            user.createUser(username, "n", "pwd-" + username, "USER");
        } catch (RuntimeException ignored) {
            // already exists — fine
        }
    }
}
