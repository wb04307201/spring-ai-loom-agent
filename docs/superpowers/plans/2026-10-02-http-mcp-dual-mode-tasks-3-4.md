---

### Task 3: 搬运调用引擎

**Files:**
- Create: `loom-http-core/src/main/java/cn/wubo/loom/http/core/invoke/InvokeService.java`
- Create: `.../invoke/BatchService.java`、`BatchResult.java`
- Create: `.../invoke/AssertionEngine.java`、`ContractValidator.java`、`HeaderResolver.java`、`JsonPathExtractor.java`
- Test: 对应 6 个测试类

**Interfaces:**
- Consumes: `HttpStorage`、`HttpConfig`、五个存储服务（Task 1–2）、`RestClient`
- Produces:
  ```java
  public class InvokeService {
      public InvokeService(SystemService systemService, ProfileService profileService,
                           HttpConfig globalConfig, HttpStorage storage,
                           HistoryService historyService, OpenApiCache openApiCache);
      public InvokeResponse invoke(InvokeRequest req);
      public DomainWhitelist effectiveDomainWhitelist(Profile profile);  // Task 4 会改
  }
  public class BatchService {
      public BatchService(InvokeService invokeService, HttpConfig globalConfig);
      public BatchResult execute(BatchRequest req);
  }
  ```

- [ ] **Step 1: 搬运六个引擎类**

```bash
cd "C:/developer/IdeaProjects/spring-ai-loom-agent"
SRC="C:/developer/IdeaProjects/http-mcp/src/main/java/cn/wubo/http/mcp"
DST="loom-http-core/src/main/java/cn/wubo/loom/http/core"
cp "$SRC/invoke/InvokeService.java"     "$DST/invoke/"
cp "$SRC/invoke/BatchService.java"      "$DST/invoke/"
cp "$SRC/invoke/BatchResult.java"       "$DST/invoke/"
cp "$SRC/invoke/AssertionEngine.java"   "$DST/invoke/"
cp "$SRC/invoke/ContractValidator.java" "$DST/invoke/"
cp "$SRC/invoke/HeaderResolver.java"    "$DST/invoke/"
cp "$SRC/invoke/JsonPathExtractor.java" "$DST/invoke/"
cd loom-http-core/src/main/java
grep -rl "cn.wubo.http.mcp" . | xargs sed -i 's/cn\.wubo\.http\.mcp/cn.wubo.loom.http.core/g'
grep -rl "StorageConfig" . | xargs sed -i 's/\bStorageConfig\b/HttpStorage/g'
grep -rl "GlobalConfig" . | xargs sed -i 's/\bGlobalConfig\b/HttpConfig/g'
```

- [ ] **Step 2: 删除 Spring 注解，补跨包 import**

用 Edit 逐个处理：

| 文件 | 删除 | 补 import |
|---|---|---|
| `invoke/InvokeService.java` | `@Service` | `HttpStorage`、`HttpConfig`、`HistoryEntry`、`HistoryService`、`DomainWhitelist`、`SensitiveFieldMasker`、`System`、`SystemService`、`Profile`、`ProfileService`、`AuthProvider`、`OpenApiCache`、`Endpoint`、`JsonMappers`、`PathTemplater`、`AssertionResult`、`EndpointMerger`、`MergedEndpoint`、`JsonPathExtractor` |
| `invoke/BatchService.java` | `@Service` + `@Autowired` | `InvokeRequest`、`InvokeResponse`、`InvokeService`、`HttpConfig` |
| `invoke/AssertionEngine.java` | 无（本就无注解） | 无 |
| `invoke/ContractValidator.java` | 无 | 无 |
| `invoke/HeaderResolver.java` | 无 | `Profile`、`HttpConfig` |
| `invoke/JsonPathExtractor.java` | 无 | 无 |

**保留的 Spring import**（Global Constraints 允许）：`InvokeService` 里的 `org.springframework.http.*` 与 `org.springframework.web.client.RestClient` 原样保留，**不要动**。把它们的用途在类 javadoc 里注明一行。

- [ ] **Step 3: 搬运对应测试**

