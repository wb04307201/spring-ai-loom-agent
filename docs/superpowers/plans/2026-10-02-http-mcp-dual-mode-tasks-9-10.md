# HTTP 调用能力双模接入 实现计划（Task 9–10）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打通内部工具模式到真实上游的确定性链路 —— 用 WireMock 证明 `invokeEndpoint` 真的会发请求、白名单 fail-closed 真的会拦住、以及凭据不会经 REST 读接口外泄。

**Architecture:** 在 `spring-ai-loom-agent-test` 引入 WireMock 作为假上游。`HttpToolInvokeIT` 绕开 LLM，直接调 `DefaultHttpTool.invokeEndpoint(...)` 并手工构造 `ToolContext`，以请求计数断言区分"发得出去"与"被拦住"。REST 侧用 `MockHttpServletRequest` 直调 router 函数（沿用仓库既有 `FeaturesRouterTest` 模式），避开 MockMvc + 会话搭建的脆弱性。

**Tech Stack:** JUnit 5 + AssertJ + WireMock 3.5.4（`wiremock` + `wiremock-jetty12`）/ Spring `MockHttpServletRequest` / Mockito

**Spec:** `docs/superpowers/specs/2026-10-02-http-mcp-dual-mode-design.md`

**前置：** Task 1–8 已完成并提交。Task 8 已产出 `HttpToolDualModeContractTest` / `HttpPerUserIsolationTest` / `HttpToolGroupMetadataTest`，本文件**不重复**它们。

## Global Constraints

沿用总纲 `2026-10-02-http-mcp-dual-mode.md` 的全部约束，以下三条在本阶段尤其关键：

- **`HttpConfig.failClosed` 必须显式设置**：内部工具模式恒 `true`（`DefaultHttpTool.engineFor` / `HttpRouterSupport.engineFor` 已设）。测试里手工 new `HttpConfig()` 时默认 `false`，会走 jar 的 fail-open 路径 —— 断言 fail-closed 前先确认这个开关。
- **测试打的是 `@TempDir`**，不是 `~/.loom`。每个用例自带 `@TempDir Path tmp`，`mvn clean` 即清。
- **不打 LLM**：任何"模型会不会调这个工具"的假设都不写进测试。工具调用链用方法调用覆盖。

## 已核实的既有 API（照抄，不要重新推断）

- `cn.wubo.spring.ai.loom.agent.user.UserContextHolder` —— **已有** `getCurrentUser()` / `setCurrentUser(String)` / `clear()`。不要重复添加。
- `cn.wubo.spring.ai.loom.agent.rbac.IRoleService#getVisibleToolsForUser(String)` 返回 **`List<String>`**（不是 `Set`）。
- `LoomAgentProperties` 类级 `@Data`（Lombok），`getUsersBasePath()` / `getHttp()` 自动生成。
- `DomainWhitelist` 无端口的纯主机 pattern **端口无关** —— pattern `"localhost"` 能匹配 WireMock 的任意动态端口，不必把端口写进白名单。
- `InvokeService` 对**未注册的端点不报错**（`foundEndpoint` 只是 `Optional`，缺席时跳过 schema 校验），故测试里只注册 system、不注册 endpoint 也能打到上游。
- **`addProfile` 会跑 `ProfileValidator`（Task 4 Step 3）** —— 手工 new `Profile` 后必须设 `auth.type`，且类型必配对应字段（`bearer`→`token`、`basic`→`username`+`password`、`apiKey-*`→`keyName`+`value`、`none`→无）。`auth` 整块留 `null` 也可以（= 无认证系统）。

## Review Focus

1. **白名单为空但 system 已注册** —— 必须拒绝且 WireMock 请求数为 0（不能是"发出去了再过滤响应"）。
2. **profile 里带 token 时的出站请求头** —— `Authorization: Bearer <token>` 确实带上，且 token 不出现在返回给调用方的字符串里。
3. **两个用户各自注册同名 system** —— 各自只看得到自己的（Task 8 `HttpPerUserIsolationTest` 已覆盖，本阶段只补 REST 面）。

---

### Task 9: WireMock 接入 + HttpToolInvokeIT（工具 → 上游全链路）

