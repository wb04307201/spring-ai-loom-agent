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
 * <p><b>Schema 折衷:</b> {@code loom_market_knowledge_review.market_id} 是
 * {@code BIGINT} 而 {@code loom_market_knowledge.id} 是 {@code VARCHAR(36)} UUID —
 * 测试场景在 loom_market_knowledge 直接插入 numeric-style id (字符串数字),使
 * review 服务的 {@code hasAccessedKb} 通过 H2 隐式 VARCHAR↔BIGINT 转换能匹配
 * {@code loom_user_knowledge.market_knowledge_id}。
 *
 * <p><b>未覆盖:</b> 真实 KB UUID 路径(Long.parseLong 在路由层抛 4xx,服务层不感知)
 * — 与 T16 graceful-degradation 同款。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("DefaultKnowledgeReviewService IT — T17")
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
        long marketId = System.nanoTime() & 0x7FFFFFFFL; // 31-bit 正整数,确保 Long 可表达
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
        long marketId = System.nanoTime() & 0x7FFFFFFFL;
        seedMarketKb(marketId, "zero-access-test-" + marketId, "alice");
        seedUserKbRow("bob", String.valueOf(marketId), 0L);

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
        long marketId = System.nanoTime() & 0x7FFFFFFFL;
        seedMarketKb(marketId, "has-access-test-" + marketId, "alice");
        seedUserKbRow("bob", String.valueOf(marketId), 1L);

        ReviewRow row = reviewService.submit(marketId, "bob", new ReviewSubmitRequest(4, "works"));
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
        long marketId = System.nanoTime() & 0x7FFFFFFFL;
        seedMarketKb(marketId, "update-twice-" + marketId, "alice");
        seedUserKbRow("bob", String.valueOf(marketId), 1L);

        reviewService.submit(marketId, "bob", new ReviewSubmitRequest(3, "first"));
        ReviewRow second = reviewService.submit(marketId, "bob", new ReviewSubmitRequest(5, "second"));

        assertEquals(5, second.rating());
        assertEquals("second", second.comment());
        assertEquals(0, second.editCount());
    }

    /** update 第一次允许 + 自增 edit_count,无严门槛(只有 submit 卡门槛)。 */
    @Test
    @DisplayName("update 不再卡 KB 严门槛,第一次允许,edit_count +1")
    void kbReviewUpdateFirstTimeAllowed() {
        long marketId = System.nanoTime() & 0x7FFFFFFFL;
        seedMarketKb(marketId, "update-test-" + marketId, "alice");
        seedUserKbRow("bob", String.valueOf(marketId), 1L);

        reviewService.submit(marketId, "bob", new ReviewSubmitRequest(3, "first"));
        ReviewRow after = reviewService.update(marketId, "bob", new ReviewUpdateRequest(5, "edited"));

        assertEquals(5, after.rating());
        assertEquals(1, after.editCount());
    }

    /** update 第二次抛 403 — 与 Skill 端对称。 */
    @Test
    @DisplayName("update 第二次抛 403")
    void kbReviewUpdateSecondTimeBlocked() {
        long marketId = System.nanoTime() & 0x7FFFFFFFL;
        seedMarketKb(marketId, "block-twice-" + marketId, "alice");
        seedUserKbRow("bob", String.valueOf(marketId), 1L);

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
        long marketId = System.nanoTime() & 0x7FFFFFFFL;
        seedMarketKb(marketId, "agg-test-" + marketId, "alice");
        seedUserKbRow("normal1", String.valueOf(marketId), 1L);
        seedUserKbRow("normal2", String.valueOf(marketId), 1L);
        seedUserKbRow("normal3", String.valueOf(marketId), 1L);
        seedUserKbRow(ADMIN_USER, String.valueOf(marketId), 1L);

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
        long marketId = System.nanoTime() & 0x7FFFFFFFL;
        seedMarketKb(marketId, "list-test-" + marketId, "alice");
        for (int i = 1; i <= 5; i++) {
            String u = "kbuser" + i;
            seedNormalUser(u);
            seedUserKbRow(u, String.valueOf(marketId), 1L);
            reviewService.submit(marketId, u, new ReviewSubmitRequest(i, "c-" + i));
        }

        Page<ReviewRow> page0 = reviewService.listReviews(marketId, 0, 3);
        assertEquals(5L, page0.total());
        assertEquals(3, page0.items().size());
    }

    /** deleteAsAdmin — KB 端走同一抽象。 */
    @Test
    @DisplayName("deleteAsAdmin 删除指定 (kb, user) 行 (KB 端)")
    void kbDeleteAsAdminRemovesOneRow() {
        long marketId = System.nanoTime() & 0x7FFFFFFFL;
        seedMarketKb(marketId, "del-test-" + marketId, "alice");
        seedNormalUser("kbdel1");
        seedNormalUser("kbdel2");
        seedUserKbRow("kbdel1", String.valueOf(marketId), 1L);
        seedUserKbRow("kbdel2", String.valueOf(marketId), 1L);

        reviewService.submit(marketId, "kbdel1", new ReviewSubmitRequest(3, "a"));
        reviewService.submit(marketId, "kbdel2", new ReviewSubmitRequest(4, "b"));

        reviewService.deleteAsAdmin(marketId, "kbdel1");

        assertEquals(1L, reviewService.listReviews(marketId, 0, 10).total());
        Page<ReviewRow> remaining = reviewService.listReviews(marketId, 0, 10);
        assertEquals("kbdel2", remaining.items().get(0).username());
    }

    /* ===== helpers ===== */

    /**
     * 直接 INSERT 到 loom_market_knowledge — 绕过 UUID 生成,用 numeric-style id
     * 让跨类型 FK + hasAccessedKb 的 String 匹配能跑通。
     */
    private void seedMarketKb(long marketId, String name, String author) {
        jdbc.update(
                "INSERT INTO loom_market_knowledge (id, username, name, description, category, status, created_by_kind) " +
                        "VALUES (?, ?, ?, ?, ?, 'APPROVED', 'USER')",
                String.valueOf(marketId), author, name, "desc", "cat");
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
