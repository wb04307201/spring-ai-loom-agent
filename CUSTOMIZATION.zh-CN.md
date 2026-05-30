# Spring AI LoomAgent 自定义能力总览

> 本文档汇总了项目中所有支持自定义/扩展的配置项、接口、Bean 覆盖点和 UI 定制能力。

---

## 目录树

```
spring-ai-loom-agent/
├── spring-ai-loom-agent/                          # 核心库
│   ├── chat/          IChat / DefaultChat          # 流式对话
│   ├── knowledge/     IKnowledge / DefaultKnowledge # 知识库CRUD
│   ├── mcp/           IMcp / SyncMcp / ASyncMcp    # MCP客户端
│   ├── skill/         ISkillStorage                # 技能存储
│   ├── file/          IFile / IUpload              # 文件存储、上传与下载
│   ├── user/          IUser / AuthenticationFilter  # 认证鉴权
│   ├── vectorstore/   JVectorStore                 # 默认向量存储
│   ├── tool/          IEmbedTool                   # 技能嵌入工具
│   ├── document/      IDocumentRead / IFileDocument # 文档解析
│   └── model/         *Record / LoomAgentProperties # 模型与配置
├── spring-ai-loom-agent-spring-boot-autoconfigure/  # 自动配置
│   └── LoomAgentConfiguration.java                # 核心装配类
├── spring-ai-loom-agent-spring-boot-starter/        # Starter空JAR
│   └── pom.xml                                    # 仅传递依赖
└── spring-ai-loom-agent-test/                       # 测试应用
    ├── LoomAgentTestApplication.java
    └── application.yml / mcp-servers.json
```

## 目录