**Files:**
- Modify: `spring-ai-loom-agent-test/pom.xml`（加 WireMock 依赖）
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/http/HttpToolInvokeIT.java`

**Interfaces:**
- Consumes: Task 6 `DefaultHttpTool`、Task 4 `HttpEngine`、Task 5 `LoomPaths.userHttpDir`
- Produces: 无新生产代码。本 Task 只加测试依赖与测试。

- [ ] **Step 1: 加 WireMock 依赖**

编辑 `spring-ai-loom-agent-test/pom.xml`，在 `</dependencies>` 之前加。**排除项逐字照抄 http-mcp 的 pom** —— 它是被实测过的组合，去掉排除会在 Boot 4 / Jetty 12 上炸：

```xml
        <!-- HTTP 工具链确定性测试用的假上游。排除项照搬 http-mcp pom(实测组合),
             去掉会在 Boot 4 / Jetty 12 上撞类冲突。 -->
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock</artifactId>
            <version>3.5.4</version>
            <scope>test</scope>
            <exclusions>
                <exclusion>
                    <groupId>org.eclipse.jetty</groupId>
                    <artifactId>jetty-servlet</artifactId>
                </exclusion>
                <exclusion>
                    <groupId>org.eclipse.jetty</groupId>
                    <artifactId>jetty-servlets</artifactId>
                </exclusion>
                <exclusion>
                    <groupId>org.eclipse.jetty</groupId>
                    <artifactId>jetty-webapp</artifactId>
                </exclusion>
                <exclusion>
                    <groupId>org.eclipse.jetty.ee10</groupId>
                    <artifactId>*</artifactId>
                </exclusion>
                <exclusion>
                    <groupId>org.eclipse.jetty.http2</groupId>
                    <artifactId>http2-server</artifactId>
                </exclusion>
                <exclusion>
                    <groupId>org.eclipse.jetty.http2</groupId>
                    <artifactId>http2-common</artifactId>
                </exclusion>
                <exclusion>
                    <groupId>org.eclipse.jetty.http2</groupId>
                    <artifactId>http2-hpack</artifactId>
                </exclusion>
                <exclusion>
                    <groupId>org.eclipse.jetty</groupId>
                    <artifactId>jetty-proxy</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-jetty12</artifactId>
            <version>3.5.4</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: 写 HttpToolInvokeIT**

创建 `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/http/HttpToolInvokeIT.java`：

