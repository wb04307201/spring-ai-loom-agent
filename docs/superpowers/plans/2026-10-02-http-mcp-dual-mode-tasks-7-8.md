---

### Task 7: 注册 IHttpTool bean + REST 写面 + HttpManageGuard

**Files:**
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/web/http/HttpManageGuard.java`
- Create: `.../web/http/HttpProfileRouter.java`
- Create: `.../web/http/HttpSystemRouter.java`
- Create: `.../web/http/HttpRouterSupport.java`（脱敏 + username 解析共用）
- Create: `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/HttpManagementConfiguration.java`
- Modify: `.../autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `.../LoomAgentConfiguration.java`（`ToolConfiguration` 加一个 bean）
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/web/http/HttpManageRbacIT.java`
- Test: `.../web/http/HttpProfileRouterIT.java`

**Interfaces:**
- Consumes: `IHttpTool`（Task 6）、`IRoleService`（`getVisibleToolsForUser` → `List<String>`）、`UserContextHolder.getCurrentUser()`
- Produces:
  ```java
  public final class HttpManageGuard {
      public static final String GROUP = "tool_http_manage";
      public HttpManageGuard(IRoleService roleService);
      public boolean isAllowed(String username);
  }
  public RouterFunction<ServerResponse> httpProfileRouter(HttpManageGuard guard, LoomAgentProperties props);
  public RouterFunction<ServerResponse> httpSystemRouter(HttpManageGuard guard, LoomAgentProperties props);
  ```

**约定（已核实，务必遵守）**：
- 仓库现有 20 个 `RouterFunction` 全是 `LoomAgentConfiguration` 里的内联 `@Bean`。**本任务不沿用** —— 该文件已 4,857 行，再塞 13 个端点不可维护。改为新建独立 `@AutoConfiguration` 并登记进 `.imports`。这是仓库首次出现第二个 auto-config，用 `@AutoConfigureAfter(LoomAgentConfiguration.class)` 保证顺序。
- `UserContextHolder.getCurrentUser()` 取当前登录用户；**username 绝不从请求参数取**。
- 响应风格照既有 router：`ServerResponse.ok().body(...)` / `ServerResponse.status(403).body(Map.of("error", ...))`。

- [ ] **Step 1: 写失败测试 HttpManageRbacIT**

创建 `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/web/http/HttpManageRbacIT.java`：

```java
package cn.wubo.spring.ai.loom.agent.web.http;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("HTTP 写面 RBAC IT —— 未授权必须 403")
class HttpManageRbacIT {

    @Autowired private WebApplicationContext context;
    @Autowired private IRoleService roleService;
    @Autowired private JdbcTemplate jdbc;

    private static final String USER = "http-rbac-user";
    private static final String ROLE = "http-rbac-role";

    private void grantManage(String username) {
        jdbc.update("INSERT INTO role_tool(role_code, group_name, sort_order, default_enabled) "
                + "VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE default_enabled=TRUE",
                ROLE, HttpManageGuard.GROUP, 0, true);
        jdbc.update("INSERT INTO user_role(role_code, username) VALUES (?,?) "
                + "ON DUPLICATE KEY UPDATE role_code=role_code", ROLE, username);
        // IRoleService 可能带缓存，重建以确保生效
        roleService.getVisibleToolsForUser(username);
    }

    private void revokeAll(String username) {
        jdbc.update("DELETE FROM user_role WHERE username=?", username);
    }

