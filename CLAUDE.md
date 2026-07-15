# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Spring AI LoomAgent** — A Spring Boot auto-configuration library that provides an out-of-the-box chat UI with RAG knowledge base, MCP tool calling, **Skill library + Skill market**, and role-based access (RBAC) for Spring AI applications.

- **JDK**: 17+
- **Framework**: Spring Boot 3.x + Spring AI 1.x
- **Build**: Maven (multi-module)
- **Database**: H2 (default), with Flyway migrations

## Project Overview Images

`docs/project-overview-en.png` and `docs/project-overview-zh.png` (shown at the top of `README.md` / `README.zh-CN.md`) are generated from project source + `README.md` + this `CLAUDE.md` by the project skill at **`.claude/skills/project-overview-image/`** using the latest DashScope image model. Regenerate via the skill trigger phrases ("更新项目概览图", "刷新 README 顶部的 overview 图", "生成 docs/project-overview-{en,zh}.png") whenever the architecture changes meaningfully — never hand-edit the PNGs. Prompt payloads and the API call shape live in `docs/image-generation-prompts.md`.

## Module Structure

| Module | Purpose |
|--------|---------|
| `spring-ai-loom-agent` | Core library — chat, knowledge base, file, MCP, skill (market + role auth), RBAC (user/role/mcp), user interfaces + default implementations, JVector vector store, H2 schema, static frontend resources |
| `spring-ai-loom-agent-spring-boot-autoconfigure` | `LoomAgentConfiguration` with 7 nested static `@Configuration` classes (Infrastructure, Chat, Rag, Mcp, Tool, Storage, Web) — `@AutoConfiguration` with `@ConditionalOnMissingBean` on all beans for full replaceability |
| `spring-ai-loom-agent-spring-boot-starter` | Empty JAR that depends on autoconfigure — the one dependency users add |
| `spring-ai-loom-agent-test` | Test application with `application.yml` — run locally to verify changes |

## Key Commands

```bash
# Build all modules (skip GPG signing for local dev)
mvn clean install -Dgpg.skip=true

# Run the test application
mvn spring-boot:run -pl spring-ai-loom-agent-test

# Run a single test
mvn test -pl spring-ai-loom-agent-test -Dtest=ChatTest

# Package for release (includes GPG signing)
mvn clean deploy
```

## Architecture

### Core Interfaces (in `spring-ai-loom-agent`)

All components follow an **interface + default implementation** pattern. Every bean is registered with `@ConditionalOnMissingBean`, allowing consumers to replace any piece:

| Interface | Default Impl | Responsibility |
|-----------|-------------|----------------|
| `IChat` | `DefaultChat` | Chat streaming (SSE), MCP tool orchestration, RAG augmentation. `stream(record, username, request)` — username injected by filter |
| `IKnowledge` | `DefaultKnowledge` | Knowledge base CRUD (stored in H2) |
| `IMcp` | `SyncMcp` / `ASyncMcp` | MCP client wrapper (sync or async), tool discovery & invocation |
| `ISkillStorage` | `DefaultSkillStorage` | Per-user `user_skill` storage (DB). Auto-syncs `role_skill` → `user_skill` (locked ROLE_GRANTED entries) on every list/get. Admins get a union view (APPROVED + own PENDING). Pairs with `ISkillMarketService` and `ISkillRoleAdmin`. |
| `IFile` | `DefaultFile` | File metadata storage (H2) — 仅用于知识空间文件、文件预览/下载桥接、聊天附件 |
| `IUpload` | `DefaultUpload` | File upload pipeline: 上传文件存储到 `fileBasePath/{username}/`，知识库文件存储到 `knowledgeBasePath/{username}/{knowledgeId}/`，重名自动追加序号 |
| `IUser` | `DefaultUser` | BFF + HttpOnly cookie session auth + auto-login |
| `IUserConversation` | `DefaultUserConversation` | User-to-conversation mapping |
| `IEmbedTool` | _(marker interface)_ | Aggregate type for all embed tools, sub-interfaces extend it |
| `ITimeTool` | `DefaultTimeTool` | Time tools: get current time, convert between timezones |
| `ISkillTool` | `DefaultSkillTool` | Skill tools: list skills, get skill details |
| `IFileTool` | `DefaultFileTool` | 16 File tools: 基于路径的读写/编辑/搜索/目录浏览（readTextFile, readMediaFile, readMultipleFiles, writeFile, editFile, createDirectory, moveFile, searchFiles, listAllowedDirectories, listDirectory, listDirectoryWithSizes, directoryTree, getFileInfo, downloadFileUrl, viewFileUrl, deleteFileOrDirectory），预览/下载自动桥接 fileId，删除支持递归 + 显式确认 + 清理临时 file_info 记录 |
| `IGitTool` | `DefaultGitTool` | 28 Git tools: init, clone, status, add, commit, diff, log, branch, checkout, pull, push, fetch, merge, rebase, reset, stash, tag, remote, blame, show, reflog, clean, cherry-pick, worktree, set-working-dir, clear-working-dir, changelog-analyze, wrapup-instructions（**默认 disabled** — `git.enabled=false`；需要单点 git 操作时设 `true`），不依赖 IFile |
| `IMavenTool` | `DefaultMavenTool` | 6 Maven tools: mavenExecute (generic), mavenBuild (compile), mavenPackage (package), mavenTest (run tests), mavenDependencyTree (dep tree), mavenValidate (validate) — based on maven-invoker, no shell needed（**默认 disabled** — `maven.enabled=false`；编译/打包请走 `ICompileAndDeployTool`，需要单点 mvn 命令时设 `true`） |
| `ICompileAndDeployTool` | `DefaultCompileAndDeployTool` | 端到端部署：git clone → 按 buildTool 打包（maven / npm / npm-frontend / pip）→ Docker 镜像构建 → 容器启动 → 健康检查（**默认 enabled**）。支持 Spring Boot / Node（前后端） / Python 等多栈项目。单次 LLM tool call 完成整个部署流水线，避免 LLM 拆解成多步时出错。 |
| `IDocumentRead` | `DefaultDocumentRead` | Document reading with LLM metadata enrichment |
| `IFileDocument` | `DefaultFileDocument` | File-to-document ID mapping |

