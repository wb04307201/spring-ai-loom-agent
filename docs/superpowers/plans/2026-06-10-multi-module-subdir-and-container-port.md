# Multi-Module subDir + containerPort Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `ICompileAndDeployTool` 改造成纯对话参数化工具：删 yml `defaultPort` / `baseImage` 兜底，加 `containerPort` 与 `subDir` 入参，让 LLM 在对话里主动向用户索取业务参数。

**Architecture:** 三层改造 —— yml 删字段、工具接口加必填校验、Dockerfile / docker run 拆宿主-容器端口。`resolveEffectiveProjectDir` 接受 `subDir` 显式覆盖启发式；歧义时工具 fail 并 errorMessage 引导 LLM 追问。skill 模板去掉硬编码 `port: 8080`。

**Tech Stack:** Spring AI `@Tool` / `@ToolParam`、Spring Boot 3.x、JDK 17+、Maven 3.x、JUnit 5、ProcessBuilder、JGit、Jackson。

**前置 spec:** [2026-06-10-multi-module-subdir-and-container-port-design.md](../specs/2026-06-10-multi-module-subdir-and-container-port-design.md)

---

## 任务总览

| Task | 主题 | 主要文件 |
|------|------|----------|
| 1 | 删 yml 字段 `defaultPort` / `baseImage` | `LoomAgentProperties.java`、`DefaultCompileAndDeployToolTest.java` |
| 2 | 更新 `ICompileAndDeployTool` 接口契约 | `ICompileAndDeployTool.java` |
| 3 | `compileAndDeploy` 入口校验（缺 port/containerPort → fail） | `DefaultCompileAndDeployTool.java`、`DefaultCompileAndDeployToolTest.java` |
| 4 | `writeDockerfile` EXPOSE 用 `containerPort` | `DefaultCompileAndDeployTool.java`、`DefaultCompileAndDeployToolTest.java` |
| 5 | `dockerRun` 加 `containerPort` 入参，`-p <port>:<containerPort>` | `DefaultCompileAndDeployTool.java`、`DefaultCompileAndDeployToolTest.java` |
| 6 | `resolveEffectiveProjectDir` 加 `subDir` 入参（5 条规则 + 3 个测试） | `DefaultCompileAndDeployTool.java`、`DefaultCompileAndDeployToolTest.java` |
| 7 | `package-docker.st` skill 模板改写 | `package-docker.st` |
| 8 | 6 份文档同步 | `API.md`、`API.zh-CN.md`、`README.md`、`README.zh-CN.md`、`CUSTOMIZATION.md`、`CUSTOMIZATION.zh-CN.md` |

---

## Task 1: 删除 yml 字段 `defaultPort` 和 `baseImage`

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java:230-326`（删 2 字段、改 Javadoc）
- Modify: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployToolTest.java`（加 2 个反射测试）

- [ ] **Step 1.1: 写失败的反射测试（验证字段不存在）**

在 `DefaultCompileAndDeployToolTest.java` 末尾追加：

```java
@Test
void compileProperty_noDefaultPortField() {
    assertThatThrownBy(() -> LoomAgentProperties.CompileProperty.class.getDeclaredField("defaultPort"))
            .isInstanceOf(NoSuchFieldException.class);
}

@Test
void compileProperty_noBaseImageField() {
    assertThatThrownBy(() -> LoomAgentProperties.CompileProperty.class.getDeclaredField("baseImage"))
            .isInstanceOf(NoSuchFieldException.class);
}
```

需要的 import：
```java
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```
（如果文件已有 `import static org.assertj.core.api.Assertions.*` 改用全限定名调用）

- [ ] **Step 1.2: 跑测试确认失败**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
./mvnw test -pl spring-ai-loom-agent-test -Dtest='DefaultCompileAndDeployToolTest#compileProperty_noDefaultPortField+compileProperty_noBaseImageField' -q
```

期望：FAIL，提示 `defaultPort` / `baseImage` 字段仍存在。

- [ ] **Step 1.3: 删 `defaultPort` 字段**

打开 `LoomAgentProperties.java`，定位到 `CompileProperty` 内部类（行 278-294）。

**删除**：
```java
        private int defaultPort = 8080;
```

**修改 Javadoc**（行 261-276 段落）：从"yml 通过 `spring.ai.loom.agent.compile.*` 配置"列表里删 `defaultPort` 整行 + `baseImage` 整行。**保留**：
- `enabled`
- `mavenHome`
- `dockerCmd`
- `mavenTimeoutMs` / `dockerBuildTimeoutMs` / `dockerRunTimeoutMs`
- `healthCheckMaxWaitMs` / `healthCheckIntervalMs`
- `keepWorkspace`
- `extraRunArgs`
- `imageTemplates`

新的 Javadoc 段落（替换原行 261-276）：
```java
    /**
     * 一站式编译部署工具配置。
     * <p>
     * yml 通过 {@code spring.ai.loom.agent.compile.*} 配置（仅运维参数）：
     * <ul>
     *   <li>{@code enabled} — 是否启用该工具（默认 true）</li>
     *   <li>{@code mavenHome} — 可选；不配则复用 {@link MavenProperty#getMavenHome()}，
     *       再不行就环境变量自动探测</li>
     *   <li>{@code dockerCmd} — 可选；不配则用 PATH 上的 {@code docker}</li>
     *   <li>{@code mavenTimeoutMs} — maven 编译超时（默认 600000 = 10 分钟）</li>
     *   <li>{@code dockerBuildTimeoutMs} — docker build 超时（默认 600000）</li>
     *   <li>{@code dockerRunTimeoutMs} — docker run 启动等待超时（默认 60000）</li>
     *   <li>{@code healthCheckMaxWaitMs} — 容器启动后健康检查总等待（默认 60000）</li>
     *   <li>{@code healthCheckIntervalMs} — 健康检查轮询间隔（默认 2000）</li>
     *   <li>{@code keepWorkspace} — 是否保留工作区目录（默认 false）</li>
     *   <li>{@code extraRunArgs} — {@code docker run} 额外参数（默认空）</li>
     *   <li>{@code imageTemplates} — 预置基础镜像模板（key=别名，value=ImageTemplate），
     *       工具入参 {@code baseImage} 命中 key 时使用；缺省回退到 {@code java17}</li>
     * </ul>
     * <p>
     * 业务参数（{@code port}、{@code containerPort}、{@code subDir}、{@code healthPath}、
     * {@code baseImage}、{@code runCommand}）一律从对话给到 AI，不在 yml 中配置。
     */
