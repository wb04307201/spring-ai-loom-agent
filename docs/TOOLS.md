# Spring AI LoomAgent — Built-in Tools

> Reference for every tool that LoomAgent exposes to the LLM by default. Each tool group can be enabled/disabled independently, and each sub-tool interface can be replaced with a custom `@Bean`.

---

## Table of Contents

- [1. Tool Visibility & RBAC](#1-tool-visibility--rbac)
- [2. `IEmbedTool` Overview](#2-iembedtool-overview)
- [3. `ITimeTool` — Time Tools](#3-itimetool--time-tools)
- [4. `ISkillTool` — Skill Tools](#4-iskilltool--skill-tools)
- [5. `IFileTool` — File Tools](#5-ifiletool--file-tools)
- [6. `IGitTool` — Git Tools (JGit)](#6-igittool--git-tools-jgit)
- [7. `IMavenTool` — Maven Build Tools (maven-invoker)](#7-imaventool--maven-build-tools-maven-invoker)
- [8. `IKnowledgeTool` — Knowledge RAG Retrieval](#8-iknowledgetool--knowledge-rag-retrieval)
- [9. `ICompileAndDeployTool` — End-to-End Deployment](#9-icompileanddeploytool--end-to-end-deployment)
- [10. `ISubTaskTool` — Sub-task Delegation](#10-isubtasktool--sub-task-delegation)
- [11. `IScheduleTool` — Scheduled Tasks](#11-ischeduletool--scheduled-tasks)
- [12. Replacing a Sub-Tool](#12-replacing-a-sub-tool)
 - [8.1 Tool-call Parameters](#81-tool-call-parameters)
 - [8.2 Configuration](#82-configuration)
 - [8.3 Base-image Templates (built-in)](#83-base-image-templates-built-in)
 - [8.4 Example Invocation](#84-example-invocation)
 - [8.5 End-to-End Conversation Examples](#85-end-to-end-conversation-examples)
- [9. `ISubTaskTool` — Sub-task Delegation](#9-isubtasktool--sub-task-delegation)
- [10. `IScheduleTool` — Scheduled Tasks](#10-ischeduletool--scheduled-tasks)
- [11. Replacing a Sub-Tool](#11-replacing-a-sub-tool)

---

## 1. Tool Visibility & RBAC

Since M3, all 9 `I*Tool` beans are **always created** regardless of any `*.enabled` yml flag. Since M6, visibility is governed by two mechanisms instead:

1. **Universal tools** (annotated `@ToolGroup(defaultGranted=true)`) — visible to every logged-in user. The 6 universal tools are listed below.
2. **RBAC tools** (annotated `@ToolGroup(defaultGranted=false)`) — visible only after an admin assigns the tool group to a role via the `/admin/roles/{code}/tools` endpoint (persisted in `role_tool` table). The 3 RBAC tools are listed below.

### Universal tools (always visible)

| Tool | Group | Reason for defaultGrant |
|------|-------|-------------------------|
| `ITimeTool` | `tool_time` | Read-only; returns current time / timezone conversion — no side effects |
| `IFileTool` | `tool_file` | Per-user file isolation (`{fileBasePath}/{username}/`); LLM prompt guides safe usage; includes `deleteFileOrDirectory` — admins should review any auto-deletion flows |
| `ISkillTool` | `tool_skill` | Skill content is per-user; allows self-created skill editing |
| `IKnowledgeTool` | `tool_knowledge` | KB access is independently gated by `role_knowledge` table — the tool itself needs no extra RBAC |
| `ISubTaskTool` | `tool_subtask` | Sub-task runs inherit user identity — no privilege escalation surface |
| `IScheduleTool` | `tool_schedule` | Per-user namespace `loom-sched-{user}-{conv}-{name}`; fires as sub-task — no privilege escalation |

### RBAC tools (require `role_tool` authorization)

| Tool | Group | Reason for opt-in |
|------|-------|-------------------|
| `IGitTool` | `tool_git` | `git push` writes to remote repositories; end-to-end deployment via `ICompileAndDeployTool` covers the common case |
| `IMavenTool` | `tool_maven` | Arbitrary `mvn` builds; compile/package goes through `ICompileAndDeployTool` |
| `ICompileAndDeployTool` | `tool_compile` | Spawns Docker containers; runs build pipelines; consumes network & disk resources |

### Why the yml `*.enabled` switches no longer gate tools

The `*.enabled` properties still exist for backward compatibility (e.g. `time.enabled`, `git.enabled`, `maven.enabled`, `compile.enabled`) — see `LoomAgentProperties` — but they were deprecated in M3 and have no effect on whether the tool is created. The only meaningful toggle for opt-in tools is to register a custom `@Bean` (which wins over the default via `@ConditionalOnMissingBean`).

### Migration note: V2.4 cleanup

Flyway `V2.4__cleanup_universal_tools_from_role_tool.sql` ran a one-time `DELETE FROM role_tool WHERE group_name IN ('tool_schedule', 'tool_subtask', 'tool_knowledge', 'tool_time', 'tool_skill', 'tool_file')` to clear any historical RBAC rows for universal groups. Pre-existing databases now reflect M6 semantics: a `role` no longer needs to be granted universal tools for users to see them.

### Re-enable example (replace with custom implementation)

```yaml
# No yml flag needed. Replace the bean with @ConditionalOnMissingBean:
@Bean
@ConditionalOnMissingBean
public IGitTool customGitTool() { return new MyGitTool(); }
```

---

## 2. `IEmbedTool` Overview

`IEmbedTool` is an aggregate marker interface. 9 sub-interfaces each contribute independent `@Tool` methods to the LLM. `ICompileAndDeployTool` also extends `IEmbedTool` and is the recommended end-to-end entry point for deployment.

| Sub-Interface | Default Impl | Methods | Visibility | Notes |
|---------------------------|-----------------------------|---------|------------|------------------------------------------------|
| `ITimeTool` | `DefaultTimeTool` | 2 | **universal** | Read-only current time / timezone conversion |
| `ISkillTool` | `DefaultSkillTool` | 2 | **universal** | Reads `user_skill` (DB); seeded from `market_skill` by the init migration — yml `skills[]` is no longer read; full skill list is auto-injected into the system prompt (no `listSkills` tool) |
| `IFileTool` | `DefaultFileTool` | 16 | **universal** | Path-based; root = `{fileBasePath}/{username}/` |
| `IKnowledgeTool` | `DefaultKnowledgeTool` | 1 | **universal** | Tool-based RAG: `searchKnowledge(knowledgeId, query, topK?)`; KB list is auto-injected in the system prompt (no `listKnowledgeBases` tool) |
| `ISubTaskTool` | `DefaultSubTaskTool` | 4 | **universal** | `start_sub_task` + `list_sub_tasks` + `cancel_sub_task` + `get_sub_task_history` — delegate/query/cancel/history, strictly scoped by `(username, conversationId)` |
| `IScheduleTool` | `DefaultScheduleTool` | 4 | **universal** | create/cancel/list/history; fires as a sub-task; persisted to H2 (`loom_scheduled_task`) + restored on restart |
| `IGitTool` | `DefaultGitTool` (JGit 7.6) | 28 | **RBAC** | Requires `role_tool.tool_git` authorization; opt-in via `@Bean IGitTool` replacement |
| `IMavenTool` | `DefaultMavenTool` (maven-invoker 3.3.0) | 6 | **RBAC** | Requires `role_tool.tool_maven`; needs `maven-invoker` on classpath |
| `ICompileAndDeployTool` | `DefaultCompileAndDeployTool` | 1 | **RBAC** | Requires `role_tool.tool_compile`; end-to-end `git clone → build → docker run → health check` |

> Each tool's individual section below still lists any remaining yml flags as **legacy** — they are kept for backward compatibility only and have no functional effect since M3.

---

## 3. `ITimeTool` — Time Tools

| Item | Details |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface** | `cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool` |
| **Default** | `DefaultTimeTool` |
| **Override** | Custom `@Bean ITimeTool` |
| **State** | **Universal** — `@ToolGroup(defaultGranted=true)`. Visible to every logged-in user regardless of role. |
| **Methods** | `getCurrentTime` — get current time by timezone; `convertTime` — convert between timezones |

---

## 4. `ISkillTool` — Skill Tools

| Item | Details |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface** | `cn.wubo.spring.ai.loom.agent.tool.skill.ISkillTool` |
| **Default** | `DefaultSkillTool` |
| **Override** | Custom `@Bean ISkillTool` |
| **State** | **Universal** — `@ToolGroup(defaultGranted=true)`. Visible to every logged-in user regardless of role. |
| **Methods** | `getSkill(skillName)` — get one skill's full content; `createOrUpdateSkill(name, description, content)` — create or overwrite a user-owned skill (`ROLE_GRANTED` rejects with 403; `MARKET_PULLED` rejects with 403 — use `duplicateSkill` to copy it as USER_CREATED first). The full skill list is auto-injected into the system prompt `技能` section; there is no `listSkills` tool. |
| **Data source** | `user_skill` (DB). On every call, `DefaultSkillStorage` auto-syncs `role_skill` → `user_skill` (locked ROLE_GRANTED entries). MARKET_PULLED rows are always the latest market snapshot (the author pushes on every `save()`; pullers need no manual re-pull). |

---

## 5. `IFileTool` — File Tools

| Item | Details |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface** | `cn.wubo.spring.ai.loom.agent.tool.file.IFileTool` |
| **Default** | `DefaultFileTool` |
| **Override** | Custom `@Bean IFileTool` |
| **State** | **Universal** — `@ToolGroup(defaultGranted=true)`. Visible to every logged-in user regardless of role. Includes `deleteFileOrDirectory` — admins should review any auto-deletion flows. |
| **Root path** | All path-based operations use `{fileBasePath}/{username}/` (default `.local/file/{username}/`) |

**Methods (16)**:

| Method | Purpose |
|---------------------------|-------------------------------------------------------------------------|
| `readTextFile` | Read text content of a single file |
| `readMediaFile` | Read a media file (image/audio) |
| `readMultipleFiles` | Read several files in one call |
| `writeFile` | Create or overwrite a file |
| `editFile` | Targeted text edit in an existing file |
| `createDirectory` | Create a directory (recursively) |
| `moveFile` | Move or rename a file/directory |
| `searchFiles` | Glob/regex search across the file tree |
| `listAllowedDirectories` | List directories the LLM is allowed to access |
| `listDirectory` | List directory entries |
| `listDirectoryWithSizes` | List entries with size info |
| `directoryTree` | Recursive directory tree |
| `getFileInfo` | Metadata (size, mtime, type) of a file/directory |
| `downloadFileUrl` | Get a download URL (auto-creates a temporary `file_info` record, `usage="temp"`) |
| `viewFileUrl` | Get a preview URL (auto-creates a temporary `file_info` record) |
| `deleteFileOrDirectory` | Delete with explicit `I_CONFIRM_DELETE` confirmation (token configurable via `spring.ai.loom.agent.file.deleteConfirmToken`); supports recursive directory removal; cleans up `file_info` records for deleted files |

---

## 6. `IGitTool` — Git Tools (JGit)

| Item | Details |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface** | `cn.wubo.spring.ai.loom.agent.tool.git.IGitTool` |
| **Default** | `DefaultGitTool` (based on Eclipse JGit 7.6.0) |
| **Override** | Custom `@Bean IGitTool` |
| **State** | **RBAC + opt-in bean**. Bean is conditional on `spring.ai.loom.agent.git.enabled=true` (default `false`, `matchIfMissing=false`) plus `@ConditionalOnMissingBean`. Once created, only visible to users whose roles grant `tool_git` via the `role_tool` table. |
| **Working dir** | Set via `gitSetWorkingDir` (absolute path or relative to `{fileBasePath}/{username}/`); `gitInit` / `gitClone` accept an absolute path or a relative path under the user file dir |

**Methods (28)**:

- **Repository lifecycle**: `gitInit`, `gitClone`
- **Basic operations**: `gitStatus`, `gitAdd`, `gitCommit`, `gitDiff`, `gitLog`
- **Branch management**: `gitBranch`, `gitCheckout`
- **Remote operations**: `gitPull`, `gitPush`, `gitFetch`, `gitMerge`, `gitRebase`, `gitReset`
- **Stash / Tag / Remote**: `gitStash`, `gitTag`, `gitRemote`
- **Inspection**: `gitBlame`, `gitShow`, `gitReflog`
- **Maintenance**: `gitClean`, `gitCherryPick`
- **Worktree**: `gitWorktree`, `gitSetWorkingDir`, `gitClearWorkingDir`
- **Analysis helpers**: `gitChangelogAnalyze`, `gitWrapupInstructions`

> For end-to-end deployment (`git clone → build → docker run → health check`), prefer `ICompileAndDeployTool` — `IGitTool` is for single-point git operations (status/log/blame/branch/etc.).

---

## 7. `IMavenTool` — Maven Build Tools (maven-invoker)

| Item | Details |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface** | `cn.wubo.spring.ai.loom.agent.tool.maven.IMavenTool` |
| **Default** | `DefaultMavenTool` (based on maven-invoker 3.3.0, no shell dependency) |
| **Override** | Custom `@Bean IMavenTool` |
| **State** | **RBAC + opt-in bean**. Bean is conditional on `maven-invoker` classpath + `spring.ai.loom.agent.maven.enabled=true` (default `false`) plus `@ConditionalOnMissingBean`. Once created, only visible to users whose roles grant `tool_maven` via the `role_tool` table. |
| **Methods (6)** | `mavenExecute` (generic Maven goal execution); `mavenBuild` (compile); `mavenPackage` (package JAR/WAR); `mavenTest` (run tests, supports test pattern); `mavenDependencyTree` (dependency tree with scope filter); `mavenValidate` (validate project structure) |

**Configuration**:

| Property | Type | Default | Description |
|-----------------------------------------------|---------|-------------|----------------------------------------------------|
| `spring.ai.loom.agent.maven.enabled` | boolean | `false` | Whether to enable Maven tool (opt-in) |
| `spring.ai.loom.agent.maven.mavenHome` | String | — | Maven install directory (optional, uses PATH if empty) |
| `spring.ai.loom.agent.maven.localRepository` | String | — | Local repository path (optional) |
| `spring.ai.loom.agent.maven.maxOutputLines` | int | `200` | Max output lines before truncation |
| `spring.ai.loom.agent.maven.defaultTimeoutMs` | long | `300000` | Default execution timeout (5 minutes) |

> For compile/package inside the deployment pipeline, prefer `ICompileAndDeployTool`. Use `IMavenTool` only when the LLM needs a single-point `mvn` command.

---

## 8. `IKnowledgeTool` — Knowledge RAG Retrieval

`IKnowledgeTool` provides tool-based RAG: the LLM invokes `searchKnowledge` to pull relevant chunks from a specific knowledge base on demand. It replaces the older `RetrievalAugmentationAdvisor` pattern (which pre-injected all chunks into the system prompt) with explicit tool calls that the LLM can decide when to use.

| Item | Details |
|-----------------|----------------------------------------------------------------------------------------|
| **Interface** | `cn.wubo.spring.ai.loom.agent.tool.knowledge.IKnowledgeTool` |
| **Default** | `DefaultKnowledgeTool` |
| **Override** | Custom `@Bean IKnowledgeTool` |
| **State** | **Universal** — `@ToolGroup(defaultGranted=true)`. Per-call RBAC still applies: each `searchKnowledge` call verifies the user has access to the target KB (own / subscribed / role-granted). |
| **Methods** | `searchKnowledge(knowledgeId, query, topK?)` — vector-search a single KB and return top-k chunks with similarity score |
| **Access check**| Each call verifies the user has access to the target KB (own / subscribed / role-granted); returns "没有权限访问该知识库" if not |
| **Filter** | Built SpEL filter `type == 'knowledge' && knowledgeId == ?` so search is scoped to the requested KB only |
| **KB discovery**| The list of **enabled** KBs is auto-injected into the system prompt under the `【知识库】` section (ID + name + summary) — the LLM does not need a `listKnowledgeBases` tool to discover them; the previous `listKnowledgeBases` tool was removed (Sep 2025) as redundant with the system prompt injection |

> KB `description` field is treated as a **content summary** for the LLM (not a topic label). Good descriptions read like "本知识库收录产品保修条款、售后流程：保修期限（主机 36 个月/电池 12 个月/配件 6 个月）...". Auto-summary via LLM is planned.

---

## 9. `ICompileAndDeployTool` — End-to-End Deployment

`ICompileAndDeployTool` runs the full pipeline in a single LLM tool call: `git clone → buildTool build (maven / npm / pip) → docker build → docker run → health check`. It is the **supported entry point** for `git clone → build → docker run` workflows — LLM only needs to supply the parameters and read back the `accessUrl`.

| Item | Details |
|-----------------|----------------------------------------------------------------------------------------|
| **Interface** | `cn.wubo.spring.ai.loom.agent.tool.compile.ICompileAndDeployTool` |
| **Default** | `DefaultCompileAndDeployTool` |
| **Override** | Custom `@Bean ICompileAndDeployTool` |
| **State** | **RBAC** — `@ToolGroup(defaultGranted=false)`. Only visible to users whose roles grant `tool_compile` via the `role_tool` table (admin → `/admin/roles/{code}/tools`). |
| **Method** | `compileAndDeploy(Map<String,Object> params, ToolContext toolContext)` → `CompileAndDeployResult` |
| **Workspace** | Each call creates an isolated work dir under `{fileBasePath}/{username}/compile-deploy-<uuid>/` |

### 9.1 Tool-call Parameters

`params` is a case-insensitive Map. Required: `gitUrl`, `port`, `containerPort`. Others are optional.

| Key | Required | Description |
|------------------|----------|----------------------------------------------------------------------------------------------------------------------|
| `gitUrl` | yes | Git repository URL |
| `gitUsername` | no | Git username (public repos can skip) |
| `gitPassword` | no | Git password or token (public repos can skip) |
| `branch` | no | Branch to clone (defaults to remote HEAD) |
| `port` | yes | Host port the container publishes (the URL the caller accesses: `http://localhost:{port}/{healthPath}`) |
| `containerPort` | yes | Port the application listens on inside the container (no yml fallback; reference `server.port` in application.yml) |
| `subDir` | no | Subdirectory of a multi-module repo; **required** when root has no `pom.xml` — tool returns `fail` otherwise |
| `imageName` | no | Docker image name (default derived from timestamp) |
| `containerName` | no | Docker container name (default derived from timestamp) |
| `healthPath` | no | Health-check path **and** access URL path (e.g. `healthPath=sql-forge-demo` → `http://localhost:{port}/sql-forge-demo`; pass `/` when there is no context-path) |
| `buildTool` | no | Build stack: `maven` / `npm` / `npm-frontend` / `pip`. Auto-detected from markers when omitted (`pom.xml` → `maven`, `package.json` → `npm`, `requirements.txt` / `pyproject.toml` → `pip`). Multi-module repos with multiple markers must specify explicitly. |
| `baseImage` | no | Template alias (`java17` / `java21` / `nginx` / `python3` / `node20` / `node20-serve`) or full image name (e.g. `openjdk:17-slim`). Default per `buildTool`: `maven→java17`, `npm→node20`, `npm-frontend→node20-serve`, `pip→python3`. |
| `runCommand` | no | String array overriding the template's default ENTRYPOINT (rare) |

### 9.2 Configuration

All settings live under `spring.ai.loom.agent.compile.*`.

| Property | Type | Default | Description |
|---------------------------------------------------|----------|----------------------------------|--------------------------------------------------------------------------------------------------------------|
| `spring.ai.loom.agent.compile.enabled` | boolean | `true` | Whether to register the end-to-end deploy tool (default enabled) |
| `spring.ai.loom.agent.compile.mavenHome` | string | auto-discover | Optional Maven install dir; falls back to `maven.mavenHome` and PATH |
| `spring.ai.loom.agent.compile.dockerCmd` | string | `docker` | Optional override for the docker CLI binary |
| `spring.ai.loom.agent.compile.mavenTimeoutMs` | long | `600000` | Maven build timeout (10 minutes) |
| `spring.ai.loom.agent.compile.dockerBuildTimeoutMs`| long | `600000` | `docker build` timeout (10 minutes) |
| `spring.ai.loom.agent.compile.dockerRunTimeoutMs` | long | `60000` | `docker run` startup timeout (1 minute) |
| `spring.ai.loom.agent.compile.healthCheckMaxWaitMs`| long | `60000` | Total wait for the post-startup health check (1 minute) |
| `spring.ai.loom.agent.compile.healthCheckIntervalMs`| long | `2000` | Health-check poll interval (2 seconds) |
| `spring.ai.loom.agent.compile.keepWorkspace` | boolean | `false` | Keep the per-call workspace after deployment finishes (default: delete; set `true` to debug) |
| `spring.ai.loom.agent.compile.imageTemplates` | map | (6 pre-set templates) | Pre-set base-image templates keyed by alias; see below |
| `spring.ai.loom.agent.compile.extraRunArgs` | string[] | `[]` | Extra `docker run` args injected between `--name` and the image name |

### 9.3 Base-image Templates (built-in)

| Alias | Image | Default ENTRYPOINT |
|-------------------|--------------------------------------|---------------------------------------------|
| `java17` | `eclipse-temurin:17-jre-alpine` | `["java","-jar","app.jar"]` |
| `java21` | `eclipse-temurin:21-jre-alpine` | `["java","-jar","app.jar"]` |
| `nginx` | `nginx:1.27-alpine` | `["nginx","-g","daemon off;"]` |
| `python3` | `python:3.12-slim` | `["python","app.py"]` |
| `node20` | `node:20-alpine` | `["node","dist/index.js"]` |
| `node20-serve` | `nginx:1.27-alpine` | `["nginx","-g","daemon off;"]` |

Override or add templates via yml:

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

Pass the alias to `baseImage` to select a template, or pass a full image name (e.g. `openjdk:17-slim`) to use it directly — `command` falls back to `java17`.

### 9.4 Example Invocation

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

### 9.5 End-to-End Conversation Examples

Below are three complete walkthroughs showing how a user describes a deployment in chat, how the LLM extracts and asks clarifying questions, and how it finally invokes the end-to-end deployment tool (`ICompileAndDeployTool` — the actual registered tool name is auto-derived from the method by Spring AI; do not hardcode it in skill templates).

#### Scenario A: Maven multi-module Spring Boot project

**User prompt**:

```text
Help me deploy https://gitee.com/wb04307201/java-brain.git — I want to deploy the oms submodule. It's a private repo: username `wb04307201`, password `••••••••`.
Use port 8081 for both host and container. Health-check path: `/sql/forge/web`
```

**LLM-extracted tool invocation** (`buildTool` auto-detected from the `pom.xml` in the submodule — no need for the user to spell it out):

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

**Tool-side defaults** (user did not need to specify):

| Param | Default |
| --- | --- |
| `baseImage` | `buildTool=maven` → `java17` (uses `eclipse-temurin:17-jre-alpine`) |
| `imageName` / `containerName` | Auto-generated from timestamp |
| `runCommand` | `["java","-jar","app.jar"]` from the `java17` template |

**Tool return value on success** (example):

```text
✅ Deployment succeeded
- Image: sql-forge-demo-20260612-153022
- Container: sql-forge-demo-20260612-153022
- Access URL: http://localhost:8081/sql/forge/web
- Health check: 6.8s
- Image build: 42.1s
```

#### Scenario B: Node static frontend (Vue / React build output → nginx)

**User prompt**:

```text
Deploy https://gitee.com/example/spa-admin.git, single repo, host port 8088, nginx listens on 80 inside the container, access path /admin.
```

**LLM-extracted tool invocation**:

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

**Notes**:
- `buildTool=npm-frontend` → Dockerfile runs `npm ci && npm run build`, copies `dist/` into the nginx image
- `baseImage=node20-serve` → built-in nginx template (omitted = same default for `npm-frontend`)
- `containerPort=80` → nginx default listen port; `port=8088` → browser hits `http://localhost:8088/admin/`
- `healthPath=/admin/` → also used as the access URL path

#### Scenario C: Python project

**User prompt**:

```text
Deploy https://gitee.com/example/py-service.git, port 9000, requirements.txt at the repo root.
```

**LLM-extracted tool invocation**:

```json
{
 "gitUrl": "https://gitee.com/example/py-service.git",
 "buildTool": "pip",
 "port": 9000,
 "containerPort": 9000,
 "healthPath": "/"
}
```

**Notes**:
- `buildTool=pip` → Dockerfile runs `pip install -r requirements.txt` (if `pyproject.toml` also exists, LLM should ask which to use)
- `baseImage` omitted → `buildTool=pip` defaults to `python3` (uses `python:3.12-slim`)
- `runCommand` omitted → uses `python3` template's `["python","app.py"]`. For `gunicorn` / `uvicorn` entry points, pass `runCommand` explicitly.

#### Common Variants

| Scenario | Adjustment |
| --- | --- |
| Public repo (no creds) | Omit `gitUsername` / `gitPassword` |
| Single-module repo (root is the target) | Omit `subDir`; `buildTool` can also be omitted (auto-detect) |
| Specific branch | Pass `branch=release/2.0` (default = remote HEAD) |
| Spring Boot with context-path | `healthPath` is the full context-path (leading `/`); access URL = `http://localhost:{port}{healthPath}` |
| Custom ENTRYPOINT | Pass `runCommand=["gunicorn","-b","0.0.0.0:9000","app:app"]` (rare — default follows the template) |
| Private Harbor / custom image | Override `image-templates.<alias>.image` in yml, then pass `baseImage=<alias>` |
| Custom image / container name on host | Pass `imageName=...` / `containerName=...` (default = timestamp) |
| `port` or `containerPort` missing | Tool **returns fail** (no yml fallback) — LLM must ask the user |

> **🔐 Security Note — never put real passwords in chat / docs / git history**
>
> `Password: ••••••••` in the prompt and `gitPassword: "<your-password>"` in the JSON are **placeholders**. Never paste real repo passwords, personal tokens, SSH private keys, etc. in cleartext into chat, issues, docs, or commit messages — they may be retained in server logs, screenshots, LLM training data, or git history and are hard to scrub.
>
> **Recommended practices**:
>
> 1. **Public repos**: simply omit `gitUsername` / `gitPassword`.
> 2. **Private repos** (in order of preference):
 > - Configure `spring.ai.loom.agent.git.username` / `git.token` in `application.yml` — the tool injects them implicitly; the LLM never sees the credentials.
 > - Use your CI/CD platform's **Secret / Credential variables** (GitHub Actions, GitLab CI, Jenkins Credentials, etc.) and inject at runtime.
> - Use an **SSH key** (mount `id_rsa` + `config` into the container or `~/.ssh/` on the host); the git URL becomes `git@…` and the LLM does not need a password.
> 3. **Ad-hoc debugging**: store the password outside the LLM (env var, temp file) and say "password is in place" in the chat.

---

## 10. `ISubTaskTool` — Sub-task Delegation

| Item | Details |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface** | `cn.wubo.spring.ai.loom.agent.subtask.ISubTaskTool` |
| **Default** | `DefaultSubTaskTool` |
| **Override** | Custom `@Bean ISubTaskTool` |
| **State** | **Universal** — `@ToolGroup(defaultGranted=true)`. Sub-task runs inherit user identity — no privilege escalation surface. |
| **Methods** | `start_sub_task(prompt, systemContext)` — delegate a slice of work to a "sub-model" that runs **synchronously**<br/>`list_sub_tasks()` — list active sub-tasks in the current conversation<br/>`cancel_sub_task(subTaskId)` — cancel a running sub-task in the current conversation<br/>`get_sub_task_history(limit)` — get sub-task history for the current conversation |

The sub-task runs on the dedicated `loomSubTaskExecutor` pool (`ISubTaskExecutor` / `DefaultSubTaskExecutor`). Its tool set is filtered to **exclude self-tools** (`ISubTaskTool` / `IScheduleTool`) so a sub-task cannot spawn further sub-tasks or schedules (recursion guard). Sub-task memory is namespaced `{conversationId}--sub--{subTaskId}`.

**Configuration**:

| Property | Default | Description |
|--------------------------------|---------|------------------------------------------|
| `subtask.enabled` | `true` | Enable the `start_sub_task` tool |
| `subtask.max-concurrent` | `4` | Max concurrent sub-tasks |
| `subtask.max-history` | `200` | Retained sub-task history entries |

**Error codes** (thrown as `LoomAgentRuntimeException` and mapped to HTTP status by the route layer):

| Code | HTTP | Trigger |
| --- | --- | --- |
| `TASK_NOT_FOUND` | 404 | `cancel_sub_task` / `get_sub_task_history` with an unknown `id` |
| `CROSS_CONVERSATION_FORBIDDEN`| 403 | `cancel_sub_task` where the task's `conversationId` ≠ current user's conversation |

**Namespaces**

| Resource | Format | Notes |
| --- | --- | --- |
| Sub-task memory (ChatMemory) | `{conversationId}--sub--{subTaskId}` | Each sub-task gets its own memory slice so it doesn't leak into the main conversation |
| Sub-task pool | `loomSubTaskExecutor` (4 threads, queue 50) | Sub-tasks block the LLM call until completion (synchronous) |

---

## 11. `IScheduleTool` — Scheduled Tasks

| Item | Details |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface** | `cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool` |
| **Default** | `DefaultScheduleTool` |
| **Override** | Custom `@Bean IScheduleTool` |
| **State** | **Universal** — `@ToolGroup(defaultGranted=true)`. Scheduled tasks are namespaced `loom-sched-{user}-{conv}-{name}` and fire as sub-tasks. |
| **Methods (4)** | `createSchedule` (cron / fixed_delay / fixed_rate / one_shot), `cancelSchedule`, `listSchedules`, `getScheduleHistory` |

Scheduled tasks are namespaced `loom-sched-{username}-{conversationId}-{name}` and **fire as sub-tasks** when triggered. LoomAgent owns the H2 persistence (`loom_scheduled_task`, added in ``); `ScheduleRestoreListener` rehydrates rows on `ApplicationReadyEvent` preserving the original `createdAt` so the `max-lifetime` ceiling accumulates across restarts (rows older than the ceiling are cleaned up). Cancelling verifies row ownership (cross-user cancel is refused) and deletes the persisted row so the restore listener cannot resurrect a "ghost" task.

**Trigger constraints** come from `flex.schedule.limits`:

| Property | Example | Description |
|--------------------------------|---------|------------------------------------------|
| `schedule.enabled` | `true` | Enable the schedule tools |
| `flex.schedule.limits.min-interval` | `10m` | Minimum trigger interval |
| `flex.schedule.limits.max-lifetime` | `72h` | Max task lifetime (accumulates across restarts) |
| `flex.schedule.limits.mode` | `strict`| `strict` = exceeding a limit throws |

**Error codes** (thrown as `LoomAgentRuntimeException`):

| Code | HTTP | Trigger |
| --- | --- | --- |
| `TASK_NOT_FOUND` | 404 | `cancelSchedule` / `getScheduleHistory` with an unknown task |
| `OWNERSHIP_VIOLATION` | 403 | `cancelSchedule` / `getScheduleHistory` where the task's `username` ≠ caller |
| `NAME_TAKEN` | 400 | `createSchedule` with a name that already exists for the same `(user, conversation)` |
| `INTERVAL_TOO_SHORT` | 400 | `createSchedule` with interval < `flex.schedule.limits.min-interval` (default 10m) |
| `LIFETIME_EXCEEDED` | 400 | `createSchedule` with end-before-start or > `max-lifetime` |

**Namespaces**

| Resource | Format | Notes |
| --- | --- | --- |
| Schedule task name | `loom-sched-{username}-{conversationId}-{name}` | Frontend POSTs the full name to `/spring/ai/loom/schedule/cancel` (body field `name`) |
| Persistence | `loom_scheduled_task` table (migration) | `ScheduleRestoreListener` rehydrates on `ApplicationReadyEvent`, preserving `createdAt` so `max-lifetime` accumulates across restarts; rows exceeding the ceiling are auto-cleaned on startup |

---

## 12. Replacing a Sub-Tool

Each sub-tool interface is registered with `@ConditionalOnMissingBean`, so a custom implementation wins automatically:

```java
@Bean
public IFileTool customFileTool(IFile file, LoomAgentProperties properties) {
 return new MyCustomFileTool(file, properties.getFileBasePath());
}
// DefaultTimeTool and DefaultSkillTool remain active
```

You can also re-enable a disabled group by registering your own bean:

```java
@Bean
public IGitTool customGitTool() {
 return new MyCliGitTool(); // enabled regardless of git.enabled
}
```

---

- For broader configuration properties (RAG / JVector / MCP / Skill / Auth / File / Git), see [CUSTOMIZATION.md](./CUSTOMIZATION.md)
- For HTTP API reference, see [API.md](./API.md)