```java
package cn.wubo.spring.ai.loom.agent.tool.http;

import cn.wubo.loom.file.core.LoomPaths;
import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.profile.Profile;
import cn.wubo.loom.http.core.system.System;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具 → 上游全链路的确定性覆盖（spec §6.2）。
 *
 * <p><b>为什么不打 LLM</b>：模型可能不调用工具、或调用方式与预期不同，那会让
 * 本该确定的安全断言变成 flaky。故直接调工具方法 + 手工构造 ToolContext。
 *
 * <p><b>fail-closed 的真正证明</b>在 {@link #blankWhitelistBlocksBeforeSendingRequest()}：
 * Task 6 的同名用例在 system 未注册时就返回了，根本没走到白名单判定。
 */
@DisplayName("HTTP 工具 → 上游全链路（WireMock 确定性，不经 LLM）")
class HttpToolInvokeIT {

    private static final String SECRET = "sk-live-DO-NOT-LEAK-12345";
    private static final List<String> ALLOWED = List.of("localhost", "127.0.0.1");

    private WireMockServer wm;
    private String usersBase;

    @BeforeEach
    void startWireMock() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        wm.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"pong\":true,\"id\":\"abc-123\"}")));
    }

    @AfterEach
    void stopWireMock() {
        if (wm != null) wm.stop();
    }

    private void initBase(Path tmp) {
        usersBase = tmp.toString();
    }

    private static ToolContext ctx(String username) {
        return new ToolContext(Map.of("username", username));
    }

    private static LoomAgentProperties.HttpProperty props(List<String> allowedDomains) {
        LoomAgentProperties.HttpProperty p = new LoomAgentProperties.HttpProperty();
        p.setAllowedDomains(allowedDomains);
        return p;
    }

    /** 与 DefaultHttpTool.engineFor 逐行同构 —— 白名单与 fail-closed 开关必须一致。 */
    private HttpEngine engineFor(String user, List<String> allowedDomains) {
        HttpConfig cfg = new HttpConfig();
        cfg.setFailClosed(true);
        cfg.setAllowedDomains(new LinkedHashSet<>(allowedDomains));
        return new HttpEngine(LoomPaths.userHttpDir(usersBase, user), cfg);
    }

    private static System buildSystem(String name, String baseUrl, String authProfile) {
        System s = new System();
        s.setName(name);
        s.setBaseUrl(baseUrl);
        s.setAuthProfile(authProfile);
        return s;
    }

    @Test
    @DisplayName("invokeEndpoint 真的打到上游并拿到响应")
    void reachesUpstream(@TempDir Path tmp) {
        initBase(tmp);
        engineFor("alice", ALLOWED).registerSystem(buildSystem("svc", wm.baseUrl(), null));
        DefaultHttpTool tool = new DefaultHttpTool(usersBase, props(ALLOWED));

        String out = tool.invokeEndpoint("svc", "GET", "/ping",
                null, null, null, null, null, null, null, ctx("alice"));

        wm.verify(1, getRequestedFor(urlEqualTo("/ping")));
        assertThat(out).contains("\"statusCode\":200").contains("pong");
    }

    @Test
    @DisplayName("profile 的 token 随请求头发往上游，且不回显给调用方")
    void authHeaderReachesUpstream(@TempDir Path tmp) {
        initBase(tmp);
        HttpEngine engine = engineFor("alice", ALLOWED);
        Profile p = new Profile();
        p.setName("prod");
        p.getAuth().setType("bearer");
        p.getAuth().setToken(SECRET);
        engine.addProfile(p);
        engine.registerSystem(buildSystem("authed", wm.baseUrl(), "prod"));

        DefaultHttpTool tool = new DefaultHttpTool(usersBase, props(ALLOWED));
        String out = tool.invokeEndpoint("authed", "GET", "/ping",
                null, null, null, null, null, null, null, ctx("alice"));

        wm.verify(1, getRequestedFor(urlEqualTo("/ping"))
                .withHeader("Authorization", equalTo("Bearer " + SECRET)));
        assertThat(out).contains("\"statusCode\":200");
        assertThat(out).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("fail-closed：白名单拒绝时 WireMock 请求数为 0")
    void blankWhitelistBlocksBeforeSendingRequest(@TempDir Path tmp) {
        initBase(tmp);
        // 先用有白名单的引擎把 system 注册进 alice 的目录（注册不需要联网）
        engineFor("alice", ALLOWED).registerSystem(buildSystem("svc", wm.baseUrl(), null));

        // 再用一个"空白名单"的工具去打 —— fail-closed 应在发请求前拦住
        DefaultHttpTool closedTool = new DefaultHttpTool(usersBase, props(List.of()));
        String out = closedTool.invokeEndpoint("svc", "GET", "/ping",
                null, null, null, null, null, null, null, ctx("alice"));

        wm.verify(0, getRequestedFor(urlEqualTo("/ping")));
        assertThat(out).contains("DomainNotAllowed");
    }

    @Test
    @DisplayName("白名单不含上游 host 时同样拒绝，且零请求")
    void hostNotInWhitelistIsRejected(@TempDir Path tmp) {
        initBase(tmp);
        engineFor("alice", List.of("example.com")).registerSystem(buildSystem("svc", wm.baseUrl(), null));
        DefaultHttpTool tool = new DefaultHttpTool(usersBase, props(List.of("example.com")));

        String out = tool.invokeEndpoint("svc", "GET", "/ping",
                null, null, null, null, null, null, null, ctx("alice"));

        wm.verify(0, getRequestedFor(urlEqualTo("/ping")));
        assertThat(out).contains("DomainNotAllowed");
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=HttpToolInvokeIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 至少 `reachesUpstream` 与 `authHeaderReachesUpstream` ERROR/FAIL。若报 `NoClassDefFoundError: WireMockServer`，说明 Step 1 的依赖没生效。

- [ ] **Step 4: 运行确认通过**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest=HttpToolInvokeIT -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 4 个测试 PASS。

**排查指引 —— 改实现，不要改断言：**

| 现象 | 含义 |
|---|---|
| `reachesUpstream` 报 404 / 连接错误 | `baseUrl` 与 `path` 拼接有问题。先打印 `out` 看实际 URL 再修。 |
| `blankWhitelistBlocksBeforeSendingRequest` 请求数 ≠ 0 | **安全缺陷** —— 白名单检查被放到了请求之后。必须修实现。 |
| `authHeaderReachesUpstream` 报 header 不匹配 | 检查 `HeaderResolver` 是否真的把 `auth.token` 合成 `Authorization` 头；`type=bearer` 应产出 `Bearer <token>`。 |

- [ ] **Step 5: 提交**

```bash
git add spring-ai-loom-agent-test
git commit -m "test(http): WireMock 全链路 IT —— 工具真的打到上游,fail-closed 真的拦住