```

- [ ] **Step 1.4: 删 `baseImage` 字段**

在同一文件 `CompileProperty` 内部类（行 290）**删除**：
```java
        private String baseImage = "eclipse-temurin:17-jre-alpine";
```

- [ ] **Step 1.5: 同步构造器日志行（可选清理）**

`DefaultCompileAndDeployTool.java` 行 94-96：
```java
        log.info("CompileAndDeployTool initialized: enabled={}, mavenHome={}, fileBasePath={}, defaultPort={}",
                compile != null && compile.isEnabled(), resolvedMavenHome, this.fileBasePath,
                compile != null ? compile.getDefaultPort() : 8080);
```

**改为**：
```java
        log.info("CompileAndDeployTool initialized: enabled={}, mavenHome={}, fileBasePath={}",
                compile != null && compile.isEnabled(), resolvedMavenHome, this.fileBasePath);
```

- [ ] **Step 1.6: 跑测试确认通过**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
./mvnw test -pl spring-ai-loom-agent-test -Dtest='DefaultCompileAndDeployToolTest' -q
```

期望：所有现有测试 + 2 个新反射测试通过。

- [ ] **Step 1.7: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java \
        spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployTool.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployToolTest.java
git commit -m "refactor(compile): 删除 yml defaultPort 和 baseImage 兜底字段"
```

---

## Task 2: 更新 `ICompileAndDeployTool` 接口契约

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/compile/ICompileAndDeployTool.java`（Javadoc + `@Tool` 描述 + `@ToolParam` 描述）

- [ ] **Step 2.1: 替换接口方法 Javadoc**

`ICompileAndDeployTool.java` 行 44-63 整段替换为：

```java
    /**
     * 克隆代码仓库、本地 Maven 打包、构建 Docker 镜像并启动容器，最后返回访问 URL。
     * <p>
     * 业务参数（port / containerPort / subDir / baseImage / runCommand / healthPath）
     * 一律从对话给到 AI，不在 yml 中配置。
     *
     * @param params       工具入参 Map，支持以下键（大小写不敏感）：
     *                     <ul>
     *                       <li>{@code gitUrl}        — Git 仓库 URL（<b>必填</b>）</li>
     *                       <li>{@code gitUsername}   — Git 用户名（公开仓库可省略）</li>
     *                       <li>{@code gitPassword}   — Git 密码或 token（公开仓库可省略）</li>
     *                       <li>{@code branch}        — 克隆分支（可选，默认为远程 HEAD）</li>
     *                       <li>{@code port}          — 宿主机对外端口（<b>必填，无 yml 兜底</b>）</li>
     *                       <li>{@code containerPort} — 容器内应用监听端口（<b>必填，无 yml 兜底</b>）</li>
     *                       <li>{@code subDir}        — 多模块仓库子目录（可选）；缺省时多模块可能选错</li>
     *                       <li>{@code imageName}     — Docker 镜像名（可选，工具自动生成）</li>
     *                       <li>{@code containerName} — Docker 容器名（可选，工具自动生成）</li>
     *                       <li>{@code healthPath}    — 健康检查路径（可选，默认 {@code /}）</li>
     *                       <li>{@code baseImage}     — 基础镜像（可选）；支持模板别名 {@code java17/java21/nginx/python3}，
     *                                                  或完整镜像名如 {@code openjdk:17-slim}。缺省回退到 java17 模板</li>
     *                       <li>{@code runCommand}    — 容器启动命令（可选）；缺省按 baseImage 模板自动生成</li>
     *                     </ul>
     * @param toolContext  Spring AI 工具上下文（注入 username）
     * @return 编译部署结果
     */
```

- [ ] **Step 2.2: 替换 `@Tool` 描述**

`ICompileAndDeployTool.java` 行 64-71 整段替换为：

```java
    @Tool(description = "克隆 Git 仓库、运行 mvn 打包、构建 Docker 镜像并启动容器，返回访问 URL。"
            + "适用于 Spring Boot / 标准 Maven 项目的端到端编译部署。"
            + "入参是 Map：gitUrl、port、containerPort 都是必填（无 yml 兜底，缺失会返回 fail）。"
            + "其余按需提供（gitUsername、gitPassword、branch、subDir、imageName、containerName、"
            + "healthPath、baseImage、runCommand）。"
            + "baseImage 支持模板别名（java17 / java21 / nginx / python3，缺省 java17）或完整镜像名（如 openjdk:17-slim）；"
            + "runCommand 极少用，缺省即可（会按模板自动生成 ENTRYPOINT）。"
            + "port 是宿主机对外端口，containerPort 是容器内应用监听端口（参考 application.yml 的 server.port），"
            + "docker run 会用 -p <port>:<containerPort> 映射。"
            + "多模块仓库（根无 pom.xml）必须传 subDir 显式选择子模块；缺省时若多个子模块无法自动选择，工具会返回 fail。"
            + "healthPath 既作探活路径也作访问 URL 路径（如 healthPath=sql-forge-demo 则访问 http://localhost:<port>/sql-forge-demo）；无 context-path 时传 \"/\"。")
```

- [ ] **Step 2.3: 替换 `@ToolParam` 描述**

`ICompileAndDeployTool.java` 行 73-75 整段替换为：

