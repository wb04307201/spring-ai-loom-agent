# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Spring AI LoomAgent** — A Spring Boot auto-configuration library that provides an out-of-the-box chat UI with RAG knowledge base, MCP tool calling, **Skill library + Skill market**, and role-based access (RBAC) for Spring AI applications.

- **JDK**: 17+
- **Framework**: Spring Boot 3.x + Spring AI 1.x
- **Build**: Maven (multi-module)
- **Database**: H2 (default), with Flyway migrations
- **No CHANGELOG**: this project does not maintain a `CHANGELOG.md` — read `git log` for the change history

## Project Overview Images

`docs/project-overview-en.png` and `docs/project-overview-zh.png` (shown at the top of `README.md` / `README.zh-CN.md`) are generated from project source + `README.md` + this `CLAUDE.md` by the project skill at **`.claude/skills/project-overview-image/`** using DashScope `wan2.7-image` (`generate.py` PRIMARY_MODEL; requires `DASHSCOPE_WORKSPACE_ID` + `DASHSCOPE_API_KEY`). The infographic layout (single source of truth) lives in `generate.py`'s `EN_LAYOUT` / `ZH_LAYOUT` — edit those, not the PNGs. Regenerate via the skill trigger phrases ("更新项目概览图", "刷新 README 顶部的 overview 图", "生成 docs/project-overview-{en,zh}.png") whenever the architecture changes meaningfully — never hand-edit the PNGs.

## Module Structure

| Module | Purpose |
|--------|---------|
| `spring-ai-loom-agent` | Core library — chat, knowledge base, file, MCP, skill (market + role auth), RBAC (user/role/mcp), user interfaces + default implementations, H2-backed JVector vector store (loom_vector_store), H2 schema, static frontend resources |
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

## M0/M1/M2 Market Upgrade (v1.2.0)

The v1.2.0 cycle (commits `26834b0..dceaddc`, M0 + M1 + M2 milestones) introduced a unified Skill + Knowledge Base market: full CRUD via admin UI, PENDING/APPROVED/REJECTED approval flow, `is_official / featured_rank / category` columns on both `market_skill` and `loom_market_knowledge`, `BatchedCounterService` for stat tracking, 5-star reviews with an `edit_count` gate, per-row announcements, and a KB tag system with filter UI. See [`docs/superpowers/specs/2026-09-04-skill-knowledge-market-design.md`](docs/superpowers/specs/2026-09-04-skill-knowledge-market-design.md) for the design.

### M3+ Technical Debt (named inventory)

v1.2.0 left a tech-debt inventory (named categories `A12 / B1 / B2 / B3 / B4 / B8` + §10 rulings; not a numbered 24-row catalogue) — spec at [`docs/superpowers/specs/2026-09-05-market-tech-debt-cleanup.md`](docs/superpowers/specs/2026-09-05-market-tech-debt-cleanup.md) and task breakdown at [`docs/superpowers/plans/2026-09-05-market-tech-debt-cleanup.md`](docs/superpowers/plans/2026-09-05-market-tech-debt-cleanup.md). Cleanup is staged across 7 phases (T0–T6 + verification, ~8–12 weeks); each task lands in its own commit (`feat:` / `fix:` / `refactor:` / `docs:` / `test:` prefix) with an IT regression gate at every phase boundary. Task T1.2 / T1.3 share a single commit per plan line 214.

| Phase | Scope |
|-------|-------|
| T0 | Doc sync (`CLAUDE.md` for v1.2.0); auto-enable `@EnableScheduling` + javadoc `BatchedCounterService` |
| T1 | **B1 真修 (core)** — schema `market_id BIGINT → VARCHAR(36)`; `IMarketContentAdminService<K, M, U, R>` 参数化; `RouterIdParser<K>` 抽象; drop KB String twins + `findOneByRawId` + NFE catches |
| T2 | **N+1 fix** — list DTO embed announcement / tags; drop frontend `listWithAnnouncements` / `listWithTags` helpers |
| T3 | **Architecture cleanup** — v1 service shim policy (1 minor version); `buildAdminMarketRoutes` helper extraction |
| T4 | **Observability / security** — Micrometer counters + timers; rate-limit on `/pull` / `/access` / `/reviews`; i18n key extraction |
| T5 | **Portability / spec drift** — Flyway source-organization split (policy A: keep V1.0 single fresh-init file, segment by SQL comment); spec drift A3/A10 422→403; portable upsert |
| T6 | **Test cleanup** — extract `LoomAgentTestUtil.safeRoute`; cover async batched flush path |

