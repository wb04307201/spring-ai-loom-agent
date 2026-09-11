package cn.wubo.spring.ai.loom.agent.browser;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ReducedMotion;
import com.microsoft.playwright.options.RequestOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 浏览器 IT 基类:RANDOM_PORT 起应用 + Playwright Chromium(缺失 assumeTrue 跳过,
 * 镜像 HtmlRenderEngineIT 守卫模式)。数据走 src/test/resources/application.yml 的
 * ./target/test-ds(IT 仪式清库);文件目录覆盖到 ./target/e2e-files,不触碰 ~/.loom。
 *
 * <p>PER_CLASS 生命周期:@BeforeAll 非静态,可注入 @LocalServerPort。
 *
 * <p>viewport 裁定(Task 2 控制器):所有 context 统一 1280×800 / deviceScaleFactor=1 /
 * reducedMotion=REDUCE —— Task 13 截图基线要求确定性视觉环境,第一版起固定,不中途改基类。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.loom.agent.file-base-path=./target/e2e-files/file",
                "spring.ai.loom.agent.knowledge-base-path=./target/e2e-files/knowledge"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BrowserTestBase {

    protected static final String ADMIN_USER = "wb04307201";
    protected static final String ADMIN_PASS = "123456";
    protected static final String UI = "/spring/ai/loom/";

    @LocalServerPort
    protected int port;

    protected String baseUrl;
    protected Playwright playwright;
    protected Browser browser;

    private final Map<Page, List<String>> consoleErrors = new ConcurrentHashMap<>();

    @BeforeAll
    void launchBrowser() {
        baseUrl = "http://localhost:" + port;
        boolean available = true;
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
        } catch (Exception e) {
            available = false;
            System.out.println("[BrowserTestBase] Chromium 不可用,跳过: " + e.getMessage());
        }
        assumeTrue(available, "跳过:Chromium 不可用(先跑 playwright install chromium)");
    }

    @AfterAll
    void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    protected BrowserContext newContext() {
        return browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1280, 800)
                .setDeviceScaleFactor(1)
                .setReducedMotion(ReducedMotion.REDUCE));
    }

    protected Page newPage(BrowserContext ctx) {
        Page page = ctx.newPage();
        attachConsoleCollector(page);
        return page;
    }

    /** API 登录(共享 context cookie jar),后续页面导航即已认证。 */
    protected void loginViaApi(BrowserContext ctx, String username, String password) {
        APIResponse resp = ctx.request().post(baseUrl + UI + "user/login",
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json; charset=UTF-8")
                        .setData("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
        if (resp.status() != 200) {
            throw new IllegalStateException("login failed " + resp.status() + " for " + username);
        }
    }

    protected BrowserContext adminContext() {
        BrowserContext ctx = newContext();
        loginViaApi(ctx, ADMIN_USER, ADMIN_PASS);
        return ctx;
    }

    protected String apiGet(BrowserContext ctx, String path) {
        APIResponse resp = ctx.request().get(baseUrl + path);
        if (resp.status() != 200) {
            throw new IllegalStateException("GET " + path + " -> " + resp.status());
        }
        return resp.text();
    }

    /** 通用 admin API 调用;jsonBody 可为 null。 */
    protected APIResponse apiSend(BrowserContext ctx, String method, String path, String jsonBody) {
        RequestOptions opts = RequestOptions.create()
                .setHeader("Content-Type", "application/json; charset=UTF-8");
        if (jsonBody != null) opts.setData(jsonBody);
        return switch (method.toUpperCase()) {
            case "POST" -> ctx.request().post(baseUrl + path, opts);
            case "PUT" -> ctx.request().put(baseUrl + path, opts);
            case "PATCH" -> ctx.request().patch(baseUrl + path, opts);
            case "DELETE" -> ctx.request().delete(baseUrl + path, opts);
            default -> ctx.request().get(baseUrl + path, opts);
        };
    }

    protected void attachConsoleCollector(Page page) {
        List<String> errors = new ArrayList<>();
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) errors.add(msg.text());
        });
        page.onPageError(err -> errors.add(String.valueOf(err)));
        // Playwright Java 无 page 级 attribute;用 map 关联
        consoleErrors.put(page, errors);
    }

    protected List<String> consoleErrorsOf(Page page) {
        return consoleErrors.getOrDefault(page, List.of());
    }
}
