package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 全页面体检:10 页(index + login + 8 admin)逐页断言
 * HTTP 200 / console 无 error / 关键主容器可见 / 无水平溢出 / load<3s。
 *
 * <p><b>探针参数(执行期校准,源码核验):</b>{@code admin/user.html} 与
 * {@code admin/conversation.html} 的 JS(user.js / conversation.js 顶部)在缺失
 * {@code username} / {@code id} query param 时立即 {@code window.location.replace("console.html")}
 * —— 裸路径体检对象会漂移成 console 页。故本测试对这两页附加确定性探针参数:
 * <ul>
 *   <li>user.html?username=wb04307201(admin 自身,页面走 ADMIN 分支渲染角色卡)</li>
 *   <li>conversation.html?id=page-health-probe&username=wb04307201 —— 不存在的会话 id
 *       走后端 ConversationFlowService.flow 空数据兜底(loadMeta 空行 + count 查询全 0,
 *       HTTP 200),前端渲染友好空状态,主容器 #flow-container 仍可见</li>
 * </ul>
 *
 * <p><b>console 噪声过滤(校准点 1,防御性):</b>项目所有页面均未声明 favicon,
 * 浏览器可能自动请求 {@code /favicon.ico} → 404 → console "Failed to load resource"
 * error(2026-09-11 本机 headless Chromium 实测 11 用例 0 条噪声,过滤分支未触发;
 * 保留过滤器防 CI/浏览器版本差异)。该噪声与页面健康无关(纯静态资源缺失,不影响
 * 任何功能),按 brief 授权在本类收集器内过滤,且被过滤条目全部打印到 stdout 留档
 * 人工复核;除此之外任何 console error / page error(JS 异常)都计入失败,不放宽。
 *
 * <p><b>异步渲染等待(校准点 2):</b>navigate 后先 {@code waitForLoadState(NETWORKIDLE)}
 * (静态 vanilla JS 页面,API fetch 全部短请求,无长轮询),再补
 * {@code waitForTimeout(1500)} 给渲染脚本收尾,然后查 console + 关键元素。
 */
@DisplayName("P1 全页面体检:200/console 无 error/关键元素/无水平溢出/load<3s")
class PageHealthBrowserIT extends BrowserTestBase {

    /** 每页 1-2 个确定性主容器 id(html 源码逐页核验,非 body 兜底)。 */
    private static final Map<String, String> KEY_ELEMENT = Map.ofEntries(
            Map.entry("index.html", "#textarea"),
            Map.entry("admin/console.html", "#user-table-container"),
            Map.entry("admin/stats.html", "#bar-chart"),
            Map.entry("admin/user.html", "#conv-list-container"),
            Map.entry("admin/roles.html", "#role-table-container"),
            Map.entry("admin/mcps.html", "#mcp-table-container"),
            Map.entry("admin/conversation.html", "#flow-container"),
            Map.entry("admin/market-skills.html", "#skill-table-container"),
            Map.entry("admin/knowledge-market.html", "#knowledge-table-container"));

    /** 不存在的会话 id 探针:走 /flow 空数据兜底路径(见类 javadoc)。 */
    private static final String CONV_PROBE_ID = "page-health-probe";

    /** 需 query param 的页面在此补齐探针参数,避免 JS 重定向到 console.html。 */
    private String withProbeParams(String path) {
        return switch (path) {
            case "admin/user.html" -> path + "?username=" + ADMIN_USER;
            case "admin/conversation.html" ->
                    path + "?id=" + CONV_PROBE_ID + "&username=" + ADMIN_USER;
            default -> path;
        };
    }

    /**
     * 噪声过滤版 console 收集器(基类 attachConsoleCollector 只存 text;本增强保留
     * ConsoleMessage.location() —— Playwright 1.50.0 直接返回 String URL,javap 核实 ——
     * 以精确识别 favicon 404;过滤理由见类 javadoc)。
     * 被过滤条目打印 stdout 留档;page error(JS 异常)无条件计入。
     */
    private List<String> attachFilteredConsoleCollector(Page page) {
        List<String> errors = new ArrayList<>();
        page.onConsoleMessage(msg -> {
            if (!"error".equals(msg.type())) return;
            // Playwright 1.50.0:ConsoleMessage.location() 直接返回 String(javap 核实)
            String url = msg.location() != null ? msg.location() : "";
            if (url.contains("favicon.ico")) {
                System.out.println("[PageHealth][noise-filtered] " + msg.text() + " @ " + url);
                return;
            }
            errors.add(msg.text() + (url.isEmpty() ? "" : " @ " + url));
        });
        page.onPageError(err -> errors.add("pageerror: " + err));
        return errors;
    }

