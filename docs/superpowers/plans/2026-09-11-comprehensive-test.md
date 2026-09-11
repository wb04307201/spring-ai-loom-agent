# 全面测试(单元 + 浏览器 E2E + 样式)实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Spring AI LoomAgent 建立四层测试网:L1 单元基线 + L3 Java Playwright 浏览器 E2E(P0/P1/P2 分层)+ L2 样式回归(计算样式断言 + 截图基线)+ L4 一次性评估(AI 视觉审查 + Lighthouse),并产出汇总报告。

**Architecture:** 所有浏览器测试落在 `spring-ai-loom-agent-test` 模块新包 `cn.wubo.spring.ai.loom.agent.browser`,命名 `*BrowserIT`(被现有 `-Dtest='*IT'` 门禁自动收编)。`@SpringBootTest(RANDOM_PORT)` 同 JVM 起应用,Playwright Chromium 访问 `http://localhost:{port}`;Chromium 缺失走 `assumeTrue` 跳过(镜像 `HtmlRenderEngineIT`)。数据用 test 模块既有 `./target/test-ds`(IT 仪式清库),文件目录覆盖为 `./target/e2e-files`,绝不触碰 `~/.loom`。

**Tech Stack:** JUnit 5 + Spring Boot Test(RANDOM_PORT + @LocalServerPort)+ com.microsoft.playwright 1.50.0(已在 test 模块 compile scope)+ 纯 Java BufferedImage 像素 diff。零 Node 工具链。

**Spec:** `docs/superpowers/specs/2026-09-11-comprehensive-test-design.md`

## Global Constraints

- JDK 17;Playwright 固定 **1.50.0**(父 pom dependencyManagement,不得改版本)
- 测试类命名必须以 `IT` 结尾(项目无 failsafe,IT 门禁命令:`mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false`,跑前必须 `rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports`)
- 每个含 Chromium 的测试类必须经 `BrowserTestBase` 的 `assumeTrue` 守卫,环境缺失 = skip 而非 fail
- 种子 admin:**`wb04307201` / `123456`**(V1.0__init.sql,BCrypt);cookie 名 **`loom-agent-session`**(HttpOnly, SameSite=Lax, path=/)
- 鉴权失败形态(实测源码,断言按此写):未认证 API → **401 裸状态无 body**;未认证 HTML → **302 → login.html**;非 ADMIN 访问 `/spring/ai/loom/admin/**` → **302 → index.html**(不存在 filter 层 403)
- 登录端点:`POST /spring/ai/loom/user/login`,body `{"username":"...","password":"..."}`,失败 401 + `{"message":"..."}`
- `/api/features` 返回精确为 `{"knowledge":<boolean>}`;`rag.enabled` 属性 key = `spring.ai.loom.agent.rag.enabled`;fileBasePath key = `spring.ai.loom.agent.file-base-path`
- CSS 变量真值(style.css `:root`):`--primary-color: #6366f1`、`--bg-secondary: #f8fafc`、`--header-height: 60px`、`--text-primary: #1e293b`、`--bg-primary: #ffffff`、`--error-color: #ef4444`、`--success-color: #10b981`(共 25 个)
- universal 工具组(7):`tool_schedule` / `tool_subtask` / `tool_knowledge` / `tool_time` / `tool_skill` / `tool_file` / `tool_askUser`;RBAC 工具组(4):`tool_git` / `tool_maven` / `tool_compile` / `tool_render`
- admin API(预置数据用,cookie 鉴权):`POST /spring/ai/loom/admin/users`(`{username,nickname,password,type}`)、`POST /spring/ai/loom/admin/roles`(`{code,name,description,mcpNames}`)、`PUT /spring/ai/loom/admin/roles/{code}/tools`(`{"items":[{"groupName":"tool_git","defaultEnabled":true}]}`)、`PUT /spring/ai/loom/admin/users/{username}/roles`(`{"roleCodes":["..."]}`)、`GET /spring/ai/loom/api/capabilities`
- 用户名不得含 `-`,密码 ≥6 位(DefaultUser.createUser 校验)
- commit 前缀:`test:` / `docs:`;产品 bug 不混入测试 commit(记录进 findings,单独走 systematic-debugging)
- 页面清单(10 个 HTML):`index.html`、`login.html`、`admin/console.html`、`admin/stats.html`、`admin/user.html`、`admin/roles.html`、`admin/mcps.html`、`admin/conversation.html`、`admin/market-skills.html`、`admin/knowledge-market.html`(前缀 `/spring/ai/loom/`)

---

### Task 1: 基线门禁 + 环境准备

**Files:** 无新增(纯验证)

- [ ] **Step 1: 清库 + 跑单元基线**

```bash
cd /c/developer/IdeaProjects/spring-ai-loom-agent
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn test -pl spring-ai-loom-agent-test
```
Expected: `Tests run: 380` 量级,Failures: 0, Errors: 0。**不绿则停止,先修基线**(systematic-debugging)。

- [ ] **Step 2: 跑 IT 基线**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `Tests run: 80, Failures: 0, Errors: 0, Skipped: ≤3`(skip 为 env-gated)。不绿则停止先修。

- [ ] **Step 3: 确认 Chromium 可用**

直接跑现有守卫 IT 验证(它会真启动 Chromium):

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest='HtmlRenderEngineIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS(非 Skipped)。若 Skipped,用 Playwright CLI 下载(项目无 exec 插件配置,走 dependency:build-classpath):

```bash
mvn -q dependency:build-classpath -pl spring-ai-loom-agent-test -Dmdep.outputFile=target/cp.txt -Dgpg.skip=true
java -cp "$(cat spring-ai-loom-agent-test/target/cp.txt)" com.microsoft.playwright.CLI install chromium
```
下载后复跑 HtmlRenderEngineIT 确认 PASS。

- [ ] **Step 4: 无 commit(纯验证任务)**

---

### Task 2: BrowserTestBase + LoginPage + IndexPage + 冒烟 IT

**Files:**
- Create: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/browser/BrowserTestBase.java`
- Create: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/browser/page/LoginPage.java`
- Create: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/browser/page/IndexPage.java`
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/browser/BrowserSmokeIT.java`

**Interfaces:**
- Produces(后续所有任务依赖):
  - `BrowserTestBase`:字段 `protected int port`、`protected String baseUrl`、`protected Browser browser`;方法 `BrowserContext newContext()`、`Page newPage(BrowserContext ctx)`、`void loginViaApi(BrowserContext ctx, String username, String password)`、`BrowserContext adminContext()`(新 context + admin 已登录)、`String apiGet(BrowserContext ctx, String path)`、`APIResponse apiSend(BrowserContext ctx, String method, String path, String jsonBody)`、`List<String> consoleErrorsOf(Page page)`(需先 `attachConsoleCollector(Page)`)
  - `LoginPage`:`open(String baseUrl)`、`submit(String user, String pass)`、`expectRedirectToIndex()`、`errorVisible()`、`errorMessage()`
  - `IndexPage`:`open(String baseUrl)`、`expectLoaded()`(`#textarea` 可见)

- [ ] **Step 1: 写 BrowserTestBase**

