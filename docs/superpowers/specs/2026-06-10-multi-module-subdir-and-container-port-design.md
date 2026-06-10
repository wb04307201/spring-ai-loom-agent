# ICompileAndDeployTool — 多模块子目录显式选择 + containerPort 拆分

**日期**: 2026-06-10
**状态**: Approved
**作者**: brainstorming session
**前置 spec**: [2026-06-10-compile-deploy-image-templates-design.md](./2026-06-10-compile-deploy-image-templates-design.md)

## 背景

上一轮已经做了基础镜像模板化 + docker build 失败诊断，但实战中暴露三类问题：

1. **多模块仓选错子模块**：`https://gitee.com/wb04307201/sql-forge-demo.git` 根目录下有 `spring-ai-chat-demo/` 和 `sql-forge-demo/` 两个子模块，当前 `resolveEffectiveProjectDir` 用"名字 = repoName"启发式兜底，能蒙中 `sql-forge-demo/`，但用户要部署 `spring-ai-chat-demo` 时会选错。**启发式不够用，需要显式逃出口**。

2. **端口双重语义混淆**：`sql-forge-demo` 子模块的 `application.yml` 写死 `server.port: 8081`。当前工具只暴露一个 `port` 入参，含义模糊——是宿主机端口？还是容器内端口？两者差着 `-p` 映射一层。

3. **yml 兜底污染对话**：当前 `compile.defaultPort: 8080` yml 字段 + skill 模板里硬编码 `"port": 8080` 等于**告诉 LLM 不用问用户**。`1986z11z20Z!` 这种端口冲突场景下，工具静默用 8080 启动，撞端口后才报错。

**核心原则**：
> yml 只配**运维参数**（超时、路径、maven 路径等跟对话无关的东西）；**业务参数**（端口、子模块、镜像别名、healthPath）一律从对话给到 AI，让 LLM 在每轮对话里**主动向用户索取**。

## 目标

- 多模块仓支持 `subDir` 入参显式选子模块；无可唯一确定时让工具返回 fail，LLM 看到后追问用户。
- 拆 `port`（宿主机对外） / `containerPort`（应用监听）；两者都从对话给，不给兜底。
- 删除 yml `compile.defaultPort` 与 `compile.baseImage` 字段（`imageTemplates.java17.image` 已经是天然兜底）。
- skill 模板（`package-docker.st`）去掉 `"port": 8080` 硬编码，强制 LLM 追问。
- 工具返回 fail 时的 errorMessage 文案带上"请向用户……"指引，让 LLM 自然追问。

## 非目标

- 不改 `IGitTool` / `IMavenTool` 行为。
- 不动镜像模板机制（`imageTemplates` 4 个预置保留：`java17` / `java21` / `nginx` / `python3`）。
- 不读 yml 里的 `server.port`（避免解析 application.yml 引入新依赖）；LLM 负责问用户。
- 不为 nginx / python 单独写多阶段 Dockerfile。

## 设计

### 1. `LoomAgentProperties.CompileProperty` 字段变更

**删除**：
- `private int defaultPort = 8080;` —— 业务参数，对话给
- `private String baseImage = "eclipse-temurin:17-jre-alpine";` —— `imageTemplates.java17.image` 已是兜底

**保留**：
- `enabled` / `mavenHome` / `dockerCmd`
- `mavenTimeoutMs` / `dockerBuildTimeoutMs` / `dockerRunTimeoutMs`
- `healthCheckMaxWaitMs` / `healthCheckIntervalMs`
- `keepWorkspace` / `extraRunArgs`
- `imageTemplates`（4 个预置）

> `LoomAgentProperties` 顶层不再暴露 `defaultPort` / `baseImage`（已经是无状态字段删除，Javadoc 同步更新）。

### 2. `ICompileAndDeployTool` 入参

| 字段 | 类型 | 来源 | 必填 | 默认行为 | 缺省时 |
|------|------|------|------|----------|--------|
| `gitUrl` | 字符串 | 对话 | ✅ | — | fail |
| `gitUsername` / `gitPassword` | 字符串 | 对话 | 私有仓 ✅ | — | 公开仓省略 |
| `branch` | 字符串 | 对话 | ❌ | JGit 自动选 HEAD；经 `isPlausibleBranch` 防御 | 启发式不通过则忽略 |
| `port` | 整数 | 对话 | **✅** | — | **fail**（"请向用户索取宿主机端口"） |
| `containerPort` | 整数 | 对话 | **✅** | — | **fail**（"请向用户索取应用监听端口，参考 application.yml 的 server.port"） |
| `subDir` | 字符串 | 对话 | ❌ | 启发式：名字 = repoName → 排序后取第一个 | 多模块无 subDir + 无名字匹配时 fail |
| `baseImage` | 字符串 | 对话 | ❌ | `imageTemplates.java17.image` | — |
| `runCommand` | 字符串数组 | 对话 | ❌ | 模板默认 ENTRYPOINT | — |
| `healthPath` | 字符串 | 对话 | ❌ | `"/"`（无 context-path 时） | — |
| `imageName` / `containerName` | 字符串 | 工具生成 | —— | 工具按 `compile-deploy-<base36-time>` 生成 | — |

