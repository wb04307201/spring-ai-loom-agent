package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.skill.DefaultSkillMarketService;
import cn.wubo.spring.ai.loom.agent.skill.stats.DefaultSkillStatsService;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：{@link DefaultSkillStatsService} + 公共 GET /stats 路由 + admin PUT /stats-reset 路由。
 * <p>
 * 镜像 {@link DefaultSkillMarketServiceIT} 的模式(SpringBootTest + 真实 JdbcTemplate),覆盖 T16:
 * <ol>
 *   <li>直接 service 层 {@code incrementStat} 后再 read — 验证缓冲+flush 的端到端流程;</li>
 *   <li>直接 service 层 {@code getStats} 命中已存在 row vs 返回零行;</li>
 *   <li>公共路由 {@code GET /spring/ai/loom/market-skills/{id}/stats} — 任意已登录用户可读;</li>
 *   <li>admin 路由 {@code PUT /spring/ai/loom/admin/market-skills/{id}/stats-reset} —
 *       非 admin 拒 403,admin 写入并返回新计数。</li>
 * </ol>
 * <p>
 * 注:为了让 buffered UPDATE 立即可见,我们在 incrementStat 之后调用
 * {@code batchedCounterService.flush()}。{@code @EnableScheduling} 已经在
 * {@link LoomAgentTestApplication} 上,默认 30s 周期也会跑,测试不必依赖它。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("DefaultSkillStatsService IT — T16")
class DefaultSkillStatsServiceIT {

    @Autowired DefaultSkillStatsService skillStatsService;
    @Autowired cn.wubo.spring.ai.loom.agent.market.BatchedCounterService batchedCounterService;
    @Autowired DefaultSkillMarketService skillMarketService;
    @Autowired IUser user;
    @Autowired JdbcTemplate jdbc;

    @Autowired
    @Qualifier("loomAgentSkillMarketPublicRouter")
    RouterFunction<ServerResponse> skillPublicRouter;

    @Autowired
    @Qualifier("loomAgentMarketSkillAdminRouter")
    RouterFunction<ServerResponse> skillAdminRouter;

    private static final String ADMIN_USER = "statsadmin";
    private static final String ADMIN_PASS = "stats-pwd-123";

    @BeforeEach
    void setUp() {
        // Seed an admin user — most setups already have one in V1.0 init data
        // (`wb04307201`), but be defensive so a wiped datasource still works.
        // Username MUST NOT contain '-' (see DefaultUser.createUser validation —
        // '-' conflicts with the schedule-task namespace parser).
        try {
            user.createUser(ADMIN_USER, "Stats Admin", ADMIN_PASS, "ADMIN");
        } catch (RuntimeException ignored) {
            // already exists — fine, the next test reuses it
        }
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    /* ===== direct service-layer tests ===== */

    /**
     * 增、缓冲 → DB 落库 → 读回:验证
     * {@link cn.wubo.spring.ai.loom.agent.market.IMarketContentStatsService#incrementStat}
     * + flush + read 端到端能跑通。stats row 由 {@code AbstractMarketStatsService.ensureStatsRowExists}
     * lazy-upsert 创建。
     */
    @Test
    @DisplayName("incrementStat + flush: pull_count 累加,last_pulled_at 更新")
    void incrementStatLandsInDb() {
        Long skillId = skillMarketService.create("alice", new MarketCreateRequest(
                "stats-inc-" + System.nanoTime(), "d", "c", null)).id();

        // 起:行不存在
        Integer before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM market_skill_stats WHERE market_skill_id = ?",
                Integer.class, skillId);
        assertEquals(0, before, "skill should have no stats row before first increment");

        // 增 3 次
        skillStatsService.incrementStat(skillId, "PULL");
        skillStatsService.incrementStat(skillId, "PULL");
        skillStatsService.incrementStat(skillId, "PULL");

        // 缓冲未刷前:DB 仍然没计数,但 row 已存在(upsert 阶段就插了)
        Integer rowExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM market_skill_stats WHERE market_skill_id = ?",
                Integer.class, skillId);
        assertEquals(1, rowExists, "lazy upsert should create the row before buffer flush");

        // flush 让 buffered deltas 落库
        batchedCounterService.flush();

        Long count = jdbc.queryForObject(
                "SELECT pull_count FROM market_skill_stats WHERE market_skill_id = ?",
                Long.class, skillId);
        assertEquals(3L, count, "3 increments must collapse to pull_count=3");