```java
            @ToolParam(description = "工具入参 Map，包含 gitUrl 等键。支持的键（大小写不敏感）："
                    + "gitUrl（必填）、gitUsername、gitPassword、branch、"
                    + "port（必填，宿主机对外端口）、containerPort（必填，容器内应用端口）、"
                    + "subDir（多模块仓显式选子模块）、"
                    + "imageName、containerName、healthPath、"
                    + "baseImage（java17/java21/nginx/python3 或完整镜像名）、"
                    + "runCommand（字符串数组，覆盖模板 ENTRYPOINT）") Map<String, Object> params,
```

- [ ] **Step 2.4: 编译验证**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
./mvnw compile -pl spring-ai-loom-agent -q
```

期望：BUILD SUCCESS（这只是文档改动，不影响编译）。

- [ ] **Step 2.5: 跑现有测试确认没破坏**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
./mvnw test -pl spring-ai-loom-agent-test -Dtest='DefaultCompileAndDeployToolTest' -q
```

期望：所有测试通过（接口改动不破坏实现）。

- [ ] **Step 2.6: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/compile/ICompileAndDeployTool.java
git commit -m "docs(compile): 更新 @Tool 描述，标记 port/containerPort 必填、subDir 显式"
```

---

## Task 3: `compileAndDeploy` 入口校验（缺 port/containerPort → fail）

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployTool.java:102-138`（参数解析 + 校验）
- Modify: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployToolTest.java`（+2 测试）

- [ ] **Step 3.1: 写失败的测试（缺 port 返回 fail）**

在 `DefaultCompileAndDeployToolTest.java` 找到 `compileAndDeploy` 类的测试组（搜 `Map.of("gitUrl"` 之类）末尾追加：

```java
@Test
void compileAndDeploy_missingPort_returnsFailWithAskUserHint() {
    Map<String, Object> params = new HashMap<>();
    params.put("gitUrl", "https://gitee.com/test/repo.git");
    // 故意不传 port
    params.put("containerPort", 8080);
    params.put("username", "tester");

    CompileAndDeployResult result = invokeCompileAndDeploy(params);

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("port").contains("不能为空");
    assertThat(result.errorMessage()).contains("请向用户");
    assertThat(result.accessUrl()).isNull();
    assertThat(result.steps()).isEmpty();
}

@Test
void compileAndDeploy_missingContainerPort_returnsFailWithAskUserHint() {
    Map<String, Object> params = new HashMap<>();
    params.put("gitUrl", "https://gitee.com/test/repo.git");
    params.put("port", 8080);
    // 故意不传 containerPort
    params.put("username", "tester");

    CompileAndDeployResult result = invokeCompileAndDeploy(params);

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("containerPort").contains("不能为空");
    assertThat(result.errorMessage()).contains("请向用户");
}
```

工具方法 `invokeCompileAndDeploy` 已在该测试类存在（搜一下既有的私有方法；如果不存在，按以下模板添加在测试类内）：

```java
private CompileAndDeployResult invokeCompileAndDeploy(Map<String, Object> params) {
    LoomAgentProperties props = new LoomAgentProperties();
    DefaultCompileAndDeployTool tool = new DefaultCompileAndDeployTool(props);
    ToolContext ctx = new ToolContext(Map.of("username", "tester"));
    return tool.compileAndDeploy(params, ctx);
}
```

需要的 import（如果文件里没有）：
```java
import java.util.HashMap;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
```

- [ ] **Step 3.2: 跑测试确认失败**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
./mvnw test -pl spring-ai-loom-agent-test -Dtest='DefaultCompileAndDeployToolTest#compileAndDeploy_missingPort_returnsFailWithAskUserHint+compileAndDeploy_missingContainerPort_returnsFailWithAskUserHint' -q
```

期望：FAIL，提示 `result.success()` 不为 false（当前缺 port 走的是 `defaultPort` 兜底，success=true）。

- [ ] **Step 3.3: 改 `compileAndDeploy` 解析逻辑**

`DefaultCompileAndDeployTool.java` 行 114 修改：

**原**：
```java
        Integer port = intOrNull(flat, "port");
```

**改为**（在 `port` 之后增加 `containerPort`）：
```java
        Integer port = intOrNull(flat, "port");
        Integer containerPort = intOrNull(flat, "containerPort", "container_port", "containerPort");
```

- [ ] **Step 3.4: 改 `compileAndDeploy` 校验逻辑**

`DefaultCompileAndDeployTool.java` 行 119-126 **替换**为：

```java
        if (gitUrl == null || gitUrl.isBlank()) {
            return CompileAndDeployResult.fail(null, null, null, null, null, null, steps,
                    "参数错误：gitUrl 不能为空，请向用户索取仓库 URL");
        }
        if (port == null) {
            return CompileAndDeployResult.fail(null, gitUrl, null, null, null, null, steps,
                    "参数错误：port 不能为空，请向用户索取宿主机端口（对外暴露的端口）");
        }
        if (containerPort == null) {
            return CompileAndDeployResult.fail(null, gitUrl, null, null, null, null, steps,
                    "参数错误：containerPort 不能为空，请向用户索取应用监听端口（参考 application.yml 的 server.port）");
        }
        if (username == null) {
            return CompileAndDeployResult.fail(null, gitUrl, null, null, null, null, steps,
                    "无法获取用户名，请通过登录态调用");
        }
```

- [ ] **Step 3.5: 改 `effectivePort` 兜底逻辑（删除 defaultPort 兜底）**

`DefaultCompileAndDeployTool.java` 行 128：

**原**：
```java
        int effectivePort = port != null && port > 0 ? port : compile.getDefaultPort();
```

**改为**：
```java
        int effectivePort = port;
        int effectiveContainerPort = containerPort;
```

- [ ] **Step 3.6: 修复所有 `effectivePort` / `effectiveHealthPath` 引用**

**注意**：本步只修复**签名**——后续 Task 4、5、6 才把 `containerPort` 传到具体方法。本步只把 `effectivePort` 的初始化从"兜底到 defaultPort"改成"直接用入参"（因为前面已经 fail-fast，port 必非空）。

**不改**的行（因为这些方法内部还需要单独改造）：
- 行 200 `String runningContainer = dockerRun(effectiveImage, effectiveContainer, effectivePort);` —— Task 5 改
- 行 209 `boolean healthy = waitForHealthy(effectivePort, effectiveHealthPath);` —— 不变（健康检查 URL 用宿主机端口）
- 行 210 `String accessUrl = buildAccessUrl(effectivePort, effectiveHealthPath);` —— 不变

**所有 `CompileAndDeployResult.fail(...)` / `CompileAndDeployResult.ok(...)` 调用的 `port` 参数**保持 `effectivePort`（含义是对外暴露的宿主机端口，与 result 字段语义保持一致）。

- [ ] **Step 3.7: 跑测试**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
./mvnw test -pl spring-ai-loom-agent-test -Dtest='DefaultCompileAndDeployToolTest' -q
```

期望：2 个新测试 + 所有现有测试通过。

- [ ] **Step 3.8: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployTool.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployToolTest.java
git commit -m "feat(compile): port/containerPort 缺一即 fail，引导 LLM 追问用户"
```

---

## Task 4: `writeDockerfile` EXPOSE 用 `containerPort`

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployTool.java:182-184`（调用方传 containerPort）
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployTool.java:455-478`（`writeDockerfile` 签名）
- Modify: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployToolTest.java`（+1 测试）

- [ ] **Step 4.1: 写失败的测试（EXPOSE 用 containerPort 而非 port）**

追加到测试类：

```java
@Test
void writeDockerfile_exposeUsesContainerPort() throws Exception {
    LoomAgentProperties props = new LoomAgentProperties();
    DefaultCompileAndDeployTool tool = new DefaultCompileAndDeployTool(props);
    ResolvedImage resolved = new ResolvedImage("java17", "eclipse-temurin:17-jre-alpine",
            List.of("java", "-jar", "app.jar"));

    Path tmp = Files.createTempDirectory("loom-cdt-test-");
    try {
        // 模拟 target/app.jar
        Files.createDirectories(tmp.resolve("target"));
        Files.createFile(tmp.resolve("target/app.jar"));
        File jarFile = tmp.resolve("target/app.jar").toFile();

        // 反射调用 package-private writeDockerfile
        var method = DefaultCompileAndDeployTool.class.getDeclaredMethod(
                "writeDockerfile", Path.class, File.class, ResolvedImage.class, int.class);
        method.setAccessible(true);
        File dockerfile = (File) method.invoke(tool, tmp, jarFile, resolved, /*containerPort*/ 9090);

        String content = Files.readString(dockerfile.toPath());
        assertThat(content).contains("EXPOSE 9090");
        assertThat(content).doesNotContain("EXPOSE 8080");
    } finally {
        deleteRecursively(tmp);
    }
}
```

需要的 import（按需）：
```java
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.util.List;
import cn.wubo.spring.ai.loom.agent.tool.compile.DefaultCompileAndDeployTool.ResolvedImage;
```

`deleteRecursively(Path)` —— 测试类可能没有；按需添加（与 `DefaultCompileAndDeployTool` 内的 `deleteRecursively` 一致，私有静态复制一份即可）：
```java
private static void deleteRecursively(Path dir) {
    if (!Files.exists(dir)) return;
    try (var stream = Files.walk(dir)) {
        stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        });
    } catch (Exception ignored) {}
}
```

- [ ] **Step 4.2: 跑测试确认失败**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
./mvnw test -pl spring-ai-loom-agent-test -Dtest='DefaultCompileAndDeployToolTest#writeDockerfile_exposeUsesContainerPort' -q
```

