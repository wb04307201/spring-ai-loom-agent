## File Structure

**新建 —— `loom-http-core`**（职责：引擎与存储，无协议无注解）

| 文件 | 职责 |
|---|---|
| `pom.xml` | 依赖：spring-web、jackson、json-path、swagger-parser |
| `HttpStorage.java` | 存储根路径派生（原 `StorageConfig`，去 `@Component`） |
| `HttpConfig.java` | 全局配置（原 `GlobalConfig`） |
| `HttpEngine.java` | **唯一门面**，两个壳的共同入口 |
| `invoke/InvokeService.java` | 16 步调用流水线（`RestClient` 执行） |
| `invoke/InvokeRequest.java` `InvokeResponse.java` | 调用入参 / 出参模型 |
| `invoke/BatchService.java` `BatchRequest.java` `BatchResult.java` | 批量并发调用 |
| `invoke/AssertionEngine.java` `AssertionResult.java` | 断言校验 |
| `invoke/ContractValidator.java` | 响应字段对 OpenAPI schema 轻量校验 |
| `invoke/HeaderResolver.java` | 占位符展开（env / file / hmacSha256） |
| `invoke/JsonPathExtractor.java` | JSONPath 字段提取 |
| `invoke/EndpointMerger.java` `MergedEndpoint.java` | manual + openapi 合并 |
| `profile/*` | `Profile` / `ProfileService` / `AuthProvider` / `ProfileValidator` / `ProfileNotFoundException` |
| `system/*` | `System` / `SystemService` / `OpenApiCache` / `SystemNotFoundException` |
| `endpoint/*` | `Endpoint` / `EndpointService` / `EndpointNotFoundException` |
| `history/*` | `HistoryEntry` / `HistoryService` |
| `security/*` | `DomainWhitelist` / `SensitiveFieldMasker` |
| `util/*` | `JsonMappers` / `PathTemplater` |

**新建 —— `loom-http-mcp`**（职责：独立 jar 壳 + jar 独占能力）

| 文件 | 职责 |
|---|---|
| `pom.xml` | `loom-http-core` + `spring-ai-starter-mcp-server` + boot repackage |
| `LoomHttpMcpApplication.java` | 启动类（`web-application-type: none`） |
| `LoomHttpMcpConfiguration.java` | 手搭 DI |
| `LoomHttpMcpProperties.java` | `loom.http.mcp.*` |
| `LoomHttpMcpService.java` | 19 个 `@McpTool`（snake_case） |
| `LoomHttpMcpResources.java` | 4 个 `@McpResource` |
| `LoomHttpService.java` | jar 独占 `httpGet/Post/Put/Delete` |
| `FileWatcher.java` | jar 独占 `WatchService` → `list_changed` |
| `application.yml` | stdio 配置 |

**修改 —— `loom-file-core`**：`LoomPaths.java` 加 `HTTP_SUBDIR` + `userHttpDir(...)`

**新建 —— `spring-ai-loom-agent`**：`LoomAgentProperties.HttpProperty` / `IHttpTool` / `DefaultHttpTool` / `HttpManageGuard` / `HttpProfileRouter` / `HttpSystemRouter`

**修改 —— autoconfigure**：`ToolConfiguration` 加 bean；`WebConfiguration` 加两个 router

**修改 —— 测试模块**：`spring-ai-loom-agent-test/pom.xml` 加 `wiremock-jetty12`（test scope）

---

## 阶段一：loom-http-core

### Task 1: 创建 loom-http-core 模块骨架 + 存储与模型

