# Spring AI LoomAgent API 文档

> **Base URL**: `http://localhost:8089`（测试环境默认端口）
> **版本**: 1.0.0
> **认证**: 项目采用 **BFF（Backend-For-Frontend）+ HttpOnly Cookie** 鉴权模式。登录成功后，服务器通过 `Set-Cookie` 响应头设置 `loom-agent-session` Cookie，浏览器会在后续请求中自动携带该 Cookie。无需在客户端存储或手动管理 Token。

---

## 目录

- [1. 用户认证](#1-用户认证)
- [2. 会话管理](#2-会话管理)
- [3. 流式对话](#3-流式对话)
- [4. 文件管理](#4-文件管理)
- [5. 知识库管理](#5-知识库管理)
- [6. 技能管理](#6-技能管理)
- [7. MCP 工具](#7-mcp-工具)
- [8. 数据模型](#8-数据模型)
- [9. 配置属性](#9-配置属性)

---

## 1. 用户认证

### 1.1 检查是否启用自动登录

```
POST /spring/ai/loom/user/isAutoLogin
```

**请求体**: 无

**响应**: `boolean`

| 值 | 说明 |
|---|---|
| `true` | 已启用自动登录 |
| `false` | 未启用自动登录 |

---

### 1.2 用户登录

```
POST /spring/ai/loom/user/login
```

**请求体**:

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `username` | string | 是 | 用户名 |
| `verified` | string | 否 | 验证信息 |

**示例**:

```json
{
  "username": "testuser",
  "verified": ""
}
```

**响应** (`UserResponseRecord`):

| 字段 | 类型 | 说明 |
|---|---|---|
| `token` | string | Session Token（同时作为 HttpOnly Cookie 设置） |
| `nickname` | string | 用户昵称 |

**响应头**:
- `Set-Cookie: loom-agent-session=<token>; Max-Age=86400; Path=/; HttpOnly; SameSite=Lax`

**示例**:

```json
{
  "token": "567fb50c-b293-403b-a903-f6b7b597c318",
  "nickname": "用户"
}
```

---

### 1.3 用户登出

```
POST /spring/ai/loom/user/logout
```

**请求体**: 无

**响应**: `true` (boolean)

**效果**: 服务端失效 Session Token，同时清除浏览器的 `loom-agent-session` Cookie（Max-Age=0）。

---

## 2. 会话管理

### 2.1 获取会话列表

```
GET /spring/ai/loom/conversation
```

**请求头**: 需携带认证信息

**响应**: `ConversationRecord[]`

| 字段 | 类型 | 说明 |
|---|---|---|
| `conversationId` | string | 会话 ID |
| `title` | string | 会话标题 |

**示例**:

```json
[
  {
    "conversationId": "conv-001",
    "title": "第一次对话"
  }
]
```

---

### 2.2 获取会话历史

```
GET /spring/ai/loom/conversation/{conversationId}
```

**路径参数**:

| 参数 | 类型 | 说明 |
|---|---|---|
| `conversationId` | string | 会话 ID |

**响应**: `Message[]` — Spring AI 聊天记忆消息列表

---

### 2.3 删除会话

```
DELETE /spring/ai/loom/conversation/{conversationId}
```

**路径参数**:

| 参数 | 类型 | 说明 |
|---|---|---|
| `conversationId` | string | 会话 ID |

**响应**: `true` (boolean)

---

## 3. 流式对话

### 3.1 SSE 流式对话

```
POST /spring/ai/loom/stream
Content-Type: application/json
Accept: text/event-stream
```

**请求体** (`ChatRequestRecord`):

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `message` | string | 是 | 用户消息内容 |
| `conversationId` | string | 否 | 会话 ID，不传则创建新会话 |
| `mcps` | string[] | 否 | 需要启用的 MCP 工具名称列表 |
| `knowledgeId` | string | 否 | 知识库 ID，用于 RAG 检索 |
| `fileIds` | string[] | 否 | 关联的文件 ID 列表（支持多文件） |

**示例**:

```json
{
  "message": "请帮我总结这份文档",
  "conversationId": "conv-001",
  "knowledgeId": "kb-001",
  "mcps": [],
  "fileIds": ["file-abc123", "file-def456"]
}
```

**响应**: SSE (Server-Sent Events) 流

每个事件返回 `ChatResponseRecord`:

| 字段 | 类型 | 说明 |
|---|---|---|
| `content` | string | AI 回复的文本片段 |
| `reasoningContent` | string | 推理/思考过程（可选） |

**SSE 事件示例**:

```
data: {"content":"你好","reasoningContent":""}

data: {"content":"！","reasoningContent":""}

data: {"content":"有什么","reasoningContent":""}

data: {"content":"可以帮你的？","reasoningContent":""}
```

---

## 4. 文件管理

### 4.1 上传文件

```
POST /spring/ai/loom/file/upload
Content-Type: multipart/form-data
```

**表单字段**:

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `file` | file | 是 | 要上传的文件 |

**响应**:

| 字段 | 类型 | 说明 |
|---|---|---|
| `fileId` | string | 上传后返回的文件 ID |
| `status` | string | 上传状态，成功时返回 `"success"` |

**示例**:

```json
{
  "fileId": "file-abc123",
  "status": "success"
}
```

---

### 4.2 获取文件列表

```
GET /spring/ai/loom/file
```

**响应**: `FileRecord[]`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 文件 ID |
| `username` | string | 上传者用户名 |
| `knowledgeId` | string | 关联的知识库 ID（无关联时为 null） |
| `fileName` | string | 文件名 |
| `size` | number | 文件大小（字节） |
| `uploadTime` | string | 上传时间 (ISO 8601) |
| `path` | string | 文件路径 |
| `usage` | string | 文件用途：`conversation`（对话）/ `knowledge`（知识库）/ `add`（MCP 工具注册） |
| `mimeType` | string | 文件 MIME 类型 |

**示例**:

```json
[
  {
    "id": "file-abc123",
    "username": "admin",
    "knowledgeId": null,
    "fileName": "report.pdf",
    "size": 102400,
    "uploadTime": "2026-05-10T10:30:00",
    "path": "/files/report.pdf",
    "usage": "conversation",
    "mimeType": "application/pdf"
  }
]
```

---

### 4.3 删除文件

```
DELETE /spring/ai/loom/file/{id}
```

**路径参数**:

| 参数 | 类型 | 说明 |
|---|---|---|
| `id` | string | 文件 ID |

**响应**: `number` — 删除的记录数

---

### 4.4 下载文件

```
GET /spring/ai/chat/file/download/{id}
```

**路径参数**:

| 参数 | 类型 | 说明 |
|---|---|---|
| `id` | string | 文件 ID |

**响应**: 文件二进制流，`Content-Disposition` 头包含原始文件名。

---

## 5. 知识库管理

### 5.1 检查知识库上传状态

```
GET /spring/ai/loom/knowledge/checkKnowledgeUpload
```

**响应**: `boolean` — 知识库上传功能是否可用

---

### 5.2 获取知识库列表

```
GET /spring/ai/loom/knowledge
```

**响应**: `KnowledgeRecord[]`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 知识库 ID |
| `username` | string | 创建者用户名 |
| `name` | string | 知识库名称 |

**示例**:

```json
[
  {
    "id": "kb-001",
    "username": "admin",
    "name": "产品文档"
  }
]
```

---

### 5.3 创建知识库

```
PUT /spring/ai/loom/knowledge
Content-Type: application/json
```

**请求体** (`KnowledgeRecord`):

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 | 知识库名称 |

**示例**:

```json
{
  "name": "产品文档"
}
```

**响应**: 创建后的 `KnowledgeRecord`（包含生成的 `id`）

---

### 5.4 删除知识库

```
DELETE /spring/ai/loom/knowledge/{knowledgeId}
```

**路径参数**:

| 参数 | 类型 | 说明 |
|---|---|---|
| `knowledgeId` | string | 知识库 ID |

> 删除知识库时会级联清理关联的文件与向量数据。

**响应**: `number` — 删除的记录数

---

### 5.5 上传文件到知识库

```
POST /spring/ai/loom/knowledge/{knowledgeId}/upload
Content-Type: multipart/form-data
```

**路径参数**:

| 参数 | 类型 | 说明 |
|---|---|---|
| `knowledgeId` | string | 知识库 ID |

**表单字段**:

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `file` | file | 是 | 要上传的文件 |

**响应**:

```json
{
  "fileId": "file-xyz789",
  "status": "success"
}
```

---

### 5.6 获取知识库文件列表

```
GET /spring/ai/loom/knowledge/{knowledgeId}/file
```

**路径参数**:

| 参数 | 类型 | 说明 |
|---|---|---|
| `knowledgeId` | string | 知识库 ID |

**响应**: `FileRecord[]`（同 [4.2](#42-获取文件列表) 格式）

---

### 5.7 删除知识库文件

```
DELETE /spring/ai/loom/knowledge/{knowledgeId}/file/{fileId}
```

**路径参数**:

| 参数 | 类型 | 说明 |
|---|---|---|
| `knowledgeId` | string | 知识库 ID |
| `fileId` | string | 文件 ID |

**响应**: `number` — 删除的记录数

---

## 6. 技能管理

### 6.1 获取技能列表

```
GET /spring/ai/chat/skill
```

**响应**: `SkillDocument[]`

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | string | 技能名称 |
| `description` | string | 技能描述 |
| `defaultPreload` | boolean | 是否默认预加载 |
| `tools` | string[] | 关联的工具名称列表 |
| `content` | string | 技能内容（支持 `classpath:` 前缀从类路径加载） |
| `params` | SkillParamProperty[] | 技能参数定义 |
| `source` | string | 技能来源（`configuration` 配置注入 / `database` 数据库存储） |

---

### 6.2 创建/更新技能

```
PUT /spring/ai/chat/skill
Content-Type: application/json
```

**请求体** (`SkillProperty`):

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 | 技能名称 |
| `description` | string | 是 | 技能描述 |
| `defaultPreload` | boolean | 否 | 是否默认预加载，默认 `true` |
| `tools` | string[] | 否 | 关联的工具名称列表 |
| `content` | string | 否 | 技能内容（支持 `classpath:` 前缀） |
| `params` | SkillParamProperty[] | 否 | 技能参数定义 |

**SkillParamProperty**:

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 | 参数名称 |
| `label` | string | 是 | 参数显示名称 |
| `type` | string | 否 | 参数类型：`TEXT` / `SELECT` / `TEXT_AREA` |
| `required` | boolean | 否 | 是否必填 |
| `defaultValue` | string | 否 | 默认值 |
| `placeholder` | string | 否 | 占位符文本 |
| `options` | Option[] | 否 | 下拉选项（`type=SELECT` 时使用） |

**Option**:

| 字段 | 类型 | 说明 |
|---|---|---|
| `label` | string | 选项显示文本 |
| `value` | string | 选项值 |

**示例**:

```json
{
  "name": "email_writer",
  "description": "专业邮件撰写助手",
  "defaultPreload": true,
  "tools": [],
  "params": [
    {
      "name": "recipient",
      "label": "收件人",
      "type": "TEXT",
      "required": true,
      "placeholder": "请输入收件人邮箱"
    },
    {
      "name": "tone",
      "label": "语气",
      "type": "SELECT",
      "required": false,
      "defaultValue": "formal",
      "options": [
        { "label": "正式", "value": "formal" },
        { "label": "友好", "value": "friendly" },
        { "label": "简洁", "value": "concise" }
      ]
    },
    {
      "name": "content",
      "label": "邮件内容",
      "type": "TEXT_AREA",
      "required": true,
      "placeholder": "请输入邮件主要内容"
    }
  ]
}
```

**响应**: `true` (boolean)

---

### 6.3 获取单个技能

```
GET /spring/ai/chat/skill/{name}
```

**路径参数**:

| 参数 | 类型 | 说明 |
|---|---|---|
| `name` | string | 技能名称 |

**响应**: `SkillDocument`

---

### 6.4 删除技能

```
DELETE /spring/ai/chat/skill/{name}
```

**路径参数**:

| 参数 | 类型 | 说明 |
|---|---|---|
| `name` | string | 技能名称 |

**响应**: `true` (boolean)

---

## 7. MCP 工具

### 7.1 获取 MCP 服务器及工具列表

```
GET /spring/ai/chat/loom/mcp
```

**响应**: `McpRecord[]`

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | string | MCP 服务器名称 |
| `title` | string | MCP 服务器标题（中文标签） |
| `version` | string | MCP 版本 |
| `description` | string | MCP 描述 |
| `defaultSelected` | boolean | 是否默认选中 |
| `tools` | ToolRecord[] | 工具列表 |

**ToolRecord**:

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | string | 工具名称 |
| `description` | string | 工具描述 |

**示例**:

```json
[
  {
    "name": "weather-mcp",
    "title": "天气查询",
    "version": "1.0.0",
    "description": "提供实时天气查询服务",
    "defaultSelected": true,
    "tools": [
      {
        "name": "getWeather",
        "description": "查询指定城市的当前天气"
      }
    ]
  }
]
```

---

## 8. 数据模型

### ChatRequestRecord

```json
{
  "message": "string",
  "conversationId": "string",
  "mcps": ["string"],
  "knowledgeId": "string",
  "fileIds": ["string"]
}
```

### ChatResponseRecord

```json
{
  "content": "string",
  "reasoningContent": "string"
}
```

### ConversationRecord

```json
{
  "conversationId": "string",
  "title": "string"
}
```

### UserRequestRecord

```json
{
  "username": "string",
  "verified": "string"
}
```

### UserResponseRecord

```json
{
  "token": "string",
  "nickname": "string"
}
```

> **注意**: `token` 同时会通过 `Set-Cookie` 响应头设置为 HttpOnly Cookie（`loom-agent-session`）。浏览器会自动在后续请求中携带该 Cookie，客户端无需手动存储或发送 Token。

### FileRecord

```json
{
  "id": "string",
  "username": "string",
  "knowledgeId": "string",
  "fileName": "string",
  "size": 0,
  "uploadTime": "2026-05-10T10:30:00",
  "path": "string",
  "usage": "conversation",
  "mimeType": "application/pdf"
}
```

### KnowledgeRecord

```json
{
  "id": "string",
  "username": "string",
  "name": "string"
}
```

### McpRecord

```json
{
  "name": "string",
  "title": "string",
  "version": "string",
  "description": "string",
  "defaultSelected": true,
  "tools": [
    {
      "name": "string",
      "description": "string"
    }
  ]
}
```

### SkillDocument

继承 `SkillProperty`，额外包含 `source` 字段标识来源。

---

## 9. 配置属性

所有配置项在 `application.yml` 中以 `spring.ai.loom.agent` 为前缀。

### 9.1 基础配置

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `spring.ai.loom.agent.defaultSystem` | string | 技能发现提示词 | 默认系统提示词 |
| `spring.ai.loom.agent.init` | boolean | `true` | 是否初始化 ChatClient |

### 9.2 RAG 配置

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `spring.ai.loom.agent.rag.similarityThreshold` | double | `0.0` | 向量检索相似度阈值 |
| `spring.ai.loom.agent.rag.topK` | int | `4` | 检索返回的顶部 K 条结果 |
| `spring.ai.loom.agent.rag.defaultPromptTemplate` | string | — | RAG 默认提示词模板 |
| `spring.ai.loom.agent.rag.defaultEmptyContextPromptTemplate` | string | — | 无上下文时的默认回复模板 |
| `spring.ai.loom.agent.rag.enabledKeyword` | boolean | `false` | 是否启用关键词检索 |
| `spring.ai.loom.agent.rag.enabledSummary` | boolean | `false` | 是否启用摘要生成 |

### 9.3 MCP 配置

`spring.ai.loom.agent.mcps` 为数组，每项包含：

| 属性 | 类型 | 说明 |
|---|---|---|
| `name` | string | MCP 服务器名称 |
| `title` | string | 中文显示名称 |
| `description` | string | 描述信息 |
| `defaultSelected` | boolean | 是否默认选中 |
| `tools[].name` | string | 工具名称 |
| `tools[].description` | string | 工具描述 |

### 9.4 技能配置

`spring.ai.loom.agent.skills` 为数组，每项即 [SkillProperty](#62-创建更新技能) 中定义的字段。

### 9.5 JVector 配置

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `spring.ai.loom.agent.jvector.indexPath` | string | `.local/jvector-index` | 向量索引存储路径 |
| `spring.ai.loom.agent.jvector.m` | int | `16` | HNSW 图参数 M |
| `spring.ai.loom.agent.jvector.efConstruction` | int | `100` | 构建时的 ef 参数 |
| `spring.ai.loom.agent.jvector.efSearch` | int | `10` | 搜索时的 ef 参数 |

### 9.6 鉴权配置

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `spring.ai.loom.agent.auth.enabled` | boolean | `true` | 鉴权总开关 |
| `spring.ai.loom.agent.auth.pathPatterns` | string[] | `["/spring/ai/loom/**"]` | 需要鉴权的路径模式 |
| `spring.ai.loom.agent.auth.cookie.name` | string | `loom-agent-session` | Session Cookie 名称 |
| `spring.ai.loom.agent.auth.cookie.maxAge` | int | `86400` | Cookie 最大存活时间（秒，24 小时） |

---

## 附录：接口总览

| # | 方法 | 路径 | 说明 |
|---|---|---|---|
| 1 | `POST` | `/spring/ai/loom/user/isAutoLogin` | 检查自动登录状态 |
| 2 | `POST` | `/spring/ai/loom/user/login` | 用户登录（设置 Session Cookie） |
| 2a| `POST` | `/spring/ai/loom/user/logout` | 用户登出（失效 Session） |
| 3 | `GET` | `/spring/ai/loom/conversation` | 获取会话列表 |
| 4 | `GET` | `/spring/ai/loom/conversation/{id}` | 获取会话历史 |
| 5 | `DELETE` | `/spring/ai/loom/conversation/{id}` | 删除会话 |
| 6 | `POST` | `/spring/ai/loom/stream` | SSE 流式对话 |
| 7 | `POST` | `/spring/ai/loom/file/upload` | 上传文件 |
| 8 | `GET` | `/spring/ai/loom/file` | 获取文件列表 |
| 9 | `DELETE` | `/spring/ai/loom/file/{id}` | 删除文件 |
| 10 | `GET` | `/spring/ai/chat/file/download/{id}` | 下载文件 |
| 11 | `GET` | `/spring/ai/loom/knowledge/checkKnowledgeUpload` | 检查知识库状态 |
| 12 | `GET` | `/spring/ai/loom/knowledge` | 获取知识库列表 |
| 13 | `PUT` | `/spring/ai/loom/knowledge` | 创建知识库 |
| 14 | `DELETE` | `/spring/ai/loom/knowledge/{id}` | 删除知识库（级联清理） |
| 15 | `POST` | `/spring/ai/loom/knowledge/{id}/upload` | 上传文件到知识库 |
| 16 | `GET` | `/spring/ai/loom/knowledge/{id}/file` | 获取知识库文件列表 |
| 17 | `DELETE` | `/spring/ai/loom/knowledge/{id}/file/{fileId}` | 删除知识库文件 |
| 18 | `GET` | `/spring/ai/chat/loom/mcp` | 获取 MCP 工具列表 |
| 19 | `GET` | `/spring/ai/chat/skill` | 获取技能列表 |
| 20 | `PUT` | `/spring/ai/chat/skill` | 创建/更新技能 |
| 21 | `GET` | `/spring/ai/chat/skill/{name}` | 获取单个技能 |
| 22 | `DELETE` | `/spring/ai/chat/skill/{name}` | 删除技能 |
| — | `GET` | `/spring/ai/loom` | 重定向到 UI 首页 |