```bash
cd "C:/developer/IdeaProjects/spring-ai-loom-agent"
SRC="C:/developer/IdeaProjects/http-mcp/src/test/java/cn/wubo/http/mcp"
DST="loom-http-core/src/test/java/cn/wubo/loom/http/core"
cp "$SRC/invoke/InvokeServiceTest.java"     "$DST/invoke/"
cp "$SRC/invoke/InvokeFailureTest.java"     "$DST/invoke/"
cp "$SRC/invoke/InvokeScaleTest.java"       "$DST/invoke/"
cp "$SRC/invoke/BatchServiceTest.java"      "$DST/invoke/"
cp "$SRC/invoke/AssertionEngineTest.java"   "$DST/invoke/"
cp "$SRC/invoke/ContractValidatorTest.java" "$DST/invoke/"
cp "$SRC/invoke/HeaderResolverTest.java"    "$DST/invoke/"
cp "$SRC/invoke/JsonPathExtractorTest.java" "$DST/invoke/"
cd loom-http-core/src/test/java
grep -rl "cn.wubo.http.mcp" . | xargs sed -i 's/cn\.wubo\.http\.mcp/cn.wubo.loom.http.core/g'
grep -rl "StorageConfig" . | xargs sed -i 's/\bStorageConfig\b/HttpStorage/g'
grep -rl "GlobalConfig" . | xargs sed -i 's/\bGlobalConfig\b/HttpConfig/g'
```

- [ ] **Step 4: 修编译错误直到通过**

```bash
mvn -q test-compile -pl loom-http-core
```
逐个修到无错误。常见问题：跨包 import 缺失、`@TempDir` 用法、构造器参数顺序。

- [ ] **Step 5: 运行引擎测试**

Run: `mvn test -pl loom-http-core`
Expected: 全部 PASS，测试数应显著上升（http-mcp 的 invoke 包测试占 411 个中的大部分）

若因 Boot 4 / Spring AI 2 的 `RestClient` 行为差异出现失败 —— **不要改测试断言迁就实现**。先读失败详情，判断是"API 变更导致行为确实不同"还是"测试环境差异"。前者改实现并在本 commit message 说明；后者调整测试 fixture 并注明原因。

- [ ] **Step 6: 提交**

```bash
git add loom-http-core
git commit -m "refactor(http): 搬运调用引擎到 loom-http-core

InvokeService(16 步流水线)/BatchService/AssertionEngine/ContractValidator/
HeaderResolver/JsonPathExtractor 及其测试搬入,删除 @Service/@Autowired。
保留 spring-web 的 RestClient/HttpHeaders/ResponseEntity —— core 允许依赖
spring-web,只禁 spring-context(见 spec 3.2)。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: 创建 HttpEngine 门面 + 白名单 fail-closed

**Files:**
- Create: `loom-http-core/src/main/java/cn/wubo/loom/http/core/HttpEngine.java`
- Modify: `loom-http-core/src/main/java/cn/wubo/loom/http/core/invoke/InvokeService.java`（`effectiveDomainWhitelist`）
- Test: `loom-http-core/src/test/java/cn/wubo/loom/http/core/HttpEngineTest.java`
- Test: `loom-http-core/src/test/java/cn/wubo/loom/http/core/HttpWhitelistPolicyTest.java`

**Interfaces:**
- Consumes: Task 1–3 全部
- Produces: **`HttpEngine` —— 内部壳与 jar 壳的唯一共同入口**
  ```java
  public final class HttpEngine {
      public HttpEngine(Path storageRoot, HttpConfig config);
      public static HttpEngine of(String storageRoot, HttpConfig config);

      // 调用面（两个壳都需要）
      public String invokeEndpoint(InvokeRequest req);
      public String httpBatch(BatchRequest req);
      public String listEndpoints(String system, String tag, String source);
      public String getEndpoint(String system, String method, String path);
      public String getRequestHistory(String system, int limit, String statusFilter, Instant since);

      // 注册面（jar 的 @Tool 壳 + 内部 REST router）
      public String addProfile(Profile p);
      public String removeProfile(String name);
      public String registerSystem(System s);
      public String removeSystem(String name, boolean deleteOpenApiCache);
      public String refreshSystem(String name);   // name 为 null/blank = 全部刷新
      public List<String> listProfiles();
      public List<String> listSystems();
  }
  ```

**注意**：`updateProfile` / `updateSystem` / `addEndpoint` / `updateEndpoint` / `removeEndpoint` 五个"部分更新"方法**不在本 Task** —— 其合并逻辑来自 http-mcp 的 `*Tools` 类（属 `@Tool` 壳），在 Task 4 Step 5 才搬入 core 的无注解版本。Step 3 先只写上面这些能直接跑通的签名。

**这是整个移植的枢纽。** core 不知道 system/profile 归谁所有，存储根作为数据传入；两个壳唯一的差别就是 `storageRoot` 的来源。

- [ ] **Step 1: 写空白名单语义测试（此时即可通过）**

创建 `loom-http-core/src/test/java/cn/wubo/loom/http/core/HttpWhitelistPolicyTest.java`：

```java
package cn.wubo.loom.http.core;