期望：FAIL（`writeDockerfile` 还没加第 4 个参数）。

- [ ] **Step 4.3: 改 `writeDockerfile` 签名 + 实现**

`DefaultCompileAndDeployTool.java` 行 461-478 **替换**为：

```java
    File writeDockerfile(Path projectDir, File jar, ResolvedImage resolved, int containerPort) {
        File dockerfile = projectDir.resolve("Dockerfile").toFile();
        String entrypoint = toJsonArray(resolved.command());
        String content = String.format("""
                # Auto-generated by DefaultCompileAndDeployTool
                FROM %s
                WORKDIR /app
                COPY target/%s app.jar
                EXPOSE %d
                ENTRYPOINT %s
                """, resolved.image(), jar.getName(), containerPort, entrypoint);
        try {
            Files.writeString(dockerfile.toPath(), content, StandardCharsets.UTF_8);
            return dockerfile;
        } catch (IOException e) {
            throw new RuntimeException("写 Dockerfile 失败: " + e.getMessage(), e);
        }
    }
```

- [ ] **Step 4.4: 改 `compileAndDeploy` 内的调用**

`DefaultCompileAndDeployTool.java` 行 183：

**原**：
```java
            File dockerfile = writeDockerfile(effectiveDir, jar, resolvedImage);
```

**改为**：
```java
            File dockerfile = writeDockerfile(effectiveDir, jar, resolvedImage, effectiveContainerPort);
```

- [ ] **Step 4.5: 跑测试**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
./mvnw test -pl spring-ai-loom-agent-test -Dtest='DefaultCompileAndDeployToolTest' -q
```

期望：所有测试通过。**注意**：现有 `writeDockerfile_usesJavaFallback` / `writeDockerfile_usesTemplateCommand` 等测试也用反射调 `writeDockerfile`，需要改这些旧测试的方法签名——新增第 4 个 int 参数（按需传 8080，行为兼容）。**简化方案**：本任务内同步修改所有现有 `writeDockerfile*` 测试，按 `invokeWriteDockerfile(tmp, jar, resolved, 8080)` 形式调用（即 `port=containerPort=8080`，EXPOSE 仍是 8080）。

- [ ] **Step 4.6: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployTool.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployToolTest.java
git commit -m "feat(compile): writeDockerfile EXPOSE 改用 containerPort"
```

---

