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
- [9. `ISubTaskTool` — 子任务委派](#9-isubtasktool--子任务委派)
- [10. `IScheduleTool` — 定时任务](#10-ischeduletool--定时任务)
- [14. `IAskUserTool` — 用户提问（askUser）](#14-iaskusertool--用户提问askuser)
- [15. `IHtmlRenderTool` — HTML 渲染截图](#15-ihtmlrendertool--html-渲染截图)
- [16. 替换子工具](#16-替换子工具)

---

## 1. 工具启用开关

每个内置工具组都通过 `spring.ai.loom.agent.*` 下的 `*.enabled` 属性控制开关。`time` / `file` / `skill` / `subtask` / `schedule` / `compile` 默认启用；`git` 和 `maven` 是 **opt-in**（默认关闭），因为端到端部署已经覆盖了编译/打包场景。

| 属性 | 类型 | 默认值 | 说明 |
|--------------------|--------|-------|----------------------------------------------------------|
| `time.enabled` | boolean | `true` | 时间工具（`ITimeTool` — 当前时间、时区转换） |
| `file.enabled` | boolean | `true` | 文件工具（`IFileTool` — 16 个基于路径的读写/编辑/删除） |
| `skill.enabled` | boolean | `true` | 技能工具（`ISkillTool` — 获取技能详情；技能全量列表自动注入到 system prompt【技能】段） |
| `subtask.enabled` | boolean | `true` | 子任务委派（`ISubTaskTool` — `start_sub_task` 把一段任务交给子模型同步执行） |
| `schedule.enabled` | boolean | `true` | 定时任务（`IScheduleTool` — 创建/取消/列出/查历史；触发时以子任务方式运行，持久化到 H2 + 重启恢复） |
| `git.enabled` | boolean | `false` | Git 工具（`IGitTool` — 28 个 git 操作，基于 JGit）。**opt-in** — 端到端部署走 `ICompileAndDeployTool`。 |
| `maven.enabled` | boolean | `false` | Maven 构建工具（`IMavenTool` — 同时要求 classpath 上有 `maven-invoker`）。**opt-in** — 编译/打包走 `ICompileAndDeployTool`。 |
| `compile.enabled` | boolean | `true` | 端到端部署工具（`ICompileAndDeployTool` — git clone → 按 buildTool 打包 [maven/npm/pip] → docker build → docker run → health check）。支持 Spring Boot、Node（后端 + 静态前端 → nginx）、Python 等多栈项目。 |
| _(无 enabled 开关)_ | — | — | HTML 渲染截图(`IHtmlRenderTool` — 无头 Chromium 把本地单页 HTML 渲染成 PNG 原型图)。**classpath 门控**:引入 `com.microsoft.playwright:playwright:1.50.0`(库侧 optional)才创建 bean;RBAC 工具,需在角色授权页勾选 `tool_render`。Linux 裸机先跑 `docs/provision-chromium.sh`。 |

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

`IEmbedTool` 是聚合标记接口。子接口（`ITimeTool`、`ISkillTool`、`IFileTool`、`ISubTaskTool`、`IScheduleTool`、`IAskUserTool`、`IGitTool`、`IMavenTool`）各自向 LLM 提供独立的 `@Tool` 方法。`ICompileAndDeployTool` 同样继承 `IEmbedTool`，是部署场景的推荐入口。

| 子接口 | 默认实现 | 方法数 | 默认状态 | 备注 |
|--------------------------|-----------------------------------|------|-----------|---------------------------------------------|
| `ITimeTool` | `DefaultTimeTool` | 2 | 启用 | 未设 `time.enabled` 时始终开启 |
| `ISkillTool` | `DefaultSkillTool` | 2 | 启用 | 从 `user_skill`（数据库）读取；演示应用由 `V1.1` 迁移把 6 个系统技能 seed 进默认 admin 用户的 `user_skill` —— yml `skills[]` 不再读取；技能全量列表自动注入到 system prompt（无 `listSkills` 工具） |
| `IFileTool` | `DefaultFileTool` | 16 | 启用 | 基于路径；根目录 = `{fileBasePath}/{username}/` |
| `ISubTaskTool` | `DefaultSubTaskTool` | 4 | 启用 | `start_sub_task` + `list_sub_tasks` + `cancel_sub_task` + `get_sub_task_history` — 委派/查询/取消/历史，按 `(username, conversationId)` 严格隔离 |
| `IScheduleTool` | `DefaultScheduleTool` | 4 | 启用 | 创建/取消/列出/查历史；触发时以子任务方式运行；持久化到 H2（`loom_scheduled_task`）+ 重启恢复 |
| `IAskUserTool` | `DefaultAskUserTool` | 1 | 启用 | `askUser` — 聊天流内嵌选择卡片阻塞提问；子任务/定时任务 schema 级排除 |
| `IGitTool` | `DefaultGitTool`（JGit 7.6） | 28 | **禁用** | 通过 `git.enabled=true` 开启 |
| `IMavenTool` | `DefaultMavenTool`（maven-invoker 3.3.0） | 6 | **禁用** | 通过 `maven.enabled=true` 开启；classpath 需有 `maven-invoker` |
| `ICompileAndDeployTool` | `DefaultCompileAndDeployTool` | 1 | 启用 | 端到端 `git clone → build → docker run → health check` |
| `IHtmlRenderTool` | `DefaultHtmlRenderTool`(Playwright 1.50.0) | 1 | **classpath 门控** | `renderHtmlFile` — HTML→PNG 截图;需 playwright 依赖 + `tool_render` 角色授权 |

---

## 3. `ITimeTool` — 时间工具

| 项目 | 内容 |
|----------|------------------------------------------------------------------------|
| **接口** | `cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool` |
| **默认实现** | `DefaultTimeTool` |
| **覆盖方式** | 自定义 `@Bean ITimeTool` |
| **状态** | 默认启用；通过 `spring.ai.loom.agent.time.enabled` 切换 |
| **方法** | `getCurrentTime`（获取指定时区的当前时间）、`convertTime`（在不同时区之间转换时间） |

---

## 4. `ISkillTool` — 技能工具

| 项目 | 内容 |
|----------|------------------------------------------------------------------------|
| **接口** | `cn.wubo.spring.ai.loom.agent.tool.skill.ISkillTool` |
| **默认实现** | `DefaultSkillTool` |
| **覆盖方式** | 自定义 `@Bean ISkillTool` |
| **状态** | 默认启用；通过 `spring.ai.loom.agent.skill.enabled` 切换 |
| **方法** | `getSkill`（根据名称获取技能详情）、`createOrUpdateSkill(name, description, content)`（创建或覆盖自建技能；`ROLE_GRANTED` / `MARKET_PULLED` 锁定返回 403，要改 MARKET_PULLED 请用 duplicateSkill 复制后改）。技能全量列表自动注入到 system prompt【技能】段，无 `listSkills` 工具。 |
| **数据源** | `user_skill`（数据库）。每次调用前 `DefaultSkillStorage` 自动 sync `role_skill` → `user_skill`（locked 的 ROLE_GRANTED 条目）。MARKET_PULLED 行始终是市场最新快照（作者 save() 时自动推送到所有拉取者；拉取者无需手动 re-pull）。 |

---

## 5. `IFileTool` — 文件工具

| 项目 | 内容 |
|----------|------------------------------------------------------------------------|
| **接口** | `cn.wubo.spring.ai.loom.agent.tool.file.IFileTool` |
| **默认实现** | `DefaultFileTool` |
| **覆盖方式** | 自定义 `@Bean IFileTool` |
| **状态** | 默认启用；通过 `spring.ai.loom.agent.file.enabled` 切换 |
| **根路径** | 所有基于路径的操作以 `{fileBasePath}/{username}/`（默认 `.local/file/{username}/`）为根目录 |

**方法（16 个）**：

| 方法 | 用途 |
|--------------------------|--------------------------------------------------------------------|
| `readTextFile` | 读取单个文本文件 |
| `readMediaFile` | 读取媒体文件（图片/音频） |
| `readMultipleFiles` | 一次读取多个文件 |
| `writeFile` | 创建或覆盖写入文件 |
| `editFile` | 在已存在的文件中进行定点文本编辑 |
| `createDirectory` | 创建目录（递归） |
| `moveFile` | 移动或重命名文件/目录 |
| `searchFiles` | 在文件树上做 glob/regex 搜索 |
| `listAllowedDirectories` | 列出 LLM 允许访问的目录 |
| `listDirectory` | 列出目录条目 |
| `listDirectoryWithSizes` | 列出目录条目（含大小信息） |
| `directoryTree` | 递归的目录树 |
| `getFileInfo` | 获取文件/目录的元信息（大小、修改时间、类型） |
| `downloadFileUrl` | 获取下载链接（自动创建临时 `file_info` 记录，`usage="temp"`） |
| `viewFileUrl` | 获取预览链接（自动创建临时 `file_info` 记录） |
| `deleteFileOrDirectory` | 删除（需显式 `I_CONFIRM_DELETE` 确认，token 可在 `spring.ai.loom.agent.file.deleteConfirmToken` 改）；支持递归删除目录；清理已删除文件对应的 `file_info` 记录 |

---

## 6. `IGitTool` — Git 工具（JGit）

| 项目 | 内容 |
|----------|------------------------------------------------------------------------|
| **接口** | `cn.wubo.spring.ai.loom.agent.tool.git.IGitTool` |
| **默认实现** | `DefaultGitTool`（基于 Eclipse JGit 7.6.0） |
| **覆盖方式** | 自定义 `@Bean IGitTool` |
| **状态** | `@ConditionalOnProperty(name = "spring.ai.loom.agent.git.enabled", havingValue = "true")` — **默认禁用** |
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

| 项目 | 内容 |
|----------|------------------------------------------------------------------------|
| **接口** | `cn.wubo.spring.ai.loom.agent.tool.maven.IMavenTool` |
| **默认实现** | `DefaultMavenTool`（基于 maven-invoker 3.3.0，不依赖 shell） |
| **覆盖方式** | 自定义 `@Bean IMavenTool` |
| **状态** | `@ConditionalOnClass(name = "org.apache.maven.shared.invoker.Invoker")` 且 `@ConditionalOnProperty(name = "spring.ai.loom.agent.maven.enabled", havingValue = "true")` — **默认禁用，需 opt-in** |
| **方法（6 个）** | `mavenExecute`（通用 Maven 命令执行）、`mavenBuild`（编译）、`mavenPackage`（打包 JAR/WAR）、`mavenTest`（运行测试，支持测试模式匹配）、`mavenDependencyTree`（依赖树，支持范围过滤）、`mavenValidate`（验证项目结构） |

**配置属性**：

| 属性 | 类型 | 默认值 | 说明 |
|---------------------------------------------------|----------|-------------|---------------------------------------------------|
| `spring.ai.loom.agent.maven.enabled` | boolean | `false` | 是否启用 Maven 工具（**opt-in**）—— 编译/打包走 `ICompileAndDeployTool` |
| `spring.ai.loom.agent.maven.mavenHome` | String | — | Maven 安装目录（可选，空则使用 PATH） |
| `spring.ai.loom.agent.maven.localRepository` | String | — | 本地仓库路径（可选） |
| `spring.ai.loom.agent.maven.maxOutputLines` | int | `200` | 输出最大行数（超出截断） |
| `spring.ai.loom.agent.maven.defaultTimeoutMs` | long | `300000` | 默认执行超时（5 分钟） |

> 部署流水线中的编译/打包请优先使用 `ICompileAndDeployTool`；仅当 LLM 需要执行单点 `mvn` 命令时再开启 `IMavenTool`。

---

## 8. `IKnowledgeTool` — 知识库 RAG 检索

`IKnowledgeTool` 提供工具化 RAG：LLM 调 `searchKnowledge` 按需从指定知识库拉取相关 chunk。替代了旧的 `RetrievalAugmentationAdvisor` 模式（旧的模式是把所有 chunk 预注入到 system prompt 里）。

| 项目 | 内容 |
|----------|------------------------------------------------------------------------|
| **接口** | `cn.wubo.spring.ai.loom.agent.tool.knowledge.IKnowledgeTool` |
| **默认实现** | `DefaultKnowledgeTool` |
| **覆盖方式** | 自定义 `@Bean IKnowledgeTool` |
| **状态** | 默认启用；通过 `spring.ai.loom.agent.knowledge.enabled` 切换 |
| **方法** | `searchKnowledge(knowledgeId, query, topK?)`（向量检索指定知识库，返回 top-k chunk 含相似度分数） |
| **权限检查** | 每次调用校验用户对目标知识库的访问权限（own / subscribed / role-granted）；否则返回 "没有权限访问该知识库" |
| **过滤** | 内置 SpEL 过滤 `type == 'knowledge' && knowledgeId == ?` 限定只在请求的知识库内检索 |
| **KB 发现** | 已启用的知识库列表在 system prompt `【知识库】` 段自动展示（ID + 名称 + 摘要）—— LLM 不需要 `listKnowledgeBases` 工具来发现它们；该工具已于 2025-09 移除 |

> KB `description` 字段作为 LLM 用的**内容摘要**（不是主题标签）。好例子：「本知识库收录产品保修条款、售后流程：保修期限（主机 36 个月/电池 12 个月/配件 6 个月）...」。未来 LLM 自动生成摘要。

---

## 11. `ISubTaskTool` — 子任务委派

`ISubTaskTool` 让主对话把一段任务委派给同步运行的"子模型"，跑在独立线程池上。子任务不能再触发子任务或定时任务（递归防御），也不能直接向用户提问（`IAskUserTool` schema 级排除 —— 子任务只做主任务规划好的执行并返回结果，疑问写进执行结果由主任务决定是否提问）。

| 项目 | 内容 |
|----------|------------------------------------------------------------------------|
| **接口** | `cn.wubo.spring.ai.loom.agent.tool.subtask.ISubTaskTool` |
| **默认实现** | `DefaultSubTaskTool` |
| **覆盖方式** | 自定义 `@Bean ISubTaskTool` |
| **状态** | 默认启用；通过 `spring.ai.loom.agent.subtask.enabled` 切换 |
| **方法(4)** | `start_sub_task(prompt, systemContext?)`（在 `loomSubTaskExecutor` 上启动子任务）；`list_sub_tasks()`（列出当前会话的活跃子任务）；`cancel_sub_task(subTaskId)`（取消运行中的子任务）；`get_sub_task_history(limit?)`（最近已完成/已取消的子任务） |
| **隔离** | 严格按 `(username, conversationId)` 隔离；子任务 memory 用 `{conversationId}--sub--{subTaskId}` 命名空间，避免污染父会话历史 |
| **工具过滤** | 子任务运行时 self-tools（`ISubTaskTool` / `IScheduleTool` / `IAskUserTool` —— 子任务不能向用户提问）被过滤掉，防止递归 |
| **并发** | 通过 `spring.ai.loom.agent.subtask.max-concurrent`（默认 4）控制；历史通过 `max-history`（默认 200）控制 |

---

## 12. `ICompileAndDeployTool` — 端到端部署

`ICompileAndDeployTool` 在单次 LLM tool call 内完成 `git clone → buildTool build (maven / npm / pip) → docker build → docker run → health check`。是 `git clone → build → docker run` 工作流的**推荐入口** —— LLM 只需传参，工具返回 `accessUrl`。

| 项目 | 内容 |
|----------|-----------------------------------------------------------------------------------|
| **接口** | `cn.wubo.spring.ai.loom.agent.tool.compile.ICompileAndDeployTool` |
| **默认实现** | `DefaultCompileAndDeployTool` |
| **覆盖方式** | 自定义 `@Bean ICompileAndDeployTool` |
| **状态** | 默认启用；通过 `spring.ai.loom.agent.compile.enabled` 切换 |
| **方法** | `compileAndDeploy(Map<String,Object> params, ToolContext toolContext)` → `CompileAndDeployResult` |
| **工作区** | 每次调用在 `{fileBasePath}/{username}/compile-deploy-<uuid>/` 下创建独立工作区 |

### 10.1 工具入参

`params` 是大小写不敏感的 Map。必填：`gitUrl`、`port`、`containerPort`。其他按需提供。

| 键 | 必填 | 说明 |
|--------------------|------|-----------------------------------------------------------------------------------------------------------------|
| `gitUrl` | 是 | Git 仓库 URL |
| `gitUsername` | 否 | Git 用户名（公开仓库可省略） |
| `gitPassword` | 否 | Git 密码或 token（公开仓库可省略） |
| `branch` | 否 | 克隆分支（默认远程 HEAD） |
| `port` | 是 | 宿主机对外端口（也是访问 URL 的端口，如 `http://localhost:{port}/{healthPath}`） |
| `containerPort` | 是 | 容器内应用监听端口（无 yml 兜底，参考 application.yml 的 `server.port`） |
| `subDir` | 否 | 多模块仓库的子目录；根目录无 `pom.xml` 时**必须**显式指定，否则工具会返回 fail |
| `imageName` | 否 | Docker 镜像名（默认按时间戳自动生成） |
| `containerName` | 否 | Docker 容器名（默认按时间戳自动生成） |
| `healthPath` | 否 | 健康检查路径，同时作为访问 URL 路径（如 `healthPath=sql-forge-demo` → `http://localhost:{port}/sql-forge-demo`；无 context-path 时传 `/`） |
| `buildTool` | 否 | 构建栈：`maven` / `npm` / `npm-frontend` / `pip`。缺省时按 marker 文件自动探测（`pom.xml→maven`、`package.json→npm`、`requirements.txt` / `pyproject.toml→pip`）。多模块仓同时存在多个 marker 时必须显式指定。 |
| `baseImage` | 否 | 基础镜像；支持模板别名（`java17` / `java21` / `nginx` / `python3` / `node20` / `node20-serve`）或完整镜像名（如 `openjdk:17-slim`）。缺省按 `buildTool` 自动选（`maven→java17`、`npm→node20`、`npm-frontend→node20-serve`、`pip→python3`）。 |
| `runCommand` | 否 | 字符串数组，覆盖模板的默认 ENTRYPOINT（极少用） |

### 10.2 配置属性

所有配置位于 `spring.ai.loom.agent.compile.*` 下。

| 属性 | 类型 | 默认值 | 说明 |
|----------------------------------------------------------|---------|---------------------------|---------------------------------------------------------------------------------------------------|
| `spring.ai.loom.agent.compile.enabled` | boolean | `true` | 是否注册端到端部署工具（默认启用） |
| `spring.ai.loom.agent.compile.mavenHome` | string | 自动探测 | 可选 Maven 安装目录；回退到 `maven.mavenHome` 与 PATH |
| `spring.ai.loom.agent.compile.dockerCmd` | string | `docker` | 可选 docker CLI 二进制覆盖 |
| `spring.ai.loom.agent.compile.mavenTimeoutMs` | long | `600000` | Maven 编译超时（10 分钟） |
| `spring.ai.loom.agent.compile.dockerBuildTimeoutMs` | long | `600000` | `docker build` 超时（10 分钟） |
| `spring.ai.loom.agent.compile.dockerRunTimeoutMs` | long | `60000` | `docker run` 启动超时（1 分钟） |
| `spring.ai.loom.agent.compile.healthCheckMaxWaitMs` | long | `60000` | 容器启动后健康检查总等待（1 分钟） |
| `spring.ai.loom.agent.compile.healthCheckIntervalMs` | long | `2000` | 健康检查轮询间隔（2 秒） |
| `spring.ai.loom.agent.compile.keepWorkspace` | boolean | `false` | 部署完成后是否保留工作区（默认删；调试时设 `true`） |
| `spring.ai.loom.agent.compile.imageTemplates` | map | （6 个预置模板） | 按别名预置的基础镜像模板，见下 |
| `spring.ai.loom.agent.compile.extraRunArgs` | string[]| `[]` | 注入到 `--name` 与镜像名之间的额外 `docker run` 参数 |

### 10.3 预置基础镜像模板

| 别名 | 镜像 | 默认 ENTRYPOINT |
|------------------|-----------------------------------|---------------------------------------------|
| `java17` | `eclipse-temurin:17-jre-alpine` | `["java","-jar","app.jar"]` |
| `java21` | `eclipse-temurin:21-jre-alpine` | `["java","-jar","app.jar"]` |
| `nginx` | `nginx:1.27-alpine` | `["nginx","-g","daemon off;"]` |
| `python3` | `python:3.12-slim` | `["python","app.py"]` |
| `node20` | `node:20-alpine` | `["node","dist/index.js"]` |
| `node20-serve` | `nginx:1.27-alpine` | `["nginx","-g","daemon off;"]` |

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

### 10.4 工具入参示例

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

### 10.5 端到端对话示例

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
> - 在 `application.yml` 里配 `spring.ai.loom.agent.git.username` / `git.token`，由工具隐式注入 `gitUsername` / `gitPassword`；LLM 完全看不到凭据。
> - 用部署平台的 **Secret / Credential 变量**（GitHub Actions、GitLab CI、Jenkins Credentials 等），运行时注入到环境变量。
> - 用 **SSH Key**（在容器或宿主机 `~/.ssh/` 里挂好 `id_rsa` + `config`），git 协议直接走 `git@…`，LLM 不需要密码。
> 3. **临时调试**时，让用户在 LLM 之外的渠道（环境变量、临时文件）保管密码，对话里只说"密码已就位"。

---

## 13. `IScheduleTool` — 定时任务

| 项目 | 内容 |
|----------|------------------------------------------------------------------------|
| **接口** | `cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool` |
| **默认实现** | `DefaultScheduleTool` |
| **覆盖方式** | 自定义 `@Bean IScheduleTool` |
| **状态** | 默认启用；通过 `spring.ai.loom.agent.schedule.enabled` 切换 |
| **方法（4）** | `createSchedule`（cron / fixed_delay / fixed_rate / one_shot）、`cancelSchedule`、`listSchedules`、`getScheduleHistory` |

定时任务命名空间为 `loom-sched-{username}-{conversationId}-{name}`，触发时**以子任务方式运行**。loom-agent 自管 H2 持久化（`loom_scheduled_task`，新增）；`ScheduleRestoreListener` 在 `ApplicationReadyEvent` 时按原 `createdAt` 重新装载，使 `max-lifetime` 上限跨重启累计（超上限的行自动清理）。取消时会校验行所有权（跨用户取消会被拒绝）并删除持久化行，使恢复监听器不会"复活"幽灵任务。

**触发约束**来自 `flex.schedule.limits`：

| 属性 | 示例 | 说明 |
|--------------------------|-------|---------------------------------|
| `schedule.enabled` | `true` | 启用定时任务工具 |
| `flex.schedule.limits.min-interval` | `10m` | 最小触发间隔 |
| `flex.schedule.limits.max-lifetime` | `72h` | 任务最大存活（跨重启累计） |
| `flex.schedule.limits.mode` | `strict` | `strict` = 超限抛异常 |

---

## 14. `IAskUserTool` — 用户提问（askUser）

| 项目 | 内容 |
|----------|------------------------------------------------------------------------|
| **接口** | `cn.wubo.spring.ai.loom.agent.askuser.IAskUserTool` |
| **默认实现** | `DefaultAskUserTool` |
| **覆盖方式** | 自定义 `@Bean IAskUserTool` |
| **状态** | **Universal** — `@ToolGroup(defaultGranted=true)`。仅向当前流内的本人提问，答案回同一流，无越权风险；子任务/定时任务 schema 级排除。 |
| **方法（1）** | `askUser(question, header, background, optionsJson, multiSelect, allowCustomInput)` — 在聊天流中渲染内嵌选择卡片，阻塞等待用户作答 |

**工具入参**：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `question` | string | 是 | 问题正文 — 一句话，清晰具体 |
| `header` | string | 否 | 短标题/chip（2-6 字，如"部署方式"），可传 null |
| `background` | string | 否 | 为什么问这个问题的背景说明（1-2 句），可传 null |
| `optionsJson` | string | 是 | 选项 JSON 数组字符串，2-4 个：`[{"label":"选项A","description":"补充说明"},{"label":"选项B"}]`（用 String 而非类型化 List —— 对 qwen 系模型的 tool-args JSON 容错更好；服务端用 Spring AI 宽容 `JsonParser` 解析） |
| `multiSelect` | boolean | 否 | 是否允许多选（null = false） |
| `allowCustomInput` | boolean | 否 | 是否允许"其他"自定义输入（null = false） |

**卡片形态**：问题以 SSE 帧推入当前聊天流（`ChatResponseRecord.askUser` = `AskUserEvent`，见 [API.zh-CN.md](./API.zh-CN.md) § 3.1）。前端渲染内嵌卡片 —— 单选即点即交、多选显式提交按钮、可选自定义输入、倒计时，以及"已答 / 已超时 / 已取消"定格状态。答案 POST 到 `/spring/ai/loom/ask/{questionId}/answer`（见 [API.zh-CN.md](./API.zh-CN.md) § 3.2），唤醒阻塞的工具线程；答案以 tool_result 回到同一条流。

**返回文本契约**（所有失败路径都返回文本、不抛异常 —— 保住 SSE Flux 正常 complete，让 ChatMemory 落库）：

| 返回前缀 | 触发条件 |
|---|---|
| `[用户已回答] {answer}` | 用户在超时前作答 |
| `[用户未作答] ...` | 超时（`askuser.timeoutSeconds`，默认 300），或用户点了停止（stop 路径会取消该会话全部挂起提问） |
| `[提问失败] ...` | 校验错误（question 空白 / optionsJson 解析失败 / 选项数不在 2-4 / 选项 label 空白）、缺少会话上下文、会话流不可用、或等待异常 —— 每条消息都告诉 LLM 如何纠正后重试 |

（另有罕见的 `[提问被中断]` 前缀 —— 等待线程被中断时返回。）

**超时与 stop 行为**：超时后工具返回"用户未作答"文本，指示 LLM 基于现有信息自行合理决策继续；点停止会按 `(username, conversationId)` 取消全部挂起问题，不泄漏阻塞线程。

**子任务/定时任务排除契约**（#1 spec D6，见 `ISubTaskTool` javadoc）：子任务只做主任务规划好的执行并返回结果，不能直接向用户提问 —— 需要用户决策的疑问必须写进子任务的返回结果，由主对话决定是否向用户提问。`DefaultSubTaskExecutor` 在子任务工具 schema 中过滤掉 `IAskUserTool`；定时任务触发时走同一条子任务路径，继承此排除。

**配置**：

| 属性 | 默认值 | 说明 |
|--------------------------|-------|---------------------------------|
| `askuser.timeoutSeconds` | `300` | askUser 工具阻塞等待用户作答的最长秒数；超时返回"用户未作答"文本，Flux 正常 complete |

---

## 15. `IHtmlRenderTool` — HTML 渲染截图

用无头 Chromium(Playwright)把本地自包含单页 HTML 文件渲染成 PNG 截图 —— 面向 LLM 编写的界面原型图(嵌入需求文档)、数据分析单页与报告可视化。

| 方法 | 参数 | 说明 |
|--------|-----------|-------------|
| `renderHtmlFile` | `htmlFilePath`(必填)、`imageName?`、`device?`(desktop/tablet/mobile)、`fullPage?`(默认 true) | 把 `{fileBasePath}/{username}/{htmlFilePath}` 渲染成 `{fileBasePath}/{username}/prototypes/{name}-{timestamp}.png`,桥接一条 `usage='temp'` 的 file 记录,返回预览链接 + markdown 嵌入片段 |

**启用方式(2 个条件):**

1. 引入 optional 依赖(库不会传递引入):

```xml
<dependency>
    <groupId>com.microsoft.playwright</groupId>
    <artifactId>playwright</artifactId>
    <version>1.50.0</version>
</dependency>
```

2. 管理控制台 → 角色 → 为该角色授权 `tool_render` 工具组(RBAC 工具,非 universal)。

**Linux 裸机部署(jar 不走 Docker):** 以 root 执行一次 `docs/provision-chromium.sh /path/to/app.jar`
(安装系统 so 库 + `fonts-noto-cjk`,避免中文截图渲染成豆腐块 □□□,并下载 Playwright 管理的 Chromium),
然后以**非 root** 用户启动 jar。开发机(Windows/macOS)无需 provision —— Playwright 首次使用时自动
下载浏览器(需要网络)。渲染时若 Chromium 不可用,工具返回 `[渲染不可用]` 文本并附 provision 提示 ——
绝不抛异常。

**安全约束:** 全部外部网络请求被双重屏蔽(context route abort + 注入 CSP
`default-src 'none'`);HTML 必须自包含(内联 CSS/JS)。输入路径沙箱限定在用户文件目录内;
输出只落在其 `prototypes/` 子目录。渲染串行执行(同一时刻一个),30s 超时;HTML 大小上限 2MB。

**配置(`spring.ai.loom.agent.render.*`):**

| 属性 | 默认值 | 说明 |
|----------|---------|-------------|
| `chromium-path` | _(空)_ | 显式 Chromium 二进制路径(如 `/usr/bin/chromium-browser`);空 = Playwright 探测(托管缓存 → 系统 channel) |
| `device-scale-factor` | `2` | 截图缩放(2 = Retina) |
| `timeout-seconds` | `30` | 单次渲染超时(含排队等待) |
| `render-wait-ms` | `1500` | `setContent` 后等待内联 JS 完成的固定时长 |
| `network-blocked` | `true` | route-abort 全部外部请求(false 时 CSP 仍注入) |
| `max-html-bytes` | `2097152` | HTML 文件大小上限 |

---

## 16. 替换子工具

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
 return new MyCliGitTool(); // 即便 git.enabled=false 也会生效
}
```

---

- 其它配置属性（RAG / JVector / MCP / Skill / Auth / File / Git）见 [CUSTOMIZATION.zh-CN.md](./CUSTOMIZATION.zh-CN.md)
- HTTP API 参考见 [API.zh-CN.md](./API.zh-CN.md)