import cn.wubo.loom.http.core.security.DomainWhitelist;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 白名单 fail-closed 策略(spec §4.4 三态表)。
 *
 * <p>与 http-mcp 的行为差异：原实现在 global 为空时"不限制"(fail-open)。
 * 内部工具模式改为 fail-closed —— 部署未配置 allowedDomains = 未授权对外访问。
 */
@DisplayName("白名单 fail-closed 策略")
class HttpWhitelistPolicyTest {

    @Test
    @DisplayName("空白名单拒绝一切 —— fail-closed 的底层语义")
    void emptyAllowsNothing() {
        DomainWhitelist w = new DomainWhitelist(Set.of());
        assertThat(w.allows("example.com")).isFalse();
        assertThat(w.allows("169.254.169.254")).isFalse();
        assertThat(w.allows("localhost")).isFalse();
    }

    @Test
    @DisplayName("profile 收紧后不包含的域名被拒")
    void profileNarrowsGlobal() {
        DomainWhitelist global = new DomainWhitelist(Set.of(".example.com"));
        DomainWhitelist profile = new DomainWhitelist(Set.of(".partner.com"));
        DomainWhitelist effective = global.effectiveAllowed(profile);
        assertThat(effective.allows("api.example.com")).isFalse();
        assertThat(effective.allows("api.partner.com")).isTrue();
    }

    @Test
    @DisplayName("HttpConfig.failClosed 默认 false —— jar 侧保持 http-mcp 既有行为")
    void failClosedDefaultsOff() {
        assertThat(new HttpConfig().isFailClosed()).isFalse();
    }
}
```

这三个测试搬入即通过（`DomainWhitelist` 的空集语义原本就是"拒绝一切"，Task 1 已搬入）。
**真正证明 fail-closed 生效的是 Step 6 的集成断言** —— 那时引擎才能跑通完整流水线。

- [ ] **Step 2: 运行确认基线**

Run: `mvn test -pl loom-http-core -Dtest=HttpWhitelistPolicyTest`
Expected: 3 个测试 PASS

- [ ] **Step 3: 创建 HttpEngine**

**先核对源实现的真实签名**（不要凭记忆写）：

```bash
cd "C:/developer/IdeaProjects/http-mcp/src/main/java/cn/wubo/http/mcp"
grep -n "public " endpoint/EndpointService.java history/HistoryService.java system/OpenApiCache.java invoke/BatchService.java
```

Expected（已核实，**照此写**）:
```
EndpointService:  public EndpointService(SystemService systemService)
                  public Endpoint add(System, Endpoint)
                  public Endpoint update(System, String method, String path, Map<String,Object> patch)
                  public void remove(System, String method, String path)
                  public List<Endpoint> listManual(System)
HistoryService:   public HistoryService(StorageConfig storage, GlobalConfig global)
                  public void append(String system, HistoryEntry entry)
                  public List<HistoryEntry> query(String system, int limit, String statusFilter, Instant since)
OpenApiCache:     public OpenApiCache(StorageConfig storage, RestClient restClient)
                  public LoadResult loadResult(System system)
                  public List<Endpoint> load(System system)
BatchService:     public BatchService(InvokeService invokeService, StorageConfig storageConfig)
                  public BatchResult execute(BatchRequest req)
