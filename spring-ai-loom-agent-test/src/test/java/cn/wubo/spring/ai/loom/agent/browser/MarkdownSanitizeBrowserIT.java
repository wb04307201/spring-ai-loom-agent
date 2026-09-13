package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * markdown-renderer.js sanitizeHtml XSS 剥除浏览器直调 IT。
 *
 * <p>加载链:index.html → {@code <script type="module" src="app.js">} →
 * {@code import { sanitizeHtml } from "./markdown-renderer.js"} → 模块求值时执行
 * 尾部 {@code window.sanitizeHtml = sanitizeHtml}(该文件第 84 行,供非 module 脚本用)。
 * 用 waitForFunction 守卫可用性,不放水。
 *
 * <p>断言对照实现(白名单 ALLOWED_TAGS + GLOBAL_ATTRIBUTES/TAG_ATTRIBUTES +
 * isSafeUrl):script 非白名单 → 降为纯文本;textContent 保留;on* / 未列属性剥除;
 * img src=x 非 http(s)/blob → 剥除;href javascript: → isSafeUrl 拒 → 剥除;
 * class 属 GLOBAL_ATTRIBUTES → 保留;strong/em/code 白名单原样保留。
 */
@DisplayName("markdown-renderer sanitizeHtml:XSS 载荷剥除(page.evaluate 直调)")
class MarkdownSanitizeBrowserIT extends BrowserTestBase {

    private String sanitize(Page page, String payload) {
        return (String) page.evaluate("html => window.sanitizeHtml(html)", payload);
    }

    @Test
    void stripsScriptEventAndUnsafeUrl() {
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "index.html");
            page.waitForFunction("typeof window.sanitizeHtml === 'function'");

            assertThat(sanitize(page, "<script>alert(1)</script>hello"))
                    .doesNotContain("<script").contains("hello");
            assertThat(sanitize(page, "<img src=x onerror=alert(1)>"))
                    .doesNotContain("onerror");
            assertThat(sanitize(page, "<a href=\"javascript:alert(1)\">x</a>"))
                    .doesNotContain("javascript:");
            assertThat(sanitize(page, "<p onclick=\"evil()\" class=\"ok\">t</p>"))
                    .doesNotContain("onclick").contains("class=\"ok\"");
            // 白名单标签保留
            assertThat(sanitize(page, "<strong>b</strong><em>i</em><code>c</code>"))
                    .contains("<strong>").contains("<em>").contains("<code>");
        }
    }
}
