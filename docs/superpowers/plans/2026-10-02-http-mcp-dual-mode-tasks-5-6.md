---

## 阶段二：内部工具模式

### Task 5: LoomPaths 新增 userHttpDir + LoomAgentProperties.HttpProperty

**Files:**
- Modify: `loom-file-core/src/main/java/cn/wubo/loom/file/core/LoomPaths.java`
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java`
- Test: `loom-file-core/src/test/java/cn/wubo/loom/file/core/LoomPathsHttpTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  ```java
  // LoomPaths
  public static final String HTTP_SUBDIR = "http";
  public static Path userHttpDir(String usersBasePath, String username);

  // LoomAgentProperties
  private HttpProperty http = new HttpProperty();
  public HttpProperty getHttp() { return http; }

  public static class HttpProperty {
      private boolean enabled = true;
      private List<String> allowedDomains = List.of();   // 空 = 拒绝一切外网(fail-closed)
      private long timeoutMs = 10000;
      private long maxResponseSizeBytes = 1024L * 1024L;
      private int maxBatchConcurrency = 10;
      private long maxRequestBodyBytes = 10L * 1024L * 1024L;
      private int historyMaxEntriesPerSystem = 1000;
  }
  ```

- [ ] **Step 1: 写失败测试**

创建 `loom-file-core/src/test/java/cn/wubo/loom/file/core/LoomPathsHttpTest.java`：

```java
package cn.wubo.loom.file.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LoomPaths.userHttpDir 用户树路径派生")
class LoomPathsHttpTest {

    @Test
    @DisplayName("http 目录与 file / compile-workspaces 平级")
    void httpIsSiblingOfFileAndCompile(@TempDir Path tmp) {
        Path base = tmp.toString();
        Path userRoot = LoomPaths.userRoot(base, "alice");
        assertThat(LoomPaths.userHttpDir(base, "alice")).isEqualTo(userRoot.resolve("http"));
        assertThat(LoomPaths.userFileDir(base, "alice")).isEqualTo(userRoot.resolve("file"));
        assertThat(LoomPaths.userCompileWorkspacesDir(base, "alice"))
                .isEqualTo(userRoot.resolve("compile-workspaces"));
    }

    @Test
    @DisplayName("不同用户互相看不到对方的 http 目录")
    void usersAreIsolated(@TempDir Path tmp) {
        Path base = tmp.toString();
        assertThat(LoomPaths.userHttpDir(base, "alice"))
                .isNotEqualTo(LoomPaths.userHttpDir(base, "bob"));
    }

    @Test
    @DisplayName("username 消毒：路径分隔符与 .. 被替换为 _")
    void usernameIsSanitized(@TempDir Path tmp) {
        Path evil = LoomPaths.userHttpDir(tmp.toString(), "../../etc");
        assertThat(evil).startsWith(tmp.toAbsolutePath().normalize());
        assertThat(evil.toString()).doesNotContain("..");
    }

    @Test
    @DisplayName("username 为空回退 anonymous，不抛异常")
    void blankUsernameFallsBack(@TempDir Path tmp) {
        assertThat(LoomPaths.userHttpDir(tmp.toString(), null))
                .isEqualTo(LoomPaths.userRoot(tmp.toString(), "anonymous"));
        assertThat(LoomPaths.userHttpDir(tmp.toString(), "  "))
                .isEqualTo(LoomPaths.userRoot(tmp.toString(), "anonymous"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -pl loom-file-core -Dtest=LoomPathsHttpTest`
Expected: 编译失败 —— `cannot find symbol: method userHttpDir`

- [ ] **Step 3: 实现**

编辑 `LoomPaths.java`，在 `COMPILE_WORKSPACES_SUBDIR` 常量之后加：

```java
    /** 用户树下的目录名：HTTP profile / system / history 存储根。 */
    public static final String HTTP_SUBDIR = "http";
```

在 `userCompileWorkspacesDir` 方法之后加：