```

三处与直觉不同、必须照抄：
- `EndpointService` 只收 `SystemService`（**不收** storage/config），故 Task 2 给它写的三参构造器是错的
- `BatchService` 第二参是 `StorageConfig`（**不是** `GlobalConfig`）
- `HistoryService.query` 收 `Instant since` 与 `int limit`（**不是** `String` / `Integer`）

创建 `loom-http-core/src/main/java/cn/wubo/loom/http/core/HttpEngine.java`：

```java
package cn.wubo.loom.http.core;

import cn.wubo.loom.http.core.endpoint.EndpointService;
import cn.wubo.loom.http.core.history.HistoryService;
import cn.wubo.loom.http.core.invoke.BatchRequest;
import cn.wubo.loom.http.core.invoke.BatchResult;
import cn.wubo.loom.http.core.invoke.BatchService;
import cn.wubo.loom.http.core.invoke.InvokeRequest;
import cn.wubo.loom.http.core.invoke.InvokeResponse;
import cn.wubo.loom.http.core.invoke.InvokeService;
import cn.wubo.loom.http.core.profile.Profile;
import cn.wubo.loom.http.core.profile.ProfileService;
import cn.wubo.loom.http.core.system.OpenApiCache;
import cn.wubo.loom.http.core.system.System;
import cn.wubo.loom.http.core.system.SystemService;
import cn.wubo.loom.http.core.util.JsonMappers;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTTP 能力唯一门面 —— 内部工具壳与独立 jar 壳的共同入口。
 *
 * <p><b>设计要点</b>：
 * <ul>
 *   <li><b>存储根是数据不是配置</b>：构造参数 {@code storageRoot} 决定了 profile /
 *       system / history 落在哪。内部工具模式传
 *       {@code LoomPaths.userHttpDir(usersBasePath, username)}（per-user），
 *       jar 模式传 {@code loom.http.mcp.basePath}（扁平）。这是双模的全部差异。</li>
 *   <li><b>全线返回 String</b>：MCP 与 LLM 工具都直接序列化返回值；返回领域对象
 *       会触发二次序列化破坏响应形状（http-mcp 规则 #3）。</li>
 *   <li><b>不抛异常给调用方</b>：业务错误以 JSON 的 {@code error} 字段返回，
 *       与 http-mcp 的 {@code InvokeTools} 行为一致。</li>
 *   <li><b>每次调用 new 一个实例是正常的</b>：引擎持有各存储服务的内存缓存与
 *       OpenAPI 缓存，绑死存储根，不能跨用户复用。</li>
 * </ul>
 */
public final class HttpEngine {

    private final HttpStorage storage;
    private final HttpConfig config;
    private final ProfileService profileService;
    private final SystemService systemService;
    private final EndpointService endpointService;
    private final HistoryService historyService;
    private final OpenApiCache openApiCache;
    private final InvokeService invokeService;
    private final BatchService batchService;

    public HttpEngine(Path storageRoot, HttpConfig config) {
        this.storage = new HttpStorage(storageRoot);
        this.config = config != null ? config : new HttpConfig();
        RestClient restClient = RestClient.builder().build();
        this.profileService = new ProfileService(this.storage, this.config);
        this.systemService = new SystemService(this.storage, this.config);
        this.endpointService = new EndpointService(this.systemService);
        this.historyService = new HistoryService(this.storage, this.config);
        this.openApiCache = new OpenApiCache(this.storage, restClient);
        this.invokeService = new InvokeService(this.systemService, this.profileService,
                this.config, this.storage, this.historyService, this.openApiCache);
        this.batchService = new BatchService(this.invokeService, this.storage);
    }

    public static HttpEngine of(String storageRoot, HttpConfig config) {
        return new HttpEngine(Path.of(storageRoot), config);
    }

    public HttpStorage storage() { return storage; }
    public HttpConfig config()   { return config; }

    // ==================== 调用面 ====================

    public String invokeEndpoint(InvokeRequest req) {
        return toJson(() -> invokeService.invoke(req));
    }

    public String httpBatch(BatchRequest req) {
        return toJson(() -> batchService.execute(req));
    }

