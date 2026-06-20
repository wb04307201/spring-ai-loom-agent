# Spring AI LoomAgent — Built-in Tools

> Reference for every tool that LoomAgent exposes to the LLM by default. Each tool group can be enabled/disabled independently, and each sub-tool interface can be replaced with a custom `@Bean`.

---

## Table of Contents

- [1. Tool Group Switches](#1-tool-group-switches)
- [2. `IEmbedTool` Overview](#2-iembedtool-overview)
- [3. `ITimeTool` — Time Tools](#3-itimetool--time-tools)
- [4. `ISkillTool` — Skill Tools](#4-iskilltool--skill-tools)
- [5. `IFileTool` — File Tools](#5-ifiletool--file-tools)
- [6. `IGitTool` — Git Tools (JGit)](#6-igittool--git-tools-jgit)
- [7. `IMavenTool` — Maven Build Tools (maven-invoker)](#7-imaventool--maven-build-tools-maven-invoker)
- [8. `ICompileAndDeployTool` — End-to-End Deployment](#8-icompileanddeploytool--end-to-end-deployment)
  - [8.1 Tool-call Parameters](#81-tool-call-parameters)
  - [8.2 Configuration](#82-configuration)
  - [8.3 Base-image Templates (built-in)](#83-base-image-templates-built-in)
  - [8.4 Example Invocation](#84-example-invocation)
  - [8.5 End-to-End Conversation Examples](#85-end-to-end-conversation-examples)
- [9. Replacing a Sub-Tool](#9-replacing-a-sub-tool)

---

## 1. Tool Group Switches

Every built-in tool group is gated by a `*.enabled` property under `spring.ai.loom.agent.*`. The `time` / `file` / `skill` / `compile` groups are enabled by default; `git` and `maven` are **opt-in** because end-to-end deployment already covers the compile/package use case.

| Property           | Type    | Default | Description                                                       |
|--------------------|---------|---------|-------------------------------------------------------------------|
| `time.enabled`     | boolean | `true`  | Time tools (`ITimeTool` — current time, timezone conversion)       |
| `file.enabled`     | boolean | `true`  | File tools (`IFileTool` — 16 path-based read/write/edit/delete)   |
| `skill.enabled`    | boolean | `true`  | Skill tools (`ISkillTool` — list skills, get skill details)       |
| `git.enabled`      | boolean | `false` | Git tools (`IGitTool` — 31 git operations via JGit). **Opt-in** — end-to-end deployment uses `ICompileAndDeployTool`. |
| `maven.enabled`    | boolean | `false` | Maven build tools (`IMavenTool` — also requires `maven-invoker` on classpath). **Opt-in** — compile/package goes through `ICompileAndDeployTool`. |
| `compile.enabled`  | boolean | `true`  | End-to-end deployment tool (`ICompileAndDeployTool` — git clone → buildTool build [maven/npm/pip] → docker build → docker run → health check). Supports Spring Boot, Node (backend + static-frontend → nginx), and Python projects. |

> Even with the tool group disabled, you can still register your own `@Bean IGitTool` / `@Bean IMavenTool` to opt back in — `@ConditionalOnMissingBean` honors user beans first.

### Disable example

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

## 2. `IEmbedTool` Overview

`IEmbedTool` is an aggregate marker interface. Sub-interfaces (`ITimeTool`, `ISkillTool`, `IFileTool`, `IGitTool`, `IMavenTool`) each contribute independent `@Tool` methods to the LLM. `ICompileAndDeployTool` also extends `IEmbedTool` and is the recommended end-to-end entry point for deployment.

| Sub-Interface             | Default Impl                | Methods | Default State | Notes                                          |
|---------------------------|-----------------------------|---------|---------------|------------------------------------------------|
| `ITimeTool`               | `DefaultTimeTool`           | 2       | enabled       | Always on when `time.enabled` is unset        |
| `ISkillTool`              | `DefaultSkillTool`          | 2       | enabled       | Pairs with the `skills[]` configuration        |
| `IFileTool`               | `DefaultFileTool`           | 16      | enabled       | Path-based; root = `{fileBasePath}/{username}/` |
| `IGitTool`                | `DefaultGitTool` (JGit 7.6) | 31      | **disabled**  | Opt-in via `git.enabled=true`                  |
| `IMavenTool`              | `DefaultMavenTool` (maven-invoker 3.3.0) | 6 | **disabled**  | Opt-in via `maven.enabled=true`; needs `maven-invoker` on classpath |
| `ICompileAndDeployTool`   | `DefaultCompileAndDeployTool` | 1     | enabled       | End-to-end `git clone → build → docker run → health check` |

---

## 3. `ITimeTool` — Time Tools

| Item            | Details                                                                               |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool`                                    |
| **Default**     | `DefaultTimeTool`                                                                     |
| **Override**    | Custom `@Bean ITimeTool`                                                              |
| **State**       | Enabled by default; toggle with `spring.ai.loom.agent.time.enabled`                    |
| **Methods**     | `getCurrentTime` — get current time by timezone; `convertTime` — convert between timezones |

---

## 4. `ISkillTool` — Skill Tools

| Item            | Details                                                                               |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.tool.skill.ISkillTool`                                  |
| **Default**     | `DefaultSkillTool`                                                                    |
| **Override**    | Custom `@Bean ISkillTool`                                                             |
| **State**       | Enabled by default; toggle with `spring.ai.loom.agent.skill.enabled`                   |
| **Methods**     | `skillContents` — list all skills; `getSkill` — get skill details                     |

---

## 5. `IFileTool` — File Tools

| Item            | Details                                                                               |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.tool.file.IFileTool`                                    |
| **Default**     | `DefaultFileTool`                                                                     |
| **Override**    | Custom `@Bean IFileTool`                                                              |
| **State**       | Enabled by default; toggle with `spring.ai.loom.agent.file.enabled`                   |
| **Root path**   | All path-based operations use `{fileBasePath}/{username}/` (default `.local/file/{username}/`) |

**Methods (16)**:

| Method                    | Purpose                                                                 |
|---------------------------|-------------------------------------------------------------------------|
| `readTextFile`            | Read text content of a single file                                      |
| `readMediaFile`           | Read a media file (image/audio)                                         |
| `readMultipleFiles`       | Read several files in one call                                          |
| `writeFile`               | Create or overwrite a file                                              |
| `editFile`                | Targeted text edit in an existing file                                  |
| `createDirectory`         | Create a directory (recursively)                                        |
| `moveFile`                | Move or rename a file/directory                                         |
| `searchFiles`             | Glob/regex search across the file tree                                  |
| `listAllowedDirectories`  | List directories the LLM is allowed to access                           |
| `listDirectory`           | List directory entries                                                  |
| `listDirectoryWithSizes`  | List entries with size info                                             |
| `directoryTree`           | Recursive directory tree                                                |
| `getFileInfo`             | Metadata (size, mtime, type) of a file/directory                        |
| `downloadFileUrl`         | Get a download URL (auto-creates a temporary `file_info` record, `usage="temp"`) |
| `viewFileUrl`             | Get a preview URL (auto-creates a temporary `file_info` record)         |
| `deleteFileOrDirectory`   | Delete with explicit `I_CONFIRM_DELETE` confirmation (token configurable via `spring.ai.loom.agent.file.deleteConfirmToken`); supports recursive directory removal; cleans up `file_info` records for deleted files |

---

## 6. `IGitTool` — Git Tools (JGit)

| Item            | Details                                                                               |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.tool.git.IGitTool`                                      |
| **Default**     | `DefaultGitTool` (based on Eclipse JGit 7.6.0)                                        |
| **Override**    | Custom `@Bean IGitTool`                                                               |
| **State**       | `@ConditionalOnProperty(name = "spring.ai.loom.agent.git.enabled", havingValue = "true")` — **disabled by default** |
| **Working dir** | Set via `gitSetWorkingDir` (absolute path or relative to `{fileBasePath}/{username}/`); `gitInit` / `gitClone` accept an absolute path or a relative path under the user file dir |

**Methods (31)**:

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

| Item            | Details                                                                               |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.tool.maven.IMavenTool`                                  |
| **Default**     | `DefaultMavenTool` (based on maven-invoker 3.3.0, no shell dependency)                |
| **Override**    | Custom `@Bean IMavenTool`                                                             |
| **State**       | `@ConditionalOnClass(name = "org.apache.maven.shared.invoker.Invoker")` and `@ConditionalOnProperty(name = "spring.ai.loom.agent.maven.enabled", havingValue = "true")` — **disabled by default** |
| **Methods (6)** | `mavenExecute` (generic Maven goal execution); `mavenBuild` (compile); `mavenPackage` (package JAR/WAR); `mavenTest` (run tests, supports test pattern); `mavenDependencyTree` (dependency tree with scope filter); `mavenValidate` (validate project structure) |

**Configuration**:

| Property                                      | Type    | Default     | Description                                        |
|-----------------------------------------------|---------|-------------|----------------------------------------------------|
| `spring.ai.loom.agent.maven.enabled`          | boolean | `false`     | Whether to enable Maven tool (opt-in)              |
| `spring.ai.loom.agent.maven.mavenHome`        | String  | —           | Maven install directory (optional, uses PATH if empty) |
| `spring.ai.loom.agent.maven.localRepository`  | String  | —           | Local repository path (optional)                   |
| `spring.ai.loom.agent.maven.maxOutputLines`   | int     | `200`       | Max output lines before truncation                 |
| `spring.ai.loom.agent.maven.defaultTimeoutMs` | long    | `300000`    | Default execution timeout (5 minutes)              |

> For compile/package inside the deployment pipeline, prefer `ICompileAndDeployTool`. Use `IMavenTool` only when the LLM needs a single-point `mvn` command.

---

## 8. `ICompileAndDeployTool` — End-to-End Deployment

`ICompileAndDeployTool` runs the full pipeline in a single LLM tool call: `git clone → buildTool build (maven / npm / pip) → docker build → docker run → health check`. It is the **supported entry point** for `git clone → build → docker run` workflows — LLM only needs to supply the parameters and read back the `accessUrl`.

| Item            | Details                                                                                |
|-----------------|----------------------------------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.tool.compile.ICompileAndDeployTool`                       |
| **Default**     | `DefaultCompileAndDeployTool`                                                          |
| **Override**    | Custom `@Bean ICompileAndDeployTool`                                                   |
| **State**       | Enabled by default; toggle with `spring.ai.loom.agent.compile.enabled`                 |
| **Method**      | `compileAndDeploy(Map<String,Object> params, ToolContext toolContext)` → `CompileAndDeployResult` |
| **Workspace**   | Each call creates an isolated work dir under `{fileBasePath}/{username}/compile-deploy-<uuid>/` |

### 8.1 Tool-call Parameters

`params` is a case-insensitive Map. Required: `gitUrl`, `port`, `containerPort`. Others are optional.

| Key              | Required | Description                                                                                                          |
|------------------|----------|----------------------------------------------------------------------------------------------------------------------|
| `gitUrl`         | yes      | Git repository URL                                                                                                   |
| `gitUsername`    | no       | Git username (public repos can skip)                                                                                 |
| `gitPassword`    | no       | Git password or token (public repos can skip)                                                                        |
| `branch`         | no       | Branch to clone (defaults to remote HEAD)                                                                            |
| `port`           | yes      | Host port the container publishes (the URL the caller accesses: `http://localhost:{port}/{healthPath}`)             |
| `containerPort`  | yes      | Port the application listens on inside the container (no yml fallback; reference `server.port` in application.yml)  |
| `subDir`         | no       | Subdirectory of a multi-module repo; **required** when root has no `pom.xml` — tool returns `fail` otherwise           |
| `imageName`      | no       | Docker image name (default derived from timestamp)                                                                   |
| `containerName`  | no       | Docker container name (default derived from timestamp)                                                               |
| `healthPath`     | no       | Health-check path **and** access URL path (e.g. `healthPath=sql-forge-demo` → `http://localhost:{port}/sql-forge-demo`; pass `/` when there is no context-path) |
| `buildTool`      | no       | Build stack: `maven` / `npm` / `npm-frontend` / `pip`. Auto-detected from markers when omitted (`pom.xml` → `maven`, `package.json` → `npm`, `requirements.txt` / `pyproject.toml` → `pip`). Multi-module repos with multiple markers must specify explicitly. |
| `baseImage`      | no       | Template alias (`java17` / `java21` / `nginx` / `python3` / `node20` / `node20-serve`) or full image name (e.g. `openjdk:17-slim`). Default per `buildTool`: `maven→java17`, `npm→node20`, `npm-frontend→node20-serve`, `pip→python3`. |
| `runCommand`     | no       | String array overriding the template's default ENTRYPOINT (rare)                                                     |

### 8.2 Configuration

All settings live under `spring.ai.loom.agent.compile.*`.

| Property                                          | Type     | Default                          | Description                                                                                                  |
|---------------------------------------------------|----------|----------------------------------|--------------------------------------------------------------------------------------------------------------|
| `spring.ai.loom.agent.compile.enabled`            | boolean  | `true`                           | Whether to register the end-to-end deploy tool (default enabled)                                            |
| `spring.ai.loom.agent.compile.mavenHome`          | string   | auto-discover                    | Optional Maven install dir; falls back to `maven.mavenHome` and PATH                                         |
| `spring.ai.loom.agent.compile.dockerCmd`          | string   | `docker`                         | Optional override for the docker CLI binary                                                                  |
| `spring.ai.loom.agent.compile.mavenTimeoutMs`     | long     | `600000`                         | Maven build timeout (10 minutes)                                                                             |
| `spring.ai.loom.agent.compile.dockerBuildTimeoutMs`| long    | `600000`                         | `docker build` timeout (10 minutes)                                                                          |
| `spring.ai.loom.agent.compile.dockerRunTimeoutMs`  | long     | `60000`                          | `docker run` startup timeout (1 minute)                                                                      |
| `spring.ai.loom.agent.compile.healthCheckMaxWaitMs`| long     | `60000`                          | Total wait for the post-startup health check (1 minute)                                                      |
| `spring.ai.loom.agent.compile.healthCheckIntervalMs`| long    | `2000`                           | Health-check poll interval (2 seconds)                                                                       |
| `spring.ai.loom.agent.compile.keepWorkspace`      | boolean  | `false`                          | Keep the per-call workspace after deployment finishes (default: delete; set `true` to debug)                  |
| `spring.ai.loom.agent.compile.imageTemplates`     | map      | (6 pre-set templates)            | Pre-set base-image templates keyed by alias; see below                                                       |
| `spring.ai.loom.agent.compile.extraRunArgs`       | string[] | `[]`                             | Extra `docker run` args injected between `--name` and the image name                                         |

### 8.3 Base-image Templates (built-in)

| Alias             | Image                                | Default ENTRYPOINT                          |
|-------------------|--------------------------------------|---------------------------------------------|
| `java17`          | `eclipse-temurin:17-jre-alpine`      | `["java","-jar","app.jar"]`                 |
| `java21`          | `eclipse-temurin:21-jre-alpine`      | `["java","-jar","app.jar"]`                 |
| `nginx`           | `nginx:1.27-alpine`                  | `["nginx","-g","daemon off;"]`              |
| `python3`         | `python:3.12-slim`                   | `["python","app.py"]`                       |
| `node20`          | `node:20-alpine`                     | `["node","dist/index.js"]`                  |
| `node20-serve`    | `nginx:1.27-alpine`                  | `["nginx","-g","daemon off;"]`              |

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

### 8.4 Example Invocation

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

### 8.5 End-to-End Conversation Examples

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
- Image:   sql-forge-demo-20260612-153022
- Container: sql-forge-demo-20260612-153022
- Access URL: http://localhost:8081/sql/forge/web
- Health check: 6.8s
- Image build:  42.1s
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
>    - Configure `spring.ai.loom.agent.git.username` / `git.token` in `application.yml` — the tool injects them implicitly; the LLM never sees the credentials.
>    - Use your CI/CD platform's **Secret / Credential variables** (GitHub Actions, GitLab CI, Jenkins Credentials, etc.) and inject at runtime.
>    - Use an **SSH key** (mount `id_rsa` + `config` into the container or `~/.ssh/` on the host); the git URL becomes `git@…` and the LLM does not need a password.
> 3. **Ad-hoc debugging**: store the password outside the LLM (env var, temp file) and say "password is in place" in the chat.

---

## 9. Replacing a Sub-Tool

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
    return new MyCliGitTool();   // enabled regardless of git.enabled
}
```

---

- For broader configuration properties (RAG / JVector / MCP / Skill / Auth / File / Git), see [CUSTOMIZATION.md](./CUSTOMIZATION.md)
- For HTTP API reference, see [API.md](./API.md)
