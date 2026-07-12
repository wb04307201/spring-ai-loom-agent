# Spring AI LoomAgent Customization Guide

> This document summarizes all customizable and extensible configuration properties, interfaces, bean override points, and UI customization capabilities in the project.

---

## Directory Structure

```
spring-ai-loom-agent/
├── spring-ai-loom-agent/                          # Core library
│   ├── chat/          IChat / DefaultChat          # Streaming chat
│   ├── knowledge/     IKnowledge / DefaultKnowledge # Knowledge base CRUD
│   ├── mcp/           IMcp / SyncMcp / ASyncMcp    # MCP client
│   ├── skill/         ISkillStorage                # Skill storage
│   ├── file/          IFile / IUpload              # File storage & upload
│   ├── user/          IUser / AuthenticationFilter  # Auth & filter
│   ├── vectorstore/   JVectorStore                 # Default vector store
│   ├── tool/          IEmbedTool (marker)          # Aggregate tool interface
│   │   ├── time/      ITimeTool / DefaultTimeTool  # Time tools
│   │   ├── skill/     ISkillTool / DefaultSkillTool # Skill tools
│   │   ├── file/      IFileTool / DefaultFileTool  # File tools
│   │   ├── git/       IGitTool / DefaultGitTool    # Git tools (JGit)
│   │   └── maven/     IMavenTool / DefaultMavenTool # Maven build tools (maven-invoker)
│   ├── document/      IDocumentRead / IFileDocument # Document parsing
│   └── model/         *Record / LoomAgentProperties # Models & config
├── spring-ai-loom-agent-spring-boot-autoconfigure/  # Auto-configuration
│   └── LoomAgentConfiguration.java                # Core config (7 nested @Configuration classes)
├── spring-ai-loom-agent-spring-boot-starter/        # Starter empty JAR
│   └── pom.xml                                    # Transitive dependencies only
└── spring-ai-loom-agent-test/                       # Test application
    ├── LoomAgentTestApplication.java
    └── application.yml / mcp-servers.json
```

## Table of Contents