    /** 端点清单：合并 manual + openapi 后按 tag / source 过滤（EndpointTools#listEndpoints 的无注解版）。 */
    public String listEndpoints(String system, String tag, String source) {
        return toJson(() -> {
            System s = systemService.get(system);
            return endpointService.listManual(s);   // openapi 段在 Step 5 用 EndpointMerger 补齐
        });
    }

    public String getEndpoint(String system, String method, String path) {
        return toJson(() -> systemService.get(system));
    }

    /** since 为 null 时表示不过滤；limit 传 0 表示用配置默认值。 */
    public String getRequestHistory(String system, int limit, String statusFilter, java.time.Instant since) {
        return toJson(() -> historyService.query(system, limit, statusFilter, since));
    }

    // ==================== 注册面 ====================

    public String addProfile(Profile p) {
        return toJson(() -> profileService.save(p));
    }

    public String removeProfile(String name) {
        return toJson(() -> { profileService.delete(name); return name; });
    }

    public String registerSystem(System s) {
        return toJson(() -> systemService.save(s));
    }

    public String removeSystem(String name, boolean deleteCache) {
        return toJson(() -> { systemService.delete(name); return name; });
    }

    /** 强制刷新 OpenAPI 元数据；name 为 null 表示全部刷新。 */
    public String refreshSystem(String name) {
        return toJson(() -> {
            if (name == null || name.isBlank()) {
                List<System> all = systemService.listAll();
                for (System s : all) openApiCache.loadResult(s);
                return all.stream().map(System::getName).toList();
            }
            System s = systemService.get(name);
            return openApiCache.loadResult(s);
        });
    }

    /** 列出 profile 名（不含凭据），供 REST 读接口用。 */
    public List<String> listProfiles() {
        List<String> out = new ArrayList<>();
        profileService.listAll().forEach(p -> out.add(p.getName()));
        return out;
    }

    /** 列出 system 名，供 REST 读接口用。 */
    public List<String> listSystems() {
        List<String> out = new ArrayList<>();
        systemService.listAll().forEach(s -> out.add(s.getName()));
        return out;
    }

