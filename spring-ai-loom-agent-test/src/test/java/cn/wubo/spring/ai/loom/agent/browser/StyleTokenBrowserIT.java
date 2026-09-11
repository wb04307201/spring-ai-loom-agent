package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 样式回归:计算样式断言(非截图)。
 *
 * <p>真值来源(单一真源 = 源码):
 * <ul>
 *   <li>{@code style.css} :root — --primary-color #6366f1 / --primary-hover #4f46e5 /
 *       --bg-primary #ffffff / --bg-secondary #f8fafc / --text-primary #1e293b /
 *       --text-secondary #64748b / --error-color #ef4444 / --success-color #10b981 /
 *       --header-height 60px / --border-color #e2e8f0;主应用顶栏 {@code header.header}。</li>
 *   <li>{@code login.css} :root — 登录页 token 名为 {@code --primary}(非 --primary-color),
 *       值同为 #6366f1;顶栏 {@code header.app-header}(白底 --bg-card #ffffff,60px);
 *       品牌卡 {@code .login-card} 含圆形 {@code .brand-logo}(border-radius 50%)+ h1「灵梭」。</li>
 * </ul>
 */
@DisplayName("L2 样式回归:设计 token / 顶栏 60px / 对比度 AA / 登录页布局契约 / 双页 token 一致")
class StyleTokenBrowserIT extends BrowserTestBase {

    private static String cssVar(Page page, String name) {
        return (String) page.evaluate(
                "getComputedStyle(document.documentElement).getPropertyValue('" + name + "').trim()");
    }

    /** sRGB 相对亮度(WCAG 2.x) */
    private static double luminance(int r, int g, int b) {
        double[] c = {r / 255.0, g / 255.0, b / 255.0};
        for (int i = 0; i < 3; i++) {
            c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static double contrastRatio(String fgRgb, String bgRgb) {
        int[] fg = parseRgb(fgRgb);
        int[] bg = parseRgb(bgRgb);
        double l1 = luminance(fg[0], fg[1], fg[2]);
        double l2 = luminance(bg[0], bg[1], bg[2]);
        double hi = Math.max(l1, l2);
        double lo = Math.min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** 解析 "rgb(30, 41, 59)" / "rgba(...)" / "#1e293b"。 */
    private static int[] parseRgb(String s) {
        String v = s.trim();
        if (v.startsWith("#")) {
            return new int[]{Integer.parseInt(v.substring(1, 3), 16),
                    Integer.parseInt(v.substring(3, 5), 16),
                    Integer.parseInt(v.substring(5, 7), 16)};
        }
        String[] p = v.replaceAll("[^0-9,]", "").split(",");
        return new int[]{Integer.parseInt(p[0].trim()),
                Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())};
    }

    @Test
    @DisplayName("index 设计 token:10 个核心 CSS 变量与 style.css :root 精确一致")
    void designTokensOnIndex() {
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "index.html");
            page.waitForSelector("#textarea");
            assertThat(cssVar(page, "--primary-color")).isEqualTo("#6366f1");
            assertThat(cssVar(page, "--primary-hover")).isEqualTo("#4f46e5");
            assertThat(cssVar(page, "--bg-primary")).isEqualTo("#ffffff");
            assertThat(cssVar(page, "--bg-secondary")).isEqualTo("#f8fafc");
            assertThat(cssVar(page, "--text-primary")).isEqualTo("#1e293b");
            assertThat(cssVar(page, "--text-secondary")).isEqualTo("#64748b");
            assertThat(cssVar(page, "--error-color")).isEqualTo("#ef4444");
            assertThat(cssVar(page, "--success-color")).isEqualTo("#10b981");
            assertThat(cssVar(page, "--border-color")).isEqualTo("#e2e8f0");
            assertThat(cssVar(page, "--header-height")).isEqualTo("60px");
        }
    }

    @Test
    @DisplayName("index 顶栏 header.header 实测 60px(±1)+ body 文本对比度 ≥ 4.5(WCAG AA)")
    void headerIs60pxAndBodyTextContrastPassesAA() {
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "index.html");
            page.waitForSelector("#textarea");

            // Playwright 整数反序列化为 Integer,统一走 Number.doubleValue()
            double h = ((Number) page.evaluate(
                    "document.querySelector('header.header')?.getBoundingClientRect().height ?? -1"))
                    .doubleValue();
            assertThat(h).as("主应用顶栏 header.header 高度 60px(±1)").isBetween(59.0, 61.0);