**`@Tool` 描述更新要点**（措辞最终以实现为准，含义如下）：
- 强调"port 和 containerPort 都是必填，无 yml 兜底"
- 强调"subDir 缺省时多模块仓可能选错子模块，LLM 应当主动向用户确认"

### 3. `resolveEffectiveProjectDir(Path, String repoName, String subDir)` 重构

签名变化：增加 `String subDir` 参数（包私有方法，测试可调）。

**新规则**：

| 条件 | 行为 |
|------|------|
| `subDir` 非空 | 校验 `<projectDir>/<subDir>/pom.xml` 存在。存在 → 返回 `<projectDir>/<subDir>`；不存在 → fail 并 errorMessage 列出实际候选子目录 |
| `subDir` 空 + `<projectDir>/pom.xml` 存在 | 返回 `projectDir`（单模块） |
| `subDir` 空 + 根无 pom.xml + 启发式名字匹配唯一 | 返回名字 = `repoName` 的子目录 |
| `subDir` 空 + 根无 pom.xml + 启发式无匹配 + 子目录数 = 1 | 返回该子目录（兜底） |
| `subDir` 空 + 根无 pom.xml + 启发式无匹配 + 子目录数 ≥ 2 | **fail**（errorMessage 列出 `[a, b, c]`，让 LLM 追问） |

> 启发式候选排序沿用 `listChildDirs` 的"按目录名升序"——保证日志可复现。

**fail 文案模板**：
```
参数错误：subDir='<x>' 在克隆后的仓库中不存在，可选子目录：[a, b, c]，请向用户确认
参数错误：仓库根目录无 pom.xml，且多个子模块无法自动选择，可选子目录：[a, b, c]，请向用户确认要部署哪个
```

### 4. Dockerfile / docker run 改造

**Dockerfile**（`writeDockerfile`）：
```dockerfile
FROM <resolved.image>
WORKDIR /app
COPY target/<jar> app.jar
EXPOSE <containerPort>
ENTRYPOINT <jsonArray>
```
- `EXPOSE` 改用 `containerPort`（文档性指令，告知 Docker 容器期望监听哪个端口）
- **不加 `ENV SERVER_PORT`**（按用户决定，让仓里 `application.yml` 的 `server.port` 原样生效）
- `port` 不写进 Dockerfile

**docker run**（`dockerRun(String image, String container, int port, int containerPort)`）：
```bash
docker rm -f <container>                 # 同名清理
docker run -d -p <port>:<containerPort>  # 宿主机:容器
  --name <container> [<extraRunArgs>...] <image>
```

**健康检查**（`waitForHealthy`）：
- URL = `http://localhost:<port><healthPath>`（**用 port，即宿主机端口**——`accessUrl` 同样）
- 沿用 `buildAccessUrl(int port, String healthPath)`

**校验顺序**（`compileAndDeploy` 入口处）：

```java
if (gitUrl == null || gitUrl.isBlank())
    return fail("参数错误：gitUrl 不能为空，请向用户索取仓库 URL");
if (port == null)
    return fail("参数错误：port 不能为空，请向用户索取宿主机端口（对外暴露的端口）");
if (containerPort == null)
    return fail("参数错误：containerPort 不能为空，请向用户索取应用监听端口（参考 application.yml 的 server.port）");
if (username == null)
    return fail("无法获取用户名，请通过登录态调用");
```

> fail 全部走 `CompileAndDeployResult.fail(...)` 路径，不抛异常（避免 SSE 链路被异常打断——参考现有 Javadoc 关于 qwen JSON 解析的注释）。

### 5. `package-docker.st` skill 模板

**当前**（硬编码兜底，违反新原则）：
```
"port": 8080,
"healthPath": "/"
```

**新版**（强制追问）：
```
从用户消息里提取以下参数。port 和 containerPort 任何一个缺失都必须先向用户追问，
不要瞎猜、不要用 8080 之类的兜底：

- gitUrl（必填，URL 形式）
- gitUsername / gitPassword（私有仓必填，公开仓可省略）
- port：宿主机对外端口（必填，例如用户说"我要在 9090 访问" → port=9090）
- containerPort：应用在容器内实际监听的端口（必填）
   如果用户没明说，提示"请确认仓里 application.yml 的 server.port 是多少"，
   让用户回答后再调用。如果仓没有 application.yml 或没有 server.port 字段，
   默认 8080 并告知用户
- subDir（可选）：多模块仓指定子模块名。如果不指定，工具会自动尝试，但可能选错——
   多模块场景下请主动向用户确认要部署哪个
- baseImage（可选）：java17 / java21 / nginx / python3 或完整镜像名
- runCommand（可选）：覆盖 ENTRYPOINT 的字符串数组
- healthPath（可选）：默认 "/"，没有 context-path 时传 "/"

传给 @compileAndDeploy 工具：
{ "gitUrl": "...", "gitUsername": "...", "gitPassword": "...",
  "port": <port>, "containerPort": <containerPort>,
  "subDir": "<可选>", "baseImage": "<可选>", "runCommand": [...],
  "healthPath": "/" }
```