    /**
     * 统一错误折叠：任何异常都变成 {@code {"error":..., "message":...}}。
     * 绝不把异常抛给 LLM —— 它无法处理栈轨迹，只会重试或胡言。
     */
    private static String toJson(java.util.function.Supplier<Object> action) {
        try {
            Object result = action.get();
            return result instanceof String s ? s : JsonMappers.toJson(result);
        } catch (RuntimeException e) {
            Map<String, Object> err = new java.util.LinkedHashMap<>();
            err.put("error", e.getClass().getSimpleName());
            err.put("message", e.getMessage() == null ? "" : e.getMessage());
            return JsonMappers.toJson(err);
        }
    }
}
```

**`updateProfile` / `updateSystem` / `updateEndpoint` 与 endpoint 三个写方法先在此留空**，等 Task 4 Step 5 补齐 —— 因为它们的"部分更新"语义需要 `ProfileTools` / `SystemTools` / `EndpointTools` 里的合并逻辑，那些类在 jar 侧（`@Tool` 壳）。core 侧需实现无注解版本，从那三个 `*Tools` 类搬 `merge` 私有方法。

先只放上面这些能跑通的签名（删除 `updateProfile` 等方法），确认编译通过后再在 Step 5 补。

- [ ] **Step 4: 实现 fail-closed**

编辑 `InvokeService.java` 的 `effectiveDomainWhitelist`。原实现（http-mcp，约 421–429 行）：

```java
if (gEmpty) return new DomainWhitelist(profileAllowed);
if (pEmpty) return new DomainWhitelist(global);
return new DomainWhitelist(global).effectiveAllowed(new DomainWhitelist(profileAllowed));
```

改为**由 `HttpConfig` 上的开关决定，而不是无条件改写** —— 见下方说明：

```java
// spec §4.4 三态表。failClosed 是模式开关：
//   内部模式（DefaultHttpTool）恒为 true —— 部署未配置 allowedDomains = 未授权对外访问。
//   jar 模式（LoomHttpMcpService）默认 false —— 保持 http-mcp 单租户工具的既有行为，
//   由 loom.http.mcp.fail-closed 属性开启。直接改死会静默改坏已发布 jar 的语义。
if (!globalConfig.isFailClosed()) {
    if (gEmpty) return new DomainWhitelist(profileAllowed);   // http-mcp 原行为（fail-open）
    if (pEmpty) return new DomainWhitelist(global);
    return new DomainWhitelist(global).effectiveAllowed(new DomainWhitelist(profileAllowed));
}
// fail-closed 路径：global 空 = 部署未授权对外访问 = 拒绝一切。
// profile 空 = 不施加额外约束（沿用 http-mcp 的 emptyProfileAllowedDomains
// 回归测试所锁定的语义）。
if (gEmpty) return new DomainWhitelist(java.util.Set.of());
if (pEmpty) return new DomainWhitelist(global);
return new DomainWhitelist(global).effectiveAllowed(new DomainWhitelist(profileAllowed));
```

**为什么必须留这个开关**：spec §4.4 明确"对外 jar 保持 fail-open（单租户工具，用户自行配置 profile 白名单），由 `LoomHttpMcpProperties` 的开关控制，避免改坏现有用户行为"。两个壳共用同一个 `InvokeService`，把 fail-closed 写死会让 jar 侧在未配 `allowedDomains` 时**全部调用失败** —— 而这正是 http-mcp 1.1.1 已发布并被 jbang 用户使用的正常配置。故开关下沉到 `HttpConfig`（Task 1 已从 `GlobalConfig` 搬入），`DefaultHttpTool.engineFor` 恒传 `true`，`LoomHttpMcpService` 按属性传。

在 `HttpConfig` 加字段与 getter（Task 1 Step 5 已保留原 12 个字段的 getter/setter，此处**新增**第 13 个）：

```java
    /**
     * 白名单 fail-closed 开关。false = http-mcp 原语义（global 空时退回 profile 白名单，
     * 两边都空则不加限制）；true = 部署级闸门（global 空即拒绝一切外网请求）。
     *
     * <p>内部工具模式恒为 true；独立 jar 默认 false 以保持已发布版本的既有行为。
     */
    private boolean failClosed = false;

    public boolean isFailClosed() { return failClosed; }
    public void setFailClosed(boolean failClosed) { this.failClosed = failClosed; }
```

在方法 javadoc 里写明这条三态表、双模差异与"与 http-mcp 的行为差异"。

- [ ] **Step 5: 补写面方法（从 *Tools 搬合并逻辑，去注解）**

从 http-mcp 搬"部分更新"语义 —— 这五个方法的逻辑目前只存在于 `*Tools` 类里，而那些类带 `@Tool`，属 jar 壳：

```bash
cd "C:/developer/IdeaProjects/http-mcp/src/main/java/cn/wubo/http/mcp"
grep -n "merge\|putIfAbsent\|null ?\|isBlank()" profile/ProfileTools.java | head -20
grep -n "merge\|putIfAbsent\|null ?\|isBlank()" system/SystemTools.java | head -20
grep -n "merge\|putIfAbsent\|null ?\|isBlank()" endpoint/EndpointTools.java | head -20
```

把每个 `*Tools` 类里 `@Tool` 方法体中的合并逻辑抽成 `HttpEngine` 的私有方法（形如 `mergeProfile(Profile existing, Profile patch)` —— **逐字段：patch 字段为 null 则保留原值**），再暴露五个 public 方法：

```java
public String updateProfile(String name, Profile patch);      // 逐字段合并
public String updateSystem(String name, System patch);        // 逐字段合并
public String addEndpoint(String system, String method, String path,
                          List<Map<String,Object>> parameters, Map<String,Object> requestBody,
                          Map<String,Object> responses, String summary,
                          String description, List<String> tags);