```java
package cn.wubo.spring.ai.loom.agent.browser;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 浏览器 IT 基类:RANDOM_PORT 起应用 + Playwright Chromium(缺失 assumeTrue 跳过,
 * 镜像 HtmlRenderEngineIT 守卫模式)。数据走 src/test/resources/application.yml 的
 * ./target/test-ds(IT 仪式清库);文件目录覆盖到 ./target/e2e-files,不触碰 ~/.loom。
 *
 * PER_CLASS 生命周期:@BeforeAll 非静态,可注入 @LocalServerPort。
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
                .setViewportSize(1440, 900));
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

    private static final String CONSOLE_ERRORS = "__consoleErrors";

    protected void attachConsoleCollector(Page page) {
        List<String> errors = new ArrayList<>();
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) errors.add(msg.text());
        });
        page.onPageError(err -> errors.add(String.valueOf(err)));
        // Playwright Java 无 page 级 attribute;用 map 关联
        consoleErrors.put(page, errors);
    }

    private final Map<Page, List<String>> consoleErrors = new java.util.concurrent.ConcurrentHashMap<>();

    protected List<String> consoleErrorsOf(Page page) {
        return consoleErrors.getOrDefault(page, List.of());
    }
}
```

注意:`RequestOptions.patch` 若 1.50.0 的 `APIRequestContext` 无 `patch` 方法,改用 `ctx.request().fetch(url, opts.setMethod("PATCH"))`——写代码时以 IDE 补全为准,其余不变。

- [ ] **Step 2: 写 LoginPage / IndexPage**

```java
package cn.wubo.spring.ai.loom.agent.browser.page;

import com.microsoft.playwright.Page;

/** login.html Page Object(选择器原样抄自 login.html/login.js)。 */
public class LoginPage {
    private final Page page;
    public LoginPage(Page page) { this.page = page; }

    public LoginPage open(String baseUrl) {
        page.navigate(baseUrl + "/spring/ai/loom/login.html");
        page.waitForSelector("#login-form");
        return this;
    }
    public void submit(String username, String password) {
        page.fill("#username", username);
        page.fill("#password", password);
        page.click("#submit-btn");
    }
    public void expectRedirectToIndex() {
        page.waitForURL("**/spring/ai/loom/index.html",
                new Page.WaitForURLOptions().setTimeout(10_000));
    }
    public boolean errorVisible() { return page.isVisible("#error-msg"); }
    public String errorMessage() { return page.textContent("#error-msg"); }
}
```

```java
package cn.wubo.spring.ai.loom.agent.browser.page;

import com.microsoft.playwright.Page;

/** index.html Page Object(仅常用入口,细粒度选择器测试内直接用)。 */
public class IndexPage {
    private final Page page;
    public IndexPage(Page page) { this.page = page; }

    public IndexPage open(String baseUrl) {
        page.navigate(baseUrl + "/spring/ai/loom/index.html");
        expectLoaded();
        return this;
    }
    public void expectLoaded() {
        page.waitForSelector("#textarea",
                new Page.WaitForSelectorOptions().setTimeout(15_000));
    }
    public void send(String text) {
        page.fill("#textarea", text);
        page.click("#send-btn");
    }
}
```

- [ ] **Step 3: 写 BrowserSmokeIT(基建自验证)**

```java
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
```

(`BrowserContext` 实现 `AutoCloseable`,可用 try-with-resources;若 1.50.0 未实现则手动 `finally { ctx.close(); }`。)

- [ ] **Step 4: 跑冒烟**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest='BrowserSmokeIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。若 Chromium 下载失败 → Skipped(先解决环境再继续)。

- [ ] **Step 5: Commit**

```bash
git add spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/browser/
git commit -m "test: 浏览器 E2E 基建 BrowserTestBase + LoginPage/IndexPage + 冒烟 IT"
```

---

### Task 3: P0-1 认证/鉴权链路(`AuthFlowBrowserIT`)

**Files:**
- Test: `.../browser/AuthFlowBrowserIT.java`

**Interfaces:** Consumes Task 2 全部;Produces:无(叶子测试)。

- [ ] **Step 1: 写测试**

```java
package cn.wubo.spring.ai.loom.agent.browser;

import cn.wubo.spring.ai.loom.agent.browser.page.LoginPage;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("P0-1 认证链路:登录/401/302/HttpOnly cookie/登出")
class AuthFlowBrowserIT extends BrowserTestBase {

    @Test
    void wrongPasswordShowsError() {
        try (BrowserContext ctx = newContext()) {
            Page page = newPage(ctx);
            LoginPage login = new LoginPage(page);
            login.open(baseUrl).submit(ADMIN_USER, "wrong-pass");
            assertThat(login.errorVisible()).isTrue();
            assertThat(login.errorMessage()).isNotBlank();
        }
    }

    @Test
    void loginSetsHttpOnlySessionCookie() {
        try (BrowserContext ctx = newContext()) {
            APIResponse resp = ctx.request().post(baseUrl + UI + "user/login",
                    com.microsoft.playwright.RequestOptions.create()
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
            Response resp = page.navigate(baseUrl + UI + "admin/console.html");
            // 302 → login.html;Playwright 跟随后终态 URL 为 login
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
```

- [ ] **Step 2: 跑**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest='AuthFlowBrowserIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 6 PASS。失败若是产品 bug → 记录 findings,不在本 commit 修。

- [ ] **Step 3: Commit** — `test: P0-1 认证鉴权链路浏览器 IT(登录/401/302/HttpOnly/登出)`

---

### Task 4: P0-2 知识空间门控(`FeatureGateBrowserIT`,含 rag-off profile)

**Files:**
- Test: `.../browser/FeatureGateBrowserIT.java`

- [ ] **Step 1: 写测试(rag.enabled=false 独立 context 缓存键 → 第二个 Spring context)**

```java
package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.NestedTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("P0-2 知识空间门控:rag.enabled=false → features.knowledge=false + ks-button 隐藏")
@SpringBootTest(classes = cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.loom.agent.rag.enabled=false",
                "spring.ai.loom.agent.file-base-path=./target/e2e-files/file",
                "spring.ai.loom.agent.knowledge-base-path=./target/e2e-files/knowledge"
        })
class FeatureGateBrowserIT extends BrowserTestBase {

    @Test
    void featuresReportsKnowledgeFalse() {
        try (BrowserContext ctx = adminContext()) {
            String body = apiGet(ctx, UI + "api/features");
            assertThat(body).contains("\"knowledge\"").contains("false");
        }
    }

    @Test
    void ksButtonHiddenWhenRagDisabled() {
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "index.html");
            page.waitForSelector("#textarea");
            // app.js 读 /api/features 后隐藏;给前端一点异步时间
            page.waitForTimeout(2000);
            assertThat(page.isVisible("#ks-button")).isFalse();
        }
    }
}
```

注意:本类**自带 @SpringBootTest 注解覆盖基类**(不同 properties → 独立 context + 独立端口);H2 `AUTO_SERVER=TRUE` 允许与默认 context 共享 test-ds 文件。基类注解仍在,子类注解优先(Spring Boot 就近覆盖)。若实测发现基类注解未被覆盖(两 context 端口/属性冲突),把基类改为不含 @SpringBootTest 的抽象层 + 两个带注解的中间基类(`DefaultProfileBrowserTestBase` / `RagOffBrowserTestBase`)——先按就近覆盖写,跑不过再重构。

- [ ] **Step 2: 默认 profile 正向断言补进 `PageHealthBrowserIT` 前置**(见 Task 6:`/api/features` → `"knowledge":true` 且 `#ks-button` 可见)

- [ ] **Step 3: 跑**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest='FeatureGateBrowserIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 2 PASS。

- [ ] **Step 4: Commit** — `test: P0-2 知识空间全局开关浏览器门控 IT(rag.enabled=false profile)`

---

### Task 5: P0-3/P0-4 RBAC 工具过滤(`RbacToolsBrowserIT`)

**Files:**
- Test: `.../browser/RbacToolsBrowserIT.java`

- [ ] **Step 1: 写测试**

