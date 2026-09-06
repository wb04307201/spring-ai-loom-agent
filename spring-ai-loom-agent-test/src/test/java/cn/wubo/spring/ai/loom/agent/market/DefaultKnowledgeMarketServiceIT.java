package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.knowledge.DefaultKnowledgeMarketService;
import cn.wubo.spring.ai.loom.agent.knowledge.market.KnowledgeTagService;
import cn.wubo.spring.ai.loom.agent.model.MarketKnowledgeRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 镜像 {@link DefaultSkillMarketServiceIT},表换成 {@code loom_market_knowledge}。
 *
 * <p>唯一结构性差异:{@code loom_market_knowledge.id} 是 {@code VARCHAR(36)} UUID,
 * 不是 {@code BIGINT},因此这里断言的是 String 主键,并调用服务的 String 主键孪生方法。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
class DefaultKnowledgeMarketServiceIT {

    @Autowired DefaultKnowledgeMarketService svc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MarketAnnouncementRepository annRepo;
    @Autowired KnowledgeTagService tagService;

    @Test
    void createAppendsRowToMarketKnowledge() {
        String id = svc.create("alice", new MarketCreateRequest(
            "name-" + System.nanoTime(),
            "desc",
            "content",
            "category-x"
        )).id();
        assertNotNull(id);
        assertFalse(id.isBlank());
        String status = jdbc.queryForObject("SELECT status FROM loom_market_knowledge WHERE id=?", String.class, id);
        assertEquals("PENDING", status);
    }

    @Test
    void approveFlipsStatus() {
        String id = svc.create("alice", new MarketCreateRequest(
            "appr-" + System.nanoTime(), "d", "c", null
        )).id();
        svc.approve(id, "admin1");
        String s = jdbc.queryForObject("SELECT status FROM loom_market_knowledge WHERE id=?", String.class, id);
        assertEquals("APPROVED", s);
        String reviewedBy = jdbc.queryForObject("SELECT reviewed_by FROM loom_market_knowledge WHERE id=?", String.class, id);
        assertEquals("admin1", reviewedBy);
    }

    @Test
    void rejectWithoutCommentThrows() {
        String id = svc.create("alice", new MarketCreateRequest(
            "rej-" + System.nanoTime(), "d", "c", null
        )).id();
        assertThrows(IllegalArgumentException.class, () -> svc.reject(id, "admin1", ""));
    }

    @Test
    void setOfficialTogglesFlag() {
        String id = svc.create("alice", new MarketCreateRequest(
            "off-" + System.nanoTime(), "d", "c", null
        )).id();
        svc.setOfficial(id, true, "admin1");
        Boolean official = jdbc.queryForObject("SELECT is_official FROM loom_market_knowledge WHERE id=?", Boolean.class, id);
        assertEquals(true, official);
    }

    /* ===== M3+ R4 (AT2 follow-up): announcement embed (change 0) + tags embed (change 1) ===== */

    /**
     * RED-GREEN for change 0 (marketKind() "KB" → "KNOWLEDGE").
     * Seeds a KB (real UUID) via the service, upserts an announcement with the
     * canonical kind "KNOWLEDGE" (same as the KB announcement routers use), then
     * calls listPaged. Before the fix, the join filter a.market_kind='KB' never
     * matches the 'KNOWLEDGE' row → announcementTitle/Body are null → FAIL.
     * After the fix → PASS.
     */
    @Test
    void listPagedEmbedsAnnouncementForKnowledgeKind() {
        String category = "r4-ann-" + System.nanoTime();
        String id = svc.create("alice", new MarketCreateRequest(
            "r4-ann-" + System.nanoTime(), "d", "c", category
        )).id();
        annRepo.upsert("KNOWLEDGE", id, "r4-title", "r4-body");

        MarketKnowledgeRecord row = findInPage(svc.listPaged(
            new MarketFilter(0, 50, null, category, null, "official_rank")), id);

        assertNotNull(row, "seeded KB must appear in listPaged page");
        assertEquals("r4-title", row.announcementTitle());
        assertEquals("r4-body", row.announcementBody());
    }

    /** Change 1: tags embedded via ONE batch SELECT — exact match, stable order. */
    @Test
    void listPagedEmbedsTagsExactly() {
        String category = "r4-tags-" + System.nanoTime();
        String id = svc.create("alice", new MarketCreateRequest(
            "r4-tags-" + System.nanoTime(), "d", "c", category
        )).id();
        tagService.replaceTags(KnowledgeTagService.MARKET_KIND_KNOWLEDGE, id,
            List.of("spring", "java", "rag"));

        MarketKnowledgeRecord row = findInPage(svc.listPaged(
            new MarketFilter(0, 50, null, category, null, "official_rank")), id);

        assertNotNull(row);
        // KnowledgeTagService.listTags orders by tag ASC — embed must be consistent
        assertEquals(List.of("java", "rag", "spring"), row.tags());
        assertEquals(tagService.listTags(KnowledgeTagService.MARKET_KIND_KNOWLEDGE, id), row.tags());
    }

    /** Change 1: a KB with NO tags returns tags() == empty list, NOT null. */
    @Test
    void listPagedReturnsEmptyTagListWhenNoTags() {
        String category = "r4-notags-" + System.nanoTime();
        String id = svc.create("alice", new MarketCreateRequest(
            "r4-notags-" + System.nanoTime(), "d", "c", category
        )).id();

        MarketKnowledgeRecord row = findInPage(svc.listPaged(
            new MarketFilter(0, 50, null, category, null, "official_rank")), id);

        assertNotNull(row);
        assertNotNull(row.tags(), "tags must be empty list, never null");
        assertTrue(row.tags().isEmpty());
    }

