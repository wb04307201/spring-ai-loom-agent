# Spring AI LoomAgent API 文档

> **Base URL**: `http://localhost:8080`（测试环境默认端口）
> **版本**: 1.1.36
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
- [8. 技能工具（@Tool 注解）](#8-技能工具tool-注解)
- [9. 知识库工具（@Tool 注解）](#9-知识库工具tool-注解)
- [10. 终端管理](#10-终端管理)
- [11. Maven 构建工具](#11-maven-构建工具)
  - [11.7 文件工具（@Tool 注解）](#117-文件工具tool-注解)
- [12. 数据模型](#12-数据模型)
- [13. 配置属性](#13-配置属性)

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

### 4.2 获取文件树

```
GET /spring/ai/loom/file
GET /spring/ai/loom/file/tree
```

**响应**: 目录树 JSON（递归结构）

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | string | 文件或目录名称 |
| `type` | string | `"file"` 或 `"directory"` |
| `size` | number | 文件大小（字节，仅文件） |
| `children` | array | 子项（仅目录） |

**示例**:

```json
{
  "name": ".",
  "type": "directory",
  "children": [
    { "name": "report.pdf", "type": "file", "size": 102400 },
    {
      "name": "docs",
      "type": "directory",
      "children": [
        { "name": "guide.md", "type": "file", "size": 4096 }
      ]
    }
  ]
}
```

---

### 4.3 按路径预览文件

```
GET /spring/ai/loom/file/by-path/view?path=report.pdf
```

**查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `path` | string | 是 | 相对于用户文件目录的文件路径 |

**响应**: `307 临时重定向` → `/file/view/{fileId}`（WOPI 在线预览）。

> 如果文件尚未注册到 `file_info`，会自动创建临时记录（`usage="temp"`）获取 `fileId`。

---

### 4.4 按路径下载文件

```
GET /spring/ai/loom/file/by-path/download?path=report.pdf
```

**查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `path` | string | 是 | 相对于用户文件目录的文件路径 |

**响应**: `307 临时重定向` → `/wopi/files/{fileId}/contents`。

---

### 4.5 按 ID 下载文件

```
GET /spring/ai/loom/file/{id}/download
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

**响应**: `FileRecord[]`（同 [4.2](#42-获取文件树) 格式）

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

### 5.8 知识市场

> 知识市场支持跨用户共享知识库。流程：提交 → PENDING → admin 审批 → APPROVED → 其他用户可订阅。

#### 5.8.1 浏览已审批的市场知识库

```
GET /spring/ai/loom/api/knowledge-market?page=1&size=20
```

**查询参数**:

| 参数   | 类型 | 必填 | 说明               |
|--------|------|------|--------------------|
| `page` | int  | 否   | 页码（默认 1）     |
| `size` | int  | 否   | 每页条数（默认 20）|

**响应**: `MarketKnowledgeRecord[]` — 已审批的市场知识库列表。

| 字段          | 类型   | 说明             |
|---------------|--------|------------------|
| `id`          | string | 市场知识库 ID    |
| `username`    | string | 原始作者用户名   |
| `name`        | string | 知识库名称       |
| `description` | string | 知识库描述       |
| `status`      | string | `APPROVED`       |
| `submittedAt` | string | 提交时间         |
| `reviewedAt`  | string | 审核时间         |

---

#### 5.8.2 订阅市场知识库

```
POST /spring/ai/loom/api/knowledge-market/{marketId}/pull
```

**路径参数**:

| 参数       | 类型   | 说明           |
|------------|--------|----------------|
| `marketId` | string | 市场知识库 ID  |

**响应**: 成功返回 `{"success": true}`。在 `loom_user_knowledge` 表中创建 `source=MARKET_PULLED` 的订阅记录。

---

#### 5.8.3 提交知识库到市场

```
POST /spring/ai/loom/api/knowledge/{knowledgeId}/submit
```

**路径参数**:

| 参数          | 类型   | 说明       |
|---------------|--------|------------|
| `knowledgeId` | string | 知识库 ID  |

**响应**: `MarketKnowledgeRecord` — 创建的市场条目，`status=PENDING`。

**约束**: `(username, name)` 必须唯一，重复提交返回 409。

---

#### 5.8.4 撤回市场提交

```
DELETE /spring/ai/loom/api/knowledge-market/{marketId}
```

**路径参数**:

| 参数       | 类型   | 说明           |
|------------|--------|----------------|
| `marketId` | string | 市场知识库 ID  |

**响应**: 成功返回 `{"success": true}`。仅原始提交者可撤回。

---

#### 5.8.5 管理员审批市场提交

```
POST /spring/ai/loom/api/knowledge-market/{marketId}/approve
```

**路径参数**:

| 参数       | 类型   | 说明           |
|------------|--------|----------------|
| `marketId` | string | 市场知识库 ID  |

**响应**: `MarketKnowledgeRecord` — 更新后的记录，`status=APPROVED`。

**权限**: 仅管理员。非管理员返回 403。

---

#### 5.8.6 管理员拒绝市场提交

```
POST /spring/ai/loom/api/knowledge-market/{marketId}/reject
```

**路径参数**:

| 参数       | 类型   | 说明           |
|------------|--------|----------------|
| `marketId` | string | 市场知识库 ID  |

**响应**: `MarketKnowledgeRecord` — 更新后的记录，`status=REJECTED`。

**权限**: 仅管理员。非管理员返回 403。

---

#### 5.8.7 查看我订阅的知识库

```
GET /spring/ai/loom/api/knowledge-market/my-pulled
```

**响应**: `MarketKnowledgeRecord[]` — 当前用户从市场订阅的知识库列表。

---

#### 5.8.8 查看我的市场提交

```
GET /spring/ai/loom/api/knowledge-market/my-submitted
```

**响应**: `MarketKnowledgeRecord[]` — 当前用户提交到市场的知识库列表（所有状态：PENDING / APPROVED / REJECTED）。

---

## 6. 技能管理

> 所有 Skill 全部入数据库（表 `market_skill` / `user_skill` / `role_skill`），yml 的 `skills[]` 段不再读取 —— 改为 6 个 system seed 进 `market_skill`，加一套 admin 管理的市场流程。
>
> `user_skill.source` 字段反映 Skill 三个来源：
> - `USER_CREATED` — 用户通过 API 或聊天 UI 自己创建；**完全可编辑**（name/desc/content/default_loaded）
> - `MARKET_PULLED` — 用户从已审批的市场拉取；**只能改 desc / default_loaded**（content 锁定为市场快照，要更新就重新拉取）
> - `ROLE_GRANTED` — admin 通过角色授权自动注入；**只读**（locked=true；不能改不能删）
> - `MARKET_VIEW` — 仅 admin：把"全部 APPROVED + 自己的 PENDING"虚拟展示在聊天界面（带 `市` 角标），不写 `user_skill`
>
> 每次 list/get 接口都会自动跑 `role_skill` → `user_skill` 同步，所以新授权的 Skill 在下次 list 时立刻可见。

---

### 6.1 获取当前用户技能列表

```
GET /spring/ai/loom/skill
```

**响应**: `SkillRecord[]`（LLM 视角的视图）

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | string | 技能名称 |
| `description` | string | 技能描述 |
| `load` | boolean | 是否预加载到 LLM 系统提示 |
| `content` | string | 技能内容（如果存的是 `classpath:xxx`，会在读时自动 resolve 成真实文本） |
| `source` | string | `USER_CREATED` / `MARKET_PULLED` / `ROLE_GRANTED` /（仅 admin）`MARKET_VIEW` |

admin 还会附带 `MARKET_VIEW` union（全部 APPROVED + 自己的 PENDING）。

---

### 6.2 创建/覆盖一个 Skill（USER_CREATED）

```
PUT /spring/ai/loom/skill
Content-Type: application/json
```

**请求体** (`SkillRecord`):

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 | 技能名称 |
| `description` | string | 否 | 技能描述 |
| `load` | boolean | 否 | 是否预加载到 LLM 系统提示，默认 `true` |
| `content` | string | 是 | 技能内容 / prompt 模板（支持 `classpath:` 前缀，读时 resolve） |

> `content` 里的 `{param}` 占位符由 LLM 在运行时从对话上下文解释，不是结构化表单字段。`content` 里通过 `@工具名` 引用 MCP 工具，可用工具由角色授权决定。

**示例**:

```json
{
  "name": "email_writer",
  "description": "专业邮件撰写助手",
  "load": true,
  "content": "你是一名邮件助手。收件人是 {recipient}..."
}
```

**响应**: `true` (boolean) — 若同名 `ROLE_GRANTED` 锁定则抛 `400`。

---

### 6.3 修改描述 / 默认加载

```
PATCH /spring/ai/loom/skill/{name}
Content-Type: application/json
```

`MARKET_PULLED` 和 `USER_CREATED` 可改 `description` 和/或 `defaultLoaded`（不改 content）。`ROLE_GRANTED` 锁定时返回 `400`。

**请求体** (`UserSkillPatchRequest`):

| 字段 | 类型 | 说明 |
|---|---|---|
| `description` | string | 新描述（省略则不变） |
| `defaultLoaded` | boolean | 新默认加载标志（省略则不变） |

---

### 6.4 获取单个技能（LLM 视角）

```
GET /spring/ai/loom/skill/{name}
```

**路径参数**:

| 参数 | 类型 | 说明 |
|---|---|---|
| `name` | string | 技能名称 |

**响应**: `SkillRecord`。admin 若本地无副本，会 fallback 到市场视图。

---

### 6.5 删除技能

```
DELETE /spring/ai/loom/skill/{name}
```

`ROLE_GRANTED` 锁定时返回 `400`。成功返回 `true`。

---

### 6.6 手动触发角色同步

```
POST /spring/ai/loom/skill/sync
```

对当前用户重跑 `role_skill` → `user_skill` 同步。主要用于排查问题 —— 实际上每次 list/get 都会自动跑。

---

### 6.7 Skill 市场 — 浏览（任意用户）

```
GET /spring/ai/loom/market-skills
```

返回所有 `status='APPROVED'` 的 `market_skill`，按 `author, name, version DESC` 排序。每条带完整 `MarketSkill` 模型（`id` / `name` / `description` / `content` / `version` / `author` / `status` / `submittedAt` / `reviewedAt` / `reviewedBy` / `reviewComment`）。

---

### 6.8 Skill 市场 — 查看单个

```
GET /spring/ai/loom/market-skills/{id}
```

**路径参数**: `id` = `market_skill.id`（Long）

---

### 6.9 Skill 市场 — 拉取到我的 user_skill

```
POST /spring/ai/loom/market-skills/{id}/pull
```

从指定 `market_skill` 创建/更新一条 `MARKET_PULLED` 的 `user_skill`。抛 `400` 条件：
- 市场 Skill 状态不是 `APPROVED`
- 同名已有 `ROLE_GRANTED` 锁定
- 同名已存在（静默刷新 content）

---

### 6.10 提交我的 Skill 到市场

```
POST /spring/ai/loom/user/market-skills
Content-Type: application/json
```

新建一条 `market_skill`，`status=PENDING`，`author=currentUser`。

**请求体** (`MarketSkillSubmitRequest`):

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 | 技能名称 |
| `description` | string | 否 | 技能描述 |
| `content` | string | 是 | prompt 模板 |
| `version` | string | 是 | 语义化版本号 |

约束：`(author, name, version)` 三元组必须唯一。重复的 PENDING 需用新版本号重新提交。

---

### 6.11 管理员 — 市场 CRUD

> 仅 admin。路由层校验 `auth.adminPathPatterns`，handler 内二次校验 `isAdmin()`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET    | `/spring/ai/loom/admin/market-skills`             | 列出**所有**（PENDING/APPROVED/REJECTED） |
| GET    | `/spring/ai/loom/admin/market-skills/pending`     | 只列 PENDING                                |
| POST   | `/spring/ai/loom/admin/market-skills`             | 直接以 `status=APPROVED` 创建（绕过审批） |
| PUT    | `/spring/ai/loom/admin/market-skills/{id}`        | 改任意字段                                  |
| DELETE | `/spring/ai/loom/admin/market-skills/{id}`        | 级联删除 user_skill / role_skill 引用        |
| POST   | `/spring/ai/loom/admin/market-skills/{id}/approve`| 审批通过 PENDING                              |
| POST   | `/spring/ai/loom/admin/market-skills/{id}/reject` | 拒绝 PENDING；body: `{comment}`              |

`MarketSkillUpsertRequest`（POST/PUT 通用）:

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 | 技能名称 |
| `description` | string | 否 | 技能描述 |
| `content` | string | 是 | prompt 模板 |
| `version` | string | 是 | 版本号 |
| `status` | string | 否 | 默认 `APPROVED`（admin 提交/编辑时） |

---

### 6.12 管理员 — 给角色授权 Skill

```
GET /spring/ai/loom/admin/roles/{code}/skills
PUT /spring/ai/loom/admin/roles/{code}/skills
```

`GET` 返回该角色已授权的 `RoleSkillItem[]`（每条 `marketSkillId` + `defaultLoaded`）。

`PUT` 覆盖式设置整张清单：

```json
{
  "items": [
    {"marketSkillId": 1, "defaultLoaded": true},
    {"marketSkillId": 5, "defaultLoaded": false}
  ]
}
```

`defaultLoaded` 缺省 `true`。角色下的用户在下一次 list/sync 时会看到这些 Skill 注入到自己的 `user_skill`（`source=ROLE_GRANTED`，`locked=true`）。

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
    "version": "1.1.36",
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

## 8. 技能工具（@Tool 注解）

`ISkillTool` 注册给 LLM 调用的 `@Tool` 方法。**与** `## 6. 技能管理` **的服务端 REST API 不同**（后者服务于管理控制台 UI）。

### 10.1 `listSkills` — 列出可用技能

```
@Tool: listSkills
```

列出当前用户可访问的技能（渐进式披露风格）。默认返回全部（上限 200），可按 `keyword` 和 `source` 过滤。

| 参数        | 类型    | 必填 | 说明                                                                                          |
|-------------|---------|------|----------------------------------------------------------------------------------------------|
| `keyword`   | string  | 否   | 子串过滤（不区分大小写），匹配 `name` 或 `description`                                            |
| `source`    | string  | 否   | 按 source 过滤：`USER_CREATED` / `MARKET_VIEW` / `ROLE_GRANTED` / `MARKET_PULLED`                  |
| `maxCount`  | integer | 否   | 最多返回数量，默认 `200`                                                                       |

**返回**：文本表格列出匹配的技能（name + source + description），结果数小于用户全部可访问技能数时附带截断提示。

### 10.2 `getSkill` — 获取单个技能的内容

```
@Tool: getSkill
```

| 参数   | 类型   | 必填 | 说明         |
|--------|--------|------|--------------|
| `name` | string | 是   | 技能名称     |

**返回**：包含 `技能名`、`技能描述` 和完整 `技能内容` 的文本。用户无访问权限时抛出异常。

### 10.3 `createOrUpdateSkill` — 创建或更新自建技能

```
@Tool: createOrUpdateSkill
```

| 参数          | 类型   | 必填 | 说明                                                                |
|---------------|--------|------|--------------------------------------------------------------------|
| `name`        | string | 是   | 技能名；去前后空白；最长 128                                            |
| `description` | string | 否   | 技能描述                                                               |
| `content`     | string | 是   | 技能 Prompt 内容（非空）                                              |

**行为**：
- 若用户已拥有同名技能，内容/描述被覆盖。
- `ROLE_GRANTED` / `MARKET_PULLED` 技能**已锁定**，返回 403。
- 返回 "已创建技能 X" 或 "已更新技能 X"。

---

## 9. 知识库工具（@Tool 注解）

`IKnowledgeTool` 注册给 LLM 的工具化 RAG 检索方法。已启用的知识库列表在 system prompt `【知识库】` 段自动展示——LLM 不需要列表工具来发现它们。

### 11.1 `searchKnowledge` — 向量检索知识库

```
@Tool: searchKnowledge
```

| 参数          | 类型    | 必填 | 说明                                                                  |
|---------------|---------|------|----------------------------------------------------------------------|
| `knowledgeId` | string  | 是   | 目标知识库 ID                                                          |
| `query`       | string  | 是   | 检索查询（语义相似度）                                                  |
| `topK`        | integer | 否   | 最多返回 chunk 数，默认取 `rag.topK` 配置                                |

**返回**：top-k chunk 列表，含相似度分数和文本内容。空结果返回 "未检索到相关文档片段"。权限检查：仅 own / subscribed / role-granted 知识库可检索；否则返回 "没有权限访问该知识库"。

**相似度阈值**：通过 `rag.similarityThreshold` 配置（默认 0.50）。低于阈值的 chunk 在返回前被过滤。

---

## 10. 终端管理

通过 `@Tool` 注解暴露的进程管理工具，供 LLM 在对话中启动和管理系统进程。

### 10.1 `startProcess` — 启动进程

```
@Tool: startProcess
```

启动一个新的系统进程或 REPL 会话。支持两种模式：**Shell 模式**（一次性命令如 `ls`、`cat`）和 **REPL 模式**（长期交互式会话如 `python`、`node`）。REPL 模式在可用时使用 PTY（伪终端）以实现完整的终端交互。

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `command` | `String` | ✅ | 要执行的命令 |
| `workingDir` | `String` | | 工作目录（默认 `.local/file/{username}/`） |
| `repl` | `Boolean` | | 是否 REPL 模式（`true`= 长期交互；`false`/省略 = 一次性命令） |
| `timeout` | `Long` | | 等待超时（毫秒，默认 30000） |

**响应：**

```json
{
  "sessionId": "auto-generated-id",
  "pid": 12345,
  "status": "RUNNING",
  "output": "...initial output..."
}
```

### 10.2 `interactWithProcess` — 与进程交互

```
@Tool: interactWithProcess
```

向运行中的 REPL 会话发送输入并等待响应。

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | `String` | ✅ | 会话 ID（`startProcess` 返回） |
| `input` | `String` | ✅ | 要发送的输入（自动追加换行符） |
| `timeout` | `Long` | | 等待响应超时（毫秒，默认 10000） |

### 10.3 `readProcessOutput` — 读取进程输出

```
@Tool: readProcessOutput
```

读取运行中进程的最新输出。支持三种模式：`new`（自上次读取以来的新内容，默认）、`tail`（最后 N 行）、`absolute`（从字符位置 N 开始）。

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | `String` | ✅ | 会话 ID |
| `mode` | `String` | | 读取模式：`new` / `tail` / `absolute` |
| `position` | `Integer` | | 绝对字符位置（仅 `mode=absolute` 时） |
| `lines` | `Integer` | | 行数（仅 `mode=tail` 时，默认 50） |

### 10.4 `forceTerminate` — 强制终止进程

```
@Tool: forceTerminate
```

强制终止受管理的终端会话。

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | `String` | ✅ | 会话 ID |

### 10.5 `listSessions` — 列出所有会话

```
@Tool: listSessions
```

列出当前用户所有活跃的终端会话。

### 10.6 `getProcessInfo` — 获取进程信息

```
@Tool: getProcessInfo
```

获取单个会话的详细信息，包括完整输出、进程状态、工作目录、PTY 模式等。

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | `String` | ✅ | 会话 ID |

### 10.7 `sendSignal` — 发送信号

```
@Tool: sendSignal
```

向终端会话发送控制信号。PTY 模式支持：`interrupt`（Ctrl+C）、`eof`（Ctrl+D）、`quit`（Ctrl+\）。非 PTY 模式仅支持通过 `destroy` 发送 `interrupt`。

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | `String` | ✅ | 会话 ID |
| `signal` | `String` | ✅ | 信号类型：`interrupt` / `eof` / `quit` |

### 10.8 `listProcesses` — 列出系统进程

```
@Tool: listProcesses
```

列出所有运行中的操作系统进程（类似 `ps` 或任务管理器）。支持分页。

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `maxResults` | `Integer` | | 每页最大结果数（默认 50，最大 200） |
| `page` | `Integer` | | 页码，从 0 开始（默认 0） |

### 10.9 `killProcess` — 杀死指定进程

```
@Tool: killProcess
```

通过 PID 强制终止系统进程。

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `pid` | `Long` | ✅ | 进程 ID |
| `force` | `Boolean` | | 是否使用强制终止（默认 true） |

---

## 11. Maven 构建工具

所有 Maven 工具的操作范围限定在用户文件目录（`{fileBasePath}/{username}/`）内。超出该范围的绝对路径将被拒绝。

### 11.1 通用执行

```
@Tool: mavenExecute
```

执行任意 Maven goals（通用入口）。

| 参数           | 类型               | 必填 | 说明                                                            |
|----------------|--------------------|------|-----------------------------------------------------------------|
| `goals`        | string[]           | 是   | 要执行的 Maven 目标（如 `["clean", "package"]`）                  |
| `pomPath`      | string             | 否   | pom.xml 路径（相对于用户目录或在范围内的绝对路径）                 |
| `workingDir`   | string             | 否   | 工作目录（必须在用户文件目录内）                                    |
| `properties`   | Map<string,string> | 否   | Maven -D 属性                                                    |
| `timeoutMs`    | long               | 否   | 超时时间（毫秒），默认 300000                                     |

---

### 11.2 编译

```
@Tool: mavenBuild
```

编译项目（`mvn compile`）。自动查找 pom.xml。

| 参数           | 类型               | 必填 | 说明                                                            |
|----------------|--------------------|------|-----------------------------------------------------------------|
| `pomPath`      | string             | 否   | pom.xml 路径                                                     |
| `workingDir`   | string             | 否   | 工作目录                                                          |
| `properties`   | Map<string,string> | 否   | Maven -D 属性                                                    |
| `skipTests`    | boolean            | 否   | 跳过测试（默认 false）                                             |

---

### 11.3 打包

```
@Tool: mavenPackage
```

打包项目（`mvn package`），生成 JAR/WAR 等构件。

| 参数           | 类型               | 必填 | 说明                                                            |
|----------------|--------------------|------|-----------------------------------------------------------------|
| `pomPath`      | string             | 否   | pom.xml 路径                                                     |
| `workingDir`   | string             | 否   | 工作目录                                                          |
| `properties`   | Map<string,string> | 否   | Maven -D 属性                                                    |
| `skipTests`    | boolean            | 否   | 跳过测试（默认 true）                                              |

---

### 11.4 测试

```
@Tool: mavenTest
```

运行单元测试（`mvn test`）。支持测试类名模式匹配。

| 参数           | 类型               | 必填 | 说明                                                            |
|----------------|--------------------|------|-----------------------------------------------------------------|
| `pomPath`      | string             | 否   | pom.xml 路径                                                     |
| `workingDir`   | string             | 否   | 工作目录                                                          |
| `testPattern`  | string             | 否   | 测试类名模式（如 `*ServiceTest`，对应 `-Dtest`）                   |
| `properties`   | Map<string,string> | 否   | Maven -D 属性                                                    |

---

### 11.5 依赖树

```
@Tool: mavenDependencyTree
```

查看项目依赖树（`mvn dependency:tree`）。支持范围过滤。

| 参数             | 类型   | 必填 | 说明                                                  |
|------------------|--------|------|-------------------------------------------------------|
| `pomPath`        | string | 否   | pom.xml 路径                                           |
| `workingDir`     | string | 否   | 工作目录                                                |
| `includeScope`   | string | 否   | 依赖范围过滤：compile/runtime/test/provided             |

---

### 11.6 验证

```
@Tool: mavenValidate
```

验证项目结构（`mvn validate`）。检查 pom.xml 和必要资源是否存在。

| 参数           | 类型   | 必填 | 说明              |
|----------------|--------|------|-------------------|
| `pomPath`      | string | 否   | pom.xml 路径       |
| `workingDir`   | string | 否   | 工作目录            |

---

## 11.7 文件工具（@Tool 注解）

所有文件工具均限定在用户文件目录（`{fileBasePath}/{username}/`）内，绝对路径及 `..` 越界会被拒绝并抛出 `SecurityException`。symlink 越界（userDir 内有指向外面的软链）也通过 `PathSecurityUtils.toRealPath` 跟链防御。预览/下载工具会自动创建 `file_info` 临时记录（`usage="temp"`）用于桥接访问。

**资源约束**（详见 [11.7.1](#1171-文件工具配置ifiletool)）：`readTextFile` / `writeFile` / `editFile` 走 `file.maxFileSize`（默认 5 MB）；`readMediaFile` 走 `file.maxMediaSize`（默认 1 MB）；`directoryTree` / `searchFiles` 走 `file.maxWalkDepth` / `file.maxWalkEntries` / `file.excludedDirs`；`searchFiles` 还受 `file.maxSearchResults` 限制。

### 9.7.1 读取文本

```
@Tool: readTextFile
```

读取本地文件内容为文本。支持 `head`（仅前 N 行）或 `tail`（仅后 N 行）参数。

| 参数    | 类型    | 必填 | 说明                            |
|---------|---------|------|---------------------------------|
| `path`  | string  | 是   | 相对于用户文件目录的路径          |
| `head`  | integer | 否   | 若提供，仅返回前 N 行            |
| `tail`  | integer | 否   | 若提供，仅返回后 N 行            |

### 9.7.2 读取媒体文件

```
@Tool: readMediaFile
```

读取本地图片或音频文件，返回 base64 编码数据和 MIME 类型。

| 参数   | 类型   | 必填 | 说明                            |
|--------|--------|------|---------------------------------|
| `path` | string | 是   | 相对于用户文件目录的路径          |

### 9.7.3 批量读取

```
@Tool: readMultipleFiles
```

一次读取多个文件，单个失败不影响其他文件。

| 参数    | 类型     | 必填 | 说明                                  |
|---------|----------|------|---------------------------------------|
| `paths` | string[] | 是   | 相对于用户文件目录的路径列表          |

### 9.7.4 写入文件

```
@Tool: writeFile
```

创建新文件或完全覆盖已有文件。父目录不存在会自动创建。写入走 **原子写**（写 `.tmp` 再 `Files.move(ATOMIC_MOVE)`），避免断电导致文件损坏；跨卷时退化。

| 参数      | 类型   | 必填 | 说明                            |
|-----------|--------|------|---------------------------------|
| `path`    | string | 是   | 相对于用户文件目录的路径          |
| `content` | string | 是   | 要写入的文本内容；超过 `file.maxFileSize` 时拒绝写入并返回错误 |

### 9.7.5 编辑文件

```
@Tool: editFile
```

按行编辑：基于精确文本匹配做替换，返回 git 风格的 diff。

| 参数    | 类型                       | 必填 | 说明                                          |
|---------|----------------------------|------|-----------------------------------------------|
| `path`  | string                     | 是   | 相对于用户文件目录的路径                       |
| `edits` | Array<{oldText, newText}>  | 是   | 编辑列表，每项必须含 `oldText` 和 `newText`  |

**唯一性校验**：`oldText` 在文件中出现 >1 次时**直接拒绝**（避免 LLM 误传导致多处被错误替换），并提示"在文件中出现 N 次，请提供更精确的上下文使其唯一"。空 `oldText` 也被拒绝。

### 9.7.6 创建目录

```
@Tool: createDirectory
```

创建新目录（若已存在则幂等成功），支持多级嵌套。

| 参数   | 类型   | 必填 | 说明                            |
|--------|--------|------|---------------------------------|
| `path` | string | 是   | 相对于用户文件目录的路径          |

### 9.7.7 移动文件

```
@Tool: moveFile
```

移动或重命名文件/目录（支持跨目录），使用原子移动。

| 参数          | 类型   | 必填 | 说明                              |
|---------------|--------|------|-----------------------------------|
| `source`      | string | 是   | 源路径（相对于用户文件目录）       |
| `destination` | string | 是   | 目标路径（相对于用户文件目录）     |

### 9.7.8 搜索文件

```
@Tool: searchFiles
```

按 glob 模式（`*.txt`、`**/*.java` 等）递归搜索文件。空模式返回所有文件。

| 参数      | 类型   | 必填 | 说明                            |
|-----------|--------|------|---------------------------------|
| `pattern` | string | 否   | glob 模式，如 `*.txt`           |

### 9.7.9 列出允许目录

```
@Tool: listAllowedDirectories
```

返回当前用户的文件操作目录。其他所有工具的 `path` 参数均相对于此目录。

### 9.7.10 列出目录

```
@Tool: listDirectory
```

列出目录内容，区分 `[FILE]` 和 `[DIR]`。支持 `depth` 参数控制递归深度。

| 参数    | 类型    | 必填 | 说明                                |
|---------|---------|------|-------------------------------------|
| `path`  | string  | 是   | 目录路径（空字符串 = 根目录）        |
| `depth` | integer | 否   | 递归深度（默认 1）                   |

### 9.7.11 列出目录（带大小）

```
@Tool: listDirectoryWithSizes
```

列出目录内容及每项的大小。`[FILE]` 和 `[DIR]` 标记。

| 参数   | 类型   | 必填 | 说明                                |
|--------|--------|------|-------------------------------------|
| `path` | string | 是   | 目录路径（空字符串 = 根目录）        |

### 9.7.12 目录树

```
@Tool: directoryTree
```

返回目录的递归树视图，JSON 格式。

| 参数   | 类型   | 必填 | 说明                                |
|--------|--------|------|-------------------------------------|
| `path` | string | 是   | 目录路径（空字符串 = 根目录）        |

### 9.7.13 文件信息

```
@Tool: getFileInfo
```

返回文件或目录的详细元数据（大小、创建/修改/访问时间、权限，文件还包含行数）。

| 参数   | 类型   | 必填 | 说明                            |
|--------|--------|------|---------------------------------|
| `path` | string | 是   | 相对于用户文件目录的路径          |

### 9.7.14 下载链接

```
@Tool: downloadFileUrl
```

生成原始文件下载链接。自动创建临时 `file_info` 记录（`usage="temp"`）。

| 参数   | 类型   | 必填 | 说明                            |
|--------|--------|------|---------------------------------|
| `path` | string | 是   | 相对于用户文件目录的路径          |

### 9.7.15 预览链接

```
@Tool: viewFileUrl
```

生成在线预览链接。支持 PDF、Word、Excel、PPT、图片、Markdown 等格式。自动创建临时 `file_info` 记录。

| 参数   | 类型   | 必填 | 说明                            |
|--------|--------|------|---------------------------------|
| `path` | string | 是   | 相对于用户文件目录的路径          |

### 9.7.16 删除文件或目录

```
@Tool: deleteFileOrDirectory
```

删除本地文件或目录（目录会递归删除）。**必须显式确认**（传入 `confirm` 参数且与 `spring.ai.loom.agent.file.deleteConfirmToken` 配置值一致，默认 `I_CONFIRM_DELETE`）才能执行，避免 LLM 误删。删除时同步清理对应已注册文件在 `file_info` 表中的 `usage="temp"` 记录。

| 参数      | 类型   | 必填 | 说明                                                                          |
|-----------|--------|------|-------------------------------------------------------------------------------|
| `path`    | string | 是   | 相对于用户文件目录的路径（可以是文件或目录）                                  |
| `confirm` | string | 是   | 确认值，必须与配置的 `deleteConfirmToken`（默认 `I_CONFIRM_DELETE`）完全一致，否则拒绝执行（不改动文件系统） |

**确认规则：**

- 缺失或与 `deleteConfirmToken` 不匹配 → 返回 `错误：需要确认。请传入 confirm="<token>" 才能执行删除。`，不动文件系统。
- 路径不存在 → 返回 `路径不存在：<path>`。
- 路径是文件 → 单文件 `Files.delete` 后返回 `已删除文件：<path>`。
- 路径是目录 → 深度优先递归删除后返回 `已删除目录：<path>（N 个文件）`。

> 旧版本接受 `Y` / `y` / `Yes` / `yes` 作为确认值；新版本要求传入完整 token（默认 `I_CONFIRM_DELETE`）。可以通过 `spring.ai.loom.agent.file.deleteConfirmToken: "YES"` 改回短 token。

---

## 12. 数据模型

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

### SkillRecord

```json
{
  "name": "string",
  "description": "string",
  "load": true,
  "content": "string",
  "source": "USER_CREATED | MARKET_PULLED | ROLE_GRANTED | MARKET_VIEW"
}
```

> 响应形态与 PUT 请求体一致（`name` / `description` / `load` / `content`），由服务端在响应里补一个 `source` 字段标识数据来源。`source` 取值：`USER_CREATED` / `MARKET_PULLED` / `ROLE_GRANTED` / `MARKET_VIEW`。

---

## 13. 配置属性

所有配置项在 `application.yml` 中以 `spring.ai.loom.agent` 为前缀。

### 11.1 基础配置

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `spring.ai.loom.agent.defaultSystem` | string | 技能发现提示词 | 默认系统提示词 |
| `spring.ai.loom.agent.init` | boolean | `true` | 是否初始化 ChatClient |

### 11.2 RAG 配置

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `spring.ai.loom.agent.rag.similarityThreshold` | double | `0.0` | 向量检索相似度阈值 |
| `spring.ai.loom.agent.rag.topK` | int | `4` | 检索返回的顶部 K 条结果 |

### 11.3 MCP 配置

`spring.ai.loom.agent.mcps` 为数组，每项包含：

| 属性 | 类型 | 说明 |
|---|---|---|
| `name` | string | MCP 服务器名称 |
| `title` | string | 中文显示名称 |
| `description` | string | 描述信息 |
| `defaultSelected` | boolean | 是否默认选中 |
| `tools[].name` | string | 工具名称 |
| `tools[].description` | string | 工具描述 |

### 11.4 技能配置（yml 不再读取）

`spring.ai.loom.agent.skills[]` yml 段**不再读取**。参见 [§6 技能管理](#6-技能管理) 了解新的数据库流程。首次启动时会 seed 6 个 system skill。

### 11.5 JVector 配置

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `spring.ai.loom.agent.jvector.indexPath` | string | `.local/jvector-index` | 向量索引存储路径 |
| `spring.ai.loom.agent.jvector.m` | int | `16` | HNSW 图参数 M |
| `spring.ai.loom.agent.jvector.efConstruction` | int | `100` | 构建时的 ef 参数 |
| `spring.ai.loom.agent.jvector.efSearch` | int | `10` | 搜索时的 ef 参数 |

### 11.6 鉴权配置

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `spring.ai.loom.agent.auth.enabled` | boolean | `true` | 鉴权总开关 |
| `spring.ai.loom.agent.auth.pathPatterns` | string[] | `["/spring/ai/loom/**"]` | 需要鉴权的路径模式 |
| `spring.ai.loom.agent.auth.cookie.name` | string | `loom-agent-session` | Session Cookie 名称 |
| `spring.ai.loom.agent.auth.cookie.maxAge` | int | `86400` | Cookie 最大存活时间（秒，24 小时） |

### 11.7 文件存储配置

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `spring.ai.loom.agent.fileBasePath` | string | `.local/file` | 上传文件的根目录 |
| `spring.ai.loom.agent.knowledgeBasePath` | string | `.local/knowledge` | 知识库文件的根目录 |

> 同目录下同名文件自动追加序号：`file.txt` → `file(1).txt` → `file(2).txt`。

### 11.7.1 文件工具配置（`IFileTool`）

文件工具（`IFileTool`）走 `spring.ai.loom.agent.file.*`，有一组针对 LLM 工具调用场景的默认安全/资源约束。

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `spring.ai.loom.agent.file.enabled` | boolean | `true` | 是否启用文件工具 |
| `spring.ai.loom.agent.file.maxFileSize` | long | `5242880`（5 MB） | 单次读取 / 写入文件大小上限（字节）。超过直接拒绝，**避免 OOM 和 LLM context 溢出**。 |
| `spring.ai.loom.agent.file.maxMediaSize` | long | `1048576`（1 MB） | 媒体文件（图片 / 音频）大小上限。base64 编码后体积 ≈ 4/3，比文本更严。 |
| `spring.ai.loom.agent.file.maxWalkDepth` | int | `5` | 目录树 / 递归列出 / 搜索的深度上限。 |
| `spring.ai.loom.agent.file.maxWalkEntries` | int | `1000` | 单次 `listDirectory` / `directoryTree` 返回的条目数上限。 |
| `spring.ai.loom.agent.file.maxSearchResults` | int | `500` | `searchFiles` 命中数上限。 |
| `spring.ai.loom.agent.file.deleteConfirmToken` | string | `I_CONFIRM_DELETE` | `deleteFileOrDirectory` 必须显式传入这个字符串才执行（**防 LLM 误删**）。可以改成更短的 token（如 `YES`）以省 token。 |
| `spring.ai.loom.agent.file.excludedDirs` | string[] | `[".git", "node_modules", "target", "build", "dist", ".idea", ".vscode", ".gradle", "out", "bin"]` | 目录遍历时跳过的目录名（精确匹配，非 glob）。让 `directoryTree` / `searchFiles` 在 Spring Boot 项目上不会一次性拉出几万个 `target/classes/*.class`。 |

**安全机制**：

- 所有路径解析都委托给 `PathSecurityUtils.assertInsideUserDir(resolved, userDir, mustExist)`，统一处理：
  - `..` 越权（`Path.normalize` 即可防）
  - **symlink 越界**（`Path.toRealPath` 跟链）—— 即使用户在 `userDir` 里放了一个指向 `C:\Windows` 的软链，也不会读到
  - 大小写不敏感文件系统的 size-bypass（Windows / macOS）
- 原子写：`writeFile` / `editFile` 写 `.tmp` 再 `Files.move(ATOMIC_MOVE)`，避免写到一半断电导致目标文件损坏。跨卷时退化到非原子替换。
- `editFile` 唯一性校验：`oldText` 在文件中出现 >1 次时拒绝，要求 LLM 提供更精确的上下文。

### 11.8 Maven 构建配置

| 属性                                              | 类型     | 默认值       | 说明                                                                                                          |
|---------------------------------------------------|----------|-------------|---------------------------------------------------------------------------------------------------------------|
| `spring.ai.loom.agent.maven.enabled`              | boolean  | `false`     | 是否启用 Maven 工具（**opt-in**）—— 部署场景的编译/打包由 `ICompileAndDeployTool` 处理                                  |
| `spring.ai.loom.agent.maven.mavenHome`            | string   | —           | Maven 安装目录。**为空时工具会自动探测**：依次尝试 `MAVEN_HOME` / `M2_HOME` 环境变量，再扫描 Windows 常见路径（如 `C:\developer\apache-maven-*`、`C:\Program Files\Apache Maven`）。自动探测**不依赖系统 PATH**，避免被损坏的 mvn 包装脚本（如 npm 全局 mvn）遮蔽而让 `maven-invoker` 抛 `Error configuring command line`。 |
| `spring.ai.loom.agent.maven.localRepository`      | string   | —           | 本地仓库路径（为空时使用默认路径）                                                                                |
| `spring.ai.loom.agent.maven.maxOutputLines`       | int      | `200`       | 输出最大行数（超出截断）                                                                                          |
| `spring.ai.loom.agent.maven.defaultTimeoutMs`     | long     | `300000`    | 默认执行超时（毫秒），5 分钟                                                                                      |

> 所有 Maven 工具的操作范围限定在 `{fileBasePath}/{username}/` 内，超出该范围的路径将被拒绝。
>
> **排错提示 — `MavenInvocationException: Error configuring command line`**：表示 `maven-invoker` 找不到可用的 `mvn` / `mvn.cmd`。工具启动日志会打印实际解析到的 `mavenHome` 和一份诊断提示（包含所有搜索过的路径、环境变量、修好方法）。最常见原因是 `PATH` 上有损坏或遮蔽的 `mvn`（例如 npm 全局 mvn 包装脚本），此时在 `application.yml` 显式设置 `spring.ai.loom.agent.maven.mavenHome` 指向真实 Maven 安装目录即可绕过。
>
> **排错提示 — Windows 上删除项目目录报"文件被锁定"**：旧版本是因为 `maven-invoker 3.3.0` / `plexus-utils 3.3.0`（a）在异常/取消路径上注册的 JVM shutdown hook 永不释放持有的 `Process` 引用，（b）`Invoker.execute()` 拿不到子进程句柄、无法把取消/超时向下传播给 mvn 子进程。结果是：一次被取消或超时的 Maven 调用会留下 mvn 子进程继续运行，持续对 `target/classes`、`~/.m2/repository/*.jar` 持有 mmap 句柄，在 Windows 上锁住这些文件。**新版本不再用 `Invoker.execute()` 跑进程**——直接用 `ProcessBuilder` fork mvn、用 `Process.waitFor(timeout, unit)` 做干净超时、超时后 `Process.destroyForcibly()` + 显式关闭流。**不再注册任何 JVM shutdown hook，mvn 子进程在超时/取消时一定被杀。** 升级后如果还看到锁，多半是上一次 JVM 留下的孤儿 mvn 进程，用 `tasklist /FI "IMAGENAME eq cmd.exe"` 找到并 `taskkill /F /PID <pid>` 即可。

### 11.9 工具组开关

所有内置工具组**默认全部启用**（`matchIfMissing=true`）。在 yml 中将下列任一属性设为 `false` 即可关闭对应工具组。

| 属性                                          | 类型     | 默认值 | 说明                                                                                            |
|-----------------------------------------------|----------|-------|-------------------------------------------------------------------------------------------------|
| `spring.ai.loom.agent.time.enabled`           | boolean  | `true` | 时间工具（`ITimeTool` — 获取当前时间、时区转换）                                                  |
| `spring.ai.loom.agent.file.enabled`           | boolean  | `true` | 文件工具（`IFileTool` — 16 个基于路径的读写/编辑/搜索/删除操作）                                   |
| `spring.ai.loom.agent.skill.enabled`          | boolean  | `true` | 技能工具（`ISkillTool` — 列出技能、获取技能详情）                                                  |
| `spring.ai.loom.agent.git.enabled`            | boolean  | `false` | Git 工具（`IGitTool` — 28 个 git 操作）。**opt-in** —— 端到端部署走 `ICompileAndDeployTool`。 |
| `spring.ai.loom.agent.maven.enabled`          | boolean  | `false` | Maven 工具（同时需要 classpath 上有 `maven-invoker`）。**opt-in** —— 部署场景的编译/打包走 `ICompileAndDeployTool`。 |
| `spring.ai.loom.agent.git.username`           | string   | —     | HTTP(S) Git 认证用户名（clone/pull/push）                                                          |
| `spring.ai.loom.agent.git.token`              | string   | —     | HTTP(S) Git 认证 token / 密码                                                                    |
| `spring.ai.loom.agent.gitUsername`            | string   | —     | **遗留**顶层别名，等价于 `git.username`                                                            |
| `spring.ai.loom.agent.gitToken`               | string   | —     | **遗留**顶层别名，等价于 `git.token`                                                               |

**示例 — 启用 Git 工具**：

```yaml
spring:
  ai:
    loom:
      agent:
        git:
          enabled: true   # 默认 false；设为 true 启用
```

> 即便工具组被关闭，你仍可以通过自定义 `@Bean IGitTool` / `@Bean IMavenTool` 重新启用 —— `@ConditionalOnMissingBean` 始终优先采用用户提供的 Bean。

### 11.10 端到端部署配置（`ICompileAndDeployTool`）

`ICompileAndDeployTool` 在单次 LLM tool call 内完成 `git clone → 按 buildTool 打包（maven / npm / pip）→ docker build → docker run → health check` 整条部署流水线。支持 Maven、Node.js（后端 + 静态前端 → nginx）、Python 等多栈项目。所有配置项均在 `spring.ai.loom.agent.compile.*` 下。

| 属性                                              | 类型     | 默认值       | 说明                                                                                                          |
|---------------------------------------------------|----------|-------------|---------------------------------------------------------------------------------------------------------------|
| `spring.ai.loom.agent.compile.enabled`            | boolean  | `true`      | 是否启用端到端部署工具。默认开启，是部署场景的推荐入口。                                                                            |
| `spring.ai.loom.agent.compile.imageTemplates`     | map<string, ImageTemplate> | `java17` / `java21` / `nginx` / `python3` / `node20` / `node20-serve` | 预置基础镜像别名（`ImageTemplate { image, command[] }`），可通过工具入参 `baseImage` 选择。                              |

**基础镜像模板**（可选）：预置 `java17` / `java21` / `nginx` / `python3` / `node20` / `node20-serve` 六个模板，可通过 yml 覆盖或新增。工具入参 `baseImage` 传别名即选中对应模板，传完整镜像名（如 `openjdk:17-slim`）则直接用，command 走 java17 兜底。

```yaml
spring:
  ai:
    loom:
      agent:
        compile:
          image-templates:
            java17:
              image: eclipse-temurin:17-jre-alpine
              command: [java, -jar, app.jar]
            nginx:
              image: nginx:1.27-alpine
              command: [nginx, -g, "daemon off;"]
```

工具入参示例：

```json
{
  "gitUrl": "https://gitee.com/wb04307201/sql-forge-demo.git",
  "port": 8081,
  "containerPort": 8080,
  "subDir": "sql-forge-web",
  "buildTool": "maven",
  "baseImage": "java17",
  "healthPath": "sql-forge-demo"
}
```

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
| 8 | `GET` | `/spring/ai/loom/file` 或 `/spring/ai/loom/file/tree` | 获取文件树（目录树 JSON） |
| 8a | `GET` | `/spring/ai/loom/file/by-path/view` | 按路径预览文件（重定向） |
| 8b | `GET` | `/spring/ai/loom/file/by-path/download` | 按路径下载文件（重定向） |
| 8c | `GET` | `/spring/ai/loom/file/{id}/download` | 按 ID 下载文件（二进制流） |
| 9 | `GET` | `/spring/ai/loom/knowledge/checkKnowledgeUpload` | 检查知识库状态 |
| 10 | `GET` | `/spring/ai/loom/knowledge` | 获取知识库列表 |
| 11 | `PUT` | `/spring/ai/loom/knowledge` | 创建知识库 |
| 12 | `DELETE` | `/spring/ai/loom/knowledge/{id}` | 删除知识库（级联清理） |
| 13 | `POST` | `/spring/ai/loom/knowledge/{id}/upload` | 上传文件到知识库 |
| 14 | `GET` | `/spring/ai/loom/knowledge/{id}/file` | 获取知识库文件列表 |
| 15 | `DELETE` | `/spring/ai/loom/knowledge/{id}/file/{fileId}` | 删除知识库文件 |
| 15a| `GET` | `/spring/ai/loom/api/knowledge-market` | 浏览已审批的市场知识库（分页） |
| 15b| `POST` | `/spring/ai/loom/api/knowledge-market/{marketId}/pull` | 订阅市场知识库 |
| 15c| `POST` | `/spring/ai/loom/api/knowledge/{knowledgeId}/submit` | 提交知识库到市场 |
| 15d| `DELETE` | `/spring/ai/loom/api/knowledge-market/{marketId}` | 撤回市场提交 |
| 15e| `POST` | `/spring/ai/loom/api/knowledge-market/{marketId}/approve` | 管理员审批市场提交 |
| 15f| `POST` | `/spring/ai/loom/api/knowledge-market/{marketId}/reject` | 管理员拒绝市场提交 |
| 15g| `GET` | `/spring/ai/loom/api/knowledge-market/my-pulled` | 查看我订阅的市场知识库 |
| 15h| `GET` | `/spring/ai/loom/api/knowledge-market/my-submitted` | 查看我的市场提交 |
| 16 | `GET` | `/spring/ai/chat/loom/mcp` | 获取 MCP 工具列表 |
| 17 | `GET` | `/spring/ai/loom/skill` | 获取技能列表 |
| 18 | `PUT` | `/spring/ai/loom/skill` | 创建/更新技能 |
| 19 | `GET` | `/spring/ai/loom/skill/{name}` | 获取单个技能 |
| 20 | `DELETE` | `/spring/ai/loom/skill/{name}` | 删除技能 |
| — | `GET` | `/spring/ai/loom` | 重定向到 UI 首页 |
