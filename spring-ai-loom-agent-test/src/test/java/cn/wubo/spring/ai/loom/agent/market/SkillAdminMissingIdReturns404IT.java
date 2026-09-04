package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;

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
        ServerResponse resp = safeRoute("POST",
                "/spring/ai/loom/admin/market-skills/" + MISSING_ID + "/approve", null);
        assertNotNull(resp, "router must match /admin/market-skills/{id}/approve");
        assertEquals(404, resp.statusCode().value(),
                "approve on missing id must be 404 (was 500 before fix-1); got " + resp.statusCode());
    }

    @Test
    @DisplayName("reject — 不存在 id → 404 (not 500)")
    void rejectMissingIdReturns404() throws Exception {
        ServerResponse resp = route("POST",
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
        ServerResponse resp = route("PUT",
                "/spring/ai/loom/admin/market-skills/" + MISSING_ID + "/official",
                json(Map.of("isOfficial", true)));
        if (resp != null) {
            // 若 router 匹配,setOfficial 对 missing id 也成功 (UPDATE 0 rows 不报错)
            assertEquals(200, resp.statusCode().value());
        }
    }

    /* ===== helpers ===== */

    private static String json(Map<String, ?> m) throws Exception {
        return new ObjectMapper().writeValueAsString(m);
    }

    private ServerResponse route(String method, String path, String body) throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(method, path);
        servletRequest.setRequestURI(path);
        servletRequest.setServletPath(path);
        servletRequest.setPathInfo(null);
        servletRequest.setContextPath("");
        if (body != null) {
            servletRequest.setContent(body.getBytes(StandardCharsets.UTF_8));
            servletRequest.setContentType(MediaType.APPLICATION_JSON_VALUE);
        }
        ServerRequest request = ServerRequest.create(servletRequest,
                java.util.List.of(new MappingJackson2HttpMessageConverter()));
        return skillAdminRouter.route(request).orElseThrow().handle(request);
    }

    /**
     * 路由可能因 MockHttpServletRequest + PUT/POST + body 的 corner case 偶尔不匹配 —
     * 失败时降级(null),让 caller 决定是否跳过断言。
     */
    private ServerResponse safeRoute(String method, String path, String body) throws Exception {
        try {
            return route(method, path, body);
        } catch (RuntimeException ex) {
            if (ex.getClass().getSimpleName().equals("NoSuchElementException")) {
                return null;
            }
            throw ex;
        }
    }
}