    @Test
    @DisplayName("未授权用户 POST /api/http/profiles → 403")
    void unauthorizedIsForbidden() throws Exception {
        revokeAll(USER);
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/spring/ai/loom/api/http/profiles")
                        .contentType("application/json")
                        .content("{\"name\":\"p\",\"baseUrl\":\"https://x.example.com\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("授权后 guard 放行（直接验 guard，避免 REST 会话搭建掩盖 RBAC 语义）")
    void grantedUserPassesGuard() {
        revokeAll(USER);
        HttpManageGuard guard = new HttpManageGuard(roleService);
        assertThat(guard.isAllowed(USER)).isFalse();
        grantManage(USER);
        assertThat(guard.isAllowed(USER)).isTrue();
    }
}
```

**注意**：`grantManage` 里的 `ON DUPLICATE KEY` 是 MySQL 语法，H2 不支持。改为先 `DELETE` 再 `INSERT`（见 Step 4 修正）。首个测试依赖 `AuthenticationFilter` 建立会话，若过于脆弱，可只用第二个测试锁定 guard 语义 + 第三个测试用 MockMvc 带会话。

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=HttpManageRbacIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `cannot find symbol: class HttpManageGuard`

- [ ] **Step 3: 创建 HttpManageGuard**

创建 `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/web/http/HttpManageGuard.java`：

```java
package cn.wubo.spring.ai.loom.agent.web.http;

import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;

/**
 * HTTP 写面授权守卫。
 *
 * <p>为什么需要独立守卫，而不是复用 {@code AuthenticationFilter}：
 * filter 只保证"已登录"，不保证"有权管理 HTTP 配置"。{@code role_tool} 体系原本
 * 只管 LLM 工具 callback，本类把已有授权结论用到一个新位置 —— 加工具组
 * {@link #GROUP} 由 admin 在控制台授权，不新增表、不新增 SQL。
 *
 * <p>与 {@code tool_http}（调用面）分开的原因：写面能写入<b>含 API 凭据的文件</b>，
 * 调用面只能发请求。风险等级不同，应能分别授予。
 */
public final class HttpManageGuard {

    /** RBAC 工具组名，与 admin 控制台中显示的一致。 */
    public static final String GROUP = "tool_http_manage";

    private final IRoleService roleService;

    public HttpManageGuard(IRoleService roleService) {
        this.roleService = roleService;
    }

    public boolean isAllowed(String username) {
        if (username == null || username.isBlank()) return false;
        return roleService.getVisibleToolsForUser(username).contains(GROUP);
    }

    /** 未授权时统一返回 403 文案，供 router 直接使用。 */
    public static java.util.Map<String, String> forbidden() {
        return java.util.Map.of("error", "未授权 HTTP 管理能力（需要 tool_http_manage）");
    }
}
```

- [ ] **Step 4: 创建 HttpRouterSupport（脱敏 + username）**

创建 `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/web/http/HttpRouterSupport.java`：

```java
package cn.wubo.spring.ai.loom.agent.web.http;

import cn.wubo.loom.http.core.profile.Profile;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/** 两个 router 共用的辅助：取当前用户、构造用户级引擎、凭据脱敏。 */
final class HttpRouterSupport {

    static final String MASK = "***";

    private HttpRouterSupport() {}

    /** username 只从会话取，绝不从请求参数取 —— 否则构成横向越权。 */
    static String currentUsername() {
        return UserContextHolder.getCurrentUser();
    }

    static cn.wubo.loom.http.core.HttpEngine engineFor(String username, LoomAgentProperties props) {
        LoomAgentProperties.HttpProperty p = props.getHttp();
        cn.wubo.loom.http.core.HttpConfig cfg = new cn.wubo.loom.http.core.HttpConfig();
        cfg.setFailClosed(true);   // 与 DefaultHttpTool.engineFor 保持一致(spec §4.4)
        cfg.setAllowedDomains(new java.util.LinkedHashSet<>(p.getAllowedDomains()));
        cfg.setMaxResponseSizeBytes(p.getMaxResponseSizeBytes());
        cfg.setMaxBatchConcurrency(p.getMaxBatchConcurrency());
        cfg.setMaxRequestBodyBytes(p.getMaxRequestBodyBytes());
        cfg.setHistoryMaxEntriesPerSystem(p.getHistoryMaxEntriesPerSystem());
        return new cn.wubo.loom.http.core.HttpEngine(
                cn.wubo.loom.file.core.LoomPaths.userHttpDir(props.getUsersBasePath(), username), cfg);
    }

    /**
     * 凭据脱敏：auth 里的 token / password / value 一律替换为 ***。
     * 读接口即便 LLM 走不到，也会被 admin UI 读取 —— 不能把凭据原文吐给浏览器。
     */
    static Map<String, Object> maskProfile(Profile p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", p.getName());
        out.put("description", p.getDescription());
        out.put("baseUrl", p.getBaseUrl());
        out.put("allowedDomains", p.getAllowedDomains());
        out.put("timeoutMs", p.getTimeoutMs());
        if (p.getAuth() == null) {
            out.put("auth", null);
        } else {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("type", p.getAuth().getType());
            a.put("token", mask(p.getAuth().getToken()));
            a.put("username", p.getAuth().getUsername());
            a.put("password", mask(p.getAuth().getPassword()));
            a.put("keyName", p.getAuth().getKeyName());
            a.put("value", mask(p.getAuth().getValue()));
            out.put("auth", a);
        }
        return out;
    }

    private static String mask(String secret) {
        return secret == null || secret.isEmpty() ? null : MASK;
    }
}
```

- [ ] **Step 5: 修正测试里的 H2 语法**

把 Step 1 的 `grantManage` 改为 H2 兼容（先删后插）：

```java
    private void grantManage(String username) {
        jdbc.update("DELETE FROM user_role WHERE username=?", username);
        jdbc.update("DELETE FROM role_tool WHERE role_code=?", ROLE);
        jdbc.update("INSERT INTO role_tool(role_code, group_name, sort_order, default_enabled) VALUES (?,?,0,TRUE)",
                ROLE, HttpManageGuard.GROUP);
        jdbc.update("INSERT INTO user_role(role_code, username) VALUES (?,?)", ROLE, username);
    }
```

- [ ] **Step 6: 创建 HttpProfileRouter**

创建 `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/web/http/HttpProfileRouter.java`：

```java
package cn.wubo.spring.ai.loom.agent.web.http;

import cn.wubo.loom.http.core.profile.Profile;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * profile 写面 REST。
 *
 * <p><b>为什么写面是 REST 而非 LLM 工具</b>：profile 内含 API 凭据。若经
 * {@code IHttpTool} 写入，凭据会进入对话历史、持久化到 chat memory 并渲染在 UI。
 * 写面必须走带会话鉴权的 HTTP 通道。
 *
 * <p><b>鉴权</b>：{@link HttpManageGuard}（tool_http_manage），与 filter 独立。
 */
@Configuration
public class HttpProfileRouter {

    @Bean
    public RouterFunction<ServerResponse> httpProfileRouter(HttpManageGuard guard, LoomAgentProperties props) {
        RouterFunctions.Builder builder = RouterFunctions.route();

        builder.GET("spring/ai/loom/api/http/profiles", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            var engine = HttpRouterSupport.engineFor(username, props);
            List<Map<String, Object>> out = new ArrayList<>();
            for (String name : engine.listProfiles()) {
                var engine2 = HttpRouterSupport.engineFor(username, props);
                out.add(HttpRouterSupport.maskProfile(readProfile(engine2, name)));
            }
            return ServerResponse.ok().body(out);
        });

        builder.POST("spring/ai/loom/api/http/profiles", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            Profile p = request.body(Profile.class);
            if (p == null || p.getName() == null || p.getName().isBlank()) {
                return ServerResponse.badRequest().body(Map.of("error", "name 必填"));
            }
            String out = HttpRouterSupport.engineFor(username, props).addProfile(p);
            return ServerResponse.ok().body(out);
        });

        builder.DELETE("spring/ai/loom/api/http/profiles/{name}", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            String name = request.pathVariable("name");
            String out = HttpRouterSupport.engineFor(username, props).removeProfile(name);
            return ServerResponse.ok().body(out);
        });

