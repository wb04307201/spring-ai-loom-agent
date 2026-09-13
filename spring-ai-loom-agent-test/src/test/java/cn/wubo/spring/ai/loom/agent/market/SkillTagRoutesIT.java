package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.model.MarketSkill;
import cn.wubo.spring.ai.loom.agent.skill.DefaultSkillMarketService;
import cn.wubo.spring.ai.loom.agent.skill.market.SkillTagService;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;
import java.util.Map;

import static cn.wubo.spring.ai.loom.agent.testutil.LoomAgentTestUtil.json;
import static cn.wubo.spring.ai.loom.agent.testutil.LoomAgentTestUtil.route;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 T4 — skill tag 路由级 IT(router-driven,与 {@code MarketAcceptanceIT} 同款风格):
 * <ul>
 *   <li>admin {@code PUT/GET /spring/ai/loom/admin/market-skills/{id}/tags} → 200 {tags}</li>
 *   <li>非 admin → 403;非数字 id → 400;未知 id PUT → 404 (service IAE)</li>
 *   <li>public {@code GET /spring/ai/loom/market-skills/{id}/tags} → 200 {tags}(未知 id → [])</li>
 *   <li>public {@code GET /spring/ai/loom/market-skills?tag=x} → APPROVED-only,
 *       且行内嵌 tags + announcementTitle(FU-1-fix 差异化断言 — KB ?tag= 分支返回裸行,
 *       skill ?tag= 分支经 {@code svc.enrich(...)} 补回两者)</li>
 * </ul>
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("M4 T4 — skill tag routes IT (admin PUT/GET + public GET {id}/tags + ?tag= enrich)")
class SkillTagRoutesIT {

    @Autowired DefaultSkillMarketService skillSvc;
    @Autowired SkillTagService tagService;
    @Autowired MarketAnnouncementRepository annRepo;
    @Autowired IUser user;
    @Autowired JdbcTemplate jdbc;

    @Autowired
    @Qualifier("loomAgentSkillMarketPublicRouter")
    RouterFunction<ServerResponse> skillPublicRouter;

    @Autowired
    @Qualifier("loomAgentMarketSkillAdminRouter")
    RouterFunction<ServerResponse> skillAdminRouter;

    private static final String ADMIN_USER = "tagadmin";
    private static final String NORMAL_USER = "tagnormal";

