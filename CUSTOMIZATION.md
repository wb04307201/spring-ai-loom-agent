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
│   │   └── terminal/  ITerminalTool / DefaultTerminalTool # Terminal/Process tools (pty4j)
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

### 1.4 MCP Server Configuration (`mcps[]`)

YAML array configuration. Each MCP server entry contains:

| Field                 | Type    | Default | Description                   |
|-----------------------|---------|---------|-------------------------------|
| `name`                | String  | —       | MCP server identifier         |
| `title`               | String  | —       | Display label                 |
| `description`         | String  | —       | Description info              |
| `defaultSelected`     | boolean | `true`  | Whether selected by default in the UI |
| `tools[].name`        | String  | —       | Tool name                     |
| `tools[].description` | String  | —       | Tool description              |

### 1.5 Skill Configuration (`skills[]`)

YAML array configuration. Each skill entry contains:

| Field            | Type     | Default | Description                                       |
|------------------|----------|---------|---------------------------------------------------|
| `name`           | String   | —       | Skill name                                        |
| `description`    | String   | —       | Skill description                                 |
| `load`           | boolean  | `true`  | Whether preloaded into conversations by default   |
| `content`        | String   | —       | Skill content text (supports `classpath:` prefix to read from files) |

**Example Configuration**:

```yaml
spring:
  ai:
    loom:
      agent:
        defaultSystem: |
          You are a professional technical support assistant...
        rag:
          similarityThreshold: 0.3
          topK: 5
          enabledKeyword: true
          enabledSummary: true
        jvector:
          indexPath: /data/jvector-index
          m: 32
          efConstruction: 200
          efSearch: 50
        skills:
          - name: email_writer
            description: Professional email writing assistant
            load: false
            content: "classpath:skills/email-writer.md"
```

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

| Property                   | Type    | Default | Description                                                                 |
|----------------------------|---------|---------|-----------------------------------------------------------------------------|
| `git.enabled`              | boolean | `false` | Whether to enable Git tool (IGitTool); set to `true` to activate            |
| `git.gitUsername`          | String  | —       | Username for HTTP(S) git authentication (clone/pull/push)                   |
| `git.gitToken`             | String  | —       | Token/password for HTTP(S) git authentication                               |

**Example Configuration**:

```yaml
spring:
  ai:
    loom:
      agent:
        git:
          enabled: true
          gitUsername: your-git-username
          gitToken: your-git-token
```

> Git credentials can also be passed per-request via `ToolContext` (`gitUsername` / `gitToken` keys), which override the configured defaults.

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

**Default behavior**: `isAutoLogin()` always returns `true`; `login()` always succeeds; session tokens are stored in Spring Cache (default Caffeine).

**Customization Example**:

```java
@Bean
public IUser customUser(Cache sessionCache) {
    return new IUser() {
        @Override public Boolean isAutoLogin() { return false; }
        @Override public UserResponseRecord login(UserRequestRecord request) {
            // Integrate real LDAP/OAuth/JWT authentication
            // Return new UserResponseRecord(token, nickname)
        }
        @Override public String createToken(String username) {
            String token = UUID.randomUUID().toString();
            sessionCache.put(token, username);
            return token;
        }
        @Override public boolean validateToken(String token) {
            return sessionCache.get(token) != null;
        }
        @Override public void invalidateToken(String token) {
            sessionCache.evict(token);
        }
        @Override public String getUsernameByToken(String token) {
            var wrapper = sessionCache.get(token);
            return wrapper != null ? (String) wrapper.get() : null;
        }
        @Override public String getUsernameByAuthentication(String authentication) {
            // Parse real JWT or OAuth token
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
| **Controls**    | Streaming chat: user/session management, RAG advisor, MCP tool injection, skill tool injection, image file handling |

**Default behavior**: Assembles `ChatClient`, optionally adding `RetrievalAugmentationAdvisor`, `IMcp` tools, all `IEmbedTool` sub-tools (time, skill, file, git), and user session management.

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
| **Controls**    | File upload (plain/knowledge-base), file deletion (knowledge-base-aware), bulk knowledge-base file deletion |

**Default behavior**: Chat-uploaded files saved to `{fileBasePath}/{username}/` (e.g., `.local/file/username/`), knowledge-base files to `{knowledgeBasePath}/{username}/{knowledgeId}/` (e.g., `.local/knowledge/username/{knowledgeId}/`). Duplicate names get a numeric suffix: `file.txt` → `file(1).txt` → `file(2).txt`. Documents are parsed via `IDocumentRead` and stored in `VectorStore`.

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
| **Controls**    | Skill list, save (create/update), get by name, remove |

**Default behavior**: Skills stored in an in-memory `List<SkillDocument>`, initialized from `LoomAgentProperties.getSkills()`. `save()` prevents overwriting built-in skills with `source="embed"`.

**Common use case**: Persist to a database, load skills from a remote API.

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

### 2.11 `IEmbedTool` — Embed Tools (Time / Skill / File / Git)

`IEmbedTool` is an aggregate marker interface. Four sub-interfaces each provide independent `@Tool` methods to the LLM. Each sub-tool can be replaced independently via `@ConditionalOnMissingBean`.

#### `ITimeTool` — Time Tools

| Item            | Details                                                                               |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool`                                    |
| **Default**     | `DefaultTimeTool`                                                                     |
| **Override**    | Custom `@Bean ITimeTool`                                                              |
| **Controls**    | `@Tool` methods: `getCurrentTime` (get current time by timezone) and `convertTime` (convert between timezones) |

#### `ISkillTool` — Skill Tools

| Item            | Details                                                                               |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.tool.skill.ISkillTool`                                  |
| **Default**     | `DefaultSkillTool`                                                                    |
| **Override**    | Custom `@Bean ISkillTool`                                                             |
| **Controls**    | `@Tool` methods: `skillContents` (list all skills) and `getSkill` (get skill details) |

#### `IFileTool` — File Tools

| Item            | Details                                                                               |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.tool.file.IFileTool`                                    |
| **Default**     | `DefaultFileTool`                                                                     |
| **Override**    | Custom `@Bean IFileTool`                                                              |
| **Controls**    | `@Tool` methods: `readTextFile`, `readMediaFile`, `readMultipleFiles`, `writeFile`, `editFile`, `createDirectory`, `moveFile`, `searchFiles`, `listAllowedDirectories`, `listDirectory`, `listDirectoryWithSizes`, `directoryTree`, `getFileInfo`, `downloadFileUrl`, `viewFileUrl`. All path-based operations use `{fileBasePath}/{username}/` as root; `downloadFileUrl`/`viewFileUrl` auto-create temporary `file_info` records (`usage="temp"`) for bridge access. |

#### `IGitTool` — Git Tools

| Item            | Details                                                                               |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.tool.git.IGitTool`                                      |
| **Default**     | `DefaultGitTool` (based on Eclipse JGit 7.6.0)                                        |
| **Override**    | Custom `@Bean IGitTool`                                                               |
| **Condition**   | `@ConditionalOnProperty(name = "spring.ai.loom.agent.git.enabled", havingValue = "true")` — disabled by default |
| **Controls**    | 31 `@Tool` methods: `gitInit`, `gitClone`, `gitStatus`, `gitAdd`, `gitCommit`, `gitDiff`, `gitLog`, `gitBranch`, `gitCheckout`, `gitPull`, `gitPush`, `gitFetch`, `gitMerge`, `gitRebase`, `gitReset`, `gitStash`, `gitTag`, `gitRemote`, `gitBlame`, `gitShow`, `gitReflog`, `gitClean`, `gitCherryPick`, `gitWorktree`, `gitSetWorkingDir`, `gitClearWorkingDir`, `gitChangelogAnalyze`, `gitWrapupInstructions` |

Git repositories are stored under `{fileBasePath}/{username}/` (for clone/init by repo name) or absolute paths (for `gitSetWorkingDir`). All file operations go through JGit's `Repository` object.

**Customization Example** (replace only the file tool, keep default time and skill tools):

```java
@Bean
public IFileTool customFileTool(IFile file, LoomAgentProperties properties) {
    return new MyCustomFileTool(file, properties.getFileBasePath());
}
// DefaultTimeTool and DefaultSkillTool remain active
```

#### `ITerminalTool` — Terminal/Process Tools

| Item            | Details                                                                               |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.tool.terminal.ITerminalTool`                            |
| **Default**     | `DefaultTerminalTool` (optional pty4j support for pseudo-terminal REPL interaction)   |
| **Override**    | Custom `@Bean ITerminalTool`                                                          |
| **Controls**    | 9 `@Tool` methods: `startProcess` (start shell/REPL session), `interactWithProcess` (send input to REPL), `readProcessOutput` (read output in new/tail/absolute mode), `forceTerminate` (kill session), `listSessions` (list user sessions), `getProcessInfo` (session details with full output), `sendSignal` (Ctrl+C/EOF/quit signals), `listProcesses` (list OS processes with pagination), `killProcess` (terminate OS process by PID) |