### Auto-Configuration (`LoomAgentConfiguration`)

Organized into 7 nested static `@Configuration` classes:

| Inner Class | Responsibility |
|-------------|----------------|
| `InfrastructureConfiguration` | Properties binding, Flyway, ChatMemory, BeanFactoryPostProcessors |
| `ChatConfiguration` | ChatClient, IChat, SseController |
| `RagConfiguration` | VectorStore (JVector fallback), DocumentRead, RAG Advisor, IUpload (all conditional on VectorStore) |
| `McpConfiguration` | SyncMcp / ASyncMcp |
| `ToolConfiguration` | ITimeTool, ISkillTool, IFileTool, IGitTool, IMavenTool, ICompileAndDeployTool — `time/file/skill/compile` 默认 enabled；`git/maven` 默认 disabled。Each can be enabled/disabled via `spring.ai.loom.agent.{time,file,skill,git,maven,compile}.enabled=true/false`. IMavenTool additionally requires maven-invoker on the classpath. |
| `StorageConfiguration` | IUser, IUserConversation, ISkillStorage, IFile, IFileDocument, IKnowledge |
| `WebConfiguration` | AuthenticationFilter, 6 RouterFunctions |

- `@AutoConfigureAfter` all Spring AI model/embedding/vectorstore/memory/MCP auto-configurations
- Creates `ChatClient` with `MessageChatMemoryAdvisor` and `SimpleLoggerAdvisor`
- Default `JVectorStore` (HNSW index, disk-persisted) when no other `VectorStore` bean exists
- `RetrievalAugmentationAdvisor` with configurable prompt templates and similarity threshold
- `IGitTool` (Eclipse JGit 7.6.0) is **disabled by default** (`git.enabled=false`); users opt in with `spring.ai.loom.agent.git.enabled=true` for single-point git operations (status/log/blame/branch etc.) or replace it with a custom `@Bean IGitTool` via `@ConditionalOnMissingBean`. End-to-end deployment is handled by `ICompileAndDeployTool`.
- `IMavenTool` is **disabled by default** (`maven.enabled=false`); same opt-in pattern. Compile/package is handled by `ICompileAndDeployTool`.
- `ICompileAndDeployTool` is **enabled by default**; the supported entry point for `git clone → buildTool build (maven/npm/pip) → docker build → docker run → health check`. Supports `maven` / `npm` (Node 后端) / `npm-frontend` (Node 前端 → nginx) / `pip` (Python) — selected by `buildTool` param or auto-detected from marker files (`pom.xml` / `package.json` / `requirements.txt` / `pyproject.toml`).
- REST endpoints under `/spring/ai/loom/*` (RouterFunctions + one `@RestController` for SSE)
- `AuthenticationFilter` on `/*` (matches all), with `AntPathMatcher` filtering via `auth.pathPatterns` and `auth.excludePathPatterns`

### Data Layer