        java.sql.Timestamp lastAt = jdbc.queryForObject(
                "SELECT last_pulled_at FROM market_skill_stats WHERE market_skill_id = ?",
                java.sql.Timestamp.class, skillId);
        assertNotNull(lastAt, "last_pulled_at must be set by the UPSERT + flush");
    }

    /**
     * 没增过 (row 不存在): {@code getStats} 必须返回 0/null,不抛异常。
     * 这是契约 — 公共路由要直接 {@code getStats} 取数。
     */
    @Test
    @DisplayName("getStats 未增过: 返回 (id, 0, null) 不抛异常")
    void getStatsOnMissingRowReturnsZeros() {
        Long skillId = skillMarketService.create("alice", new MarketCreateRequest(
                "stats-empty-" + System.nanoTime(), "d", "c", null)).id();

        StatsRow row = skillStatsService.getStats(skillId);
        assertNotNull(row);
        assertEquals(skillId, row.marketId());
        assertEquals(0L, row.pullCountOrSearchCount());
        assertNull(row.lastAt());
    }

    /* ===== router end-to-end tests ===== */

    /**
     * 公共路由 — 任意已登录用户都能读 stats(不需要 admin 权限,因为只是只读计数)。
     * 模拟未登录 — 但其实 {@code UserContextHolder.getCurrentUser()} 在 GET stats 路由里
     * 没有被读取,所以即使是未登录也能查到数据(只查 id 不需要 username)。
     * 这里我们要回归主要契约:返回值字段 ({id, pullCount, lastAt}) 正确。
     */
    @Test
    @DisplayName("GET /market-skills/{id}/stats: 返回 {id, pullCount, lastAt}")
    @SuppressWarnings("unchecked")
    void publicRouterGetStatsReturnsAllFields() throws Exception {
        Long skillId = skillMarketService.create("alice", new MarketCreateRequest(
                "stats-rget-" + System.nanoTime(), "d", "c", null)).id();
        skillStatsService.incrementStat(skillId, "PULL");
        skillStatsService.incrementStat(skillId, "PULL");
        batchedCounterService.flush();

        ServerResponse response = route(skillPublicRouter, "GET",
                "/spring/ai/loom/market-skills/" + skillId + "/stats", null);
        assertEquals(200, response.statusCode().value());

        Map<String, Object> body = (Map<String, Object>) ((EntityResponse<?>) response).entity();
        assertNotNull(body);
        assertEquals(skillId.intValue(), ((Number) body.get("id")).intValue());
        assertEquals(2L, ((Number) body.get("pullCount")).longValue());
        assertNotNull(body.get("lastAt"), "lastAt must be set after the increments + flush");
    }

    /**
     * admin 路由 — 非 admin 拿到 403。验证端点的 admin 网关是走的
     * {@code user.isAdmin(...)} 二次校验(在 AuthenticationFilter 之外),与其它 T7 路由同款。
     */
    @Test
    @DisplayName("PUT /admin/market-skills/{id}/stats-reset: 非 admin → 403")
    void adminRouterRejectsNonAdmin() throws Exception {
        Long skillId = skillMarketService.create("alice", new MarketCreateRequest(
                "stats-rej-" + System.nanoTime(), "d", "c", null)).id();
        UserContextHolder.setCurrentUser("alice"); // 普通用户,非 admin

        ServerResponse response = route(skillAdminRouter, "PUT",
                "/spring/ai/loom/admin/market-skills/" + skillId + "/stats-reset",
                "{\"count\": 0}");
        assertEquals(403, response.statusCode().value());
    }

    /**
     * admin 路由 — admin 写入并返回新计数。这条等价于 "{ count: 0 }" → pull_count=0, lastAt=null。
     */
    @Test
    @DisplayName("PUT /admin/market-skills/{id}/stats-reset: admin 写入,body {count: 0}")
    @SuppressWarnings("unchecked")
    void adminRouterResetsCount() throws Exception {
        Long skillId = skillMarketService.create("alice", new MarketCreateRequest(
                "stats-ok-" + System.nanoTime(), "d", "c", null)).id();
        skillStatsService.incrementStat(skillId, "PULL");
        skillStatsService.incrementStat(skillId, "PULL");
        skillStatsService.incrementStat(skillId, "PULL");
        batchedCounterService.flush();
        assertEquals(3L, skillStatsService.getStats(skillId).pullCountOrSearchCount());

        UserContextHolder.setCurrentUser(ADMIN_USER);

        ServerResponse response = route(skillAdminRouter, "PUT",
                "/spring/ai/loom/admin/market-skills/" + skillId + "/stats-reset",
                "{\"count\": 0}");
        assertEquals(200, response.statusCode().value());

        Map<String, Object> body = (Map<String, Object>) ((EntityResponse<?>) response).entity();
        assertNotNull(body);
        assertEquals(0L, ((Number) body.get("pullCount")).longValue());
        assertNull(body.get("lastAt"), "reset must null out lastAt");

        // 再次读 DB 验证持久化
        Long count = jdbc.queryForObject(
                "SELECT pull_count FROM market_skill_stats WHERE market_skill_id = ?",
                Long.class, skillId);
        assertEquals(0L, count);
        java.sql.Timestamp lastAt = jdbc.queryForObject(
                "SELECT last_pulled_at FROM market_skill_stats WHERE market_skill_id = ?",
                java.sql.Timestamp.class, skillId);
        // lastAt 可能是 null (rs.getTimestamp 返回 null),H2 行为一致
        assertTrue(lastAt == null || lastAt.getTime() == 0L,
                "last_pulled_at should be null after reset; was: " + lastAt);

        // 同时验证 lazy-upsert 也能跑 — 即使 stats row 之前不存在,reset 也能创建
        // 删除已创建的 row,再 reset 一次
        jdbc.update("DELETE FROM market_skill_stats WHERE market_skill_id = ?", skillId);
        ServerResponse reResponse = route(skillAdminRouter, "PUT",
                "/spring/ai/loom/admin/market-skills/" + skillId + "/stats-reset",
                "{\"count\": 5}");
        assertEquals(200, reResponse.statusCode().value());
        Map<String, Object> reBody = (Map<String, Object>) ((EntityResponse<?>) reResponse).entity();
        assertEquals(5L, ((Number) reBody.get("pullCount")).longValue(),
                "reset on missing row must lazy-upsert with the new count");
    }

    /* ===== helpers ===== */

    private ServerResponse route(RouterFunction<ServerResponse> router,
                                String method, String path, String body) throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(method, path);
        servletRequest.setRequestURI(path);
        servletRequest.setServletPath(path);
        if (body != null) {
            servletRequest.setContent(body.getBytes(StandardCharsets.UTF_8));
            servletRequest.setContentType(MediaType.APPLICATION_JSON_VALUE);
        }
        ServerRequest request = ServerRequest.create(servletRequest,
                List.of(new MappingJackson2HttpMessageConverter()));
        return router.route(request).orElseThrow().handle(request);
    }
}