不经 LLM:直接调 DefaultHttpTool.invokeEndpoint 并构造 ToolContext,避免
'模型不调工具'把安全断言变成 flaky。

四个用例各锁一条:
- reachesUpstream              —— 请求数 1 + statusCode 200 + 响应体含 pong
- authHeaderReachesUpstream    —— Authorization 头确实带上 profile 的 token,
                                  且 token 不出现在返回给调用方的字符串里
- blankWhitelistBlocksBeforeSendingRequest —— 空白名单请求数 0(spec 6.2 最核心一条;
  Task 6 的同名用例在 system 未注册时就返回,证明不了任何东西)
- hostNotInWhitelistIsRejected —— 白名单不含上游 host 时同样零请求

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 10: REST 读面脱敏 IT —— 凭据不得外泄

**Files:**
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/web/http/HttpProfileRouterMaskingIT.java`

**Interfaces:**
- Consumes: Task 7 `HttpProfileRouter` / `HttpRouterSupport.maskProfile` / `HttpManageGuard`
- Produces: 无新生产代码（`UserContextHolder.setCurrentUser` / `clear` 仓库已有，**不要重复添加**）。

**为什么用 `MockHttpServletRequest` 而不是 MockMvc**：MockMvc 会带上 `AuthenticationFilter`，需要真实会话才能让 `UserContextHolder` 有值 —— 那会让本测试退化成"测会话"而非"测脱敏"。直调 router 函数能显式注入 username，断言精确。仓库既有先例见 `FeaturesRouterTest`（同样 `MockHttpServletRequest` + `ServerRequest.create`）。

- [ ] **Step 1: 写 HttpProfileRouterMaskingIT**

创建 `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/web/http/HttpProfileRouterMaskingIT.java`：

```java
package cn.wubo.spring.ai.loom.agent.web.http;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REST 读面脱敏（spec §4.3 约束 2）。
 *
 * <p>LLM 走不到这条路径，但 admin 将来要在 UI 上查看 profile —— 一旦明文吐给
 * 浏览器，devtools / 扩展 / 代理都能拿到 API 凭据。
 */
@DisplayName("HTTP profile 读面脱敏")
class HttpProfileRouterMaskingIT {

    private static final String SECRET = "sk-live-READ-ME-99999";
    private static final String LIST_URI = "/spring/ai/loom/api/http/profiles";

    @AfterEach
    void clearUser() {
        UserContextHolder.clear();
    }