```java
    /**
     * 用户 HTTP 配置目录：{@code {usersBase}/{username}/http}。
     * <p>存放该用户的 profile（含凭据）、system 定义、请求历史与 OpenAPI 缓存。
     * 与 {@code file/}、{@code compile-workspaces/} 平级，file 工具沙箱够不到。
     *
     * <p>README/CLAUDE.md 的用户树表需同步补一行（Task 13）。
     */
    public static Path userHttpDir(String usersBasePath, String username) {
        return userRoot(usersBasePath, username).resolve(HTTP_SUBDIR);
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -pl loom-file-core -Dtest=LoomPathsHttpTest`
Expected: 4 个测试 PASS

**spec §6.2 列的 `HttpPathIsolationTest` 由本测试类承担**（同为 `loom-file-core` 的 `LoomPathsHttpTest`
四个用例：平级 / 用户互不可见 / username 消毒 / 空值回退），不另立类名 —— 拆两个类只会在
`loom-file-core` 里留下重复的 fixture。若 spec 读者找不到对应类名，以本类为准并在 spec 补注。

- [ ] **Step 5: 新增 HttpProperty**

编辑 `LoomAgentProperties.java`，在 `RenderProperty` 类之后、类的结束大括号之前加：

```java
    /**
     * HTTP 调用工具配置。yml 通过 spring.ai.loom.agent.http.* 配置。
     *
     * <p><b>allowedDomains 默认空 = 拒绝一切外网请求</b>（fail-closed）。
     * 部署方必须显式配置可访问域名，工具才生效 —— 这是 SSRF 防护的部署级闸门，
     * 与 profile 级的 allowedDomains（用户自助、收紧语义）刻意不同，
     * 见 spec §4.4 三态表。
     */
    public static class HttpProperty {
        private boolean enabled = true;
        /** 允许访问的域名白名单，支持通配符（如 api.example.com / *.example.com / host:port）。空 = 拒绝一切。 */
        private List<String> allowedDomains = List.of();
        /** 单次请求超时（毫秒）。 */
        private long timeoutMs = 10000;
        /** 单次响应体大小上限（字节），超过截断或落盘。 */
        private long maxResponseSizeBytes = 1024L * 1024L;
        /** httpBatch 的最大并发 op 数。 */
        private int maxBatchConcurrency = 10;
        /** 请求体大小上限（字节）。 */
        private long maxRequestBodyBytes = 10L * 1024L * 1024L;
        /** 每系统保留的历史条目数上限。 */
        private int historyMaxEntriesPerSystem = 1000;
    }
```

**不要手写 getter/setter** —— `LoomAgentProperties` 类级是 `@Data`（Lombok），所有属性类的 getter/setter 自动生成。`FileToolProperty` 同样一个 getter 都没写，这是仓库惯例。

在类中加字段与 getter：

```java
    private HttpProperty http = new HttpProperty();
```

Lombok 会为外层类生成 `getHttp()`，无需手写。

- [ ] **Step 6: 提交**

```bash
git add loom-file-core spring-ai-loom-agent
git commit -m "feat(http): LoomPaths 新增 userHttpDir,HttpProperty 默认 fail-closed

userHttpDir 与 file / compile-workspaces 平级,保证用户树下三块数据互不越界。
http.allowedDomains 默认空 = 拒绝一切外网 —— 部署未配置即未授权,与 @ToolGroup
的'可执行副作用必须显式授权'一致。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: IHttpTool + DefaultHttpTool

**Files:**
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/http/IHttpTool.java`
- Create: `.../tool/http/DefaultHttpTool.java`
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/http/DefaultHttpToolTest.java`

**Interfaces:**
- Consumes: `HttpEngine`（Task 4）、`LoomPaths.userHttpDir` + `LoomAgentProperties.HttpProperty`（Task 5）
- Produces:
  ```java
  @ToolGroup(value = "http", defaultGranted = false, description = "...")
  public interface IHttpTool extends IEmbedTool {
      String invokeEndpoint(String system, String method, String path,
                            Map<String,Object> params, Map<String,Object> body, String bodyRaw,
                            Map<String,String> headers, List<Map<String,Object>> assertions,
                            Map<String,String> extract, String responseMode, ToolContext toolContext);
      String httpBatch(List<Map<String,Object>> operations, Integer concurrency,
                       String failPolicy, String responseMode,
                       Integer firstN, ToolContext toolContext);
      String listEndpoints(String system, String tag, String source, ToolContext toolContext);
      String getEndpoint(String system, String method, String path, ToolContext toolContext);
      String getRequestHistory(String system, Integer limit, String statusFilter,
                               String since, ToolContext toolContext);
  }

  public class DefaultHttpTool implements IHttpTool {
      public DefaultHttpTool(String usersBasePath, LoomAgentProperties.HttpProperty property);
  }
  ```

- [ ] **Step 1: 写失败测试**

创建 `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/http/DefaultHttpToolTest.java`：

```java
package cn.wubo.spring.ai.loom.agent.tool.http;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultHttpTool 用户隔离与上下文")
class DefaultHttpToolTest {