    /** Change 0 + 1 coexist: one KB row carries announcement AND tags after listPaged. */
    @Test
    void listPagedEmbedsAnnouncementAndTagsInSameRow() {
        String category = "r4-both-" + System.nanoTime();
        String id = svc.create("alice", new MarketCreateRequest(
            "r4-both-" + System.nanoTime(), "d", "c", category
        )).id();
        annRepo.upsert("KNOWLEDGE", id, "both-title", "both-body");
        tagService.replaceTags(KnowledgeTagService.MARKET_KIND_KNOWLEDGE, id,
            List.of("kb", "market"));

        MarketKnowledgeRecord row = findInPage(svc.listPaged(
            new MarketFilter(0, 50, null, category, null, "official_rank")), id);

        assertNotNull(row);
        assertEquals("both-title", row.announcementTitle());
        assertEquals("both-body", row.announcementBody());
        assertEquals(List.of("kb", "market"), row.tags());
    }

    /** search() delegates to listPaged in the base — override must cover it too. */
    @Test
    void searchEmbedsTagsAndAnnouncement() {
        String name = "r4-search-" + System.nanoTime();
        String id = svc.create("alice", new MarketCreateRequest(name, "d", "c", null)).id();
        svc.approve(id, "admin1");
        annRepo.upsert("KNOWLEDGE", id, "s-title", "s-body");
        tagService.replaceTags(KnowledgeTagService.MARKET_KIND_KNOWLEDGE, id, List.of("search-tag"));

        MarketKnowledgeRecord row = findInPage(svc.search(name, null, 0, 50), id);

        assertNotNull(row);
        assertEquals("s-title", row.announcementTitle());
        assertEquals(List.of("search-tag"), row.tags());
    }

    /**
     * Change 2: the user chat market tab (app.js _renderMarketTab) reads
     * v1 GET /api/knowledge-market → listApproved, and renders row.tags —
     * so listApproved must embed tags too (single batch SELECT, same helper).
     */
    @Test
    void listApprovedEmbedsTags() {
        String name = "r4-appr-" + System.nanoTime();
        String id = svc.create("alice", new MarketCreateRequest(name, "d", "c", null)).id();
        svc.approve(id, "admin1");
        tagService.replaceTags(KnowledgeTagService.MARKET_KIND_KNOWLEDGE, id,
            List.of("v1-tag-b", "v1-tag-a"));

        MarketKnowledgeRecord row = svc.listApproved(1, 100).stream()
            .filter(r -> id.equals(r.id()))
            .findFirst()
            .orElse(null);

        assertNotNull(row, "approved KB must appear in listApproved");
        assertEquals(List.of("v1-tag-a", "v1-tag-b"), row.tags());
    }

    private static MarketKnowledgeRecord findInPage(Page<MarketKnowledgeRecord> page, String id) {
        return page.items().stream()
            .filter(r -> id.equals(r.id()))
            .findFirst()
            .orElse(null);
    }

    /* ===== M4 T3: sortBy=rating — VARCHAR(36) review-aggregate LEFT JOIN ===== */

    /**
     * M4 T3 — KB 端 review-aggregate JOIN 走 {@code loom_market_knowledge_review.market_id}
     * (VARCHAR(36)) ↔ {@code loom_market_knowledge.id} (VARCHAR(36)) 直接等值(无 CAST)。
     * 播种 1 条 USER 评价(avg 4.0)+ 1 条 ADMIN 评价(必须排除),sortBy=rating
     * 的 listPaged 必须返回 avgRating=4.0 / ratingCount=1,且 tags 嵌入不受影响。
     */
    @Test
    void listPagedSortByRatingWorksOnVarcharUuidJoin() {
        String category = "t3-kb-rating-" + System.nanoTime();
        String id = svc.create("alice", new MarketCreateRequest(
            "t3-kb-" + System.nanoTime(), "d", "c", category
        )).id();
        svc.approve(id, "admin1");

        String user = "t3kbu-" + System.nanoTime();
        String admin = "t3kba-" + System.nanoTime();
        jdbc.update("INSERT INTO user_info (username, nickname, password, type) VALUES (?, ?, ?, ?)",
            user, user, "pwd-" + user, "USER");
        jdbc.update("INSERT INTO user_info (username, nickname, password, type) VALUES (?, ?, ?, ?)",
            admin, admin, "pwd-" + admin, "ADMIN");
        jdbc.update("MERGE INTO loom_market_knowledge_review (market_id, username, rating, comment) " +
            "KEY(market_id, username) VALUES (?, ?, ?, ?)", id, user, 4, "kb-good");
        jdbc.update("MERGE INTO loom_market_knowledge_review (market_id, username, rating, comment) " +
            "KEY(market_id, username) VALUES (?, ?, ?, ?)", id, admin, 5, "kb-admin-self");

        tagService.replaceTags(KnowledgeTagService.MARKET_KIND_KNOWLEDGE, id, List.of("rating"));

        Page<MarketKnowledgeRecord> page = svc.listPaged(new MarketFilter(
            0, 50, null, category, null, "rating"));

        MarketKnowledgeRecord row = findInPage(page, id);
        assertNotNull(row, "seeded KB must appear in sortBy=rating page");
        assertEquals(4.0, row.avgRating(), 0.001, "ADMIN review must be excluded → avg 4.0");
        assertEquals(1L, row.ratingCount());
        assertEquals(Boolean.FALSE, row.isOfficial());
        assertEquals(0, row.featuredRank());
        assertEquals(List.of("rating"), row.tags(), "tags embed must survive withTags copying the new components");
    }
}