**Status (2026-09-06): complete.** All 7 phases landed (commits `85f8d66..7f43929`); T5.3 portable upsert deferred per `ADR-T05.3` (reactivates only on PG/MySQL adoption). The T7.1 gate's 3 residuals were closed 2026-09-06 (review chain `<K>` `699c1a1`, announcement String-native + a13 null-safe + CAST join `c0df007`, KB `marketKind()` fix + tags embed `cb8178b`, fix wave `376454d`) — `ADR-T07.1` now records **Pass** (AT1 + AT2 included). **Verification gotcha:** default `mvn test -pl spring-ai-loom-agent-test` runs 380 tests but **no `*IT` classes** (no failsafe plugin); the IT gate (80 tests) requires explicit `-Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false` from wiped `~/.loom/datasource` + `target/test-ds` + `target/surefire-reports`. Post-M3+ follow-ups: spec § Follow-ups FU-1..FU-5.

## Architecture

### Core Interfaces (in `spring-ai-loom-agent`)

All components follow an **interface + default implementation** pattern. Every bean is registered with `@ConditionalOnMissingBean`, allowing consumers to replace any piece:

| Interface | Default Impl | Responsibility |
|-----------|-------------|----------------|
| `IChat` | `DefaultChat` | Chat streaming (SSE), MCP tool orchestration, RAG augmentation. `stream(record, username, request)` — username injected by filter |
| `IKnowledge` | `DefaultKnowledge` | Knowledge base CRUD (stored in H2) |
| `IMcp` | `SyncMcp` / `ASyncMcp` | MCP client wrapper (sync or async), tool discovery & invocation |
| `ISkillStorage` | `DefaultSkillStorage` | Per-user `user_skill` storage (DB). Auto-syncs `role_skill` → `user_skill` (locked ROLE_GRANTED entries) on every list/get. Approval flow (M4/#4): market submit → PENDING, admin approve/reject (reject comment required); REJECTED re-submit archives old row to `market_skill_archive` / `loom_market_knowledge_archive` (id-preserving) + new PENDING row; same-name PENDING/APPROVED re-submit = in-place content update, status untouched; admin create (`createApproved`) → APPROVED immediately + created_by_kind='ADMIN'; pull requires APPROVED else 403. pull rejects overwriting USER_CREATED same-name; remove blocked when `market_skill_id` set; admin sees only own `user_skill` (no union view). Pairs with `ISkillMarketService` and `ISkillRoleAdmin`. V1.0 尾部种子 2 条官方技能 market_skill(STAR-IJ 讲清一件事 / 靶心人公式 讲好一个故事,author=system,APPROVED,is_official=TRUE,category=表达沟通)。 |
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
| `ISubTaskExecutor` | `DefaultSubTaskExecutor` | Runs a sub-task synchronously on the dedicated `loomSubTaskExecutor` pool via `ChatClient.call`; tools filtered to exclude self-tools (no `ISubTaskTool`/`IScheduleTool`/`IAskUserTool` — 子任务不能向用户提问,疑问写进执行结果由主任务决定) to prevent recursion. Sub-task memory namespaced `{conversationId}--sub--{subTaskId}` |
| `ISubTaskTool` | `DefaultSubTaskTool` | LLM-callable `start_sub_task(prompt, systemContext)` + `list_sub_tasks` + `cancel_sub_task(subTaskId)` + `get_sub_task_history(limit)` — 委派/查询/取消/历史子任务，全部按 `(username, conversationId)` 严格隔离，防跨会话越权。默认 enabled (`subtask.enabled=true`) |
| `IScheduleTool` | `DefaultScheduleTool` | LLM-callable create/cancel/list/history 定时任务，通过 flex-schedule。任务名命名空间 `loom-sched-{user}-{conv}-{name}`，触发时以子任务方式运行。loom-agent 自管 H2 持久化 (`loom_scheduled_task`，增量，前身 Flyway V13)；`ScheduleRestoreListener` 在 `ApplicationReadyEvent` 时按原 `createdAt` 重新装载，超 72h 的过期行自动清理。间隔/存活上限见 `flex.schedule.limits`。默认 enabled (`schedule.enabled=true`) |
| `IAskUserTool` | `DefaultAskUserTool` | LLM-callable `askUser(question, header, background, optionsJson, multiSelect, allowCustomInput)` — 聊天流内嵌选择卡片向当前用户提问,工具方法阻塞等待作答(默认 `askuser.timeoutSeconds=300`),答案以 tool_result 回同一条流。超时/stop 返回"用户未作答"文本保 ChatMemory ON_COMPLETE。默认 enabled(universal) |

### Auto-Configuration (`LoomAgentConfiguration`)

Organized into 7 nested static `@Configuration` classes:

| Inner Class | Responsibility |
|-------------|----------------|
| `InfrastructureConfiguration` | Properties binding, Flyway, ChatMemory, BeanFactoryPostProcessors |
| `ChatConfiguration` | ChatClient, IChat, SseController |
| `RagConfiguration` | VectorStore (H2-backed JVector fallback), DocumentRead, IUpload (all conditional on VectorStore) |
| `McpConfiguration` | SyncMcp / ASyncMcp |
| `ToolConfiguration` | ITimeTool, ISkillTool, IKnowledgeTool, IFileTool, IGitTool, IMavenTool, ICompileAndDeployTool — **10 个 I*Tool bean 总是创建**(M3 起废弃 yml enabled 开关;M6 引入 `@ToolGroup(defaultGranted=true)` 后,部分工具标记为"平台默认能力",对所有登录用户可见 — 见下方 Universal 工具表)。`git/maven` 不再默认 opt-in,但 IMavenTool 需要 maven-invoker 在 classpath,IGitTool 需要 Eclipse JGit(已在默认依赖里)。**RBAC 工具启停由 `role_tool` 表控制**;admin 在 `/admin/roles/{code}/tools` 给 role 授权后,只有被分配该 role 的用户才看得到工具。|
| `StorageConfiguration` | IUser, IUserConversation, ISkillStorage, IFile, IFileDocument, IKnowledge |
| `WebConfiguration` | AuthenticationFilter, 14 RouterFunctions + `SseController` |
| `CapabilityConfiguration` | `CapabilityService` (统一 list 本地 + MCP capability) + `IRoleService` 的 tool/mcp/skill/knowledge 授权方法 |

### Capability 统一模型(M1-M7 重构)

新增 4 个组件来替代旧的"9 个 I*Tool + 5 个 MCP server 各管各的"混乱:

1. **`@ToolGroup` 注解**(`cn.wubo.spring.ai.loom.agent.tool.ToolGroup`)
   放在 10 个 `I*Tool` 接口上,声明所属 capability group:
   ```java
   @ToolGroup(value = "file", description = "readTextFile / writeFile / listDirectory ...")
   public interface IFileTool extends IEmbedTool { ... }
   ```
   `value` 是 group 名(如 "file");`description` 渲染到 admin UI。`@Target=TYPE`,放接口不放实现(实现可替换不丢 group 身份)。

2. **`role_tool` 表**(已合并入 `V1.0__init.sql`,与 `role_mcp` 镜像):
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
   **role 删除时自动 cascade 清 role_tool**(B.2.1 修复 — `V1.0` 中加 FK,应用层 `DefaultRoleService.delete` 也显式 DELETE 兜底;DEFECT-Q3-1 修复后 delete() 清单覆盖全部 5 张子表:role_tool/role_mcp/user_role/role_skill/loom_role_knowledge)。

3. **`CapabilityService` 服务**(`cn.wubo.spring.ai.loom.agent.capability`)
   - `list(username)` — 当前用户可见 capability(含 effectiveEnabled,从 `IRoleService.getVisibleToolsForUser` / `getVisibleMcpsForUser` 算)
   - `visibleToolGroupsFor(username)` — RBAC 视角的本地 tool group 授权集
   - `allowedCapabilityIdsFor(username, userPick)` — 角色授权 ∩ 前端勾选(空 pick 退化为角色全集)
   - `toLocalCapability(bean)` — 反射 `@Tool` 注解方法(包括接口上的继承注解,使用 `MergedAnnotations` 走 `TYPE_HIERARCHY`)
   - **`MCP name 必须原样保留**(`role_mcp.mcp_name = McpSyncClient.getClientInfo().name()`,不做 REPLACE / 不加 prefix)

4. **API 端点**:
   - `GET  /spring/ai/loom/api/capabilities` — 聊天面板用(返回 10 LOCAL + N MCP,带 effectiveEnabled)
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
   - **admin 也可在控制台被分配角色**(2026-09-08 起):`console.js` 的 ADMIN early-return 已删,分配角色弹窗对 ADMIN 用户同样打开(附 strict RBAC 提示句);`IRoleService.setUserRolesOrSkipAdmin` 已标 `@Deprecated`(其内部 admin 短路已移除)

8. **admin UI 整合**(M6 + M7):
   - 聊天面板 `🔧 MCP服务` 按钮 → `🔧 工具` 按钮,带类型徽章("本地" / "MCP")
   - admin 角色管理页 "授权本地工具组" section,真实从 `/admin/capabilities` 拉动态列表(替换之前的硬编码 `KNOWN_TOOL_GROUPS` 9 行)
   - admin 日志页 `stats.html`(stats.js)除月度 Token 用量外,含"提问卡片"(askUser)日志区块(时间/用户/问题/答案或状态/等待时长/会话),数据源 `GET /spring/ai/loom/admin/ask-logs` —— `loom_tool_call_log` 表(tool_name='askUser')的只读视图,adminPathPatterns 门禁
   - `app.js` 所有 fetch 显式 `Content-Type: application/json; charset=UTF-8`(解决 GBK 解析错)

#### Universal 工具(M6:平台默认能力,不受 RBAC 控制)

`@ToolGroup` 注解加 `boolean defaultGranted() default false` 字段。设为 `true` 的工具对**所有登录用户可见**,与 `role_tool` 表完全解耦:

| Universal 工具 | group | 理由 |
|----------------|-------|------|
| `IScheduleTool` | `tool_schedule` | per-user 命名空间,触发也走子模型 user 身份,无越权风险 |
| `ISubTaskTool` | `tool_subtask` | 子任务 tool 调用继承 user 角色,无越权风险 |
| `IKnowledgeTool` | `tool_knowledge` | KB 列表本身受 `role_knowledge` 控制,工具无授权必要 |
| `ITimeTool` | `tool_time` | 只读返回时间,无副作用 |
| `ISkillTool` | `tool_skill` | 允许用户自由创建/编辑自建 skill |
| `IFileTool` | `tool_file` | 默认放开本地文件访问(⚠️ 含 `deleteFileOrDirectory` 递归删除,LLM 端需谨慎 prompt 约束) |
| `IAskUserTool` | `tool_askUser` | 仅向当前流内的本人提问,答案回同一流,无越权风险;子任务/定时任务 schema 级排除 |

**RBAC 工具(走 `role_tool` 表)**:`IGitTool` / `IMavenTool` / `ICompileAndDeployTool` —— 涉及 git push / 任意 mvn 构建 / Docker 容器运行,必须显式授权。

**实现机制**:
- 元数据单一源 = `@ToolGroup(defaultGranted=true)` 注解,**DB 端无 `loom_universal_tool` 表**
- `CapabilityService.universalToolGroups()` 反射所有 `@ToolGroup` 注解,返回默认授予的 group_name 集合
- `visibleToolGroupsFor(username) = role_granted ∪ universal` —— 新装 admin / 普通用户也能至少调用 7 个 universal 工具
- `allowedCapabilityIdsFor(...)` 在 `role ∩ user_pick` 之外再 `addAll(universalGroups)`,user_pick 不能拒绝 universal
- 聊天面板"工具"弹窗**完全不展示** universal 工具的 checkbox(无感调用)
- admin 角色授权页"已授权本地工具"列表**完全不展示** universal 工具入口(没有"移除"按钮,只有 RBAC 工具可操作)
- Flyway `V2.4__cleanup_universal_tools_from_role_tool.sql` 一次性清理 `role_tool` 表里 6 个 universal 工具的历史授权记录(防止旧库"残留可见但 UI 无法移除"的歧义)
  — **历史:已合并入 V1.0 的末尾 DELETE 段,新装环境天然干净**


- `IMavenTool` is **disabled by default** (`maven.enabled=false`); same opt-in pattern. Compile/package is handled by `ICompileAndDeployTool`.
- `ICompileAndDeployTool` is **enabled by default**; the supported entry point for `git clone → buildTool build (maven/npm/pip) → docker build → docker run → health check`. Supports `maven` / `npm` (Node 后端) / `npm-frontend` (Node 前端 → nginx) / `pip` (Python) — selected by `buildTool` param or auto-detected from marker files (`pom.xml` / `package.json` / `requirements.txt` / `pyproject.toml`).
- REST endpoints under `/spring/ai/loom/*` (RouterFunctions + one `@RestController` for SSE)
- `AuthenticationFilter` on `/*` (matches all), with `AntPathMatcher` filtering via `auth.pathPatterns` and `auth.excludePathPatterns`

### Data Layer

- **Schema** (单一 V1.0 一站式 init,**项目只跑全新库**;任何已有 V1/V2 历史部署必须 `flyway baseline` 或 `rm -rf ~/.loom/datasource` 重跑):
 - 库 `src/main/resources/db/migration/V1.0__init.sql` — **完整 schema 一站式 init**(knowledge / file / user / conversation / token / skill / role / mcp_server / mcp_tool / market_skill / user_skill / role_skill / role_mcp / role_tool / market_skill_archive / loom_market_knowledge_archive / loom_vector_store)+ RBAC **5 张子表 CASCADE FK**(user_role.role_code / role_mcp.role_code / role_tool.role_code / role_skill.role_code / loom_role_knowledge.role_code → role.code,加 user_role.username → user_info.username;后两张为 DEFECT-Q3-1 修复补加,2026-09-09)+ M6 universal tools DELETE-from-role_tool 一并落地 + 默认 admin 账号 + 尾部种子:2 条官方 market_skill(STAR-IJ / 靶心人公式,author=system,APPROVED,is_official=TRUE,category=表达沟通)+ **默认基础角色 base**(is_system=FALSE,授权 4 常用 MCP `spring-ai-mcp-client - {sequential-thinking,bing-search,memory,@tokenizin-agency/mcp-npx-fetch}` default_enabled + 2 官方技能 default_loaded,role_skill 按名子查询 market_skill.id;并 user_role 授予 wb04307201 —— 开箱即用)。`*_archive` 两表(M4/#4 审批流)存 REJECTED 重投时归档的旧行(保留原 id + 拒绝评论/审核人/时间)
 - **角色删除级联(DEFECT-Q3-1 修复)**:`DefaultRoleService.delete` 显式清 user_role/role_mcp/role_tool/**role_skill/loom_role_knowledge**(后两张原被 B.2.1 遗漏 → 删角色后 dangling 行在同 code 重建时经 role_skill→user_skill 自动同步"复活"陈旧授权;现 delete() 补 DELETE + V1.0 补 FK CASCADE 双保险)。回归锁:`DefaultRoleServiceDeleteCascadeTest`(mock 清理清单)+ `RoleDeleteCascadeIT`(真 DB 复活场景)
 - **保持稳定,不再拆分增量**:所有 schema 演进(loom_scheduled_task / loom_schedule_execution / loom_subtask_history / user_conversation 三列 / SPRING_AI_CHAT_MEMORY.conversation_id 加宽 / loom_market_knowledge / loom_user_knowledge / loom_role_knowledge / loom_file_content / loom_tool_call_log / loom_chat_usage / loom_chat_reasoning / tool_call_log + chat_token_usage 替换等)都已合并入 V1.0 单一文件;V12~V17 历史也已 inline 进 V1.0
 - 业务 `spring-ai-loom-agent-test/src/main/resources/db/migration/V1.1__init_app_data.sql` — 业务 demo 数据:12 个 mcp_server + 14 个 mcp_tool + 6 个 system skill。test 模块独立 Flyway,与库主 schema 物理隔离(`./target/test-ds`)
 - Flyway 在同实例按版本号顺序执行:`V1.0__init.sql`(库)→ `V1.1__init_app_data.sql`(业务)
 - **升级注意**(已收紧):任何已有 V1/V2.x 历史数据库 → 必须清空后重跑(`rm -rf ~/.loom/datasource`),或 `flyway baseline` 后手动迁移数据。本项目**不接受在已运行实例上增量升级 schema**。#3 起向量(embedding BLOB)也存 H2 表 loom_vector_store —— 清库重跑后知识库文档需重传(一次性 re-embed);更换 embedding 模型同理(dim 守卫会跳过旧维度行并 WARN)。
- **Chat memory**: Spring AI `JdbcChatMemoryRepository` (JDBC-backed, auto-initialized)
- **Flyway table**: `flyway_schema_history`（Spring Boot 默认，库不覆盖）

### File System Storage

All user-local state lives under `~/.loom/` (single root, single `rm -rf` to wipe):

| 目录 | 内容 | 默认值 |
|---|---|---|
| `~/.loom/file/{username}/` | 用户上传的文件（聊天附件、文件管理 UI 列出）| `fileBasePath` 默认 `${user.home}/.loom/file` |
| `~/.loom/knowledge/{username}/{knowledgeId}/` | 知识库文档原文件 | `knowledgeBasePath` 默认 `${user.home}/.loom/knowledge` |
| `~/.loom/datasource/` | H2 文件数据库 `db.mv.db` | `datasourceDir` 默认 `${user.home}/.loom/datasource`（yml 通过 `spring.datasource.url` 拼装）|
| `~/.loom/compile-deploy-workspaces/{username}/` | 编译部署工具临时 workspace（带 username/timestamp 前缀；成功默认清理）| `DefaultCompileAndDeployTool.getCompileDeployWorkspaceDir` |

**重名处理**: 同名文件自动追加序号，如 `file.txt` → `file(1).txt` → `file(2).txt`
**预览/下载桥接**: 路径操作的预览/下载通过 `IFile.getByExactPath` 查询，不存在时自动插入 `usage='temp'` 记录获取 fileId

> 历史注意：早期版本把以上全都放在 cwd-relative `.local/` 下，导致 `mvn spring-boot:run -pl test-module` 时路径漂到 test 模块下、UI 列出项目源码而不是用户文件。Fix A/B/C 把路径统一到 `~/.loom/` 之后这种事不再发生。

### Configuration Properties

All under `spring.ai.loom.agent`:
- `rag` — similarity threshold, top-k, prompt templates
- `jvector` — HNSW params (m, efConstruction, efSearch);持久化在 H2 表 `loom_vector_store`(#3 起,原 indexPath/json 目录已退役)
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
- `askuser` — `timeoutSeconds`(default 300):askUser 工具阻塞等待用户作答的最长秒数;超时返回"用户未作答"文本,Flux 正常 complete
- `fileBasePath` — 用户文件存储根目录，默认 `${user.home}/.loom/file`（绝对路径，不再 cwd-relative）
- `knowledgeBasePath` — 知识库文件存储根目录，默认 `${user.home}/.loom/knowledge`
- `datasourceDir` — H2 文件存储目录，默认 `${user.home}/.loom/datasource`（在 `application.yml` 的 `spring.datasource.url` 里通过 `${user.home}/.loom/datasource/db` 拼接）

### Frontend

Static SPA at `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/`:
- `index.html` — entry point，含文件管理模态框（目录树视图）
- `app.js` — Vue-based chat UI (SSE streaming, sidebar, modals). **BFF + Cookie auth**: no localStorage token, browser auto-carries HttpOnly cookie
- `style.css` — styling
- Uses marked.js for Markdown rendering (sanitized by a tiny inline `markdown-renderer.js` allowlist), and a minimal inline SSE parser in `app.js`

**文件管理模态框**: 显示 `{fileBasePath}/{username}/`（例如 `C:\Users\<you>\.loom\file\<username>\`）的目录树，支持展开子目录，每个文件有预览/下载按钮。不显示 `~/.loom/datasource/`、`~/.loom/compile-deploy-workspaces/` 这些工具/系统目录。

## Extension Points

To customize behavior, replace any `@Bean` by providing your own implementation:

```java
@Bean
@ConditionalOnMissingBean
public IChat customChat(...) { return new MyChat(...); }
```

To swap the vector store, simply add a Spring AI vector store starter dependency — `H2JVectorStore` won't be created due to `@ConditionalOnMissingBean(VectorStore.class)`.

`IGitTool` uses both `@ConditionalOnProperty` (`matchIfMissing=false`; set `git.enabled=true` to enable) and `@ConditionalOnMissingBean` — users can replace it with a custom implementation (e.g., CLI-based git) while keeping the feature on. Disabled by default; `ICompileAndDeployTool` is the supported end-to-end entry point.

`IMavenTool` uses `@ConditionalOnClass` (maven-invoker on classpath) + `@ConditionalOnProperty` (default off) + `@ConditionalOnMissingBean`. Disabled by default; same opt-in pattern.
