# Spring AI LoomAgent — 内置工具

> LoomAgent 默认暴露给 LLM 的所有工具参考手册。每个工具组可独立启用/禁用，每个子工具接口可通过自定义 `@Bean` 整体替换。

---

## 目录

- [1. 工具启用开关](#1-工具启用开关)
- [2. `IEmbedTool` 总览](#2-iembedtool-总览)
- [3. `ITimeTool` — 时间工具](#3-itimetool--时间工具)
- [4. `ISkillTool` — 技能工具](#4-iskilltool--技能工具)
- [5. `IFileTool` — 文件工具](#5-ifiletool--文件工具)
- [6. `IGitTool` — Git 工具（JGit）](#6-igittool--git-工具jgit)
- [7. `IMavenTool` — Maven 构建工具（maven-invoker）](#7-imaventool--maven-构建工具maven-invoker)
- [8. `ICompileAndDeployTool` — 端到端部署](#8-icompileanddeploytool--端到端部署)
  - [8.1 工具入参](#81-工具入参)
  - [8.2 配置属性](#82-配置属性)
  - [8.3 预置基础镜像模板](#83-预置基础镜像模板)
  - [8.4 工具入参示例](#84-工具入参示例)
  - [8.5 端到端对话示例](#85-端到端对话示例)
- [9. 替换子工具](#9-替换子工具)

---

## 1. 工具启用开关

每个内置工具组都通过 `spring.ai.loom.agent.*` 下的 `*.enabled` 属性控制开关。`time` / `file` / `skill` / `compile` 默认启用；`git` 和 `maven` 是 **opt-in**（默认关闭），因为端到端部署已经覆盖了编译/打包场景。

| 属性                 | 类型     | 默认值   | 说明                                                       |
|--------------------|--------|-------|----------------------------------------------------------|
| `time.enabled`     | boolean | `true`  | 时间工具（`ITimeTool` — 当前时间、时区转换）                            |
| `file.enabled`     | boolean | `true`  | 文件工具（`IFileTool` — 16 个基于路径的读写/编辑/删除）                     |
| `skill.enabled`    | boolean | `true`  | 技能工具（`ISkillTool` — 列出技能、获取技能详情）                          |
| `git.enabled`      | boolean | `false` | Git 工具（`IGitTool` — 28 个 git 操作，基于 JGit）。**opt-in** — 端到端部署走 `ICompileAndDeployTool`。 |
| `maven.enabled`    | boolean | `false` | Maven 构建工具（`IMavenTool` — 同时要求 classpath 上有 `maven-invoker`）。**opt-in** — 编译/打包走 `ICompileAndDeployTool`。 |
| `compile.enabled`  | boolean | `true`  | 端到端部署工具（`ICompileAndDeployTool` — git clone → 按 buildTool 打包 [maven/npm/pip] → docker build → docker run → health check）。支持 Spring Boot、Node（后端 + 静态前端 → nginx）、Python 等多栈项目。 |

> 即便工具组被禁用，你仍可注册自己的 `@Bean IGitTool` / `@Bean IMavenTool` 来重新启用 — `@ConditionalOnMissingBean` 优先使用用户提供的 Bean。

### 关闭示例

```yaml
spring:
  ai:
    loom:
      agent:
        git:
          enabled: false
        maven:
          enabled: false
```

---

## 2. `IEmbedTool` 总览

`IEmbedTool` 是聚合标记接口。子接口（`ITimeTool`、`ISkillTool`、`IFileTool`、`IGitTool`、`IMavenTool`）各自向 LLM 提供独立的 `@Tool` 方法。`ICompileAndDeployTool` 同样继承 `IEmbedTool`，是部署场景的推荐入口。

| 子接口                       | 默认实现                              | 方法数  | 默认状态      | 备注                                          |
|--------------------------|-----------------------------------|------|-----------|---------------------------------------------|
| `ITimeTool`              | `DefaultTimeTool`                 | 2    | 启用        | 未设 `time.enabled` 时始终开启                     |
| `ISkillTool`             | `DefaultSkillTool`                | 2    | 启用        | 从 `user_skill`（数据库）读取；init migration seed 6 个 system skill —— yml `skills[]` 不再读取 |
| `IFileTool`              | `DefaultFileTool`                 | 16   | 启用        | 基于路径；根目录 = `{fileBasePath}/{username}/` |
| `IGitTool`               | `DefaultGitTool`（JGit 7.6）         | 28   | **禁用**    | 通过 `git.enabled=true` 开启                     |
| `IMavenTool`             | `DefaultMavenTool`（maven-invoker 3.3.0） | 6 | **禁用**    | 通过 `maven.enabled=true` 开启；classpath 需有 `maven-invoker` |
| `ICompileAndDeployTool`  | `DefaultCompileAndDeployTool`     | 1    | 启用        | 端到端 `git clone → build → docker run → health check` |

---

## 3. `ITimeTool` — 时间工具

| 项目       | 内容                                                                     |
|----------|------------------------------------------------------------------------|
| **接口**   | `cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool`                     |
| **默认实现** | `DefaultTimeTool`                                                      |
| **覆盖方式** | 自定义 `@Bean ITimeTool`                                                  |
| **状态**   | 默认启用；通过 `spring.ai.loom.agent.time.enabled` 切换                       |
| **方法**   | `getCurrentTime`（获取指定时区的当前时间）、`convertTime`（在不同时区之间转换时间） |

---

## 4. `ISkillTool` — 技能工具

| 项目       | 内容                                                                     |
|----------|------------------------------------------------------------------------|
| **接口**   | `cn.wubo.spring.ai.loom.agent.tool.skill.ISkillTool`                   |
| **默认实现** | `DefaultSkillTool`                                                     |
| **覆盖方式** | 自定义 `@Bean ISkillTool`                                                 |
| **状态**   | 默认启用；通过 `spring.ai.loom.agent.skill.enabled` 切换                      |
| **方法**   | `skillContents`（列出当前用户可用技能）、`getSkill`（根据名称获取技能详情） |
| **数据源** | `user_skill`（数据库）。每次调用前 `DefaultSkillStorage` 自动 sync `role_skill` → `user_skill`（locked 的 ROLE_GRANTED 条目）。对 admin 还会附带** union view**：所有 APPROVED + 自己的 PENDING（source=`MARKET_VIEW`）。 |

---

## 5. `IFileTool` — 文件工具

| 项目       | 内容                                                                     |
|----------|------------------------------------------------------------------------|
| **接口**   | `cn.wubo.spring.ai.loom.agent.tool.file.IFileTool`                     |
| **默认实现** | `DefaultFileTool`                                                      |
| **覆盖方式** | 自定义 `@Bean IFileTool`                                                  |
| **状态**   | 默认启用；通过 `spring.ai.loom.agent.file.enabled` 切换                       |
| **根路径** | 所有基于路径的操作以 `{fileBasePath}/{username}/`（默认 `.local/file/{username}/`）为根目录 |

**方法（16 个）**：

| 方法                       | 用途                                                                 |
|--------------------------|--------------------------------------------------------------------|
| `readTextFile`           | 读取单个文本文件                                                          |
| `readMediaFile`          | 读取媒体文件（图片/音频）                                                    |
| `readMultipleFiles`      | 一次读取多个文件                                                          |
| `writeFile`              | 创建或覆盖写入文件                                                        |
| `editFile`               | 在已存在的文件中进行定点文本编辑                                                  |
| `createDirectory`        | 创建目录（递归）                                                          |
| `moveFile`               | 移动或重命名文件/目录                                                       |
| `searchFiles`            | 在文件树上做 glob/regex 搜索                                              |
| `listAllowedDirectories` | 列出 LLM 允许访问的目录                                                    |
| `listDirectory`          | 列出目录条目                                                            |
| `listDirectoryWithSizes` | 列出目录条目（含大小信息）                                                     |
| `directoryTree`          | 递归的目录树                                                            |
| `getFileInfo`            | 获取文件/目录的元信息（大小、修改时间、类型）                                            |
| `downloadFileUrl`        | 获取下载链接（自动创建临时 `file_info` 记录，`usage="temp"`）                        |
| `viewFileUrl`            | 获取预览链接（自动创建临时 `file_info` 记录）                                       |
| `deleteFileOrDirectory`  | 删除（需显式 `I_CONFIRM_DELETE` 确认，token 可在 `spring.ai.loom.agent.file.deleteConfirmToken` 改）；支持递归删除目录；清理已删除文件对应的 `file_info` 记录         |

---

## 6. `IGitTool` — Git 工具（JGit）

| 项目       | 内容                                                                     |
|----------|------------------------------------------------------------------------|
| **接口**   | `cn.wubo.spring.ai.loom.agent.tool.git.IGitTool`                       |
| **默认实现** | `DefaultGitTool`（基于 Eclipse JGit 7.6.0）                               |
| **覆盖方式** | 自定义 `@Bean IGitTool`                                                   |
| **状态**   | `@ConditionalOnProperty(name = "spring.ai.loom.agent.git.enabled", havingValue = "true")` — **默认禁用** |
| **工作目录** | 通过 `gitSetWorkingDir` 设置（绝对路径或相对于 `{fileBasePath}/{username}/` 的相对路径）；`gitInit` / `gitClone` 也接受绝对路径或用户文件目录下的相对路径 |

**方法（28 个）**：

- **仓库生命周期**：`gitInit`、`gitClone`
- **基础操作**：`gitStatus`、`gitAdd`、`gitCommit`、`gitDiff`、`gitLog`
- **分支管理**：`gitBranch`、`gitCheckout`
- **远程操作**：`gitPull`、`gitPush`、`gitFetch`、`gitMerge`、`gitRebase`、`gitReset`
- **Stash / Tag / Remote**：`gitStash`、`gitTag`、`gitRemote`
- **检视**：`gitBlame`、`gitShow`、`gitReflog`
- **维护**：`gitClean`、`gitCherryPick`
- **Worktree**：`gitWorktree`、`gitSetWorkingDir`、`gitClearWorkingDir`
- **分析辅助**：`gitChangelogAnalyze`、`gitWrapupInstructions`

> 端到端部署（`git clone → build → docker run → health check`）请优先使用 `ICompileAndDeployTool`；`IGitTool` 适合单点 git 操作（status/log/blame/branch 等）。

---

## 7. `IMavenTool` — Maven 构建工具（maven-invoker）

| 项目       | 内容                                                                     |
|----------|------------------------------------------------------------------------|
| **接口**   | `cn.wubo.spring.ai.loom.agent.tool.maven.IMavenTool`                   |
| **默认实现** | `DefaultMavenTool`（基于 maven-invoker 3.3.0，不依赖 shell）              |
| **覆盖方式** | 自定义 `@Bean IMavenTool`                                              |
| **状态**   | `@ConditionalOnClass(name = "org.apache.maven.shared.invoker.Invoker")` 且 `@ConditionalOnProperty(name = "spring.ai.loom.agent.maven.enabled", havingValue = "true")` — **默认禁用，需 opt-in** |
| **方法（6 个）** | `mavenExecute`（通用 Maven 命令执行）、`mavenBuild`（编译）、`mavenPackage`（打包 JAR/WAR）、`mavenTest`（运行测试，支持测试模式匹配）、`mavenDependencyTree`（依赖树，支持范围过滤）、`mavenValidate`（验证项目结构） |

**配置属性**：

| 属性                                              | 类型     | 默认值       | 说明                                              |
|---------------------------------------------------|----------|-------------|---------------------------------------------------|
| `spring.ai.loom.agent.maven.enabled`              | boolean  | `false`     | 是否启用 Maven 工具（**opt-in**）—— 编译/打包走 `ICompileAndDeployTool` |
| `spring.ai.loom.agent.maven.mavenHome`            | String   | —           | Maven 安装目录（可选，空则使用 PATH）                  |
| `spring.ai.loom.agent.maven.localRepository`      | String   | —           | 本地仓库路径（可选）                                  |
| `spring.ai.loom.agent.maven.maxOutputLines`       | int      | `200`       | 输出最大行数（超出截断）                               |
| `spring.ai.loom.agent.maven.defaultTimeoutMs`     | long     | `300000`    | 默认执行超时（5 分钟）                                 |

> 部署流水线中的编译/打包请优先使用 `ICompileAndDeployTool`；仅当 LLM 需要执行单点 `mvn` 命令时再开启 `IMavenTool`。

---

## 8. `ICompileAndDeployTool` — 端到端部署

`ICompileAndDeployTool` 在单次 LLM tool call 内完成 `git clone → buildTool build (maven / npm / pip) → docker build → docker run → health check`。是 `git clone → build → docker run` 工作流的**推荐入口** —— LLM 只需传参，工具返回 `accessUrl`。

| 项目       | 内容                                                                                |
|----------|-----------------------------------------------------------------------------------|
| **接口**   | `cn.wubo.spring.ai.loom.agent.tool.compile.ICompileAndDeployTool`                  |
| **默认实现** | `DefaultCompileAndDeployTool`                                                     |
| **覆盖方式** | 自定义 `@Bean ICompileAndDeployTool`                                               |
| **状态**   | 默认启用；通过 `spring.ai.loom.agent.compile.enabled` 切换                            |
| **方法**   | `compileAndDeploy(Map<String,Object> params, ToolContext toolContext)` → `CompileAndDeployResult` |
| **工作区** | 每次调用在 `{fileBasePath}/{username}/compile-deploy-<uuid>/` 下创建独立工作区               |

### 8.1 工具入参

`params` 是大小写不敏感的 Map。必填：`gitUrl`、`port`、`containerPort`。其他按需提供。

| 键                  | 必填   | 说明                                                                                                              |
|--------------------|------|-----------------------------------------------------------------------------------------------------------------|
| `gitUrl`           | 是    | Git 仓库 URL                                                                                                       |
| `gitUsername`      | 否    | Git 用户名（公开仓库可省略）                                                                                                  |
| `gitPassword`      | 否    | Git 密码或 token（公开仓库可省略）                                                                                            |
| `branch`           | 否    | 克隆分支（默认远程 HEAD）                                                                                                  |
| `port`             | 是    | 宿主机对外端口（也是访问 URL 的端口，如 `http://localhost:{port}/{healthPath}`）                                                       |
| `containerPort`    | 是    | 容器内应用监听端口（无 yml 兜底，参考 application.yml 的 `server.port`）                                                            |
| `subDir`           | 否    | 多模块仓库的子目录；根目录无 `pom.xml` 时**必须**显式指定，否则工具会返回 fail                                                                          |
| `imageName`        | 否    | Docker 镜像名（默认按时间戳自动生成）                                                                                            |
| `containerName`    | 否    | Docker 容器名（默认按时间戳自动生成）                                                                                            |
| `healthPath`       | 否    | 健康检查路径，同时作为访问 URL 路径（如 `healthPath=sql-forge-demo` → `http://localhost:{port}/sql-forge-demo`；无 context-path 时传 `/`） |
| `buildTool`        | 否    | 构建栈：`maven` / `npm` / `npm-frontend` / `pip`。缺省时按 marker 文件自动探测（`pom.xml→maven`、`package.json→npm`、`requirements.txt` / `pyproject.toml→pip`）。多模块仓同时存在多个 marker 时必须显式指定。 |
| `baseImage`        | 否    | 基础镜像；支持模板别名（`java17` / `java21` / `nginx` / `python3` / `node20` / `node20-serve`）或完整镜像名（如 `openjdk:17-slim`）。缺省按 `buildTool` 自动选（`maven→java17`、`npm→node20`、`npm-frontend→node20-serve`、`pip→python3`）。 |
| `runCommand`       | 否    | 字符串数组，覆盖模板的默认 ENTRYPOINT（极少用）                                                                                       |

### 8.2 配置属性

所有配置位于 `spring.ai.loom.agent.compile.*` 下。

| 属性                                                       | 类型      | 默认值                       | 说明                                                                                                  |
|----------------------------------------------------------|---------|---------------------------|---------------------------------------------------------------------------------------------------|
| `spring.ai.loom.agent.compile.enabled`                    | boolean | `true`                    | 是否注册端到端部署工具（默认启用）                                                                                   |
| `spring.ai.loom.agent.compile.mavenHome`                  | string  | 自动探测                      | 可选 Maven 安装目录；回退到 `maven.mavenHome` 与 PATH                                                             |
| `spring.ai.loom.agent.compile.dockerCmd`                  | string  | `docker`                  | 可选 docker CLI 二进制覆盖                                                                                  |
| `spring.ai.loom.agent.compile.mavenTimeoutMs`             | long    | `600000`                  | Maven 编译超时（10 分钟）                                                                                    |
| `spring.ai.loom.agent.compile.dockerBuildTimeoutMs`       | long    | `600000`                  | `docker build` 超时（10 分钟）                                                                                |
| `spring.ai.loom.agent.compile.dockerRunTimeoutMs`         | long    | `60000`                   | `docker run` 启动超时（1 分钟）                                                                               |
| `spring.ai.loom.agent.compile.healthCheckMaxWaitMs`       | long    | `60000`                   | 容器启动后健康检查总等待（1 分钟）                                                                                   |
| `spring.ai.loom.agent.compile.healthCheckIntervalMs`      | long    | `2000`                    | 健康检查轮询间隔（2 秒）                                                                                        |
| `spring.ai.loom.agent.compile.keepWorkspace`              | boolean | `false`                   | 部署完成后是否保留工作区（默认删；调试时设 `true`）                                                                          |
| `spring.ai.loom.agent.compile.imageTemplates`             | map     | （6 个预置模板）                  | 按别名预置的基础镜像模板，见下                                                                                     |
| `spring.ai.loom.agent.compile.extraRunArgs`               | string[]| `[]`                      | 注入到 `--name` 与镜像名之间的额外 `docker run` 参数                                                                   |

### 8.3 预置基础镜像模板

| 别名               | 镜像                                | 默认 ENTRYPOINT                          |
|------------------|-----------------------------------|---------------------------------------------|
| `java17`         | `eclipse-temurin:17-jre-alpine`   | `["java","-jar","app.jar"]`                 |
| `java21`         | `eclipse-temurin:21-jre-alpine`   | `["java","-jar","app.jar"]`                 |
| `nginx`          | `nginx:1.27-alpine`               | `["nginx","-g","daemon off;"]`              |
| `python3`        | `python:3.12-slim`                | `["python","app.py"]`                       |
| `node20`         | `node:20-alpine`                  | `["node","dist/index.js"]`                  |
| `node20-serve`   | `nginx:1.27-alpine`               | `["nginx","-g","daemon off;"]`              |

可通过 yml 覆盖或新增模板：

```yaml
spring:
  ai:
    loom:
      agent:
        compile:
          image-templates:
            java17:
              image: eclipse-temurin:17-jre-alpine
              command: [java, -jar, app.jar]
            nginx:
              image: nginx:1.27-alpine
              command: [nginx, -g, "daemon off;"]
```

工具入参 `baseImage` 传别名即选中对应模板；传完整镜像名（如 `openjdk:17-slim`）则直接用，command 走 `java17` 兜底。

### 8.4 工具入参示例

```json
{
  "gitUrl": "https://gitee.com/wb04307201/sql-forge-demo.git",
  "port": 8081,
  "containerPort": 8080,
  "subDir": "sql-forge-web",
  "buildTool": "maven",
  "baseImage": "java17",
  "healthPath": "sql-forge-demo"
}
```

### 8.5 端到端对话示例

下面是一个**完整的对话场景**，展示用户如何在聊天中向 LLM 描述部署需求，LLM 如何追问缺失字段、抽取参数、调用端到端部署工具（即 `ICompileAndDeployTool`，注册名由 Spring AI 按方法名自动生成）。

#### 场景 A：Maven 多模块 Spring Boot 项目

**用户提示词**：

```text
帮我部署一下项目
Git 仓库：https://gitee.com/wb04307201/java-brain.git
用户名：wb04307201
密码：••••••••
子目录 oms 使用 maven 打包
环境：java17
宿主机端口：8081
容器内端口：8081
健康检查路径：/sql/forge/web
```

**LLM 抽取后下发的工具入参**（`buildTool` 由子模块里的 `pom.xml` 自动识别，无需用户显式说明）：

```json
{
  "gitUrl": "https://gitee.com/wb04307201/sql-forge-demo.git",
  "gitUsername": "wb04307201",
  "gitPassword": "<your-password>",
  "subDir": "sql-forge-demo",
  "buildTool": "maven",
  "port": 8081,
  "containerPort": 8081,
  "healthPath": "/sql/forge/web"
}
```

**工具内部推断**（无需用户显式说）：

| 入参 | 推断方式 |
| --- | --- |
| `baseImage` | `buildTool=maven` → 缺省 `java17`（用 `eclipse-temurin:17-jre-alpine`） |
| `imageName` / `containerName` | 按时间戳自动生成 |
| `runCommand` | 走 `java17` 模板的 `["java","-jar","app.jar"]` |

**部署完成后，工具返回**（示例）：

```text
✅ 部署成功
- 镜像：sql-forge-demo-20260612-153022
- 容器：sql-forge-demo-20260612-153022
- 访问 URL：http://localhost:8081/sql/forge/web
- 健康检查耗时：6.8s
- 镜像构建耗时：42.1s
```

#### 场景 B：Node 静态前端（Vue / React 构建产物 → nginx）

**用户提示词**：

```text
帮我部署 https://gitee.com/example/spa-admin.git，单仓，8088 端口，容器里 nginx 听 80，访问路径 /admin。
```

**LLM 抽取后下发的工具入参**：

```json
{
  "gitUrl": "https://gitee.com/example/spa-admin.git",
  "buildTool": "npm-frontend",
  "baseImage": "node20-serve",
  "port": 8088,
  "containerPort": 80,
  "healthPath": "/admin/"
}
```

**说明**：
- `buildTool=npm-frontend` → Dockerfile 会跑 `npm ci && npm run build`，把 `dist/` 拷进 nginx 镜像
- `baseImage=node20-serve` → 用预置的 nginx 模板；省略则按 `buildTool=npm-frontend` 缺省也走 `node20-serve`
- `containerPort=80` → nginx 默认监听端口；`port=8088` → 浏览器访问 `http://localhost:8088/admin/`
- `healthPath=/admin/` → 同时作为健康检查 URL 和访问 URL path

#### 场景 C：Python 项目

**用户提示词**：

```text
部署 https://gitee.com/example/py-service.git，9000 端口，requirements.txt 在根目录。
```

**LLM 抽取后下发的工具入参**：

```json
{
  "gitUrl": "https://gitee.com/example/py-service.git",
  "buildTool": "pip",
  "port": 9000,
  "containerPort": 9000,
  "healthPath": "/"
}
```

**说明**：
- `buildTool=pip` → Dockerfile 跑 `pip install -r requirements.txt`（如同时存在 `pyproject.toml`，按提示词优先 `requirements.txt`）
- `baseImage` 缺省按 `buildTool=pip` → `python3`（用 `python:3.12-slim`）
- `runCommand` 缺省走 `python3` 模板的 `["python","app.py"]`；若入口是 `gunicorn` / `uvicorn` 等，可显式传 `runCommand`

#### 几个常见变体

| 场景 | 调整 |
| --- | --- |
| 公开仓（无凭据） | 省略 `gitUsername` / `gitPassword` |
| 单模块仓（根目录即目标） | 省略 `subDir`；`buildTool` 也可省略（自动探测） |
| 切到特定分支 | 传 `branch=release/2.0`（默认走远程 HEAD） |
| Spring Boot 带 context-path | `healthPath` 传完整 context-path（开头带 `/`），访问 URL 拼成 `http://localhost:{port}{healthPath}` |
| 自定义 ENTRYPOINT | 传 `runCommand=["gunicorn","-b","0.0.0.0:9000","app:app"]`（极少用，缺省走模板） |
| 改用私有 Harbor / 自建镜像 | yml 里 `image-templates.<alias>.image` 覆盖，再传 `baseImage=<alias>` |
| 改 host 上的镜像名 / 容器名 | 传 `imageName=...` / `containerName=...`（默认按时间戳生成） |
| `port` 或 `containerPort` 缺一 | 工具**直接返回 fail**（不兜底），LLM 必须反问用户 |

> **🔐 安全提示 — 不要把真实密码写进聊天 / 文档 / 提交历史**
>
> 上述示例里的 `密码：••••••••` 和 `gitPassword: "<your-password>"` 都是**占位符**。请勿把真实仓库密码、个人 token、SSH 私钥等敏感信息以明文形式贴进聊天框、issue、文档或 commit message —— 这些内容可能被服务端日志、屏幕截图、LLM 训练数据或 git 历史留存，难以彻底清除。
>
> **推荐做法**：
>
> 1. **公开仓库**：直接省略 `gitUsername` / `gitPassword`。
> 2. **私有仓库**（按优先级）：
>    - 在 `application.yml` 里配 `spring.ai.loom.agent.git.username` / `git.token`，由工具隐式注入 `gitUsername` / `gitPassword`；LLM 完全看不到凭据。
>    - 用部署平台的 **Secret / Credential 变量**（GitHub Actions、GitLab CI、Jenkins Credentials 等），运行时注入到环境变量。
>    - 用 **SSH Key**（在容器或宿主机 `~/.ssh/` 里挂好 `id_rsa` + `config`），git 协议直接走 `git@…`，LLM 不需要密码。
> 3. **临时调试**时，让用户在 LLM 之外的渠道（环境变量、临时文件）保管密码，对话里只说"密码已就位"。

---

## 9. 替换子工具

每个子工具接口都通过 `@ConditionalOnMissingBean` 注册，自定义实现自动优先生效：

```java
@Bean
public IFileTool customFileTool(IFile file, LoomAgentProperties properties) {
    return new MyCustomFileTool(file, properties.getFileBasePath());
}
// DefaultTimeTool 和 DefaultSkillTool 仍然生效
```

也可以通过注册自己的 Bean 来重新启用被禁用的组：

```java
@Bean
public IGitTool customGitTool() {
    return new MyCliGitTool();   // 即便 git.enabled=false 也会生效
}
```

---

- 其它配置属性（RAG / JVector / MCP / Skill / Auth / File / Git）见 [CUSTOMIZATION.zh-CN.md](./CUSTOMIZATION.zh-CN.md)
- HTTP API 参考见 [API.zh-CN.md](./API.zh-CN.md)
