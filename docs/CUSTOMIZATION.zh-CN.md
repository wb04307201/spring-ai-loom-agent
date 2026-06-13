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
│   ├── tool/          IEmbedTool (marker)          # 聚合工具接口
│   │   ├── time/      ITimeTool / DefaultTimeTool  # 时间工具
│   │   ├── skill/     ISkillTool / DefaultSkillTool # 技能工具
│   │   ├── file/      IFileTool / DefaultFileTool  # 文件工具
│   │   ├── git/       IGitTool / DefaultGitTool    # Git 工具（JGit）
│   │   └── maven/     IMavenTool / DefaultMavenTool # Maven 构建工具（maven-invoker）
│   ├── document/      IDocumentRead / IFileDocument # 文档解析
│   └── model/         *Record / LoomAgentProperties # 模型与配置
├── spring-ai-loom-agent-spring-boot-autoconfigure/  # 自动配置
│   └── LoomAgentConfiguration.java                # 核心装配类（7 个嵌套 @Configuration 子类）
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
| `load`           | boolean  | `true` | 是否默认预加载到对话中                    |
| `content`        | String   | —      | 技能内容文本（支持 `classpath:` 前缀读取文件） |

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
            load: false
            content: "classpath:skills/email-writer.md"
```

### 1.6 鉴权配置 (`auth.*`)

项目采用 **BFF（Backend-For-Frontend）+ HttpOnly Cookie** 鉴权模式。登录成功后，服务器设置 Session Cookie，浏览器会在后续请求中自动携带该 Cookie，无需在客户端存储或手动管理 Token。

| 属性                            | 类型    | 默认值                                                                                   | 说明                                                   |
|---------------------------------|---------|------------------------------------------------------------------------------------------|--------------------------------------------------------|
| `auth.enabled`                  | boolean | `true`                                                                                   | 鉴权总开关；设为 `false` 则跳过所有鉴权检查              |
| `auth.pathPatterns`             | Array   | `["/spring/ai/loom/**"]`                                                                 | 需要鉴权的路径模式（Ant 风格通配符）                      |
| `auth.excludePathPatterns`      | Array   | `["/spring/ai/loom/user/login", "/spring/ai/loom/user/isAutoLogin", "/spring/ai/loom/user/logout", "/spring/ai/loom/index.html", "/spring/ai/loom/app.js", "/spring/ai/loom/style.css"]` | 明确排除鉴权的路径列表                                   |
| `auth.cookie.name`              | String  | `loom-agent-session`                                                                     | Session Cookie 名称                                    |
| `auth.cookie.path`              | String  | `/`                                                                                      | Cookie 路径                                            |
| `auth.cookie.domain`            | String  | `""`                                                                                     | Cookie 域名（空表示当前域名）                            |
| `auth.cookie.secure`            | boolean | `false`                                                                                  | Cookie 是否仅通过 HTTPS 发送                           |
| `auth.cookie.sameSite`          | String  | `Lax`                                                                                    | SameSite 属性（`Lax` / `Strict` / `None`）              |
| `auth.cookie.maxAge`            | int     | `86400`                                                                                  | Cookie 最大存活时间（秒，默认 24 小时）                   |

**示例配置**：

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

**用户配置 (`user.*`)**：

| 属性                            | 类型   | 默认值           | 说明                                  |
|---------------------------------|--------|------------------|---------------------------------------|
| `user.username`                 | String | `username`       | 默认自动登录用户名                    |
| `user.nickname`                 | String | `用户`           | 默认自动登录昵称                      |
| `user.authentication`           | String | `loom-agent-auth`| 旧版令牌值（向后兼容）                  |

**Session 存储**：默认使用 Spring Cache（Caffeine），TTL 与 `auth.cookie.maxAge` 一致。可替换 `sessionCache` Bean 为自定义存储（如 Redis）。

### 1.7 文件存储配置

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `fileBasePath` | String | `.local/file` | 上传文件的根目录（聊天附件、文件工具操作） |
| `knowledgeBasePath` | String | `.local/knowledge` | 知识库文件的根目录 |

同目录下的同名文件自动追加序号：`file.txt` → `file(1).txt` → `file(2).txt`。

### 1.8 Git 配置 (`git.*`)

| 属性                | 类型     | 默认值   | 说明                                                                 |
|-------------------|--------|-------|--------------------------------------------------------------------|
| `git.enabled`     | boolean | `false` | 是否启用 Git 工具（IGitTool）。**默认禁用**（opt-in）—— 端到端部署请走 `ICompileAndDeployTool`（始终启用）。设为 `true` 时再暴露 28 个 git 命令给 LLM。 |
| `git.username`    | String  | —     | HTTP(S) git 认证用户名（clone/pull/push）                               |
| `git.token`       | String  | —     | HTTP(S) git 认证令牌/密码                                              |
| `gitUsername`     | String  | —     | **兼容** 顶层字段，等价于 `git.username`                                  |
| `gitToken`        | String  | —     | **兼容** 顶层字段，等价于 `git.token`                                     |

**示例配置**：

```yaml
spring:
  ai:
    loom:
      agent:
        git:
          enabled: true   # 默认；设为 false 禁用
          username: your-git-username
          token: your-git-token