            String color = (String) page.evaluate("getComputedStyle(document.body).color");
            String bg = (String) page.evaluate("getComputedStyle(document.body).backgroundColor");
            // style.css body 未显式设 color(浏览器默认黑)且 background=--bg-secondary;
            // 若某环境 body 背景透明,回退画布底色 --bg-primary #ffffff 计算。
            if (bg.equals("rgba(0, 0, 0, 0)") || bg.equals("transparent")) {
                bg = "#ffffff";
            }
            double ratio = contrastRatio(color, bg);
            System.out.printf("[StyleTokenBrowserIT] body 对比度实测: fg=%s bg=%s ratio=%.2f%n",
                    color, bg, ratio);
            assertThat(ratio)
                    .as("body 文本对比度 ≥ 4.5(fg=%s bg=%s 实测=%.2f)", color, bg, ratio)
                    .isGreaterThanOrEqualTo(4.5);
        }
    }

    @Test
    @DisplayName("登录页 A+B 组合布局契约:白顶栏(亮度>0.9)+ 品牌卡「灵梭」+ 圆形 logo + 表单三元素可见")
    void loginPageLayoutContract() {
        try (BrowserContext ctx = newContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "login.html");
            page.waitForSelector("#login-form");

            // A:与主应用同款 60px 白顶栏(login.html 实际结构 = header.app-header)
            assertThat(page.locator("header.app-header").isVisible())
                    .as("登录页顶栏 header.app-header 可见").isTrue();
            String headerBg = (String) page.evaluate(
                    "getComputedStyle(document.querySelector('header.app-header')).backgroundColor");
            int[] rgb = parseRgb(headerBg);
            assertThat(luminance(rgb[0], rgb[1], rgb[2]))
                    .as("登录页顶栏近白(bg=%s)", headerBg).isGreaterThan(0.9);
            double headerH = ((Number) page.evaluate(
                    "document.querySelector('header.app-header').getBoundingClientRect().height"))
                    .doubleValue();
            assertThat(headerH).as("登录页顶栏同为 60px(±1)").isBetween(59.0, 61.0);

            // B:居中品牌卡 — 圆形 logo + 品牌文案「灵梭」+ English caption
            assertThat(page.locator(".login-card .brand-logo").isVisible())
                    .as("品牌卡圆形 logo 可见").isTrue();
            // 圆形契约:computed border-radius 会被解析为 px(50% of 56px = 28px),
            // 数值断言 radius ≥ width/2(允许 0.5px 舍入),不比对字符串 "50%"。
            @SuppressWarnings("unchecked")
            java.util.List<Object> logo = (java.util.List<Object>) page.evaluate("(() => {"
                    + " const el = document.querySelector('.login-card .brand-logo');"
                    + " const r = parseFloat(getComputedStyle(el).borderTopLeftRadius);"
                    + " const w = el.getBoundingClientRect().width;"
                    + " return [r, w]; })()");
            double radius = ((Number) logo.get(0)).doubleValue();
            double width = ((Number) logo.get(1)).doubleValue();
            assertThat(radius).as("brand-logo 圆形(radius %.1fpx ≥ width %.1fpx / 2)", radius, width)
                    .isGreaterThanOrEqualTo(width / 2 - 0.5);
            assertThat(page.locator(".login-card h1").innerText()).isEqualTo("灵梭");
            assertThat(page.getByText("灵梭").count()).isGreaterThanOrEqualTo(1);

            // 表单三元素齐备可见
            assertThat(page.isVisible("#username")).isTrue();
            assertThat(page.isVisible("#password")).isTrue();
            assertThat(page.isVisible("#submit-btn")).isTrue();
        }
    }

    @Test
    @DisplayName("无感切换契约:login(--primary)与 index(--primary-color)主色一致 = #6366f1")
    void primaryTokenConsistentBetweenLoginAndIndex() {
        // 校准:login.css 的 token 名是 --primary(login.html 不加载 style.css),
        // 主应用是 --primary-color;契约是"值一致",按各自实际变量名读取。
        try (BrowserContext ctxAnon = newContext(); BrowserContext ctxAdmin = adminContext()) {
            Page login = newPage(ctxAnon);
            login.navigate(baseUrl + UI + "login.html");
            login.waitForSelector("#login-form");
            Page index = newPage(ctxAdmin);
            index.navigate(baseUrl + UI + "index.html");
            index.waitForSelector("#textarea");

            String loginPrimary = cssVar(login, "--primary");
            String indexPrimary = cssVar(index, "--primary-color");
            assertThat(loginPrimary).as("login --primary").isEqualTo("#6366f1");
            assertThat(indexPrimary).as("index --primary-color").isEqualTo("#6366f1");
            assertThat(loginPrimary).isEqualTo(indexPrimary);
        }
    }
}
