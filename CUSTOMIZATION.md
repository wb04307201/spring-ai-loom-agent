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
│   ├── tool/          IEmbedTool                   # Skill embedding
│   ├── document/      IDocumentRead / IFileDocument # Document parsing
│   └── model/         *Record / LoomAgentProperties # Models & config
├── spring-ai-loom-agent-spring-boot-autoconfigure/  # Auto-configuration
│   └── LoomAgentConfiguration.java                # Core configuration class
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
| `defaultPreload` | boolean  | `true`  | Whether preloaded into conversations by default   |
| `tools`          | String[] | —       | List of associated tool names                     |
| `content`        | String   | —       | Skill content text (supports `classpath:` prefix to read from files) |
| `params[]`       | Array    | —       | Parameter definitions (see below)                 |

**SkillParamProperty**:

| Field             | Type    | Default | Description                                    |
|-------------------|---------|---------|------------------------------------------------|
| `name`            | String  | —       | Parameter identifier                           |
| `label`           | String  | —       | Parameter display label                        |
| `type`            | Enum    | —       | Type: `TEXT`, `SELECT`, `TEXT_AREA`            |
| `required`        | boolean | —       | Whether required                               |
| `defaultValue`    | String  | —       | Default value                                  |
| `placeholder`     | String  | —       | Placeholder hint text                          |
| `options[].label` | String  | —       | Dropdown option label (`type=SELECT` only)     |
| `options[].value` | String  | —       | Dropdown option value (`type=SELECT` only)     |

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
            defaultPreload: false
            content: "classpath:skills/email-writer.md"
            params:
              - name: recipient
                label: Recipient
                type: TEXT
                required: true
                placeholder: "Enter recipient email"
              - name: tone
                label: Tone
                type: SELECT
                defaultValue: formal
                options:
                  - { label: Formal, value: formal }
                  - { label: Friendly, value: friendly }
```

---

## 2. Bean Override (Interface Replacement)

The project uses the `@ConditionalOnMissingBean` pattern. All interfaces support custom implementation replacement — simply register a bean of the same type in the Spring container.

### 2.1 `IUser` — User Authentication

| Item            | Details                                                                                                                                                               |
|-----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.user.IUser`                                                                                                                             |
| **Default**     | `DefaultUser`                                                                                                                                                         |
| **Override**    | Custom `@Bean IUser`                                                                                                                                                  |
| **Properties**  | `spring.ai.loom.agent.user.username` (default `username`), `spring.ai.loom.agent.user.nickname` (default `User`), `spring.ai.loom.agent.user.authentication` (default `loom-agent-auth`) |
| **Controls**    | Auto-login check, user login validation, auth token resolution                                                                                                         |

**Default behavior**: `isAutoLogin()` always returns `true`; `login()` always succeeds; `getUsernameByAuthentication()` only accepts the preconfigured token.

**Customization Example**:

```java
@Bean
public IUser customUser() {
    return new IUser() {
        @Override public Boolean isAutoLogin() { return false; }
        @Override public UserResponseRecord login(UserRequestRecord request) {
            // Integrate real LDAP/OAuth/JWT authentication
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
| **Controls**    | Streaming chat: user/session management, RAG advisor, MCP tool injection, skill tool injection, image file handling |

**Default behavior**: Assembles `ChatClient`, optionally adding `RetrievalAugmentationAdvisor`, `IMcp` tools, `IEmbedTool` skill tools, and user session management.

**Customization Example**:

```java
@Bean
@ConditionalOnMissingBean(IChat.class)
public IChat customChat(
        ChatClient chatClient,
        Optional<RetrievalAugmentationAdvisor> ragAdvisor,
        IMcp mcp,
        IEmbedTool embedTool,
        IUserConversation userConversation,
        IUser user,
        IFile file) {
    return new MyCustomChat(chatClient, ragAdvisor, mcp, embedTool, userConversation, user, file);
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

**Default behavior**: Files saved locally to `.local/file/{username}/{fileId}/{fileName}`, parsed via `IDocumentRead`, and stored in `VectorStore`.

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

### 2.11 `IEmbedTool` — Skill Embed Tool

| Item            | Details                                                                               |
|-----------------|---------------------------------------------------------------------------------------|
| **Interface**   | `cn.wubo.spring.ai.loom.agent.tool.IEmbedTool`                                        |
| **Default**     | `DefaultEmbedTool`                                                                    |
| **Override**    | Custom `@Bean IEmbedTool`                                                             |
| **Controls**    | Two `@Tool` methods exposed to the LLM: `skillContents` (get skill directory) and `getSkill` (get skill details) |

### 2.12 `AuthenticationFilter` — Authentication Filter

| Item            | Details                                                  |
|-----------------|----------------------------------------------------------|
| **Type**        | `cn.wubo.spring.ai.loom.agent.user.AuthenticationFilter` |
| **Override**    | Custom Servlet Filter, or override the `IUser` bean       |
| **Controls**    | Intercepts `/spring/ai/loom/*` requests, validates `Authorization` header |

**Whitelist paths** (no authentication required):

- `/spring/ai/loom/user/login`
- `/spring/ai/loom/user/isAutoLogin`
- `/spring/ai/loom`
- `/spring/ai/loom/index.html`
- `/spring/ai/loom/app.js`
- `/spring/ai/loom/style.css`

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

### 5.2 Skill Parameter Types

Three UI control types are supported:

| Type        | Description      | Use Case                         |
|-------------|------------------|----------------------------------|
| `TEXT`      | Single-line text | Short text (name, email, etc.)   |
| `TEXT_AREA` | Multi-line text  | Long text (description, content) |
| `SELECT`    | Dropdown         | Fixed options (tone, format, language) |

### 5.3 Skill-Tool Association

Link MCP tools via the `tools` field. When a skill is activated, the associated tools are automatically enabled:

```yaml
skills:
  - name: data_analysis
    description: Data analysis assistant
    tools:
      - python_interpreter
      - chart_generator
```

---

## 6. Database Schema Customization

### 6.1 Default Tables

| Table               | Purpose                        | Primary Key                     |
|---------------------|--------------------------------|---------------------------------|
| `knowledge`         | Knowledge base metadata        | `id`                            |
| `knowledge_file`    | Knowledge base — file mapping  | `(knowledge_id, file_id)`       |
| `file_info`         | File metadata and storage path | `id`                            |
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
| Allowed image upload types  | `app.js`     | JPG, PNG, GIF, WebP, BMP |
| Max image upload size       | `app.js`     | 10 MB                    |
| SSE timeout                 | `app.js`     | `0` (no timeout)         |
| LocalStorage Token Key      | `app.js`     | `loomAgentToken`         |
| LocalStorage Nickname Key   | `app.js`     | `loomAgentNickname`      |
| UI modules                  | `index.html` | Knowledge Space, MCP Services, Skill Library |

### 7.3 Override Method

Place same-named static resources in your own project to override the defaults, or use Spring static resource configuration.

---

## 8. Conditional Switches Summary

| Condition                            | Configuration/Dependency     | Impact                                                                                           |
|--------------------------------------|------------------------------|--------------------------------------------------------------------------------------------------|
| `spring.ai.chat.ui.init=false`       | application.yml              | `ChatClient` is not created; the entire chat pipeline is unavailable                              |
| `spring.ai.mcp.client.stdio=ASYNC`   | application.yml              | Switches to `ASyncMcp` (async MCP client)                                                        |
| No `VectorStore` bean provided       | Do not add any VectorStore Starter | `IDocumentRead`, `RetrievalAugmentationAdvisor`, and `loomAgentKnowledgeRouter` are not created; knowledge base features unavailable |
| No `EmbeddingModel` bean provided    | Do not add EmbeddingModel Starter | `JVectorStore` is not created; vector storage unavailable                                        |
| Custom bean of the same type         | Java `@Bean` configuration   | The corresponding `@ConditionalOnMissingBean` bean will not be created                           |

### 8.1 Quick Feature Disablement Guide

| To Disable         | Action                                                                 |
|--------------------|------------------------------------------------------------------------|
| Entire chat        | Set `spring.ai.chat.ui.init=false`                                     |
| RAG / Knowledge Base | Do not add any `VectorStore` or `EmbeddingModel` Starter               |
| MCP functionality  | Do not configure `spring.ai.mcp.*` or provide a custom `IMcp` returning empty lists |
| Auth filter        | Override `IUser.isAutoLogin()` to return `true`, or provide a custom Filter |
| Auto-login         | Override `IUser.isAutoLogin()` to return `false`                       |