```

> Git 凭证也可通过 `ToolContext` 按请求传入（`gitUsername` / `gitToken` 键），会覆盖配置的默认值。

### 1.9 工具启用开关（`{time,file,skill,git,maven,compile}.enabled`）

所有内置工具组（`ITimeTool` / `ISkillTool` / `IFileTool` / `IGitTool` / `IMavenTool` / `ICompileAndDeployTool`）的完整参考——包括默认状态、所有 `@Tool` 方法签名、配置属性、基础镜像模板、端到端部署参数——见 **[TOOLS.zh-CN.md](./TOOLS.zh-CN.md)**。

开关一览：

| 属性                | 类型     | 默认值   | 说明                                                       |
|-------------------|--------|-------|----------------------------------------------------------|
| `time.enabled`    | boolean | `true` | `ITimeTool` — 时间与时区工具                                |
| `file.enabled`    | boolean | `true` | `IFileTool` — 16 个基于路径的文件工具                          |
| `skill.enabled`   | boolean | `true` | `ISkillTool` — 列出技能、获取技能详情                          |
| `git.enabled`     | boolean | `false` | `IGitTool`（JGit）。**opt-in** —— 端到端部署走 `ICompileAndDeployTool`。 |
| `maven.enabled`   | boolean | `false` | `IMavenTool`（需 `maven-invoker`）。**opt-in** —— 编译/打包走 `ICompileAndDeployTool`。 |
| `compile.enabled` | boolean | `true`  | `ICompileAndDeployTool` — 端到端 `git clone → build → docker run → health check` |

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
| **控制内容** | 自动登录判断、用户登录验证、Session Token 管理                                                                                                                                           |

**接口方法**：
- `isAutoLogin()` — 是否支持自动登录（默认：`true`）
- `login(UserRequestRecord)` — 验证凭据并返回响应
- `createToken(username)` — 生成 Session Token 并存入缓存
- `validateToken(token)` — 从缓存中校验 Session Token
- `invalidateToken(token)` — 删除 Session Token（登出）
- `getUsernameByToken(token)` — 从 Session Token 解析用户名
- `getUsernameByAuthentication(authentication)` — 旧版方法（向后兼容）

**默认行为**: `isAutoLogin()` 始终返回 `true`；`login()` 始终返回成功；Session Token 存储在 Spring Cache（默认 Caffeine）中。

**自定义示例**:

```java
@Bean
public IUser customUser(Cache sessionCache) {
    return new IUser() {
        @Override public Boolean isAutoLogin() { return false; }
        @Override public UserResponseRecord login(UserRequestRecord request) {
            // 接入真实的 LDAP/OAuth/JWT 认证
            // 返回 new UserResponseRecord(token, nickname)
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
| **方法签名** | `Flux<ChatResponse> stream(ChatRequestRecord record, String username, HttpServletRequest request)` |
| **控制内容** | 流式对话处理：用户/会话管理、RAG 顾问、MCP 工具注入、技能工具注入、图片/文档处理、toolContext 跨线程上下文传递 |

**默认行为**: 组装 `ChatClient`，可选加入 `RetrievalAugmentationAdvisor`、`IMcp` 工具、所有 `IEmbedTool` 子工具（时间、技能、文件、Git）、用户会话管理等。文档类文件（PDF/DOCX/XLSX/PPTX/MD 等）通过 Apache Tika 提取文本后以 System Prompt 注入，图片作为 Media 类型传入模型。

**自定义示例**:

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

**默认行为**: 聊天上传的文件保存到 `{fileBasePath}/{username}/`（如 `.local/file/username/`），知识库文件保存到 `{knowledgeBasePath}/{username}/{knowledgeId}/`（如 `.local/knowledge/username/{knowledgeId}/`）。同名文件自动追加序号：`file.txt` → `file(1).txt` → `file(2).txt`。文档通过 `IDocumentRead` 解析（PDF/DOCX/XLSX/PPTX/MD 等），文本内容通过 System Prompt 注入对话。

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

### 2.11 `IEmbedTool` — 嵌入工具（时间 / 技能 / 文件 / Git / Maven / 部署）

`IEmbedTool` 是聚合标记接口，子接口（`ITimeTool`、`ISkillTool`、`IFileTool`、`IGitTool`、`IMavenTool`、`ICompileAndDeployTool`）各自向 LLM 提供独立的 `@Tool` 方法。每个子工具均可通过 `@ConditionalOnMissingBean` 独立替换。

完整参考（所有 `@Tool` 方法签名、配置属性、基础镜像模板、端到端部署参数、替换示例）见 **[TOOLS.zh-CN.md](./TOOLS.zh-CN.md)**。

### 2.12 `AuthenticationFilter` — 认证过滤器

| 项目       | 内容                                                       |
|----------|----------------------------------------------------------|
| **类型**   | `cn.wubo.spring.ai.loom.agent.user.AuthenticationFilter` |
| **覆盖方式** | 自定义 Servlet Filter，或覆盖 `IUser` Bean                      |
| **控制内容** | 拦截匹配 `auth.pathPatterns` 的请求，校验 Session Cookie          |

过滤器使用 `AntPathMatcher` 匹配请求路径与 `auth.pathPatterns`。`auth.excludePathPatterns` 中列出的路径始终跳过鉴权。当 `auth.enabled=false` 时，过滤器放行所有请求。

**Session 管理流程**：
1. 用户访问 `/spring/ai/loom/index.html`（无需鉴权）
2. 前端调用 `POST /spring/ai/loom/user/isAutoLogin` → 返回 `true`
3. 前端调用 `POST /spring/ai/loom/user/login` → 服务端创建 Session，设置 `Set-Cookie: loom-agent-session=...`
4. 浏览器自动在后续请求中携带 HttpOnly Cookie
5. `AuthenticationFilter` 读取 Cookie，校验缓存中的 Token，设置 `UserContextHolder`
6. 登出：`POST /spring/ai/loom/user/logout` → 服务端失效 Token 并清除 Cookie

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

### 5.2 技能属性

每个技能有四个可配置字段：

| 字段            | 类型     | 默认值    | 说明                              |
|-----------------|----------|---------|-----------------------------------|
| `name`          | String   | —       | 技能名称（唯一标识符）                  |
| `description`   | String   | —       | 技能描述（用于 LLM 匹配）               |
| `load`          | boolean  | `true`  | 是否默认预加载到对话中                   |
| `content`       | String   | —       | 技能内容文本或 `classpath:` 前缀从文件读取 |

> **注意**：YAML 配置中技能不再支持 `tools` 或 `params` 字段。MCP 工具绑定和技能参数在运行时通过 Skill Library API（`PUT /spring/ai/loom/skill`）管理。

---

## 6. 数据库 Schema 自定义

### 6.1 默认表结构

| 表名                  | 用途         | 主键                            |
|---------------------|------------|-------------------------------|
| `knowledge`         | 知识库元数据     | `id`                          |
| `knowledge_file`    | 知识库-文件关联   | `(knowledge_id, file_id)`     |
| `file_info`         | 文件元数据与存储路径（`usage` 列：`conversation` / `knowledge` / `tool` / `git` / `temp`） | `id` |
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
| 允许上传类型                    | `app.js`     | JPG, PNG, GIF, WebP, BMP, PDF, DOCX, XLSX, PPTX, MD, TXT 等 |
| 最大上传大小                    | `app.js`     | 10 MB                    |
| SSE 超时时间                    | `app.js`     | `0`（不超时）                 |
| UI 模块                       | `index.html` | 知识空间、MCP 服务、技能库          |

> **注意**：前端不再使用 localStorage 存储 Token（BFF + Cookie 鉴权模式）。Session 由 HttpOnly Cookie 管理。

### 7.3 覆盖方式

用户可以在自己的项目中放置同名静态资源覆盖默认 UI，或通过 Spring 静态资源配置替换。

---

## 8. 条件开关汇总

| 条件                                 | 配置/依赖                      | 影响范围                                                                                    |
|------------------------------------|----------------------------|-----------------------------------------------------------------------------------------|
| `spring.ai.chat.ui.init=false`     | application.yml            | 不创建 `ChatClient`，整个聊天流水线不可用                                                             |
| `spring.ai.mcp.client.stdio=ASYNC` | application.yml            | 切换为 `ASyncMcp`（异步 MCP 客户端）                                                              |
| `spring.ai.mcp.client.enabled=false`| application.yml           | 禁用 MCP 客户端自动配置（当 MCP 服务器不可用时可避免启动失败）                                       |
| `auth.enabled=false`               | application.yml            | 禁用鉴权；`AuthenticationFilter` 放行所有请求                                                    |
| 不提供 `VectorStore` Bean             | 不引入任何 VectorStore Starter  | 不会创建 `IDocumentRead`、`RetrievalAugmentationAdvisor`、`loomAgentFileRouter`、`loomAgentKnowledgeRouter`，知识库和文件上传功能不可用 |
| 不提供 `EmbeddingModel` Bean          | 不引入 EmbeddingModel Starter | 不会创建 `JVectorStore`，向量存储不可用                                                             |
| 自定义同类型 Bean                        | Java `@Bean` 配置            | 对应的 `@ConditionalOnMissingBean` Bean 不会被创建                                              |
| `spring.ai.loom.agent.git.enabled=true` | application.yml          | 创建 `IGitTool` Bean（`DefaultGitTool`，Eclipse JGit 7.6.0）；未配置时 Git 工具不可用            |
| classpath 上有 `maven-invoker`           | 已提供依赖                     | 启用 `IMavenTool` Bean 创建；没有时 Maven 工具不可用                                       |

### 8.1 快速禁用功能清单

| 想要禁用    | 操作                                                 |
|---------|----------------------------------------------------|
| 整个聊天功能  | 设置 `spring.ai.chat.ui.init=false`                  |
| RAG/知识库 | 不引入任何 `VectorStore` 或 `EmbeddingModel` Starter     |
| MCP 功能  | 设置 `spring.ai.mcp.client.enabled=false`               |
| Git 工具  | 不设置 `spring.ai.loom.agent.git.enabled=true`（默认禁用）    |
| Maven 工具 | 设置 `spring.ai.loom.agent.maven.enabled=false`            |
| 认证过滤器   | 设置 `spring.ai.loom.agent.auth.enabled=false`         |
| 自动登录    | 自定义 `IUser` 的 `isAutoLogin()` 返回 `false`           |

### 8.2 Session 缓存自定义

`sessionCache` Bean 默认使用 Caffeine，TTL 与 `auth.cookie.maxAge` 一致。可替换为自定义存储：

```java
@Bean
public Cache sessionCache(RedisCacheManager cacheManager) {
    return cacheManager.getCache("loom-agent-auth");
}
```

### 8.3 IChat 方法签名

`IChat.stream()` 方法接收 `username` 参数（由 `SseController` 从 `UserContextHolder` 注入），不再需要 `ChatRequestRecord.authentication` 字段。用户名从隐式变为显式传递。
