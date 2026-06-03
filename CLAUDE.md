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
| `IChat` | `DefaultChat` | Chat streaming (SSE), MCP tool orchestration, RAG augmentation |
| `IKnowledge` | `DefaultKnowledge` | Knowledge base CRUD (stored in H2) |
| `IMcp` | `SyncMcp` / `ASyncMcp` | MCP client wrapper (sync or async), tool discovery & invocation |
| `ISkillStorage` | `DefaultSkillStorage` | Skill template storage, parameter forms, MCP tool binding |
| `IFile` | `DefaultFile` | File metadata storage (H2) + disk storage |
| `IUpload` | `DefaultUpload` | File upload pipeline: Tika parsing → document splitting → vectorization |
| `IUser` | `DefaultUser` | Token-based auth filter + auto-login |
| `IUserConversation` | `DefaultUserConversation` | User-to-conversation mapping |
| `IEmbedTool` | _(marker interface)_ | Aggregate type for all embed tools, sub-interfaces extend it |
| `ITimeTool` | `DefaultTimeTool` | Time tools: get current time, convert between timezones |
| `ISkillTool` | `DefaultSkillTool` | Skill tools: list skills, get skill details |
| `IFileTool` | `DefaultFileTool` | File tools: read/write/edit/search files, manage directories |
| `IGitTool` | `DefaultGitTool` | Git tools: clone, commit, push, pull, branch, merge, diff, blame, and ~20 more (conditionally enabled via `git.enabled`) |
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
| `ToolConfiguration` | ITimeTool, ISkillTool, IFileTool, IGitTool (git conditional on `git.enabled`) |
| `StorageConfiguration` | IUser, IUserConversation, ISkillStorage, IFile, IFileDocument, IKnowledge |
| `WebConfiguration` | AuthenticationFilter, 6 RouterFunctions |

- `@AutoConfigureAfter` all Spring AI model/embedding/vectorstore/memory/MCP auto-configurations
- Creates `ChatClient` with `MessageChatMemoryAdvisor` and `SimpleLoggerAdvisor`
- Default `JVectorStore` (HNSW index, disk-persisted) when no other `VectorStore` bean exists
- `RetrievalAugmentationAdvisor` with configurable prompt templates and similarity threshold
- `IGitTool` (Eclipse JGit 7.2.1) conditionally created when `spring.ai.loom.agent.git.enabled=true`
- REST endpoints under `/spring/ai/loom/*` (RouterFunctions + one `@RestController` for SSE)
- `AuthenticationFilter` on `/spring/ai/loom/*` paths

### Data Layer

- **Schema**: `db/loom/V1__db_init.sql` — Flyway migration creates `knowledge`, `knowledge_file`, `file_info`, `file_document`, `user_conversation` tables
- **Chat memory**: Spring AI `JdbcChatMemoryRepository` (JDBC-backed, auto-initialized)
- **Custom Flyway table**: `loomAgent_schema_history`

### Configuration Properties

All under `spring.ai.loom.agent`:
- `rag` — similarity threshold, top-k, prompt templates
- `jvector` — index path, HNSW params (m, efConstruction, efSearch)
- `mcps` — list of MCP service configs (name, title, description, tools, default-selected)
- `skills` — list of skill templates (name, description, tools, content path, params)
- `user` — default username, nickname, authentication token
- `git` — `enabled` (boolean, default false), `gitUsername`, `gitToken` for remote git authentication

### Frontend

Static SPA at `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/`:
- `index.html` — entry point
- `app.js` — Vue-based chat UI (SSE streaming, sidebar, modals)
- `style.css` — styling
- Uses marked.js for Markdown rendering, eventsource-parser for SSE

## Extension Points

To customize behavior, replace any `@Bean` by providing your own implementation:

```java
@Bean
@ConditionalOnMissingBean
public IChat customChat(...) { return new MyChat(...); }
```

To swap the vector store, simply add a Spring AI vector store starter dependency — `JVectorStore` won't be created due to `@ConditionalOnMissingBean(VectorStore.class)`.

`IGitTool` uses both `@ConditionalOnProperty` (requires `git.enabled=true`) and `@ConditionalOnMissingBean` — users can replace it with a custom implementation (e.g., CLI-based git) while still enabling the feature.
