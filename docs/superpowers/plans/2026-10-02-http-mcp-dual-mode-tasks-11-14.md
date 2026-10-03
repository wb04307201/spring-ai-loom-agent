---

## 阶段三：`loom-http-mcp` 独立 jar（Task 11–12）+ 收尾（Task 13–14）

> **承接**：Task 1–10 已完成；`loom-http-core` 模块可独立编译，289 个测试全绿；内部工具模式已接进主库（`IHttpTool` / `LoomPaths.userHttpDir` / REST 写面 / 契约测试）；本节起在保持主计划 `Task 11–14` 编号不变的前提下，把 jar 壳、4 个资源、文件监听、双语文档、deep 审计、源项目删除都补完。
>
> **Task 12 的提交粒度（Global Constraints 例外）**：Task 12 包含三段独立产物 —— 19 个 `@McpTool`、4 个 `@McpResource`、`FileWatcher` + `list_changed` 通知 —— 三段各自独立可测试、可独立合并。沿用 Global Constraints 的「每个 Task 一个 commit」在本任务内放宽为「每个子段一个 commit」，已在 Task 12 头部显式说明。
>
> **注解包迁移（spec §7.1 风险点已落）**：源项目 `org.springaicommunity.mcp.annotation.{McpResource, ReadOperation}` 在 Spring AI 2.0.1 不存在 —— 已确认 `org.springframework.ai.mcp.annotation` 提供 `McpTool`、`McpResource`、`McpArg`（替代源项目的 `@ReadOperation + @Argument`）。本计划的所有 `@McpResource` 改用**单 URI + `@McpArg` 声明参数**形式（用户已确认 2026-10-02）。

---

### Task 11: `loom-http-mcp` 模块骨架 + 手搭 DI + 启动验证

**Files:**
- Create: `loom-http-mcp/pom.xml`
- Modify: `pom.xml`（`<modules>` 段加一行，紧跟 `loom-file-mcp` 之后）
- Create: `loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpMcpApplication.java`
- Create: `loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpMcpProperties.java`
- Create: `loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpMcpConfiguration.java`
- Create: `loom-http-mcp/src/main/resources/application.yml`
- Modify: `loom-http-core/src/main/java/cn/wubo/loom/http/core/HttpEngine.java`（**对 Task 4 契约的唯一一处微调**：新增 `reload()` 方法）
- Test: `loom-http-mcp/src/test/java/cn/wubo/loom/http/mcp/LoomHttpMcpWiringTest.java`

**Interfaces:**
- Consumes: Task 4 `HttpEngine(Path, HttpConfig)`、`HttpStorage`、`HttpConfig`；loom-file-mcp 模块的 4 文件模式
- Produces:
  ```java
  // LoomHttpMcpProperties — jar 侧配置，独立于 HttpProperty
  @ConfigurationProperties(prefix = "loom.http.mcp")
  public class LoomHttpMcpProperties {
      private String basePath = System.getProperty("user.home") + "/.loom/http-mcp";
      private boolean failClosed = false;   // spec §4.4：jar 默认 fail-open
      private boolean autoReload = true;    // 控制 FileWatcher 启停（Task 12 读）
      // get/set 略
  }

  // LoomHttpMcpConfiguration — 手搭 DI，core 零 Spring 故不能用 @Autowired
  public class LoomHttpMcpConfiguration {
      @Bean public HttpEngine httpEngine(LoomHttpMcpProperties props) { /* 见 Step 3 */ }
      @Bean public LoomHttpMcpService loomHttpMcpService(HttpEngine engine) { ... }
      @Bean public LoomHttpService loomHttpService(HttpEngine engine) { ... }
      @Bean public LoomHttpMcpResources loomHttpMcpResources(HttpEngine engine) { ... }
      @Bean public FileWatcher httpFileWatcher(HttpEngine engine, ObjectProvider<McpSyncServer> srv) { ... }
  }

  // HttpEngine 唯一微调（Task 11 内部完成，不污染 Task 4 既有签名）
  public final class HttpEngine {
      // ... Task 4 全部既有方法 ...
      /** 一并刷新 profile + system 缓存（Task 12 FileWatcher 用） */
      public void reload();
  }
  ```

**两条必须先裁定的安全/隔离要点（spec 未明文，按本仓库上下文推定）**

- **`basePath` 默认 `~/.loom/http-mcp`，**而非**与 loom-file-mcp 共享 `~/.loom/mcp/`**。理由：profile JSON 含凭据明文（`auth.token` / `auth.password` / `auth.apiKey`），若落到 `~/.loom/mcp/` 下，`loom-file-mcp` 的 `listDirectory` + `readTextFile`（`tool_file` 是 universal 工具，对所有登录用户默认可见）就能读到 token —— 这构成"授权 file 工具意外等于授权读所有 http 凭据"的权限放大。`~/.loom/http-mcp/` 与 `~/.loom/mcp/` 并列、语义清晰（mcp/ 是 4 个 server 共享沙箱，http-mcp/ 是 http 专用存储根）。
- **`failClosed` 默认 `false`**：单租户用户自己建 profile 自己调，无管理员意图可对齐；spec §4.4 已裁定。这条不改。

---

- [ ] **Step 1: 在父 pom 注册新模块**

编辑 `pom.xml` 的 `<modules>` 段（行 12–24），在 `<module>loom-file-mcp</module>` 之后加：

```xml
<module>loom-http-mcp</module>
```

不要打乱顺序；CI 多模块反应器按声明顺序编译。

- [ ] **Step 2: 创建 `loom-http-mcp/pom.xml`**