## Task 5: `dockerRun` 加 `containerPort` 入参，`-p <port>:<containerPort>`，不加 `-e SERVER_PORT`

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployTool.java:200`（调用方）
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployTool.java:531-561`（`dockerRun` 签名）
- Modify: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployToolTest.java`（+2 测试）

- [ ] **Step 5.1: 写失败的测试（`-p <port>:<containerPort>`）**

追加：

```java
@Test
void dockerRun_mapsPortToContainerPort() throws Exception {
    LoomAgentProperties props = new LoomAgentProperties();
    DefaultCompileAndDeployTool tool = new DefaultCompileAndDeployTool(props);

    // 反射调用 package-private dockerRun(String, String, int, int)
    var method = DefaultCompileAndDeployTool.class.getDeclaredMethod(
            "dockerRun", String.class, String.class, int.class, int.class);
    method.setAccessible(true);

    // 第一次会真的跑 docker rm -f / docker run，外部环境若无 docker 跑不通
    // —— 改为用 mock 或者捕获命令。简化：只检查命令构造，跳过实际执行
    // 下面给"构造命令 + 校验"的反射实现思路：
    // 由于 dockerRun 直接调 runProcess，校验起来麻烦。改用直接断言 cmd 列表。
    // 备选方案：在 DefaultCompileAndDeployTool 内部加 package-private buildDockerRunCommand(...)
    // 便于测试。本任务采用"加 buildDockerRunCommand 静态方法"路线。

    // 验证逻辑：buildDockerRunCommand 应返回 ["run","-d","-p","8888:9090","--name","c1","img1"]
    // （extraRunArgs 为空时不插入任何东西）
    var builder = DefaultCompileAndDeployTool.class.getDeclaredMethod(
            "buildDockerRunCommand", String.class, String.class, int.class, int.class, java.util.List.class);
    builder.setAccessible(true);
    @SuppressWarnings("unchecked")
    List<String> cmd = (List<String>) builder.invoke(tool, "img1", "c1", 8888, 9090, java.util.List.of());

    int pIdx = cmd.indexOf("-p");
    assertThat(pIdx).isGreaterThan(0);
    assertThat(cmd.get(pIdx + 1)).isEqualTo("8888:9090");
}

@Test
void dockerRun_noServerPortEnv() throws Exception {
    var builder = DefaultCompileAndDeployTool.class.getDeclaredMethod(
            "buildDockerRunCommand", String.class, String.class, int.class, int.class, java.util.List.class);
    builder.setAccessible(true);
    @SuppressWarnings("unchecked")
    List<String> cmd = (List<String>) builder.invoke(
            new DefaultCompileAndDeployTool(new LoomAgentProperties()),
            "img1", "c1", 8080, 8080, java.util.List.of());

    assertThat(cmd).doesNotContain("SERVER_PORT");
    assertThat(cmd).doesNotContain("-e");
}
```

- [ ] **Step 5.2: 跑测试确认失败**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
./mvnw test -pl spring-ai-loom-agent-test -Dtest='DefaultCompileAndDeployToolTest#dockerRun_mapsPortToContainerPort+dockerRun_noServerPortEnv' -q
```

期望：FAIL（`buildDockerRunCommand` 方法还不存在）。

- [ ] **Step 5.3: 抽取 `buildDockerRunCommand` 静态方法 + 改 `dockerRun`**

`DefaultCompileAndDeployTool.java` 行 531-561 **替换**为：

```java
    /**
     * {@code docker rm -f <name> ; docker run -d -p <port>:<containerPort> --name <name> <image> [extraArgs]}。
     * 同名容器已存在时先清理。
     * <p>
     * 故意不加 {@code -e SERVER_PORT=...} —— 仓里 application.yml 的 server.port 原样生效；
     * 用户应在对话里告诉 LLM "server.port 是 X"，LLM 再传 containerPort=X。
     */
    private String dockerRun(String imageName, String containerName, int port, int containerPort) {
        String docker = resolveDockerCmd();
        // 先清理同名容器（force remove）
        List<String> rmArgs = new ArrayList<>();
        rmArgs.add("rm");
        rmArgs.add("-f");
        rmArgs.add(containerName);
        // 不关心 exitCode —— 不存在时也会非 0
        runProcess(wrapForWindows(docker, rmArgs), null, 15_000L);

        List<String> runArgs = buildDockerRunCommand(imageName, containerName, port, containerPort,
                compile.getExtraRunArgs());
        List<String> cmd = wrapForWindows(docker, runArgs);
        ExecOutcome out = runProcess(cmd, null, compile.getDockerRunTimeoutMs());
        if (out.timeout || out.exitCode != 0) {
            log.error("docker run failed. exitCode={}, timeout={}, tail=\n{}",
                    out.exitCode, out.timeout, tail(out.output, 60));
            return null;
        }
        return containerName;
    }

    /**
     * 构造 {@code docker run} 的参数列表（不含 docker 本身），便于测试断言。
     * 暴露为 package-private 静态是因为单元测试需要拿到 list 校验。
     */
    static List<String> buildDockerRunCommand(String imageName, String containerName, int port,
                                               int containerPort, List<String> extraRunArgs) {
        List<String> runArgs = new ArrayList<>();
        runArgs.add("run");
        runArgs.add("-d");
        runArgs.add("-p");
        runArgs.add(port + ":" + containerPort);
        runArgs.add("--name");
        runArgs.add(containerName);
        if (extraRunArgs != null && !extraRunArgs.isEmpty()) {
            runArgs.addAll(extraRunArgs);
        }
        runArgs.add(imageName);
        return runArgs;
    }
```

- [ ] **Step 5.4: 改 `compileAndDeploy` 内的调用**

`DefaultCompileAndDeployTool.java` 行 200：

**原**：
```java
            String runningContainer = dockerRun(effectiveImage, effectiveContainer, effectivePort);
```

**改为**：
```java
            String runningContainer = dockerRun(effectiveImage, effectiveContainer, effectivePort, effectiveContainerPort);
```