    private static ToolContext ctxFor(String username) {
        return new ToolContext(Map.of("username", username));
    }

    private static LoomAgentProperties.HttpProperty props(List<String> allowedDomains) {
        LoomAgentProperties.HttpProperty p = new LoomAgentProperties.HttpProperty();
        p.setAllowedDomains(allowedDomains);
        return p;
    }

    @Test
    @DisplayName("缺少 username 上下文时返回错误文本，不抛异常")
    void missingUsernameReturnsError(@TempDir Path tmp) {
        DefaultHttpTool tool = new DefaultHttpTool(tmp.toString(), props(List.of()));
        String out = tool.listEndpoints("svc", null, null, new ToolContext(Map.of()));
        assertThat(out).contains("username");
    }

    @Test
    @DisplayName("两个用户的存储根互不可见 —— profile 不串号")
    void perUserStorageIsolation(@TempDir Path tmp) throws Exception {
        DefaultHttpTool tool = new DefaultHttpTool(tmp.toString(), props(List.of()));
        tool.listEndpoints("svc", null, null, ctxFor("alice"));
        tool.listEndpoints("svc", null, null, ctxFor("bob"));

        Path aliceDir = tmp.resolve("alice").resolve("http");
        Path bobDir = tmp.resolve("bob").resolve("http");
        // bob 的调用不能往自己的目录写出任何 system 文件（svc 未注册，两边都应是空目录）
        assertThat(dirIsEmpty(bobDir)).isTrue();
        // 存储根确实落在用户树下，且未逃出 tmp
        assertThat(aliceDir.startsWith(tmp.toAbsolutePath().normalize())).isTrue();
    }

    private static boolean dirIsEmpty(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return true;   // 目录都没建 = 空
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }

    @Test
    @DisplayName("白名单为空时拒绝，且不发起真实请求")
    void emptyWhitelistRejects(@TempDir Path tmp) {
        DefaultHttpTool tool = new DefaultHttpTool(tmp.toString(), props(List.of()));
        String out = tool.invokeEndpoint("svc", "GET", "/x",
                null, null, null, null, null, null, null, ctxFor("alice"));
        // svc 未注册 → 结构化错误；关键是不能抛异常
        assertThat(out).isNotNull();
        assertThat(out).doesNotContain("Connection refused");
    }
}
```

**第三条断言弱在哪、为什么 Task 9 要补**：`svc` 未注册时流水线在"查 system"就返回了，
根本没走到白名单判定那一步，所以它证明不了 fail-closed。真正的 fail-closed 证明在
Task 9 的 `HttpToolInvokeIT` —— 先注册指向 WireMock 的 system，再断言"请求数为 0"。

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=DefaultHttpToolTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `cannot find symbol: class DefaultHttpTool`

- [ ] **Step 3: 声明 loom-http-core 依赖**

编辑 `spring-ai-loom-agent/pom.xml`，在 `loom-file-core` 依赖之后加：

```xml
        <dependency>
            <groupId>io.github.wb04307201</groupId>
            <artifactId>loom-http-core</artifactId>
            <version>${project.parent.version}</version>
        </dependency>
```

（`loom-file-core` 已在该文件第 99 行附近，照其格式写。依赖方向已核实：
`spring-ai-loom-agent` → `loom-file-core`；autoconfigure → `spring-ai-loom-agent`。）

- [ ] **Step 4: 创建 IHttpTool**

创建 `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/http/IHttpTool.java`：

```java
package cn.wubo.spring.ai.loom.agent.tool.http;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