```java
package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("P0-3/P0-4 RBAC:capabilities 过滤 + 工具弹窗 universal 无 checkbox + 授权后可见")
class RbacToolsBrowserIT extends BrowserTestBase {

    private static final List<String> UNIVERSAL = List.of(
            "tool_schedule", "tool_subtask", "tool_knowledge", "tool_time",
            "tool_skill", "tool_file", "tool_askUser");
    private static final List<String> RBAC_GROUPS = List.of(
            "tool_git", "tool_maven", "tool_compile", "tool_render");

    private String user;
    private String role;

    @AfterEach
    void cleanup() {
        try (BrowserContext admin = adminContext()) {
            if (user != null) apiSend(admin, "DELETE", UI + "admin/users/" + user, null);
            if (role != null) apiSend(admin, "DELETE", UI + "admin/roles/" + role, null);
        }
        user = null; role = null;
    }

    private void provision(String roleCode, boolean grantGit) {
        long stamp = System.currentTimeMillis() % 1000000;
        user = "rbacu" + stamp;
        role = roleCode + stamp;
        try (BrowserContext admin = adminContext()) {
            assertThat(apiSend(admin, "POST", UI + "admin/users",
                    "{\"username\":\"" + user + "\",\"nickname\":\"RBAC U\",\"password\":\"rbacpass1\",\"type\":\"USER\"}").status())
                    .isEqualTo(200);
            assertThat(apiSend(admin, "POST", UI + "admin/roles",
                    "{\"code\":\"" + role + "\",\"name\":\"rbac-e2e\",\"description\":\"e2e\",\"mcpNames\":[]}").status())
                    .isEqualTo(200);
            if (grantGit) {
                assertThat(apiSend(admin, "PUT", UI + "admin/roles/" + role + "/tools",
                        "{\"items\":[{\"groupName\":\"tool_git\",\"defaultEnabled\":true}]}").status())
                        .isEqualTo(200);
            }
            assertThat(apiSend(admin, "PUT", UI + "admin/users/" + user + "/roles",
                    "{\"roleCodes\":[\"" + role + "\"]}").status()).isEqualTo(200);
        }
    }

    @Test
    void zeroRoleUserSeesOnlyUniversalInCapabilities() {
        provision("zerorole", false);
        try (BrowserContext ctx = newContext()) {
            loginViaApi(ctx, user, "rbacpass1");
            String caps = apiGet(ctx, UI + "api/capabilities");
            for (String u : UNIVERSAL) {
                assertThat(caps).as("universal 可见 " + u).contains("\"" + u + "\"");
            }
            for (String r : RBAC_GROUPS) {
                assertThat(caps).as("RBAC 不可见 " + r).doesNotContain("\"" + r + "\"");
            }
        }
    }

    @Test
    void grantedRoleUserSeesToolGit() {
        provision("gitrole", true);
        try (BrowserContext ctx = newContext()) {
            loginViaApi(ctx, user, "rbacpass1");
            String caps = apiGet(ctx, UI + "api/capabilities");
            assertThat(caps).contains("\"tool_git\"");
        }
    }

    @Test
    void toolsModalHidesUniversalCheckboxes() {
        provision("modalrole", false);
        try (BrowserContext ctx = newContext()) {
            loginViaApi(ctx, user, "rbacpass1");
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "index.html");
            page.waitForSelector("#textarea");
            page.click("#mcp-button");
            page.waitForSelector("#mcp-modal-overlay", 
                    new Page.WaitForSelectorOptions().setTimeout(5000));
            String listHtml = (String) page.evaluate("document.getElementById('mcp-list').innerHTML");
            // universal 组不渲染 checkbox(input)条目;RBAC 组也未授权 → 弹窗中不应出现 tool_git 可勾选项
            assertThat(listHtml).doesNotContain("tool_askUser");
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    @Test
    void subtaskAndScheduleButtonsVisibleForPlainUser() {
        provision("subtaskrole", false);
        try (BrowserContext ctx = newContext()) {
            loginViaApi(ctx, user, "rbacpass1");
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "index.html");
            page.waitForSelector("#textarea");
            assertThat(page.isVisible("#subtask-button")).isTrue();
            assertThat(page.isVisible("#schedule-button")).isTrue();
        }
    }
}
```

注:`#mcp-list` 内部 DOM 结构以实际渲染为准;若 universal 确实完全不渲染,`doesNotContain("tool_askUser")` 成立;若前端以名称(非 group id)渲染,则改为断言列表中不存在 checkbox `input[type=checkbox]` 属于 universal 组——执行时先 `page.content()` 观察一次实际 DOM 再定断言(把观察结论写进测试注释)。

- [ ] **Step 2: 跑** — `mvn test -pl spring-ai-loom-agent-test -Dtest='RbacToolsBrowserIT' -Dsurefire.failIfNoSpecifiedTests=false` → 4 PASS
- [ ] **Step 3: Commit** — `test: P0-3/P0-4 RBAC 工具过滤浏览器 IT(capabilities/弹窗/universal 入口)`

---

### Task 6: P1 全页面体检(`PageHealthBrowserIT`)

**Files:**
- Test: `.../browser/PageHealthBrowserIT.java`

- [ ] **Step 1: 写测试(参数化 9 页 + login 单独无认证 context)**

```java
package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("P1 全页面体检:200/console 无 error/关键元素/无水平溢出/load<3s")
class PageHealthBrowserIT extends BrowserTestBase {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "index.html",
            "admin/console.html", "admin/stats.html", "admin/user.html",
            "admin/roles.html", "admin/mcps.html", "admin/conversation.html",
            "admin/market-skills.html", "admin/knowledge-market.html"
    })
    void pageLoadsHealthy(String path) {
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            long t0 = System.currentTimeMillis();
            Response resp = page.navigate(baseUrl + UI + path);
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
            long elapsed = System.currentTimeMillis() - t0;

            assertThat(resp.status()).as(path + " HTTP 200").isEqualTo(200);
            assertThat(elapsed).as(path + " load < 3s(软记录)").isLessThan(3000);
            // 给 Vue-free 异步渲染留时间后查 console
            page.waitForTimeout(1500);
            assertThat(consoleErrorsOf(page)).as(path + " console 无 error").isEmpty();
            Boolean overflow = (Boolean) page.evaluate(
                    "document.documentElement.scrollWidth <= window.innerWidth + 1");
            assertThat(overflow).as(path + " 无水平溢出").isTrue();
        }
    }

    @Test
    void loginPageHealthyUnauthenticated() {
        try (BrowserContext ctx = newContext()) {
            Page page = newPage(ctx);
            Response resp = page.navigate(baseUrl + UI + "login.html");
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
            assertThat(resp.status()).isEqualTo(200);
            page.waitForTimeout(1000);
            assertThat(consoleErrorsOf(page)).isEmpty();
            assertThat(page.isVisible("#login-form")).isTrue();
            assertThat((Boolean) page.evaluate(
                    "document.documentElement.scrollWidth <= window.innerWidth + 1")).isTrue();
        }
    }

    @Test
    void featuresPositiveAndKsButtonVisibleOnDefaultProfile() {
        try (BrowserContext ctx = adminContext()) {
            assertThat(apiGet(ctx, UI + "api/features")).contains("\"knowledge\"").contains("true");
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "index.html");
            page.waitForSelector("#textarea");
            page.waitForTimeout(1500);
            assertThat(page.isVisible("#ks-button")).isTrue();
        }
    }
}
```

注:`index.html` 未认证会被 302 到 login——所以 index 体检必须走 `adminContext()`。console error 若出现 favicon 404 之类噪声:确认为无害后在收集器过滤 `Failed to load resource.*favicon`(把过滤理由写注释);除此之外任何 error 都算失败。

