# HTTP 调用能力双模接入设计（loom-http-core / loom-http-mcp）

- 日期：2026-10-02
- 状态：设计已确认，待实现计划
- 来源项目：`C:\developer\IdeaProjects\http-mcp`（Boot 3.5.13 / Spring AI 1.1.8 / Java 17，发布为 `io.github.wb04307201:http-mcp:1.1.1`）
- 目标仓库：`spring-ai-loom-agent`（Boot 4.1.1 / Spring AI 2.0.1 / JDK 25）

## 1. 背景与目标

### 1.1 为什么搬

`http-mcp` 是一个功能完整的通用 HTTP 客户端 MCP server：profile 化认证、OpenAPI 自动加载、批量调用、断言校验、JSONPath 提取、请求历史。它目前独立运行，用户通过 jbang 拉起，与 `spring-ai-loom-agent` 的聊天能力完全割裂 —— LLM 在 loom-agent 里无法调用外部 API。

仓库已有成熟的**双模工具模式**：`loom-file-core` 装纯逻辑，`DefaultFileTool`（`@Tool` + `ToolContext`）与 `LoomFileMcpService`（`@McpTool` + 扁平 `basePath`）是两个薄壳。本次把 http-mcp 按同一模式接入，使 LLM 既能在 loom-agent 内部调用外部 API，也能作为独立 MCP server 供其他 AI 客户端使用。

### 1.2 现状盘点（实测数据）

| 指标 | 数值 |
|---|---|
| 主代码 | 50 个文件 / 7,039 行 |
| 测试代码 | 38 个文件 / 10,517 行（411 个测试） |
| `@Tool` 方法 | 19 个 |
| `@McpResource` | 4 个 |
| 触碰 Spring 的文件 | 25 / 50 |
| 纯 JUnit 测试文件 | 37 / 38（仅 `E2ETestSupport` 起 Spring 上下文） |

**测试资产是本次移植最重要的风险缓冲**：绝大多数测试不依赖 Spring 容器，可近乎原样搬运，为改写提供回归网。

### 1.3 目标

1. 新增 `loom-http-core`（引擎）+ `loom-http-mcp`（独立 jar）+ 内部 `IHttpTool` / REST 写面
2. 内部模式严格 per-user；`LoomPaths` 新增 `userHttpDir`
3. 凭据不经 LLM：内部模式只暴露读用面工具，写面走 REST
4. RBAC 双 group，默认不授权；域名白名单 fail-closed

## 2. 范围裁定

### 2.1 迁入（全部保留）

**调用引擎**：`InvokeService`(687)、`AssertionEngine`(272)、`ContractValidator`(245)、`HeaderResolver`(225)、`DomainWhitelist`(218)、`SensitiveFieldMasker`(124)、`EndpointMerger`(87)、`PathTemplater`(62)、`JsonPathExtractor`(44)、`AuthProvider`(41)、`ProfileValidator`(255) 及全部模型记录（`Profile` / `System` / `Endpoint` / `InvokeRequest` / `InvokeResponse` / `BatchRequest` / `BatchResult` / `HistoryEntry` / `GlobalConfig` / `MergedEndpoint` / `AssertionResult`）。

**存储服务**：`ProfileService`(93)、`SystemService`(78)、`EndpointService`(65)、`HistoryService`(188)、`OpenApiCache`(394)。

### 2.2 只在对外 jar 保留

四类文件不进 `loom-http-core`，仅作为 `loom-http-mcp` 的 jar 私有实现存在：

**(a) `httpGet` / `httpPost` / `httpPut` / `httpDelete`**（`HttpService`, 256 行）

理由：原始 URL 入口在多租户服务器端是最大的越权面 —— 任一获授权用户可用**他人 profile 的凭据**请求云元数据服务（`169.254.169.254`）或内网管理接口。`invokeEndpoint` 已覆盖同等能力，LLM 只需指定 system 名。

**(b) 4 个 MCP Resource**（`SystemListResource` / `SystemResource` / `EndpointsResource` / `EndpointResource`，515 行）

理由：MCP Resource 是 **MCP 协议专有**能力，Spring AI 内部工具路径无对应物；且其信息与 `listEndpoints` / `getEndpoint` 完全重叠，后者返回同样的 JSON。