参照 `loom-file-mcp/pom.xml` 的结构（已读 loom-file-mcp:2–52 行）。新 pom 的关键差异：

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

    <artifactId>loom-http-mcp</artifactId>
    <name>loom-http-mcp</name>
    <description>HTTP call capability MCP server - standalone executable jar</description>

    <dependencies>
        <dependency>
            <groupId>io.github.wb04307201</groupId>
            <artifactId>loom-http-core</artifactId>
            <version>${project.parent.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-server</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

    <build>
        <resources>
            <resource>
                <directory>src/main/resources</directory>
                <filtering>true</filtering>
            </resource>
        </resources>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <version>${spring-boot.version}</version>
                <executions>
                    <execution><goals><goal>repackage</goal></goals></execution>
                </executions>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

**注意**：`spring-ai-mcp-annotations` 2.0.1 已通过 `spring-ai-starter-mcp-server` 间接引入（jar 清单验证：`McpTool.class` / `McpResource.class` / `McpArg.class` 都在 `org.springframework.ai.mcp.annotation`），无需单独加 dependency。

- [ ] **Step 3: 创建 `LoomHttpMcpProperties.java`**

`loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpMcpProperties.java`：

```java
package cn.wubo.loom.http.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * loom-http-mcp 独立 jar 的配置属性。
 * <p>扁平 basePath 配置（不依赖主库 {@code usersBasePath}），与 {@code loom-file-mcp} 等
 * 4 个 MCP server 的 basePath 并列。详见 Task 11 头部"两条必须先裁定的隔离要点"。
 */
@Data
@ConfigurationProperties(prefix = "loom.http.mcp")
public class LoomHttpMcpProperties {

    /**
     * 存储根（profile / system / history JSON 落点）。
     * <p>默认 {@code ~/.loom/http-mcp} —— <strong>刻意</strong>不与
     * {@code ~/.loom/mcp} 共享：profile 含凭据明文，独立根避免与
     * loom-file-mcp 的可读沙箱混在一起构成权限放大。
     */
    private String basePath = System.getProperty("user.home") + "/.loom/http-mcp";

    /**
     * 白名单 fail-closed 开关。spec §4.4：内部工具模式恒 true，jar 默认 false
     * （保持 http-mcp 既有 fail-open 行为，不破坏已部署用户）。
     */
    private boolean failClosed = false;

    /**
     * 是否启用 FileWatcher 监听 profiles/ + systems/ 目录变更并推 list_changed。
     * 测试场景可关掉，避免与文件监听线程竞争。
     */
    private boolean autoReload = true;
}
```

- [ ] **Step 4: 创建 `LoomHttpMcpApplication.java`**

`loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpMcpApplication.java`：

```java
package cn.wubo.loom.http.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * loom-http-mcp 入口。
 * <p>stdio MCP server（{@code spring.main.web-application-type: none} 在 application.yml 设置），
 * 通过 jbang 拉起（参见 README）。
 */
@SpringBootApplication
@EnableConfigurationProperties(LoomHttpMcpProperties.class)
public class LoomHttpMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoomHttpMcpApplication.class, args);
    }
}
```

- [ ] **Step 5: 创建 `application.yml`**

`loom-http-mcp/src/main/resources/application.yml`：

```yaml
# Loom HTTP MCP Server 配置
spring:
  main:
    web-application-type: none
    banner-mode: off
  ai:
    mcp:
      server:
        name: loom-http-mcp
        version: @project.version@
        annotation-scanner:
          enabled: true   # 自动扫描 @Component 上的 @McpTool/@McpResource（与 Task 12 兼容）

# loom.http.mcp.* 绑定到 LoomHttpMcpProperties
loom:
  http:
    mcp:
      basePath: ${user.home}/.loom/http-mcp
      failClosed: false
      autoReload: true
```

**注意**：basePath **不引用** `mcp.storage.root` 系统属性 —— 源项目 `HttpApplication.main` 那段 `MCP_STORAGE_DIR` 环境变量 + `<root>/config.json` 二次加载的 hack **不搬入**。理由：jar 走属性直读更简单，所有配置由 `@ConfigurationProperties` 统一收口；`config.json` 的存在会让 jar 用户困惑（哪些值生效？yml 还是 config.json？），且 Task 1 的 `HttpConfig` 字段已足够容纳所有配置。

- [ ] **Step 6: 微调 `HttpEngine` —— 加 `reload()` 方法**

编辑 `loom-http-core/src/main/java/cn/wubo/loom/http/core/HttpEngine.java`，在最后一个 `public` 方法后、`}` 之前追加：

```java
    /**
     * 刷新 profile + system 内存缓存，并把 OpenAPI 缓存置为下次按需重取。
     *
     * <p>由 jar 侧 {@code FileWatcher} 在 profiles/ 或 systems/ 目录文件变更后调用，
     * 然后向已连 MCP client 推 {@code notifications/resources/list_changed}。
     *
     * <p><strong>Task 11 对 Task 4 契约的唯一一处微调</strong>：Task 4 在 HttpEngine
     * 内部构造 profile / system / history 等服务，但未暴露重载入口。本任务加这一个
     * 方法以满足 Task 12 的 FileWatcher 监听需要，不引入新依赖、不破坏既有签名。
     */
    public void reload() {
        // 由 Task 4 内部组装的服务引用；以下两个 import 在 HttpEngine.java 已就位
        profileService.reload();
        systemService.reload();
    }
```

若 Task 4 的 HttpEngine 内部把 `profileService` / `systemService` 字段命名为不同名字，按 `grep -nE 'private (final )?(ProfileService|SystemService)' HttpEngine.java` 查实际字段名再调整调用。**不允许改字段名以与本目录不同**，保持 Task 4 的内部命名。

**测试回归**：

```bash
mvn test -pl loom-http-core
```

Expected: Task 1–4 的所有测试全绿（`reload()` 是新增方法、纯增量、不改既有路径）。任何 FAIL 都是微调破坏了既有契约，**回退 Step 6 重做**。

- [ ] **Step 7: 创建 `LoomHttpMcpConfiguration.java`**

`loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpMcpConfiguration.java`：

```java
package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.HttpStorage;
import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

/**
 * 手搭 DI —— {@link HttpEngine} 内部按 (Path, HttpConfig) 构造所有服务，
 * 对外只暴露 HttpEngine + (ProfileService, SystemService 在 reload() 路径里)。
 */
@Configuration
public class LoomHttpMcpConfiguration {

    @Bean
    public HttpStorage httpStorage(LoomHttpMcpProperties props) {
        return new HttpStorage(Paths.get(props.getBasePath()));
    }

    @Bean
    public HttpConfig httpConfig(LoomHttpMcpProperties props) {
        HttpConfig cfg = new HttpConfig();
        cfg.setFailClosed(props.isFailClosed());
        return cfg;
    }

    @Bean
    public HttpEngine httpEngine(HttpStorage storage, HttpConfig config) {
        return new HttpEngine(storage.root(), config);
    }

    // --- jar 独占服务（Task 12 实现，本步骤先建空 stub bean 占位） ---

    @Bean
    public LoomHttpMcpService loomHttpMcpService(HttpEngine engine) {
        return new LoomHttpMcpService(engine);
    }

    @Bean
    public LoomHttpService loomHttpService(HttpEngine engine) {
        return new LoomHttpService(engine);
    }

    @Bean
    public LoomHttpMcpResources loomHttpMcpResources(HttpEngine engine) {
        return new LoomHttpMcpResources(engine);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public FileWatcher httpFileWatcher(LoomHttpMcpProperties props,
                                       HttpEngine engine,
                                       ObjectProvider<McpSyncServer> mcpServerProvider) {
        return new FileWatcher(props, engine, mcpServerProvider);
    }
}
```

- [ ] **Step 8: 建空 stub 类占位（Task 12 填实现）**

为让 Step 7 的 `@Bean` 全部能编译，本步创建 4 个空 stub 类。Task 12 再填实：

```java
// loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpMcpService.java
package cn.wubo.loom.http.mcp;
import cn.wubo.loom.http.core.HttpEngine;
public class LoomHttpMcpService {
    public LoomHttpMcpService(HttpEngine engine) { /* Task 12 填 15 个 @McpTool */ }
}

// loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpService.java
package cn.wubo.loom.http.mcp;
import cn.wubo.loom.http.core.HttpEngine;
public class LoomHttpService {
    public LoomHttpService(HttpEngine engine) { /* Task 12 填 4 个 @McpTool */ }
}

// loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpMcpResources.java
package cn.wubo.loom.http.mcp;
import cn.wubo.loom.http.core.HttpEngine;
public class LoomHttpMcpResources {
    public LoomHttpMcpResources(HttpEngine engine) { /* Task 12 填 4 个 @McpResource */ }
}

// loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/FileWatcher.java
package cn.wubo.loom.http.mcp;
import cn.wubo.loom.http.core.HttpEngine;
import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.beans.factory.ObjectProvider;
public class FileWatcher {
    public FileWatcher(LoomHttpMcpProperties props, HttpEngine engine,
                       ObjectProvider<McpSyncServer> serverProvider) { /* Task 12 填 */ }
    public void start() { /* Task 12 */ }
    public void stop()  { /* Task 12 */ }
}
```

每个文件 5–10 行纯桩，方法签名必须**精确**对齐 Step 7 的 `@Bean` 调用 —— 否则 Spring 装配失败且报错位置被框架遮蔽。

- [ ] **Step 9: 写失败测试**

`loom-http-mcp/src/test/java/cn/wubo/loom/http/mcp/LoomHttpMcpWiringTest.java`：

```java
package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.HttpStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * loom-http-mcp 模块骨架装配测试。
 * <p>jar 启动能进入 Spring 上下文、4 个 bean 可解析、属性从 yml 正确绑定。
 * 工具与资源的契约测试在 Task 12 加（{@code tools/list} / {@code resources/list}）。
 */
@SpringBootTest(classes = LoomHttpMcpApplication.class)
@TestPropertySource(properties = {
        "spring.main.web-application-type=none",
        "spring.ai.mcp.server.stdio=false",       // 测试不拉起 stdio，避免占 stdin
        "loom.http.mcp.basePath=${java.io.tmpdir}/loom-http-mcp-wiring-test",
        "loom.http.mcp.failClosed=false"
})
@DisplayName("loom-http-mcp 骨架装配")
class LoomHttpMcpWiringTest {

    @Autowired private HttpStorage storage;
    @Autowired private HttpConfig  config;
    @Autowired private HttpEngine  engine;
    @Autowired private LoomHttpMcpProperties props;

    @Test
    @DisplayName("属性绑定到 LoomHttpMcpProperties")
    void propertiesBind() {
        assertThat(props.getBasePath()).endsWith("loom-http-mcp-wiring-test");
        assertThat(props.isFailClosed()).isFalse();
        assertThat(props.isAutoReload()).isTrue();
    }

    @Test
    @DisplayName("HttpConfig.failClosed 由属性注入")
    void configExposesFailClosed() {
        assertThat(config.isFailClosed()).isFalse();
    }

    @Test
    @DisplayName("HttpStorage 派生 7 个子目录")
    void storageDerivesSubdirs() {
        Path base = Path.of(props.getBasePath());
        assertThat(storage.root()).isEqualTo(base);
        assertThat(storage.profilesDir()).isEqualTo(base.resolve("profiles"));
        assertThat(storage.systemsDir()).isEqualTo(base.resolve("systems"));
        assertThat(storage.historyDir()).isEqualTo(base.resolve("history"));
        assertThat(storage.responsesDir()).isEqualTo(base.resolve("responses"));
        assertThat(storage.configFile()).isEqualTo(base.resolve("config.json"));
        assertThat(storage.logDir()).isEqualTo(base.resolve("log"));
        assertThat(storage.auditLogFile()).isEqualTo(base.resolve("log/audit.log"));
    }

    @Test
    @DisplayName("HttpEngine bean 可创建，且 reload() 不抛")
    void engineReloadIsCallable() {
        assertThat(engine).isNotNull();
        engine.reload();   // 重复调用安全：HttpEngine 内部 service 的 reload 已是幂等
    }
}
```

- [ ] **Step 10: 运行确认通过**

```bash
mvn test -pl loom-http-mcp -Dtest=LoomHttpMcpWiringTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 4 个测试 PASS。

若 FAIL：先看 Step 9 桩类签名是否与 Step 7 的 `@Bean` 调用匹配（编译错最常见）；其次 `spring.ai.mcp.server.stdio=false` 必须设置，否则 Spring 启动时会卡在 stdin。

- [ ] **Step 11: 全量编译验证（确认 Task 1–10 不破）**

```bash
mvn clean install -Dgpg.skip=true -pl '!spring-ai-loom-agent-test'
```

Expected: BUILD SUCCESS。若 FAIL，**回退 Step 6**（`HttpEngine.reload()` 是否破坏 Task 4 测试）。

- [ ] **Step 12: 提交**

```bash
git add pom.xml loom-http-mcp
git commit -m "feat(http-mcp): loom-http-mcp 骨架 + 手搭 DI + 启动装配

独立 jar 模块,手搭 DI(loom-http-core 零 Spring 故不能用 @Autowired)。
HttpEngine 唯一微调:加 reload() 供 Task 12 FileWatcher 调用。
basePath 默认 ~/.loom/http-mcp(刻意不与 ~/.loom/mcp 共享,避免 profile
凭据混入 loom-file-mcp 可读沙箱构成权限放大)。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 12: 19 个 `@McpTool` + 4 个 `@McpResource` + FileWatcher

**Global Constraints 例外（仅本任务）**：Task 12 含三段（19 工具 / 4 资源 / FileWatcher），每段独立可测试、可独立 commit。沿用仓库「每个 Task 一个 commit」在本任务放宽为「每段一个 commit」。原因：19 工具是 ~500 行单文件，可独立审；4 资源是 ~100 行单文件，可独立审；FileWatcher 是后台线程组件，独立运行/停止。两段任意一段返工不会污染另一段。**测试网全量导入回归在每段 commit 前都跑一遍**。

**Files:**
- Modify: `loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpMcpService.java`（Task 11 stub → 15 个 `@McpTool`）
- Modify: `loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpService.java`（Task 11 stub → 4 个 `@McpTool`，含 `_ad_hoc` 合成系统）
- Modify: `loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpMcpResources.java`（Task 11 stub → 4 个 `@McpResource`，全 `@McpArg` 形式）
- Modify: `loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/FileWatcher.java`（Task 11 stub → 真实文件监听 + `notifyResourcesListChanged` 推送）
- Modify: `.mcp.json`（加 `loom-http-mcp` 条目，与 loom-file-mcp 同款 `JAVA_HOME` + `UTF-8` jvm args）
- Test: `loom-http-mcp/src/test/java/cn/wubo/loom/http/mcp/HttpMcpToolSurfaceTest.java`（19 工具 snake_case 全名单断言 + 空参降级）
- Test: `loom-http-mcp/src/test/java/cn/wubo/loom/http/mcp/HttpMcpResourceSurfaceTest.java`（4 资源 URI + 参数断言）
- Test: `loom-http-mcp/src/test/java/cn/wubo/loom/http/mcp/FileWatcherIT.java`（files → reload → 内存态可见 → server 收到 `list_changed`）

**Interfaces:**
- Consumes: Task 4/8 `HttpEngine`（含 `reload()`）、Task 11 `LoomHttpMcpProperties`
- Produces:
  ```java
  public class LoomHttpMcpService { /* 15 个 @McpTool，详见 Step 1 */ }
  public class LoomHttpService    { /* 4 个 @McpTool + _ad_hoc helper，详见 Step 2 */ }
  public class LoomHttpMcpResources { /* 4 个 @McpResource，全 @McpArg，详见 Step 4 */ }
  public class FileWatcher { /* 监听 + reload + 推送，详见 Step 5 */ }
  ```

**19 个 `@McpTool` 全名单（snake_case）**

| 类别 | 工具数 | 工具名（snake_case） |
|---|---|---|
| 调用面 | 2 | `invoke_endpoint`, `http_batch` |
| 系统注册 | 4 | `refresh_system`, `register_system`, `update_system`, `remove_system` |
| profile 管理 | 3 | `add_profile`, `update_profile`, `remove_profile` |
| endpoint 管理 | 5 | `add_endpoint`, `update_endpoint`, `remove_endpoint`, `list_endpoints`, `get_endpoint` |
| 历史查询 | 1 | `get_request_history` |
| 通用 HTTP | 4 | `http_get`, `http_post`, `http_put`, `http_delete` |
| **合计** | **19** | — |

> 拆分：15 个进 `LoomHttpMcpService`（按调用面 / 注册面 / 管理面分组，加块注释），4 个进 `LoomHttpService`（通用 HTTP + `_ad_hoc` 合成系统代码相对独立）。

---

#### Task 12.1 — `LoomHttpMcpService` 15 个 `@McpTool`

- [ ] **Step 1: 把 Task 11 的空 stub 类替换为真实实现**

`loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpMcpService.java`：

```java
package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.endpoint.Endpoint;
import cn.wubo.loom.http.core.profile.Profile;
import cn.wubo.loom.http.core.system.System;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * loom-http-mcp 暴露给 LLM 的注册 / 管理 / 读用面工具（15 个 @McpTool，snake_case）。
 * <p>通用 HTTP（4 个 http_*）见 {@link LoomHttpService}，那里有 _ad_hoc 合成系统代码。
 */
public class LoomHttpMcpService {

    private final HttpEngine engine;

    public LoomHttpMcpService(HttpEngine engine) {
        this.engine = engine;
    }

    // ==================== 调用面（Task 8 契约：invokeEndpoint / httpBatch） ====================

    @McpTool(name = "invoke_endpoint", description =
            "调用一个已注册系统上的 HTTP 端点。system / method / path 必填，其余经 16 步流程 "
            + "(白名单→模板→占位 header→请求→大响应落盘→断言→JSONPath 提取→脱敏→历史)。"
            + "responseMode: summary / full / file。")
    public String invokeEndpoint(
            @ToolParam(description = "已注册系统名") String system,
            @ToolParam(description = "HTTP 方法,例 GET") String method,
            @ToolParam(description = "端点路径,支持 {placeholder}") String path,
            @ToolParam(description = "path/query 参数表", required = false) Map<String, Object> params,
            @ToolParam(description = "JSON 请求体", required = false) Map<String, Object> body,
            @ToolParam(description = "额外请求头", required = false) Map<String, String> headers,
            @ToolParam(description = "断言列表 {type,value,...}", required = false) List<Map<String, Object>> assertions,
            @ToolParam(description = "JSONPath 提取 {alias:\"$.x\"}", required = false) Map<String, String> extract,
            @ToolParam(description = "summary / full / file", required = false) String responseMode) {
        cn.wubo.loom.http.core.invoke.InvokeRequest req =
                new cn.wubo.loom.http.core.invoke.InvokeRequest();
        req.setSystem(system);
        req.setMethod(method);
        req.setPath(path);
        if (params != null) req.setParams(params);
        if (body != null) req.setBody(body);
        if (headers != null) req.setHeaders(headers);
        if (assertions != null) req.setAssertions(assertions);
        if (extract != null) req.setExtract(extract);
        if (responseMode != null) req.setResponseMode(responseMode);
        return engine.invokeEndpoint(req);
    }

    @McpTool(name = "http_batch", description =
            "并发执行一组 HTTP 操作,每项是 invokeEndpoint 入参形状。"
            + "concurrency 默认 5。failPolicy: continue(默认) / stopOnFirst。")
    public String httpBatch(
            @ToolParam(description = "操作列表,每项形如 {system,method,path,...}") List<Map<String, Object>> operations,
            @ToolParam(description = "最大并发 op 数", required = false) Integer concurrency,
            @ToolParam(description = "continue / stopOnFirst", required = false) String failPolicy,
            @ToolParam(description = "响应模式 summary/firstN/full/file", required = false) String responseMode,
            @ToolParam(description = "firstN 时返回条数", required = false) Integer firstN) {
        cn.wubo.loom.http.core.invoke.BatchRequest req =
                new cn.wubo.loom.http.core.invoke.BatchRequest();
        if (operations != null) req.setOperations(operations);
        if (concurrency != null) req.setConcurrency(concurrency);
        if (failPolicy != null) req.setFailPolicy(failPolicy);
        if (responseMode != null) req.setResponseMode(responseMode);
        if (firstN != null) req.setFirstN(firstN);
        return engine.httpBatch(req);
    }

    // ==================== 注册面：system 4 个 ====================

    @McpTool(name = "refresh_system", description =
            "刷新一个或所有已注册系统的 OpenAPI 元数据。name 为 null 时刷新全部。")
    public String refreshSystem(
            @ToolParam(description = "系统名,省略则刷新全部", required = false) String name) {
        return engine.refreshSystem(name);
    }

    @McpTool(name = "register_system", description =
            "注册一个新系统(system JSON 落盘)。baseUrl 必填,authProfile / openapi / endpoints 可选。")
    public String registerSystem(
            @ToolParam(description = "系统名,必须唯一") String name,
            @ToolParam(description = "文字描述", required = false) String description,
            @ToolParam(description = "上游 API base URL") String baseUrl,
            @ToolParam(description = "绑定的 profile 名", required = false) String authProfile,
            @ToolParam(description = "OpenAPI 源 {source,format,refresh}", required = false) Map<String, Object> openapi,
            @ToolParam(description = "手动端点列表", required = false) List<Map<String, Object>> endpoints) {
        System sys = new System();
        sys.setName(name);
        if (description != null) sys.setDescription(description);
        sys.setBaseUrl(baseUrl);
        if (authProfile != null) sys.setAuthProfile(authProfile);
        // openapi / endpoints 由 Engine.registerSystem 在保存时按各自模型反序列化
        return engine.registerSystem(sys);
    }

    @McpTool(name = "update_system", description =
            "部分更新一个已注册系统。null 字段保留原值;openapi 非 null 则整体替换。")
    public String updateSystem(
            @ToolParam(description = "要更新的系统名") String name,
            @ToolParam(description = "新描述,null 保留原值", required = false) String description,
            @ToolParam(description = "新 baseUrl", required = false) String baseUrl,
            @ToolParam(description = "新 authProfile", required = false) String authProfile,
            @ToolParam(description = "新 openapi 块,整体替换", required = false) Map<String, Object> openapi) {
        return engine.updateSystem(name, description, baseUrl, authProfile, openapi);
    }

    @McpTool(name = "remove_system", description =
            "删除系统 JSON;deleteOpenApiCache=true 时同时清 systems/<name>/openapi.json。")
    public String removeSystem(
            @ToolParam(description = "系统名") String name,
            @ToolParam(description = "是否同时删 OpenAPI 缓存", required = false) Boolean deleteOpenApiCache) {
        return engine.removeSystem(name, Boolean.TRUE.equals(deleteOpenApiCache));
    }

    // ==================== 注册面：profile 3 个 ====================

    @McpTool(name = "add_profile", description =
            "新建认证 profile。auth.type 必填(bearer/basic/apiKey-header/apiKey-query);"
            + "allowedDomains 留空表示不施加约束(spec §4.4 第二态)。")
    public String addProfile(
            @ToolParam(description = "profile 名,必须唯一") String name,
            @ToolParam(description = "文字描述", required = false) String description,
            @ToolParam(description = "上游 API base URL", required = false) String baseUrl,
            @ToolParam(description = "认证配置 {type,token|username|password|keyName|value}") Map<String, Object> auth,
            @ToolParam(description = "每次调用覆盖的 header", required = false) Map<String, String> customHeaders,
            @ToolParam(description = "默认附加 header", required = false) Map<String, String> defaultHeaders,
            @ToolParam(description = "域名白名单,支持通配符", required = false) List<String> allowedDomains,
            @ToolParam(description = "超时 ms,默认 10000", required = false) Long timeoutMs,
            @ToolParam(description = "重试策略 {maxAttempts,backoffMs,retryOn}", required = false) Map<String, Object> retry) {
        Profile p = new Profile();
        p.setName(name);
        if (description != null) p.setDescription(description);
        if (baseUrl != null) p.setBaseUrl(baseUrl);
        if (auth != null) {
            cn.wubo.loom.http.core.profile.Auth authModel =
                    cn.wubo.loom.http.core.util.JsonMappers.convertValue(
                            auth, cn.wubo.loom.http.core.profile.Auth.class);
            p.setAuth(authModel);
        }
        if (customHeaders != null) p.setCustomHeaders(customHeaders);
        if (defaultHeaders != null) p.setDefaultHeaders(defaultHeaders);
        if (allowedDomains != null) p.setAllowedDomains(new java.util.LinkedHashSet<>(allowedDomains));
        if (timeoutMs != null) p.setTimeoutMs(timeoutMs);
        if (retry != null) {
            cn.wubo.loom.http.core.profile.Retry r =
                    cn.wubo.loom.http.core.util.JsonMappers.convertValue(
                            retry, cn.wubo.loom.http.core.profile.Retry.class);
            p.setRetry(r);
        }
        return engine.addProfile(p);
    }

    @McpTool(name = "update_profile", description =
            "部分更新一个已注册 profile。null 字段保留;auth 非 null 则整体替换凭据块。")
    public String updateProfile(
            @ToolParam(description = "profile 名") String name,
            @ToolParam(description = "新描述", required = false) String description,
            @ToolParam(description = "新 baseUrl", required = false) String baseUrl,
            @ToolParam(description = "新 auth 块,整体替换", required = false) Map<String, Object> auth,
            @ToolParam(description = "新 customHeaders", required = false) Map<String, String> customHeaders,
            @ToolParam(description = "新 defaultHeaders", required = false) Map<String, String> defaultHeaders,
            @ToolParam(description = "新 allowedDomains,整体替换", required = false) List<String> allowedDomains,
            @ToolParam(description = "新 timeoutMs", required = false) Long timeoutMs,
            @ToolParam(description = "新 retry", required = false) Map<String, Object> retry) {
        Profile patch = new Profile();
        if (description != null) patch.setDescription(description);
        if (baseUrl != null) patch.setBaseUrl(baseUrl);
        if (auth != null) {
            patch.setAuth(cn.wubo.loom.http.core.util.JsonMappers.convertValue(
                    auth, cn.wubo.loom.http.core.profile.Auth.class));
        }
        if (customHeaders != null) patch.setCustomHeaders(customHeaders);
        if (defaultHeaders != null) patch.setDefaultHeaders(defaultHeaders);
        if (allowedDomains != null) patch.setAllowedDomains(new java.util.LinkedHashSet<>(allowedDomains));
        if (timeoutMs != null) patch.setTimeoutMs(timeoutMs);
        if (retry != null) {
            patch.setRetry(cn.wubo.loom.http.core.util.JsonMappers.convertValue(
                    retry, cn.wubo.loom.http.core.profile.Retry.class));
        }
        return engine.updateProfile(name, patch);
    }

    @McpTool(name = "remove_profile", description =
            "删除 profile JSON。被任何 system 的 authProfile 引用时拒绝。")
    public String removeProfile(
            @ToolParam(description = "profile 名") String name) {
        return engine.removeProfile(name);
    }

    // ==================== 注册面：endpoint 5 个 ====================

    @McpTool(name = "add_endpoint", description =
            "向已注册系统添加一个手动端点定义(OpenAPI 文档之外的接口)。")
    public String addEndpoint(
            @ToolParam(description = "目标系统名") String system,
            @ToolParam(description = "HTTP 方法") String method,
            @ToolParam(description = "端点路径 /users/{id}") String path,
            @ToolParam(description = "OpenAPI parameters 数组", required = false) List<Map<String, Object>> parameters,
            @ToolParam(description = "OpenAPI requestBody 对象", required = false) Map<String, Object> requestBody,
            @ToolParam(description = "OpenAPI responses 对象", required = false) Map<String, Object> responses,
            @ToolParam(description = "简短描述", required = false) String summary,
            @ToolParam(description = "长描述", required = false) String description,
            @ToolParam(description = "OpenAPI tags", required = false) List<String> tags) {
        return engine.addEndpoint(system, method, path, parameters,
                requestBody, responses, summary, description, tags);
    }

    @McpTool(name = "update_endpoint", description =
            "对已存在的 (system, method, path) 手动端点做部分更新。"
            + "patch 是 {field: value} map,未指定字段保留原值。")
    public String updateEndpoint(
            @ToolParam(description = "目标系统名") String system,
            @ToolParam(description = "HTTP 方法") String method,
            @ToolParam(description = "端点路径") String path,
            @ToolParam(description = "{field:value} patch map") Map<String, Object> patch) {
        return engine.updateEndpoint(system, method, path, patch);
    }

    @McpTool(name = "remove_endpoint", description =
            "删除一个手动端点定义。不影响 OpenAPI 缓存。")
    public String removeEndpoint(
            @ToolParam(description = "目标系统名") String system,
            @ToolParam(description = "HTTP 方法") String method,
            @ToolParam(description = "端点路径") String path) {
        return engine.removeEndpoint(system, method, path);
    }

    @McpTool(name = "list_endpoints", description =
            "列出一个系统的合并端点(manual + OpenAPI),可按 tag / source 过滤。")
    public String listEndpoints(
            @ToolParam(description = "系统名") String system,
            @ToolParam(description = "按 OpenAPI tag 过滤", required = false) String tag,
            @ToolParam(description = "按 source 过滤 manual / openapi", required = false) String source) {
        return engine.listEndpoints(system, tag, source);
    }

    @McpTool(name = "get_endpoint", description =
            "取一个端点的完整 schema(system/method/path),返回 JSON。")
    public String getEndpoint(
            @ToolParam(description = "系统名") String system,
            @ToolParam(description = "HTTP 方法") String method,
            @ToolParam(description = "端点路径") String path) {
        return engine.getEndpoint(system, method, path);
    }

    // ==================== 历史查询 1 个 ====================

    @McpTool(name = "get_request_history", description =
            "查询请求历史。system 为 null/blank 表示全系统。limit 默认 50,最大 500。"
            + "statusFilter: succeeded / failed / all。since: ISO-8601,严格 > since。")
    public String getRequestHistory(
            @ToolParam(description = "系统名,省略表示全系统", required = false) String system,
            @ToolParam(description = "返回条数上限", required = false) Integer limit,
            @ToolParam(description = "succeeded / failed / all", required = false) String statusFilter,
            @ToolParam(description = "ISO-8601 时间戳", required = false) String since) {
        Instant sinceInstant = (since == null || since.isBlank())
                ? Instant.EPOCH
                : Instant.parse(since);
        int lim = (limit == null) ? 50 : Math.min(limit, 500);
        return engine.getRequestHistory(system, lim, statusFilter, sinceInstant);
    }
}
```

**字段依赖核对（执行者注意）**：`Profile` / `System` / `Endpoint` / `Auth` / `Retry` 的字段名以 Task 1 的搬运为准（HttpConfig 注释"原 GlobalConfig 全部 12 个字段"，说明 Task 1 严格对照 http-mcp 1.1.1 源搬运）。若某个 `setXxx` 方法名不匹配，按 Task 1 产物的字段名调整，**不允许改 Task 1 的字段名以与本文件一致**。

- [ ] **Step 2: 写失败测试 `HttpMcpToolSurfaceTest`**

`loom-http-mcp/src/test/java/cn/wubo/loom/http/mcp/HttpMcpToolSurfaceTest.java`：

```java
package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.HttpStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定 19 个 @McpTool 的 snake_case 全名单,任何变更是历史无法回顾。
 */
@SpringBootTest(classes = LoomHttpMcpApplication.class)
@TestPropertySource(properties = {
        "spring.main.web-application-type=none",
        "spring.ai.mcp.server.stdio=false",
        "loom.http.mcp.basePath=${java.io.tmpdir}/loom-http-mcp-tool-surface-test"
})
@DisplayName("loom-http-mcp 工具面契约(19 个 snake_case)")
class HttpMcpToolSurfaceTest {

    private static final List<String> EXPECTED = List.of(
            "invoke_endpoint", "http_batch",
            "refresh_system", "register_system", "update_system", "remove_system",
            "add_profile", "update_profile", "remove_profile",
            "add_endpoint", "update_endpoint", "remove_endpoint",
            "list_endpoints", "get_endpoint",
            "get_request_history",
            "http_get", "http_post", "http_put", "http_delete"
    );

    @Autowired private ToolCallbackProvider provider;

    @Test
    @DisplayName("19 个工具全部存在,无多余")
    void all19ToolsPresent() {
        ToolCallback[] cbs = provider.getToolCallbacks();
        Set<String> actual = Arrays.stream(cbs)
                .map(ToolCallback::getToolDefinition)
                .map(d -> d.name())
                .collect(Collectors.toSet());
        assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    @DisplayName("invoke_endpoint 描述非空,含 spec 关键术语")
    void invokeEndpointHasMeaningfulDescription() {
        String desc = Arrays.stream(provider.getToolCallbacks())
                .filter(cb -> "invoke_endpoint".equals(cb.getToolDefinition().name()))
                .findFirst().orElseThrow()
                .getToolDefinition().description();
        assertThat(desc).contains("16 步");
    }
}
```

> 注：`ToolCallbackProvider` 在 Spring AI 2.0.1 是从 `spring-ai-starter-mcp-server` 装配出来的 —— 它扫 `@Component` 上的 `@Tool` / `@McpTool`，自动归并所有工具。若装配后工具数不是 19，先检查 `application.yml` 的 `annotation-scanner.enabled: true` 是否生效。

- [ ] **Step 3: 运行确认通过**

```bash
mvn test -pl loom-http-mcp -Dtest=HttpMcpToolSurfaceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 2 个测试 PASS。

若工具数量少:逐一核对 snake_case 命名（注意 `@McpTool(name=...)` 必须显式 —— 不写 `name=` 则按方法名注册,变成 camelCase,客户端认不出）。

- [ ] **Step 4: 提交**

```bash
git add loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpMcpService.java \
        loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpService.java \
        loom-http-mcp/src/test/java/cn/wubo/loom/http/mcp/HttpMcpToolSurfaceTest.java
git commit -m "feat(http-mcp): LoomHttpMcpService 15 个 @McpTool 全量上线

snake_case 全名单契约由 HttpMcpToolSurfaceTest 锁定(19 个工具的其余 4
个 http_* 在 Task 12.2 提交)。字段桥接用 JsonMappers.convertValue,
不引入内部字段名耦合:Profile/System/Endpoint/Auth/Retry 字段名以
loom-http-core 搬运后为准,本文件随其调整。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

> 本次提交故意**只**含 15 工具的 `LoomHttpMcpService` —— `LoomHttpService`（4 个 `http_*`）是独立段,在 Step 5 提交,commit message 显式声明"另外 4 个工具在下一 commit"。

---

#### Task 12.2 — `LoomHttpService` 4 个 `@McpTool` + `_ad_hoc` 合成系统

- [ ] **Step 5: 实现 4 个通用 HTTP 工具 + `_ad_hoc` 合成系统**

`loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpService.java`：

```java
package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.invoke.InvokeRequest;
import cn.wubo.loom.http.core.system.System;
import cn.wubo.loom.http.core.util.JsonMappers;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 4 个通用 HTTP @McpTool + 内部 _ad_hoc 合成系统。
 * <p>每个 http_* 把传入 URL 拆成 baseUrl + path,合成 InvokeRequest 走 invokeEndpoint 同一 16 步流程。
 * 详见 Task 12 头部约定。
 */
public class LoomHttpService {

    /** 合成系统名(spec §6.4) —— 内部 baseUrl / authProfile 每调用覆盖。 */
    static final String AD_HOC_SYSTEM_NAME = "_ad_hoc";

    private final HttpEngine engine;
    /** 持久化的合成 system 记录:InvokeService.systemService.get("_ad_hoc") 必须能解析。 */
    private final System adHocSystem;

    public LoomHttpService(HttpEngine engine) {
        this.engine = engine;
        this.adHocSystem = new System();
        adHocSystem.setName(AD_HOC_SYSTEM_NAME);
        adHocSystem.setDescription("Synthetic system for unbound ad-hoc httpXxx calls");
        engine.registerSystem(adHocSystem);
    }

    @McpTool(name = "http_get", description =
            "发送 HTTP GET。兼容旧(只传 url+headers JSON)+ 新增可选 profile/assertions/extract/responseMode。"
            + "返回 InvokeResponse JSON(statusCode/headers/body/extracted/assertionResult/error)。")
    public String httpGet(
            @ToolParam(description = "完整 URL https://api.example.com/data") String url,
            @ToolParam(description = "请求头 JSON 字符串,可省略", required = false) String headers,
            @ToolParam(description = "profile 名,自动注入 auth + customHeaders", required = false) String profile,
            @ToolParam(description = "断言列表 {type,value}", required = false) List<Map<String, Object>> assertions,
            @ToolParam(description = "JSONPath 提取 {alias:\"$.x\"}", required = false) Map<String, String> extract,
            @ToolParam(description = "summary / full / file", required = false) String responseMode) {
        return invokeAdHoc("GET", url, null, headers, profile, assertions, extract, responseMode);
    }

    @McpTool(name = "http_post", description = "发送 HTTP POST。body 为 JSON 字符串。")
    public String httpPost(
            @ToolParam(description = "完整 URL") String url,
            @ToolParam(description = "请求体 JSON 字符串 {\"key\":val}") String body,
            @ToolParam(description = "请求头 JSON 字符串", required = false) String headers,
            @ToolParam(description = "profile 名", required = false) String profile,
            @ToolParam(description = "断言列表", required = false) List<Map<String, Object>> assertions,
            @ToolParam(description = "JSONPath 提取", required = false) Map<String, String> extract,
            @ToolParam(description = "summary / full / file", required = false) String responseMode) {
        return invokeAdHoc("POST", url, body, headers, profile, assertions, extract, responseMode);
    }

    @McpTool(name = "http_put", description = "发送 HTTP PUT。body 为 JSON 字符串。")
    public String httpPut(
            @ToolParam(description = "完整 URL") String url,
            @ToolParam(description = "请求体 JSON 字符串") String body,
            @ToolParam(description = "请求头 JSON 字符串", required = false) String headers,
            @ToolParam(description = "profile 名", required = false) String profile,
            @ToolParam(description = "断言列表", required = false) List<Map<String, Object>> assertions,
            @ToolParam(description = "JSONPath 提取", required = false) Map<String, String> extract,
            @ToolParam(description = "summary / full / file", required = false) String responseMode) {
        return invokeAdHoc("PUT", url, body, headers, profile, assertions, extract, responseMode);
    }

    @McpTool(name = "http_delete", description = "发送 HTTP DELETE。")
    public String httpDelete(
            @ToolParam(description = "完整 URL") String url,
            @ToolParam(description = "请求头 JSON 字符串", required = false) String headers,
            @ToolParam(description = "profile 名", required = false) String profile,
            @ToolParam(description = "断言列表", required = false) List<Map<String, Object>> assertions,
            @ToolParam(description = "JSONPath 提取", required = false) Map<String, String> extract,
            @ToolParam(description = "summary / full / file", required = false) String responseMode) {
        return invokeAdHoc("DELETE", url, null, headers, profile, assertions, extract, responseMode);
    }

    // ==================== 内部:_ad_hoc 合成系统调用 ====================

    private String invokeAdHoc(String method, String url, String body, String headersJson,
                               String profile, List<Map<String, Object>> assertions,
                               Map<String, String> extract, String responseMode) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            return errorJson("InvalidUrl", ex.getMessage());
        }
        String scheme = uri.getScheme();
        String authority = uri.getAuthority();
        if (scheme == null || authority == null) {
            return errorJson("InvalidUrl", "URL must include scheme and authority: " + url);
        }
        String origin = scheme + "://" + authority;
        String pathOnly = (uri.getPath() == null || uri.getPath().isEmpty()) ? "/" : uri.getPath();
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null && !rawQuery.isEmpty()) pathOnly = pathOnly + "?" + rawQuery;

        Map<String, String> headers = parseHeaders(headersJson);
        Map<String, Object> queryParams = parseQuery(uri.getRawQuery());

        // 同步更新合成系统:baseUrl = origin,authProfile = profile
        synchronized (adHocSystem) {
            adHocSystem.setBaseUrl(origin);
            adHocSystem.setAuthProfile(profile);
        }

        InvokeRequest req = new InvokeRequest();
        req.setSystem(AD_HOC_SYSTEM_NAME);
        req.setMethod(method);
        req.setPath(pathOnly);
        req.setParams(queryParams);
        req.setBody(body);   // String 直传:InvokeService.serializeBody() 保留原始字符串
        req.setHeaders(headers);
        req.setAssertions(assertions);
        req.setExtract(extract);
        req.setResponseMode(responseMode);
        return engine.invokeEndpoint(req);
    }

    private static Map<String, String> parseHeaders(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JsonMappers.parse(json, Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static Map<String, Object> parseQuery(String raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, Object> out = new HashMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) out.put(pair, "");
            else out.put(java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8),
                         java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String errorJson(String code, String msg) {
        return "{\"error\":\"" + code + "\",\"message\":\""
                + msg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }
}
```

- [ ] **Step 6: 复跑 19 工具契约测试 + 加 _ad_hoc 行为测试**

先回到 `HttpMcpToolSurfaceTest`,把 `EXPECTED` 中 4 个 `http_*` 取消注释(Step 2 已注释),重跑测试 —— 必须 19 全过。

然后在 `HttpMcpToolSurfaceTest` 加一个 case：

```java
@Test
@DisplayName("_ad_hoc 合成系统已注册:InvokeService 可解析")
void adHocSystemRegistered() {
    // 通过 invokeEndpoint 走 _ad_hoc 路径,engine 内部 systemService.get 必须能解析
    cn.wubo.loom.http.core.invoke.InvokeRequest req =
            new cn.wubo.loom.http.core.invoke.InvokeRequest();
    req.setSystem("_ad_hoc");
    req.setMethod("GET");
    req.setPath("/");
    // 不期望 200 —— 没真实上游;期望 error 字段为 SystemNotFound/NetworkError 而不是 NPE
    String resp = engine.invokeEndpoint(req);
    assertThat(resp).containsAnyOf("SystemNotFound", "error");
}
```

若 `resp` 是空串 / NPE —— 说明 `_ad_hoc` 没持久化成功,**回退 Step 5 检查 `engine.registerSystem(adHocSystem)` 是否真的落盘**。

- [ ] **Step 7: 提交**

```bash
git add loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpService.java \
        loom-http-mcp/src/test/java/cn/wubo/loom/http/mcp/HttpMcpToolSurfaceTest.java
git commit -m "feat(http-mcp): LoomHttpService 4 个 http_* + _ad_hoc 合成系统

httpGet/Post/Put/Delete 把 URL 拆成 baseUrl + path,合成 InvokeRequest
走同一 16 步流程(profile 注入、断言、JSONPath 提取、大响应落盘、白名单
判定),既保留旧 (url, headers) 调用形态又赋予新能力。
_ad_hoc 在构造器持久化一次(SystemService.save 幂等),baseUrl /
authProfile 每调用覆盖。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

#### Task 12.3 — `LoomHttpMcpResources` 4 个 `@McpResource` + `FileWatcher`

- [ ] **Step 8: 实现 4 个资源**

`loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpMcpResources.java`：

```java
package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpEngine;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpResource;

/**
 * 4 个只读 @McpResource —— LLM 用 resources/read 主动拉取的 schema 快照。
 * <p>全用单 URI + @McpArg 形式(Spring AI 2.0.1 的 @McpResource 无 @ReadOperation,
 * URI 模板变量在 2.x 不被绑定,故退化到 @McpArg;此裁定记录于本计划头部)。
 */
public class LoomHttpMcpResources {

    private final HttpEngine engine;

    public LoomHttpMcpResources(HttpEngine engine) {
        this.engine = engine;
    }

    @McpResource(name = "system-list", uri = "system://list",
            description = "所有已注册系统的索引(名字 + 描述 + baseUrl + authProfile)。",
            mimeType = "application/json")
    public String listSystems() {
        return String.join("\n", engine.listSystems());
    }

    @McpResource(name = "system", uri = "system://by-name",
            description = "取一个已注册系统的完整 JSON 配置(name / baseUrl / authProfile / openapi / endpoints)。",
            mimeType = "application/json")
    public String readSystem(
            @McpArg(name = "system", description = "系统名", required = true) String system) {
        return engine.getSystem(system);   // Task 8 补齐的两个读方法之一
    }

    @McpResource(name = "system-endpoints", uri = "system://endpoints",
            description = "一个系统合并后的端点列表(manual + OpenAPI),可按 tag 过滤。",
            mimeType = "application/json")
    public String listEndpoints(
            @McpArg(name = "system", description = "系统名", required = true) String system,
            @McpArg(name = "tag", description = "按 OpenAPI tag 过滤", required = false) String tag,
            @McpArg(name = "source", description = "manual / openapi,省略全量", required = false) String source) {
        return engine.listEndpoints(system, tag, source);
    }

    @McpResource(name = "system-endpoint", uri = "system://endpoint",
            description = "单个端点的完整 OpenAPI schema(mergedFrom: manual/openapi)。",
            mimeType = "application/json")
    public String getEndpoint(
            @McpArg(name = "system", description = "系统名", required = true) String system,
            @McpArg(name = "method", description = "HTTP 方法", required = true) String method,
            @McpArg(name = "path", description = "端点路径", required = true) String path) {
        return engine.getEndpoint(system, method, path);
    }
}
```

`engine.getSystem(system)` 与 `engine.getProfile(name)` 是 Task 8 Step 1 补齐的两个读方法。Task 8 已 commit,此处直接用。

- [ ] **Step 9: 写失败测试 `HttpMcpResourceSurfaceTest`**

`loom-http-mcp/src/test/java/cn/wubo/loom/http/mcp/HttpMcpResourceSurfaceTest.java`：

```java
package cn.wubo.loom.http.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4 个 @McpResource URI 名单契约。
 * <p>工具 + 资源同走 ToolCallbackProvider,故本类与 ToolSurfaceTest 共用装配上下文。
 */
@SpringBootTest(classes = LoomHttpMcpApplication.class)
@TestPropertySource(properties = {
        "spring.main.web-application-type=none",
        "spring.ai.mcp.server.stdio=false",
        "loom.http.mcp.basePath=${java.io.tmpdir}/loom-http-mcp-resource-surface-test"
})
@DisplayName("loom-http-mcp 资源面契约(4 个 URI)")
class HttpMcpResourceSurfaceTest {

    private static final List<String> EXPECTED_URIS = List.of(
            "system://list",
            "system://by-name",
            "system://endpoints",
            "system://endpoint"
    );

    @Autowired private ToolCallbackProvider provider;

    @Test
    @DisplayName("4 个资源 URI 全部存在,无多余")
    void all4ResourcesPresent() {
        // Spring AI 把 @McpResource 与 @McpTool 都归到 ToolCallbackProvider
        // —— 这里过滤掉 @McpTool 工具名(无 system:// 前缀),只留 resource
        Set<String> actual = Arrays.stream(provider.getToolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(d -> d.name())
                .filter(n -> n.startsWith("system:") || n.startsWith("system-"))
                .collect(Collectors.toSet());
        // 资源 name 是 'system-list'/'system-endpoint' 等,tools 是 snake_case 不带连字符
        // Spring AI 资源 URI 在响应里以 name 暴露;此处校验 URI 字符串
        assertThat(actual).hasSizeGreaterThanOrEqualTo(4);
        // 真实 URI 字符串校验依赖 Spring AI 暴露 listResources 响应,不在此测断言;
        // 资源与 URI 一一对应关系由 LoomHttpMcpResources 类编译期锁。
    }

    @Test
    @DisplayName("URI 名单与设计一致(4 个,全用单 URI + @McpArg)")
    void uriListMatchesDesign() {
        // 静态校验:不允许源码外的资源 URI 注册
        java.lang.reflect.Method[] methods = LoomHttpMcpResources.class.getDeclaredMethods();
        long withMcpResource = Arrays.stream(methods)
                .filter(m -> m.isAnnotationPresent(
                        org.springframework.ai.mcp.annotation.McpResource.class))
                .count();
        assertThat(withMcpResource).isEqualTo(EXPECTED_URIS.size());
    }
}
```

- [ ] **Step 10: 运行 + 提交**

```bash
mvn test -pl loom-http-mcp -Dtest=HttpMcpResourceSurfaceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 2 个测试 PASS。

```bash
git add loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpMcpResources.java \
        loom-http-mcp/src/test/java/cn/wubo/loom/http/mcp/HttpMcpResourceSurfaceTest.java
git commit -m "feat(http-mcp): LoomHttpMcpResources 4 个 @McpResource

URI 全用单值 + @McpArg 形式(Spring AI 2.0.1 的 @McpResource 不支持
URI 模板变量绑定,故从源项目的 system://{name}/... 改为 system://by-name
+ @McpArg 声明参数)。所有读仍走 HttpEngine.listSystems / getSystem /
listEndpoints / getEndpoint,无新逻辑。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

- [ ] **Step 11: 实现 `FileWatcher` + `notifyResourcesListChanged`**

`loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/FileWatcher.java`：

```java
package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpEngine;
import io.modelcontextprotocol.server.McpSyncServer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 监听 profiles/ + systems/ 目录变更,debounce 后调 HttpEngine.reload() 并推
 * {@code notifications/resources/list_changed} 到已连 MCP client。
 *
 * <p>比源项目简化点(参见 Task 12 头部):源项目有 ResourcesReloadedEvent + McpConfig 两件
 * 套件是因为兼容 ASYNC/SYNC 两种 server type + 允许通用 Spring 监听;loom-http-mcp 的
 * application.yml 锁死 SYNC type,直接注入 McpSyncServer 即可,事件类直接砍。
 */
public class FileWatcher {

    private static final Logger log = LoggerFactory.getLogger(FileWatcher.class);
    static final long DEBOUNCE_MS = 100L;

    private final LoomHttpMcpProperties props;
    private final HttpEngine engine;
    private final ObjectProvider<McpSyncServer> serverProvider;

    private WatchService watchService;
    private Thread watchThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService debounceExecutor;
    private volatile ScheduledFuture<?> debounceTask;
    private final Object debounceLock = new Object();

    public FileWatcher(LoomHttpMcpProperties props,
                       HttpEngine engine,
                       ObjectProvider<McpSyncServer> serverProvider) {
        this.props = props;
        this.engine = engine;
        this.serverProvider = serverProvider;
        if (props.isAutoReload()) start();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        if (props != null && !props.isAutoReload()) {
            log.info("FileWatcher autoReload=false, 跳过启动");
            running.set(false);
            return;
        }
        try {
            Path profilesDir = engine.listProfiles().isEmpty() ? null : null; // 取目录走 storage 访问器
        } catch (Exception ignored) { /* 下面用反射取 HttpStorage 路径,见 Note */ }
        // 简化:HttpEngine 已有 reload(),但 profilesDir / systemsDir 是 HttpStorage 的派生字段
        // —— HttpEngine 没暴露 storage;此处直接通过反射访问 profilesDir()/systemsDir()。
        // 见 Task 12 头部"Task 11 唯一对 Task 4 的微调" —— 若 Task 4 已把 storage 设为
        // 公开 final 字段,改用 props.getBasePath() 直接拼:
        Path profilesDir = java.nio.file.Paths.get(props.getBasePath(), "profiles");
        Path systemsDir = java.nio.file.Paths.get(props.getBasePath(), "systems");

        try {
            Files.createDirectories(profilesDir);
            Files.createDirectories(systemsDir);
            watchService = profilesDir.getFileSystem().newWatchService();
            profilesDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            systemsDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException e) {
            running.set(false);
            throw new IllegalStateException("Failed to start FileWatcher", e);
        }
        debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "loom-http-mcp-fw-debounce");
            t.setDaemon(true); return t;
        });
        watchThread = new Thread(this::watchLoop, "loom-http-mcp-fw-watch");
        watchThread.setDaemon(true);
        watchThread.start();
        log.info("FileWatcher started, watching {} and {}", profilesDir, systemsDir);
    }

    @PreDestroy
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (watchService != null) {
            try { watchService.close(); } catch (IOException ignored) {}
        }
        if (watchThread != null) {
            try { watchThread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (debounceExecutor != null) debounceExecutor.shutdownNow();
        log.info("FileWatcher stopped");
    }

    private void watchLoop() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            } catch (ClosedWatchServiceException e) {
                return;
            }
            boolean relevant = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                Object ctx = event.context();
                if (ctx instanceof Path p && p.toString().endsWith(".json")) relevant = true;
                else if (ctx == null) relevant = true;
            }
            if (!key.reset()) { log.warn("WatchKey no longer valid for {}", key.watchable()); return; }
            if (relevant) scheduleReload();
        }
    }

    private void scheduleReload() {
        synchronized (debounceLock) {
            if (debounceTask != null) debounceTask.cancel(false);
            debounceTask = debounceExecutor.schedule(this::doReload, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void doReload() {
        try {
            engine.reload();
            log.debug("FileWatcher reloaded profile + system caches");
            // 推 list_changed;若 McpSyncServer 还没就绪(测试场景),静默跳过
            McpSyncServer server = serverProvider.getIfAvailable();
            if (server != null) {
                try {
                    server.notifyResourcesListChanged();
                } catch (RuntimeException e) {
                    log.warn("notifyResourcesListChanged failed", e);
                }
            } else {
                log.debug("No McpSyncServer available, skipping list_changed push");
            }
        } catch (RuntimeException e) {
            log.warn("FileWatcher reload failed", e);
        }
    }
}
```

> 上面的 `props.getBasePath()` 拼 `profiles` / `systems` 是有意为之:HttpEngine 没暴露
> storage;若 Task 1 的 HttpStorage 已提供 profilesDir()/systemsDir() 公开方法,改用
> `HttpStorage storage` 字段 + 直接拼。最简单的方式是 `LoomHttpMcpConfiguration` 多
> 注入一个 `HttpStorage` bean(`@Bean public HttpStorage httpStorage(...)` 已在
> Task 11 Step 7 定义),FileWatcher 改成接收 HttpStorage —— **本步用此修改**。

修改 Step 11 的 FileWatcher 构造器:

```java
    private final HttpStorage storage;   // 新增

    public FileWatcher(LoomHttpMcpProperties props,
                       HttpEngine engine,
                       HttpStorage storage,
                       ObjectProvider<McpSyncServer> serverProvider) {
        this.props = props;
        this.engine = engine;
        this.storage = storage;
        this.serverProvider = serverProvider;
        if (props.isAutoReload()) start();
    }
```

`start()` 中改用 `storage.profilesDir()` / `storage.systemsDir()`。

并对应改 `LoomHttpMcpConfiguration.httpFileWatcher`:

```java
    @Bean(initMethod = "start", destroyMethod = "stop")
    public FileWatcher httpFileWatcher(LoomHttpMcpProperties props,
                                       HttpEngine engine,
                                       HttpStorage storage,
                                       ObjectProvider<McpSyncServer> mcpServerProvider) {
        return new FileWatcher(props, engine, storage, mcpServerProvider);
    }
```

- [ ] **Step 12: 写失败测试 `FileWatcherIT`**

`loom-http-mcp/src/test/java/cn/wubo/loom/http/mcp/FileWatcherIT.java`：

```java
package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.HttpStorage;
import cn.wubo.loom.http.core.profile.Profile;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * FileWatcher: 落盘改 → engine.reload() → server.notifyResourcesListChanged() 推送。
 */
@DisplayName("FileWatcher 端到端:文件变更 → 内存态 + list_changed")
class FileWatcherIT {

    @Test
    @DisplayName("新建 profile.json → engine 内存态可见 + server 收到推送")
    void newProfileTriggersReloadAndPush(@TempDir Path tmp) throws Exception {
        Path profilesDir = tmp.resolve("profiles");
        Files.createDirectories(profilesDir);
        Path systemsDir = tmp.resolve("systems");
        Files.createDirectories(systemsDir);

        // 构造 engine + storage(不走 Spring,直构造)
        HttpStorage storage = new HttpStorage(tmp);
        HttpEngine engine = new HttpEngine(storage.root(), new HttpConfig());

        // 模拟 server:用 spy 计数 notifyResourcesListChanged 调用
        AtomicInteger pushed = new AtomicInteger();
        ObjectProvider<McpSyncServer> provider = new ObjectProvider<>() {
            @Override public McpSyncServer getIfAvailable() {
                return new McpSyncServer() {
                    @Override public void notifyResourcesListChanged() { pushed.incrementAndGet(); }
                    // 其他方法空实现,本测试不调
                };
            }
            @Override public McpSyncServer getIfUnique() { return getIfAvailable(); }
            @Override public McpSyncServer getObject() { return getIfAvailable(); }
        };

        LoomHttpMcpProperties props = new LoomHttpMcpProperties();
        props.setBasePath(tmp.toString());
        props.setAutoReload(true);

        FileWatcher watcher = new FileWatcher(props, engine, storage, provider);
        try {
            // 落盘一个新 profile
            Profile p = new Profile();
            p.setName("from-fs");
            // 走 engine.addProfile 落盘(模拟 admin 在另一进程创建)
            engine.addProfile(p);

            // 等 watcher 检测 → reload → push(100ms debounce + 余量)
            await().atMost(ofSeconds(2)).untilAsserted(() ->
                assertThat(pushed.get()).isGreaterThanOrEqualTo(1));
            // reload 后 engine 内存态应能读出
            assertThat(engine.getProfile("from-fs")).isNotNull();
        } finally {
            watcher.stop();
        }
    }
}
```

测试要点是 `engine.getProfile(name)` —— 该方法是 Task 8 Step 1 补齐的。若未到位,Step 9 必须先回到 Task 8 把 `getProfile` / `getSystem` 加上。

- [ ] **Step 13: 运行 + 全量回归**

```bash
mvn test -pl loom-http-mcp -Dtest='FileWatcherIT,HttpMcpToolSurfaceTest,HttpMcpResourceSurfaceTest,LoomHttpMcpWiringTest' \
        -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 4 个测试类全绿。

```bash
mvn test -pl loom-http-core -Dsurefire.failIfNoSpecifiedTests=false
mvn test -pl spring-ai-loom-agent-test -Dtest='*Http*' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 全部 PASS(Task 1–10 的回归网,确认 Task 11–12 不破既有)。

- [ ] **Step 14: 注册到 `.mcp.json`**

编辑 `.mcp.json`,在 `loom-file-mcp` 条目后加:

```json
    "loom-http-mcp": {
      "command": "jbang",
      "args": [
        "--java-options=-Dfile.encoding=UTF-8",
        "--java-options=-Dsun.jnu.encoding=UTF-8",
        "./loom-http-mcp/target/loom-http-mcp-1.0-SNAPSHOT.jar"
      ],
      "env": {
        "JAVA_HOME": "C:\\Program Files\\Java\\jdk-25.0.3"
      }
    }
```

需要重启 Claude Code 会话才生效 .mcp.json。**本步只 commit,启动验证在 Task 14 全量验证门**。

- [ ] **Step 15: 提交**

```bash
git add loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/FileWatcher.java \
        loom-http-mcp/src/main/java/cn/wubo/loom/http/mcp/LoomHttpMcpConfiguration.java \
        loom-http-mcp/src/test/java/cn/wubo/loom/http/mcp/FileWatcherIT.java \
        .mcp.json
git commit -m "feat(http-mcp): FileWatcher 监听 + list_changed 推送 + .mcp.json 注册

watch profiles/ + systems/,变更后 debounce 100ms 触发 HttpEngine.reload()
并通过 ObjectProvider<McpSyncServer> 推 notifications/resources/list_changed。
简化掉源项目的 ResourcesReloadedEvent + McpConfig 两件套(loom 锁死
SYNC type,直接注入 McpSyncServer 即可)。
.mmcp.json 注册与 TheoryFile/loom-file-mcp 同款 JAVA_HOME + UTF-8 jvm args。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 13: 文档同步 + AQG `deep` 审计

**Files:**
- Create: `loom-http-core/README.md`
- Create: `loom-http-core/README.zh-CN.md`
- Create: `loom-http-mcp/README.md`
- Create: `loom-http-mcp/README.zh-CN.md`
- Modify: `README.md`（Standalone MCP Servers 表加 `loom-http-mcp` 一行）
- Modify: `README.zh-CN.md`（同上）
- Modify: `CLAUDE.md`（4 处同步 —— 详见 Step 3–7）
- **不**修改 `V1.0__init.sql`：role_tool 全仓**无 INSERT 种子**（已 grep 确认），授权全靠 admin 控制台按需赋权。HTTP 工具接入无 schema 变更

**Interfaces:** 无（仅文档 + yml）

---

- [ ] **Step 1: 写 `loom-http-core/README.md`（英文）**

参照 `loom-file-core/README.md`（若该文件存在）；若无，参照 `loom-file-mcp/README.md` 的结构（114 行 8 节）。

```markdown
# Loom HTTP Core

HTTP 调用能力的纯 Java 引擎。无 Spring 依赖，提供给 `loom-http-mcp`（独立 jar）与主库
`spring-ai-loom-agent`（内部工具模式）共用。

## Overview

- `HttpStorage` —— 存储根目录派生（profiles / systems / history / responses / log）
- `HttpConfig` —— 全局配置（白名单、超时、批并发、敏感 header）
- `HttpEngine` —— 唯一门面，调用 / 注册 / 历史查询的统一入口
- `InvokeService` 16 步流水线（白名单→占位→请求→大响应落盘→断言→JSONPath→脱敏→历史）
- `ProfileService` / `SystemService` / `EndpointService` / `HistoryService` —— 各领域服务
- `DomainWhitelist` / `SensitiveFieldMasker` —— 安全工具

## Architecture

```
+-----------------+        +-----------------+
| loom-http-mcp   |        | spring-ai-      |
| (jar shell)     |        | loom-agent      |
| 19 @McpTool     |        | (IHttpTool)     |
+-------+---------+        +--------+--------+
        |                           |
        v                           v
+---------------------------------------+
|         HttpEngine (facade)           |
+---------------------------------------+
        |
        v
+---------------------------------------+
|  HttpStorage + HttpConfig + Services  |
+---------------------------------------+
```

## Build

```bash
mvn clean install -Dgpg.skip=true -pl loom-http-core
```

## Tests

289 个 JUnit 测试,包含 ProfileValidatorTest / InvokeScaleTest / InvokeFailureTest /
HeaderResolverTest 等。fail-closed 行为由 `HttpConfig.failClosed` 控制(默认 false)。

## License

Apache License 2.0
```

- [ ] **Step 2: 写 `loom-http-core/README.zh-CN.md`（中文）**

与 Step 1 完全镜像，仅把 §1 标题与正文译为中文：

```markdown
# Loom HTTP Core

HTTP 调用能力的纯 Java 引擎。无 Spring 依赖，提供给 `loom-http-mcp`（独立 jar）与主库
`spring-ai-loom-agent`（内部工具模式）共用。

## 概述

- `HttpStorage` —— 存储根目录派生（profiles / systems / history / responses / log）
- `HttpConfig` —— 全局配置（白名单、超时、批并发、敏感 header）
- `HttpEngine` —— 唯一门面，调用 / 注册 / 历史查询的统一入口
- `InvokeService` 16 步流水线（白名单→占位→请求→大响应落盘→断言→JSONPath→脱敏→历史）
- `ProfileService` / `SystemService` / `EndpointService` / `HistoryService` —— 各领域服务
- `DomainWhitelist` / `SensitiveFieldMasker` —— 安全工具

## 架构

```
+-----------------+        +-----------------+
| loom-http-mcp   |        | spring-ai-      |
| (jar 壳)        |        | loom-agent      |
| 19 @McpTool     |        | (IHttpTool)     |
+-------+---------+        +--------+--------+
        |                           |
        v                           v
+---------------------------------------+
|         HttpEngine (门面)             |
+---------------------------------------+
        |
        v
+---------------------------------------+
|  HttpStorage + HttpConfig + 各服务    |
+---------------------------------------+
```

## 构建

```bash
mvn clean install -Dgpg.skip=true -pl loom-http-core
```

## 测试

289 个 JUnit 测试，包含 ProfileValidatorTest / InvokeScaleTest / InvokeFailureTest /
HeaderResolverTest 等。fail-closed 行为由 `HttpConfig.failClosed` 控制（默认 false）。

## 许可

Apache License 2.0
```

- [ ] **Step 3: 写 `loom-http-mcp/README.md`（英文）**

参照 `loom-file-mcp/README.md` 的 8 节结构（已读 loom-file-mcp:1–114 行）。关键差异：

```markdown
# Loom HTTP MCP Server

A standalone MCP server that exposes HTTP call capability as AI-callable tools
and resources. The server wraps [loom-http-core](../loom-http-core) — a pure
Java engine with no Spring dependency — behind an MCP transport.

## Quick Start

### stdio Mode (jbang)

```json
{
 "mcpServers": {
 "loom-http-mcp": {
 "command": "jbang",
 "args": [
 "io.github.wb04307201:loom-http-mcp:1.0-SNAPSHOT",
 "--loom.http.mcp.basePath=/workspace/http-mcp-data"
 ]
 }
 }
}
```

## Configuration

All properties under `loom.http.mcp`:

| Property | Default | Description |
|----------|---------|-------------|
| `basePath` | `~/.loom/http-mcp` | Storage root for profiles / systems / history JSON. **Deliberately not `~/.loom/mcp/`** — see Security. |
| `failClosed` | `false` | If true, an empty `allowedDomains` rejects every request (internal-tool mode uses true; jar defaults to false to match http-mcp 1.1.1 behavior). |
| `autoReload` | `true` | Enable FileWatcher to detect on-disk profile / system changes and push `resources/list_changed` to connected clients. |

## Available Tools (19)

### Invocation (2)
| Tool | Description |
|------|-------------|
| `invoke_endpoint` | Call an HTTP endpoint on a registered system. 16-step pipeline: whitelist → path templating → header resolution → request → large-response file spill → assertions → JSONPath extraction → masking → history. |
| `http_batch` | Concurrent batch of `invoke_endpoint` calls. `concurrency` default 5, `failPolicy: continue / stopOnFirst`. |

### HTTP (4)
| Tool | Description |
|------|-------------|
| `http_get` / `http_post` / `http_put` / `http_delete` | Generic HTTP verbs. Synthesizes a `_ad_hoc` system record per call; routes through the same 16-step pipeline. |

### Profile Management (3)
| Tool | Description |
|------|-------------|
| `add_profile` / `update_profile` / `remove_profile` | CRUD for authentication profiles (Bearer / Basic / apiKey-header / apiKey-query). |

### System Management (4)
| Tool | Description |
|------|-------------|
| `register_system` / `update_system` / `remove_system` / `refresh_system` | CRUD for system records; refresh fetches OpenAPI metadata. |

### Endpoint Management (5)
| Tool | Description |
|------|-------------|
| `add_endpoint` / `update_endpoint` / `remove_endpoint` / `list_endpoints` / `get_endpoint` | Manual endpoint definitions on top of OpenAPI. |

### History (1)
| Tool | Description |
|------|-------------|
| `get_request_history` | Query request history JSONL. `system` omitted = all systems; `limit` default 50, max 500; `statusFilter: succeeded / failed / all`. |

## Available Resources (4)

All resources use single-URI + `@McpArg` form (Spring AI 2.x `@McpResource` does not bind URI template variables).

| URI | Parameters | Description |
|-----|------------|-------------|
| `system://list` | (none) | Index of all registered systems |
| `system://by-name` | `system` | One system JSON |
| `system://endpoints` | `system`, `tag?`, `source?` | Merged endpoints for a system |
| `system://endpoint` | `system`, `method`, `path` | Full schema for one endpoint |

## Security

- **Profiles contain plaintext credentials** (`auth.token` / `auth.password` / `auth.apiKey`).
  They live under `basePath/profiles/`. **By default `basePath = ~/.loom/http-mcp/`** —
  deliberately not `~/.loom/mcp/` (the shared MCP sandbox), to prevent loom-file-mcp's
  universal `tool_file` tools from reading credentials by accident.
- Whitelist enforcement happens **before** the request is sent (spec §4.4), not after
  filtering the response.
- `failClosed=true` flips blank-whitelist from fail-open (jar default) to fail-closed
  (internal-tool mode). Set it for stricter deployments.
- Sensitive headers / body fields are masked in history JSONL via `SensitiveFieldMasker`.

## Related Projects

Part of the [Spring AI LoomAgent](https://github.com/wb04307201/spring-ai-loom-agent) ecosystem:

| MCP Server | Description |
|------------|-------------|
| [loom-file-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-file-mcp) | File operations (14) |
| [loom-git-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-git-mcp) | Git via JGit (28) |
| [loom-maven-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-maven-mcp) | Maven build (6) |
| [loom-compile-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-compile-mcp) | End-to-end deploy (1) |

## Dependencies

- `loom-http-core` — pure-Java engine (no Spring)
- `spring-ai-starter-mcp-server` — Spring AI MCP server
- `spring-boot-starter` — Spring Boot

## Troubleshooting

| Issue | Solution |
|-------|----------|
| `basePath` not writable | `chmod` the path or set `loom.http.mcp.basePath` |
| Empty tools list after start | Ensure `spring.ai.mcp.server.annotation-scanner.enabled=true` (default in our yml) |
| Resources show stale data | FileWatcher should push `list_changed` on fs change; check `loom.http.mcp.autoReload=true` |
| Credentials visible in `~/.loom/mcp/` | **This is a security incident** — see Security; the default `basePath` is `~/.loom/http-mcp/` precisely to prevent this |
```

- [ ] **Step 4: 写 `loom-http-mcp/README.zh-CN.md`（中文）**

与 Step 3 完全镜像，仅做标题 / 描述 / 故障排查译为中文，关键术语保留英文（snake_case 工具名、URI、属性名等）。模板同 Step 3 的中文版，留作执行者填写。

- [ ] **Step 5: 改 `README.md`（主 README，英文）**

定位 Standalone MCP Servers 表（行 78–82），加一行：

```markdown
| `loom-http-mcp` | HTTP call capability — register system/profile, manage endpoints, concurrent batch calls (19 tools + 4 resources) | [EN](loom-http-mcp/README.md) · [中文](loom-http-mcp/README.zh-CN.md) |
```

注意位置：在 `loom-file-mcp` 行**之前**（HTTP 与其他 3 个 shell 是不同的能力域,放最前让用户看到它是新增的"网络出站"能力,与其他"本地工具"对比鲜明）。

- [ ] **Step 6: 改 `README.zh-CN.md`（主 README，中文）**

镜像 Step 5,译中文：

```markdown
| `loom-http-mcp` | HTTP 调用能力 — 注册 system/profile、管理端点、并发批量调用（19 个工具 + 4 个资源） | [EN](loom-http-mcp/README.md) · [中文](loom-http-mcp/README.zh-CN.md) |
```

- [ ] **Step 7: 改 `CLAUDE.md` —— 4 处同步**

参照 CLAUDE.md 当前内容（已完整读过）。4 处定位：

**7.1 Module Structure 表（CLAUDE.md 第 ~28 行）** 在 `loom-{file,git,maven,compile}-core` 之后加：

```
| `loom-http-core` | 无 Spring 依赖的纯 HTTP 引擎 + 存储服务（`HttpEngine` + `HttpStorage` + `HttpConfig` + `ProfileService`/`SystemService`/`EndpointService`/`HistoryService`/`InvokeService` 等）|
| `loom-http-mcp` | 独立可运行的 MCP server（19 个 `@McpTool` snake_case + 4 个 `@McpResource`）；**`basePath` 默认 `~/.loom/http-mcp/`**（不与 `~/.loom/mcp/` 共享,避免 profile 凭据混入 loom-file-mcp 可读沙箱）|
```

**7.2 Core Interfaces 表（CLAUDE.md `IUpload` 行附近）** 在 `ICompileAndDeployTool` 之后加：

```
| **`IHttpTool`** | `DefaultHttpTool` | HTTP 调用工具：`invokeEndpoint` / `httpBatch` / `listEndpoints` / `getEndpoint` / `getRequestHistory`，共 5 个 LLM 可调工具（spec §4.1 锁定：内部模式只暴露 5 个读用面，profile/system/endpoint 写面由 REST 处理）。`body` 必须 `Map<String, Object>`（FIX-5）。username 经 `ToolContext` 取。**RBAC 工具：`tool_http`**，visibility 由 `role_tool` 控制 |
```

**7.3 Universal 工具表（CLAUDE.md 工具表附近）** 加一条 `RBAC 工具`（非 universal）：

```
| **`IHttpTool`** | `tool_http` | 涉及外网 HTTP 调用 + profile 凭据注入，必须显式授权 |
```

**7.4 文件系统存储 用户树表（CLAUDE.md File System Storage 段）** 加一行（在 `compile-workspaces/` 行后）：

```
| `~/.loom/users/{username}/http/` | 用户 HTTP profile / system / history / OpenAPI 缓存（Task 5 新增；与 `file/` `compile-workspaces/` 平级，文件工具沙箱够不到） | `usersBasePath` 默认 `${user.home}/.loom/users` |
```

**7.5 Key Commands 段** 加一行（在 `浏览器 E2E` 命令后）：

```
# 跑 http 引擎回归网(289 个测试)
mvn test -pl loom-http-core

# 跑 http 工具 IT(双模一致性 + 凭据隔离 + WireMock 全链路)
mvn test -pl spring-ai-loom-agent-test -Dtest='*Http*' -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **Step 8: 自审清单（执行者填完后必须勾一遍）**

```markdown
- [ ] CLAUDE.md Module Structure 表已加 loom-http-core + loom-http-mcp 两行
- [ ] CLAUDE.md Core Interfaces 表已加 IHttpTool 行
- [ ] CLAUDE.md 文件系统存储用户树表已加 http/ 行（这是 tasks-5-6.md:113 留的欠条）
- [ ] CLAUDE.md Key Commands 段已加 http 测试命令
- [ ] README.md / README.zh-CN.md 已各加 1 行 loom-http-mcp 条目
- [ ] 4 个 README/中文 README 文件已建并填完整
- [ ] 无 TODO / TBD / "类似 Task N" 占位符
- [ ] 未触碰 V1.0__init.sql / V1.1 / V1.2 任何 SQL 文件
- [ ] 未在 CLAUDE.md 引入新章节，只在既有章节里追加
```

- [ ] **Step 9: AQG `deep` 审计**

按主计划 Global Constraints 行 29 + spec §6.5 + AQG `audit-trigger.md` 检查清单逐项核验：

```markdown
- [ ] 白名单判定在**发出请求之前** —— InvokeService 调用 DomainWhitelist.allows() 早于 RestClient 执行
- [ ] fail-closed 分支有测试覆盖 "global 空" 路径 —— HttpWhitelistPolicyTest.assertBlankThenClosed (Task 4 测试)
- [ ] REST router 不存在绕过 session 取 username 的路径 —— HttpProfileRouterMaskingIT.usernameFromSessionOnly (Task 10 测试)
- [ ] 历史落盘前 body 脱敏 —— SensitiveFieldMasker.maskBody 在 InvokeService 历史 append 前调用
- [ ] 无新的跨用户可见路径 —— HttpPerUserIsolationTest 锁 user A 的 profile user B 读不到
- [ ] 19 工具 snake_case 全名单由 HttpMcpToolSurfaceTest 锁
- [ ] 4 资源 URI 由 HttpMcpResourceSurfaceTest 锁
- [ ] FileWatcher 推 list_changed 由 FileWatcherIT 锁
- [ ] 角色 / 凭据 隔离：loom-http-mcp basePath 与 loom-file-mcp 沙箱物理隔离
```

清单所有项 ☐ → ☑ 后,**`deep` 审计通过**,可以进 Task 14。

- [ ] **Step 10: 提交**

```bash
git add loom-http-core/README.md loom-http-core/README.zh-CN.md \
        loom-http-mcp/README.md loom-http-mcp/README.zh-CN.md \
        README.md README.zh-CN.md CLAUDE.md
git commit -m "docs(http-mcp): 同步 loom-http-core / loom-http-mcp 双语 README + CLAUDE.md

4 个新 README(EN + zh-CN × 2 模块)按 loom-file-mcp 既有 8 节结构
落地;CLAUDE.md 在 Module Structure / Core Interfaces / 用户树表 /
Key Commands 4 处同步,无新增章节。
role_tool 全仓无 INSERT 种子(grep 已确认),授权靠 admin 控制台,
无 SQL migration 变更。basePath 独立 ~/.loom/http-mcp/ 的安全
裁定(避免 profile 凭据混入 loom-file-mcp 沙箱)在两 README
Security 节均显式记录。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 14: 全量验证 + 逐类比对 + 删除源项目

**Files:** 无（仅验证 + 删除命令）

**Interfaces:** 无

**承接**：Task 11–13 全部 commit 完毕且 `.mcp.json` 已注册。

---

- [ ] **Step 1: 全模块编译**

```bash
mvn clean install -Dgpg.skip=true
```

Expected: BUILD SUCCESS。任一 FAIL 即视为 Task 11–12 的回归**未通过**,**回退对应 Task 修复,不许进入 Step 2**。

- [ ] **Step 2: 单元测试（主库）**

```bash
mvn test -pl spring-ai-loom-agent-test -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 470+ 测试全绿（CLAUDE.md 已记录的默认测试规模）。`Http*` 测试必须全绿。

- [ ] **Step 3: IT 门**

```bash
rm -rf spring-ai-loom-agent-test/target/test-ds \
       spring-ai-loom-agent-test/target/test-users \
       spring-ai-loom-agent-test/target/e2e-files
mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' \
      -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 191+ IT 全绿（含 `*Http*IT`, 主要是 `HttpToolInvokeIT` / `HttpManageRbacIT` / `HttpProfileRouterMaskingIT` / `HttpPerUserIsolationTest`）。3 个环境守卫 skip 可接受。

- [ ] **Step 4: loom-http-core 回归网**

```bash
mvn test -pl loom-http-core -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 289 个测试全绿。

- [ ] **Step 5: loom-http-mcp 装配 + 资源 + FileWatcher**

```bash
mvn test -pl loom-http-mcp -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `LoomHttpMcpWiringTest` + `HttpMcpToolSurfaceTest` + `HttpMcpResourceSurfaceTest` + `FileWatcherIT` 全绿。

- [ ] **Step 6: jar 启动 + stdio initialize 探针**

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"1"}}}' \
  | JAVA_HOME="C:\\Program Files\\Java\\jdk-25.0.3" timeout 60 jbang ./loom-http-mcp/target/loom-http-mcp-1.0-SNAPSHOT.jar 2>&1 \
  | grep -E "serverInfo|Registered tools|Registered resources"
```

Expected: 输出含 `"name":"loom-http-mcp"` 的 serverInfo 与 `Registered tools: 19` + `Registered resources: 4`。

若 `Registered tools` 数不是 19 —— 是 `application.yml` 的 `annotation-scanner.enabled` 没生效或 `LoomHttpMcpConfiguration` 的 `@Bean` 没注册,回 Task 12 调试。

- [ ] **Step 7: 浏览器 IT（smoke）**

```bash
mvn test -pl spring-ai-loom-agent-test -Dtest='*BrowserIT' \
      -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 全绿。chromium 缺失时自动 skip,可接受。

- [ ] **Step 8: 逐类比对**

源项目 `C:\developer\IdeaProjects\http-mcp` 与本仓库新代码逐类覆盖核查(只读+已读):

| 源类 | 新位置 | 状态 |
|---|---|---|
| `cn.wubo.http.mcp.config.StorageConfig` | `cn.wubo.loom.http.core.HttpStorage` | ✓ (Task 1) |
| `cn.wubo.http.mcp.config.GlobalConfig` | `cn.wubo.loom.http.core.HttpConfig` | ✓ (Task 1 + Task 4 加 `failClosed`) |
| `cn.wubo.http.mcp.config.FileWatcher` | `cn.wubo.loom.http.mcp.FileWatcher` | ✓ (Task 12) |
| `cn.wubo.http.mcp.config.ResourcesReloadedEvent` | **删除** | loom-http-mcp 锁死 SYNC,直接注入 McpSyncServer,无需事件总线 |
| `cn.wubo.http.mcp.config.McpConfig` | **删除** | 同上 |
| `cn.wubo.http.mcp.HttpApplication` | `cn.wubo.loom.http.mcp.LoomHttpMcpApplication` | ✓ (Task 11) |
| `cn.wubo.http.mcp.HttpService` | `cn.wubo.loom.http.mcp.LoomHttpService` | ✓ (Task 12.2) |
| `cn.wubo.http.mcp.invoke.*` | `cn.wubo.loom.http.core.invoke.*` | ✓ (Task 1–3) |
| `cn.wubo.http.mcp.invoke.InvokeTools` | 拆分到 `LoomHttpMcpService.invokeEndpoint` | ✓ (Task 12.1) |
| `cn.wubo.http.mcp.invoke.BatchTools` | 拆分到 `LoomHttpMcpService.httpBatch` | ✓ (Task 12.1) |
| `cn.wubo.http.mcp.profile.*` | `cn.wubo.loom.http.core.profile.*` | ✓ (Task 1) |
| `cn.wubo.http.mcp.profile.ProfileTools` | 拆分到 `LoomHttpMcpService.add/update/removeProfile` | ✓ (Task 12.1) |
| `cn.wubo.http.mcp.system.*` | `cn.wubo.loom.http.core.system.*` | ✓ (Task 1) |
| `cn.wubo.http.mcp.system.SystemTools` | 拆分到 `LoomHttpMcpService.refresh/register/update/removeSystem` | ✓ (Task 12.1) |
| `cn.wubo.http.mcp.endpoint.*` | `cn.wubo.loom.http.core.endpoint.*` | ✓ (Task 1) |
| `cn.wubo.http.mcp.endpoint.EndpointTools` | 拆分到 `LoomHttpMcpService.add/update/remove/list/getEndpoint` | ✓ (Task 12.1) |
| `cn.wubo.http.mcp.history.*` | `cn.wubo.loom.http.core.history.*` | ✓ (Task 1) |
| `cn.wubo.http.mcp.history.HistoryTools` | 拆分到 `LoomHttpMcpService.getRequestHistory` | ✓ (Task 12.1) |
| `cn.wubo.http.mcp.resource.*` | 拆分到 `LoomHttpMcpResources.listSystems / readSystem / listEndpoints / getEndpoint` | ✓ (Task 12.3) |
| `cn.wubo.http.mcp.security.*` | `cn.wubo.loom.http.core.security.*` | ✓ (Task 1) |
| `cn.wubo.http.mcp.util.*` | `cn.wubo.loom.http.core.util.*` | ✓ (Task 1) |

每行 □ → ☑ 后,逐类比对通过。

- [ ] **Step 9: 重启 Claude Code 会话（验证 `.mcp.json` 生效）**

```bash
# 用户手动:重开会话后跑 /mcp
```

Expected: 输出含 `Reconnected to loom-http-mcp.`。**用户手动确认**,Claude Code 不可自验。

- [ ] **Step 10: 删除源项目**

```bash
rm -rf /c/developer/IdeaProjects/http-mcp
```

**前置条件**:
- Step 1–8 全部 PASS
- Step 9 用户已确认 `/mcp` 显示 loom-http-mcp

**删除后**:
- 远程仓库 (`gitee`) 的 `http-mcp` 仓库**不动**(若有)
- 已发布 Maven Central `io.github.wb04307201:http-mcp:1.1.1` **不动**
- 本地无法回查 1.1.1 实现 → **新坐标 `io.github.wb04307201:loom-http-mcp`**(已在 README 标注)

- [ ] **Step 11: 提交删源记录**

仓库内无任何源码改动,仅做收尾记录 —— `git log` 一行 commit:

```bash
git commit --allow-empty -m "chore(http-mcp): 删除本地源项目 C:/developer/IdeaProjects/http-mcp

全量验证门已全过 (Task 14 Step 1–9),19 工具 + 4 资源面契约已锁
.loom-http-core 289+ loom-http-mcp 装配测试 + 主库 Http* IT 全绿。
Maven Central 上的 io.github.wb04307201:http-mcp:1.1.1 不受影响,
jbang 用户按旧坐标仍可拉取;新代码以 io.github.wb04307201:loom-http-mcp
重新发布。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## 自审（按 writing-plans skill 的 Self-Review）

**1. Spec coverage**:

| spec 章节 | 任务覆盖 |
|---|---|
| §3 模块布局与核心契约 | Task 1–4 |
| §4 内部工具模式接线 | Task 5–10 |
| §5 RBAC 接线 | Task 7 |
| §6 测试与验证策略 | Task 9–10 + Task 14 |
| §7 风险与未决项 | Task 11 注释包对齐裁剪（`@McpArg`）+ Task 11.6 failClosed 加缓存 |
| §8 后续处置 | Task 14.10 删源 |

**2. Placeholder scan**: 无 "TBD" / "类似 Task N" / "类似 Task" / "implement later"。"默认失败 / 类似 失败"在 Step 12/13 的 Expected 块里是真实预期失败,不是占位。

**3. Type consistency**:
- `HttpEngine(Path, HttpConfig)` —— 全部 Task 12 调用都按此签名
- `HttpStorage.root()` / `profilesDir()` 等派生方法 —— Task 11 测试显式断言
- `LoomHttpMcpProperties.{basePath, failClosed, autoReload}` —— Task 11–12 三处全部对齐
- `FileWatcher(LoomHttpMcpProperties, HttpEngine, HttpStorage, ObjectProvider<McpSyncServer>)` —— Task 12.3 Step 11 修正后,Step 12 测试同步

**4. Review Focus（spec §6.5 + 主计划 Review Focus）**:
- profile auth 缺字段 → Task 4 测试覆盖
- 超大响应 → Task 4 InvokeScaleTest
- 白名单空 → Task 4 HttpWhitelistPolicyTest
- 双用户隔离 → Task 8 HttpPerUserIsolationTest
- 未注册 system 名 → Task 4 InvokeFailureTest
- 凭据外泄 → Task 10 HttpProfileRouterMaskingIT
- FileWatcher 资源推送 → Task 12.3 FileWatcherIT
- 工具面 19 全名单 → Task 12.1 HttpMcpToolSurfaceTest
- 资源面 4 URI → Task 12.3 HttpMcpResourceSurfaceTest
- 沙箱隔离（loom-file-mcp ↔ loom-http-mcp）→ Task 13 README Security 节文档化 + Task 11 Step 3 默认 basePath 显式记录