- [ ] **Step 5.5: 跑测试**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
./mvnw test -pl spring-ai-loom-agent-test -Dtest='DefaultCompileAndDeployToolTest' -q
```

期望：所有测试通过（包括 `dockerRun_mapsPortToContainerPort` 和 `dockerRun_noServerPortEnv`）。

- [ ] **Step 5.6: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployTool.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployToolTest.java
git commit -m "feat(compile): dockerRun 拆 port/containerPort，不加 -e SERVER_PORT"
```

---

## Task 6: `resolveEffectiveProjectDir` 加 `subDir` 入参（5 条规则）

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployTool.java:161-165`（调用方传 subDir）
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployTool.java:373-412`（`resolveEffectiveProjectDir` 签名 + 5 条规则）
- Modify: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployToolTest.java`（+3 测试）

- [ ] **Step 6.1: 写失败的测试（subDir 覆盖、subDir 缺失 fail、歧义 fail）**

追加：

```java
@Test
void resolveEffectiveProjectDir_subDirOverridesHeuristic() throws Exception {
    LoomAgentProperties props = new LoomAgentProperties();
    DefaultCompileAndDeployTool tool = new DefaultCompileAndDeployTool(props);

    // 模拟 sql-forge-demo 仓：根无 pom.xml，两个子目录，名字匹配的是 sql-forge-demo/，
    // 但用户传 subDir=spring-ai-chat-demo，应选 spring-ai-chat-demo/
    Path tmp = Files.createTempDirectory("loom-cdt-resolve-");
    try {
        Files.createDirectories(tmp.resolve("sql-forge-demo"));
        Files.createFile(tmp.resolve("sql-forge-demo/pom.xml"));
        Files.createDirectories(tmp.resolve("spring-ai-chat-demo"));
        Files.createFile(tmp.resolve("spring-ai-chat-demo/pom.xml"));

        var method = DefaultCompileAndDeployTool.class.getDeclaredMethod(
                "resolveEffectiveProjectDir", Path.class, String.class, String.class);
        method.setAccessible(true);
        Path picked = (Path) method.invoke(tool, tmp, "sql-forge-demo", "spring-ai-chat-demo");

        assertThat(picked.getFileName().toString()).isEqualTo("spring-ai-chat-demo");
    } finally {
        deleteRecursively(tmp);
    }
}

@Test
void resolveEffectiveProjectDir_subDirMissing_throwsWithCandidateList() throws Exception {
    LoomAgentProperties props = new LoomAgentProperties();
    DefaultCompileAndDeployTool tool = new DefaultCompileAndDeployTool(props);

    Path tmp = Files.createTempDirectory("loom-cdt-resolve-");
    try {
        Files.createDirectories(tmp.resolve("a"));
        Files.createFile(tmp.resolve("a/pom.xml"));
        Files.createDirectories(tmp.resolve("b"));
        Files.createFile(tmp.resolve("b/pom.xml"));

        var method = DefaultCompileAndDeployTool.class.getDeclaredMethod(
                "resolveEffectiveProjectDir", Path.class, String.class, String.class);
        method.setAccessible(true);

        // subDir 不存在时，工具应抛异常（IllegalArgumentException）或返回 fail —— 本任务选抛异常
        // 这样调用方 compileAndDeploy 能 catch 并转为 CompileAndDeployResult.fail
        assertThatThrownBy(() -> method.invoke(tool, tmp, "sql-forge-demo", "nonexistent"))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessageContaining("subDir='nonexistent'")
                .hasRootCauseMessageContaining("可选子目录：[a, b]");
    } finally {
        deleteRecursively(tmp);
    }
}

@Test
void resolveEffectiveProjectDir_ambiguousNoMatch_throwsWithCandidateList() throws Exception {
    LoomAgentProperties props = new LoomAgentProperties();
    DefaultCompileAndDeployTool tool = new DefaultCompileAndDeployTool(props);

    Path tmp = Files.createTempDirectory("loom-cdt-resolve-");
    try {
        // 根无 pom.xml，两个子目录都没有 pom.xml
        Files.createDirectories(tmp.resolve("aaa"));
        Files.createDirectories(tmp.resolve("bbb"));
        // 注意：aaa/、bbb/ 都没有 pom.xml —— 走 "歧义" 分支

        var method = DefaultCompileAndDeployTool.class.getDeclaredMethod(
                "resolveEffectiveProjectDir", Path.class, String.class, String.class);
        method.setAccessible(true);

        // 启发式无法唯一确定（既无名字匹配，子目录又 ≥ 2），应抛
        assertThatThrownBy(() -> method.invoke(tool, tmp, "sql-forge-demo", null))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessageContaining("多个子模块无法自动选择")
                .hasRootCauseMessageContaining("[aaa, bbb]");
    } finally {
        deleteRecursively(tmp);
    }
}
```

需要的 import（如缺）：
```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Path;
```

- [ ] **Step 6.2: 跑测试确认失败**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
./mvnw test -pl spring-ai-loom-agent-test -Dtest='DefaultCompileAndDeployToolTest#resolveEffectiveProjectDir_subDirOverridesHeuristic+resolveEffectiveProjectDir_subDirMissing_throwsWithCandidateList+resolveEffectiveProjectDir_ambiguousNoMatch_throwsWithCandidateList' -q
```

期望：FAIL（`resolveEffectiveProjectDir` 还是 2 参数版本）。

- [ ] **Step 6.3: 改 `resolveEffectiveProjectDir` 签名 + 5 条规则**

`DefaultCompileAndDeployTool.java` 行 390-412 **替换**为：