**(c) `FileWatcher`(210)**

理由：它是 MCP 协议通知机制（`notifications/resources/list_changed`），仅 jar 模式有意义。core 不含任何协议相关代码。

**(d) `McpConfig`(75)**

理由：Spring 装配层，由两个壳各自承担等价职责（内部模式无 MCP 协议通知需求）。

**由此确立双模的一条规则**（写入 CLAUDE.md）：*Resource 与 MCP 协议机制是 jar 独占能力；Tool 才是双模对象。*

### 2.3 真正舍弃

无。http-mcp 的全部 50 个文件在两种模式下都有归属（迁入 core / jar 独占）。

## 3. 模块布局与核心契约

### 3.1 模块布局

```
loom-http-core          新增。引擎 + 存储服务。零 @Tool / @McpTool / @Component。
                       依赖：spring-web（仅 RestClient / HttpHeaders / ResponseEntity）、
                       jackson、json-path、swagger-parser。
                       不依赖 spring-context —— DI 全部手搭。
                       包名 cn.wubo.loom.http.core.*

loom-http-mcp           新增。独立可运行 jar，对齐 loom-file-mcp 惯例：
                       LoomHttpMcpApplication / LoomHttpMcpConfiguration /
                       LoomHttpMcpProperties（前缀 loom.http.mcp）/ LoomHttpMcpService。
                       19 个 @McpTool（snake_case）+ 4 个 @McpResource。

spring-ai-loom-agent    + IHttpTool / DefaultHttpTool       （@Tool + 尾部 ToolContext）
                       + HttpProfileRouter / HttpSystemRouter（REST 写面）
                       + HttpManageGuard（RBAC guard）
                       + tool/http/ 包

loom-file-core          + LoomPaths.userHttpDir(usersBasePath, username) → .../http/
```

### 3.2 关于"loom-\*-core 无 Spring"不变量的修订

原不变量为"严格无 Spring 依赖"。本次经决策**改写**为"**无 spring-context / 无容器**"：

- 允许：`spring-web`（`RestClient` / `HttpHeaders` / `ResponseEntity` / `MediaType` / `HttpMethod`）
- 仍禁止：`spring-context`、`spring-boot`、`spring-ai`

理由：`spring-web` 不需要容器即可使用（`RestClient.builder()` 是编程式构造，非 bean），而强行改写为 JDK `HttpClient` 需重写重试、超时、响应头大小写处理并重测，回归风险高于收益。此修订需同步更新 CLAUDE.md 的模块结构表。

### 3.3 核心契约

```java
// cn.wubo.loom.http.core.HttpStorage —— 取代 http-mcp 的 @Component StorageConfig
public final class HttpStorage {
    private final Path root;           // 构造传入，绝不从全局/环境变量派生
    public Path root();
    public Path profilesDir();  public Path systemsDir();
    public Path historyDir();   public Path responsesDir();
    public Path configFile();   public Path logDir();
    public Path auditLogFile();
}

// 唯一门面，两个壳都调它
public final class HttpEngine {
    public HttpEngine(Path storageRoot, HttpConfig config);
    public String invokeEndpoint(InvokeRequest req);
    public String httpBatch(BatchRequest req);
    public String listEndpoints(String system, String tag, String source);
    public String getEndpoint(String system, String method, String path);
    public String getRequestHistory(String system, Integer limit, String statusFilter, String since);
    // 注册类 CRUD —— 仅供 jar 的 @Tool 壳与内部 REST router 调用
    public String addProfile(...);    public String updateProfile(...);   public String removeProfile(...);
    public String registerSystem(...);public String updateSystem(...);    public String removeSystem(...);
    public String refreshSystem(String name);
    public String addEndpoint(...);   public String updateEndpoint(...);  public String removeEndpoint(...);
}
```

**这是整个移植的枢纽**：core 不知道"system"/"profile"归谁所有，存储根作为**数据**传入。两个壳构造引擎方式完全一致，唯一差别是 `root` 的来源。

`HttpEngine` 全线返回 `String`，保住 http-mcp 的两条血泪规则：

