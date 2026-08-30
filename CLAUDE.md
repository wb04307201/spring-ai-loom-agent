# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Spring AI LoomAgent** — A Spring Boot auto-configuration library that provides an out-of-the-box chat UI with RAG knowledge base, MCP tool calling, **Skill library + Skill market**, and role-based access (RBAC) for Spring AI applications.

- **JDK**: 17+
- **Framework**: Spring Boot 3.x + Spring AI 1.x
- **Build**: Maven (multi-module)
- **Database**: H2 (default), with Flyway migrations

## Project Overview Images

`docs/project-overview-en.png` and `docs/project-overview-zh.png` (shown at the top of `README.md` / `README.zh-CN.md`) are generated from project source + `README.md` + this `CLAUDE.md` by the project skill at **`.claude/skills/project-overview-image/`** using DashScope `wan2.7-image` (`generate.py` PRIMARY_MODEL; requires `DASHSCOPE_WORKSPACE_ID` + `DASHSCOPE_API_KEY`). The infographic layout (single source of truth) lives in `generate.py`'s `EN_LAYOUT` / `ZH_LAYOUT` — edit those, not the PNGs. Regenerate via the skill trigger phrases ("更新项目概览图", "刷新 README 顶部的 overview 图", "生成 docs/project-overview-{en,zh}.png") whenever the architecture changes meaningfully — never hand-edit the PNGs.

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
| `ISkillStorage` | `DefaultSkillStorage` | Per-user `user_skill` storage (DB). Auto-syncs `role_skill` → `user_skill` (locked ROLE_GRANTED entries) on every list/get. no approval flow — submit is direct APPROVED; pull rejects overwriting USER_CREATED same-name; remove blocked when `market_skill_id` set; admin sees only own `user_skill` (no union view). Pairs with `ISkillMarketService` and `ISkillRoleAdmin`. |
| `IFile` | `DefaultFile` | File metadata storage (H2) — 仅用于知识空间文件、文件预览/下载桥接、聊天附件 |
| `IUpload` | `DefaultUpload` | File upload pipeline: 上传文件存储到 `fileBasePath/{username}/`，知识库文件存储到 `knowledgeBasePath/{username}/{knowledgeId}/`，重名自动追加序号 |
| `IUser` | `DefaultUser` | BFF + HttpOnly cookie session auth + auto-login |
| `IUserConversation` | `DefaultUserConversation` | User-to-conversation mapping |
| **登录页 (`login.html` / `login.css`)** | A+B 组合布局：主应用同款 60px 白顶栏（logo + 灵梭 + Spring AI LoomAgent）+ 居中品牌卡（圆形 logo + 灵梭 + English caption + 表单 + 织线纹理背景 + 底部 slogan）。所有视觉 token 复用主应用 `style.css`（`--primary #6366f1` / `--bg #f8fafc` 等），登录后跳 `index.html` 无感切换。 |
| `ITimeTool` | `DefaultTimeTool` | Time tools: get current time, convert between timezones |
| `ISkillTool` | `DefaultSkillTool` | Skill tools: `getSkill(skillName)` 获取技能完整 content（**技能全量列表由 `buildDynamicSystemPrompt` 注入到 system prompt【技能】段，不另提供 list 工具** — 与 `IKnowledgeTool` 删除 `listKnowledgeBases` 对称；详见 `DefaultSkillTool` 顶部 javadoc）；`createOrUpdateSkill(name, description, content)` 创建/更新自建 skill（user 通过 / picker 精准选 skill 时，`ChatRequestRecord.selectedSkillName` 强指令注入到 system prompt，绕过 LLM 工具选择偏差） |
| `IKnowledgeTool` | `DefaultKnowledgeTool` | Knowledge tools: `searchKnowledge(knowledgeId, query, topK?)` 在指定知识库中向量检索（**已删除 `listKnowledgeBases` 工具**：已启用的 KB 列表在 system prompt【知识库】段自动展示，重复调用冗余）。`description` 字段语义：LLM 用的内容摘要（不是用户标签），未来上传文件后由 LLM 自动生成。Tool-based RAG 替代了旧的 RetrievalAugmentationAdvisor |
| `IFileTool` | `DefaultFileTool` | 16 File tools: 基于路径的读写/编辑/搜索/目录浏览（readTextFile, readMediaFile, readMultipleFiles, writeFile, editFile, createDirectory, moveFile, searchFiles, listAllowedDirectories, listDirectory, listDirectoryWithSizes, directoryTree, getFileInfo, downloadFileUrl, viewFileUrl, deleteFileOrDirectory），预览/下载自动桥接 fileId，删除支持递归 + 显式确认 + 清理临时 file_info 记录 |
| `IGitTool` | `DefaultGitTool` | 28 Git tools: init, clone, status, add, commit, diff, log, branch, checkout, pull, push, fetch, merge, rebase, reset, stash, tag, remote, blame, show, reflog, clean, cherry-pick, worktree, set-working-dir, clear-working-dir, changelog-analyze, wrapup-instructions（**默认 disabled** — `git.enabled=false`；需要单点 git 操作时设 `true`），不依赖 IFile |
| `IMavenTool` | `DefaultMavenTool` | 6 Maven tools: mavenExecute (generic), mavenBuild (compile), mavenPackage (package), mavenTest (run tests), mavenDependencyTree (dep tree), mavenValidate (validate) — based on maven-invoker, no shell needed（**默认 disabled** — `maven.enabled=false`；编译/打包请走 `ICompileAndDeployTool`，需要单点 mvn 命令时设 `true`） |
| `ICompileAndDeployTool` | `DefaultCompileAndDeployTool` | 端到端部署：git clone → 按 buildTool 打包（maven / npm / npm-frontend / pip）→ Docker 镜像构建 → 容器启动 → 健康检查（**默认 enabled**）。支持 Spring Boot / Node（前后端） / Python 等多栈项目。单次 LLM tool call 完成整个部署流水线，避免 LLM 拆解成多步时出错。 |
| `IDocumentRead` | `DefaultDocumentRead` | Document reading with LLM metadata enrichment |
| `IFileDocument` | `DefaultFileDocument` | File-to-document ID mapping |
| `ISubTaskExecutor` | `DefaultSubTaskExecutor` | Runs a sub-task synchronously on the dedicated `loomSubTaskExecutor` pool via `ChatClient.call`; tools filtered to exclude self-tools (no `ISubTaskTool`/`IScheduleTool`) to prevent recursion. Sub-task memory namespaced `{conversationId}--sub--{subTaskId}` |
| `ISubTaskTool` | `DefaultSubTaskTool` | LLM-callable `start_sub_task(prompt, systemContext)` + `list_sub_tasks` + `cancel_sub_task(subTaskId)` + `get_sub_task_history(limit)` — 委派/查询/取消/历史子任务，全部按 `(username, conversationId)` 严格隔离，防跨会话越权。默认 enabled (`subtask.enabled=true`) |
| `IScheduleTool` | `DefaultScheduleTool` | LLM-callable create/cancel/list/history 定时任务，通过 flex-schedule。任务名命名空间 `loom-sched-{user}-{conv}-{name}`，触发时以子任务方式运行。loom-agent 自管 H2 持久化 (`loom_scheduled_task`，增量，前身 Flyway V13)；`ScheduleRestoreListener` 在 `ApplicationReadyEvent` 时按原 `createdAt` 重新装载，超 72h 的过期行自动清理。间隔/存活上限见 `flex.schedule.limits`。默认 enabled (`schedule.enabled=true`) |

