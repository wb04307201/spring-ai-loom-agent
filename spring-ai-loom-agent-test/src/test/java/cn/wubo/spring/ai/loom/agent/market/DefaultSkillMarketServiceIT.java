package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.model.MarketSkill;
import cn.wubo.spring.ai.loom.agent.skill.DefaultSkillMarketService;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = LoomAgentTestApplication.class)
class DefaultSkillMarketServiceIT {

    @Autowired DefaultSkillMarketService svc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IUser user;

    @Test
    void createAppendsRowToMarketSkill() {
        long id = svc.create("alice", new MarketCreateRequest(
            "name-" + System.nanoTime(),
            "desc",
            "content",
            "category-x"
        )).id();
        assertTrue(id > 0);
        String status = jdbc.queryForObject("SELECT status FROM market_skill WHERE id=?", String.class, id);
        assertEquals("PENDING", status);
    }

    @Test
    void approveFlipsStatus() {
        long id = svc.create("alice", new MarketCreateRequest(
            "appr-" + System.nanoTime(), "d", "c", null
        )).id();
        svc.approve(id, "admin1");
        String s = jdbc.queryForObject("SELECT status FROM market_skill WHERE id=?", String.class, id);
        assertEquals("APPROVED", s);
        String reviewedBy = jdbc.queryForObject("SELECT reviewed_by FROM market_skill WHERE id=?", String.class, id);
        assertEquals("admin1", reviewedBy);
    }

    @Test
    void rejectWithoutCommentThrows() {
        long id = svc.create("alice", new MarketCreateRequest(
            "rej-" + System.nanoTime(), "d", "c", null
        )).id();
        assertThrows(IllegalArgumentException.class, () -> svc.reject(id, "admin1", ""));
    }

    @Test
    void setOfficialTogglesFlag() {
        long id = svc.create("alice", new MarketCreateRequest(
            "off-" + System.nanoTime(), "d", "c", null
        )).id();
        svc.setOfficial(id, true, "admin1");
        Boolean official = jdbc.queryForObject("SELECT is_official FROM market_skill WHERE id=?", Boolean.class, id);
        assertEquals(true, official);
    }

    /* ===== M4 T3: sortBy=rating via review-aggregate LEFT JOIN ===== */

    /**
     * M4 T3 — 播种 3 个 APPROVED skill(A: 2 条用户评价 avg 4.5;B: 1 条用户评价 3.0
     * + 1 条 ADMIN 评价 5.0 → ADMIN 被排除,avg 仍 3.0 / ratingCount=1;C: 无评价),
     * sortBy=rating 的 listPaged 必须:
     * <ol>
     *   <li>顺序 A,B,C(NULLS last — {@code (rr.avg_rating IS NULL)} 布尔排序);</li>
     *   <li>row.avgRating() / ratingCount() 正确(B 不含 admin 评价);</li>
     *   <li>isOfficial / featuredRank 也被暴露;</li>
     *   <li>回归:sortBy=submitted_at 仍工作(C 最晚提交 → 第一位)。</li>
     * </ol>
     */
    @Test
    void listPagedSortByRatingOrdersByAggregateAndExcludesAdmin() {
        String category = "t3-rating-" + System.nanoTime();
        long idA = createApproved("t3-A-" + System.nanoTime(), category);
        long idB = createApproved("t3-B-" + System.nanoTime(), category);
        long idC = createApproved("t3-C-" + System.nanoTime(), category);

        String user1 = seedUser("t3u1-" + System.nanoTime(), "USER");
        String user2 = seedUser("t3u2-" + System.nanoTime(), "USER");
        String admin = seedUser("t3ad-" + System.nanoTime(), "ADMIN");

        seedReview(idA, user1, 5);
        seedReview(idA, user2, 4);
        seedReview(idB, user1, 3);
        seedReview(idB, admin, 5);   // ADMIN 自评 — 必须被聚合排除

        // setOfficial/featuredRank 暴露验证:A 官方 rank 7
        svc.setOfficial(idA, true, "admin1");
        svc.setFeaturedRank(idA, 7, "admin1");

        Page<MarketSkill> page = svc.listPaged(new MarketFilter(
            0, 50, null, category, null, "rating"));

        assertEquals(3L, page.total());
        List<MarketSkill> items = page.items();
        assertEquals(3, items.size());
        // 顺序 A,B,C — NULLS last
        assertEquals(idA, items.get(0).id());
        assertEquals(idB, items.get(1).id());
        assertEquals(idC, items.get(2).id());

        MarketSkill a = items.get(0);
        assertEquals(4.5, a.avgRating(), 0.001, "A avg = (5+4)/2 = 4.5");
        assertEquals(2L, a.ratingCount());
        assertEquals(Boolean.TRUE, a.isOfficial(), "isOfficial must be exposed on the DTO");
        assertEquals(7, a.featuredRank(), "featuredRank must be exposed on the DTO");

        MarketSkill b = items.get(1);
        assertEquals(3.0, b.avgRating(), 0.001, "B avg must exclude the ADMIN 5-star review");
        assertEquals(1L, b.ratingCount(), "B ratingCount must not count the ADMIN review");
        assertEquals(Boolean.FALSE, b.isOfficial());

        MarketSkill c = items.get(2);
        assertNull(c.avgRating(), "C has no reviews → avgRating null (NULLS last)");
        assertEquals(0L, c.ratingCount());

        // 回归:sortBy=submitted_at 仍工作 — C 最晚提交 → 第一位
        Page<MarketSkill> byTime = svc.listPaged(new MarketFilter(
            0, 50, null, category, null, "submitted_at"));
        assertEquals(3L, byTime.total());
        assertEquals(idC, byTime.items().get(0).id(),
            "submitted_at sort regression: latest-seeded C must come first");
    }

    private long createApproved(String name, String category) {
        long id = svc.create("alice", new MarketCreateRequest(name, "d", "c", category)).id();
        // DefaultSkillMarketService.create 不落 category 列(INSERT 不含 category) —
        // 用 setCategory 显式写入,让 listPaged 的 category 过滤命中本测试的种子行。
        svc.setCategory(id, category, "admin1");
        svc.approve(id, "admin1");
        return id;
    }

    /** 直接播种 user_info 行(type ADMIN/USER)— 镜像 review IT 的 seedUser,但不经 IUser。 */
    private String seedUser(String username, String type) {
        jdbc.update("INSERT INTO user_info (username, nickname, password, type) VALUES (?, ?, ?, ?)",
            username, username, "pwd-" + username, type);
        return username;
    }

    private void seedReview(long skillId, String username, int rating) {
        jdbc.update("MERGE INTO market_skill_review (market_skill_id, username, rating, comment) " +
            "KEY(market_skill_id, username) VALUES (?, ?, ?, ?)",
            skillId, username, rating, "t3-comment");
    }
}