```java
    /**
     * 解析出整个构建流程真正要操作的目录（mvn / 写 Dockerfile / docker build 都用这个）。
     * <p>
     * 多模块项目仓库根目录往往没有 pom.xml（也没有可执行 jar），
     * 而第一层子目录里才是真正的 Spring Boot 工程。
     * 这一步选错子模块会导致 COPY target/xxx.jar 找不到文件、镜像构建失败。
     * <p>
     * 规则（按顺序）：
     * <ol>
     *   <li>{@code subDir} 非空 → 校验 {@code <projectDir>/<subDir>/pom.xml} 存在；
     *       存在返回该子目录；不存在抛 {@link IllegalArgumentException}，
     *       errorMessage 列出实际候选子目录，让 LLM 追问</li>
     *   <li>{@code subDir} 缺省 + {@code projectDir/pom.xml} 存在 → 单模块，返回 projectDir</li>
     *   <li>{@code subDir} 缺省 + 根无 pom.xml + 启发式名字匹配（子目录名 = repoName）唯一 → 选该子目录</li>
     *   <li>{@code subDir} 缺省 + 根无 pom.xml + 启发式无匹配 + 子目录数 = 1 → 兜底取该子目录</li>
     *   <li>{@code subDir} 缺省 + 根无 pom.xml + 启发式无匹配 + 子目录数 ≥ 2 →
     *       抛 {@link IllegalArgumentException}，errorMessage 列出候选子目录，让 LLM 追问</li>
     * </ol>
     */
    Path resolveEffectiveProjectDir(Path projectDir, String repoName, String subDir) {
        // 规则 1：subDir 显式
        if (subDir != null && !subDir.isBlank()) {
            Path target = projectDir.resolve(subDir);
            if (Files.isDirectory(target) && Files.isRegularFile(target.resolve("pom.xml"))) {
                log.info("resolveEffectiveProjectDir: subDir='{}' picked", subDir);
                return target;
            }
            List<Path> candidates = listChildDirs(projectDir);
            String names = formatNames(candidates);
            throw new IllegalArgumentException(
                    "参数错误：subDir='" + subDir + "' 在克隆后的仓库中不存在，可选子目录：" + names
                            + "，请向用户确认");
        }

        // 规则 2：单模块
        if (Files.isRegularFile(projectDir.resolve("pom.xml"))) {
            return projectDir;
        }

        // 规则 3-5：多模块启发式
        List<Path> children = listChildDirs(projectDir);
        if (children.isEmpty()) {
            return projectDir;
        }
        // 规则 3：名字匹配
        if (repoName != null && !repoName.isBlank()) {
            for (Path c : children) {
                if (c.getFileName().toString().equals(repoName)) {
                    log.info("resolveEffectiveProjectDir: picked submodule matching repo name. submodule={}", c);
                    return c;
                }
            }
        }
        // 规则 4 vs 5：单子目录兜底 vs 多子目录歧义
        if (children.size() == 1) {
            Path only = children.get(0);
            log.info("resolveEffectiveProjectDir: only one submodule, falling back to {}", only.getFileName());
            return only;
        }
        // 规则 5：歧义
        String names = formatNames(children);
        throw new IllegalArgumentException(
                "参数错误：仓库根目录无 pom.xml，且多个子模块无法自动选择，可选子目录：" + names
                        + "，请向用户确认要部署哪个");
    }

    private static String formatNames(List<Path> paths) {
        List<String> names = new ArrayList<>();
        for (Path p : paths) {
            names.add(p.getFileName().toString());
        }
        return names.toString();
    }
```

- [ ] **Step 6.4: 改 `compileAndDeploy` 内的调用 + 解析 subDir + catch 异常**

`DefaultCompileAndDeployTool.java` 行 141-165 **修改**：

**Step 6.4a**: 在行 141（`paramBaseImage` 解析之前）增加 `subDir` 解析：

```java
        // 解析 subDir —— 多模块仓显式选子模块
        String subDir = str(flat, "subDir", "sub_dir", "module", "submodule");
```

**Step 6.4b**: 行 161-165 整段替换：

```java
            // Step 2: mvn package
            // 多模块项目根目录无 pom.xml —— 解析出真正的工作目录
            Path effectiveDir;
            try {
                effectiveDir = resolveEffectiveProjectDir(projectDir, repoName, subDir);
            } catch (IllegalArgumentException subErr) {
                steps.add("❌ 子模块解析失败：" + subErr.getMessage());
                return CompileAndDeployResult.fail(workspace.toString(), gitUrl, effectiveImage,
                        effectiveContainer, effectivePort, effectiveHealthPath, steps,
                        subErr.getMessage());
            }
            if (!effectiveDir.equals(projectDir)) {
                log.info("Multi-module layout detected. projectDir={}, effectiveDir={}", projectDir, effectiveDir);
            }
```

- [ ] **Step 6.5: 跑测试**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
./mvnw test -pl spring-ai-loom-agent-test -Dtest='DefaultCompileAndDeployToolTest' -q
```

期望：所有测试通过（包括 3 个新测试）。**注意**：`resolveEffectiveProjectDir` 是上一轮新加的方法，本任务之前没有针对它的单元测试；如有任何旧 2-参数反射调用，按 3 参数（`subDir=null`）补齐即可，行为兼容（subDir=null 走规则 2/3/4/5 的启发式分支）。

- [ ] **Step 6.6: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployTool.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/compile/DefaultCompileAndDeployToolTest.java
git commit -m "feat(compile): resolveEffectiveProjectDir 加 subDir 显式入参，歧义时 fail"
```

---

## Task 7: `package-docker.st` skill 模板改写

**Files:**
- Modify: `spring-ai-loom-agent-test/src/main/resources/skills/package-docker.st`（整段替换）

- [ ] **Step 7.1: 替换 skill 模板全文**

`package-docker.st` **整段替换**为：

