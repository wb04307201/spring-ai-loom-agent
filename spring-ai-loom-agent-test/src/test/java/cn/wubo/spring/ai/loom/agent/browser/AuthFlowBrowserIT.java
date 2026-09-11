package cn.wubo.spring.ai.loom.agent.browser;

import cn.wubo.spring.ai.loom.agent.browser.page.LoginPage;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.RequestOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-1 认证/鉴权链路浏览器 IT。
 *
 * <p>断言事实(与 AuthenticationFilter / LoomAgentConfiguration WebConfiguration 源码核验一致):
 * <ul>
 *   <li>未认证 API(pathPatterns 内,Accept 非 text/html)→ 401 裸状态</li>
 *   <li>未认证 HTML 请求 → 302 → login.html</li>
 *   <li>非 ADMIN 访问 adminPathPatterns(admin/console.html 等)→ 302 → index.html(不是 403)</li>
 *   <li>登录失败 → 401 + JSON {"message":...};成功 → 200 + HttpOnly Set-Cookie loom-agent-session</li>
 *   <li>登出 → 200,session 立即失效(后续 API 401)</li>
 * </ul>
 *
 * <p>负路径(错误密码)必须用全新未登录 context:login.html 对已登录 context 会自动跳 index。
 */
@DisplayName("P0-1 认证链路:登录/401/302/HttpOnly cookie/登出")
class AuthFlowBrowserIT extends BrowserTestBase {

    @Test
    void wrongPasswordShowsError() {
        try (BrowserContext ctx = newContext()) {
            Page page = newPage(ctx);
            LoginPage login = new LoginPage(page);
            login.open(baseUrl).submit(ADMIN_USER, "wrong-pass");
            // 提交后 login.js 异步 fetch → 401 → showError;等待错误框可见
            page.waitForSelector("#error-msg", new Page.WaitForSelectorOptions()
                    .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
                    .setTimeout(10_000));
            assertThat(login.errorVisible()).isTrue();
            assertThat(login.errorMessage()).isNotBlank();
        }
    }

    @Test
    void loginSetsHttpOnlySessionCookie() {
        try (BrowserContext ctx = newContext()) {
            APIResponse resp = ctx.request().post(baseUrl + UI + "user/login",
                    RequestOptions.create()
                            .setHeader("Content-Type", "application/json; charset=UTF-8")
                            .setData("{\"username\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_PASS + "\"}"));
            assertThat(resp.status()).isEqualTo(200);
            String setCookie = String.valueOf(resp.headers().get("set-cookie"));
            assertThat(setCookie).contains("loom-agent-session").containsIgnoringCase("HttpOnly");
            // JS 侧读不到 HttpOnly cookie
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "index.html");
            assertThat((String) page.evaluate("document.cookie")).doesNotContain("loom-agent-session");
        }
    }

    @Test
    void unauthenticatedApiGets401BareStatus() {
        try (BrowserContext ctx = newContext()) {
            APIResponse resp = ctx.request().get(baseUrl + UI + "api/features");
            assertThat(resp.status()).isEqualTo(401);
        }
    }

    @Test
    void unauthenticatedHtmlRedirectsToLogin() {
        try (BrowserContext ctx = newContext()) {
            Page page = newPage(ctx);
            // 302 → login.html;Playwright 页面导航默认 Accept: text/html 且自动跟随重定向
            page.navigate(baseUrl + UI + "admin/console.html");
            page.waitForURL("**/login.html");
            assertThat(page.url()).contains("login.html");
        }
    }

    @Test
    void nonAdminUserRedirectedFromAdminPagesToIndex() {
        String user = "e2euser" + System.currentTimeMillis() % 100000;
        try (BrowserContext admin = adminContext()) {
            APIResponse created = apiSend(admin, "POST", UI + "admin/users",
                    "{\"username\":\"" + user + "\",\"nickname\":\"E2E User\",\"password\":\"e2epass1\",\"type\":\"USER\"}");
            assertThat(created.status()).isEqualTo(200);

            try (BrowserContext ctx = newContext()) {
                loginViaApi(ctx, user, "e2epass1");
                Page page = newPage(ctx);
                page.navigate(baseUrl + UI + "admin/console.html");
                // 非 ADMIN → 302 index.html(产品行为如此,不是 403)
                page.waitForURL("**/index.html");
                assertThat(page.url()).contains("index.html");
            }

            apiSend(admin, "DELETE", UI + "admin/users/" + user, null);
        }
    }

    @Test
    void logoutClearsSession() {
        try (BrowserContext ctx = adminContext()) {
            APIResponse out = apiSend(ctx, "POST", UI + "user/logout", null);
            assertThat(out.status()).isEqualTo(200);
            APIResponse after = ctx.request().get(baseUrl + UI + "api/features");
            assertThat(after.status()).isEqualTo(401);
        }
    }
}