### Auto-Configuration (`LoomAgentConfiguration`)

Organized into 7 nested static `@Configuration` classes:

| Inner Class | Responsibility |
|-------------|----------------|
| `InfrastructureConfiguration` | Properties binding, Flyway, ChatMemory, BeanFactoryPostProcessors |
| `ChatConfiguration` | ChatClient, IChat, SseController |
| `RagConfiguration` | VectorStore (JVector fallback), DocumentRead, IUpload (all conditional on VectorStore) |
| `McpConfiguration` | SyncMcp / ASyncMcp |
| `ToolConfiguration` | ITimeTool, ISkillTool, IKnowledgeTool, IFileTool, IGitTool, IMavenTool, ICompileAndDeployTool — **9 个 I*Tool bean 总是创建**(M3 起废弃 yml enabled 开关;唯一控制是 RBAC)。`git/maven` 不再默认 opt-in,但 IMavenTool 需要 maven-invoker 在 classpath,IGitTool 需要 Eclipse JGit(已在默认依赖里)。**所有 capability 启停由 `role_tool` 表控制**:admin 在 `/admin/capabilities` 给 role 授权后,只有被分配该 role 的用户才看得到工具。|
| `StorageConfiguration` | IUser, IUserConversation, ISkillStorage, IFile, IFileDocument, IKnowledge |
| `WebConfiguration` | AuthenticationFilter, 14 RouterFunctions + `SseController` |
| `CapabilityConfiguration` | `CapabilityService` (统一 list 本地 + MCP capability) + `IRoleService` 的 tool/mcp/skill/knowledge 授权方法 |

