package cn.wubo.spring.ai.loom.agent.file.view;

import cn.wubo.file.view.auth.AuthResult;
import cn.wubo.file.view.auth.IAuth;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * file-view 鉴权实现，与 LoomAgent 的 BFF + Cookie 鉴权保持一致。
 * <p>
 * 从请求 cookie 中读取 session token，校验通过后设置 UserContextHolder，
 * 使 file-view 模块也能复用已有的用户上下文。
 */
public class LoomAgentAuth implements IAuth {

    private final IUser user;
    private final String cookieName;

    public LoomAgentAuth(IUser user, String cookieName) {
        this.user = user;
        this.cookieName = cookieName;
    }

    @Override
    public AuthResult check(HttpServletRequest request, String path) {
        String token = extractTokenFromCookie(request);
        if (token == null || !user.validateToken(token)) {
            return AuthResult.deny("未登录或会话已过期");
        }

        // Refresh the per-request user context. We do NOT clear it on the way
        // out: AuthenticationFilter (the outer servlet filter) already manages
        // the UserContextHolder lifecycle via its try/finally over the entire
        // request. Clearing here would empty the context for downstream
        // IFileStorage beans (LoomAgentFileStorageImpl#findById), which now
        // reads the username to enforce per-file ownership (BUG-RBAC-FILE-WOPI
        // fix). So the policy: outer filter is authoritative, this hook just
        // ensures the username reflects the cookie just-in-time.
        String username = user.getUsernameByToken(token);
        UserContextHolder.setCurrentUser(username);
        return AuthResult.allow();
    }

    private String extractTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookieName.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