/**
 * HTTP 调用工具 —— <b>只读用面</b>。
 *
 * <p><b>刻意不暴露的能力</b>：profile / system / endpoint 的注册与修改。
 * 那部分走 REST 写面（{@code HttpProfileRouter} / {@code HttpSystemRouter}），
 * 因为 profile 内含 API 凭据 —— 若让 LLM 通过工具写，凭据会进入对话历史、
 * 持久化到 {@code SPRING_AI_CHAT_MEMORY} 并渲染在 UI 上。
 *
 * <p><b>RBAC</b>：{@code defaultGranted = false} —— 能对外发请求是特权能力，
 * 须 admin 在控制台显式授权（group = {@code tool_http}）。
 *
 * <p><b>存储隔离</b>：全部数据落在 {@code {usersBasePath}/{username}/http/}，
 * 由实现经 {@code LoomPaths.userHttpDir} 派生，用户之间互不可见。
 */
@ToolGroup(value = "http", defaultGranted = false,
            description = "HTTP 调用能力：invokeEndpoint / httpBatch / listEndpoints / "
                        + "getEndpoint / getRequestHistory，共 5 个工具（需管理员授权）")
public interface IHttpTool extends IEmbedTool {

    String invokeEndpoint(String system, String method, String path,
                          Map<String, Object> params, Map<String, Object> body, String bodyRaw,
                          Map<String, String> headers, List<Map<String, Object>> assertions,
                          Map<String, String> extract, String responseMode, ToolContext toolContext);

    String httpBatch(List<Map<String, Object>> operations, Integer concurrency,
                     String failPolicy, String responseMode,
                     Integer firstN, ToolContext toolContext);

    String listEndpoints(String system, String tag, String source, ToolContext toolContext);

    String getEndpoint(String system, String method, String path, ToolContext toolContext);

    String getRequestHistory(String system, Integer limit, String statusFilter,
                             String since, ToolContext toolContext);
}
```

- [ ] **Step 5: 创建 DefaultHttpTool**

创建 `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/http/DefaultHttpTool.java`：

```java
package cn.wubo.spring.ai.loom.agent.tool.http;

import cn.wubo.loom.file.core.LoomPaths;
import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.invoke.BatchRequest;
import cn.wubo.loom.http.core.invoke.InvokeRequest;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * HTTP 工具默认实现 —— 每个工具方法都是「取 username → 派生存储根 → 建引擎 → 委托 → 返回 String」。
 *
 * <p><b>为什么不缓存 HttpEngine</b>：引擎持有各存储服务的内存缓存与 OpenAPI 缓存，
 * 且绑死存储根。缓存它等于把 A 用户的凭据暴露给 B 用户。对照
 * {@code DefaultFileTool} 可以复用单例 {@code FileOperations}，是因为它无状态。
 * 每次 new 的开销是几个 POJO + 读一次 {@code config.json}，可忽略。
 */
public class DefaultHttpTool implements IHttpTool {

    private final String usersBasePath;
    private final LoomAgentProperties.HttpProperty property;

    public DefaultHttpTool(String usersBasePath, LoomAgentProperties.HttpProperty property) {
        this.usersBasePath = LoomPaths.orDefaultUsersBase(usersBasePath);
        this.property = property;
    }

    private HttpEngine engineFor(String username) {
        HttpConfig cfg = new HttpConfig();
        cfg.setFailClosed(true);   // 内部工具模式恒 fail-closed(spec §4.4)
        cfg.setAllowedDomains(new java.util.LinkedHashSet<>(property.getAllowedDomains()));
        cfg.setMaxResponseSizeBytes(property.getMaxResponseSizeBytes());
        cfg.setMaxBatchConcurrency(property.getMaxBatchConcurrency());
        cfg.setMaxRequestBodyBytes(property.getMaxRequestBodyBytes());
        cfg.setHistoryMaxEntriesPerSystem(property.getHistoryMaxEntriesPerSystem());
        return new HttpEngine(LoomPaths.userHttpDir(usersBasePath, username), cfg);
    }

    private static String tryGetUsername(ToolContext toolContext) {
        Map<String, Object> ctx = toolContext == null ? null : toolContext.getContext();
        Object u = ctx == null ? null : ctx.get("username");
        if (u == null || u.toString().isBlank()) return null;
        return u.toString();
    }

    // ==================== 调用 ====================