- 规则 #3：`@Tool` 方法只返回 String，返回领域对象会被 Spring 二次序列化破坏响应形状
- 规则 #2：`JsonMappers` 绝不启用 `INDENT_OUTPUT`，否则往每个 HTTP body 塞换行与 `: ` 空格，破坏上游解析

## 4. 内部工具模式接线

### 4.1 工具分组与接口

```java
@ToolGroup(value = "http", defaultGranted = false,
            description = "HTTP 调用能力：invokeEndpoint / httpBatch / listEndpoints / "
                        + "getEndpoint / getRequestHistory，共 5 个工具")
public interface IHttpTool extends IEmbedTool {
    String invokeEndpoint(String system, String method, String path,
                          Map<String, Object> params, Map<String, Object> body, String bodyRaw,
                          Map<String, String> headers, List<Map<String, Object>> assertions,
                          Map<String, String> extract, String responseMode, ToolContext toolContext);
    String httpBatch(String profile, List<Map<String, Object>> operations, Integer concurrency,
                     String failPolicy, Long perRequestTimeoutMs, String responseMode,
                     Integer firstN, ToolContext toolContext);
    String listEndpoints(String system, String tag, String source, ToolContext toolContext);
    String getEndpoint(String system, String method, String path, ToolContext toolContext);
    String getRequestHistory(String system, Integer limit, String statusFilter,
                             String since, ToolContext toolContext);
}
```

内部模式**只暴露这 5 个读用面工具**。全部以 profile 名 / system 名引用，LLM 永远拿不到凭据本身。

`body` 必须是 `Map<String, Object>` 而非 `Object` —— http-mcp 的 FIX-5 记录了 Spring AI 1.1.0–1.1.7 对无类型 `Object` 参数的绑定 bug（会把 `McpAsyncServerExchange` 当参数传入，上游收到的 body 变成 `body.context.exchange.clientInfo`）。非对象 body 走 `bodyRaw: String`，二者互斥。

### 4.2 用户上下文与存储根

```java
public class DefaultHttpTool implements IHttpTool {
    private final String usersBasePath;
    private final HttpConfig config;
    // 不持有 engine —— engine 绑定存储根，而存储根每请求随 username 变
    private HttpEngine engineFor(String username) {
        return new HttpEngine(LoomPaths.userHttpDir(usersBasePath, username), config);
    }
}
```

`DefaultHttpTool` **不缓存 engine**。`FileOperations` 无状态故可复用单例；HTTP 引擎持有各存储服务的内存态与 OpenAPI 缓存、绑死存储根，故每次调用现造。开销为几个 POJO + 读一次 `config.json`，可忽略。

username 经 `ToolContext` 的 `context.get("username")` 取得（与 `DefaultFileTool.tryGetUsername` 同款）；`DefaultChat` 已在 `props.put("username", username)` 处注入。

### 4.3 写面 REST

复用 `WebConfiguration` 的 `RouterFunction` + `AuthenticationFilter`（cookie session）：

```
GET    /spring/ai/loom/api/http/profiles
POST   /spring/ai/loom/api/http/profiles
PUT    /spring/ai/loom/api/http/profiles/{name}
DELETE /spring/ai/loom/api/http/profiles/{name}
GET    /spring/ai/loom/api/http/systems
POST   /spring/ai/loom/api/http/systems
PUT    /spring/ai/loom/api/http/systems/{name}
DELETE /spring/ai/loom/api/http/systems/{name}
GET    /spring/ai/loom/api/http/systems/{name}/endpoints
POST   /spring/ai/loom/api/http/systems/{name}/endpoints
PUT    /spring/ai/loom/api/http/systems/{name}/endpoints/{method}/{path}
DELETE /spring/ai/loom/api/http/systems/{name}/endpoints/{method}/{path}
POST   /spring/ai/loom/api/http/systems/{name}/refresh
```

三条硬性约束：

1. **username 只从 session 取**，绝不从请求参数取 —— 否则构成横向越权
2. **读接口一律脱敏**：`auth.token` / `auth.password` / `auth.apiKey` 返回 `"***"`。LLM 走不到此路径，但 admin 将来需在 UI 上查看这些记录
3. **写面独立鉴权**（见 §5.3），不可依赖 `AuthenticationFilter` 已放行来推断权限