```
用户希望"测试自动编译"——克隆代码 → 编译 → 构建镜像 → 启动容器 → 拿到访问链接。

请严格按以下三步执行：

1. 从用户消息里提取以下参数。port 和 containerPort 任何一个缺失都必须先向用户追问，
   不要瞎猜、不要用 8080 之类的兜底：

   - gitUrl：Git 仓库 URL（必填，URL 形式）
   - gitUsername：Git 用户名（公开仓库可省略，私有仓库必填）
   - gitPassword：Git 密码或 token（同上）
   - port：宿主机对外端口（必填，例如用户说"我要在 9090 访问" → port=9090）
   - containerPort：应用在容器内实际监听的端口（必填）
      如果用户没明说，提示"请确认仓里 application.yml 的 server.port 是多少"，
      让用户回答后再调用。如果仓没有 application.yml 或没有 server.port 字段，
      默认 8080 并告知用户
   - subDir（可选）：多模块仓库指定子模块名。如果不指定，工具会自动尝试，但可能选错——
      多模块场景下请主动向用户确认要部署哪个
   - baseImage（可选）：java17 / java21 / nginx / python3 或完整镜像名
   - runCommand（可选）：覆盖 ENTRYPOINT 的字符串数组
   - healthPath（可选）：默认 "/"；没有 context-path 时传 "/"

   ⚠️ 重要：不要把仓库名当成 branch！只有当用户**明确说出** "用 main 分支" / "用 develop 分支" / "切到 feature-xxx 分支" 等具体分支名时才传 branch 字段。
   仓库名 ≠ 分支名（"sql-forge-demo" 是仓库名，不是分支名）。

2. 把上述参数传给 @compileAndDeploy 工具，不要在工具外做任何额外的 shell / mvn / docker 操作：
   {
     "gitUrl": "用户提供的 URL",
     "gitUsername": "用户名（可省略）",
     "gitPassword": "密码（可省略）",
     "port": <port>,
     "containerPort": <containerPort>,
     "subDir": "<可选>",
     "baseImage": "<可选>",
     "runCommand": [<可选>],
     "healthPath": "/"
   }

3. 工具返回结果后：
   - 若 result.success == true：用 <a href="result.accessUrl" target="_blank">result.accessUrl</a> 展示访问链接
   - 若 result.success == false：把 result.steps 里的每一步用 Markdown 列表展示给用户，
     并在末尾附上 result.errorMessage，让用户知道卡在哪一步

注意：不要手动调用 git / mvn / docker 工具，也不要拆分 @compileAndDeploy。
整个流程是 LLM → 单次工具调用 → 渲染结果。
```

- [ ] **Step 7.2: 跑测试**

skill 模板没有直接单测；跑 `spring-ai-loom-agent-test` 全部测试确认没破坏：

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
./mvnw test -pl spring-ai-loom-agent-test -q
```

期望：BUILD SUCCESS（skill 模板不参与编译，但任务跑一遍确保整体 OK）。

- [ ] **Step 7.3: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
git add spring-ai-loom-agent-test/src/main/resources/skills/package-docker.st
git commit -m "docs(skill): package-docker 模板去 port 硬编码，强制追问"
```

---

## Task 8: 6 份文档同步

**Files:**
- Modify: `API.md`
- Modify: `API.zh-CN.md`
- Modify: `README.md`
- Modify: `README.zh-CN.md`
- Modify: `CUSTOMIZATION.md`
- Modify: `CUSTOMIZATION.zh-CN.md`

- [ ] **Step 8.1: 找出所有 `default-port` / `defaultPort` 引用**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
grep -nH "default-port\|defaultPort" API.md API.zh-CN.md README.md README.zh-CN.md CUSTOMIZATION.md CUSTOMIZATION.zh-CN.md
```

期望输出：6 个文件里所有 `default-port: 8080` / `defaultPort: 8080` 出现的位置。

- [ ] **Step 8.2: 找出所有 `base-image: eclipse-temurin:17-jre-alpine` / `baseImage: ...` 引用**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
grep -nH "base-image: eclipse-temurin\|baseImage: eclipse-temurin" API.md API.zh-CN.md README.md README.zh-CN.md CUSTOMIZATION.md CUSTOMIZATION.zh-CN.md
```

期望输出：6 个文件里所有 `base-image` 兜底镜像出现的位置。

- [ ] **Step 8.3: 修改每个文档**

每个文档做 3 件事：

1. **删 yml 示例中的 `default-port: 8080` 行**
2. **删 yml 示例中的 `base-image: eclipse-temurin:17-jre-alpine` 行**（保留 `image-templates` 段）
3. **在工具入参表格（@Tool description 等地方）增加 `containerPort` / `subDir` 行**：

```markdown
| `containerPort` | 整数 | 容器内应用监听端口（必填，无 yml 兜底；参考 application.yml 的 server.port） |
| `subDir`        | 字符串 | 多模块仓库子目录（可选，缺省时多模块可能选错子模块） |
```

文件对中英文版的处理完全相同（只是文字内容不同）。

- [ ] **Step 8.4: 跑 `mvn install` 验证全量编译 + 测试**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
./mvnw clean install -Dgpg.skip=true -q
```

期望：BUILD SUCCESS，所有 25 个测试通过。

- [ ] **Step 8.5: Commit**

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
git add API.md API.zh-CN.md README.md README.zh-CN.md CUSTOMIZATION.md CUSTOMIZATION.zh-CN.md
git commit -m "docs(compile): 同步 6 份文档：删 default-port/base-image，加 containerPort/subDir"
```

---

## 执行完成检查

全部 8 任务完成后：

1. **最终验证**：

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
./mvnw clean install -Dgpg.skip=true -q
```

期望：BUILD SUCCESS，**至少 25 个测试通过**（17 现有 + 8 新增）。

2. **git log 检查提交历史**：

```bash
cd C:/developer/IdeaProjects/spring-ai-loom-agent
git log --oneline -8
```

期望：看到 8 个 commit，每个任务一个。

3. **spec 对照**（spec 自查）：

| spec 段 | 实现任务 |
|---------|----------|
| 1. 删 yml 字段 | Task 1 |
| 2. 入参表更新 | Task 2 |
| 3. resolveEffectiveProjectDir 5 条规则 | Task 6 |
| 4. Dockerfile / docker run 拆 port/containerPort | Task 4 + Task 5 |
| 5. skill 模板改写 | Task 7 |
| 6. 测试 8 个新增 | Task 1（2）+ Task 3（2）+ Task 4（1）+ Task 5（2）+ Task 6（3）— 共 10 个测试 |
| 7. 6 份文档 | Task 8 |

10 个新增测试（spec 写 8 个，实际多出 2 个反射测试属"防回归"性质，不影响 spec 完整性）。