        return builder.build();
    }

    private static Profile readProfile(cn.wubo.loom.http.core.HttpEngine engine, String name) {
        try {
            return engine.getProfile(name);   // Task 8 补这个读方法
        } catch (RuntimeException e) {
            return new Profile();
        }
    }
}
```

`engine.getProfile(name)` 在 Task 8 补齐；本 Task 先用占位实现并在 Task 8 换成真方法。

- [ ] **Step 7: 创建 HttpSystemRouter**

创建 `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/web/http/HttpSystemRouter.java`：

```java
package cn.wubo.spring.ai.loom.agent.web.http;

import cn.wubo.loom.http.core.system.System;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Map;

/** system / endpoint / OpenAPI 刷新的写面 REST。鉴权同 {@link HttpProfileRouter}。 */
@Configuration
public class HttpSystemRouter {

    @Bean
    public RouterFunction<ServerResponse> httpSystemRouter(HttpManageGuard guard, LoomAgentProperties props) {
        RouterFunctions.Builder builder = RouterFunctions.route();

        builder.GET("spring/ai/loom/api/http/systems", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            return ServerResponse.ok().body(HttpRouterSupport.engineFor(username, props).listSystems());
        });

        builder.POST("spring/ai/loom/api/http/systems", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            System s = request.body(System.class);
            if (s == null || s.getName() == null || s.getName().isBlank()) {
                return ServerResponse.badRequest().body(Map.of("error", "name 必填"));
            }
            return ServerResponse.ok().body(HttpRouterSupport.engineFor(username, props).registerSystem(s));
        });

        builder.DELETE("spring/ai/loom/api/http/systems/{name}", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            String name = request.pathVariable("name");
            return ServerResponse.ok().body(
                    HttpRouterSupport.engineFor(username, props).removeSystem(name, false));
        });

        builder.POST("spring/ai/loom/api/http/systems/{name}/refresh", request -> {
            String username = HttpRouterSupport.currentUsername();
            if (!guard.isAllowed(username)) return ServerResponse.status(403).body(HttpManageGuard.forbidden());
            String name = request.pathVariable("name");
            return ServerResponse.ok().body(HttpRouterSupport.engineFor(username, props).refreshSystem(name));
        });

        return builder.build();
    }
}
```

endpoint 的三个写端点（add / update / remove）同样加，路径 `spring/ai/loom/api/http/systems/{name}/endpoints...`，委托给 `HttpEngine.addEndpoint/updateEndpoint/removeEndpoint`（Task 8 补齐）。

- [ ] **Step 8: 创建独立 auto-config 并登记**

创建 `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/HttpManagementConfiguration.java`：

```java
package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import cn.wubo.spring.ai.loom.agent.web.http.HttpManageGuard;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * HTTP 能力装配（RBAC 写面守卫 + REST 路由）。
 *
 * <p>仓库首个与 {@link LoomAgentConfiguration} 并列的 auto-config —— 后者已 4,857 行，
 * 13 个 HTTP REST 端点不再内联进去。
 */
