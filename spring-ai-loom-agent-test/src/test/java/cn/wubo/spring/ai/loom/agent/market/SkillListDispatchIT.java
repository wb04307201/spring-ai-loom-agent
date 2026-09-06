package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.testutil.LoomAgentTestUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FU-4 外科式退役 dispatch IT: v1 skill list 路由删除后, v2 Page handler 是唯一胜出者。
 *
 * <p>断言:
 * <ul>
 *   <li>GET /spring/ai/loom/market-skills → 200 + Page shape (items/total/page/size)</li>
 *   <li>GET /spring/ai/loom/user/market-skills → 200 + ARRAY (v1-only 路由保留)</li>
 *   <li>@Qualifier("loomAgentSkillMarketRouter") v1 bean 不再持有 GET /market-skills 路径</li>
 * </ul>
 *
 * <p>环境: 走 RANDOM_PORT + TestRestTemplate (真实 servlet 容器), 与
 * MarketAcceptanceIT 的 RouterFunction 直接驱动互补 — 本 IT 验证 HTTP 层的
 * dispatch 路由 (两个同名 bean 注册同一路径时, Spring 按声明顺序先声明者胜;
 * v1 删除后 v2 自然成为唯一胜出者)。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Skill List Dispatch IT — FU-4 v1 退役后 v2 Page handler 胜出")
class SkillListDispatchIT {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    @Qualifier("loomAgentSkillMarketRouter")
    RouterFunction<ServerResponse> v1Router;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpHeaders authHeaders;

    @BeforeEach
    void loginAsAdmin() {
        // 默认 admin 由 V1.0__init.sql seed: wb04307201 / 123456
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        HttpEntity<String> loginBody = new HttpEntity<>(
                "{\"username\":\"wb04307201\",\"password\":\"123456\"}", loginHeaders);
        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/spring/ai/loom/user/login", loginBody, String.class);
        assertEquals(HttpStatus.OK, loginResp.getStatusCode(),
                "admin login must succeed; got " + loginResp.getStatusCode());

        // 提取 HttpOnly cookie 并构造后续请求 headers
        List<String> cookies = loginResp.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertNotNull(cookies, "login must set Set-Cookie header");
        assertTrue(!cookies.isEmpty(), "login must return at least one cookie");
        authHeaders = new HttpHeaders();
        authHeaders.add(HttpHeaders.COOKIE, String.join("; ", cookies));
    }

    /* ===== 1. 公开 list 返回 v2 Page shape ===== */

    @Test
    @DisplayName("GET /market-skills → 200 + Page{items,total,page,size} (v2 wins)")
    void publicListReturnsV2PageShape() throws Exception {
        HttpEntity<Void> req = new HttpEntity<>(authHeaders);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/spring/ai/loom/market-skills", HttpMethod.GET, req, String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "public list must return 200");

        JsonNode body = MAPPER.readTree(resp.getBody());
        assertNotNull(body, "body must not be null");
        // v2 Page shape: items + total + page + size
        assertTrue(body.has("items"), "v2 response must have 'items' field");
        assertTrue(body.has("total"), "v2 response must have 'total' field");
        assertTrue(body.has("page"), "v2 response must have 'page' field");
        assertTrue(body.has("size"), "v2 response must have 'size' field");
        assertTrue(body.get("items").isArray(), "'items' must be an array");
    }

    /* ===== 2. 用户提交列表返回 ARRAY (v1-only 路由保留) ===== */

    @Test
    @DisplayName("GET /user/market-skills → 200 + ARRAY (v1-only, kept)")
    void userSubmittedListReturnsArray() throws Exception {
        HttpEntity<Void> req = new HttpEntity<>(authHeaders);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/spring/ai/loom/user/market-skills", HttpMethod.GET, req, String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "user submitted list must return 200");

        JsonNode body = MAPPER.readTree(resp.getBody());
        assertNotNull(body, "body must not be null");
        assertTrue(body.isArray(),
                "user submitted list must return ARRAY (v1-only route kept)");
    }

    /* ===== 3. v1 router bean 不再持有 GET /market-skills 路径 ===== */

    @Test
    @DisplayName("v1 router bean: safeRoute GET /market-skills → empty (route removed)")
    void v1RouterNoLongerHoldsPublicListPath() throws Exception {
        ServerResponse resp = LoomAgentTestUtil.safeRoute(
                v1Router, "GET", "/spring/ai/loom/market-skills", null);
        assertNull(resp,
                "v1 router must NOT match GET /spring/ai/loom/market-skills after FU-4 retirement");
    }

    /* ===== 4. Admin list 也返回 v2 Page shape ===== */

    @Test
    @DisplayName("GET /admin/market-skills → 200 + Page{items,total,page,size} (v2 wins)")
    void adminListReturnsV2PageShape() throws Exception {
        HttpEntity<Void> req = new HttpEntity<>(authHeaders);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/spring/ai/loom/admin/market-skills", HttpMethod.GET, req, String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "admin list must return 200");

        JsonNode body = MAPPER.readTree(resp.getBody());
        assertNotNull(body, "body must not be null");
        assertTrue(body.has("items"), "v2 admin response must have 'items' field");
        assertTrue(body.has("total"), "v2 admin response must have 'total' field");
        assertTrue(body.has("page"), "v2 admin response must have 'page' field");
        assertTrue(body.has("size"), "v2 admin response must have 'size' field");
        assertTrue(body.get("items").isArray(), "'items' must be an array");
    }
}
