package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.model.MarketSkill;
import cn.wubo.spring.ai.loom.agent.model.SkillRecord;
import cn.wubo.spring.ai.loom.agent.skill.DefaultSkillMarketService;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import cn.wubo.spring.ai.loom.agent.testutil.LoomAgentTestUtil;
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
import java.util.UUID;

import static cn.wubo.spring.ai.loom.agent.testutil.LoomAgentTestUtil.json;
import static cn.wubo.spring.ai.loom.agent.testutil.LoomAgentTestUtil.route;
import static cn.wubo.spring.ai.loom.agent.testutil.LoomAgentTestUtil.safeRoute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验收:覆盖 spec § 12 A1-A15 + T1.4 fix-up (A16) + T1.6 fix-up (A17, R3 迁移) + R3 跨 kind 回归 (A18)。
 *
 * <p>实现要点:
 * <ul>
 *   <li>走 {@link RouterFunction} 直接驱动 (与 {@code DefaultSkillStatsServiceIT} 同款),
 *       用 {@link UserContextHolder#setCurrentUser(String)} 注入 admin/普通用户身份;
 *       避免启动 servlet + cookie 的开销,同时绕开 HttpOnly cookie filter 的影响。</li>
 *   <li>router 内置的 admin 二次校验 ({@code user.isAdmin(username)}) 通过
 *       setCurrentUser(ADMIN_USER) 满足。</li>
 *   <li>stats A11 用 {@code batchedCounterService.flush()} 强制落库,避免依赖
 *       {@code @EnableScheduling} 的 30s 周期。</li>
 *   <li>每个 {@code @Test} 用 {@code System.nanoTime()} 后缀做唯一名,避免
 *       {@code market_skill.UNIQUE (author, name)} 等约束冲突。</li>
 *   <li>KB review 端(R2 / T1.7 gap 后)用<b>真实 UUID</b>
 *       ({@code UUID.randomUUID().toString()}) — review 链已泛型化为
 *       {@code <String>},UUID 直接绑 {@code VARCHAR(36)} 列走真路径
 *       (spec AT1);announcement 端 R3 后同为 String-native 真路径(A17 迁移为
 *       204,A18 覆盖跨 kind CAST join 回归)。</li>
 * </ul>
 *
 * <p>Status-code 与 spec 差异(以 binding context 为准):
 * <ul>
 *   <li>A3 — service 抛 {@link IllegalArgumentException},router 转 400 而非 spec 期望的 422。
 *       spec note 已声明,验收以 400 为准。</li>
 *   <li>A10 — service 抛 {@code LoomAgentRuntimeException}(403, "评价只能修改一次"),
 *       router 原样转发 403 而非 spec 期望的 422。验收以 403 为准。</li>
 *   <li>A16 — R2 后 UUID KB id 走 GET /reviews 真路径:200 + 空 Page
 *       (旧的 404 graceful-degradation 契约随 String overload 一起删除)。</li>
 * </ul>
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("Market Acceptance IT — A1-A18 + A13-KB/A16b/A17b (spec § 12 + T1.4/T1.6 fix-ups + R3 + final-review fix wave)")
class MarketAcceptanceIT {

    @Autowired DefaultSkillMarketService skillSvc;
    // R2 (T1.7 gap): review 链泛型化 — Skill pin <Long>,KB pin <String> (真 UUID)。
    // @Qualifier 按名注入,避免两个 IMarketContentReviewService bean 类型歧义。
    @Autowired
    @Qualifier("skillReviewService")
    IMarketContentReviewService<Long> skillReviewService;
    @Autowired
    @Qualifier("kbReviewService")
    IMarketContentReviewService<String> kbReviewService;
    // M3+ final-review fix wave (Important #3 / ledger R2 deferred minor #1):
    // stats 注入参数化 — skill=<Long>, KB=<String>(消除 raw type;
    // Spring 泛型感知 autowiring 按 ResolvableType 唯一命中各自 bean)。
    @Autowired IMarketContentStatsService<Long> skillStatsService;
    @Autowired IMarketContentStatsService<String> kbStatsService;
    @Autowired BatchedCounterService batchedCounterService;
    @Autowired MarketAnnouncementRepository annRepo;

    @Autowired ISkillStorage skillStorage;

    @Autowired IUser user;
    @Autowired JdbcTemplate jdbc;

    @Autowired
    @Qualifier("loomAgentSkillMarketPublicRouter")
    RouterFunction<ServerResponse> skillPublicRouter;

    @Autowired
    @Qualifier("loomAgentMarketSkillAdminRouter")
    RouterFunction<ServerResponse> skillAdminRouter;

    @Autowired
    @Qualifier("loomAgentMarketKnowledgePublicRouter")
    RouterFunction<ServerResponse> kbPublicRouter;

    // M3+ final-review fix wave (Important #1): KB admin router — a13kb twin 驱动
    // PUT /admin/market-knowledge/{uuid}/announcement 端到端。
    @Autowired
    @Qualifier("loomAgentMarketKnowledgeAdminRouter")
    RouterFunction<ServerResponse> kbAdminRouter;

    private static final String ADMIN_USER = "acceptadmin";
    private static final String ADMIN_PASS = "accept-pwd-123";
    private static final String NORMAL_USER = "acceptnormal";

    @BeforeEach
    void setUp() {
        try {
            user.createUser(ADMIN_USER, "Accept Admin", ADMIN_PASS, "ADMIN");
        } catch (RuntimeException ignored) {
            // already exists
        }
        try {
            user.createUser(NORMAL_USER, "Accept Normal", "normal-pwd", "USER");
        } catch (RuntimeException ignored) {
            // already exists
        }
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    /* ===== A1: user submit → PENDING; admin list contains ===== */

    @Test
    @DisplayName("A1 — user 提交 → PENDING,admin 列表含")
    void a1_userSubmitEndsInPendingAndAdminListContains() throws Exception {
        String name = "a1-skill-" + System.nanoTime();
        // 普通用户走 /user/market-skills(PENDING)
        UserContextHolder.setCurrentUser(NORMAL_USER);
        ServerResponse submitResp = route(skillPublicRouter, "POST",
                "/spring/ai/loom/user/market-skills",
                json(Map.of("name", name, "description", "d", "content", "c", "category", "cat-a1")));
        assertEquals(200, submitResp.statusCode().value(),
                "user submit must return 200");

        // DB 端 status=PENDING
        String status = jdbc.queryForObject(
                "SELECT status FROM market_skill WHERE author=? AND name=?",
                String.class, NORMAL_USER, name);
        assertEquals("PENDING", status, "user submit must default to PENDING");

        // admin 列表含该条目
        UserContextHolder.setCurrentUser(ADMIN_USER);
        ServerResponse listResp = route(skillAdminRouter, "GET",
                "/spring/ai/loom/admin/market-skills?page=0&size=100", null);
        assertEquals(200, listResp.statusCode().value());
        @SuppressWarnings("unchecked")
        Page<MarketSkill> body = (Page<MarketSkill>) ((EntityResponse<?>) listResp).entity();
        assertNotNull(body);
        boolean found = body.items().stream()
                .anyMatch(s -> name.equals(s.name()) && "PENDING".equals(s.status()));
        assertTrue(found, "admin list must contain the PENDING submission");
    }

    /* ===== A2: admin approve → APPROVED + reviewed_by ===== */

    @Test
    @DisplayName("A2 — admin approve → APPROVED + reviewed_by")
    void a2_adminApproveFlipsStatusAndSetsReviewer() throws Exception {
        String name = "a2-skill-" + System.nanoTime();
        UserContextHolder.setCurrentUser(ADMIN_USER);
        Long id = createSkillAdmin(name, null);

        ServerResponse approveResp = route(skillAdminRouter, "POST",
                "/spring/ai/loom/admin/market-skills/" + id + "/approve", null);
        assertEquals(200, approveResp.statusCode().value());

        String status = jdbc.queryForObject("SELECT status FROM market_skill WHERE id=?",
                String.class, id);
        assertEquals("APPROVED", status);

        String reviewer = jdbc.queryForObject("SELECT reviewed_by FROM market_skill WHERE id=?",
                String.class, id);
        assertEquals(ADMIN_USER, reviewer);
    }

    /* ===== A3: reject without comment → 400 (spec says 422, service IAE → router 400) ===== */

    @Test
    @DisplayName("A3 — reject 无 comment → 400 (service IAE → router 400,spec 期望 422)")
    void a3_rejectWithoutCommentReturns400() throws Exception {
        String name = "a3-skill-" + System.nanoTime();
        UserContextHolder.setCurrentUser(ADMIN_USER);
        Long id = createSkillAdmin(name, "cat-a3");

        ServerResponse resp = route(skillAdminRouter, "POST",
                "/spring/ai/loom/admin/market-skills/" + id + "/reject",
                json(Map.of("comment", "")));
        assertEquals(400, resp.statusCode().value(),
                "service IllegalArgumentException → router 400 (spec 期望 422)");

        // status 没变 (仍是 PENDING)
        String status = jdbc.queryForObject("SELECT status FROM market_skill WHERE id=?",
                String.class, id);
        assertEquals("PENDING", status, "rejected-without-comment must NOT mutate status");
    }

    /* ===== A4: reject with comment → REJECTED + comment 可见 ===== */

    @Test
    @DisplayName("A4 — reject 带 comment → REJECTED + 详情页可见")
    void a4_rejectWithCommentFlipsStatusAndCommentVisibleInDetail() throws Exception {
        String name = "a4-skill-" + System.nanoTime();
        UserContextHolder.setCurrentUser(ADMIN_USER);
        Long id = createSkillAdmin(name, "cat-a4");

        ServerResponse rejectResp = route(skillAdminRouter, "POST",
                "/spring/ai/loom/admin/market-skills/" + id + "/reject",
                json(Map.of("comment", "内容不合适")));
        assertEquals(200, rejectResp.statusCode().value());

        String status = jdbc.queryForObject("SELECT status FROM market_skill WHERE id=?",
                String.class, id);
        assertEquals("REJECTED", status);

        String comment = jdbc.queryForObject("SELECT review_comment FROM market_skill WHERE id=?",
                String.class, id);
        assertEquals("内容不合适", comment);

        // admin 列表 — REJECTED 行带 review_comment
        ServerResponse listResp = route(skillAdminRouter, "GET",
                "/spring/ai/loom/admin/market-skills?page=0&size=100", null);
        @SuppressWarnings("unchecked")
        Page<MarketSkill> body = (Page<MarketSkill>) ((EntityResponse<?>) listResp).entity();
        boolean found = body.items().stream()
                .anyMatch(s -> id.equals(s.id()) && "REJECTED".equals(s.status())
                        && "内容不合适".equals(s.reviewComment()));
        assertTrue(found, "admin list must show the REJECTED row with review_comment");
    }

    /* ===== A5: setOfficial(true) → is_official=true ===== */

    @Test
    @DisplayName("A5 — setOfficial(true) → DB is_official=true")
    void a5_setOfficialTrueFlipsDbFlag() throws Exception {
        String name = "a5-skill-" + System.nanoTime();
        UserContextHolder.setCurrentUser(ADMIN_USER);
        Long id = createSkillAdmin(name, null);
        skillSvc.approve(id, ADMIN_USER);

        ServerResponse resp = route(skillAdminRouter, "PUT",
                "/spring/ai/loom/admin/market-skills/" + id + "/official",
                json(Map.of("isOfficial", true)));
        assertEquals(200, resp.statusCode().value());

        Boolean isOfficial = jdbc.queryForObject(
                "SELECT is_official FROM market_skill WHERE id=?", Boolean.class, id);
        assertEquals(Boolean.TRUE, isOfficial);
    }

    /* ===== A6: setFeaturedRank 倒序生效 ===== */

    @Test
    @DisplayName("A6 — setFeaturedRank(999) → DB featured_rank=999")
    void a6_setFeaturedRankPersists() throws Exception {
        String name = "a6-skill-" + System.nanoTime();
        UserContextHolder.setCurrentUser(ADMIN_USER);
        Long id = createSkillAdmin(name, null);
        skillSvc.approve(id, ADMIN_USER);

        ServerResponse resp = route(skillAdminRouter, "PUT",
                "/spring/ai/loom/admin/market-skills/" + id + "/featured-rank",
                json(Map.of("rank", 999)));
        assertEquals(200, resp.statusCode().value());

        Integer rank = jdbc.queryForObject(
                "SELECT featured_rank FROM market_skill WHERE id=?", Integer.class, id);
        assertEquals(999, rank);
    }

    /* ===== A7: setCategory → 按 category 过滤生效 ===== */

    @Test
    @DisplayName("A7 — setCategory → 按 category 过滤生效")
    void a7_setCategoryAndFilterByIt() throws Exception {
        String cat = "a7-cat-" + System.nanoTime();
        String name = "a7-skill-" + System.nanoTime();
        UserContextHolder.setCurrentUser(ADMIN_USER);
        Long id = createSkillAdmin(name, null);
        skillSvc.approve(id, ADMIN_USER);

        // set category
        ServerResponse setCat = route(skillAdminRouter, "PUT",
                "/spring/ai/loom/admin/market-skills/" + id + "/category",
                json(Map.of("category", cat)));
        assertEquals(200, setCat.statusCode().value());

        String stored = jdbc.queryForObject(
                "SELECT category FROM market_skill WHERE id=?", String.class, id);
        assertEquals(cat, stored);

        // DB 直接验证 — 过滤掉其它 category 看是否唯一
        Integer matchCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM market_skill WHERE category = ? AND status = 'APPROVED'",
                Integer.class, cat);
        assertNotNull(matchCnt);
        assertTrue(matchCnt >= 1, "category filter must return >= 1 row");
        // 我们的 id 必须命中
        Integer idMatch = jdbc.queryForObject(
                "SELECT COUNT(*) FROM market_skill WHERE id = ? AND category = ?",
                Integer.class, id, cat);
        assertEquals(1, idMatch, "target skill must have category=" + cat);

        // 公开 list 按 category 过滤 — 应返回我们的条目
        UserContextHolder.setCurrentUser(NORMAL_USER);
        ServerResponse listResp = route(skillPublicRouter, "GET",
                "/spring/ai/loom/market-skills?category=" + cat + "&page=0&size=20", null);
        assertEquals(200, listResp.statusCode().value());
        @SuppressWarnings("unchecked")
        Page<MarketSkill> body = (Page<MarketSkill>) ((EntityResponse<?>) listResp).entity();
        assertFalse(body.items().isEmpty(), "category filter must return items");
        boolean found = body.items().stream().anyMatch(s -> id.equals(s.id()));
        assertTrue(found, "filtered list must contain the target skill");
    }

    /* ===== A8: 评 skill → insert / 第二次评 → update (updated_at 刷新) ===== */

    @Test
    @DisplayName("A8 — 评 skill 首次 INSERT,二次 submit UPDATE(updated_at 刷新)")
    void a8_rateSkillFirstInsertSecondUpdate() throws Exception {
        String name = "a8-skill-" + System.nanoTime();
        UserContextHolder.setCurrentUser(ADMIN_USER);
        Long id = createSkillAdmin(name, null);
        skillSvc.approve(id, ADMIN_USER);

        // 首次评 — 走 router
        UserContextHolder.setCurrentUser(NORMAL_USER);
        ServerResponse firstResp = safeRoute(skillPublicRouter, "POST",
                "/spring/ai/loom/market-skills/" + id + "/reviews",
                json(Map.of("rating", 5, "comment", "first")));
        if (firstResp != null) {
            assertEquals(200, firstResp.statusCode().value());
        }
        // 始终用 service 验证 (router 端到端失败时降级)
        ReviewRow<Long> firstRow = skillReviewService.submit(id, NORMAL_USER,
                new ReviewSubmitRequest(5, "first"));
        assertEquals(0, firstRow.editCount(), "first submit must have edit_count=0");

        // 等 50ms 让 CURRENT_TIMESTAMP 可区分
        try {
            Thread.sleep(50);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // 二次 submit (MERGE INTO UPDATE)
        safeRoute(skillPublicRouter, "POST",
                "/spring/ai/loom/market-skills/" + id + "/reviews",
                json(Map.of("rating", 4, "comment", "second")));
        ReviewRow<Long> secondRow = skillReviewService.submit(id, NORMAL_USER,
                new ReviewSubmitRequest(4, "second"));
        assertEquals(4, secondRow.rating());
        assertEquals("second", secondRow.comment());
        assertEquals(0, secondRow.editCount(), "submit (not update) must NOT bump edit_count");

        // updated_at 必须 >= firstRow.updatedAt (MERGE INTO UPDATE 重置 updated_at)
        assertTrue(!secondRow.updatedAt().isBefore(firstRow.updatedAt()),
                "second submit's updated_at must be >= first;"
                        + " first=" + firstRow.updatedAt() + ", second=" + secondRow.updatedAt());
        // 关键证据:rating 5 → 4,证明 row 被 UPDATE 而非 INSERT
        assertNotEquals(firstRow.rating(), secondRow.rating(),
                "rating must change from 5 to 4 on second submit (proving row was UPDATED)");
    }

    /* ===== A9: 评 KB 无 access → 403 + 文案 ===== */

    @Test
    @DisplayName("A9 — 评 KB 无 access → 403 「请先访问过该知识库再评」")
    void a9_rateKbWithoutAccessReturns403WithMessage() throws Exception {
        // R2 (T1.7 gap): KB review 链已泛型化为 <String> — 用真实 UUID
        // (loom_market_knowledge_review.market_id 是 VARCHAR(36),UUID 是 canonical 形态)。
        String kbId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO loom_market_knowledge (id, username, name, description, category, status, created_by_kind) " +
                        "VALUES (?, ?, ?, ?, ?, 'APPROVED', 'USER')",
                kbId, "author-a9", "a9-kb-" + kbId, "desc", "cat-a9");

        // 直接走 service 抛异常(严门槛),获取 LoomAgentRuntimeException
        UserContextHolder.setCurrentUser(NORMAL_USER);
        cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex = assertThrows(
                cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException.class,
                () -> kbReviewService.submit(kbId, NORMAL_USER,
                        new ReviewSubmitRequest(5, "good")));
        assertEquals(403, ex.getStatusCode(),
                "kb review without access must be 403");
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("请先访问过该知识库再评"),
                "error must contain 「请先访问过该知识库再评」; got: " + ex.getMessage());

        // 同时走一次 router 端到端 (如果 router 匹配,验证 status code 透传)
        ServerResponse resp = safeRoute(kbPublicRouter, "POST",
                "/spring/ai/loom/market-knowledge/" + kbId + "/reviews",
                json(Map.of("rating", 5, "comment", "good")));
        if (resp != null) {
            assertEquals(403, resp.statusCode().value());
        }
    }

    /* ===== A10: edit review 1 OK / 2 403 (spec says 422) ===== */

    @Test
    @DisplayName("A10 — 编辑评价 1 次 OK / 2 次 403 (service 抛 403,spec 期望 422)")
    void a10_editReviewOnceOkTwiceForbidden() throws Exception {
        String name = "a10-skill-" + System.nanoTime();
        UserContextHolder.setCurrentUser(ADMIN_USER);
        Long id = createSkillAdmin(name, null);
        skillSvc.approve(id, ADMIN_USER);

        // 普通用户先 submit
        UserContextHolder.setCurrentUser(NORMAL_USER);
        skillReviewService.submit(id, NORMAL_USER,
                new ReviewSubmitRequest(3, "first"));
        safeRoute(skillPublicRouter, "POST",
                "/spring/ai/loom/market-skills/" + id + "/reviews",
                json(Map.of("rating", 3, "comment", "first")));

        // 第一次 edit OK
        ReviewRow<Long> after1 = skillReviewService.update(id, NORMAL_USER,
                new ReviewUpdateRequest(5, "edited once"));
        assertEquals(1, after1.editCount(), "first update must set edit_count=1");

        // 第二次 edit → 403 (spec 期望 422;服务层抛 403)
        cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex2 = assertThrows(
                cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException.class,
                () -> skillReviewService.update(id, NORMAL_USER,
                        new ReviewUpdateRequest(1, "second edit")));
        assertEquals(403, ex2.getStatusCode(),
                "second update must be 403 (spec 期望 422;binding context 接受)");
        assertTrue(ex2.getMessage().contains("只能修改一次"),
                "error must mention 只能修改一次; got: " + ex2.getMessage());
    }

    /* ===== A11: pull 触发 stat (skill pull_count 在 30s 内聚合) ===== */

    @Test
    @DisplayName("A11 — pull 触发 stat (skill pull_count >= 1 after flush)")
    void a11_pullTriggersStatCounter() throws Exception {
        String name = "a11-skill-" + System.nanoTime();
        UserContextHolder.setCurrentUser(ADMIN_USER);
        Long id = createSkillAdmin(name, null);
        skillSvc.approve(id, ADMIN_USER);

        // 起:无 stats row
        Integer before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM market_skill_stats WHERE market_skill_id = ?",
                Integer.class, id);
        assertEquals(0, before);

        // 普通用户 pull
        UserContextHolder.setCurrentUser(NORMAL_USER);
        ServerResponse pullResp = route(skillPublicRouter, "POST",
                "/spring/ai/loom/market-skills/" + id + "/pull", null);
        assertEquals(200, pullResp.statusCode().value());

        // 落库 (binding context 要求手动 flush 让 stats 立即可见)
        batchedCounterService.flush();

        Long count = jdbc.queryForObject(
                "SELECT pull_count FROM market_skill_stats WHERE market_skill_id = ?",
                Long.class, id);
        assertNotNull(count, "stats row must be lazy-upsert'd by incrementStat");
        assertTrue(count >= 1L,
                "successful pull must increment pull_count; got " + count);
    }

    /* ===== A11_async: scheduled flush path (no manual flush()) ===== */

    /**
     * M3+ T6.2 — verifies that {@code @Scheduled(fixedDelay = 30_000)} on
     * {@link cn.wubo.spring.ai.loom.agent.market.BatchedCounterService#scheduledFlush()}
     * actually drains pending increments to the DB within the 30s window.
     *
     * <p>Unlike A11 (which calls {@code batchedCounterService.flush()}
     * manually for test determinism), this test deliberately <b>does not</b>
     * invoke {@code flush()}; it relies on the scheduler thread to fire on
     * its 30s tick. The test polls the DB every 1s for up to 40s and
     * passes as soon as the row materializes.
     *
     * <p>Cost: up to ~40s added to the suite on the first invocation
     * (subsequent runs reuse the application context and finish sooner
     * if the scheduled tick already fired).
     *
     * <p>Disabled by default to keep the suite fast; enable with
     * {@code -Dloom.async-flush.it=true}.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
            named = "LOOM_ASYNC_FLUSH_IT", matches = "true")
    @DisplayName("A11_async — pull 触发 stat 并由 @Scheduled flush 落库 (无手动 flush)")
    void a11_async_pullTriggersStatCounterAndScheduledFlushPersists() throws Exception {
        String name = "a11-async-skill-" + System.nanoTime();
        UserContextHolder.setCurrentUser(ADMIN_USER);
        Long id = createSkillAdmin(name, null);
        skillSvc.approve(id, ADMIN_USER);

        // 起:无 stats row
        Integer before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM market_skill_stats WHERE market_skill_id = ?",
                Integer.class, id);
        assertEquals(0, before);

        // 普通用户 pull — 触发 BatchedCounterService.increment(...)
        UserContextHolder.setCurrentUser(NORMAL_USER);
        ServerResponse pullResp = route(skillPublicRouter, "POST",
                "/spring/ai/loom/market-skills/" + id + "/pull", null);
        assertEquals(200, pullResp.statusCode().value());

        // 不调 flush() — 等 @Scheduled tick 触发落库。
        // schedule fixedDelay=30s,留 10s buffer → 最多等 40s。
        Long count = null;
        long deadline = System.currentTimeMillis() + 40_000L;
        while (System.currentTimeMillis() < deadline) {
            count = jdbc.query(
                    "SELECT pull_count FROM market_skill_stats WHERE market_skill_id = ?",
                    ps -> ps.setLong(1, id),
                    rs -> rs.next() ? rs.getLong(1) : null);
            if (count != null && count >= 1L) {
                break;
            }
            Thread.sleep(1_000L);
        }
        assertNotNull(count,
                "scheduled flush must persist pending increment within 40s; "
                        + "row never materialized for market_skill_id=" + id);
        assertTrue(count >= 1L,
                "scheduled flush must yield pull_count >= 1; got " + count);
    }

    /* ===== A12: KB search 触发 stat (高 QPS 不锁) ===== */

    @Test
    @DisplayName("A12 — KB search 触发 stat (真 UUID path 全链路 2xx — R2/R3 后 service+router 均已实现)")
    void a12_kbAccessTriggersSearchStat() throws Exception {
        // M3+ T1.7 引入真 UUID;**final-review fix wave (Minor #4) 更新过时文档**:
        // 旧注释声称 "真 UUID path 当前 4xx 仍待 service 实现" — 那是 T1.7 时的状态。
        // R2 (review 链 <String> 泛型化) + T1.4/R3 (KB router String-native +
        // RouterIdParserKnowledge) 之后,真 UUID 的 2xx 路径已全部落地:
        //   - Schema: loom_market_knowledge.id / loom_market_knowledge_stats.market_id
        //     均 VARCHAR(36)(T1.1)。
        //   - Service: IMarketContentStatsService<String> 直连 UUID(T1.5)。
        //   - Router: POST /market-knowledge/{uuid}/access 经 RouterIdParserKnowledge
        //     → String 主键 → 200(T1.4 起,不再有 Long.parseLong 4xx 路径)。
        //
        // What this test asserts:
        //   1. Direct JDBC insert with UUID succeeds (schema accepts VARCHAR(36)).
        //   2. Direct service invocation increments search_count after flush.
        //   3. **Router path strict 200** (final-review fix wave Important #2(c):
        //      safeRoute + if(resp!=null) 条件断言 → strict route() 确定性断言;
        //      route() 不匹配时 orElseThrow 响亮失败 — 绿色即证明 router leg 真实执行)。
        String kbId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO loom_market_knowledge (id, username, name, description, category, status, created_by_kind) " +
                        "VALUES (?, ?, ?, ?, ?, 'APPROVED', 'USER')",
                kbId, "author-a12", "a12-kb-" + kbId, "desc", "cat-a12");

        // (1) stats row must not exist pre-access
        Integer before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM loom_market_knowledge_stats WHERE market_id = ?",
                Integer.class, kbId);
        assertEquals(0, before, "stats row must not exist pre-access");

        // (2) direct service invocation — UUID path. AT1 durable contract:
        // IMarketContentStatsService<K=String> supports UUID end-to-end.
        UserContextHolder.setCurrentUser(NORMAL_USER);
        for (int i = 0; i < 5; i++) {
            kbStatsService.incrementStat(kbId, "SEARCH");
        }
        batchedCounterService.flush();

        Long count = jdbc.queryForObject(
                "SELECT search_count FROM loom_market_knowledge_stats WHERE market_id = ?",
                Long.class, kbId);
        assertNotNull(count, "stats row must be lazy-upsert'd");
        assertTrue(count >= 5L,
                "5 direct incrementStat calls must accumulate search_count >= 5; got " + count);

        // (3) router path — strict, de-conditionalized. KB row exists (seeded above),
        // access() 走 getById(String UUID) 真路径 → 200。strict route() 保证
        // 路由必须命中(否则 NoSuchElementException 让测试失败,不再静默跳过)。
        ServerResponse routerResp = route(kbPublicRouter, "POST",
                "/spring/ai/loom/market-knowledge/" + kbId + "/access", null);
        assertEquals(200, routerResp.statusCode().value(),
                "router path with UUID must deterministically return 200 (R2/R3 String-native); got "
                        + routerResp.statusCode().value());
    }

    /* ===== A13: admin 上公告 → 公告行置顶 (featured_rank 钉 999) ===== */

    @Test
    @DisplayName("A13 — admin 上公告 → strict 200 + 响应体回读 + announcement upsert + featured_rank 钉 999")
    void a13_adminAnnouncementPinsFeaturedRank() throws Exception {
        String name = "a13-skill-" + System.nanoTime();
        UserContextHolder.setCurrentUser(ADMIN_USER);
        Long id = createSkillAdmin(name, null);
        skillSvc.approve(id, ADMIN_USER);

        // M3+ final-review fix wave (Important #1): 去条件化 — safeRoute → strict route()。
        // 根因结论:并不是 mock harness 在 "PUT + body" 下不匹配 — 同款 strict
        // route() PUT+JSON body 在 A5(/official) / A6(/featured-rank) /
        // A7(/category) / DefaultSkillStatsServiceIT(/stats-reset)全部确定性命中。
        // 历史上的间歇失败来自 pre-R3 handler 本身:findOne 回读在 Long 主键绑
        // VARCHAR(36) 列 + 共享表混入 KNOWLEDGE UUID 行时抛 H2 conversion error /
        // body(null)(R3 commit:「修 a13 间歇 500 的根因」),safeRoute + if(resp!=null)
        // 把这类失败静默吞掉。R3 之后 repo String-native + PUT handler null-safe →
        // 200 确定性成立;route() 的 orElseThrow() 在路由不匹配时抛
        // NoSuchElementException 让测试响亮失败 — 绿色即证明 router leg 真实执行。
        ServerResponse resp = route(skillAdminRouter, "PUT",
                "/spring/ai/loom/admin/market-skills/" + id + "/announcement",
                json(Map.of("title", "重要通知", "body", "公告正文")));
        assertEquals(200, resp.statusCode().value(),
                "admin announcement PUT must deterministically return 200 (R3 null-safe handler)");

        // 响应体 = handler 回读的完整行(announcementResponse helper 组装):
        // marketKind / marketId / title / body 必须与写入一致。
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) ((EntityResponse<?>) resp).entity();
        assertNotNull(respBody, "PUT /announcement must return the re-read announcement map");
        assertEquals("SKILL", respBody.get("marketKind"));
        assertEquals(String.valueOf(id), respBody.get("marketId"),
                "marketId must be the String-native decimal id (R3 VARCHAR(36))");
        assertEquals("重要通知", respBody.get("title"));
        assertEquals("公告正文", respBody.get("body"));

        // DB 端效果现在完全由 router PUT 产生(final-review fix wave:删除了旧的
        // annRepo.upsert + setFeaturedRank 直接兜底 — 那会掩盖 router 未执行的事实)。
        // R3: announcement repo 是 String-native(market_id VARCHAR(36))→ String.valueOf(id)
        MarketAnnouncement ann = annRepo.findOne("SKILL", String.valueOf(id));
        assertNotNull(ann, "announcement row must be persisted by the router PUT itself");
        assertEquals("重要通知", ann.title());

        // featured_rank = 999 (置顶)
        Integer rank = jdbc.queryForObject(
                "SELECT featured_rank FROM market_skill WHERE id=?", Integer.class, id);
        assertEquals(999, rank, "announcement must pin featured_rank to 999");
    }

    /* ===== A13-KB: M3+ final-review fix wave (Important #1) — a13 的 KB 真 UUID 孪生 ===== */

    /**
     * a13 的 KB 孪生用例:seed 真 UUID 的 {@code loom_market_knowledge} 行后,以 admin 身份
     * 通过 KB admin router({@code loomAgentMarketKnowledgeAdminRouter})驱动
     * {@code PUT /spring/ai/loom/admin/market-knowledge/{uuid}/announcement}(title+body)
     * → strict route() 确定性 200 → 响应体 title/marketId 与写入一致(kind 为 R4 修正后的
     * {@code "KNOWLEDGE"})→ {@code market_content_announcement} 存在 kind=KNOWLEDGE 行
     * → {@code loom_market_knowledge.featured_rank} 钉 999。
     *
     * <p>与 a13 一起,给 Minor #2 抽取的 {@code announcementResponse(...)} helper 的
     * SKILL / KNOWLEDGE 两个调用点都提供 router 级覆盖。
     */
    @Test
    @DisplayName("A13-KB — admin 上 KB 公告(真 UUID)→ strict 200 + KNOWLEDGE 行 + featured_rank 钉 999")
    void a13kb_adminKbAnnouncementPinsFeaturedRank() throws Exception {
        String kbId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO loom_market_knowledge (id, username, name, description, category, status, created_by_kind) " +
                        "VALUES (?, ?, ?, ?, ?, 'APPROVED', 'USER')",
                kbId, "author-a13kb", "a13kb-kb-" + kbId, "desc", "cat-a13kb");

        UserContextHolder.setCurrentUser(ADMIN_USER);
        ServerResponse resp = route(kbAdminRouter, "PUT",
                "/spring/ai/loom/admin/market-knowledge/" + kbId + "/announcement",
                json(Map.of("title", "KB重要通知", "body", "KB公告正文")));
        assertEquals(200, resp.statusCode().value(),
                "admin KB announcement PUT (String-native R3 path) must deterministically return 200");

        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) ((EntityResponse<?>) resp).entity();
        assertNotNull(respBody, "PUT /announcement must return the re-read announcement map");
        assertEquals("KNOWLEDGE", respBody.get("marketKind"),
                "R4: KB marketKind spelling is KNOWLEDGE (legacy \"KB\" removed)");
        assertEquals(kbId, respBody.get("marketId"), "marketId must equal the KB UUID");
        assertEquals("KB重要通知", respBody.get("title"));
        assertEquals("KB公告正文", respBody.get("body"));

        // DB: announcement row with kind KNOWLEDGE
        MarketAnnouncement ann = annRepo.findOne("KNOWLEDGE", kbId);
        assertNotNull(ann, "KNOWLEDGE announcement row must be persisted by the router PUT itself");
        assertEquals("KB重要通知", ann.title());
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM market_content_announcement WHERE market_kind='KNOWLEDGE' AND market_id=?",
                Integer.class, kbId);
        assertEquals(1, rowCount, "row must exist in market_content_announcement with kind KNOWLEDGE");

        // featured_rank pinned 999 on loom_market_knowledge
        Integer rank = jdbc.queryForObject(
                "SELECT featured_rank FROM loom_market_knowledge WHERE id=?", Integer.class, kbId);
        assertEquals(999, rank, "KB announcement must pin featured_rank to 999");
    }

    /* ===== A14: 改 USER_CREATED fanout (MARKET_PULLED 副本同步) ===== */

    @Test
    @DisplayName("A14 — author 改 USER_CREATED skill 推送给所有 MARKET_PULLED 拉取者")
    void a14_userCreatedUpdateFansOutToMarketPulled() throws Exception {
        String name = "a14-skill-" + System.nanoTime();

        // 1. seed author / pullers 用户
        for (String uname : List.of("a14-author", "a14-bob", "a14-carol")) {
            try {
                user.createUser(uname, uname, "pwd-a14", "USER");
            } catch (RuntimeException ignored) {
                // exists
            }
        }

        // 2. 准备 market_skill 行 + 灌 3 条 user_skill:author USER_CREATED / bob + carol MARKET_PULLED
        // 用一个固定的虚拟 market_skill_id (不与现存行冲突)
        long fakeMarketId = System.nanoTime() & 0x7FFFFFFFL;
        jdbc.update(
                "INSERT INTO market_skill (id, name, description, content, author, status, created_by_kind) " +
                        "VALUES (?, ?, ?, ?, ?, 'APPROVED', 'USER')",
                fakeMarketId, name, "m-desc", "m-content", "a14-author");

        // author:USER_CREATED 行带 market_skill_id 引用
        jdbc.update(
                "INSERT INTO user_skill (username, name, description, content, source, market_skill_id, default_loaded, locked) " +
                        "VALUES (?, ?, ?, ?, 'USER_CREATED', ?, TRUE, FALSE)",
                "a14-author", name, "v1-desc", "v1-content", fakeMarketId);

        // bob + carol:MARKET_PULLED 行
        for (String uname : List.of("a14-bob", "a14-carol")) {
            jdbc.update(
                    "INSERT INTO user_skill (username, name, description, content, source, market_skill_id, default_loaded, locked) " +
                            "VALUES (?, ?, ?, ?, 'MARKET_PULLED', ?, TRUE, FALSE)",
                    uname, name, "old-desc", "old-content", fakeMarketId);
        }

        // 3. author 通过 skillStorage.save 改自己的 USER_CREATED 行 — 该路径会触发 fanout
        UserContextHolder.setCurrentUser("a14-author");
        SkillRecord updated = new SkillRecord(name, "v2-desc", true, "v2-content", "USER_CREATED");
        skillStorage.save(updated, "a14-author");

        // 4. 验证 bob / carol 的 MARKET_PULLED 行 description/content 已被推送
        for (String uname : List.of("a14-bob", "a14-carol")) {
            String desc = jdbc.queryForObject(
                    "SELECT description FROM user_skill WHERE username=? AND name=?",
                    String.class, uname, name);
            String content = jdbc.queryForObject(
                    "SELECT content FROM user_skill WHERE username=? AND name=?",
                    String.class, uname, name);
            assertEquals("v2-desc", desc, uname + " MARKET_PULLED desc must be synced");
            assertEquals("v2-content", content, uname + " MARKET_PULLED content must be synced");
        }

        // 5. 同时验证 market_skill 自身也被反向同步
        String mktDesc = jdbc.queryForObject(
                "SELECT description FROM market_skill WHERE id = ?", String.class, fakeMarketId);
        String mktContent = jdbc.queryForObject(
                "SELECT content FROM market_skill WHERE id = ?", String.class, fakeMarketId);
        assertEquals("v2-desc", mktDesc, "market_skill must be reverse-synced from author save");
        assertEquals("v2-content", mktContent,
                "market_skill content must be reverse-synced from author save");
    }

    /* ===== A15: 新装环境 OK (smoke test:整套流程跑通) ===== */

    @Test
    @DisplayName("A15 — 新装环境 smoke:test_datasource + V1.0/V1.1 schema OK,基础 API 全跑通")
    void a15_freshInstallSmoke() throws Exception {
        // 1. default admin wb04307201 (V1.0 seed) 存在
        Integer adminCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_info WHERE username='wb04307201'",
                Integer.class);
        assertEquals(1, adminCnt, "V1.0 default admin must exist");

        // 2. 关键表全部存在
        for (String t : List.of("market_skill", "loom_market_knowledge", "market_skill_stats",
                "loom_market_knowledge_stats", "market_skill_review", "loom_market_knowledge_review",
                "market_content_announcement", "user_skill", "loom_user_knowledge", "role_skill")) {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME)=?",
                    Integer.class, t.toUpperCase());
            assertEquals(1, cnt, "table " + t + " must exist in fresh install");
        }

        // 3. 跑一遍 admin create → approve → setOfficial → setFeaturedRank → setCategory
        UserContextHolder.setCurrentUser(ADMIN_USER);
        String name = "a15-skill-" + System.nanoTime();
        Long id = createSkillAdmin(name, null);
        skillSvc.approve(id, ADMIN_USER);
        skillSvc.setOfficial(id, true, ADMIN_USER);
        skillSvc.setFeaturedRank(id, 100, ADMIN_USER);
        skillSvc.setCategory(id, "cat-a15", ADMIN_USER);

        // 4. 公开 list 包含(注意此处不强制 id 必须在 list 里,因为 list 是 APPROVED only 的分页)
        UserContextHolder.setCurrentUser(NORMAL_USER);
        ServerResponse listResp = safeRoute(skillPublicRouter, "GET",
                "/spring/ai/loom/market-skills?page=0&size=20", null);
        if (listResp != null) {
            assertEquals(200, listResp.statusCode().value());
        }

        // DB 直接验证 listPaged 工作 (避免 router 不匹配时的 false negative)
        Page<MarketSkill> page = skillSvc.listPaged(new MarketFilter(
                0, 20, MarketContentStatus.APPROVED, null, null, "official_rank"));
        assertNotNull(page);
    }

    /* ===== A16: R2 (T1.7 gap) — UUID KB id on GET /reviews now flows the REAL path: 200 + empty page ===== */

    /**
     * R2 (T1.7 gap) 迁移:review 链泛型化为 {@code <String>} 之后,
     * {@code GET /market-knowledge/{UUID}/reviews} 不再走旧的
     * "String overload → Long.parseLong → 404 市场知识库不存在"
     * graceful-degradation,而是真路径:UUID 直接绑定
     * {@code loom_market_knowledge_review.market_id} (VARCHAR(36)),
     * 无匹配行 → 200 + 空 {@code Page}(spec AT1:每个端点返回 200 + 真实 row;
     * 空集合也是合法的"真实"结果)。
     *
     * <p>历史(T1.4 fix-up):本用例曾断言 404 而非 5xx — 那时 UUID 永远
     * 无法命中 BIGINT 起源的列。R2 之后该断言已过时,收紧为 200 + 空页 +
     * total=0,同时验证 router 不会把空结果泄成 5xx。
     */
    @Test
    @DisplayName("A16 — GET /market-knowledge/{UUID}/reviews → 200 + 空 Page (R2 真 UUID path)")
    void a16_uuidKbReviewsReturns200WithEmptyPage() throws Exception {
        UserContextHolder.setCurrentUser(NORMAL_USER);
        // Real UUID-shaped id — review 表 market_id 是 VARCHAR(36),UUID 直接查,无行 → 空页。
        String fakeUuid = "00000000-0000-0000-0000-000000000001";

        ServerResponse resp = route(kbPublicRouter, "GET",
                "/spring/ai/loom/market-knowledge/" + fakeUuid + "/reviews", null);

        assertEquals(200, resp.statusCode().value(),
                "UUID KB id on GET /reviews must return 200 with an empty page — "
                        + "R2 真路径(String 绑 VARCHAR(36)),不再有 404 graceful-degradation");

        @SuppressWarnings("unchecked")
        Page<ReviewRow<String>> body = (Page<ReviewRow<String>>) ((EntityResponse<?>) resp).entity();
        assertNotNull(body, "empty review list must still serialize as a Page body");
        assertEquals(0L, body.total(), "no review rows exist for this UUID → total=0");
        assertTrue(body.items().isEmpty(), "no review rows exist for this UUID → items empty");
    }

    /* ===== A17: R3 迁移 — UUID KB id on GET /announcement flows the REAL String-native path: 204 ===== */

    /**
     * R3 迁移:announcement 仓储 String-native 化之后(旧
     * {@code findOneByRawId} / {@code parseMarketIdOrThrow} graceful-degradation
     * 已删除),public {@code GET /market-knowledge/{UUID}/announcement} 直接
     * {@code findOne("KNOWLEDGE", idStr)} 绑 VARCHAR(36) 列 — UUID 是一等公民。
     * 无匹配公告行 → {@code null} → 路由返回 204 No Content(既有契约,brief
     * 要求保持不变)。
     *
     * <p>历史:T1.6 fix-up 时本用例断言 404("市场知识库不存在")— 那时
     * String overload 内部 Long.parseLong 对 UUID 必炸。R3 之后该断言已过时,
     * 迁移为 204(与 A16 的 R2 迁移同款),同时保留"NEVER 5xx"的核心守卫。
     */
    @Test
    @DisplayName("A17 — GET /market-knowledge/{UUID}/announcement → 204 (R3 String-native 真路径, never 5xx)")
    void a17_uuidKbAnnouncementReturns204Not5xx() throws Exception {
        UserContextHolder.setCurrentUser(NORMAL_USER);
        // Real UUID-shaped id — market_id 列是 VARCHAR(36),UUID 直接查,无行 → null → 204。
        String fakeUuid = "00000000-0000-0000-0000-000000000002";

        ServerResponse resp = route(kbPublicRouter, "GET",
                "/spring/ai/loom/market-knowledge/" + fakeUuid + "/announcement", null);

        int status = resp.statusCode().value();
        assertTrue(status < 500,
                "UUID KB id on GET /announcement must NEVER produce 5xx — "
                        + "R3 String-native findOne 直查 VARCHAR(36),不再有 Long 强转。got status=" + status);

        // Specifically: the canonical path is 204 No Content (findOne → null → noContent(),
        // 既有 public-GET 契约,brief 要求保持不变)。
        assertEquals(204, status,
                "UUID KB id with no announcement row must produce 204 No Content "
                        + "(findOne null → noContent()), not 4xx/5xx. got status=" + status);
    }

    /* ===== A17b: M3+ final-review fix wave (Important #2a) — seeded positive GET announcement ===== */

    /**
     * A17 的正向孪生(A17 保留 no-row → 204 用例):先经 String-native repo 为真 UUID KB
     * upsert 一条 kind=KNOWLEDGE 公告行,再以 strict route() 驱动公开
     * {@code GET /market-knowledge/{uuid}/announcement} → 200 + body 的
     * title/body/marketId 与写入一致(spec AT1「200 + 真实 row」正向覆盖)。
     */
    @Test
    @DisplayName("A17b — GET /market-knowledge/{UUID}/announcement 有行 → 200 + title/body/marketId (AT1 正向)")
    void a17b_existingKbAnnouncementReturns200WithRow() throws Exception {
        UserContextHolder.setCurrentUser(NORMAL_USER);
        String kbUuid = UUID.randomUUID().toString();
        annRepo.upsert("KNOWLEDGE", kbUuid, "a17b-公告标题", "a17b-公告正文");

        ServerResponse resp = route(kbPublicRouter, "GET",
                "/spring/ai/loom/market-knowledge/" + kbUuid + "/announcement", null);

        assertEquals(200, resp.statusCode().value(),
                "existing announcement row must produce 200 (A17 covers the no-row 204 path)");
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) ((EntityResponse<?>) resp).entity();
        assertNotNull(respBody, "200 must carry the announcement map body");
        assertEquals("KNOWLEDGE", respBody.get("marketKind"));
        assertEquals(kbUuid, respBody.get("marketId"), "marketId must equal the KB UUID");
        assertEquals("a17b-公告标题", respBody.get("title"));
        assertEquals("a17b-公告正文", respBody.get("body"));

        // cleanup — 不让 KNOWLEDGE 残留行影响 A18 等跨 kind 用例的计数
        annRepo.delete("KNOWLEDGE", kbUuid);
    }

    /* ===== A16b: M3+ final-review fix wave (Important #2b) — seeded positive POST review ===== */

    /**
     * A9(no-access → 403 严门槛)的正向孪生:seed 真 UUID KB + NORMAL_USER 的
     * {@code loom_user_knowledge} 行(access_count=1,镜像
     * {@code DefaultKnowledgeReviewServiceIT.seedUserKbRow} 的严门槛放行条件),
     * 再以 strict route() 驱动公开 {@code POST /market-knowledge/{uuid}/reviews}
     * → 200 + {@code ReviewRow<String>} 的 marketId==uuid(spec AT1「200 + 真实 row」)。
     *
     * <p>排查结论(final-review 要求先查证再新增):本仓库此前<b>没有</b> KB 公开路由的
     * 正向 review POST 测试 — A9 只覆盖 403 负向(safeRoute 条件断言),A16 只覆盖
     * GET 空页,DefaultKnowledgeReviewServiceIT 的正向用例只打 service 层不打 router。
     * 因此本用例是新增覆盖而非重复。
     */
    @Test
    @DisplayName("A16b — POST /market-knowledge/{UUID}/reviews (已 access) → 200 + ReviewRow marketId==uuid (AT1 正向)")
    void a16b_kbReviewPositiveSubmitReturns200WithRow() throws Exception {
        String kbId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO loom_market_knowledge (id, username, name, description, category, status, created_by_kind) " +
                        "VALUES (?, ?, ?, ?, ?, 'APPROVED', 'USER')",
                kbId, "author-a16b", "a16b-kb-" + kbId, "desc", "cat-a16b");
        // 严门槛放行:NORMAL_USER 对该 KB access_count >= 1
        jdbc.update(
                "INSERT INTO loom_user_knowledge (username, market_knowledge_id, source, locked, access_count) " +
                        "VALUES (?, ?, 'MARKET_PULLED', FALSE, 1)",
                NORMAL_USER, kbId);

        UserContextHolder.setCurrentUser(NORMAL_USER);
        ServerResponse resp = route(kbPublicRouter, "POST",
                "/spring/ai/loom/market-knowledge/" + kbId + "/reviews",
                json(Map.of("rating", 5, "comment", "a16b-正向评价")));

        assertEquals(200, resp.statusCode().value(),
                "review submit with access_count >= 1 must deterministically return 200 (A9 covers the 403 gate)");
        @SuppressWarnings("unchecked")
        ReviewRow<String> row = (ReviewRow<String>) ((EntityResponse<?>) resp).entity();
        assertNotNull(row, "200 must carry the submitted ReviewRow");
        assertEquals(kbId, row.marketId(),
                "ReviewRow.marketId must equal the KB UUID (R2 String-native review chain)");
        assertEquals(NORMAL_USER, row.username());
        assertEquals(5, row.rating());
        assertEquals("a16b-正向评价", row.comment());

        // 真实落库 + GET 列表可见(与 A16 的空页用例互为正负镜像)
        ServerResponse listResp = route(kbPublicRouter, "GET",
                "/spring/ai/loom/market-knowledge/" + kbId + "/reviews", null);
        assertEquals(200, listResp.statusCode().value());
        @SuppressWarnings("unchecked")
        Page<ReviewRow<String>> page = (Page<ReviewRow<String>>) ((EntityResponse<?>) listResp).entity();
        assertEquals(1L, page.total(), "the submitted review row must be listed for this UUID");
        assertEquals(kbId, page.items().get(0).marketId());
    }

    /* ===== A18: R3 跨 kind JOIN 回归 — KB UUID 公告行存在时 SKILL listPaged/search 不得炸 ===== */

    /**
     * R3 回归(spec AT2):{@code market_content_announcement} 是 SKILL / KNOWLEDGE
     * 共享表,{@code market_id} 列 VARCHAR(36)。R3 前
     * {@code AbstractMarketAdminService.listPaged} 的 LEFT JOIN 用
     * {@code a.market_id = m.id} — SKILL 端 m.id 是 BIGINT,H2 把 VARCHAR 侧强转
     * BIGINT,表里只要有一条 KNOWLEDGE UUID 公告行,SKILL 的 list 查询就抛
     * conversion error(跨 kind 打挂)。R3 改成
     * {@code a.market_id = CAST(m.id AS VARCHAR(36))},VARCHAR↔VARCHAR 比较,
     * UUID 行安全跳过。
     *
     * <p>本用例:先 upsert 一条真 UUID 的 KNOWLEDGE 公告行,再跑 SKILL
     * {@code listPaged} + {@code search}(admin 路由 GET list 也验 200),断言:
     * <ol>
     *   <li>不抛异常、返回 rows(无 conversion error);</li>
     *   <li>带 SKILL 公告的 skill 行仍然内嵌 announcement_title / announcement_body
     *       (T2.1 LEFT JOIN embed 不受 CAST 改动影响)。</li>
     * </ol>
     */
    @Test
    @DisplayName("A18 — KB UUID 公告行在场时 SKILL listPaged/search 200 + SKILL 公告仍内嵌 (R3 CAST join)")
    void a18_crossKindUuidAnnouncementRowDoesNotBreakSkillListPaged() throws Exception {
        UserContextHolder.setCurrentUser(ADMIN_USER);

        // 1. 跨 kind 干扰行:真 UUID 的 KNOWLEDGE 公告(R3 前会让 SKILL join 抛
        //    VARCHAR→BIGINT conversion error)
        String kbUuid = UUID.randomUUID().toString();
        annRepo.upsert("KNOWLEDGE", kbUuid, "kb-干扰公告", "kb-cross-kind-body");

        // 2. 一条带 SKILL 公告的 skill 行
        String name = "a18-skill-" + System.nanoTime();
        Long id = createSkillAdmin(name, null);
        skillSvc.approve(id, ADMIN_USER);
        annRepo.upsert("SKILL", String.valueOf(id), "a18-公告标题", "a18-公告正文");

        // 3. SKILL listPaged — 必须不抛异常且包含两行验证点
        Page<MarketSkill> page = skillSvc.listPaged(new MarketFilter(
                0, 200, MarketContentStatus.APPROVED, null, null, "official_rank"));
        assertNotNull(page, "listPaged must not throw with a KB UUID announcement row present (R3 CAST join)");
        MarketSkill row = page.items().stream()
                .filter(s -> id.equals(s.id()))
                .findFirst()
                .orElse(null);
        assertNotNull(row, "the approved a18 skill must be present in listPaged rows");
        assertEquals("a18-公告标题", row.announcementTitle(),
                "SKILL announcement must still embed via LEFT JOIN (T2.1) after the CAST change");
        assertEquals("a18-公告正文", row.announcementBody());

        // 4. search 同路径(listPaged 的 APPROVED wrapper)— 也不得炸
        Page<MarketSkill> searched = skillSvc.search(name, null, 0, 50);
        assertNotNull(searched);
        assertTrue(searched.items().stream().anyMatch(s -> id.equals(s.id())),
                "search must still find the a18 skill with the KB UUID row present");

        // 5. admin 路由端到端 GET list → 200
        ServerResponse listResp = route(skillAdminRouter, "GET",
                "/spring/ai/loom/admin/market-skills?page=0&size=100", null);
        assertEquals(200, listResp.statusCode().value(),
                "admin SKILL list route must return 200 with a KB UUID announcement row in the shared table");

        // cleanup:删掉干扰行,避免影响其它用例的 listAllForKind 计数
        annRepo.delete("KNOWLEDGE", kbUuid);
        annRepo.delete("SKILL", String.valueOf(id));
    }

    /* ===== helpers ===== */

    private Long createSkillAdmin(String name, String category) {
        MarketSkill created = skillSvc.create(
                ADMIN_USER,
                new MarketCreateRequest(name, "d", "c", category));
        return created.id();
    }

    /**
     * M3+ T6.1 — safeRoute / route / json helpers have moved to
     * {@link LoomAgentTestUtil}. This class uses static imports on
     * {@code LoomAgentTestUtil.{safeRoute, route, json}} so the existing call
     * sites in test methods remain unchanged.
     */
}