### Capability 统一模型(M1-M7 重构)

新增 4 个组件来替代旧的"9 个 I*Tool + 5 个 MCP server 各管各的"混乱:

1. **`@ToolGroup` 注解**(`cn.wubo.spring.ai.loom.agent.tool.ToolGroup`)
   放在 9 个 `I*Tool` 接口上,声明所属 capability group:
   ```java
   @ToolGroup(value = "file", description = "readTextFile / writeFile / listDirectory ...")
   public interface IFileTool extends IEmbedTool { ... }
   ```
   `value` 是 group 名(如 "file");`description` 渲染到 admin UI。`@Target=TYPE`,放接口不放实现(实现可替换不丢 group 身份)。

2. **`role_tool` 表**(`V2.1__role_tool.sql` 新增,与 `role_mcp` 镜像):
   ```sql
   CREATE TABLE role_tool (
     role_code VARCHAR(32) NOT NULL,
     group_name VARCHAR(64) NOT NULL,    -- 'tool_' + @ToolGroup value
     sort_order INT NOT NULL DEFAULT 0,
     default_enabled BOOLEAN NOT NULL DEFAULT TRUE,
     PRIMARY KEY (role_code, group_name)
   );
   ALTER TABLE role_tool ADD CONSTRAINT fk_role_tool_role
     FOREIGN KEY (role_code) REFERENCES role(code) ON DELETE CASCADE;
   ```
   **role 删除时自动 cascade 清 role_tool**(B.2.1 修复 — V2.2 migration 加了 FK,应用层 `DefaultRoleService.delete` 也显式 DELETE 兜底)。