**Files:**
- Modify: `pom.xml`（`<modules>` 加一行）
- Create: `loom-http-core/pom.xml`
- Create: `loom-http-core/src/main/java/cn/wubo/loom/http/core/HttpStorage.java`
- Create: `loom-http-core/src/main/java/cn/wubo/loom/http/core/HttpConfig.java`
- Create: `loom-http-core/src/main/java/cn/wubo/loom/http/core/{util,security,profile,system,endpoint,history,invoke}/*.java`（搬移）
- Test: `loom-http-core/src/test/java/cn/wubo/loom/http/core/HttpStorageTest.java`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces:
  ```java
  public final class HttpStorage {
      public HttpStorage(Path root);
      public static HttpStorage of(String path);
      public Path root();
      public Path profilesDir();     // root/profiles
      public Path systemsDir();      // root/systems
      public Path historyDir();      // root/history
      public Path responsesDir();    // root/responses
      public Path configFile();      // root/config.json
      public Path logDir();          // root/log
      public Path auditLogFile();    // root/log/audit.log
  }
  public final class HttpConfig { /* 原 GlobalConfig 全部 12 个字段 */ }
  ```

- [ ] **Step 1: 在父 pom 注册模块**

编辑 `pom.xml` 的 `<modules>` 段，在 `loom-file-core` 之后加一行：

```xml
<module>loom-http-core</module>
```

- [ ] **Step 2: 创建模块 pom**

创建 `loom-http-core/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.github.wb04307201</groupId>
        <artifactId>spring-ai-loom-agent-parent</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>loom-http-core</artifactId>
    <name>loom-http-core</name>
    <description>Core HTTP client engine - no Spring context, no MCP protocol</description>

    <dependencies>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>
        <dependency>
            <groupId>com.jayway.jsonpath</groupId>
            <artifactId>json-path</artifactId>
            <version>2.9.0</version>
        </dependency>
        <dependency>
            <groupId>io.swagger.parser.v3</groupId>
            <artifactId>swagger-parser</artifactId>
            <version>2.1.22</version>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: 搬移纯 POJO 与工具类**

```bash
cd "C:/developer/IdeaProjects/spring-ai-loom-agent"
SRC="C:/developer/IdeaProjects/http-mcp/src/main/java/cn/wubo/http/mcp"
DST="loom-http-core/src/main/java/cn/wubo/loom/http/core"
mkdir -p "$DST"/util "$DST"/security "$DST"/profile "$DST"/system "$DST"/endpoint "$DST"/history "$DST"/invoke
cp "$SRC/util/JsonMappers.java"            "$DST/util/"
cp "$SRC/util/PathTemplater.java"          "$DST/util/"
cp "$SRC/security/DomainWhitelist.java"    "$DST/security/"
cp "$SRC/security/SensitiveFieldMasker.java" "$DST/security/"
cp "$SRC/profile/Profile.java"             "$DST/profile/"
cp "$SRC/profile/AuthProvider.java"        "$DST/profile/"
cp "$SRC/profile/ProfileValidator.java"    "$DST/profile/"
cp "$SRC/profile/ProfileNotFoundException.java" "$DST/profile/"
cp "$SRC/system/System.java"               "$DST/system/"
cp "$SRC/system/SystemNotFoundException.java" "$DST/system/"
cp "$SRC/endpoint/Endpoint.java"           "$DST/endpoint/"
cp "$SRC/endpoint/EndpointNotFoundException.java" "$DST/endpoint/"
cp "$SRC/history/HistoryEntry.java"        "$DST/history/"
cp "$SRC/invoke/InvokeRequest.java"        "$DST/invoke/"
cp "$SRC/invoke/InvokeResponse.java"       "$DST/invoke/"
cp "$SRC/invoke/AssertionResult.java"      "$DST/invoke/"
cp "$SRC/invoke/BatchRequest.java"         "$DST/invoke/"
cp "$SRC/invoke/MergedEndpoint.java"       "$DST/invoke/"
cp "$SRC/invoke/ExtractionException.java"  "$DST/invoke/"
cp "$SRC/invoke/HeaderResolutionException.java" "$DST/invoke/"
```

改包声明：

```bash
cd loom-http-core/src/main/java
grep -rl "cn.wubo.http.mcp" . | xargs sed -i 's/cn\.wubo\.http\.mcp/cn.wubo.loom.http.core/g'
```

这些文件本身零 Spring 依赖（已核实：`AuthProvider` 只 import `java.util`，`ProfileValidator` 只 import `org.slf4j` + JDK）。

- [ ] **Step 4: 创建 HttpStorage**

创建 `loom-http-core/src/main/java/cn/wubo/loom/http/core/HttpStorage.java`：

```java
package cn.wubo.loom.http.core;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * HTTP 能力的存储根派生（无 Spring，原 http-mcp 的 {@code StorageConfig}）。
 *
 * <p><b>与 http-mcp 的关键差异</b>：原实现从 {@code ${mcp.storage.root}} + 环境变量
 * {@code MCP_STORAGE_DIR} 派生，是<b>单一全局根</b>。本类改为<b>构造传入</b>，
 * 因为内部工具模式必须按用户隔离（{@code ~/.loom/users/{username}/http/}），
 * 而 jar 模式保持扁平（{@code ~/.loom/mcp/http/}）—— 存储根是<b>数据</b>，
 * 不是全局配置。这是双模差别的全部所在。
 */
