package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.model.MarketSkill;
import cn.wubo.spring.ai.loom.agent.skill.DefaultSkillMarketService;
import cn.wubo.spring.ai.loom.agent.skill.market.SkillTagService;
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
    @Autowired SkillTagService tagService;
    @Autowired MarketAnnouncementRepository annRepo;

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

    /* ===== M4 T4: tags embed (镜像 DefaultKnowledgeMarketServiceIT R4 块) ===== */

    /** tags embedded via ONE batch SELECT — exact match, stable order (tag ASC). */
    @Test
    void listPagedEmbedsTagsExactly() {
        String category = "t4-tags-" + System.nanoTime();
        long id = createApproved("t4-tags-" + System.nanoTime(), category);
        tagService.replaceTags(SkillTagService.MARKET_KIND_SKILL, id,
            List.of("spring", "java", "rag"));

        MarketSkill row = findInPage(svc.listPaged(
            new MarketFilter(0, 50, null, category, null, "official_rank")), id);

        assertNotNull(row);
        // SkillTagService.listTags orders by tag ASC — embed must be consistent
        assertEquals(List.of("java", "rag", "spring"), row.tags());
        assertEquals(tagService.listTags(SkillTagService.MARKET_KIND_SKILL, id), row.tags());
    }

    /** A skill with NO tags returns tags() == empty list, NOT null. */
    @Test
    void listPagedReturnsEmptyTagListWhenNoTags() {
        String category = "t4-notags-" + System.nanoTime();
        long id = createApproved("t4-notags-" + System.nanoTime(), category);

        MarketSkill row = findInPage(svc.listPaged(
            new MarketFilter(0, 50, null, category, null, "official_rank")), id);

        assertNotNull(row);
        assertNotNull(row.tags(), "tags must be empty list, never null");
        assertTrue(row.tags().isEmpty());
    }

    /** announcement + tags coexist: one skill row carries both after listPaged. */
    @Test
    void listPagedEmbedsAnnouncementAndTagsInSameRow() {
        String category = "t4-both-" + System.nanoTime();
        long id = createApproved("t4-both-" + System.nanoTime(), category);
        annRepo.upsert("SKILL", String.valueOf(id), "t4-both-title", "t4-both-body");
        tagService.replaceTags(SkillTagService.MARKET_KIND_SKILL, id,
            List.of("skill", "market"));

        MarketSkill row = findInPage(svc.listPaged(
            new MarketFilter(0, 50, null, category, null, "official_rank")), id);

        assertNotNull(row);
        assertEquals("t4-both-title", row.announcementTitle());
        assertEquals("t4-both-body", row.announcementBody());
        assertEquals(List.of("market", "skill"), row.tags());
    }

    /** search() delegates to listPaged in the base — override must cover it too. */
    @Test
    void searchEmbedsTagsAndAnnouncement() {
        String name = "t4-search-" + System.nanoTime();
        long id = svc.create("alice", new MarketCreateRequest(name, "d", "c", null)).id();
        svc.approve(id, "admin1");
        annRepo.upsert("SKILL", String.valueOf(id), "t4-s-title", "t4-s-body");
        tagService.replaceTags(SkillTagService.MARKET_KIND_SKILL, id, List.of("search-tag"));

        MarketSkill row = findInPage(svc.search(name, null, 0, 50), id);

        assertNotNull(row);
        assertEquals("t4-s-title", row.announcementTitle());
        assertEquals(List.of("search-tag"), row.tags());
    }

    /**
     * v1 语义保留:skill 端 {@code listApproved()}(无参,v1 裸 SELECT *)
     * <b>不</b> embed tags —— KB 端 v1 listApproved(page,size) embed 是因为聊天面板
     * market tab 读取 row.tags;skill market tab 不读取,brief 明确 v1 语义不动。
     */
    @Test
    void listApprovedDoesNotEmbedTags() {
        String name = "t4-appr-" + System.nanoTime();
        long id = svc.create("alice", new MarketCreateRequest(name, "d", "c", null)).id();
        svc.approve(id, "admin1");
        tagService.replaceTags(SkillTagService.MARKET_KIND_SKILL, id,
            List.of("v1-no-embed"));

        MarketSkill row = svc.listApproved().stream()
            .filter(r -> Long.valueOf(id).equals(r.id()))
            .findFirst()
            .orElse(null);

        assertNotNull(row, "approved skill must appear in listApproved");
        assertNull(row.tags(), "v1 listApproved 路径保持不 embed(tags=null)");
    }

    private static MarketSkill findInPage(Page<MarketSkill> page, long id) {
        return page.items().stream()
            .filter(r -> Long.valueOf(id).equals(r.id()))
            .findFirst()
            .orElse(null);
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