- [ ] **Step 2: 跑** — `-Dtest='PageHealthBrowserIT'` → 11 PASS
- [ ] **Step 3: Commit** — `test: P1 全页面体检浏览器 IT(10 页健康断言 + features 正向门控)`

---

### Task 7: P1 主应用交互深化(`IndexInteractionsBrowserIT`)

**Files:**
- Test: `.../browser/IndexInteractionsBrowserIT.java`

- [ ] **Step 1: 写测试**

```java
package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("P1 主应用交互:会话 CRUD / 文件模态 / 工具弹窗 / 知识空间弹窗")
class IndexInteractionsBrowserIT extends BrowserTestBase {

    private Page openIndex(BrowserContext ctx) {
        Page page = newPage(ctx);
        page.navigate(baseUrl + UI + "index.html");
        page.waitForSelector("#textarea");
        return page;
    }

    @Test
    void conversationCrudViaSidebar() {
        try (BrowserContext ctx = adminContext()) {
            // API 建会话(POST /user-conversations),UI 验证列表/改名/删除
            String convBody = apiSend(ctx, "POST", UI + "user-conversations",
                    "{\"title\":\"e2e-conv\"}").text();
            Page page = openIndex(ctx);
            page.waitForSelector("#sidebarList .sidebar-item",
                    new Page.WaitForSelectorOptions().setTimeout(5000));
            int count = page.locator("#sidebarList .sidebar-item").count();
            assertThat(count).isGreaterThanOrEqualTo(1);

            // 激活第一项
            page.locator("#sidebarList .sidebar-item").first().click();
            page.waitForTimeout(500);
            assertThat(page.locator("#sidebarList .sidebar-item.active").count())
                    .isGreaterThanOrEqualTo(1);

            // 改名:hover 出 actions → 点 rename → 填 edit input → Enter
            var first = page.locator("#sidebarList .sidebar-item").first();
            first.hover();
            first.locator(".sidebar-item-rename").click();
            page.locator("#sidebarList .sidebar-item-edit").first().fill("e2e-renamed");
            page.keyboard().press("Enter");
            page.waitForTimeout(800);
            assertThat(page.locator("#sidebarList").innerHTML()).contains("e2e-renamed");

            // 删除 → 确认弹窗
            first.hover();
            first.locator(".sidebar-item-delete").click();
            page.waitForSelector("#confirm-modal-overlay",
                    new Page.WaitForSelectorOptions().setTimeout(3000));
            page.click("#confirm-modal-ok");
            page.waitForTimeout(800);
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    @Test
    void fileManagerModalOpensWithTree() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);
            page.click("#file-manager-button");
            page.waitForSelector("#file-modal-overlay",
                    new Page.WaitForSelectorOptions().setTimeout(5000));
            assertThat(page.isVisible("#file-list")).isTrue();
            page.waitForTimeout(1000);
            assertThat(consoleErrorsOf(page)).isEmpty();
            page.click("#file-close-btn");
        }
    }

    @Test
    void toolsModalOpensAndListsCapabilities() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);
            page.click("#mcp-button");
            page.waitForSelector("#mcp-modal-overlay",
                    new Page.WaitForSelectorOptions().setTimeout(5000));
            page.waitForTimeout(1000);
            assertThat(page.locator("#mcp-list").innerHTML()).isNotBlank();
            page.click("#mcp-close-btn");
        }
    }

    @Test
    void knowledgeSpaceModalOpens() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);
            page.click("#ks-button");
            page.waitForSelector("#ks-modal-overlay",
                    new Page.WaitForSelectorOptions().setTimeout(5000));
            assertThat(page.isVisible("#ks-sidebar")).isTrue();
            page.click("#ks-close-btn");
        }
    }

    @Test
    void skillsModalTabsSwitch() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);
            page.click("#skills-button");
            page.waitForSelector("#skills-modal-overlay",
                    new Page.WaitForSelectorOptions().setTimeout(5000));
            page.click("button.skill-tab[data-tab='market']");
            page.waitForTimeout(800);
            assertThat(consoleErrorsOf(page)).isEmpty();
            page.click("#skills-close-btn");
        }
    }
}
```

注:`POST /user-conversations` 请求体字段以 app.js L? 实际 fetch 为准(执行时 grep `user-conversations` 看 body 结构,常见为 `{"conversationId":...}` 或空体)——若体结构不符按实际改;改名/删除的 hover-actions 若需要 `.sidebar-item-actions` 先可见,补一次 `first.locator(".sidebar-item-actions").waitFor()`。

- [ ] **Step 2: 跑** — `-Dtest='IndexInteractionsBrowserIT'` → 5 PASS
- [ ] **Step 3: Commit** — `test: P1 主应用交互浏览器 IT(会话 CRUD/文件/工具/知识空间/技能弹窗)`

---

### Task 8: P2 角色管理深路径(`RolesAdminBrowserIT`)

**Files:**
- Test: `.../browser/RolesAdminBrowserIT.java`

- [ ] **Step 1: 写测试**

```java
package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("P2 roles.html:创建角色/动态工具授权/保存持久化/删除级联")
class RolesAdminBrowserIT extends BrowserTestBase {

    @Test
    void createGrantAndDeleteRoleViaUi() {
        String code = "e2erole" + System.currentTimeMillis() % 100000;
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "admin/roles.html");
            page.waitForSelector("#create-role-btn");

            // 创建
            page.click("#create-role-btn");
            page.waitForSelector("#create-role-modal");
            page.fill("#new-role-code", code);
            page.fill("#new-role-name", "E2E 角色");
            page.fill("#new-role-desc", "浏览器 IT 创建");
            page.click("#create-role-submit");
            page.waitForTimeout(1000);
            assertThat(page.locator("#role-table-container").innerHTML()).contains(code);

            // 打开详情 → 工具授权 section 动态加载(/admin/capabilities)
            page.getByText(code).first().click();
            page.waitForSelector("#role-detail-modal");
            page.waitForTimeout(1000);
            String toolsHtml = page.locator("#rd-tools").innerHTML();
            assertThat(toolsHtml).contains("tool_git"); // RBAC 工具动态出现

            // 勾选 tool_git 并保存(checkbox 选择器以实际 DOM 为准,先观察)
            page.locator("#rd-tools input[type='checkbox'][value='tool_git']").check();
            page.click("#rd-save");
            page.waitForTimeout(1000);

            // API 验证持久化
            String tools = apiGet(ctx, UI + "admin/roles/" + code + "/tools");
            assertThat(tools).contains("tool_git");

            // 删除角色(UI 或 API 均可;此处 API 保证清场)+ 验证消失
            assertThat(apiSend(ctx, "DELETE", UI + "admin/roles/" + code, null).status())
                    .isEqualTo(200);
            assertThat(apiGet(ctx, UI + "admin/roles")).doesNotContain("\"" + code + "\"");
        }
    }
}
```

注:`#rd-tools` 内 checkbox 的精确选择器(value 属性?data-group?)执行时先 `page.locator("#rd-tools").innerHTML()` 打印观察一次再定;角色行的点击进入详情的触发元素(行 click 还是"详情"按钮)同理以 roles.js 实际绑定为准。

- [ ] **Step 2: 跑** — `-Dtest='RolesAdminBrowserIT'` → 1 PASS
- [ ] **Step 3: Commit** — `test: P2 角色管理浏览器 IT(创建/动态授权/持久化/删除)`

---

### Task 9: P2 console + 其余 admin 冒烟(`ConsoleAdminBrowserIT`)

**Files:**
- Test: `.../browser/ConsoleAdminBrowserIT.java`

- [ ] **Step 1: 写测试**