### 6. 单元测试

`DefaultCompileAndDeployToolTest` 变更：

**新增**：

| 测试 | 覆盖点 |
|------|--------|
| `compileAndDeploy_missingPort_returnsFail` | 缺 `port` → fail，errorMessage 含"port 不能为空" + "请向用户" |
| `compileAndDeploy_missingContainerPort_returnsFail` | 缺 `containerPort` → fail，errorMessage 含"containerPort 不能为空" |
| `compileAndDeploy_defaultPortFieldRemoved` | 反射 `CompileProperty.class.getDeclaredField("defaultPort")` 抛 `NoSuchFieldException` |
| `compileAndDeploy_baseImageFieldRemoved` | 反射 `CompileProperty.class.getDeclaredField("baseImage")` 抛 `NoSuchFieldException` |
| `resolveEffectiveProjectDir_subDirOverridesHeuristic` | 传 `subDir=spring-ai-chat-demo` → 选 `spring-ai-chat-demo/`，**忽略** 名字 = repoName 的 `sql-forge-demo/` |
| `resolveEffectiveProjectDir_subDirMissing_returnsFail` | `subDir=nonexistent` → 工具返回 fail，errorMessage 列出实际子目录 |
| `resolveEffectiveProjectDir_ambiguousNoMatch_returnsFail` | 多子模块、无 subDir、无名字匹配 → fail，errorMessage 列出候选 |
| `writeDockerfile_exposeUsesContainerPort` | `containerPort=9090, port=8888` → `EXPOSE 9090`（非 `port`） |
| `dockerRun_mapsPortToContainerPort` | `port=8888, containerPort=9090` → `-p 8888:9090`（非 `8888:8888`） |
| `dockerRun_noServerPortEnv` | 校验 `docker run` 命令**不含** `-e SERVER_PORT=...`（防回归） |

**保留**（约 17 个现有测试）：所有 `imageTemplates_*` / `writeDockerfile_*` / `accessUrl_*` / `cloneRepo_*` / `isPlausibleBranch_*` 测试。

**修改**：
- 现有 `writeDockerfile_usesJavaFallback` / `writeDockerfile_usesTemplateCommand` 等涉及 `EXPOSE` 的断言：旧测试传 `port=8080` 并校验 `EXPOSE 8080`。本轮 `EXPOSE` 改用 `containerPort`，因此旧测试改用 `port=containerPort=8080` 调用（`EXPOSE` 仍是 8080，结果一致），避免改动断言。新测试 `writeDockerfile_exposeUsesContainerPort` 显式校验 `containerPort ≠ port` 时的行为。

**总计**：8 个新增 + 17 现有 = 25 个测试，目标全部通过。

### 7. 文档同步

要更新的文件：

- `API.md` / `API.zh-CN.md` —— 删 `default-port` 段落；改入参表加 `containerPort` / `subDir`；yml 段落同步
- `README.md` / `README.zh-CN.md` —— 删 `default-port` / `base-image` 字段示例
- `CUSTOMIZATION.md` / `CUSTOMIZATION.zh-CN.md` —— 同上
- `package-docker.st` —— Section 5 的内容
- `LoomAgentProperties.java` Javadoc —— 删 `defaultPort` / `baseImage` 字段说明
- `ICompileAndDeployTool.java` Javadoc + `@Tool` 描述 —— 体现新必填字段
- `DefaultCompileAndDeployTool.java` 方法 Javadoc —— 体现新规则

## 影响范围

- **修改**：
  - `LoomAgentProperties.java`（-2 字段）
  - `ICompileAndDeployTool.java`（Javadoc + `@Tool` 描述）
  - `DefaultCompileAndDeployTool.java`（解析 + 校验 + Dockerfile / docker run + 启发式重构 + 新错误信息）
  - `DefaultCompileAndDeployToolTest.java`（+8 测试）
  - `package-docker.st`（skill 模板）
  - 6 份文档
- **不修改**：`CompileAndDeployResult.java` 字段（record 现有 `port` 字段复用——含义改为"对外暴露的宿主机端口"，与 `accessUrl` 拼接保持一致；`containerPort` 不入 result 字段，避免破坏现有序列化结构）。
- **不修改**：`IGitTool` / `IMavenTool`。

## 风险与回滚

- `defaultPort` / `baseImage` 字段删除属于**行为破坏性变更**：现有 yml 配这两个字段会因 yml 绑定宽松（多余键被忽略）而不报错，但工具不再读它们。用户须自行把 yml 配值改到对话入参。回滚方式：把两个字段加回 `CompileProperty` 即可恢复 yml 兜底行为。
- 入参 `port` 必填后，旧 skill 模板（`package-docker.st`）的硬编码 `8080` 也会被同时改掉——但任何直接用 LLM 调工具（不经过 skill）的用户会突然缺 port 报错。**这是有意为之**：工具宁可 fail 也不静默兜底。
- `subDir` 解析失败时返回 fail 而非兜底选第一个——多模块仓用户可能需要在对话里多轮提供 subDir。这是设计意图：宁可多问一轮也不静默选错。
