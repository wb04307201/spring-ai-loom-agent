package cn.wubo.spring.ai.loom.agent.user;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AuthenticationFilter 单元测试 —— 开关/排除/匹配/未登录分化/admin 二次校验。
 */
@DisplayName("AuthenticationFilter 单元测试")
class AuthenticationFilterTest {

 private IUser user;
 private LoomAgentProperties.AuthProperty auth;
 private AuthenticationFilter filter;
 private FilterChain chain;
 private final AtomicBoolean chainCalled = new AtomicBoolean(false);
 private final AtomicReference<String> userInChain = new AtomicReference<>();

 @BeforeEach
 void setUp() {
 user = mock(IUser.class);
 auth = new LoomAgentProperties().getAuth();
 filter = new AuthenticationFilter(user, auth);
 chain = (req, res) -> {
 chainCalled.set(true);
 userInChain.set(UserContextHolder.getCurrentUser());
 };
 }

 @AfterEach
 void tearDown() {
 UserContextHolder.clear();
 }

 private MockHttpServletResponse run(MockHttpServletRequest req) throws Exception {
 MockHttpServletResponse res = new MockHttpServletResponse();
 filter.doFilter(req, res, chain);
 return res;
 }

 @Test
 @DisplayName("鉴权关闭：直接放行")
 void disabledLetsEverythingThrough() throws Exception {
 auth.setEnabled(false);
 MockHttpServletRequest req = new MockHttpServletRequest("GET", "/spring/ai/loom/conversation");

 run(req);

 assertTrue(chainCalled.get());
 }

 @Test
 @DisplayName("排除路径（login.html）：不鉴权放行")
 void excludedPathBypassesAuth() throws Exception {
 MockHttpServletRequest req = new MockHttpServletRequest("GET", "/spring/ai/loom/login.html");

 run(req);

 assertTrue(chainCalled.get());
 verifyNoInteractions(user);
 }

 @Test
 @DisplayName("pathPatterns 之外的路径：不鉴权放行")
 void nonMatchingPathBypassesAuth() throws Exception {
 MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");

 run(req);

 assertTrue(chainCalled.get());
 verifyNoInteractions(user);
 }

 @Test
 @DisplayName("受保护路径未登录：HTML 请求 302 到 login.html")
 void htmlRequestRedirectsToLogin() throws Exception {
 MockHttpServletRequest req = new MockHttpServletRequest("GET", "/spring/ai/loom/conversation");
 req.addHeader("Accept", "text/html");

 MockHttpServletResponse res = run(req);

 assertFalse(chainCalled.get());
 assertEquals(302, res.getStatus());
 assertTrue(res.getRedirectedUrl().endsWith("/spring/ai/loom/login.html"));
 }

 @Test
 @DisplayName("受保护路径未登录：API 请求 401")
 void apiRequestGets401() throws Exception {
 MockHttpServletRequest req = new MockHttpServletRequest("GET", "/spring/ai/loom/conversation");
 req.addHeader("Accept", "application/json");

 MockHttpServletResponse res = run(req);

 assertFalse(chainCalled.get());
 assertEquals(401, res.getStatus());
 }

 @Test
 @DisplayName("有效会话：放行且 UserContextHolder 在链内可见、链后清理")
 void validSessionSetsAndClearsContext() throws Exception {
 when(user.validateToken("tok-1")).thenReturn(true);
 when(user.getUsernameByToken("tok-1")).thenReturn("alice");
 MockHttpServletRequest req = new MockHttpServletRequest("GET", "/spring/ai/loom/conversation");
 req.setCookies(new Cookie("loom-agent-session", "tok-1"));

 run(req);

 assertTrue(chainCalled.get());
 assertEquals("alice", userInChain.get(), "链内应能看到当前用户");
 assertNull(UserContextHolder.getCurrentUser(), "链后应清理 ThreadLocal");
 }

 @Test
 @DisplayName("admin 路径 + 非 admin：重定向到 index.html 且不放行")
 void adminPathRejectsNonAdmin() throws Exception {
 when(user.validateToken("tok-1")).thenReturn(true);
 when(user.getUsernameByToken("tok-1")).thenReturn("bob");
 when(user.isAdmin("bob")).thenReturn(false);
 MockHttpServletRequest req = new MockHttpServletRequest("GET", "/spring/ai/loom/admin/users");
 req.setCookies(new Cookie("loom-agent-session", "tok-1"));

 MockHttpServletResponse res = run(req);

 assertFalse(chainCalled.get());
 assertEquals(302, res.getStatus());
 assertTrue(res.getRedirectedUrl().endsWith("/spring/ai/loom/index.html"));
 }

 @Test
 @DisplayName("admin 路径 + admin：放行")
 void adminPathAllowsAdmin() throws Exception {
 when(user.validateToken("tok-1")).thenReturn(true);
 when(user.getUsernameByToken("tok-1")).thenReturn("root");
 when(user.isAdmin("root")).thenReturn(true);
 MockHttpServletRequest req = new MockHttpServletRequest("GET", "/spring/ai/loom/admin/users");
 req.setCookies(new Cookie("loom-agent-session", "tok-1"));

 run(req);

 assertTrue(chainCalled.get());
 }
}