```java
package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("P2 console.html 用户创建/角色分配(含 ADMIN strict-RBAC 提示)+ user/mcps/conversation 冒烟")
class ConsoleAdminBrowserIT extends BrowserTestBase {

    @Test
    void createUserAndAssignRoleViaUi() {
        String user = "conu" + System.currentTimeMillis() % 100000;
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "admin/console.html");
            page.waitForSelector("#create-user-btn");

            page.click("#create-user-btn");
            page.waitForSelector("#create-user-modal");
            page.fill("#new-username", user);
            page.fill("#new-nickname", "Console E2E");
            page.fill("#new-password", "conpass1");
            page.selectOption("#new-type", "USER");
            page.click("#create-submit-btn");
            page.waitForTimeout(1000);
            assertThat(page.locator("#user-table-container").innerHTML()).contains(user);

            // 分配角色弹窗:点用户行的分配入口(以 console.js 实际绑定为准)
            page.locator("#user-table-container tr", 
                    new Page.LocatorOptions()).filter(
                    new com.microsoft.playwright.Locator.FilterOptions().setHasText(user))
                    .getByText("角色").first().click();
            page.waitForSelector("#assign-role-modal");
            page.waitForTimeout(500);
            // 勾选 base 角色并保存
            page.locator("#assign-role-list input[type='checkbox']").first().check();
            page.click("#assign-role-save");
            page.waitForTimeout(1000);

            String roles = apiGet(ctx, UI + "admin/users/" + user + "/roles");
            assertThat(roles).isNotBlank();

            apiSend(ctx, "DELETE", UI + "admin/users/" + user, null);
        }
    }

    @Test
    void adminUserCanOpenAssignModalForAdminRow() {
        // 2026-09-08 起 ADMIN 用户同样可打开分配角色弹窗(strict RBAC 提示句)
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "admin/console.html");
            page.waitForSelector("#user-table-container");
            page.waitForTimeout(800);
            page.locator("#user-table-container tr").filter(
                    new com.microsoft.playwright.Locator.FilterOptions().setHasText(ADMIN_USER))
                    .getByText("角色").first().click();
            page.waitForSelector("#assign-role-modal");
            String modalText = page.locator("#assign-role-modal").innerText();
            assertThat(modalText).contains("ADMIN"); // strict RBAC 提示
            page.click("#assign-role-cancel");
        }
    }

    @Test
    void userMcpsConversationPagesSmoke() {
        try (BrowserContext ctx = adminContext()) {
            for (String p : new String[]{"admin/user.html", "admin/mcps.html", "admin/conversation.html"}) {
                Page page = newPage(ctx);
                page.navigate(baseUrl + UI + p);
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
                page.waitForTimeout(1200);
                assertThat(consoleErrorsOf(page)).as(p + " 无 console error").isEmpty();
                page.close();
            }
        }
    }
}
```

注:"角色"按钮文案以 console.js 实际渲染为准(可能是"分配角色"/图标按钮);断言 `contains("ADMIN")` 对应 strict-RBAC 提示句,若提示文案不同则改为断言弹窗成功打开 + 列出角色 checkbox。

- [ ] **Step 2: 跑** — `-Dtest='ConsoleAdminBrowserIT'` → 3 PASS
- [ ] **Step 3: Commit** — `test: P2 console 用户/角色分配浏览器 IT + admin 三页冒烟`

---

### Task 10: P2 技能市场审批流(`MarketSkillsAdminBrowserIT`)

**Files:**
- Test: `.../browser/MarketSkillsAdminBrowserIT.java`

- [ ] **Step 1: 写测试**

预置 PENDING 技能:用普通用户 context 走 `POST /spring/ai/loom/user/market-skills`(提交到市场 → PENDING)。请求体 record 执行时 grep `user/market-skills` 路由确认字段(常见 `{name, description, content, category}`)。

```java
package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("P2 market-skills.html:审批流 PENDING→APPROVED / PENDING→REJECTED(评论必填)")
class MarketSkillsAdminBrowserIT extends BrowserTestBase {

    private String provisionSubmitter() {
        String user = "mktu" + System.currentTimeMillis() % 100000;
        try (BrowserContext admin = adminContext()) {
            apiSend(admin, "POST", UI + "admin/users",
                    "{\"username\":\"" + user + "\",\"nickname\":\"MKT U\",\"password\":\"mktpass1\",\"type\":\"USER\"}");
        }
        return user;
    }

    private void submitSkill(String user, String skillName) {
        try (BrowserContext ctx = newContext()) {
            loginViaApi(ctx, user, "mktpass1");
            APIResponse resp = apiSend(ctx, "POST", UI + "user/market-skills",
                    "{\"name\":\"" + skillName + "\",\"description\":\"e2e 审批测试技能\","
                            + "\"content\":\"# " + skillName + "\\n测试内容\",\"category\":\"测试\"}");
            assertThat(resp.status()).as("submit skill " + skillName).isIn(200, 201);
        }
    }

    @Test
    void approvePendingSkillViaUi() {
        String user = provisionSubmitter();
        String skill = "E2E审批通过" + System.currentTimeMillis() % 10000;
        submitSkill(user, skill);
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "admin/market-skills.html");
            page.waitForSelector("#skill-table-container");
            page.waitForTimeout(1500);
            assertThat(page.locator("#skill-table-container").innerHTML()).contains(skill);
            assertThat(page.locator("span.approval-status-PENDING").count()).isGreaterThanOrEqualTo(1);

            // 找到该行的通过按钮并点击
            var row = page.locator("#skill-table-container tr").filter(
                    new com.microsoft.playwright.Locator.FilterOptions().setHasText(skill));
            row.locator("button.approve-btn").click();
            page.waitForTimeout(1500);
            // 若有确认弹窗则确认
            if (page.isVisible("#confirm-modal")) page.click("#confirm-ok");
            page.waitForTimeout(1000);

            var rowAfter = page.locator("#skill-table-container tr").filter(
                    new com.microsoft.playwright.Locator.FilterOptions().setHasText(skill));
            assertThat(rowAfter.locator("span.approval-status-APPROVED").count()).isEqualTo(1);
            apiSend(ctx, "DELETE", UI + "admin/users/" + user, null);
        }
    }

    @Test
    void rejectRequiresComment() {
        String user = provisionSubmitter();
        String skill = "E2E审批拒绝" + System.currentTimeMillis() % 10000;
        submitSkill(user, skill);
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "admin/market-skills.html");
            page.waitForSelector("#skill-table-container");
            page.waitForTimeout(1500);

            var row = page.locator("#skill-table-container tr").filter(
                    new com.microsoft.playwright.Locator.FilterOptions().setHasText(skill));
            row.locator("button.reject-btn").click();
            page.waitForTimeout(800);
            // 拒绝评论必填:直接确认应被拦截(错误提示或弹窗不关)
            // 具体交互(rompt/内嵌 textarea/#review-modal)以 market-admin.js 实际实现为准,
            // 执行时观察 DOM 后写精确断言:空评论 → 提示出现且状态仍 PENDING
            assertThat(page.locator("#skill-table-container").innerHTML())
                    .contains("approval-status-PENDING");

            apiSend(ctx, "DELETE", UI + "admin/users/" + user, null);
        }
    }
}
```

注:拒绝评论 UI 形态(window.prompt?内嵌输入?)执行时读 `market-admin.js` reject 分支后把 Step 1 中"观察后写精确断言"落实为具体代码(dialog 用 `page.onDialog`)。**不允许留观察注释交付**——最终 commit 的代码必须是精确断言。

- [ ] **Step 2: 跑** — `-Dtest='MarketSkillsAdminBrowserIT'` → 2 PASS
- [ ] **Step 3: Commit** — `test: P2 技能市场审批流浏览器 IT(通过/拒绝评论必填)`

---