- [1. 配置属性自定义](#1-配置属性自定义)
- [2. Bean 覆盖（接口替换）](#2-bean-覆盖接口替换)
- [3. 基础设施替换](#3-基础设施替换)
- [4. MCP 自定义](#4-mcp-自定义)
- [5. 技能自定义](#5-技能自定义)
- [6. 数据库 Schema 自定义](#6-数据库-schema-自定义)
- [7. UI 前端自定义](#7-ui-前端自定义)
- [8. 条件开关汇总](#8-条件开关汇总)

---

## 1. 配置属性自定义

所有配置前缀：`spring.ai.loom.agent`

### 1.1 基础配置

| 属性                                   | 类型      | 默认值            | 说明                                    |
|--------------------------------------|---------|----------------|---------------------------------------|
| `spring.ai.loom.agent.defaultSystem` | String  | 技能发现工作流 Prompt | 默认系统提示词，控制 AI 的核心行为模式                 |
| `spring.ai.loom.agent.init`          | boolean | `true`         | 是否初始化 ChatClient，设为 `false` 则不创建聊天客户端 |

### 1.2 RAG 配置 (`rag.*`)

| 属性                                      | 类型      | 默认值     | 说明                                            |
|-----------------------------------------|---------|---------|-----------------------------------------------|
| `rag.similarityThreshold`               | double  | `0.0`   | 向量检索相似度阈值，低于此值的文档将被过滤                         |
| `rag.topK`                              | int     | `4`     | 检索返回的文档数量                                     |
| `rag.defaultPromptTemplate`             | String  | (内置模板)  | RAG 有上下文时的提示词模板，支持 `{context}` 和 `{query}` 变量 |
| `rag.defaultEmptyContextPromptTemplate` | String  | (内置模板)  | RAG 无上下文时的默认回复模板                              |
| `rag.enabledKeyword`                    | boolean | `false` | 是否启用关键词元数据增强                                  |
| `rag.enabledSummary`                    | boolean | `false` | 是否启用摘要元数据增强                                   |

### 1.3 JVector 向量库配置 (`jvector.*`)

| 属性                       | 类型     | 默认值                    | 说明                               |
|--------------------------|--------|------------------------|----------------------------------|
| `jvector.indexPath`      | String | `.local/jvector-index` | 向量索引持久化路径                        |
| `jvector.m`              | int    | `16`                   | HNSW 图参数 M（控制分支因子，越大索引质量越高但构建越慢） |
| `jvector.efConstruction` | int    | `100`                  | HNSW 构建时的搜索宽度（影响构建质量和速度）         |
| `jvector.efSearch`       | int    | `10`                   | HNSW 搜索时的搜索宽度（越大搜索越精确但越慢）        |

> 底层还支持 `similarityFunction`（COSINE / DOT_PRODUCT），但未暴露为配置属性，需自定义 `VectorStore` Bean 来修改。

### 1.4 MCP 服务器配置 (`mcps[]`)

YAML 数组配置，每个 MCP 服务器包含：

| 字段                    | 类型      | 默认值    | 说明           |
|-----------------------|---------|--------|--------------|
| `name`                | String  | —      | MCP 服务器标识名   |
| `title`               | String  | —      | 中文显示名称       |
| `description`         | String  | —      | 描述信息         |
| `defaultSelected`     | boolean | `true` | 是否在 UI 中默认选中 |
| `tools[].name`        | String  | —      | 工具名称         |
| `tools[].description` | String  | —      | 工具描述         |

### 1.5 技能配置 (`skills[]`)

YAML 数组配置，每个技能包含：

| 字段               | 类型       | 默认值    | 说明                             |
|------------------|----------|--------|--------------------------------|
| `name`           | String   | —      | 技能名称                           |
| `description`    | String   | —      | 技能描述                           |
| `defaultPreload` | boolean  | `true` | 是否默认预加载到对话中                    |
| `tools`          | String[] | —      | 关联的工具名称列表                      |
| `content`        | String   | —      | 技能内容文本（支持 `classpath:` 前缀读取文件） |
| `params[]`       | 数组       | —      | 参数定义（见下方）                      |

**SkillParamProperty**:

| 字段                | 类型      | 默认值 | 说明                             |
|-------------------|---------|-----|--------------------------------|
| `name`            | String  | —   | 参数标识名                          |
| `label`           | String  | —   | 参数显示名称                         |
| `type`            | Enum    | —   | 类型：`TEXT`、`SELECT`、`TEXT_AREA` |
| `required`        | boolean | —   | 是否必填                           |
| `defaultValue`    | String  | —   | 默认值                            |
| `placeholder`     | String  | —   | 占位符提示文本                        |
| `options[].label` | String  | —   | 下拉选项显示文本（仅 `type=SELECT`）      |
| `options[].value` | String  | —   | 下拉选项值（仅 `type=SELECT`）         |

**示例配置**:

```yaml
spring:
  ai:
    loom:
      agent:
        defaultSystem: |
          你是一个专业的技术支持助手...
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
            description: 专业邮件撰写助手
            defaultPreload: false
            content: "classpath:skills/email-writer.md"
            params:
              - name: recipient
                label: 收件人
                type: TEXT
                required: true
                placeholder: "请输入收件人邮箱"
              - name: tone
                label: 语气
                type: SELECT
                defaultValue: formal
                options:
                  - { label: 正式, value: formal }
                  - { label: 友好, value: friendly }
```

---

## 2. Bean 覆盖（接口替换）

项目采用 `@ConditionalOnMissingBean` 模式，所有接口均支持自定义实现替换。只需在 Spring 容器中注册同类型的 Bean 即可。

### 2.1 `IUser` — 用户认证

| 项目       | 内容                                                                                                                                                                 |
|----------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **接口**   | `cn.wubo.spring.ai.loom.agent.user.IUser`                                                                                                                          |
| **默认实现** | `DefaultUser`                                                                                                                                                      |
| **覆盖方式** | 自定义 `@Bean IUser`                                                                                                                                                  |
| **相关属性** | `spring.ai.loom.agent.user.username`（默认 `username`）、`spring.ai.loom.agent.user.nickname`（默认 `用户`）、`spring.ai.loom.agent.user.authentication`（默认 `loom-agent-auth`） |
| **控制内容** | 自动登录判断、用户登录验证、认证令牌解析                                                                                                                                               |

**默认行为**: `isAutoLogin()` 始终返回 `true`；`login()` 始终返回成功；`getUsernameByAuthentication()` 只接受配置中预设的令牌。

**自定义示例**:

```java

@Bean
public IUser customUser() {
    return new IUser() {
        @Override
        public Boolean isAutoLogin() {
            return false;
        }

        @Override
        public UserResponseRecord login(UserRequestRecord request) {
            // 接入真实的 LDAP/OAuth/JWT 认证
        }

        @Override
        public String getUsernameByAuthentication(String authentication) {
            // 解析真实的 JWT 或 OAuth token
        }
    };
}
```

### 2.2 `IUserConversation` — 用户会话管理

| 项目       | 内容                                                    |
|----------|-------------------------------------------------------|
| **接口**   | `cn.wubo.spring.ai.loom.agent.user.IUserConversation` |
| **默认实现** | `DefaultUserConversation`                             |
| **覆盖方式** | 自定义 `@Bean IUserConversation`                         |
| **控制内容** | 会话列表、会话存在性检查、会话创建/删除（含清理聊天记忆）                         |

**默认行为**: 通过 JdbcTemplate 操作 `user_conversation` 表；删除会话时同步清除 `ChatMemoryRepository`。

### 2.3 `IChat` — 核心聊天流水线

| 项目       | 内容                                           |
|----------|----------------------------------------------|
| **接口**   | `cn.wubo.spring.ai.loom.agent.chat.IChat`    |
| **默认实现** | `DefaultChat`                                |
| **覆盖方式** | 自定义 `@Bean IChat`                            |
| **控制内容** | 流式对话处理：用户/会话管理、RAG 顾问、MCP 工具注入、技能工具注入、图片/文档处理、toolContext 跨线程上下文传递 |

**默认行为**: 组装 `ChatClient`，可选加入 `RetrievalAugmentationAdvisor`、`IMcp` 工具、`IEmbedTool` 技能工具、用户会话管理等。文档类文件（PDF/DOCX/XLSX/PPTX/MD 等）通过 Apache Tika 提取文本后以 System Prompt 注入，图片作为 Media 类型传入模型。

**自定义示例**:

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

### 2.4 `IFile` — 文件元数据

| 项目       | 内容                                        |
|----------|-------------------------------------------|
| **接口**   | `cn.wubo.spring.ai.loom.agent.file.IFile` |
| **默认实现** | `DefaultFile`                             |
| **覆盖方式** | 自定义 `@Bean IFile`                         |
| **控制内容** | 文件元数据 CRUD、通过 ID 获取 Spring `Resource`     |

**默认行为**: 元数据存储在 `file_info` 表；`getResourceById()` 返回本地文件系统的 `FileSystemResource`。

**常见自定义场景**: 替换为 S3、OSS、MinIO 等对象存储。

### 2.5 `IUpload` — 文件上传/下载流水线

| 项目       | 内容                                          |
|----------|---------------------------------------------|
| **接口**   | `cn.wubo.spring.ai.loom.agent.file.IUpload` |
| **默认实现** | `DefaultUpload`                             |
| **覆盖方式** | 自定义 `@Bean IUpload`                         |
| **控制内容** | 文件上传（普通/知识库）、文件下载、文件删除（关联知识库）、知识库文件批量删除    |

**默认行为**: 文件保存到本地 `.local/file/{username}/{fileId}/{fileName}`，调用 `IDocumentRead` 解析文档（PDF/DOCX/XLSX/PPTX/MD 等），文本内容通过 System Prompt 注入对话。

**常见自定义场景**: 上传到云存储（S3/OSS）、接入第三方 OCR、异步文档解析等。

### 2.6 `IDocumentRead` — 文档解析与处理

| 项目       | 内容                                                    |
|----------|-------------------------------------------------------|
| **接口**   | `cn.wubo.spring.ai.loom.agent.document.IDocumentRead` |
| **默认实现** | `DefaultDocumentRead`                                 |
| **覆盖方式** | 自定义 `@Bean IDocumentRead`                             |
| **生效条件** | 仅当存在 `VectorStore` Bean 时创建                           |
| **控制内容** | 文件读取（Tika 解析）、文本切分、关键词元数据增强、摘要元数据增强                   |

**默认行为**: 使用 Apache Tika 解析多种文档格式（PDF/DOCX/XLSX/PPTX/MD/TXT 等），按 `PagePdfParser.DEFAULT_MAX_CHARS` 切分文本，可选择性地注入关键词和摘要元数据。

### 2.7 `IFileDocument` — 文件-文档关联

| 项目       | 内容                                                    |
|----------|-------------------------------------------------------|
| **接口**   | `cn.wubo.spring.ai.loom.agent.document.IFileDocument` |
| **默认实现** | `DefaultFileDocument`                                 |
| **覆盖方式** | 自定义 `@Bean IFileDocument`                             |
| **控制内容** | `file_document` 关联表的 CRUD，维护文件与向量文档 ID 的映射            |

### 2.8 `IKnowledge` — 知识库管理

| 项目       | 内容                                                  |
|----------|-----------------------------------------------------|
| **接口**   | `cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge` |
| **默认实现** | `DefaultKnowledge`                                  |
| **覆盖方式** | 自定义 `@Bean IKnowledge`                              |
| **控制内容** | 知识库列表、创建、删除（含级联清理关联文件和向量）                           |

**默认行为**: 通过 JdbcTemplate 操作 `knowledge` 和 `knowledge_file` 表。

### 2.9 `ISkillStorage` — 技能存储

| 项目       | 内容                                                 |
|----------|----------------------------------------------------|
| **接口**   | `cn.wubo.spring.ai.loom.agent.skill.ISkillStorage` |
| **默认实现** | `DefaultSkillStorage`                              |
| **覆盖方式** | 自定义 `@Bean ISkillStorage`                          |
| **控制内容** | 技能列表、保存（新增/更新）、按名称查询、删除                            |

**默认行为**: 技能数据存储在内存 `List<SkillDocument>` 中，初始数据来源为 `LoomAgentProperties.getSkills()`。`save()`
方法防止覆盖 `source="embed"` 的内置技能。

**常见自定义场景**: 持久化到数据库、从远程 API 加载技能等。

### 2.10 `IMcp` — MCP 工具提供者

| 项目       | 内容                                        |
|----------|-------------------------------------------|
| **接口**   | `cn.wubo.spring.ai.loom.agent.mcp.IMcp`   |
| **默认实现** | `SyncMcp` / `ASyncMcp`                    |
| **覆盖方式** | 自定义 `@Bean IMcp`                          |
| **控制内容** | MCP 服务器列表、选中 MCP 的 `ToolCallbackProvider` |

**变体选择**:

- 默认使用 `SyncMcp`（基于 `McpSyncClient`）
- 设置 `spring.ai.mcp.client.stdio=ASYNC` 时切换为 `ASyncMcp`（基于 `McpAsyncClient`）

### 2.11 `IEmbedTool` — 技能嵌入工具

| 项目       | 内容                                                                 |
|----------|--------------------------------------------------------------------|
| **接口**   | `cn.wubo.spring.ai.loom.agent.tool.IEmbedTool`                     |
| **默认实现** | `DefaultEmbedTool`                                                 |
| **覆盖方式** | 自定义 `@Bean IEmbedTool`                                             |
| **控制内容** | 暴露给 LLM 的 `@Tool` 方法：`skillContents`（获取技能目录）、`getSkill`（获取技能详情）、`downloadFileUrl`（生成文件下载链接）、`addFile`（通过路径注册文件） |

### 2.12 `AuthenticationFilter` — 认证过滤器

| 项目       | 内容                                                       |
|----------|----------------------------------------------------------|
| **类型**   | `cn.wubo.spring.ai.loom.agent.user.AuthenticationFilter` |
| **覆盖方式** | 自定义 Servlet Filter，或覆盖 `IUser` Bean                      |
| **控制内容** | 拦截 `/spring/ai/loom/*` 路径的请求，验证 `Authorization` 请求头      |

**白名单路径**（无需认证）:

- `/spring/ai/loom/user/login`
- `/spring/ai/loom/user/isAutoLogin`
- `/spring/ai/loom`
- `/spring/ai/loom/index.html`
- `/spring/ai/loom/app.js`
- `/spring/ai/loom/style.css`

---

## 3. 基础设施替换

### 3.1 ChatClient

| 项目          | 内容                                                                                                     |
|-------------|--------------------------------------------------------------------------------------------------------|
| **Bean 名称** | `ChatClient`                                                                                           |
| **创建条件**    | `@ConditionalOnProperty(name = "spring.ai.chat.ui.init", havingValue = "true", matchIfMissing = true)` |
| **覆盖方式**    | 自定义 `@Bean ChatClient`，或设置 `spring.ai.chat.ui.init=false` 阻止创建                                         |
| **默认配置**    | 使用 `defaultSystem` 作为系统提示词，挂载 `ChatMemory` 顾问和日志顾问                                                     |

### 3.2 ChatMemory

| 项目          | 内容                                                                        |
|-------------|---------------------------------------------------------------------------|
| **Bean 名称** | `jdbChatMemory`                                                           |
| **默认实现**    | `MessageWindowChatMemory`，底层使用 `ChatMemoryRepository`                     |
| **覆盖方式**    | 自定义 `@Bean ChatMemory`                                                    |
| **可替换策略**   | Spring AI 提供多种聊天记忆策略：`BufferWindowChatMemory`、`ConcurrentMapChatMemory` 等 |

### 3.3 ChatMemoryRepository（持久化后端）

Spring AI 支持多种持久化后端，通过引入对应依赖自动配置：

| 后端        | 依赖                           | 说明                |
|-----------|------------------------------|-------------------|
| JDBC（默认）  | `spring-ai-jdbc-memory`      | 关系型数据库，当前项目使用的方案  |
| Redis     | `spring-ai-redis-memory`     | Redis 存储          |
| MongoDB   | `spring-ai-mongodb-memory`   | MongoDB 存储        |
| Cassandra | `spring-ai-cassandra-memory` | Cassandra 存储      |
| CosmosDB  | `spring-ai-cosmosdb-memory`  | Azure CosmosDB 存储 |
| Neo4j     | `spring-ai-neo4j-memory`     | Neo4j 图数据库存储      |

### 3.4 VectorStore（向量存储）

JVector 是项目的回退方案。引入任何 Spring AI VectorStore Starter 即可自动替换：

| 向量库               | 依赖 Starter                      | 说明            |
|-------------------|---------------------------------|---------------|
| **JVector（默认回退）** | 内置                              | 本地文件持久化，零外部依赖 |
| Qdrant            | `spring-ai-qdrant-store`        | 测试模块使用        |
| Milvus            | `spring-ai-milvus-store`        | 生产常用          |
| Redis             | `spring-ai-redis-store`         | Redis Vector  |
| Chroma            | `spring-ai-chroma-store`        | 轻量级本地方案       |
| Elasticsearch     | `spring-ai-elasticsearch-store` | ELK 生态        |
| Pinecone          | `spring-ai-pinecone-store`      | 云服务           |
| Weaviate          | `spring-ai-weaviate-store`      | 开源向量数据库       |

**自定义示例**（以 Qdrant 为例）：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-qdrant</artifactId>
</dependency>
```

引入后 `JVectorStore` 将自动跳过，无需额外代码。

### 3.5 ChatModel（AI 模型提供商）

Spring AI 支持多种模型，通过配置对应 Starter 和 API Key 自动切换：

| 模型              | 配置示例                                               |
|-----------------|----------------------------------------------------|
| DashScope（通义千问） | `spring.ai.dashscope.api-key=...`                  |
| OpenAI          | `spring.ai.openai.api-key=...`                     |
| Ollama          | `spring.ai.ollama.base-url=http://localhost:11434` |
| Anthropic       | `spring.ai.anthropic.api-key=...`                  |
| Azure OpenAI    | `spring.ai.azure.openai.api-key=...`               |

### 3.6 RetrievalAugmentationAdvisor

| 项目       | 内容                                                      |
|----------|---------------------------------------------------------|
| **类型**   | `RetrievalAugmentationAdvisor`                          |
| **创建条件** | 仅当存在 `VectorStore` Bean 时创建                             |
| **覆盖方式** | 自定义 `@Bean RetrievalAugmentationAdvisor`                |
| **可配置项** | `documentRetriever`（相似度阈值、topK）、`queryAugmenter`（提示词模板） |

### 3.7 Flyway（数据库迁移）

| 项目       | 内容                                                               |
|----------|------------------------------------------------------------------|
| **类型**   | `FlywayConfigurationCustomizer`                                  |
| **覆盖方式** | 自定义 `@Bean FlywayConfigurationCustomizer`                        |
| **默认配置** | 迁移脚本路径 `classpath:db/loom`，历史表 `loomAgent_schema_history`，自动基线迁移 |

---

## 4. MCP 自定义

### 4.1 MCP 同步/异步模式切换

| 属性                                          | 值             | 效果                  |
|---------------------------------------------|---------------|---------------------|
| `spring.ai.mcp.client.stdio` 未设置或不为 `ASYNC` | 使用 `SyncMcp`  | 基于 `McpSyncClient`  |
| `spring.ai.mcp.client.stdio=ASYNC`          | 使用 `ASyncMcp` | 基于 `McpAsyncClient` |

### 4.2 MCP 自定义实现

除配置 `mcps[]` 外，还可以完全替换 `IMcp` 接口实现，自定义：

- MCP 服务器的发现逻辑
- 工具回调的拦截/增强
- 动态工具注册

---

## 5. 技能自定义

### 5.1 技能内容注入

在 YAML 中配置技能内容时支持两种方式：

```yaml
# 方式一：内联文本
skills:
  - name: greeting
    content: |
      你是一个问候助手，当用户说"你好"时回复"你好！"

# 方式二：从类路径读取文件
skills:
  - name: email_writer
    content: "classpath:skills/email-writer.md"
```

### 5.2 技能参数化

支持三种 UI 控件类型：

| 类型          | 说明     | 适用场景            |
|-------------|--------|-----------------|
| `TEXT`      | 单行文本输入 | 短文本（名称、邮箱等）     |
| `TEXT_AREA` | 多行文本输入 | 长文本（描述、正文等）     |
| `SELECT`    | 下拉选择器  | 固定选项（语气、格式、语言等） |

### 5.3 技能工具关联

通过 `tools` 字段关联 MCP 工具，技能激活时可自动启用对应工具：

```yaml
skills:
  - name: data_analysis
    description: 数据分析助手
    tools:
      - python_interpreter
      - chart_generator
```

---

## 6. 数据库 Schema 自定义

### 6.1 默认表结构

| 表名                  | 用途         | 主键                            |
|---------------------|------------|-------------------------------|
| `knowledge`         | 知识库元数据     | `id`                          |
| `knowledge_file`    | 知识库-文件关联   | `(knowledge_id, file_id)`     |
| `file_info`         | 文件元数据与存储路径 | `id`（含 `usage`、`mime_type` 列）|
| `file_document`     | 文件-向量文档关联  | `(file_id, document_id)`      |
| `user_conversation` | 用户-会话映射    | `(username, conversation_id)` |

### 6.2 自定义迁移脚本

通过覆盖 `FlywayConfigurationCustomizer`，可以：

- 修改迁移脚本路径
- 自定义表结构
- 添加额外的迁移脚本

### 6.3 替换数据库

默认使用嵌入式 H2 数据库。通过引入 Spring Boot 对应的数据库 Starter 可替换为：

- MySQL
- PostgreSQL
- MariaDB
- 其他 JDBC 兼容数据库

---

## 7. UI 前端自定义

UI 静态资源位于 `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/`。

### 7.1 可自定义的 UI 元素

| 元素            | 文件           | 说明              |
|---------------|--------------|-----------------|
| HTML 结构       | `index.html` | 页面骨架，品牌名"灵梭"硬编码 |
| JavaScript 逻辑 | `app.js`     | 前端交互逻辑          |
| CSS 样式        | `style.css`  | 样式定义            |

### 7.2 硬编码常量（如需修改需改源码）

| 常量                        | 位置           | 默认值                      |
|---------------------------|--------------|--------------------------|
| AI 头像图片                   | `app.js`     | `/static/ai.jpg`         |
| 用户头像图片                    | `app.js`     | `/static/user.png`       |
| 图片上传允许类型                  | `app.js`     | JPG, PNG, GIF, WebP, BMP, PDF, DOCX, XLSX, PPTX, MD, TXT 等 |
| 图片上传最大大小                  | `app.js`     | 10 MB                    |
| 文档上传允许类型                  | `app.js`     | 除图片外的所有支持的文档格式         |
| SSE 超时时间                  | `app.js`     | `0`（不超时）                 |
| LocalStorage Token Key    | `app.js`     | `loomAgentToken`         |
| LocalStorage Nickname Key | `app.js`     | `loomAgentNickname`      |
| UI 模块                     | `index.html` | 知识空间、MCP 服务、技能库          |

### 7.3 覆盖方式

用户可以在自己的项目中放置同名静态资源覆盖默认 UI，或通过 Spring 静态资源配置替换。

---

## 8. 条件开关汇总

| 条件                                 | 配置/依赖                      | 影响范围                                                                                    |
|------------------------------------|----------------------------|-----------------------------------------------------------------------------------------|
| `spring.ai.chat.ui.init=false`     | application.yml            | 不创建 `ChatClient`，整个聊天流水线不可用                                                             |
| `spring.ai.mcp.client.stdio=ASYNC` | application.yml            | 切换为 `ASyncMcp`（异步 MCP 客户端）                                                              |
| 不提供 `VectorStore` Bean             | 不引入任何 VectorStore Starter  | 不会创建 `IDocumentRead`、`RetrievalAugmentationAdvisor`、`loomAgentKnowledgeRouter`，知识库功能不可用 |
| 不提供 `EmbeddingModel` Bean          | 不引入 EmbeddingModel Starter | 不会创建 `JVectorStore`，向量存储不可用                                                             |
| 自定义同类型 Bean                        | Java `@Bean` 配置            | 对应的 `@ConditionalOnMissingBean` Bean 不会被创建                                              |

### 8.1 快速禁用功能清单

| 想要禁用    | 操作                                                 |
|---------|----------------------------------------------------|
| 整个聊天功能  | 设置 `spring.ai.chat.ui.init=false`                  |
| RAG/知识库 | 不引入任何 `VectorStore` 或 `EmbeddingModel` Starter     |
| MCP 功能  | 不配置 `spring.ai.mcp.*` 或自定义 `IMcp` 返回空列表            |
| 认证过滤器   | 覆盖 `IUser` 的 `isAutoLogin()` 返回 `true`，或自定义 Filter |
| 自动登录    | 自定义 `IUser` 的 `isAutoLogin()` 返回 `false`           |