### 4.4 白名单 fail-closed 的落点

```java
// LoomAgentProperties.HttpProperty
private List<String> allowedDomains = List.of();   // 空 = 拒绝一切外网请求
```

`InvokeService` 原语义为"两边都空 → 返回对方"（fail-open）。内部模式改为**三态**判定：

| global（`http.allowed-domains`） | profile（`profile.allowedDomains`） | effective | 理由 |
|---|---|---|---|
| 空 | 任意 | **空白名单**（拒绝一切） | 部署未配置 = 未授权对外访问 |
| 非空 | 空 | global | profile 未声明约束 = 不额外收紧 |
| 非空 | 非空 | `effectiveAllowed(global, profile)` | profile 收紧（保留原逻辑最有价值的部分） |

第二行是关键决策：**profile 白名单为空意为"不施加约束"，而非"拒绝一切"**。理由是 profile 由用户经 REST 自助创建，若"留空 = 全拒"，用户每次建 profile 都必须先想清楚要访问哪些域名 —— 把配置摩擦强加给终端用户没有收益；而部署级的 `global` 是管理员的开关，空 = 未授权，是安全边界。两者语义不同是有意的。

`DomainWhitelist.effectiveAllowed` 原样保留 —— 它把对方集合过滤为"自己覆盖得了"的子集，正是 profile 收紧所需的语义。

**对外 jar 保持 fail-open**（单租户工具，用户自行配置 profile 白名单），由 `LoomHttpMcpProperties` 的开关控制，避免改坏现有用户行为。

判定必须发生在**发出请求之前**，而非事后过滤响应。

该判定挂在 `HttpConfig.failClosed` 开关上（core 侧），而非无条件改写：内部工具模式恒传 `true`，
`loom-http-mcp` 由 `loom.http.mcp.fail-closed` 属性控制、默认 `false`。两个壳共用同一个
`InvokeService`，写死会让未配 `allowedDomains` 的 jar 用户**全部调用失败**，而那正是已发布的
http-mcp 1.1.1 的正常配置。

## 5. RBAC 接线

### 5.1 两个 tool group

| group | `defaultGranted` | 覆盖 | 语义 |
|---|---|---|---|
| `tool_http` | `false` | 5 个读用面工具 | "能对外发请求" |
| `tool_http_manage` | `false` | REST 写面（profile / system / endpoint / refresh） | "能写入含密钥的文件" |

分成两组而非一组，理由是风险不同：可只给只读角色前者，不给后者。

### 5.2 `CapabilityService` 与系统提示词

- `universalToolGroups()` 只反射 `@ToolGroup(defaultGranted=true)`，两个新 group 均为 `false`，天然不进 union，**无需改动**
- `filterEmbedToolsByCapabilityIds` 按 `tool_http` 过滤 `IEmbedTool`，与 git / maven 同一路径
- `DefaultChat`【平台能力】段的 RBAC 工具行由 `visibleToolGroupsFor(username)` 动态生成。新增 RBAC 工具**不需要改这段代码**，但须遵守其既有约定："未授权能力绝不向用户承诺"

### 5.3 REST 写面鉴权

`role_tool` 体系原本只管工具 callback，无"REST 端点按 group 授权"的先例。本设计扩展该语义：

```java
// HttpManageGuard —— 检查 username 是否具备 tool_http_manage
boolean allowed = roleService.getVisibleToolsForUser(username).contains("tool_http_manage");
if (!allowed) throw new ResponseStatusException(FORBIDDEN, "未授权 HTTP 管理能力");
```

复用 `IRoleService.getVisibleToolsForUser`（`CapabilityService.visibleToolGroupsFor` 底层同一方法），**不加新表、不加新 SQL** —— 只是把已有授权结论用在新位置，属 RBAC 工具语义的自然延伸。

这是与 `AuthenticationFilter` **相互独立**的第二道门。测试必须锁死"未授权用户 POST `/api/http/profiles` 返回 403"。

### 5.4 数据库影响