### Task 11: P2 知识市场 + stats(`KnowledgeMarketAndStatsBrowserIT`)

**Files:**
- Test: `.../browser/KnowledgeMarketAndStatsBrowserIT.java`

- [ ] **Step 1: 写测试**

```java
package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("P2 knowledge-market.html(tag UI)+ stats.html(token 用量 + ask-logs 区块)")
class KnowledgeMarketAndStatsBrowserIT extends BrowserTestBase {

    @Test
    void knowledgeMarketPageRendersTagUi() {
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "admin/knowledge-market.html");
            page.waitForSelector("#knowledge-table-container");
            page.waitForTimeout(1500);
            assertThat(consoleErrorsOf(page)).isEmpty();
            // tag 编辑弹窗可开合(有数据行才点;种子库可能为空 → 条件执行)
            if (page.locator("button.tag-edit-row-btn").count() > 0) {
                page.locator("button.tag-edit-row-btn").first().click();
                page.waitForSelector("#tag-edit-modal");
                assertThat(page.isVisible("#tag-edit-input")).isTrue();
                // 关闭(取消按钮 id 以实际 DOM 为准)
            }
        }
    }

    @Test
    void statsPageRendersBothSections() {
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "admin/stats.html");
            page.waitForSelector("#bar-chart");
            page.waitForTimeout(1500);
            assertThat(page.isVisible("#stats-table")).isTrue();
            assertThat(page.isVisible("#ask-logs-table")).isTrue();
            // 刷新按钮无 console error
            page.click("#ask-logs-refresh");
            page.waitForTimeout(1500);
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }
}
```

- [ ] **Step 2: 跑** — `-Dtest='KnowledgeMarketAndStatsBrowserIT'` → 2 PASS
- [ ] **Step 3: Commit** — `test: P2 知识市场 tag UI + stats 双区块浏览器 IT`

---

### Task 12: L2 计算样式断言(`StyleTokenBrowserIT`)

**Files:**
- Test: `.../browser/StyleTokenBrowserIT.java`

- [ ] **Step 1: 写测试**

```java
package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("L2 样式回归:设计 token / 顶栏 60px / 对比度 AA / 登录页布局契约 / 双页 token 一致")
class StyleTokenBrowserIT extends BrowserTestBase {

    private static String cssVar(Page page, String name) {
        return (String) page.evaluate(
                "getComputedStyle(document.documentElement).getPropertyValue('" + name + "').trim()");
    }

    /** sRGB 相对亮度(WCAG 2.x) */
    private static double luminance(int r, int g, int b) {
        double[] c = {r / 255.0, g / 255.0, b / 255.0};
        for (int i = 0; i < 3; i++) c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static double contrastRatio(String fgRgb, String bgRgb) {
        int[] fg = parseRgb(fgRgb), bg = parseRgb(bgRgb);
        double l1 = luminance(fg[0], fg[1], fg[2]), l2 = luminance(bg[0], bg[1], bg[2]);
        double hi = Math.max(l1, l2), lo = Math.min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static int[] parseRgb(String s) { // "rgb(30, 41, 59)" 或 "#1e293b"
        String v = s.trim();
        if (v.startsWith("#")) {
            return new int[]{Integer.parseInt(v.substring(1, 3), 16),
                    Integer.parseInt(v.substring(3, 5), 16), Integer.parseInt(v.substring(5, 7), 16)};
        }
        String[] p = v.replaceAll("[^0-9,]", "").split(",");
        return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())};
    }

    @Test
    void designTokensOnIndex() {
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "index.html");
            page.waitForSelector("#textarea");
            assertThat(cssVar(page, "--primary-color")).isEqualTo("#6366f1");
            assertThat(cssVar(page, "--bg-secondary")).isEqualTo("#f8fafc");
            assertThat(cssVar(page, "--header-height")).isEqualTo("60px");
            assertThat(cssVar(page, "--text-primary")).isEqualTo("#1e293b");
            assertThat(cssVar(page, "--error-color")).isEqualTo("#ef4444");
        }
    }

    @Test
    void headerIs60pxAndBodyTextContrastPassesAA() {
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "index.html");
            page.waitForSelector("#textarea");
            Double h = (Double) page.evaluate(
                    "document.querySelector('header.header')?.getBoundingClientRect().height ?? -1");
            assertThat(h).as("顶栏 60px(±1)").isBetween(59.0, 61.0);

            String color = (String) page.evaluate("getComputedStyle(document.body).color");
            String bg = (String) page.evaluate(
                    "getComputedStyle(document.body).backgroundColor");
            if (bg.equals("rgba(0, 0, 0, 0)") || bg.equals("transparent")) bg = "#ffffff";
            assertThat(contrastRatio(color, bg)).as("body 文本对比度 ≥ 4.5").isGreaterThanOrEqualTo(4.5);
        }
    }

    @Test
    void loginPageLayoutContract() {
        try (BrowserContext ctx = newContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "login.html");
            page.waitForSelector("#login-form");
            // A+B 组合:顶栏白底 + 品牌卡(“灵梭”文案)+ 表单齐备
            assertThat(page.getByText("灵梭").count()).isGreaterThanOrEqualTo(1);
            assertThat(page.isVisible("#username")).isTrue();
            assertThat(page.isVisible("#password")).isTrue();
            assertThat(page.isVisible("#submit-btn")).isTrue();
            String headerBg = (String) page.evaluate(
                    "(() => { const h = document.querySelector('header, .header, .topbar');"
                            + " return h ? getComputedStyle(h).backgroundColor : 'none'; })()");
            if (!headerBg.equals("none")) {
                int[] rgb = parseRgb(headerBg);
                assertThat(luminance(rgb[0], rgb[1], rgb[2])).as("登录页顶栏近白").isGreaterThan(0.9);
            }
        }
    }

    @Test
    void primaryTokenConsistentBetweenLoginAndIndex() {
        try (BrowserContext ctxAnon = newContext(); BrowserContext ctxAdmin = adminContext()) {
            Page login = newPage(ctxAnon);
            login.navigate(baseUrl + UI + "login.html");
            login.waitForSelector("#login-form");
            Page index = newPage(ctxAdmin);
            index.navigate(baseUrl + UI + "index.html");
            index.waitForSelector("#textarea");
            assertThat(cssVar(login, "--primary-color")).isEqualTo(cssVar(index, "--primary-color"));
        }
    }
}
```

注:login.html 顶栏选择器执行时读 login.html 确认(可能是 `.header` 复用主应用样式);若 `灵梭` 文案在 login 页不存在则改断言品牌卡容器 class(以 login.html 实际结构为准,交付代码必须精确)。

- [ ] **Step 2: 跑** — `-Dtest='StyleTokenBrowserIT'` → 4 PASS
- [ ] **Step 3: Commit** — `test: L2 计算样式断言 IT(token/顶栏/对比度/登录布局/双页一致)`

---

### Task 13: L2 截图基线(`VisualBaselineBrowserIT` + `PngDiff` 工具)

**Files:**
- Create: `.../browser/PngDiff.java`
- Test: `.../browser/VisualBaselineBrowserIT.java`
- Create(首跑生成): `spring-ai-loom-agent-test/src/test/resources/browser-baselines/*.png`

- [ ] **Step 1: 写 PngDiff**