public final class HttpStorage {

    private final Path root;

    public HttpStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public static HttpStorage of(String path) {
        return new HttpStorage(Paths.get(path));
    }

    public Path root() { return root; }
    public Path profilesDir()  { return root.resolve("profiles"); }
    public Path systemsDir()   { return root.resolve("systems"); }
    public Path historyDir()   { return root.resolve("history"); }
    public Path responsesDir() { return root.resolve("responses"); }
    public Path configFile()   { return root.resolve("config.json"); }
    public Path logDir()       { return root.resolve("log"); }
    public Path auditLogFile() { return logDir().resolve("audit.log"); }
}
```

- [ ] **Step 5: 搬运 GlobalConfig 为 HttpConfig**

```bash
cd "C:/developer/IdeaProjects/spring-ai-loom-agent"
cp "C:/developer/IdeaProjects/http-mcp/src/main/java/cn/wubo/http/mcp/config/GlobalConfig.java" \
   "loom-http-core/src/main/java/cn/wubo/loom/http/core/HttpConfig.java"
```

然后用 Edit 改三处：
1. 首行 `package cn.wubo.http.mcp.config;` → `package cn.wubo.loom.http.core;`
2. `public class GlobalConfig {` → `public class HttpConfig {`
3. 在类 javadoc 里补一段说明 `allowedDomains` 的双模语义（内部模式空=拒绝一切外网；jar 模式空=不限制）

**不要重写 getter/setter** —— 原文件 12 个字段的 getter/setter 逐字保留。

- [ ] **Step 6: 写 HttpStorageTest**

创建 `loom-http-core/src/test/java/cn/wubo/loom/http/core/HttpStorageTest.java`：

```java
package cn.wubo.loom.http.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HttpStorage 路径派生")
class HttpStorageTest {

    @Test
    @DisplayName("root 绝对化并规范化，子目录挂在其下")
    void derivesSubdirectories(@TempDir Path tmp) {
        HttpStorage s = new HttpStorage(tmp.resolve("a/../b"));
        assertThat(s.root()).isEqualTo(tmp.resolve("b").toAbsolutePath().normalize());
        assertThat(s.profilesDir()).isEqualTo(s.root().resolve("profiles"));
        assertThat(s.systemsDir()).isEqualTo(s.root().resolve("systems"));
        assertThat(s.historyDir()).isEqualTo(s.root().resolve("history"));
        assertThat(s.responsesDir()).isEqualTo(s.root().resolve("responses"));
        assertThat(s.configFile()).isEqualTo(s.root().resolve("config.json"));
        assertThat(s.logDir()).isEqualTo(s.root().resolve("log"));
        assertThat(s.auditLogFile()).isEqualTo(s.root().resolve("log").resolve("audit.log"));
    }

    @Test
    @DisplayName("两个不同 root 完全隔离 —— 双模存储隔离的基础")
    void separateRootsAreIndependent(@TempDir Path tmp) {
        HttpStorage alice = new HttpStorage(tmp.resolve("users/alice/http"));
        HttpStorage bob = new HttpStorage(tmp.resolve("users/bob/http"));
        assertThat(alice.profilesDir()).isNotEqualTo(bob.profilesDir());
        assertThat(alice.profilesDir()).startsWith(tmp.resolve("users/alice"));
        assertThat(bob.profilesDir()).startsWith(tmp.resolve("users/bob"));
    }
}
```

- [ ] **Step 7: 运行测试**

Run: `mvn test -pl loom-http-core`
Expected: `HttpStorageTest` 2 个测试 PASS

- [ ] **Step 8: 提交**

```bash
git add pom.xml loom-http-core
git commit -m "feat(http): 新增 loom-http-core 模块骨架与存储模型

从 http-mcp 搬运 POJO/工具类到 cn.wubo.loom.http.core,零 Spring 依赖。
HttpStorage 改为构造传入(原 StorageConfig 从全局属性派生),这是内部
per-user 与 jar 扁平两种模式的分界点。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: 搬运存储服务层

**Files:**
- Create: `loom-http-core/src/main/java/cn/wubo/loom/http/core/profile/ProfileService.java`
- Create: `.../system/SystemService.java`、`.../system/OpenApiCache.java`
- Create: `.../endpoint/EndpointService.java`、`.../history/HistoryService.java`
- Create: `.../invoke/EndpointMerger.java`
- Test: 对应 6 个测试类

**Interfaces:**
- Consumes: `HttpStorage`、`HttpConfig`（Task 1）
- Produces:
  ```java
  public ProfileService(HttpStorage storage, HttpConfig config);
  public SystemService(HttpStorage storage, HttpConfig config);
  public EndpointService(SystemService systemService);
  public HistoryService(HttpStorage storage, HttpConfig config);
  public OpenApiCache(HttpStorage storage, RestClient restClient);
  public EndpointMerger();  // 无状态
  ```

  **注意**：`EndpointService` 只收 `SystemService` 一个参数（无 storage / config），
  `BatchService` 第二参是 `StorageConfig`（见 Task 3）。Task 4 组装 `HttpEngine` 时
  按此签名调用。

- [ ] **Step 1: 搬运五个服务类**

```bash
cd "C:/developer/IdeaProjects/spring-ai-loom-agent"
SRC="C:/developer/IdeaProjects/http-mcp/src/main/java/cn/wubo/http/mcp"
DST="loom-http-core/src/main/java/cn/wubo/loom/http/core"
cp "$SRC/profile/ProfileService.java"    "$DST/profile/"
cp "$SRC/system/SystemService.java"      "$DST/system/"
cp "$SRC/system/OpenApiCache.java"       "$DST/system/"
cp "$SRC/endpoint/EndpointService.java"  "$DST/endpoint/"
cp "$SRC/history/HistoryService.java"    "$DST/history/"
cp "$SRC/invoke/EndpointMerger.java"     "$DST/invoke/"
```

- [ ] **Step 2: 改包声明与类型名**

```bash
cd loom-http-core/src/main/java
grep -rl "cn.wubo.http.mcp" . | xargs sed -i 's/cn\.wubo\.http\.mcp/cn.wubo.loom.http.core/g'
grep -rl "StorageConfig" . | xargs sed -i 's/\bStorageConfig\b/HttpStorage/g'
grep -rl "GlobalConfig" . | xargs sed -i 's/\bGlobalConfig\b/HttpConfig/g'
```

- [ ] **Step 3: 补跨包 import**

`HttpStorage` / `HttpConfig` 在根包，子包引用需补 import。逐文件用 Edit 补：

| 文件 | 需补 |
|---|---|
| `profile/ProfileService.java` | `import cn.wubo.loom.http.core.HttpStorage;` + `import cn.wubo.loom.http.core.HttpConfig;` |
| `system/SystemService.java` | 同上 |
| `system/OpenApiCache.java` | `import cn.wubo.loom.http.core.HttpStorage;` |
| `endpoint/EndpointService.java` | 两个 import + `import cn.wubo.loom.http.core.system.SystemService;` |
| `history/HistoryService.java` | 两个 import |

（若文件已 import 了原 `cn.wubo.http.mcp.config.StorageConfig`，第 2 步的 sed 已把它改成了 `cn.wubo.loom.http.core.config.HttpStorage` —— 需再手工改为 `cn.wubo.loom.http.core.HttpStorage`。逐个核对。）

- [ ] **Step 4: 删除 Spring 注解**

逐文件用 Edit 删除（**不用 sed**，避免误伤 javadoc 文字）：

| 文件 | 删除内容 |
|---|---|
| `profile/ProfileService.java` | `@Service` 及其 import |
| `system/SystemService.java` | `@Service` 及其 import |
| `system/OpenApiCache.java` | `@Service` 及其 import |
| `endpoint/EndpointService.java` | `@Service` 及其 import |
| `history/HistoryService.java` | `@Service` 及其 import |
| `invoke/EndpointMerger.java` | `@Component` 及其 import |

- [ ] **Step 5: 搬运对应测试**

```bash
cd "C:/developer/IdeaProjects/spring-ai-loom-agent"
SRC="C:/developer/IdeaProjects/http-mcp/src/test/java/cn/wubo/http/mcp"
DST="loom-http-core/src/test/java/cn/wubo/loom/http/core"
mkdir -p "$DST"/{profile,system,endpoint,history,invoke,util,security}
cp "$SRC/profile/ProfileServiceTest.java"   "$DST/profile/"
cp "$SRC/system/SystemServiceTest.java"     "$DST/system/"
cp "$SRC/system/OpenApiCacheTest.java"      "$DST/system/"
cp "$SRC/endpoint/EndpointServiceTest.java" "$DST/endpoint/"
cp "$SRC/history/HistoryServiceTest.java"   "$DST/history/"
cp "$SRC/invoke/EndpointMergerTest.java"    "$DST/invoke/"
cp "$SRC/util/JsonMappersTest.java"         "$DST/util/"
cp "$SRC/util/PathTemplaterTest.java"       "$DST/util/"
cp "$SRC/security/DomainWhitelistTest.java" "$DST/security/"
cp "$SRC/security/SensitiveFieldMaskerTest.java" "$DST/security/"
cp "$SRC/config/GlobalConfigTest.java"      "$DST/"
cd loom-http-core/src/test/java
grep -rl "cn.wubo.http.mcp" . | xargs sed -i 's/cn\.wubo\.http\.mcp/cn.wubo.loom.http.core/g'
grep -rl "StorageConfig" . | xargs sed -i 's/\bStorageConfig\b/HttpStorage/g'
grep -rl "GlobalConfig" . | xargs sed -i 's/\bGlobalConfig\b/HttpConfig/g'
mv "$DST/GlobalConfigTest.java" "$DST/HttpConfigTest.java"
```

- [ ] **Step 6: 修正测试里的构造调用与 import**

测试中的 `new StorageConfig(dir)` → `new HttpStorage(dir)`；`new GlobalConfig()` → `new HttpConfig()`。跨包引用同样需补 import。

Run 以列出全部编译错误，逐个修到通过：
```bash
mvn -q test-compile -pl loom-http-core
```

- [ ] **Step 7: 运行测试**

Run: `mvn test -pl loom-http-core`
Expected: 全部 PASS

若 `StorageConfigTest` 有依赖 `MCP_STORAGE_DIR` 环境变量的用例 —— 该测试**留在 jar 侧**（Task 10）不搬入 core，因为 core 已无环境变量覆盖语义。core 不搬 `config/StorageConfigTest.java`。

- [ ] **Step 8: 提交**

```bash
git add loom-http-core
git commit -m "refactor(http): 搬运存储服务层到 loom-http-core,去 Spring 注解

ProfileService/SystemService/EndpointService/HistoryService/OpenApiCache
五个类的构造器本就是参数化的,删除 @Service 即可,无需改造。
搬运对应测试并把 StorageConfig/GlobalConfig 更名为 HttpStorage/HttpConfig。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```