- [1. Configuration Properties](#1-configuration-properties)
- [2. Bean Override (Interface Replacement)](#2-bean-override-interface-replacement)
- [3. Infrastructure Replacement](#3-infrastructure-replacement)
- [4. MCP Customization](#4-mcp-customization)
- [5. Skill Customization](#5-skill-customization)
- [6. Database Schema Customization](#6-database-schema-customization)
- [7. UI Frontend Customization](#7-ui-frontend-customization)
- [8. Conditional Switches Summary](#8-conditional-switches-summary)

---

## 1. Configuration Properties

All properties are prefixed with `spring.ai.loom.agent`.

### 1.1 Basic Configuration

| Property                                 | Type    | Default                 | Description                                              |
|------------------------------------------|---------|-------------------------|----------------------------------------------------------|
| `spring.ai.loom.agent.defaultSystem`     | String  | Skill discovery prompt  | Default system prompt; controls the core AI behavior     |
| `spring.ai.loom.agent.init`              | boolean | `true`                  | Whether to initialize the ChatClient; set to `false` to skip |

### 1.2 RAG Configuration (`rag.*`)

| Property                                | Type    | Default      | Description                                                        |
|-----------------------------------------|---------|--------------|--------------------------------------------------------------------|
| `rag.similarityThreshold`               | double  | `0.0`        | Vector retrieval similarity threshold; documents below are filtered |
| `rag.topK`                              | int     | `4`          | Number of documents to retrieve                                    |
| `rag.defaultPromptTemplate`             | String  | (built-in)   | RAG prompt template when context is available; supports `{context}` and `{query}` |
| `rag.defaultEmptyContextPromptTemplate` | String  | (built-in)   | Default reply template when no RAG context is found                |
| `rag.enabledKeyword`                    | boolean | `false`      | Whether to enable keyword metadata enrichment                      |
| `rag.enabledSummary`                    | boolean | `false`      | Whether to enable summary metadata enrichment                      |

### 1.3 JVector Vector Store Configuration (`jvector.*`)

| Property                   | Type   | Default                | Description                                                                 |
|----------------------------|--------|------------------------|-----------------------------------------------------------------------------|
| `jvector.indexPath`        | String | `.local/jvector-index` | Path for vector index persistence                                           |
| `jvector.m`                | int    | `16`                   | HNSW graph parameter M (controls branching factor; higher = better quality but slower build) |
| `jvector.efConstruction`   | int    | `100`                  | HNSW build-time search width (affects build quality and speed)              |
| `jvector.efSearch`         | int    | `10`                   | HNSW query-time search width (higher = more accurate but slower)            |

> The underlying library also supports `similarityFunction` (COSINE / DOT_PRODUCT), but this is not exposed as a property. Customize the `VectorStore` bean to modify it.

### 1.4 MCP Server Configuration (no longer read from yml)

> ⚠️ MCP server configuration is **no longer done via yml**. The old `mcps[]` block (under `spring.ai.loom.agent`) has been removed. MCP metadata now lives in the `mcp_server` / `mcp_tool` database tables, managed via:
>
> - **Admin Console → MCP 描述维护** (the "MCP 描述维护" sidebar section)
> - REST API `/spring/ai/loom/admin/mcps/{name}` and `/spring/ai/loom/admin/mcps/{name}/tools`
>
> Per-user "default selected" is no longer a yml-level config; it's derived from the **role authorization** in `role_mcp.default_enabled` for the user's roles.

### 1.5 Skill Configuration (no longer read from yml)

> ⚠️ Skill configuration is **no longer done via yml**. The old `skills[]` block (under `spring.ai.loom.agent`) has been removed. The init migration:
>
> 1. Creates three tables — `market_skill`, `user_skill`, `role_skill`
> 2. Migrates any existing data from the old `skill` table into `user_skill` (`source=USER_CREATED`)
> 3. Seeds 6 system skills into `market_skill` (author=`system`, status=`APPROVED`, version=`1.0.0`) — these are the demo skills with full Prompt template content hard-coded directly in the init migration:
>    - `Monthly Event Report` (网络月度事件报告)
>    - `HTTP Test` (http测试)
>    - `Save/Download/Preview Demo 1` (测试保存、下载、预览1)
>    - `Save/Download/Preview Demo 2` (测试保存、下载、预览2)
>    - `Deploy Project` (部署项目)
>    - `Auto E2E Functional Test` (测试自动E2E功能验证)
>
> To add, edit, or authorize skills, use the **admin console** (Control Panel → Skill Market) or call the REST API at `/spring/ai/loom/admin/market-skills*` and `/spring/ai/loom/admin/roles/{code}/skills`. See [./API.md → §6 Skill Management](./API.md#6-skill-management).

### 1.6 Authentication Configuration (`auth.*`)

The project uses a **BFF (Backend-For-Frontend) + HttpOnly Cookie** authentication model. After login, the server sets a session cookie, and the browser automatically carries it with each request — no token storage or manual header management is required.

| Property                        | Type    | Default                          | Description                                                     |
|---------------------------------|---------|----------------------------------|-----------------------------------------------------------------|
| `auth.enabled`                  | boolean | `true`                           | Authentication master switch; set to `false` to skip all checks |
| `auth.pathPatterns`             | Array   | `["/spring/ai/loom/**"]`         | Path patterns requiring authentication (Ant-style wildcards)    |
| `auth.excludePathPatterns`      | Array   | `["/spring/ai/loom/user/login", "/spring/ai/loom/user/isAutoLogin", "/spring/ai/loom/user/logout", "/spring/ai/loom/index.html", "/spring/ai/loom/app.js", "/spring/ai/loom/style.css"]` | Paths explicitly excluded from authentication                     |
| `auth.cookie.name`              | String  | `loom-agent-session`             | Session cookie name                                             |
| `auth.cookie.path`              | String  | `/`                              | Cookie path                                                     |
| `auth.cookie.domain`            | String  | `""`                             | Cookie domain (empty = current domain)                          |
| `auth.cookie.secure`            | boolean | `false`                          | Whether the cookie is only sent over HTTPS                      |
| `auth.cookie.sameSite`          | String  | `Lax`                            | SameSite attribute (`Lax` / `Strict` / `None`)                  |
| `auth.cookie.maxAge`            | int     | `86400`                          | Cookie max age in seconds (default 24 hours)                    |

**Example Configuration**:

```yaml
spring:
  ai:
    loom:
      agent:
        auth:
          enabled: true
          path-patterns:
            - /spring/ai/loom/**
          exclude-path-patterns:
            - /spring/ai/loom/user/login
            - /spring/ai/loom/user/isAutoLogin
            - /spring/ai/loom/user/logout
            - /spring/ai/loom/index.html
            - /spring/ai/loom/app.js
            - /spring/ai/loom/style.css
          cookie:
            name: loom-agent-session
            path: /
            secure: false
            same-site: Lax
            max-age: 86400
```

**User Configuration (`user.*`)**:

| Property                          | Type   | Default          | Description                          |
|-----------------------------------|--------|------------------|--------------------------------------|
| `user.username`                   | String | `username`       | Default auto-login username          |
| `user.nickname`                   | String | `用户`           | Default auto-login nickname          |
| `user.authentication`             | String | `loom-agent-auth`| Legacy token value (backward compatibility) |

**Session Storage**: Uses Spring Cache (default Caffeine) with TTL matching `auth.cookie.maxAge`. Replace the `sessionCache` bean for custom storage (e.g., Redis).

### 1.7 File Storage Configuration

| Property                   | Type    | Default        | Description                                                                 |
|----------------------------|---------|----------------|-----------------------------------------------------------------------------|
| `fileBasePath`             | String  | `.local/file`  | Root directory for uploaded files (chat attachments, file tool operations)  |
| `knowledgeBasePath`        | String  | `.local/knowledge` | Root directory for knowledge base files                                  |

Files with duplicate names in the same directory are auto-renamed: `file.txt` → `file(1).txt` → `file(2).txt`.

### 1.8 Git Configuration (`git.*`)

| Property            | Type    | Default | Description                                                                              |
|---------------------|---------|---------|------------------------------------------------------------------------------------------|
| `git.enabled`       | boolean | `false` | Whether to enable Git tool (IGitTool); opt-in. End-to-end deployment uses `ICompileAndDeployTool` (always on) instead. Set to `true` to expose 28 git commands to the LLM. |
| `git.username`      | String  | —       | Username for HTTP(S) git authentication (clone/pull/push)                                |
| `git.token`         | String  | —       | Token/password for HTTP(S) git authentication                                            |
| `gitUsername`       | String  | —       | **Legacy** top-level alias for `git.username`                                            |
| `gitToken`          | String  | —       | **Legacy** top-level alias for `git.token`                                               |

**Example Configuration**:

```yaml
spring:
  ai:
    loom:
      agent:
        git:
          enabled: true   # default; set to false to disable
          username: your-git-username
          token: your-git-token
```

> Git credentials can also be passed per-request via `ToolContext` (`gitUsername` / `gitToken` keys), which override the configured defaults.

### 1.9 Tool Group Switches (`{time,file,skill,git,maven,compile}.enabled`)

For the full reference of every built-in tool (`ITimeTool` / `ISkillTool` / `IFileTool` / `IGitTool` / `IMavenTool` / `ICompileAndDeployTool`) — including default state, all `@Tool` method signatures, configuration properties, base-image templates, and end-to-end deployment parameters — see **[TOOLS.md](./TOOLS.md)**.

A quick summary of the switches:

| Property           | Type    | Default | Description                                                       |
|--------------------|---------|---------|-------------------------------------------------------------------|
| `time.enabled`     | boolean | `true`  | `ITimeTool` — time and timezone tools                              |
| `file.enabled`     | boolean | `true`  | `IFileTool` — 16 path-based file tools                             |
| `skill.enabled`    | boolean | `true`  | `ISkillTool` — list skills, get skill details                     |
| `git.enabled`      | boolean | `false` | `IGitTool` (JGit). **Opt-in** — end-to-end deployment uses `ICompileAndDeployTool`. |
| `maven.enabled`    | boolean | `false` | `IMavenTool` (maven-invoker required). **Opt-in** — compile/package goes through `ICompileAndDeployTool`. |
| `compile.enabled`  | boolean | `true`  | `ICompileAndDeployTool` — end-to-end `git clone → build → docker run → health check` |

---

## 2. Bean Override (Interface Replacement)

The project uses the `@ConditionalOnMissingBean` pattern. All interfaces support custom implementation replacement — simply register a bean of the same type in the Spring container.

### 2.1 `IUser` — User Authentication

| Item            | Details                                                                                                                                                               |
|-----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.user.IUser`                                                                                                                             |
| **Default**     | `DefaultUser`                                                                                                                                                         |
| **Override**    | Custom `@Bean IUser`                                                                                                                                                  |
| **Properties**  | `spring.ai.loom.agent.user.username` (default `username`), `spring.ai.loom.agent.user.nickname` (default `用户`), `spring.ai.loom.agent.user.authentication` (default `loom-agent-auth`) |
| **Controls**    | Auto-login check, user login validation, session token management                                                                                                       |

**Interface methods**:
- `isAutoLogin()` — whether auto-login is supported (default: `true`)
- `login(UserRequestRecord)` — validate credentials and return response
- `createToken(username)` — generate session token and store in cache
- `validateToken(token)` — validate session token against cache
- `invalidateToken(token)` — remove session token (logout)
- `getUsernameByToken(token)` — resolve username from session token
- `getUsernameByAuthentication(authentication)` — legacy method (backward compatibility)

**Default behavior**: `isAutoLogin()` reads the session cookie; `login()` validates the user against the database and BCrypt-hashes passwords; session tokens are stored in Spring Cache (default Caffeine).

**Customization Example** (override every method; the sample below is a minimal shell that delegates to an upstream IdP and uses an in-memory `ConcurrentMap` instead of Spring Cache):

```java
@Bean
public IUser customUser() {
    return new IUser() {
        private final ConcurrentMap<String, String> sessions = new ConcurrentHashMap<>();

        @Override public Boolean isAutoLogin() { return false; }

        @Override public UserResponseRecord login(UserRequestRecord request) {
            // Integrate LDAP / OAuth / JWT here; on success:
            String token = createToken(request.getUsername());
            return new UserResponseRecord(token, request.getUsername());
        }

        @Override public String getUsernameByAuthentication(String authentication) {
            // Parse a real JWT / OAuth token here
            return null;
        }

        @Override public String createToken(String username) {
            String token = UUID.randomUUID().toString();
            sessions.put(token, username);
            return token;
        }

        @Override public boolean validateToken(String token) { return sessions.containsKey(token); }
        @Override public void invalidateToken(String token) { sessions.remove(token); }
        @Override public String getUsernameByToken(String token) { return sessions.get(token); }

        @Override public String getNicknameByUsername(String username) { return username; }
        @Override public boolean isAdmin(String username) { return false; }

        @Override public void changePassword(String username, String oldPassword, String newPassword) {
            // verify oldPassword against store, then update to BCrypt-hashed newPassword
        }

        @Override public List<UserInfo> listAllUsers() { return List.of(); }

        @Override public void createUser(String username, String nickname, String password, String type) {
            // persist new user (hash password with BCrypt before storing)
        }

        @Override public void deleteUser(String username) {
            // delete user; refuse if username is the last remaining ADMIN
        }
    };
}
```

### 2.2 `IUserConversation` — User Conversation Management

| Item            | Details                                                     |
|-----------------|-------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.user.IUserConversation`       |
| **Default**     | `DefaultUserConversation`                                   |
| **Override**    | Custom `@Bean IUserConversation`                            |
| **Controls**    | Conversation list, existence check, create/delete (clears chat memory) |

**Default behavior**: Operates on `user_conversation` table via JdbcTemplate; deleting a conversation also clears `ChatMemoryRepository`.

### 2.3 `IChat` — Core Chat Pipeline

| Item            | Details                                                    |
|-----------------|------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.chat.IChat`                  |
| **Default**     | `DefaultChat`                                              |
| **Override**    | Custom `@Bean IChat`                                       |
| **Method**      | `Flux<ChatResponse> stream(ChatRequestRecord record, String username, HttpServletRequest request)` |
| **Controls**    | Streaming chat: user/session management, RAG advisor, MCP tool injection, skill tool injection, image/file handling, ToolContext cross-thread context propagation |

**Default behavior**: Assembles `ChatClient`, optionally adding `RetrievalAugmentationAdvisor`, `IMcp` tools, all `IEmbedTool` sub-tools (time, skill, file, git), and user session management. Document-type files (PDF/DOCX/XLSX/PPTX/MD etc.) are parsed by Apache Tika into text and injected as a System Prompt; images are passed as Media to the model.

**Customization Example**:

```java
@Bean
@ConditionalOnMissingBean(IChat.class)
public IChat customChat(
        ChatClient chatClient,
        Optional<RetrievalAugmentationAdvisor> ragAdvisor,
        IMcp mcp,
        List<IEmbedTool> embedTools,
        IUserConversation userConversation,
        IFile file) {
    return new MyCustomChat(chatClient, ragAdvisor, mcp, embedTools, userConversation, file);
}
```

### 2.4 `IFile` — File Metadata

| Item            | Details                                         |
|-----------------|-------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.file.IFile`       |
| **Default**     | `DefaultFile`                                   |
| **Override**    | Custom `@Bean IFile`                            |
| **Controls**    | File metadata CRUD, Spring `Resource` access by ID |

**Default behavior**: Metadata stored in `file_info` table; `getResourceById()` returns a `FileSystemResource` from the local filesystem.

**Common use case**: Replace with S3, OSS, MinIO, or other object storage.

### 2.5 `IUpload` — File Upload Pipeline

| Item            | Details                                           |
|-----------------|---------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.file.IUpload`       |
| **Default**     | `DefaultUpload`                                   |
| **Override**    | Custom `@Bean IUpload`                            |
| **Controls**    | File upload (plain/knowledge-base), file download, file deletion (knowledge-base-aware), bulk knowledge-base file deletion |

**Default behavior**: Chat-uploaded files saved to `{fileBasePath}/{username}/` (e.g., `.local/file/username/`), knowledge-base files to `{knowledgeBasePath}/{username}/{knowledgeId}/` (e.g., `.local/knowledge/username/{knowledgeId}/`). Duplicate names get a numeric suffix: `file.txt` → `file(1).txt` → `file(2).txt`. Documents are parsed via `IDocumentRead` (PDF/DOCX/XLSX/PPTX/MD etc.) — the extracted text is injected into the conversation as a System Prompt.

**Common use case**: Upload to cloud storage (S3/OSS), integrate third-party OCR, async document parsing.

### 2.6 `IDocumentRead` — Document Parsing & Processing

| Item            | Details                                                     |
|-----------------|-------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.document.IDocumentRead`       |
| **Default**     | `DefaultDocumentRead`                                       |
| **Override**    | Custom `@Bean IDocumentRead`                                |
| **Condition**   | Only created when a `VectorStore` bean exists               |
| **Controls**    | File reading (Tika parsing), text splitting, keyword metadata enrichment, summary metadata enrichment |

**Default behavior**: Uses Apache Tika to parse multiple document formats, splits text by `PagePdfParser.DEFAULT_MAX_CHARS`, optionally injects keyword and summary metadata.

### 2.7 `IFileDocument` — File-Document Association

| Item            | Details                                                     |
|-----------------|-------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.document.IFileDocument`       |
| **Default**     | `DefaultFileDocument`                                       |
| **Override**    | Custom `@Bean IFileDocument`                                |
| **Controls**    | CRUD of the `file_document` join table, maintaining file-to-vector-document-ID mappings |

### 2.8 `IKnowledge` — Knowledge Base Management

| Item            | Details                                               |
|-----------------|-------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge`   |
| **Default**     | `DefaultKnowledge`                                    |
| **Override**    | Custom `@Bean IKnowledge`                             |
| **Controls**    | Knowledge base list, create, delete (with cascade cleanup of associated files and vectors) |

**Default behavior**: Operates on `knowledge` and `knowledge_file` tables via JdbcTemplate.

### 2.9 `ISkillStorage` — Skill Storage

| Item            | Details                                              |
|-----------------|------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.skill.ISkillStorage`   |
| **Default**     | `DefaultSkillStorage`                                |
| **Override**    | Custom `@Bean ISkillStorage`                         |
| **Controls**    | Per-user skill list (`user_skill`), save / patch / get / remove; auto-syncs `role_skill` → `user_skill` (locked ROLE_GRANTED entries) on every list/get; admin union view (APPROVED + own PENDING); pairs with `ISkillMarketService` and `ISkillRoleAdmin` |

**Default behavior**: JDBC-backed storage using three tables — `user_skill` (per-user installed skills), `role_skill` (skills granted to a role, automatically locked in `user_skill` for every user holding that role), and `market_skill` (the Skill Market catalog: PENDING / APPROVED / REJECTED / DEPRECATED status). `DefaultSkillStorage` does not load any yml fallback anymore; the `spring.ai.loom.agent.skills.*` properties were removed in favor of seeding via the `V1.0/V1.1` Flyway migrations and managing through the admin console → Skill Market page.

**Common use case**: Add a third-party skill registry (e.g., pull from a private Nexus / REST catalog) by implementing `ISkillStorage` and registering it as a `@Bean` to replace `DefaultSkillStorage`.

### 2.10 `IMcp` — MCP Tool Provider

| Item            | Details                                       |
|-----------------|-----------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.mcp.IMcp`       |
| **Default**     | `SyncMcp` / `ASyncMcp`                        |
| **Override**    | Custom `@Bean IMcp`                           |
| **Controls**    | MCP server list, `ToolCallbackProvider` for selected MCPs |

**Variant selection**:

- Default: `SyncMcp` (based on `McpSyncClient`)
- Set `spring.ai.mcp.client.stdio=ASYNC` to switch to `ASyncMcp` (based on `McpAsyncClient`)

### 2.11 `IEmbedTool` — Embed Tools (Time / Skill / File / Git / Maven / Compile)

`IEmbedTool` is an aggregate marker interface. Sub-interfaces (`ITimeTool`, `ISkillTool`, `IFileTool`, `IGitTool`, `IMavenTool`, `ICompileAndDeployTool`) each contribute independent `@Tool` methods to the LLM. Each can be replaced independently via `@ConditionalOnMissingBean`.

For the full reference (all `@Tool` method signatures, configuration properties, base-image templates, end-to-end deployment parameters, and replacement examples) see **[TOOLS.md](./TOOLS.md)**.

### 2.12 `AuthenticationFilter` — Authentication Filter

| Item            | Details                                                  |
|-----------------|----------------------------------------------------------|
| **Type**        | `cn.wubo.spring.ai.loom.agent.user.AuthenticationFilter` |
| **Override**    | Custom Servlet Filter, or override the `IUser` bean       |
| **Controls**    | Intercepts requests matching `auth.pathPatterns`, validates session cookie |

The filter uses `AntPathMatcher` to match request paths against `auth.pathPatterns`. Paths listed in `auth.excludePathPatterns` are always bypassed. When `auth.enabled=false`, the filter passes all requests through without validation.

**Session management flow**:
1. User accesses `/spring/ai/loom/index.html` (no auth required)
2. Frontend calls `POST /spring/ai/loom/user/isAutoLogin` → returns `true`
3. Frontend calls `POST /spring/ai/loom/user/login` → server creates session, sets `Set-Cookie: loom-agent-session=...`
4. Browser automatically includes the HttpOnly cookie in subsequent requests
5. `AuthenticationFilter` reads the cookie, validates against cache, sets `UserContextHolder`
6. Logout: `POST /spring/ai/loom/user/logout` → server invalidates token and clears cookie

---

## 3. Infrastructure Replacement

### 3.1 ChatClient

| Item            | Details                                                                                              |
|-----------------|------------------------------------------------------------------------------------------------------|
| **Bean Name**   | `ChatClient`                                                                                         |
| **Condition**   | `@ConditionalOnProperty(name = "spring.ai.chat.ui.init", havingValue = "true", matchIfMissing = true)` |
| **Override**    | Custom `@Bean ChatClient`, or set `spring.ai.chat.ui.init=false` to prevent creation                  |
| **Default**     | Uses `defaultSystem` as the system prompt, mounts `ChatMemory` advisor and logger advisor             |

### 3.2 ChatMemory

| Item            | Details                                                                     |
|-----------------|-----------------------------------------------------------------------------|
| **Bean Name**   | `jdbChatMemory`                                                             |
| **Default**     | `MessageWindowChatMemory`, backed by `ChatMemoryRepository`                   |
| **Override**    | Custom `@Bean ChatMemory`                                                   |
| **Alternatives** | Spring AI provides multiple memory strategies: `BufferWindowChatMemory`, `ConcurrentMapChatMemory`, etc. |

### 3.3 ChatMemoryRepository (Persistence Backend)

Spring AI supports multiple persistence backends via auto-configuration based on classpath dependencies:

| Backend        | Dependency                     | Description                     |
|----------------|--------------------------------|---------------------------------|
| JDBC (default) | `spring-ai-jdbc-memory`        | Relational database, current default |
| Redis          | `spring-ai-redis-memory`       | Redis storage                   |
| MongoDB        | `spring-ai-mongodb-memory`     | MongoDB storage                 |
| Cassandra      | `spring-ai-cassandra-memory`   | Cassandra storage               |
| CosmosDB       | `spring-ai-cosmosdb-memory`    | Azure CosmosDB storage          |
| Neo4j          | `spring-ai-neo4j-memory`       | Neo4j graph database storage    |

### 3.4 VectorStore

JVector is the fallback. Add any Spring AI VectorStore Starter to auto-replace it:

| Vector Store                | Dependency Starter              | Description                  |
|-----------------------------|---------------------------------|------------------------------|
| **JVector (fallback)**      | Built-in                        | Local file persistence, zero external dependencies |
| Qdrant                      | `spring-ai-qdrant-store`        | Used in the test module      |
| Milvus                      | `spring-ai-milvus-store`        | Commonly used in production  |
| Redis                       | `spring-ai-redis-store`         | Redis Vector                 |
| Chroma                      | `spring-ai-chroma-store`        | Lightweight local solution   |
| Elasticsearch               | `spring-ai-elasticsearch-store` | ELK ecosystem                |
| Pinecone                    | `spring-ai-pinecone-store`      | Cloud service                |
| Weaviate                    | `spring-ai-weaviate-store`      | Open-source vector database  |

**Customization Example** (Qdrant):

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-qdrant</artifactId>
</dependency>
```

`JVectorStore` is skipped automatically — no code needed.

### 3.5 ChatModel (AI Model Provider)

Spring AI supports multiple model providers. Switch by configuring the corresponding Starter and API Key:

| Model              | Configuration Example                                |
|--------------------|------------------------------------------------------|
| DashScope (Qwen)   | `spring.ai.dashscope.api-key=...`                    |
| OpenAI             | `spring.ai.openai.api-key=...`                       |
| Ollama             | `spring.ai.ollama.base-url=http://localhost:11434`   |
| Anthropic          | `spring.ai.anthropic.api-key=...`                    |
| Azure OpenAI       | `spring.ai.azure.openai.api-key=...`                 |

### 3.6 RetrievalAugmentationAdvisor

| Item            | Details                                                              |
|-----------------|----------------------------------------------------------------------|
| **Type**        | `RetrievalAugmentationAdvisor`                                       |
| **Condition**   | Only created when a `VectorStore` bean exists                        |
| **Override**    | Custom `@Bean RetrievalAugmentationAdvisor`                          |
| **Configurable** | `documentRetriever` (similarity threshold, topK), `queryAugmenter` (prompt templates) |

### 3.7 Flyway (Database Migration)

| Item            | Details                                                                         |
|-----------------|---------------------------------------------------------------------------------|
| **Default**     | Library ships `V1.0__init.sql` at `classpath:db/migration/` (schema + admin seed). Application modules add their own `V1__xxx.sql` (or `V1.1__xxx.sql` etc.) in the same `classpath:db/migration/`; Spring Boot's default Flyway runs them in version order on a single `flyway_schema_history` table. Library does **not** override Flyway's default location or history table. |

---

## 4. MCP Customization

### 4.1 Sync/Async Mode Switch

| Property                                      | Value         | Effect                              |
|-----------------------------------------------|---------------|-------------------------------------|
| `spring.ai.mcp.client.stdio` not set or not `ASYNC` | Uses `SyncMcp`  | Based on `McpSyncClient`            |
| `spring.ai.mcp.client.stdio=ASYNC`            | Uses `ASyncMcp` | Based on `McpAsyncClient`           |

### 4.2 Custom MCP Implementation

Beyond configuring the MCP servers (via the `mcp_server` table), you can fully replace the `IMcp` interface to customize:

- MCP server discovery logic
- Tool callback interception/enhancement
- Dynamic tool registration

---

## 5. Skill Customization

> ⚠️ Skill 数据**完全在数据库里**。`spring.ai.loom.agent.skills[]` yml 段已废弃，配置不再从此读取。详见 [§1.5 Skill Configuration](#15-skill-configuration-no-longer-read-from-yml)。

### 5.1 Skill 生命周期（按数据来源分三种来源）

| 来源 (`user_skill.source`) | 描述 | 可改 | 不可改 |
|---|---|---|---|
| `USER_CREATED` | 用户在聊天 UI「技能库 → 我的」自建 | name / desc / content / default_loaded | （PK 本身） |
| `MARKET_PULLED` | 用户从「技能库 → 市场」Tab 拉取 | desc / default_loaded | content（要更新就重新拉取） |
| `ROLE_GRANTED` | admin 通过角色 → 用户强制注入 | （全部只读） | 全部 |

### 5.2 添加 / 修改 / 授权 Skill 的方式

| 方式 | 适合 | 入口 |
|---|---|---|
| 控制台（admin） | demo 数据 / 一次性 seed | 浏览器登录 admin → 控制台 → Skill 市场 |
| 业务模块 SQL | 业务模块作者 seed 自己的 mcp / skill | `src/main/resources/db/migration/V1__xxx.sql`（V1.0 已被库占用，业务用 V1 或 V1.1+ 即可） |
| 真实用户 | 用户自建 | 聊天 UI 技能库 → 我的 Tab → + 新建 |
| REST API | 程序化操作 | [./API.md → §6 Skill Management](./API.md#6-skill-management) |

### 5.3 角色授权 Skill（admin 专属）

admin 在控制台「角色管理」编辑某个角色时，可勾选要授权的 market_skill。授权后，**该角色下的所有用户登录时自动获得该 Skill**（`source=ROLE_GRANTED, locked=true`），**用户不能改不能删**。

```sql
-- role_skill 表（自动管理，不需要手写 SQL）
-- role_code | market_skill_id | sort_order | default_loaded
-- '研发'   |  1               | 0          | true
```

### 5.4 Skill 内容模板

`content` 字段支持任何字符串（Prompt 模板）。`{param}` 占位由 LLM 在运行时从对话上下文解释，不是结构化表单字段。`@tool_name` 引用当前用户角色授权的 MCP 工具。

示例 Prompt 模板（不是 yml，是 SQL 里 INSERT 的 content 字段）：

```text
用户希望"梳理 {topic} 月度事件"。
- 调"获取当前时间"工具拿到当前年/月
- 调"必应搜索"按月搜索 {topic} 事件
- 按月分组，输出 HTML 报告
- 调"生成文件预览链接"工具把报告存为 reports/{topic}-{year}.html
```

---

## 6. Database Schema Customization

### 6.1 Default Tables

| Table               | Purpose                        | Primary Key                     |
|---------------------|--------------------------------|---------------------------------|
| `knowledge`         | Knowledge base metadata        | `id`                            |
| `knowledge_file`    | Knowledge base — file mapping  | `(knowledge_id, file_id)`       |
| `file_info`         | File metadata and storage path (`usage` column: `conversation` / `knowledge` / `tool` / `git` / `temp`) | `id` |
| `file_document`     | File — vector document mapping | `(file_id, document_id)`        |
| `user_conversation` | User — conversation mapping    | `(username, conversation_id)`   |

### 6.2 Custom Migration Scripts

By overriding `FlywayConfigurationCustomizer`, you can:

- Change migration script paths
- Customize table schemas
- Add additional migration scripts

### 6.3 Database Replacement

Defaults to the embedded H2 database. Replace it by adding the corresponding Spring Boot database Starter:

- MySQL
- PostgreSQL
- MariaDB
- Any other JDBC-compatible database

---

## 7. UI Frontend Customization

UI static resources are located at `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/`.

### 7.1 Customizable UI Elements

| Element            | File           | Description                              |
|--------------------|----------------|------------------------------------------|
| HTML structure     | `index.html`   | Page skeleton; brand name "Loom" hardcoded |
| JavaScript logic   | `app.js`       | Frontend interaction logic               |
| CSS styles         | `style.css`    | Style definitions                        |

### 7.2 Hardcoded Constants (require source code changes)

| Constant                    | Location     | Default Value            |
|-----------------------------|--------------|--------------------------|
| AI avatar image             | `app.js`     | `/static/ai.jpg`         |
| User avatar image           | `app.js`     | `/static/user.png`       |
| Allowed file upload types | `app.js`     | JPG, PNG, GIF, WebP, BMP, PDF, DOCX, XLSX, PPTX, MD, TXT |
| Max upload size           | `app.js`     | 10 MB                    |
| SSE timeout               | `app.js`     | `0` (no timeout)         |
| UI modules                | `index.html` | Knowledge Space, MCP Services, Skill Library |

> **Note**: The frontend no longer uses localStorage for tokens (BFF + Cookie auth). Session is managed via HttpOnly cookies.

### 7.3 Override Method

Place same-named static resources in your own project to override the defaults, or use Spring static resource configuration.

---

## 8. Conditional Switches Summary

| Condition                            | Configuration/Dependency     | Impact                                                                                           |
|--------------------------------------|------------------------------|--------------------------------------------------------------------------------------------------|
| `spring.ai.chat.ui.init=false`       | application.yml              | `ChatClient` is not created; the entire chat pipeline is unavailable                              |
| `spring.ai.mcp.client.stdio=ASYNC`   | application.yml              | Switches to `ASyncMcp` (async MCP client)                                                        |
| `spring.ai.mcp.client.enabled=false` | application.yml              | MCP client auto-configuration disabled (prevents startup failures if MCP servers are unavailable) |
| `auth.enabled=false`                 | application.yml              | Authentication disabled; `AuthenticationFilter` passes all requests through                      |
| No `VectorStore` bean provided       | Do not add any VectorStore Starter | `IDocumentRead`, `RetrievalAugmentationAdvisor`, `loomAgentFileRouter`, and `loomAgentKnowledgeRouter` are not created; knowledge base and file upload features unavailable |
| No `EmbeddingModel` bean provided    | Do not add EmbeddingModel Starter | `JVectorStore` is not created; vector storage unavailable                                        |
| Custom bean of the same type         | Java `@Bean` configuration   | The corresponding `@ConditionalOnMissingBean` bean will not be created                           |
| `spring.ai.loom.agent.git.enabled=true` | application.yml           | Creates `IGitTool` bean (`DefaultGitTool`, Eclipse JGit 7.6.0); without this, no Git tool methods are available to the LLM |
| `maven-invoker` on classpath         | Provided dependency          | Enables `IMavenTool` bean creation; without it, Maven tool is not available |

### 8.1 Quick Feature Disablement Guide

| To Disable         | Action                                                                 |
|--------------------|------------------------------------------------------------------------|
| Entire chat        | Set `spring.ai.chat.ui.init=false`                                     |
| RAG / Knowledge Base | Do not add any `VectorStore` or `EmbeddingModel` Starter               |
| MCP functionality  | Set `spring.ai.mcp.client.enabled=false`                               |
| Git tool           | Do not set `spring.ai.loom.agent.git.enabled=true` (default is disabled) |
| Maven tool         | Set `spring.ai.loom.agent.maven.enabled=false`                         |
| Auth filter        | Set `spring.ai.loom.agent.auth.enabled=false`                          |
| Auto-login         | Override `IUser.isAutoLogin()` to return `false`                       |

### 8.2 Session Cache Customization

The `sessionCache` bean uses Caffeine by default with TTL matching `auth.cookie.maxAge`. Replace it for custom storage:

```java
@Bean
public Cache sessionCache(RedisCacheManager cacheManager) {
    return cacheManager.getCache("loom-agent-auth");
}
```

### 8.3 IChat Method Signature

The `IChat.stream()` method accepts a `username` parameter (injected by `SseController` from `UserContextHolder`), eliminating the need for `ChatRequestRecord.authentication` field. This makes the username explicit rather than implicit.