```java
package cn.wubo.spring.ai.loom.agent.browser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/** 纯 Java 逐像素 diff:RGB 欧氏距离 > perPixelThreshold 记为差异像素;
 *  差异比例 > ratioThreshold 判 FAIL;同时输出 diff 热力图。 */
public final class PngDiff {

    public record Result(boolean pass, double diffRatio, int width, int height) {}

    public static Result compare(File expected, File actual, File diffOut,
                                 double perPixelThreshold, double ratioThreshold) throws IOException {
        BufferedImage e = ImageIO.read(expected), a = ImageIO.read(actual);
        int w = Math.min(e.getWidth(), a.getWidth()), h = Math.min(e.getHeight(), a.getHeight());
        boolean sizeMismatch = e.getWidth() != a.getWidth() || e.getHeight() != a.getHeight();
        BufferedImage diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        long changed = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int ep = e.getRGB(x, y), ap = a.getRGB(x, y);
                int dr = ((ep >> 16 & 0xff) - (ap >> 16 & 0xff));
                int dg = ((ep >> 8 & 0xff) - (ap >> 8 & 0xff));
                int db = ((ep & 0xff) - (ap & 0xff));
                double dist = Math.sqrt(dr * dr + dg * dg + db * db) / 441.67;
                if (dist > perPixelThreshold) {
                    changed++;
                    diff.setRGB(x, y, 0xff0000);
                } else {
                    diff.setRGB(x, y, (ap & 0xfefefe) >> 1); // 变暗的实际图
                }
            }
        }
        double ratio = sizeMismatch ? 1.0 : (double) changed / (w * h);
        if (ratio > ratioThreshold && diffOut != null) {
            diffOut.getParentFile().mkdirs();
            ImageIO.write(diff, "png", diffOut);
        }
        return new Result(ratio <= ratioThreshold, ratio, w, h);
    }
}
```

- [ ] **Step 2: 写 VisualBaselineBrowserIT**

```java
package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ScreenshotType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("L2 截图基线:10 页固定 viewport 全页截图 vs browser-baselines(-DupdateBaselines=true 重建)")
class VisualBaselineBrowserIT extends BrowserTestBase {

    private static final double PIXEL_THRESHOLD = 0.1;   // 单像素 RGB 距离容忍(抗锯齿)
    private static final double RATIO_THRESHOLD = 0.005; // 0.5% 差异像素比例

    private final Path baselineDir = Path.of("src/test/resources/browser-baselines");
    private final Path actualDir = Path.of("target/visual-actual");
    private final Path diffDir = Path.of("target/visual-diffs");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "index.html", "login.html",
            "admin-console.html", "admin-stats.html", "admin-user.html",
            "admin-roles.html", "admin-mcps.html", "admin-conversation.html",
            "admin-market-skills.html", "admin-knowledge-market.html"
    })
    void pageMatchesBaseline(String name) throws Exception {
        boolean isLogin = "login.html".equals(name);
        String urlPath = isLogin ? "login.html"
                : name.startsWith("admin-") ? "admin/" + name.substring(6)
                : name;

        try (BrowserContext ctx = isLogin ? newContext() : adminContext()) {
            Page page = newPage(ctx);
            page.emulateMedia(new Page.EmulateMediaOptions()
                    .setReducedMotion(com.microsoft.playwright.options.ReducedMotion.REDUCE));
            page.navigate(baseUrl + UI + urlPath);
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
            page.waitForTimeout(1500); // 停稳异步渲染/字体

            actualDir.toFile().mkdirs();
            File actual = actualDir.resolve(name.replace(".html", "") + ".png").toFile();
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(actual.toPath()).setFullPage(true).setType(ScreenshotType.PNG));

            File baseline = baselineDir.resolve(name.replace(".html", "") + ".png").toFile();
            boolean update = Boolean.getBoolean("updateBaselines");
            if (update || !baseline.exists()) {
                baselineDir.toFile().mkdirs();
                java.nio.file.Files.copy(actual.toPath(), baseline.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[VisualBaseline] 基线已写入: " + baseline);
                return; // 首建/update 模式不判 diff
            }
            PngDiff.Result r = PngDiff.compare(baseline, actual,
                    diffDir.resolve(name.replace(".html", "") + "-diff.png").toFile(),
                    PIXEL_THRESHOLD, RATIO_THRESHOLD);
            assertThat(r.pass())
                    .as("%s 视觉回归 diff=%.4f%% (阈值 %.2f%%),diff 图见 %s",
                            name, r.diffRatio() * 100, RATIO_THRESHOLD * 100, diffDir)
                    .isTrue();
        }
    }
}
```

同时把 `BrowserTestBase.newContext()` 的 viewport 固定为 **1280×800、deviceScaleFactor=1**(截图确定性;其余功能 IT 不受影响)——修改 Task 2 代码:

```java
protected BrowserContext newContext() {
    return browser.newContext(new Browser.NewContextOptions()
            .setViewportSize(1280, 800)
            .setDeviceScaleFactor(1)
            .setReducedMotion(com.microsoft.playwright.options.ReducedMotion.REDUCE));
}
```

- [ ] **Step 3: 首跑建基线**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest='VisualBaselineBrowserIT' -Dsurefire.failIfNoSpecifiedTests=false -DupdateBaselines=true
```
Expected: 10 页基线 PNG 写入 `src/test/resources/browser-baselines/`。人工抽查 2-3 张确认非空白/非错页。

- [ ] **Step 4: 复跑判 diff**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest='VisualBaselineBrowserIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 10 PASS(diff≈0)。若个别页因时间戳/随机数据抖动超阈值:把该页的动态区块在截图前用 `page.evaluate` 覆写为固定文本(如 stats 月份标签),或对该页降低 fullPage 为 clip 截图——不允许直接调大全局阈值。

- [ ] **Step 5: Commit(含基线 PNG)** — `test: L2 截图基线视觉回归 IT(10 页 + PngDiff 0.5% 阈值 + 基线集)`

---

### Task 14: L1 补充单测(`I18nKeysParityTest` + `MarkdownSanitizeBrowserIT`)

**Files:**
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/i18n/I18nKeysParityTest.java`
- Test: `.../browser/MarkdownSanitizeBrowserIT.java`

- [ ] **Step 1: 写 I18nKeysParityTest(纯单测,无浏览器)**

```java
package cn.wubo.spring.ai.loom.agent.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("i18n:zh-CN / en-US key 集合一致 + 无空值")
class I18nKeysParityTest {

    private static final String BASE = "/META-INF/resources/spring/ai/loom/i18n/";

    private Set<String> flatKeys(String file) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(BASE + file)) {
            assertThat(in).as(BASE + file + " 在 classpath").isNotNull();
            JsonNode root = new ObjectMapper().readTree(in);
            Set<String> keys = new HashSet<>();
            collect("", root, keys);
            return keys;
        }
    }

    private void collect(String prefix, JsonNode node, Set<String> out) {
        node.fields().forEachRemaining(e -> {
            String k = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue().isObject()) collect(k, e.getValue(), out);
            else out.add(k);
        });
    }

    @Test
    void keysMatchAndNoBlankValues() throws Exception {
        Set<String> zh = flatKeys("zh-CN.json");
        Set<String> en = flatKeys("en-US.json");
        assertThat(zh).isEqualTo(en);
        assertThat(zh).contains("market.admin.status.pending", "market.load.more");
        // 无空值
        try (InputStream in = getClass().getResourceAsStream(BASE + "zh-CN.json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            assertNoBlanks(root);
        }
    }

    private void assertNoBlanks(JsonNode node) {
        node.fields().forEachRemaining(e -> {
            if (e.getValue().isObject()) assertNoBlanks(e.getValue());
            else assertThat(e.getValue().asText()).as(e.getKey()).isNotBlank();
        });
    }
}
```

注:`_meta` 是合法 key,zh/en 均有,不影响对齐断言。

- [ ] **Step 2: 写 MarkdownSanitizeBrowserIT(page.evaluate 直调 `window.sanitizeHtml`)**

```java
package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
```

- [ ] **Step 3: 跑两个类**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest='I18nKeysParityTest,MarkdownSanitizeBrowserIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 2 PASS。