- **Schema** (库自带的 schema + admin seed)：
  - 库 `src/main/resources/db/migration/V1.0__init.sql` — 建表（knowledge / file / user / conversation / token / skill / role / mcp_server / mcp_tool / market_skill / user_skill / role_skill / role_mcp）+ 默认 admin 账号
  - 业务 `spring-ai-loom-agent-test/src/main/resources/db/migration/V1.1__init_app_data.sql` — 业务 demo 数据：12 个 mcp_server + 14 个 mcp_tool + 6 个 system skill
  - 两套 Flyway 用小数版本号（V1.0 / V1.1）在同一 Flyway 实例里按字典序执行
- **Chat memory**: Spring AI `JdbcChatMemoryRepository` (JDBC-backed, auto-initialized)
- **Flyway table**: `flyway_schema_history`（Spring Boot 默认，库不覆盖）

### File System Storage

- **用户文件目录**: `{fileBasePath}/{username}/`（默认 `.local/file/{username}/`）— `DefaultUpload.upload()`、`DefaultFileTool` 所有文件操作的根目录
- **知识库文件目录**: `{knowledgeBasePath}/{username}/{knowledgeId}/`（默认 `.local/knowledge/{username}/{knowledgeId}/`）— `DefaultUpload.uploadWithKnowledge()` 的存储位置
- **重名处理**: 同名文件自动追加序号，如 `file.txt` → `file(1).txt` → `file(2).txt`
- **预览/下载桥接**: 路径操作的预览/下载通过 `IFile.getByExactPath()` 查询，不存在时自动插入 `usage='temp'` 记录获取 fileId

### Configuration Properties

All under `spring.ai.loom.agent`:
- `rag` — similarity threshold, top-k, prompt templates
- `jvector` — index path, HNSW params (m, efConstruction, efSearch)
- `mcps` — list of MCP service configs (name, title, description, tools, default-selected)
- ~~`skills`~~ — **no longer read from yml**. Skill data lives in the database now (tables `market_skill` / `user_skill` / `role_skill`); 6 system skills are seeded by the init migration. Manage via the admin console → **Skill Market** page.
- `auth` — `enabled` (boolean, default true), `pathPatterns` (Ant-style path list), `excludePathPatterns`, `adminPathPatterns` (gates `/admin/**` to admin users), `cookie` (name, path, domain, secure, sameSite, maxAge)
- `init` — **Note**: The actual runtime gate for `ChatClient` creation is `spring.ai.chat.ui.init` (not `spring.ai.loom.agent.init`). Set `spring.ai.chat.ui.init=false` to prevent ChatClient auto-creation. Default: `true`
- `user` — default username, nickname, authentication token (legacy)
- `time` / `file` / `skill` / `compile` — `enabled` (boolean, default **true**). Set to `false` to disable that tool group
- `git` — `enabled` (boolean, default **false** — opt-in), `username` / `token` for remote git authentication. Top-level `gitUsername` / `gitToken` are kept for backward compatibility
- `maven` — `enabled` (boolean, default **false** — opt-in), `mavenHome` (optional Maven install dir), `localRepository` (optional local repo path), `maxOutputLines` (default 200), `defaultTimeoutMs` (default 300000)
- `fileBasePath` — 用户文件存储根目录，默认 `.local/file`
- `knowledgeBasePath` — 知识库文件存储根目录，默认 `.local/knowledge`

### Frontend

Static SPA at `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/`:
- `index.html` — entry point，含文件管理模态框（目录树视图）
- `app.js` — Vue-based chat UI (SSE streaming, sidebar, modals). **BFF + Cookie auth**: no localStorage token, browser auto-carries HttpOnly cookie
- `style.css` — styling
- Uses marked.js for Markdown rendering, eventsource-parser for SSE

**文件管理模态框**: 显示 `{fileBasePath}/{username}/` 的目录树，支持展开子目录，每个文件有预览/下载按钮

## Extension Points

To customize behavior, replace any `@Bean` by providing your own implementation:

```java
@Bean
@ConditionalOnMissingBean
public IChat customChat(...) { return new MyChat(...); }
```

To swap the vector store, simply add a Spring AI vector store starter dependency — `JVectorStore` won't be created due to `@ConditionalOnMissingBean(VectorStore.class)`.

`IGitTool` uses both `@ConditionalOnProperty` (`matchIfMissing=false`; set `git.enabled=true` to enable) and `@ConditionalOnMissingBean` — users can replace it with a custom implementation (e.g., CLI-based git) while keeping the feature on. Disabled by default; `ICompileAndDeployTool` is the supported end-to-end entry point.

`IMavenTool` uses `@ConditionalOnClass` (maven-invoker on classpath) + `@ConditionalOnProperty` (default off) + `@ConditionalOnMissingBean`. Disabled by default; same opt-in pattern.
