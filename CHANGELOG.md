# Changelog

All notable changes to Spring AI LoomAgent will be documented in this file.

---

## [1.1.36] — 2026-07-31

> **Knowledge Market · Tool-based RAG · Sub-task & Schedule · RBAC Hardening**

### 🚀 New Features

#### Knowledge Market (知识库市场)
- **Knowledge Market service** with RBAC support — submit → PENDING → admin approve → APPROVED → subscribe workflow
- **Four-tab knowledge UI**: 我的 / 市场 / 共享 / 我的发布 — mirrors the skill market pattern
- **File download & preview** for knowledge base documents via `downloadFileUrl` / `viewFileUrl`
- **IFileStorage abstraction** — two implementations: `DatabaseFileStorage` (H2 BLOB) and `DiskFileStorage` (filesystem)
- **Knowledge description field** — `knowledge` table gains `description` column; shown in creation dialog and list
- **canEdit REST endpoint** — conditional edit/delete buttons based on permission session
- **Role-based knowledge authorization** — `role_knowledge` table grants knowledge bases to roles (auto-injected on login)

#### Tool-based RAG (工具化 RAG)
- **IKnowledgeTool** — replaces old `RetrievalAugmentationAdvisor` with LLM-callable `listKnowledgeBases` + `searchKnowledge`
- **Enabled knowledge IDs per conversation** — `user_conversation.enabled_knowledge_ids` (JSON) lets users pick which KBs to search
- **Skill/knowledge summary injection** — dynamic system prompt enrichment with active skill and knowledge context

#### Skill Market Enhancements
- **listMySubmitted API** — track your market submissions (PENDING / APPROVED / REJECTED)
- **Withdraw functionality** — withdraw PENDING items to edit and resubmit
- **Paginated listSkills** — replaced bulk `skillContents` with paginated `listSkills(page, size)`

#### Sub-task & Schedule (子任务 & 定时任务)
- **ISubTaskTool** — LLM-callable `start_sub_task` / `list_sub_tasks` / `cancel_sub_task` / `get_sub_task_history`
- **IScheduleTool** — LLM-callable create/cancel/list/history for scheduled tasks
- **Conversation-scoped panels** — per-conversation sub-task and schedule management UI
- **Per-conversation history persistence** — V15 + V16 Flyway migrations
- **flex-schedule 1.2.2** integration — 10m min interval / 72h max lifetime / strict mode
- **Auto title rename** — conversation titles auto-generated from first message

###  Improvements

- **README layered structure** — 6 Pillars / Platform / Advanced aligns with project overview image
- **Project overview image redesign** — ZONE 2: 6 pillars (Chat/Knowledge/Files/MCP/Skill/RBAC), ZONE 4: dual market orange highlight
- **Knowledge selection → multi-select** — replaced single `enableRag` toggle with multi-KB picker
- **Storage root unification** — all user data under `~/.loom/` (file/knowledge/datasource/jvector/compile-deploy-workspaces)
- **V2.0 migration** — merged V12~V17 into single `V2.0__subtask_and_schedule.sql`
- **flex-schedule upgrade** — 1.0-SNAPSHOT → 1.2.2 with BUG-14 fix

### 🐛 Bug Fixes

- **Schedule RBAC**: orphan-user filter on restore, transactional market operations, history route guard
- **Sub-task RBAC**: `SubTaskRegistry.kill` scoped by username, parent conversation propagation
- **MCP persistence**: namespace by username, localStorage persistence across reloads
- **Auth validation**: reject usernames with dashes, return 401 when not logged in
- **Security**: sanitize LLM/skill markdown output, auth-exclude module paths
- **File RBAC**: `findById` scoped, path patterns enforced
- **Sidebar**: persist new conversations, safe rename, correct timestamps
- **Knowledge**: clear chat-bound KB id when deleted, prevent DuplicateKeyException on resubmit
- **Chat**: prevent attachment upload race (send before upload completes → silent drop)
- **Admin**: proper 4xx codes for role CRUD, MCP edit save race condition, conversation message route
- **SSE**: subRef/disposeRequested guard, single-record usage guard
- **UI**: hard-coded limits replaced with real config values (sub-task history, schedule interval)

### 🧪 Testing
- 15-task full regression test suite
- `DefaultKnowledgeTool` unit tests
- Knowledge market integration tests
- Schedule ownership + restore orphan filter + user caps + admin message route tests
- 99/99 E2E pass rate

### 📚 Documentation
- Knowledge market architecture docs (`docs/knowledge-market.md`)
- Tool-based RAG architecture docs
- Bilingual SUBTASK-SCHEDULER docs (EN + zh-CN)
- Full regression test reports (15 tasks)
- API docs updated with Knowledge Market section (§5.8)

### 🏗️ Infrastructure
- **Database**: `knowledge.description` (VARCHAR 500), `user_conversation.enabled_knowledge_ids` (VARCHAR 1000), `loom_scheduled_task`, `loom_schedule_execution`, `loom_subtask_history`
- **Dependencies**: flex-schedule 1.2.2, spring-boot-starter-actuator
- **Git**: JGit 7.6.0 (opt-in via `git.enabled=true`)
- **Model**: Default switched to DashScope Qwen (`qwen3.7-plus`)

---

## [1.1.34] — 2026-07-10

> **Sub-task & Schedule foundation**

### 🚀 New Features
- `ISubTaskExecutor` + `DefaultSubTaskExecutor` — sync sub-task on dedicated pool
- `ISubTaskTool` interface — LLM-callable `start_sub_task`
- `IScheduleTool` complete signatures + `DefaultScheduleTool`
- Schedule configuration with flex-schedule dependency
- Conversation delete cascades to sub-tasks + schedules
- Per-conversation cancel-all + state + cascade delete

### 🐛 Bug Fixes
- Break 3-hop bean cycle in sub-task executor
- Wire kill semantics + parent conversation ID + MCP callbacks
- Ghost task cleanup on REST cancel

---

## [1.1.32] — 2026-06-27

> **Model switch + streaming fix**

### 🔧 Changes
- Switch to Alibaba DashScope (Qwen) model
- Fix reasoning content loss in Spring AI 2.0 streaming
- Version bump 1.1.31 → 1.1.32