`role_tool` 表结构无需改动（新 group 只是多一行数据），且按既有约定**不 seed 默认 admin 授权** —— `V1.0__init.sql` 第 3 条设计要点明写"不 seed 默认 admin 授权（Q12 决定）"。新装部署零授权即零暴露，与 fail-closed 一致。

## 6. 测试与验证策略

### 6.1 第一层：搬运的回归网（`loom-http-core`）

38 个测试文件中 37 个为纯 JUnit，直接搬运。三类需改：

1. 包名 `cn.wubo.http.mcp.*` → `cn.wubo.loom.http.core.*`
2. 构造方式：`new InvokeService(...)` 参数由 Spring bean 改为 `HttpStorage` + `HttpConfig`
3. **fail-closed 语义**：断言"空白名单 = 允许全部"的用例改为断言"拒绝"。这是有意的行为变更，测试必须同步，且须在 commit message 中写明

每个 `FIX-*` 注释背后都是真实 bug，`DomainWhitelistTest` / `HeaderResolverTest` / `AssertionEngineTest` / `InvokeServiceTest` 覆盖的正是要改写的部分。

### 6.2 第二层：新增契约测试（`spring-ai-loom-agent-test`）

锁住新引入的"双模一致性"不变量：

| 测试 | 锁什么 |
|---|---|
| `HttpToolDualModeContractTest` | 同一份存储下 `IHttpTool` 与 `HttpEngine` 返回逐字一致 —— 两壳不得漂移 |
| `HttpWhitelistFailClosedTest` | `allowedDomains` 空时 `invokeEndpoint` 拒绝，且**真的未发出网络请求**（以 WireMock 请求计数断言 0）—— 由 `HttpToolInvokeIT` 的前两个用例承担 |
| `HttpToolInvokeIT` | 直接调 `DefaultHttpTool.invokeEndpoint(...)`（构造 `ToolContext` 注入 username），断言 WireMock 收到请求、响应正确 —— **不打 LLM，确定性覆盖工具→上游全链路**；同时承担上面那条 fail-closed 断言 |
| `HttpManageRbacIT` | 未授权 POST `/api/http/profiles` → 403；授权后 → 200 |
| `HttpPerUserIsolationTest` | user A 的 profile，user B 的 `invokeEndpoint` 读不到、REST 也读不到 |
| `HttpToolGroupMetadataTest` | `@ToolGroup(value="http", defaultGranted=false)` 元数据正确，且 `tool_http` **不在** `universalToolGroups()` 内 |
| `LoomPathsHttpTest` | `LoomPaths.userHttpDir` 卫生：username 消毒、`..` 逃逸被拒、与 `userFileDir` / `userCompileWorkspacesDir` 平级不重叠 |

### 6.3 第三层：浏览器 E2E（`*BrowserIT`）

沿用现有 Playwright 基建 + WireMock 上游（`wiremock-jetty12` 3.5.4，http-mcp 测试已在用）：

```
admin 登录 → 角色授权 tool_http + tool_http_manage
 → 以目标用户身份 POST profile（带 token）
  → POST system（baseUrl 指向 WireMock）+ POST endpoint
  → 打开会话历史页，检索 token 明文 → 断言不存在
```

（"LLM 实际调用"一段已移出浏览器 IT，理由见本节末说明。）

最后一条是安全底线 —— 验证 §4.3 确实做到"凭据从不进对话"。

**不依赖 LLM 自主选择工具**：上述 E2E 中"LLM 调 invokeEndpoint"这一步不可靠 —— 模型可能不调用、或调用方式与预期不同。因此 E2E 只覆盖 **admin 授权 → 用户配 profile/system/endpoint → token 不入对话历史** 这条确定性链路（纯 REST + UI，不经 LLM）。

"LLM 经工具回调真的打通到 WireMock"这一段改由**非浏览器测试**承担：`HttpToolInvokeIT` 用 `DefaultHttpTool.invokeEndpoint(...)` 直接调用并构造 `ToolContext`，断言 WireMock 收到请求且响应正确。这样确定且快，浏览器 IT 不再背这个不确定性。