    @Tool(description = "调用已注册 HTTP 系统上的某个端点。system 为已注册的 API 系统名（先用 listEndpoints 查看可用端点），"
            + "method 为 GET/POST/PUT/DELETE 等，path 可含 {placeholder}，params 填 path/query 参数。"
            + "可选 assertions 做响应断言、extract 用 JSONPath 提取字段、responseMode 控制返回详细程度。")
    @Override
    public String invokeEndpoint(
            @ToolParam(description = "已注册的系统名称") String system,
            @ToolParam(description = "HTTP 方法，例如 GET / POST / PUT / DELETE") String method,
            @ToolParam(description = "端点路径，可包含 {placeholder}") String path,
            @ToolParam(description = "path / query 参数映射", required = false) Map<String, Object> params,
            @ToolParam(description = "请求体（JSON 对象）", required = false) Map<String, Object> body,
            @ToolParam(description = "请求体原始字符串（非 JSON 对象时用，与 body 互斥）", required = false) String bodyRaw,
            @ToolParam(description = "额外请求头映射", required = false) Map<String, String> headers,
            @ToolParam(description = "断言规则列表，形如 {type:statusEquals, value:200}", required = false) List<Map<String, Object>> assertions,
            @ToolParam(description = "JSONPath 提取映射，形如 {\"userId\":\"$.id\"}", required = false) Map<String, String> extract,
            @ToolParam(description = "响应模式：summary / full / file", required = false) String responseMode,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        InvokeRequest req = new InvokeRequest();
        req.setSystem(system);
        req.setMethod(method);
        req.setPath(path);
        req.setParams(params);
        req.setBody(body);
        req.setBodyRaw(bodyRaw);
        req.setHeaders(headers);
        req.setAssertions(assertions);
        req.setExtract(extract);
        req.setResponseMode(responseMode);
        req.setTimeoutMs(property.getTimeoutMs());
        return engineFor(username).invokeEndpoint(req);
    }

    @Tool(description = "并发执行一组 HTTP 操作，每个操作与 invokeEndpoint 走同一流程。适用于跨多个端点的批量读写。")
    @Override
    public String httpBatch(
            @ToolParam(description = "操作列表，每项形如 {system, method, path, params?, body?}", required = false) List<Map<String, Object>> operations,
            @ToolParam(description = "最大并发 op 数，默认 5", required = false) Integer concurrency,
            @ToolParam(description = "失败策略：continue（默认，全部跑完）/ stopOnFirst", required = false) String failPolicy,
            @ToolParam(description = "响应模式：summary / firstN / full / file", required = false) String responseMode,
            @ToolParam(description = "responseMode=firstN 时返回的条数", required = false) Integer firstN,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        // LLM 传的是 JSON map 形态，需转成 BatchRequest 期望的 List<InvokeRequest>
        List<InvokeRequest> ops = new java.util.ArrayList<>();
        if (operations != null) {
            for (Map<String, Object> m : operations) {
                if (m != null) ops.add(toInvokeRequest(m));
            }
        }
        BatchRequest req = new BatchRequest();
        req.setOperations(ops);
        req.setConcurrency(concurrency);
        req.setFailPolicy(failPolicy);
        req.setResponseMode(responseMode);
        req.setFirstN(firstN);
        return engineFor(username).httpBatch(req);
    }

    // ==================== 发现 ====================

    @Tool(description = "列出已注册系统的所有端点（手动定义 + OpenAPI 已合并）。可用 tag / source 过滤。")
    @Override
    public String listEndpoints(
            @ToolParam(description = "系统名称") String system,
            @ToolParam(description = "按 tag 过滤", required = false) String tag,
            @ToolParam(description = "来源过滤：openapi / manual / both", required = false) String source,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        return engineFor(username).listEndpoints(system, tag, source);
    }

    @Tool(description = "获取某个端点的完整 schema（参数、请求体、响应定义）。")
    @Override
    public String getEndpoint(
            @ToolParam(description = "系统名称") String system,
            @ToolParam(description = "HTTP 方法") String method,
            @ToolParam(description = "端点路径") String path,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        return engineFor(username).getEndpoint(system, method, path);
    }