    /** IRoleService#getVisibleToolsForUser 返回 List&lt;String&gt;。 */
    private static HttpManageGuard guardGranting(String... groups) {
        IRoleService roleService = mock(IRoleService.class);
        when(roleService.getVisibleToolsForUser(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of(groups));
        return new HttpManageGuard(roleService);
    }

    private static ServerRequest get(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        req.setServletPath(uri);
        return ServerRequest.create(req, List.of(new MappingJackson2HttpMessageConverter()));
    }

    private static ServerResponse invoke(RouterFunction<ServerResponse> router, String uri) throws Exception {
        ServerRequest request = get(uri);
        return router.route(request).orElseThrow().handle(request);
    }

    private static LoomAgentProperties props(Path tmp) {
        LoomAgentProperties p = new LoomAgentProperties();
        p.setUsersBasePath(tmp.toString());
        p.getHttp().setAllowedDomains(List.of("example.com"));
        return p;
    }

    @Test
    @DisplayName("GET /profiles 的 token 是 ***，非明文")
    @SuppressWarnings("unchecked")
    void tokenIsMasked(@TempDir Path tmp) {
        UserContextHolder.setCurrentUser("alice");
        LoomAgentProperties props = props(tmp);

        var p = new cn.wubo.loom.http.core.profile.Profile();
        p.setName("prod");
        p.getAuth().setType("bearer");   // addProfile 跑 ProfileValidator,type 必填
        p.getAuth().setToken(SECRET);
        HttpRouterSupport.engineFor("alice", props).addProfile(p);

        RouterFunction<ServerResponse> router =
                new HttpProfileRouter().httpProfileRouter(guardGranting(HttpManageGuard.GROUP), props);

        ServerResponse response = invoke(router, LIST_URI);
        assertThat(response.statusCode().value()).isEqualTo(200);

        List<Map<String, Object>> body =
                (List<Map<String, Object>>) ((EntityResponse<?>) response).entity();

        assertThat(body).hasSize(1);
        assertThat(body.get(0)).containsEntry("name", "prod");
        Map<String, Object> auth = (Map<String, Object>) body.get(0).get("auth");
        assertThat(auth).containsEntry("token", HttpRouterSupport.MASK);
        assertThat(auth.get("token")).isNotEqualTo(SECRET);
    }

    @Test
    @DisplayName("未授权用户 GET /profiles → 403")
    void unauthorizedGets403(@TempDir Path tmp) {
        UserContextHolder.setCurrentUser("mallory");
        RouterFunction<ServerResponse> router =
                new HttpProfileRouter().httpProfileRouter(guardGranting(), props(tmp));

        ServerResponse response = invoke(router, LIST_URI);

        assertThat(response.statusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("未登录（username 为 null）→ 403，不泄露任何 profile")
    void anonymousGets403(@TempDir Path tmp) {
        UserContextHolder.clear();   // 不设置 —— 模拟未登录
        RouterFunction<ServerResponse> router =
                new HttpProfileRouter().httpProfileRouter(guardGranting(HttpManageGuard.GROUP), props(tmp));

        ServerResponse response = invoke(router, LIST_URI);

        assertThat(response.statusCode().value()).isEqualTo(403);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=HttpProfileRouterMaskingIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 至少 `tokenIsMasked` FAIL —— 这正是要锁的缺陷（当前实现可能返回明文）。若编译失败，检查 `HttpRouterSupport` 是否为包私有而测试包名不一致。

- [ ] **Step 3: 确认 HttpRouterSupport 对测试可见**

`HttpRouterSupport` 声明为 `final class`（包私有）。本测试 package 声明为 `cn.wubo.spring.ai.loom.agent.web.http`，与之一致，**无需改动**。若编译报"不可访问"，说明测试文件放错了包 —— 移回同包，不要把生产类改成 `public`。

- [ ] **Step 4: 运行确认通过**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest=HttpProfileRouterMaskingIT -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 3 个测试 PASS。

**若 `tokenIsMasked` 仍返回明文**：改 `HttpRouterSupport.maskProfile` 的实现（Task 7 已定义 `MASK = "***"` 与逐字段脱敏）。**不要把断言改成接受明文** —— 那条断言就是本 Task 存在的理由。

- [ ] **Step 5: 提交**

```bash
git add spring-ai-loom-agent-test
git commit -m "test(http): REST 读面脱敏 IT —— token 永不出浏览器

profile 读接口只被 admin UI 读取,但明文吐给浏览器等于把 API 凭据交给
devtools / 扩展 / 代理。用 MockHttpServletRequest 直调 router,显式注入 username,
避免 MockMvc + 会话把测试退化成'测会话'。

三个用例:脱敏正确 / 未授权 403 / 未登录(username=null)403 —— 后两条锁住
'username 只从会话取'这条横向越权防线。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```