3. **`CapabilityService` 服务**(`cn.wubo.spring.ai.loom.agent.capability`)
   - `list(username)` — 当前用户可见 capability(含 effectiveEnabled,从 `IRoleService.getVisibleToolsForUser` / `getVisibleMcpsForUser` 算)
   - `visibleToolGroupsFor(username)` — RBAC 视角的本地 tool group 授权集
   - `allowedCapabilityIdsFor(username, userPick)` — 角色授权 ∩ 前端勾选(空 pick 退化为角色全集)
   - `toLocalCapability(bean)` — 反射 `@Tool` 注解方法(包括接口上的继承注解,使用 `MergedAnnotations` 走 `TYPE_HIERARCHY`)
   - **`MCP name 必须原样保留**(`role_mcp.mcp_name = McpSyncClient.getClientInfo().name()`,不做 REPLACE / 不加 prefix)

4. **API 端点**:
   - `GET  /spring/ai/loom/api/capabilities` — 聊天面板用(返回 9 LOCAL + N MCP,带 effectiveEnabled)
   - `GET  /spring/ai/loom/admin/capabilities` — admin 角色授权用(只 LOCAL,无 effectiveEnabled)
   - `GET  /admin/roles/{code}/tools` + `PUT` — 角色授权 tool 增删
   - `GET  /admin/roles/{code}/mcps` + `PUT` — 角色授权 MCP 增删
   - `GET  /admin/roles/{code}/skills` + `PUT` — 角色授权技能(已有,镜像 tools 模式)
   - `GET  /admin/roles/{code}/knowledge` + `PUT` — 角色授权知识库(已有,镜像 tools 模式)

5. **`ChatRequestRecord` 加 `enabledToolGroups` 字段**:
   ```java
   public record ChatRequestRecord(String message, String conversationId,
                                   List<String> mcps,
                                   List<String> enabledKnowledgeIds,
                                   List<String> fileIds,
                                   String selectedSkillName,
                                   List<String> enabledToolGroups) { ... }
   ```
   - `null` / 空 → 服务端 fallback 到角色授权全集(全勾)
   - 非空 → `role_auth ∩ user_pick` 交集
   - 包含 5-arg 兼容构造器(旧调用方传 null,等同"全部启用")

6. **`DefaultChat` filter 改造**:
   ```java
   Set<String> visibleToolGroups = capabilityService.visibleToolGroupsFor(username);
   List<IEmbedTool> filtered = embedTools.stream()
       .filter(t -> t instanceof ToolGroup-anotated iface
                 && visibleToolGroups.contains("tool_" + @ToolGroup value))
       .toList();
   toolCallbacks(toolCallbacksFrom(filtered));
   ```
   之前是 `embedTools` 全部 → 改成 role ∩ pick 过滤后 → LLM 看到的 tool callback 列表被严格按 RBAC 筛选

7. **strict RBAC(无 admin bypass)**:
   - `IRoleService.getVisibleMcpsForUser` 不再走 `if ("ADMIN".equals(type)) return ALL`
   - `setUserRolesOrSkipAdmin` 删掉 admin 短路(普通 user / admin 都走同一路径)
   - **新装 admin 没有任何 role → 0 capability → 必须进 admin 控制台手动授权**

8. **admin UI 整合**(M6 + M7):
   - 聊天面板 `🔧 MCP服务` 按钮 → `🔧 工具与服务` 按钮,带类型徽章("本地" / "MCP")
   - admin 角色管理页 "授权本地工具组" section,真实从 `/admin/capabilities` 拉动态列表(替换之前的硬编码 `KNOWN_TOOL_GROUPS` 9 行)
   - `app.js` 所有 fetch 显式 `Content-Type: application/json; charset=UTF-8`(解决 GBK 解析错)


- `IMavenTool` is **disabled by default** (`maven.enabled=false`); same opt-in pattern. Compile/package is handled by `ICompileAndDeployTool`.
- `ICompileAndDeployTool` is **enabled by default**; the supported entry point for `git clone → buildTool build (maven/npm/pip) → docker build → docker run → health check`. Supports `maven` / `npm` (Node 后端) / `npm-frontend` (Node 前端 → nginx) / `pip` (Python) — selected by `buildTool` param or auto-detected from marker files (`pom.xml` / `package.json` / `requirements.txt` / `pyproject.toml`).
- REST endpoints under `/spring/ai/loom/*` (RouterFunctions + one `@RestController` for SSE)
- `AuthenticationFilter` on `/*` (matches all), with `AntPathMatcher` filtering via `auth.pathPatterns` and `auth.excludePathPatterns`

### Data Layer

- **Schema** (库自带的 schema + admin seed)：
 - 库 `src/main/resources/db/migration/V1.0__init.sql` — 基础建表（knowledge / file / user / conversation / token / skill / role / mcp_server / mcp_tool / market_skill / user_skill / role_skill / role_mcp）+ 默认 admin 账号。**保持稳定,不再改动**
 - 库 `src/main/resources/db/migration/V2.0__subtask_and_schedule.sql` — **增量**（把历史 V12~V17 合并成一个文件）：新增 `loom_scheduled_task` / `loom_schedule_execution` / `loom_subtask_history`，给 `user_conversation` 追加侧边栏三列 title/created_at/updated_at，`SPRING_AI_CHAT_MEMORY.conversation_id` 加宽到 255（子任务命名空间 id 需要）。旧 `flex_scheduled_task`（V12）已删——flex-schedule 1.x 纯内存、无 JdbcTaskRepository、零引用（保留一条防御性 `DROP IF EXISTS` 清理残留）
 - 业务 `spring-ai-loom-agent-test/src/main/resources/db/migration/V1.1__init_app_data.sql` — 业务 demo 数据：12 个 mcp_server + 14 个 mcp_tool + 6 个 system skill
 - Flyway 用版本号在同一实例里按序执行：``(基础) → ``(业务数据) → ``(子任务/定时增量)
 - **升级注意**：全新库按上述顺序干净跑通。已跑过旧 V12~V17 的历史库无法就地迁移到 （旧版本号已从磁盘移除）——需全新库或 `flyway baseline`；本地开发 `rm -rf ~/.loom/datasource` 即可重跑
- **Chat memory**: Spring AI `JdbcChatMemoryRepository` (JDBC-backed, auto-initialized)
- **Flyway table**: `flyway_schema_history`（Spring Boot 默认，库不覆盖）

### File System Storage

All user-local state lives under `~/.loom/` (single root, single `rm -rf` to wipe):

| 目录 | 内容 | 默认值 |
|---|---|---|
| `~/.loom/file/{username}/` | 用户上传的文件（聊天附件、文件管理 UI 列出）| `fileBasePath` 默认 `${user.home}/.loom/file` |
| `~/.loom/knowledge/{username}/{knowledgeId}/` | 知识库文档原文件 | `knowledgeBasePath` 默认 `${user.home}/.loom/knowledge` |
| `~/.loom/datasource/` | H2 文件数据库 `db.mv.db` | `datasourceDir` 默认 `${user.home}/.loom/datasource`（yml 通过 `spring.datasource.url` 拼装）|
| `~/.loom/jvector-index/` | HNSW 向量索引 | `jvector.indexPath` 默认 `${user.home}/.loom/jvector-index` |
| `~/.loom/compile-deploy-workspaces/{username}/` | 编译部署工具临时 workspace（带 username/timestamp 前缀；成功默认清理）| `DefaultCompileAndDeployTool.getCompileDeployWorkspaceDir` |

**重名处理**: 同名文件自动追加序号，如 `file.txt` → `file(1).txt` → `file(2).txt`
**预览/下载桥接**: 路径操作的预览/下载通过 `IFile.getByExactPath` 查询，不存在时自动插入 `usage='temp'` 记录获取 fileId

> 历史注意：早期版本把以上全都放在 cwd-relative `.local/` 下，导致 `mvn spring-boot:run -pl test-module` 时路径漂到 test 模块下、UI 列出项目源码而不是用户文件。Fix A/B/C 把路径统一到 `~/.loom/` 之后这种事不再发生。

### Configuration Properties

All under `spring.ai.loom.agent`:
- `rag` — similarity threshold, top-k, prompt templates
- `jvector` — index path (默认 `${user.home}/.loom/jvector-index`)、HNSW params (m, efConstruction, efSearch)
- `mcps` — list of MCP service configs (name, title, description, tools, default-selected)
- ~~`skills`~~ — **no longer read from yml**. Skill data lives in the database now (tables `market_skill` / `user_skill` / `role_skill`); 6 system skills are seeded by the init migration. Manage via the admin console → **Skill Market** page.
- `auth` — `enabled` (boolean, default true), `pathPatterns` (Ant-style path list), `excludePathPatterns`, `adminPathPatterns` (gates `/admin/**` to admin users), `cookie` (name, path, domain, secure, sameSite, maxAge)
- `init` — **Note**: The actual runtime gate for `ChatClient` creation is `spring.ai.chat.ui.init` (not `spring.ai.loom.agent.init`). Set `spring.ai.chat.ui.init=false` to prevent ChatClient auto-creation. Default: `true`
- `user` — default username, nickname, authentication token (legacy)
- `time` / `file` / `skill` / `knowledge` / `compile` — `enabled` (boolean, default **true**). Set to `false` to disable that tool group
- `git` — `enabled` (boolean, default **false** — opt-in), `username` / `token` for remote git authentication. Top-level `gitUsername` / `gitToken` are kept for backward compatibility
- `maven` — `enabled` (boolean, default **false** — opt-in), `mavenHome` (optional Maven install dir), `localRepository` (optional local repo path), `maxOutputLines` (default 200), `defaultTimeoutMs` (default 300000)
- `subtask` — `enabled` (boolean, default **true**), `max-concurrent` (default 4), `max-history` (default 200)
- `schedule` — `enabled` (boolean, default **true**); trigger constraints come from `flex.schedule.limits.{min-interval,max-lifetime,mode}` (test app 默认 10m / 72h / strict). Scheduled tasks persist to loom-agent-owned H2 table `loom_scheduled_task` (增量，前身 Flyway `V13`); restore listener rehydrates on ApplicationReadyEvent preserving original `createdAt` so `max-lifetime` accumulates across restarts
- `fileBasePath` — 用户文件存储根目录，默认 `${user.home}/.loom/file`（绝对路径，不再 cwd-relative）
- `knowledgeBasePath` — 知识库文件存储根目录，默认 `${user.home}/.loom/knowledge`
- `datasourceDir` — H2 文件存储目录，默认 `${user.home}/.loom/datasource`（在 `application.yml` 的 `spring.datasource.url` 里通过 `${user.home}/.loom/datasource/db` 拼接）

### Frontend

Static SPA at `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/`:
- `index.html` — entry point，含文件管理模态框（目录树视图）
- `app.js` — Vue-based chat UI (SSE streaming, sidebar, modals). **BFF + Cookie auth**: no localStorage token, browser auto-carries HttpOnly cookie
- `style.css` — styling
- Uses marked.js for Markdown rendering (sanitized by a tiny inline `markdown-renderer.js` allowlist), and a minimal inline SSE parser in `app.js`

**文件管理模态框**: 显示 `{fileBasePath}/{username}/`（例如 `C:\Users\<you>\.loom\file\<username>\`）的目录树，支持展开子目录，每个文件有预览/下载按钮。不显示 `~/.loom/jvector-index/`、`~/.loom/datasource/`、`~/.loom/compile-deploy-workspaces/` 这些工具/系统目录。

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