- [ ] **Step 4: Commit** — `test: i18n key 对齐单测 + sanitizeHtml XSS 剥除浏览器直调 IT`

---

### Task 15: LLM 烟雾(`ChatSmokeBrowserIT`,env 守卫)

**Files:**
- Test: `.../browser/ChatSmokeBrowserIT.java`

- [ ] **Step 1: 写测试**

```java
package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("LLM 烟雾:真实 SSE 聊天流渲染 + 工具调用不崩(无 API key 则跳过)")
class ChatSmokeBrowserIT extends BrowserTestBase {

    @BeforeAll
    void requireApiKey() {
        assumeTrue(System.getenv("DASHSCOPE_PERSON_TOKEN_API_KEY") != null
                        && !System.getenv("DASHSCOPE_PERSON_TOKEN_API_KEY").isBlank(),
                "跳过:DASHSCOPE_PERSON_TOKEN_API_KEY 未设置");
    }

    @Test
    void chatStreamRendersAssistantBubble() {
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "index.html");
            page.waitForSelector("#textarea");
            page.fill("#textarea", "现在几点了?请调用时间工具回答,一句话即可。");
            page.click("#send-btn");

            // 等 AI 气泡出现且文本稳定(最长 120s,每 2s 检查一次连续两次不变)
            page.waitForSelector(".chat-item-left .bubble",
                    new Page.WaitForSelectorOptions().setTimeout(120_000));
            String prev = "";
            String cur = "";
            int stable = 0;
            for (int i = 0; i < 60 && stable < 3; i++) {
                cur = page.locator(".chat-item-left .bubble").last().innerText();
                stable = cur.equals(prev) && !cur.isBlank() ? stable + 1 : 0;
                prev = cur;
                page.waitForTimeout(2000);
            }
            assertThat(cur).as("AI 回复非空").isNotBlank();
            assertThat(page.locator(".chat-item-right .bubble").count())
                    .as("用户消息气泡存在").isGreaterThanOrEqualTo(1);
            // 渲染后的 HTML 不含原始 script(sanitize 生效)
            assertThat(page.locator(".chat-item-left .bubble").last().innerHTML())
                    .doesNotContain("<script");
        }
    }
}
```

- [ ] **Step 2: 跑(需本机有 key)** — `-Dtest='ChatSmokeBrowserIT'`;无 key → Skipped(合法)
- [ ] **Step 3: Commit** — `test: LLM 烟雾浏览器 IT(SSE 流渲染 + 工具链路,env 守卫可跳过)`

---

### Task 16: L4 一次性评估(AI 视觉审查 + Lighthouse)

**Files:**
- Create: `docs/test-reports/2026-09-11-comprehensive-test-report.md`(本任务先建骨架,Task 17 汇总)

**非代码任务,由主会话用 chrome-devtools MCP 执行:**

- [ ] **Step 1: 起应用**

```bash
mvn spring-boot:run -pl spring-ai-loom-agent-test
```
(后台运行;端口 8080;需 `DASHSCOPE_PERSON_TOKEN_API_KEY` 否则启动可能失败——无 key 时加 `-Dspring-boot.run.arguments=--spring.ai.dashscope.api-key=dummy` 仅测静态页)

- [ ] **Step 2: AI 视觉审查(chrome-devtools MCP)**
  - `new_page` 逐页访问 10 页(登录用 `wb04307201/123456` 通过表单登录)
  - `resize_page` 两断点:1440×900 与 390×844,各 `take_screenshot`
  - 评审清单(逐页记录):对齐/间距一致性/配色协调/圆角阴影统一/响应式破损/文本截断溢出/焦点态可见
  - 移动断点重点:index 侧边栏折叠、admin 表格横向滚动策略、login 品牌卡缩放

- [ ] **Step 3: Lighthouse 审计**
  - `lighthouse_audit`(device=desktop)跑 4 代表页:index / login / admin console / market-skills
  - `lighthouse_audit`(device=mobile)跑 index / login
  - 记录:a11y / best-practices / performance 分数 + 每页 top-3 issues;critical a11y 项标红

- [ ] **Step 4: 写报告骨架** `docs/test-reports/2026-09-11-comprehensive-test-report.md`:
  视觉问题清单(页面 × 严重度 P0/P1/P2 × 截图引用)、Lighthouse 分数表、top issues、建议修复项(不在本轮修)

- [ ] **Step 5: Commit** — `docs: L4 AI 视觉审查 + Lighthouse 审计报告`

---

### Task 17: 收尾(全量回归 + CLAUDE.md + 汇总报告)

**Files:**
- Modify: `CLAUDE.md`(Key Commands 增补浏览器测试运行说明)
- Modify: `docs/test-reports/2026-09-11-comprehensive-test-report.md`(汇总层)

- [ ] **Step 1: 全量回归**

```bash
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/e2e-files spring-ai-loom-agent-test/target/surefire-reports
mvn test -pl spring-ai-loom-agent-test
mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 单元 380+新增(i18n parity)= 全绿;IT 80 + 新增 ~35 = 全绿(env-gated skip 除外)。

- [ ] **Step 2: CLAUDE.md 增补**(Key Commands 段后加):

```markdown
# 浏览器 E2E / 样式回归(Java Playwright,Chromium 缺失自动 skip;首跑需下载 Chromium)
mvn test -pl spring-ai-loom-agent-test -Dtest='*BrowserIT' -Dsurefire.failIfNoSpecifiedTests=false
# 重建截图基线(UI 有意改版后)
mvn test -pl spring-ai-loom-agent-test -Dtest='VisualBaselineBrowserIT' -DupdateBaselines=true
```

并在 Data Layer 或 Frontend 段注明:浏览器 IT 用 `./target/e2e-files` 隔离文件目录、`./target/test-ds` 数据库、截图基线在 `src/test/resources/browser-baselines/`(同机更新约定)。

- [ ] **Step 3: 汇总报告**:在报告顶部加执行摘要(各层用例数/通过率/发现的问题分级清单/findings 链接)

- [ ] **Step 4: Commit** — `docs: 全面测试收尾(CLAUDE.md 浏览器测试说明 + 汇总报告)`

- [ ] **Step 5: 汇报**:向用户呈报四层结果 + findings 清单;产品 bug 走 systematic-debugging 单独立项

---

## Self-Review 记录

- **Spec 覆盖**:L1 基线(T1)+ i18n/sanitize(T14)✓;L3 P0(T3/4/5)P1(T6/7)P2(T8/9/10/11)LLM 烟雾(T15)✓;L2 token(T12)截图基线(T13)✓;L4(T16)✓;执行顺序/交付物(T17)✓。spec §7 提到的"markdown sanitize 归 L3 但性质单测"已按此落 T14。
- **事实修正**(相对 spec):admin 越权是 **302 → index.html** 非 403(spec §4.2 P0-1 表格按调研修正);页面数为 **10 HTML**(spec 写 13 含 js/css)。断言以本计划为准。
- **类型一致性**:`BrowserTestBase` 方法签名(newContext/newPage/loginViaApi/adminContext/apiGet/apiSend/consoleErrorsOf)在 T3-T15 使用处一致;`PngDiff.compare(File,File,File,double,double)→Result(pass,diffRatio,width,height)` T13 内一致;`LoginPage/IndexPage` 方法名 T2 定义 = T3/T15 使用。
- **已知执行时校准点**(已在任务内注明,交付代码必须精确、不许留观察注释):`#rd-tools` checkbox 选择器(T8)、console"角色"入口文案(T9)、reject 评论 UI 形态(T10)、`POST /user-conversations` body(T7)、login 顶栏选择器(T12)。