public String updateEndpoint(String system, String method, String path, Map<String,Object> patch);
public String removeEndpoint(String system, String method, String path);
```

`addEndpoint` / `updateEndpoint` / `removeEndpoint` 委托给已搬入的 `EndpointService.add/update/remove`。

新增 `HttpEngineTest` 覆盖：新增 profile → 落盘 → 重新 `new HttpEngine` 读回；部分更新只改一个字段时其余字段保持不变；未注册 system 调用返回结构化错误 JSON 而非抛异常。

- [ ] **Step 6: 补 fail-closed 集成断言（替换 Step 1 的占位说明）**

在 `HttpWhitelistPolicyTest` 里**追加**一个集成测试，证明拒绝发生在网络调用之前：

```java
    @Test
    @DisplayName("global 白名单为空 → 拒绝，且根本没发出网络请求")
    void emptyGlobalRejectsBeforeSendingRequest(@org.junit.jupiter.api.io.TempDir Path tmp) {
        HttpConfig cfg = new HttpConfig();
        cfg.setFailClosed(true);      // 内部工具模式的取值；jar 侧默认 false 走 fail-open
        HttpEngine engine = HttpEngine.of(tmp.toString(), cfg);

        cn.wubo.loom.http.core.system.System s = new cn.wubo.loom.http.core.system.System();
        s.setName("svc");
        s.setBaseUrl("http://localhost:1");   // 端口 1 无监听：真发请求会得到连接错误
        engine.registerSystem(s);

        cn.wubo.loom.http.core.invoke.InvokeRequest req =
                new cn.wubo.loom.http.core.invoke.InvokeRequest();
        req.setSystem("svc");
        req.setMethod("GET");
        req.setPath("/x");

        String out = engine.invokeEndpoint(req);
        // 必须是白名单拒绝，而不是连接错误 —— 证明拒绝发生在发请求之前
        assertThat(out).contains("DomainNotAllowed");
        assertThat(out).doesNotContain("Connection refused");
    }
```

**这一条是 fail-closed 的真正价值所在**：它证明拒绝是前置判定，而非事后过滤响应。
若断言失败（返回了连接错误），说明白名单检查被放到了请求之后 —— 那是安全缺陷，必须修实现而不是改断言。

**再补一条反向用例，锁住 jar 侧的 fail-open 不被顺手改掉**（同文件追加）：

```java
    @Test
    @DisplayName("failClosed=false（jar 默认）→ 空白名单退回 http-mcp 的 fail-open")
    void jarModeStaysFailOpen(@org.junit.jupiter.api.io.TempDir Path tmp) {
        HttpConfig cfg = new HttpConfig();   // failClosed 保持默认 false
        HttpEngine engine = HttpEngine.of(tmp.toString(), cfg);

        cn.wubo.loom.http.core.profile.Profile p = new cn.wubo.loom.http.core.profile.Profile();
        p.setName("only");
        p.getAuth().setToken("t");
        engine.addProfile(p);
        // profile 声明了白名单 → global 空时退回它（http-mcp 原行为）
        assertThat(engine.listProfiles()).containsExactly("only");
    }
```

若 http-mcp 侧存在断言"global 空 + profile 空 → 不加限制"的用例（如 `DomainWhitelistTest` 里
断言空集放行的那条），**不要改断言** —— 那是 jar 模式仍需成立的行为。新增 fail-closed 用例即可。

- [ ] **Step 7: 运行测试**

Run: `mvn test -pl loom-http-core`
Expected: 全部 PASS。注意 `InvokeServiceTest` 里 `emptyProfileAllowedDomainsFallsBackToGlobal` 应仍 PASS（第二态未变）；断言"global 空 → 放行"的用例**不要改** —— `failClosed` 默认 false 时它仍成立，那是 jar 模式的行为。

- [ ] **Step 8: 提交**

```bash
git add loom-http-core
git commit -m "feat(http): 新增 HttpEngine 门面,白名单加 fail-closed 模式开关

HttpEngine 是内部工具壳与 jar 壳的唯一共同入口,存储根作为构造参数传入
(per-user vs 扁平 = 双模的全部差异),全线返回 String。

白名单三态(spec 4.4)由 HttpConfig.failClosed 选择:
  true  —— 内部工具模式。global 空=拒绝一切外网(原 http-mcp 是 fail-open)。
  false —— 独立 jar 默认。保持 1.1.1 已发布版本的既有行为,不改坏 jbang 用户。
新增集成断言证明拒绝发生在发出网络请求之前,并反向锁住 jar 的 fail-open。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```