    private void assertNoHorizontalOverflow(Page page, String label) {
        Boolean noOverflow = (Boolean) page.evaluate(
                "document.documentElement.scrollWidth <= window.innerWidth + 1");
        assertThat(noOverflow).as(label + " 无水平溢出").isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "index.html",
            "admin/console.html", "admin/stats.html", "admin/user.html",
            "admin/roles.html", "admin/mcps.html", "admin/conversation.html",
            "admin/market-skills.html", "admin/knowledge-market.html"
    })
    void pageLoadsHealthy(String path) {
        // index/admin 页需认证(未认证 HTML → 302 login),统一走 adminContext()
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            List<String> consoleErrors = attachFilteredConsoleCollector(page);

            long t0 = System.currentTimeMillis();
            Response resp = page.navigate(baseUrl + UI + withProbeParams(path));
            page.waitForLoadState(LoadState.NETWORKIDLE);
            long elapsed = System.currentTimeMillis() - t0;

            assertThat(resp).as(path + " 导航有响应").isNotNull();
            assertThat(resp.status()).as(path + " HTTP 200").isEqualTo(200);
            // load<3s:软记录到 stdout 留档,同时按 brief 硬断言(本地 localhost 直出静态页)
            System.out.println("[PageHealth] " + path + " load=" + elapsed + "ms");
            assertThat(elapsed).as(path + " load < 3s(软记录)").isLessThan(3000);

            // 给 vanilla JS 异步渲染收尾后查 console
            page.waitForTimeout(1500);
            assertThat(consoleErrors).as(path + " console 无 error(favicon 404 噪声已过滤)").isEmpty();

            // 关键主容器可见(每页确定性 id,见 KEY_ELEMENT;非 body 兜底)
            String selector = KEY_ELEMENT.get(path);
            page.waitForSelector(selector,
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
            assertThat(page.isVisible(selector))
                    .as(path + " 关键元素可见 " + selector).isTrue();

            assertNoHorizontalOverflow(page, path);
        }
    }

    @Test
    void loginPageHealthyUnauthenticated() {
        // login.html 无认证,走未登录 newContext()
        try (BrowserContext ctx = newContext()) {
            Page page = newPage(ctx);
            List<String> consoleErrors = attachFilteredConsoleCollector(page);

            long t0 = System.currentTimeMillis();
            Response resp = page.navigate(baseUrl + UI + "login.html");
            page.waitForLoadState(LoadState.NETWORKIDLE);
            long elapsed = System.currentTimeMillis() - t0;

            assertThat(resp).as("login.html 导航有响应").isNotNull();
            assertThat(resp.status()).as("login.html HTTP 200").isEqualTo(200);
            System.out.println("[PageHealth] login.html load=" + elapsed + "ms");
            assertThat(elapsed).as("login.html load < 3s(软记录)").isLessThan(3000);

            page.waitForTimeout(1000);
            assertThat(consoleErrors).as("login.html console 无 error").isEmpty();
            assertThat(page.isVisible("#login-form")).as("login.html #login-form 可见").isTrue();
            assertNoHorizontalOverflow(page, "login.html");
        }
    }

    @Test
    void featuresPositiveAndKsButtonVisibleOnDefaultProfile() {
        // 与 FeatureGateBrowserIT(rag.enabled=false 负向)互补:默认 profile 正向门控
        try (BrowserContext ctx = adminContext()) {
            assertThat(apiGet(ctx, UI + "api/features"))
                    .contains("\"knowledge\"").contains("true");
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "index.html");
            page.waitForSelector("#textarea");
            page.waitForTimeout(1500);
            assertThat(page.isVisible("#ks-button"))
                    .as("默认 profile(rag.enabled=true)#ks-button 可见").isTrue();
        }
    }
}