安全性的最后一环 —— **REST 写面注入的 token 不会经由任何 LLM 可达路径泄露** —— 由两条断言共同锁定：`HttpPerUserIsolationTest`（跨用户读不到）+ E2E 的历史检索断言（对话记录里搜不到明文）。

### 6.4 验证门

```bash
mvn clean install -Dgpg.skip=true                     # 全模块编译
mvn test -pl loom-http-core                           # 第一层回归
mvn test -pl spring-ai-loom-agent-test                # 单元（470+，默认不跑 *IT）
rm -rf spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/test-users
mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' \
      -Dsurefire.failIfNoSpecifiedTests=false       # IT 门（191+）
mvn test -pl spring-ai-loom-agent-test -Dtest='*BrowserIT' \
      -Dsurefire.failIfNoSpecifiedTests=false       # 浏览器门
```

`*IT` 的数据库隔离靠 `spring-ai-loom-agent-test/src/test/resources/application.yml`，该文件已把 `spring.datasource.url` 与 `datasource-dir` 覆盖到 `./target/test-ds`、`users-base-path` 覆盖到 `./target/test-users`，**测试期不触碰 `~/.loom`**。浏览器 IT 另在 `BrowserTestBase` 的 `@SpringBootTest(properties=...)` 里覆盖为 `./target/e2e-files/users`。

因此清理仪式是 `rm -rf spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/test-users spring-ai-loom-agent-test/target/e2e-files`，而非清理 `~/.loom/datasource`。

### 6.5 安全自审要点

本次改动属信任边界输入 + 凭据 + 数据模型，按 AQG 属 `deep`。提交前必查：

- 白名单判定是否在**发出请求之前**（而非事后过滤）
- `effectiveAllowed` 的 fail-closed 分支是否有测试覆盖"global 空"路径
- REST router 是否存在绕过 session 直接取 username 的路径
- 历史落盘前 body 脱敏（FIX-7）是否在搬运中丢失
- 是否引入新的跨用户可见路径

## 7. 风险与未决项

### 7.1 风险

| 风险 | 缓解 |
|---|---|
| `RestClient` 语义在 Boot 4 / AI 2 下的行为差异 | 411 个测试构成的回归网；`InvokeServiceTest` 直接覆盖调用路径 |
| Spring AI 2.x 中 `@Tool` / `ToolContext` 绑定行为变化 | `body` 保持具体类型（FIX-5）；契约测试锁双模一致性 |
| `org.springaicommunity.mcp.annotation.McpTool` 包名迁移 | loom 现有 `loom-file-mcp` 已用 `org.springframework.ai.mcp.annotation.McpTool`，直接对齐 |
| fail-closed 改动使既有 http-mcp 搬运测试失败 | §6.1 第 3 类明确要求同步改断言并写进 commit message |
| ~~E2E 依赖 LLM 实际选择工具~~ | **已消除**：LLM 调用链移至 `HttpToolInvokeIT`（直接调工具方法），浏览器 IT 只测确定性 REST/UI 链路 |

### 7.2 未决项

无阻塞性未决项。以下为实现阶段需实测确定的技术细节：

- `InvokeService` 的 16 步流水线在无 Spring 装配下的初始化顺序（`defaultMasker` 当前在构造器内初始化，改为显式传入）
- `OpenApiCache` 的 ETag 复用与 `StorageConfig` 路径解耦后的行为
- WireMock 在 `spring-ai-loom-agent-test` 中的端口分配与生命周期（与现有 `*BrowserIT` 基建共存）

## 8. 后续处置

移植完成且测试全绿后，删除 `C:\developer\IdeaProjects\http-mcp` 本地目录。删除前须逐类比对确认新代码已完整覆盖原实现。

注意：http-mcp 已发布至 Maven Central（`io.github.wb04307201:http-mcp:1.1.1`），jbang 用户仍按该坐标拉取。删除本地目录**不会**使已发布版本消失，但会使本地无法回查 1.1.1 的实现（gitee 远程仓库不受影响）。新坐标为 `io.github.wb04307201:loom-http-mcp`。

本项目 CLAUDE.md 记载 `docs/superpowers/` 目录曾于 2026-09-19 被清理；本文件为新内容，非历史恢复。