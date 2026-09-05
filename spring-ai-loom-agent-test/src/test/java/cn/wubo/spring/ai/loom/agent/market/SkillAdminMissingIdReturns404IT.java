package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
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
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Map;

import static cn.wubo.spring.ai.loom.agent.testutil.LoomAgentTestUtil.json;
import static cn.wubo.spring.ai.loom.agent.testutil.LoomAgentTestUtil.route;
import static cn.wubo.spring.ai.loom.agent.testutil.LoomAgentTestUtil.safeRoute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * MUST-FIX-1 验证:Skill 侧 admin approve / reject 在 id 不存在时应该返回 404 (not 500)。
 * <p>
 * 修复前 {@link cn.wubo.spring.ai.loom.agent.skill.DefaultSkillMarketService#getById(Long)}
 * override 没有 catch {@code EmptyResultDataAccessException},导致 v2 admin router 的
 * {@code LoomAgentRuntimeException} catch 块没接住,异常以 500 透传到客户端。
 * <p>
 * 修复后 {@code getById} 与原 {@code get(Long)} 一样把 {@code EmptyResultDataAccessException}
 * 翻译成 {@link cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException}(404,"Skill 不存在: id=..."),
 * router 落到 catch 块返回 404。
 *
 * <p>端到端走 {@code loomAgentMarketSkillAdminRouter} + {@link UserContextHolder}
 * 注入 admin 身份(与 {@code MarketAcceptanceIT} 同款),不启 servlet,绕开 HttpOnly cookie filter。
 *
 * <p>Note:未覆盖 {@code PUT /admin/market-skills/{id}} 路径 —
 * {@link cn.wubo.spring.ai.loom.agent.skill.DefaultSkillMarketService#update(Long,
 * cn.wubo.spring.ai.loom.agent.market.MarketUpdateRequest)} 有个与 MUST-FIX-1 无关的
 * 预存 SQL bug (用了 {@code market_skill.updated_at} 列,但 schema 没有该列),
 * 该路径会在到达 {@code getById} 之前就抛 BadSqlGrammar。这是 tech debt,不在 MUST-FIX-1 范围。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("MUST-FIX-1 — Skill admin 端点对不存在 id 返 404")
class SkillAdminMissingIdReturns404IT {

    private static final String ADMIN_USER = "fix1admin";
    private static final String ADMIN_PASS = "fix1-pwd-123";
    /** 不可能存在的 id — 比任何 SEQUENCE / auto_increment 都大。 */
    private static final Long MISSING_ID = 9_999_999L;

    @Autowired IUser user;
    @Autowired JdbcTemplate jdbc;

    @Autowired
    @Qualifier("loomAgentMarketSkillAdminRouter")
    RouterFunction<ServerResponse> skillAdminRouter;

    @BeforeEach
    void setUp() {
        try {
            user.createUser(ADMIN_USER, "Fix1 Admin", ADMIN_PASS, "ADMIN");
        } catch (RuntimeException ignored) {
            // already exists
        }
        UserContextHolder.setCurrentUser(ADMIN_USER);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("approve — 不存在 id → 404 (not 500)")
    void approveMissingIdReturns404() throws Exception {
        ServerResponse resp = safeRoute(skillAdminRouter, "POST",
                "/spring/ai/loom/admin/market-skills/" + MISSING_ID + "/approve", null);
        assertNotNull(resp, "router must match /admin/market-skills/{id}/approve");
        assertEquals(404, resp.statusCode().value(),
                "approve on missing id must be 404 (was 500 before fix-1); got " + resp.statusCode());
    }

    @Test
    @DisplayName("reject — 不存在 id → 404 (not 500)")
    void rejectMissingIdReturns404() throws Exception {
        ServerResponse resp = route(skillAdminRouter, "POST",
                "/spring/ai/loom/admin/market-skills/" + MISSING_ID + "/reject",
                json(Map.of("comment", "无效 id 校验")));
        assertNotNull(resp, "router must match /admin/market-skills/{id}/reject");
        assertEquals(404, resp.statusCode().value(),
                "reject on missing id must be 404 (was 500 before fix-1); got " + resp.statusCode());
    }

    @Test
    @DisplayName("setOfficial — 不存在 id → 静默 200 (router 不依赖 getById)")
    void setOfficialMissingIdReturns200() throws Exception {
        // setOfficial 走 jdbc.update 不回读;此处只作为 control — 验证 approve/reject
        // 才是真 bug 现场,setOfficial 是 OK control case。
        ServerResponse resp = route(skillAdminRouter, "PUT",
                "/spring/ai/loom/admin/market-skills/" + MISSING_ID + "/official",
                json(Map.of("isOfficial", true)));
        if (resp != null) {
            // 若 router 匹配,setOfficial 对 missing id 也成功 (UPDATE 0 rows 不报错)
            assertEquals(200, resp.statusCode().value());
        }
    }

    /* ===== helpers ===== */

    /**
     * M3+ T6.1 — safeRoute / route / json helpers have moved to
     * {@link LoomAgentTestUtil}. This class uses static imports on
     * {@code LoomAgentTestUtil.{safeRoute, route, json}} so the existing call
     * sites in test methods remain unchanged.
     *
     * <p>Note: the original private {@code route} method here did not strip
     * the query string; the extracted util version does, which is strictly
     * more capable and matches the MarketAcceptanceIT semantics.
     */
}