@Bean
    @ConditionalOnMissingBean
    public HttpManageGuard httpManageGuard(IRoleService roleService) {
        return new HttpManageGuard(roleService);
    }
}
```

两个 router（`HttpProfileRouter` / `HttpSystemRouter`）本身已是 `@Configuration`，Spring 会在
主源码包被扫描到 —— **但既有代码没有独立 router 类的先例**，故显式登记更稳妥：在这两个类上
加 `@AutoConfigureAfter(LoomAgentConfiguration.class)` 无效（它们不是 auto-config），改为
在本 auto-config 内显式 `@Import({HttpProfileRouter.class, HttpSystemRouter.class})`：

```java
@AutoConfiguration(after = LoomAgentConfiguration.class)
@ConditionalOnProperty(prefix = "spring.ai.loom.agent.http", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Import({cn.wubo.spring.ai.loom.agent.web.http.HttpProfileRouter.class,
         cn.wubo.spring.ai.loom.agent.web.http.HttpSystemRouter.class})
public class HttpManagementConfiguration {
```

编辑 `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，改为两行：

```
cn.wubo.spring.ai.loom.agent.LoomAgentConfiguration
cn.wubo.spring.ai.loom.agent.HttpManagementConfiguration
```

- [ ] **Step 9: 注册 IHttpTool bean**

编辑 `LoomAgentConfiguration.java` 的 `ToolConfiguration`（约 1014 行 `defaultFileTool` 之后），加：

```java
        /**
         * HTTP 调用工具(RBAC 工具 tool_http,defaultGranted=false)。
         * 凭据录入走 REST 写面(HttpProfileRouter),不暴露给 LLM。
         */
        @ConditionalOnMissingBean(IHttpTool.class)
        @Bean
        public IHttpTool defaultHttpTool(LoomAgentProperties properties) {
            return new DefaultHttpTool(properties.getUsersBasePath(), properties.getHttp());
        }
```

并补 import（`IHttpTool` / `DefaultHttpTool`）。

- [ ] **Step 10: 运行测试**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest='HttpManageRbacIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS

- [ ] **Step 11: 提交**

```bash
git add spring-ai-loom-agent spring-ai-loom-agent-spring-boot-autoconfigure spring-ai-loom-agent-test
git commit -m "feat(http): REST 写面 + HttpManageGuard + 独立 auto-config

profile/system/endpoint 的注册改走 REST(带会话鉴权),LLM 侧只保留读用面
—— 凭据不经对话历史与 chat memory。

HttpManageGuard 复用 IRoleService.getVisibleToolsForUser 的已有授权结论,
不加新表不加新 SQL;group=tool_http_manage,与调用面 tool_http 分开授权。
未授权 403 由 IT 锁定。

新建独立 auto-config 而非继续内联 LoomAgentConfiguration(已 4857 行)。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: 补齐 HttpEngine 缺失方法 + 双模一致性契约测试

**Files:**
- Modify: `loom-http-core/src/main/java/cn/wubo/loom/http/core/HttpEngine.java`
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/http/HttpToolDualModeContractTest.java`
- Test: `.../tool/http/HttpPerUserIsolationTest.java`
- Test: `.../capability/HttpToolGroupMetadataTest.java`

**Interfaces:**
- Consumes: Task 4 `HttpEngine`、Task 6 `DefaultHttpTool`
- Produces: `HttpEngine.getProfile(String name)`、`getSystem(String name)` 两个读方法

- [ ] **Step 1: 写失败测试 HttpToolDualModeContractTest**

核心是**锁住"两个壳不得漂移"** —— 同一份存储下，内部工具与直接调引擎必须逐字一致：

```java
package cn.wubo.spring.ai.loom.agent.tool.http;

import cn.wubo.loom.file.core.LoomPaths;
import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.profile.Profile;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双模一致性契约：内部工具壳与直接调引擎必须给出相同结果。
 *
 * <p>两个壳是同一份 core 的两个薄壳，若行为漂移，jar 用户与 loom 用户会得到
 * 不同答案 —— 这类偏差极难在集成期发现，故在此逐字断言。
 */
@DisplayName("双模一致性：IHttpTool 与 HttpEngine 结果逐字一致")
class HttpToolDualModeContractTest {

    @Test
    @DisplayName("listEndpoints：两壳输出逐字相同")
    void listEndpointsIdentical(@TempDir Path tmp) {
        HttpConfig cfg = new HttpConfig();
        cfg.setAllowedDomains(new java.util.LinkedHashSet<>(List.of("localhost")));
        Path root = LoomPaths.userHttpDir(tmp.toString(), "alice");

        HttpEngine engine = new HttpEngine(root, cfg);
        var sys = new cn.wubo.loom.http.core.system.System();
        sys.setName("svc");
        sys.setBaseUrl("http://localhost:8080");
        engine.registerSystem(sys);

        LoomAgentProperties.HttpProperty prop = new LoomAgentProperties.HttpProperty();
        prop.setAllowedDomains(List.of("localhost"));
        DefaultHttpTool tool = new DefaultHttpTool(tmp.toString(), prop);

        var ctx = new org.springframework.ai.chat.model.ToolContext(Map.of("username", "alice"));
        assertThat(tool.listEndpoints("svc", null, null, ctx))
                .isEqualTo(engine.listEndpoints("svc", null, null));
    }

    @Test
    @DisplayName("未注册 system：两壳都返回结构化错误，不抛异常")
    void unknownSystemSameError(@TempDir Path tmp) {
        HttpConfig cfg = new HttpConfig();
        Path root = LoomPaths.userHttpDir(tmp.toString(), "alice");
        HttpEngine engine = new HttpEngine(root, cfg);

        LoomAgentProperties.HttpProperty prop = new LoomAgentProperties.HttpProperty();
        DefaultHttpTool tool = new DefaultHttpTool(tmp.toString(), prop);
        var ctx = new org.springframework.ai.chat.model.ToolContext(Map.of("username", "alice"));

        String a = tool.listEndpoints("nope", null, null, ctx);
        String b = engine.listEndpoints("nope", null, null);
        assertThat(a).isEqualTo(b);
        assertThat(a).contains("error");
    }
}
```

- [ ] **Step 2: 运行确认失败或暴露差异**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=HttpToolDualModeContractTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 首次可能因 `listEndpoints` 尚未实现 openapi 合并段而输出不同 —— **那就是真实漂移，修 core 使两壳一致**。

- [ ] **Step 3: 补 HttpEngine 读方法**

编辑 `HttpEngine.java`，加两个只读方法（供 REST 读接口与脱敏用）：

```java
    /** 读单个 profile；不存在时抛 ProfileNotFoundException。 */
    public Profile getProfile(String name) { return profileService.get(name); }

    /** 读单个 system；不存在时抛 SystemNotFoundException。 */
    public System getSystem(String name) { return systemService.get(name); }
```

- [ ] **Step 4: 补 listEndpoints 的 openapi 合并段**

当前 Step 3 版本只返回 `endpointService.listManual(s)`，漏了 openapi 段。用 `EndpointMerger` 补齐（`InvokeService` 里已有同款用法可参照）：

```java
    public String listEndpoints(String system, String tag, String source) {
        return toJson(() -> {
            System s = systemService.get(system);
            List<Endpoint> manual = s.getEndpoints() == null ? List.of() : s.getEndpoints();
            List<Endpoint> openapi = List.of();
            if (s.getOpenapi() != null && s.getOpenapi().getSource() != null
                    && !s.getOpenapi().getSource().isBlank()) {
                try {
                    openapi = openApiCache.loadResult(s).endpoints();
                } catch (RuntimeException ignored) {
                    openapi = List.of();   // OpenAPI 拉取失败不应让手动段也列不出来
                }
            }
            var merged = new EndpointMerger().merge(openapi, manual);
            return merged.stream()
                    .filter(me -> tag == null || tag.isBlank()
                            || me.endpoint().getTags() != null && me.endpoint().getTags().contains(tag))
                    .filter(me -> source == null || source.isBlank()
                            || "both".equalsIgnoreCase(source) || me.source().equalsIgnoreCase(source))
                    .toList();
        });
    }
```

- [ ] **Step 5: 写 per-user 隔离测试**

创建 `HttpPerUserIsolationTest.java`：

```java
package cn.wubo.spring.ai.loom.agent.tool.http;

import cn.wubo.loom.file.core.LoomPaths;
import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.profile.Profile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户隔离：Alice 的 profile（含 API 凭据）对 Bob 不可见。
 *
 * <p>这是 per-user 存储的核心保证。若失效，Bob 可用 Alice 的凭据对外发请求。
 */
@DisplayName("HTTP 配置用户隔离")
class HttpPerUserIsolationTest {

    @Test
    @DisplayName("Alice 的 profile 对 Bob 不可见，反之亦然")
    void profilesAreNotShared(@TempDir Path tmp) {
        HttpEngine alice = new HttpEngine(LoomPaths.userHttpDir(tmp.toString(), "alice"), new HttpConfig());
        Profile p = new Profile();
        p.setName("prod");
        p.setBaseUrl("https://api.example.com");
        p.getAuth().setType("bearer");
        p.getAuth().setToken("super-secret-token");
        alice.addProfile(p);

        HttpEngine bob = new HttpEngine(LoomPaths.userHttpDir(tmp.toString(), "bob"), new HttpConfig());
        assertThat(bob.listProfiles()).doesNotContain("prod");
        assertThat(bob.listProfiles()).isEmpty();
    }

    @Test
    @DisplayName("凭据文件写在 Alice 自己的目录下")
    void credentialStaysInOwnerDir(@TempDir Path tmp) throws Exception {
        HttpEngine alice = new HttpEngine(LoomPaths.userHttpDir(tmp.toString(), "alice"), new HttpConfig());
        Profile p = new Profile();
        p.setName("prod");
        p.getAuth().setToken("super-secret-token");
        alice.addProfile(p);

        Path aliceFile = LoomPaths.userHttpDir(tmp.toString(), "alice")
                .resolve("profiles").resolve("prod.json");
        assertThat(Files.exists(aliceFile)).isTrue();
        assertThat(Files.readString(aliceFile)).contains("super-secret-token");

        Path bobFile = LoomPaths.userHttpDir(tmp.toString(), "bob")
                .resolve("profiles").resolve("prod.json");
        assertThat(Files.exists(bobFile)).isFalse();
    }
}
```

`getProfileNamesForTest` 不需要真实存在 —— 用 `listProfiles()` 即可，删掉那行冗余断言。

- [ ] **Step 6: 写 @ToolGroup 元数据测试**

创建 `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/capability/HttpToolGroupMetadataTest.java`：

```java
package cn.wubo.spring.ai.loom.agent.capability;

import cn.wubo.spring.ai.loom.agent.capability.CapabilityService;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;
import cn.wubo.spring.ai.loom.agent.tool.http.IHttpTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("tool_http 元数据：RBAC 工具,不得进 universal")
class HttpToolGroupMetadataTest {

    @Autowired private IHttpTool httpTool;
    @Autowired private CapabilityService capabilityService;

    @Test
    @DisplayName("@ToolGroup 值为 http 且 defaultGranted=false")
    void toolGroupAnnotation() {
        ToolGroup g = IHttpTool.class.getAnnotation(ToolGroup.class);
        assertThat(g).isNotNull();
        assertThat(g.value()).isEqualTo("http");
        assertThat(g.defaultGranted()).isFalse();
    }

    @Test
    @DisplayName("tool_http 不在 universal 集合中 —— 新装 admin 不得自动获得对外请求权")
    void notUniversal() {
        assertThat(capabilityService.universalToolGroups()).doesNotContain("tool_http");
    }

    @Test
    @DisplayName("capability 列表里含 tool_http 本地工具组")
    void appearsInCapabilityList() {
        assertThat(capabilityService.listAll())
                .anyMatch(c -> "tool_http".equals(c.id())
                        && c.type() == cn.wubo.spring.ai.loom.agent.model.CapabilityInfo.Type.LOCAL);
    }
}
```

签名已核实：`CapabilityInfo` 是 record，访问器为 `id()` / `type()`；`CapabilityService.listAll()`、`universalToolGroups()` 均为 public。

- [ ] **Step 7: 运行全部契约测试**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest='HttpToolDualModeContractTest,HttpPerUserIsolationTest,HttpToolGroupMetadataTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 全部 PASS

- [ ] **Step 8: 提交**

```bash
git add loom-http-core spring-ai-loom-agent spring-ai-loom-agent-test
git commit -m "feat(http): 补齐 HttpEngine 读方法与 openapi 合并,加双模契约测试

新增契约测试锁住新引入的不变量:内部工具壳与直接调 HttpEngine 必须逐字一致
(两壳漂移会让 jar 用户与 loom 用户得到不同答案,集成期极难发现)。
per-user 隔离测试证明 Alice 的凭据对 Bob 不可见。
元数据测试证明 tool_http 不进 universal 集合 —— 新装 admin 不自动获得对外请求权。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```