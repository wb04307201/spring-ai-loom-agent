package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FU-4 外科式退役 dispatch IT: v1 skill list 路由删除后, v2 Page handler 是唯一胜出者。
 *
 * <p>断言:
 * <ul>
 *   <li>GET /spring/ai/loom/market-skills → 200 + Page shape (items/total/page/size)</li>
 *   <li>GET /spring/ai/loom/user/market-skills → 200 + ARRAY (T6 #4: v1 退役迁入 v2 public router)</li>
 *   <li>POST /spring/ai/loom/admin/market-skills → APPROVED + created_by_kind=ADMIN (v2 createApproved 唯一赢家)</li>
 * </ul>
 *
 * <p>环境: 走 RANDOM_PORT + TestRestTemplate (真实 servlet 容器), 与
 * MarketAcceptanceIT 的 RouterFunction 直接驱动互补 — 本 IT 验证 HTTP 层的
 * dispatch 路由 (T6 #4 起 v1 skill 路由 bean 已全部退役, v2 是唯一注册者)。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Skill List Dispatch IT — FU-4 v1 退役后 v2 Page handler 胜出")
class SkillListDispatchIT {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

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

    /* ===== 2. 用户提交列表返回 ARRAY (v2 承接, 形态不变) ===== */

    @Test
    @DisplayName("GET /user/market-skills → 200 + ARRAY (v2, ARRAY shape kept)")
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

    /* ===== 3. Admin list 也返回 v2 Page shape ===== */

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

    /* ===== 5. Admin POST → v2 createApproved 唯一赢家 (T6) ===== */

    @Test
    @DisplayName("POST /admin/market-skills → 200 + APPROVED + created_by_kind=ADMIN (v2 createApproved 唯一赢家)")
    void adminPostSkillCreatesApproved() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, authHeaders.getFirst(HttpHeaders.COOKIE));
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        String name = "disp-adm-" + System.nanoTime();
        HttpEntity<String> req = new HttpEntity<>(
                "{\"name\":\"" + name + "\",\"description\":\"d\",\"content\":\"c\",\"category\":\"cat\"}", h);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/spring/ai/loom/admin/market-skills", HttpMethod.POST, req, String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "admin create must return 200; got " + resp.getStatusCode() + " body=" + resp.getBody());
        JsonNode body = MAPPER.readTree(resp.getBody());
        assertEquals("APPROVED", body.get("status").asText(),
                "admin create must land APPROVED");
        long id = body.get("id").asLong();
        String kind = jdbc.queryForObject(
                "SELECT created_by_kind FROM market_skill WHERE id=?", String.class, id);
        assertEquals("ADMIN", kind,
                "must be createApproved (created_by_kind=ADMIN), not v1 adminCreate (USER) nor v2 create (PENDING)");
    }
}