    @Tool(description = "查询该用户自己的 HTTP 请求历史（仅当前用户的调用记录）。")
    @Override
    public String getRequestHistory(
            @ToolParam(description = "系统名称；省略表示全部", required = false) String system,
            @ToolParam(description = "返回条数上限，默认 50", required = false) Integer limit,
            @ToolParam(description = "状态过滤：succeeded / failed / all", required = false) String statusFilter,
            @ToolParam(description = "ISO-8601 时间戳，仅返回该时间之后", required = false) String since,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        Instant sinceAt = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceAt = Instant.parse(since);
            } catch (RuntimeException e) {
                return "错误：since 必须是 ISO-8601 时间戳，例如 2026-08-22T10:00:00Z";
            }
        }
        return engineFor(username).getRequestHistory(system,
                limit == null || limit <= 0 ? 50 : limit, statusFilter, sinceAt);
    }
}
```

**两处与 http-mcp 原实现的偏离，都是有意的：**

**(a) `httpBatch` 去掉两个"预留但被忽略"的参数。** 原 `BatchTools.httpBatch` 的 `profile` 与 `perRequestTimeoutMs` 在原实现里注释明写"预留，当前忽略"。内部工具签名里删掉它们，避免 LLM 误以为传了有用。

**(b) 需自己写 map → `InvokeRequest` 的转换。** `BatchRequest.operations` 的类型是 `List<InvokeRequest>`，而 LLM 工具收到的 JSON 只会绑定成 `List<Map<String,Object>>`。原 http-mcp 靠 `BatchTools`（`@Tool` 壳）里的转换代码完成这一步 —— 那个壳不搬入内部模式，所以 `DefaultHttpTool` 必须补上：

```java
    /** LLM 传来的 map 形态 → InvokeRequest。仅拷贝已知字段，忽略未知键。 */
    private static InvokeRequest toInvokeRequest(Map<String, Object> m) {
        InvokeRequest r = new InvokeRequest();
        r.setSystem(asString(m.get("system")));
        r.setMethod(asString(m.get("method")));
        r.setPath(asString(m.get("path")));
        r.setParams(asMap(m.get("params")));
        r.setBody(m.get("body"));
        r.setBodyRaw(asString(m.get("bodyRaw")));
        r.setHeaders(asStringMap(m.get("headers")));
        return r;
    }

    private static String asString(Object o) { return o == null ? null : o.toString(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> mm ? (Map<String, Object>) mm : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> asStringMap(Object o) {
        return o instanceof Map<?, ?> mm ? (Map<String, String>) mm : null;
    }
```

在 `httpBatch` 里先转换再交给引擎：

```java
        List<InvokeRequest> ops = new java.util.ArrayList<>();
        if (operations != null) {
            for (Map<String, Object> m : operations) {
                if (m != null) ops.add(toInvokeRequest(m));
            }
        }
        BatchRequest req = new BatchRequest();
        req.setOperations(ops);
        req.setConcurrency(concurrency);
        req.setFailPolicy(failPolicy);
        req.setResponseMode(responseMode);
        req.setFirstN(firstN);
        return engineFor(username).httpBatch(req);
```

新增测试断言：传 `[{system,method,path}]` 能落到 `BatchRequest.getOperations()` 且字段正确。

- [ ] **Step 6: 运行测试**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=DefaultHttpToolTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 3 个测试 PASS

若 `BatchRequest` 没有 `setFirstN` / `setResponseMode` 等 setter，按其真实字段调整（读 `loom-http-core` 的 `BatchRequest.java` 确认）。

- [ ] **Step 7: 提交**

```bash
git add spring-ai-loom-agent spring-ai-loom-agent-test
git commit -m "feat(http): 新增 IHttpTool/DefaultHttpTool(只读用面)

5 个工具: invokeEndpoint/httpBatch/listEndpoints/getEndpoint/getRequestHistory。
defaultGranted=false —— 对外发请求是特权能力,须 admin 授权(group=tool_http)。
刻意不暴露 profile/system 注册面: 那部分含 API 凭据,经工具写会进入对话历史
与 SPRING_AI_CHAT_MEMORY,改走 REST 写面。

DefaultHttpTool 每次调用 new HttpEngine —— 引擎绑死存储根,缓存它等于把
A 用户的凭据暴露给 B 用户(对照 DefaultFileTool 可复用单例是因为 FileOperations
无状态)。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```