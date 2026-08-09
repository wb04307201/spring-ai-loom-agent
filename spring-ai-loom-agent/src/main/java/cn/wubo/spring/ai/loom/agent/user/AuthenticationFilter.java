package cn.wubo.spring.ai.loom.agent.user;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.util.Arrays;

public class AuthenticationFilter implements Filter {

 private final IUser user;
 private final LoomAgentProperties.AuthProperty authProperty;
 private final AntPathMatcher pathMatcher = new AntPathMatcher();

 public AuthenticationFilter(IUser user, LoomAgentProperties.AuthProperty authProperty) {
 this.user = user;
 this.authProperty = authProperty;
 }

 @Override
 public void doFilter(jakarta.servlet.ServletRequest servletRequest, jakarta.servlet.ServletResponse servletResponse, FilterChain chain) throws IOException, ServletException {
 HttpServletRequest request = (HttpServletRequest) servletRequest;
 HttpServletResponse response = (HttpServletResponse) servletResponse;

 // 1. 鉴权总开关
 if (!authProperty.isEnabled()) {
 chain.doFilter(request, response);
 return;
 }

 String path = request.getRequestURI();

 // 2. 排除路径：不鉴权
 if (authProperty.getExcludePathPatterns() != null) {
 boolean excluded = authProperty.getExcludePathPatterns().stream()
 .anyMatch(p -> pathMatcher.match(p, path));
 if (excluded) {
 chain.doFilter(request, response);
 return;
 }
 }

 // 3. 路径匹配：只鉴权 pathPatterns 中的路径
 boolean needAuth = authProperty.getPathPatterns().stream()
 .anyMatch(p -> pathMatcher.match(p, path));
 if (!needAuth) {
 chain.doFilter(request, response);
 return;
 }

 // 4. 从 Cookie 中读取 session token
 String sessionToken = extractTokenFromCookie(request);
 if (sessionToken == null || !user.validateToken(sessionToken)) {
 // HTML 请求 → 302 重定向到 login（让浏览器跳到登录页）
 // API 请求 → 401 状态码（前端 fetch 处理）
 String accept = request.getHeader("Accept");
 if (accept != null && accept.contains("text/html")) {
 response.sendRedirect(request.getContextPath() + "/spring/ai/loom/login.html");
 } else {
 response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
 }
 return;
 }

 // 5. 验证通过：设置用户上下文
 String username = user.getUsernameByToken(sessionToken);
 UserContextHolder.setCurrentUser(username);
 try {
 // 6. 管理员路径二次校验
 if (isAdminPath(path) && !user.isAdmin(username)) {
 response.sendRedirect(request.getContextPath() + "/spring/ai/loom/index.html");
 return;
 }
 chain.doFilter(request, response);
 } finally {
 UserContextHolder.clear();
 }
 }

 private boolean isAdminPath(String path) {
 if (authProperty.getAdminPathPatterns() == null) return false;
 return authProperty.getAdminPathPatterns().stream()
 .anyMatch(p -> pathMatcher.match(p, path));
 }

 private String extractTokenFromCookie(HttpServletRequest request) {
 Cookie[] cookies = request.getCookies();
 if (cookies != null) {
 String cookieName = authProperty.getCookie().getName();
 return Arrays.stream(cookies)
 .filter(c -> cookieName.equals(c.getName()))
 .map(Cookie::getValue)
 .findFirst()
 .orElse(null);
 }
 return null;
 }
}