**PTY Support**: When `pty4j` is on the classpath, REPL mode launches a pseudo-terminal for full terminal interaction (Ctrl+C signals, etc.). Without pty4j, REPL falls back to ProcessBuilder (limited interaction). Shell mode always works without pty4j.

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
| **Type**        | `FlywayConfigurationCustomizer`                                                 |
| **Override**    | Custom `@Bean FlywayConfigurationCustomizer`                                    |
| **Default**     | Migration scripts at `classpath:db/loom`, history table `loomAgent_schema_history`, baseline-on-migrate |

---

## 4. MCP Customization

### 4.1 Sync/Async Mode Switch

| Property                                      | Value         | Effect                              |
|-----------------------------------------------|---------------|-------------------------------------|
| `spring.ai.mcp.client.stdio` not set or not `ASYNC` | Uses `SyncMcp`  | Based on `McpSyncClient`            |
| `spring.ai.mcp.client.stdio=ASYNC`            | Uses `ASyncMcp` | Based on `McpAsyncClient`           |

### 4.2 Custom MCP Implementation

Beyond configuring `mcps[]`, you can fully replace the `IMcp` interface to customize:

- MCP server discovery logic
- Tool callback interception/enhancement
- Dynamic tool registration

---

## 5. Skill Customization

### 5.1 Skill Content Injection

Two ways to configure skill content in YAML:

```yaml
# Option 1: Inline text
skills:
  - name: greeting
    content: |
      You are a greeting assistant. When the user says "hello", reply "Hello!"

# Option 2: Read from classpath
skills:
  - name: email_writer
    content: "classpath:skills/email-writer.md"
```

### 5.2 Skill Properties

Each skill has four configurable fields:

| Field         | Type    | Default | Description                                               |
|---------------|---------|---------|-----------------------------------------------------------|
| `name`        | String  | —       | Skill name (unique identifier)                            |
| `description` | String  | —       | Skill description (used by LLM for matching)              |
| `load`        | boolean | `true`  | Whether preloaded into conversations by default           |
| `content`     | String  | —       | Skill content text or `classpath:` prefix to load from file |

> **Note**: Skills no longer support `tools` or `params` fields in YAML configuration. MCP tool binding and skill parameters are managed at runtime via the Skill Library API (`PUT /spring/ai/loom/skill`).

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
| `pty4j` on classpath                 | Optional dependency          | Enables PTY (pseudo-terminal) mode for REPL sessions in `ITerminalTool`; without it, REPL falls back to ProcessBuilder |

### 8.1 Quick Feature Disablement Guide

| To Disable         | Action                                                                 |
|--------------------|------------------------------------------------------------------------|
| Entire chat        | Set `spring.ai.chat.ui.init=false`                                     |
| RAG / Knowledge Base | Do not add any `VectorStore` or `EmbeddingModel` Starter               |
| MCP functionality  | Set `spring.ai.mcp.client.enabled=false`                               |
| Git tool           | Do not set `spring.ai.loom.agent.git.enabled=true` (default is disabled) |
| Terminal tool      | Provide a no-op `@Bean ITerminalTool` override                         |
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
