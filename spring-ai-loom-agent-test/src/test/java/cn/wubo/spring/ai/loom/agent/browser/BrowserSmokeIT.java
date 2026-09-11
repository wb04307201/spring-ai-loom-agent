package cn.wubo.spring.ai.loom.agent.browser;

import cn.wubo.spring.ai.loom.agent.browser.page.IndexPage;
import cn.wubo.spring.ai.loom.agent.browser.page.LoginPage;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("浏览器基建冒烟:登录表单 → index 加载")
class BrowserSmokeIT extends BrowserTestBase {

    @Test
    void loginFormReachesIndex() {
        // BrowserContext 在 Playwright 1.50.0 实现 AutoCloseable(javap 已核实),可 try-with-resources
        try (BrowserContext ctx = newContext()) {
            Page page = newPage(ctx);
            LoginPage login = new LoginPage(page);
            login.open(baseUrl).submit(ADMIN_USER, ADMIN_PASS);
            login.expectRedirectToIndex();
            new IndexPage(page).expectLoaded();
            assertThat(page.isVisible("#send-btn")).isTrue();
        }
    }
}
