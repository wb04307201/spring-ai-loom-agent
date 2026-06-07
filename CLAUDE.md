# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Spring AI LoomAgent** — A Spring Boot auto-configuration library that provides an out-of-the-box chat UI with RAG knowledge base, MCP tool calling, and Skill library for Spring AI applications.

- **JDK**: 17+
- **Framework**: Spring Boot 3.x + Spring AI 1.x
- **Build**: Maven (multi-module)
- **Database**: H2 (default), with Flyway migrations

## Module Structure

| Module | Purpose |
|--------|---------|
| `spring-ai-loom-agent` | Core library — chat, knowledge base, file, MCP, skill, user interfaces + default implementations, JVector vector store, H2 schema, static frontend resources |
| `spring-ai-loom-agent-spring-boot-autoconfigure` | `LoomAgentConfiguration` with 7 nested static `@Configuration` classes (Infrastructure, Chat, Rag, Mcp, Tool, Storage, Web) — `@AutoConfiguration` with `@ConditionalOnMissingBean` on all beans for full replaceability |
| `spring-ai-loom-agent-spring-boot-starter` | Empty JAR that depends on autoconfigure — the one dependency users add |
| `spring-ai-loom-agent-test` | Test application with `application.yml` — run locally to verify changes |

## Key Commands

```bash
# Build all modules (skip GPG signing for local dev)
./mvnw clean install -Dgpg.skip=true

# Run the test application
./mvnw spring-boot:run -pl spring-ai-loom-agent-test

# Run a single test
./mvnw test -pl spring-ai-loom-agent-test -Dtest=ChatTest

# Package for release (includes GPG signing)
./mvnw clean deploy
```

## Architecture

### Core Interfaces (in `spring-ai-loom-agent`)

All components follow an **interface + default implementation** pattern. Every bean is registered with `@ConditionalOnMissingBean`, allowing consumers to replace any piece:

| Interface | Default Impl | Responsibility |
|-----------|-------------|----------------|
| `IChat` | `DefaultChat` | Chat streaming (SSE), MCP tool orchestration, RAG augmentation. `stream(record, username, request)` — username injected by filter |
| `IKnowledge` | `DefaultKnowledge` | Knowledge base CRUD (stored in H2) |
| `IMcp` | `SyncMcp` / `ASyncMcp` | MCP client wrapper (sync or async), tool discovery & invocation |
| `ISkillStorage` | `DefaultSkillStorage` | Skill template storage, parameter forms, MCP tool binding |
| `IFile` | `DefaultFile` | File metadata storage (H2) — 仅用于知识空间文件、文件预览/下载桥接、聊天附件 |
| `IUpload` | `DefaultUpload` | File upload pipeline: 上传文件存储到 `fileBasePath/{username}/`，知识库文件存储到 `knowledgeBasePath/{username}/{knowledgeId}/`，重名自动追加序号 |
| `IUser` | `DefaultUser` | BFF + HttpOnly cookie session auth + auto-login |
| `IUserConversation` | `DefaultUserConversation` | User-to-conversation mapping |
| `IEmbedTool` | _(marker interface)_ | Aggregate type for all embed tools, sub-interfaces extend it |
| `ITimeTool` | `DefaultTimeTool` | Time tools: get current time, convert between timezones |
| `ISkillTool` | `DefaultSkillTool` | Skill tools: list skills, get skill details |
| `IFileTool` | `DefaultFileTool` | 15 File tools: 基于路径的读写/编辑/搜索/目录浏览（readTextFile, readMediaFile, readMultipleFiles, writeFile, editFile, createDirectory, moveFile, searchFiles, listAllowedDirectories, listDirectory, listDirectoryWithSizes, directoryTree, getFileInfo, downloadFileUrl, viewFileUrl），预览/下载自动桥接 fileId |
| `IGitTool` | `DefaultGitTool` | 31 Git tools: init, clone, status, add, commit, diff, log, branch, checkout, pull, push, fetch, merge, rebase, reset, stash, tag, remote, blame, show, reflog, clean, cherry-pick, worktree, set-working-dir, clear-working-dir, changelog-analyze, wrapup-instructions (conditionally enabled via `git.enabled`)，不依赖 IFile |
| `ITerminalTool` | `DefaultTerminalTool` | 9 Terminal tools: startProcess, interactWithProcess, readProcessOutput, forceTerminate, listSessions, getProcessInfo, sendSignal, listProcesses, killProcess — supports PTY (pty4j) for true REPL interaction |
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
| `ToolConfiguration` | ITimeTool, ISkillTool, IFileTool, IGitTool (git conditional on `git.enabled`), ITerminalTool |
| `StorageConfiguration` | IUser, IUserConversation, ISkillStorage, IFile, IFileDocument, IKnowledge |
| `WebConfiguration` | AuthenticationFilter, 6 RouterFunctions |

- `@AutoConfigureAfter` all Spring AI model/embedding/vectorstore/memory/MCP auto-configurations
- Creates `ChatClient` with `MessageChatMemoryAdvisor` and `SimpleLoggerAdvisor`
- Default `JVectorStore` (HNSW index, disk-persisted) when no other `VectorStore` bean exists
- `RetrievalAugmentationAdvisor` with configurable prompt templates and similarity threshold
- `IGitTool` (Eclipse JGit 7.6.0) conditionally created when `spring.ai.loom.agent.git.enabled=true`
- REST endpoints under `/spring/ai/loom/*` (RouterFunctions + one `@RestController` for SSE)
- `AuthenticationFilter` on `/*` (matches all), with `AntPathMatcher` filtering via `auth.pathPatterns` and `auth.excludePathPatterns`

### Data Layer

- **Schema**: `db/loom/V1__db_init.sql` — Flyway migration creates `knowledge`, `knowledge_file`, `file_info`, `file_document`, `user_conversation` tables
- **Chat memory**: Spring AI `JdbcChatMemoryRepository` (JDBC-backed, auto-initialized)
- **Custom Flyway table**: `loomAgent_schema_history`

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
- `skills` — list of skill templates (name, description, load, content path)
- `auth` — `enabled` (boolean, default true), `pathPatterns` (Ant-style path list), `excludePathPatterns`, `cookie` (name, path, domain, secure, sameSite, maxAge)
- `user` — default username, nickname, authentication token (legacy)
- `git` — `enabled` (boolean, default false), `gitUsername`, `gitToken` for remote git authentication
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

`IGitTool` uses both `@ConditionalOnProperty` (requires `git.enabled=true`) and `@ConditionalOnMissingBean` — users can replace it with a custom implementation (e.g., CLI-based git) while still enabling the feature.