    @BeforeEach
    void setUp() {
        try {
            user.createUser(ADMIN_USER, "Tag Admin", "tag-pwd-123", "ADMIN");
        } catch (RuntimeException ignored) {
            // already exists
        }
        try {
            user.createUser(NORMAL_USER, "Tag Normal", "normal-pwd", "USER");
        } catch (RuntimeException ignored) {
            // already exists
        }
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    /* ===== admin PUT/GET tags ===== */

    @Test
    @DisplayName("admin PUT tags → 200 {tags};GET 回读一致")
    void adminPutThenGetTags() throws Exception {
        UserContextHolder.setCurrentUser(ADMIN_USER);
        Long id = createSkill("t4-put-" + System.nanoTime());

        ServerResponse putResp = route(skillAdminRouter, "PUT",
                "/spring/ai/loom/admin/market-skills/" + id + "/tags",
                json(Map.of("tags", List.of("spring", "java"))));
        assertEquals(200, putResp.statusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> putBody = (Map<String, Object>) ((EntityResponse<?>) putResp).entity();
        assertEquals(List.of("java", "spring"), putBody.get("tags"),
                "replaceTags 后响应 = listTags(ORDER BY tag ASC)");

        ServerResponse getResp = route(skillAdminRouter, "GET",
                "/spring/ai/loom/admin/market-skills/" + id + "/tags", null);
        assertEquals(200, getResp.statusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> getBody = (Map<String, Object>) ((EntityResponse<?>) getResp).entity();
        assertEquals(List.of("java", "spring"), getBody.get("tags"));

        // DB 端真实落行
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM market_skill_tag WHERE market_skill_id = ?", Integer.class, id);
        assertEquals(2, n);
    }

    @Test
    @DisplayName("非 admin PUT/GET tags → 403")
    void nonAdminGets403() throws Exception {
        UserContextHolder.setCurrentUser(NORMAL_USER);
        Long id = createSkill("t4-403-" + System.nanoTime());

        ServerResponse putResp = route(skillAdminRouter, "PUT",
                "/spring/ai/loom/admin/market-skills/" + id + "/tags",
                json(Map.of("tags", List.of("x"))));
        assertEquals(403, putResp.statusCode().value());

        ServerResponse getResp = route(skillAdminRouter, "GET",
                "/spring/ai/loom/admin/market-skills/" + id + "/tags", null);
        assertEquals(403, getResp.statusCode().value());
    }

    @Test
    @DisplayName("非数字 id → 400(admin PUT/GET)")
    void nonNumericIdReturns400() throws Exception {
        UserContextHolder.setCurrentUser(ADMIN_USER);

        ServerResponse putResp = route(skillAdminRouter, "PUT",
                "/spring/ai/loom/admin/market-skills/abc/tags",
                json(Map.of("tags", List.of("x"))));
        assertEquals(400, putResp.statusCode().value());

        ServerResponse getResp = route(skillAdminRouter, "GET",
                "/spring/ai/loom/admin/market-skills/abc/tags", null);
        assertEquals(400, getResp.statusCode().value());
    }

    @Test
    @DisplayName("未知 id PUT tags → 404 (service IAE → router 404)")
    void unknownIdPutReturns404() throws Exception {
        UserContextHolder.setCurrentUser(ADMIN_USER);
        ServerResponse resp = route(skillAdminRouter, "PUT",
                "/spring/ai/loom/admin/market-skills/999999999999/tags",
                json(Map.of("tags", List.of("x"))));
        assertEquals(404, resp.statusCode().value());
    }

    @Test
    @DisplayName("tags 字段非数组 → 400")
    void nonArrayTagsReturns400() throws Exception {
        UserContextHolder.setCurrentUser(ADMIN_USER);
        Long id = createSkill("t4-400-" + System.nanoTime());
        ServerResponse resp = route(skillAdminRouter, "PUT",
                "/spring/ai/loom/admin/market-skills/" + id + "/tags",
                "{\"tags\": \"not-an-array\"}");
        assertEquals(400, resp.statusCode().value());
    }

    /* ===== public GET {id}/tags ===== */

    @Test
    @DisplayName("public GET {id}/tags — 有 tag 返回 {tags};未知 id 返回 [] (no 4xx)")
    void publicGetTags() throws Exception {
        UserContextHolder.setCurrentUser(NORMAL_USER);
        Long id = createSkill("t4-pub-" + System.nanoTime());
        tagService.replaceTags(SkillTagService.MARKET_KIND_SKILL, id, List.of("rag", "llm"));

        ServerResponse resp = route(skillPublicRouter, "GET",
                "/spring/ai/loom/market-skills/" + id + "/tags", null);
        assertEquals(200, resp.statusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ((EntityResponse<?>) resp).entity();
        assertEquals(List.of("llm", "rag"), body.get("tags"));

        // 未知 id → [] 而非 4xx
        ServerResponse unknownResp = route(skillPublicRouter, "GET",
                "/spring/ai/loom/market-skills/999999999999/tags", null);
        assertEquals(200, unknownResp.statusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> unknownBody = (Map<String, Object>) ((EntityResponse<?>) unknownResp).entity();
        assertEquals(List.of(), unknownBody.get("tags"));
    }

    @Test
    @DisplayName("public GET {id}/tags — 非数字 id → 400")
    void publicGetTagsNonNumericReturns400() throws Exception {
        UserContextHolder.setCurrentUser(NORMAL_USER);
        ServerResponse resp = route(skillPublicRouter, "GET",
                "/spring/ai/loom/market-skills/not-a-number/tags", null);
        assertEquals(400, resp.statusCode().value());
    }

    /* ===== ?tag= 分支:APPROVED-only + enrich(tags + announcement) ===== */

    /**
     * FU-1-fix 差异化断言:?tag= 路径返回的行必须同时携带 tags 与
     * announcementTitle(svc.enrich 批量补回);PENDING 行即使挂了同一 tag 也不出现。
     * 响应形态 = 裸 List(与 KB ?tag= 分支一致),非 Page。
     */
    @Test
    @DisplayName("?tag=x → APPROVED-only 裸 List,行内嵌 tags + announcementTitle (FU-1 fix)")
    void tagQueryReturnsApprovedEnrichedRows() throws Exception {
        String tag = "t4-tag-" + System.nanoTime();
        UserContextHolder.setCurrentUser(ADMIN_USER);
        Long approvedId = createSkill("t4-approved-" + System.nanoTime());
        skillSvc.approve(approvedId, ADMIN_USER);
        annRepo.upsert("SKILL", String.valueOf(approvedId), "t4-ann-title", "t4-ann-body");
        tagService.replaceTags(SkillTagService.MARKET_KIND_SKILL, approvedId, List.of(tag, "extra"));

        // PENDING 行挂同一 tag — 必须被 post-filter 掉
        Long pendingId = createSkill("t4-pending-" + System.nanoTime());
        tagService.replaceTags(SkillTagService.MARKET_KIND_SKILL, pendingId, List.of(tag));

        UserContextHolder.setCurrentUser(NORMAL_USER);
        ServerResponse resp = route(skillPublicRouter, "GET",
                "/spring/ai/loom/market-skills?tag=" + tag, null);
        assertEquals(200, resp.statusCode().value());
        @SuppressWarnings("unchecked")
        List<MarketSkill> rows = (List<MarketSkill>) ((EntityResponse<?>) resp).entity();
        assertNotNull(rows, "?tag= 分支必须返回裸 List(KB 镜像形态)");

        assertTrue(rows.stream().noneMatch(r -> pendingId.equals(r.id())),
                "PENDING 行不得出现在公开 ?tag= 结果中");
        MarketSkill row = rows.stream()
                .filter(r -> approvedId.equals(r.id()))
                .findFirst()
                .orElse(null);
        assertNotNull(row, "APPROVED 行必须出现");
        assertEquals("APPROVED", row.status());
        assertEquals(List.of("extra", tag), row.tags(),
                "tags 必须嵌入(ORDER BY tag ASC)");
        assertEquals("t4-ann-title", row.announcementTitle(),
                "FU-1 fix:?tag= 路径必须经 svc.enrich 补回 announcementTitle");
        assertEquals("t4-ann-body", row.announcementBody());
    }

    @Test
    @DisplayName("?tag=a&tag=b → AND 交集语义(只返回同时挂两个 tag 的行)")
    void tagQueryIntersectionSemantics() throws Exception {
        String tagA = "t4-and-a-" + System.nanoTime();
        String tagB = "t4-and-b-" + System.nanoTime();
        UserContextHolder.setCurrentUser(ADMIN_USER);
        Long bothId = createSkill("t4-both-" + System.nanoTime());
        skillSvc.approve(bothId, ADMIN_USER);
        tagService.replaceTags(SkillTagService.MARKET_KIND_SKILL, bothId, List.of(tagA, tagB));
        Long onlyAId = createSkill("t4-only-a-" + System.nanoTime());
        skillSvc.approve(onlyAId, ADMIN_USER);
        tagService.replaceTags(SkillTagService.MARKET_KIND_SKILL, onlyAId, List.of(tagA));

        UserContextHolder.setCurrentUser(NORMAL_USER);
        ServerResponse resp = route(skillPublicRouter, "GET",
                "/spring/ai/loom/market-skills?tag=" + tagA + "&tag=" + tagB, null);
        assertEquals(200, resp.statusCode().value());
        @SuppressWarnings("unchecked")
        List<MarketSkill> rows = (List<MarketSkill>) ((EntityResponse<?>) resp).entity();
        assertTrue(rows.stream().anyMatch(r -> bothId.equals(r.id())),
                "同时挂 a+b 的行必须出现");
        assertTrue(rows.stream().noneMatch(r -> onlyAId.equals(r.id())),
                "只挂 a 的行必须被 AND 语义排除");
    }

    private Long createSkill(String name) {
        return skillSvc.create(ADMIN_USER, new MarketCreateRequest(name, "d", "c", null)).id();
    }
}
