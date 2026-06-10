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
- [8. Maven 构建工具](#8-maven-构建工具)
  - [8.7 文件工具（@Tool 注解）](#87-文件工具tool-注解)
- [9. 数据模型](#9-数据模型)
- [10. 配置属性](#10-配置属性)

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
GET /spring/ai/loom/skill
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
PUT /spring/ai/loom/skill
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
GET /spring/ai/loom/skill/{name}
```

**路径参数**:

| 参数 | 类型 | 说明 |
|---|---|---|
| `name` | string | 技能名称 |

**响应**: `SkillDocument`

---

### 6.4 删除技能

```
DELETE /spring/ai/loom/skill/{name}
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

## 8. Maven 构建工具

所有 Maven 工具的操作范围限定在用户文件目录（`{fileBasePath}/{username}/`）内。超出该范围的绝对路径将被拒绝。

### 8.1 通用执行

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

### 8.2 编译

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

### 8.3 打包

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

### 8.4 测试

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

### 8.5 依赖树

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

### 8.6 验证

```
@Tool: mavenValidate
```

验证项目结构（`mvn validate`）。检查 pom.xml 和必要资源是否存在。

| 参数           | 类型   | 必填 | 说明              |
|----------------|--------|------|-------------------|
| `pomPath`      | string | 否   | pom.xml 路径       |
| `workingDir`   | string | 否   | 工作目录            |

---

## 8.7 文件工具（@Tool 注解）

所有文件工具均限定在用户文件目录（`{fileBasePath}/{username}/`）内，绝对路径及 `..` 越界会被拒绝并抛出 `SecurityException`。symlink 越界（userDir 内有指向外面的软链）也通过 `PathSecurityUtils.toRealPath` 跟链防御。预览/下载工具会自动创建 `file_info` 临时记录（`usage="temp"`）用于桥接访问。

**资源约束**（详见 [9.7.1](#971-文件工具配置-ifiletool)）：`readTextFile` / `writeFile` / `editFile` 走 `file.maxFileSize`（默认 5 MB）；`readMediaFile` 走 `file.maxMediaSize`（默认 1 MB）；`directoryTree` / `searchFiles` 走 `file.maxWalkDepth` / `file.maxWalkEntries` / `file.excludedDirs`；`searchFiles` 还受 `file.maxSearchResults` 限制。

### 8.7.1 读取文本

```
@Tool: readTextFile
```

读取本地文件内容为文本。支持 `head`（仅前 N 行）或 `tail`（仅后 N 行）参数。

| 参数    | 类型    | 必填 | 说明                            |
|---------|---------|------|---------------------------------|
| `path`  | string  | 是   | 相对于用户文件目录的路径          |
| `head`  | integer | 否   | 若提供，仅返回前 N 行            |
| `tail`  | integer | 否   | 若提供，仅返回后 N 行            |

### 8.7.2 读取媒体文件

```
@Tool: readMediaFile
```

读取本地图片或音频文件，返回 base64 编码数据和 MIME 类型。

| 参数   | 类型   | 必填 | 说明                            |
|--------|--------|------|---------------------------------|
| `path` | string | 是   | 相对于用户文件目录的路径          |

### 8.7.3 批量读取

```
@Tool: readMultipleFiles
```

一次读取多个文件，单个失败不影响其他文件。

| 参数    | 类型     | 必填 | 说明                                  |
|---------|----------|------|---------------------------------------|
| `paths` | string[] | 是   | 相对于用户文件目录的路径列表          |

### 8.7.4 写入文件

```
@Tool: writeFile
```

创建新文件或完全覆盖已有文件。父目录不存在会自动创建。写入走 **原子写**（写 `.tmp` 再 `Files.move(ATOMIC_MOVE)`），避免断电导致文件损坏；跨卷时退化。

| 参数      | 类型   | 必填 | 说明                            |
|-----------|--------|------|---------------------------------|
| `path`    | string | 是   | 相对于用户文件目录的路径          |
| `content` | string | 是   | 要写入的文本内容；超过 `file.maxFileSize` 时拒绝写入并返回错误 |

### 8.7.5 编辑文件

```
@Tool: editFile
```

按行编辑：基于精确文本匹配做替换，返回 git 风格的 diff。

| 参数    | 类型                       | 必填 | 说明                                          |
|---------|----------------------------|------|-----------------------------------------------|
| `path`  | string                     | 是   | 相对于用户文件目录的路径                       |
| `edits` | Array<{oldText, newText}>  | 是   | 编辑列表，每项必须含 `oldText` 和 `newText`  |

**唯一性校验**：`oldText` 在文件中出现 >1 次时**直接拒绝**（避免 LLM 误传导致多处被错误替换），并提示"在文件中出现 N 次，请提供更精确的上下文使其唯一"。空 `oldText` 也被拒绝。

### 8.7.6 创建目录

```
@Tool: createDirectory
```

创建新目录（若已存在则幂等成功），支持多级嵌套。

| 参数   | 类型   | 必填 | 说明                            |
|--------|--------|------|---------------------------------|
| `path` | string | 是   | 相对于用户文件目录的路径          |

### 8.7.7 移动文件

```
@Tool: moveFile
```

移动或重命名文件/目录（支持跨目录），使用原子移动。

| 参数          | 类型   | 必填 | 说明                              |
|---------------|--------|------|-----------------------------------|
| `source`      | string | 是   | 源路径（相对于用户文件目录）       |
| `destination` | string | 是   | 目标路径（相对于用户文件目录）     |

### 8.7.8 搜索文件

```
@Tool: searchFiles
```

按 glob 模式（`*.txt`、`**/*.java` 等）递归搜索文件。空模式返回所有文件。

| 参数      | 类型   | 必填 | 说明                            |
|-----------|--------|------|---------------------------------|
| `pattern` | string | 否   | glob 模式，如 `*.txt`           |

### 8.7.9 列出允许目录

```
@Tool: listAllowedDirectories
```

返回当前用户的文件操作目录。其他所有工具的 `path` 参数均相对于此目录。

### 8.7.10 列出目录

```
@Tool: listDirectory
```

列出目录内容，区分 `[FILE]` 和 `[DIR]`。支持 `depth` 参数控制递归深度。

| 参数    | 类型    | 必填 | 说明                                |
|---------|---------|------|-------------------------------------|
| `path`  | string  | 是   | 目录路径（空字符串 = 根目录）        |
| `depth` | integer | 否   | 递归深度（默认 1）                   |

### 8.7.11 列出目录（带大小）

```
@Tool: listDirectoryWithSizes
```

列出目录内容及每项的大小。`[FILE]` 和 `[DIR]` 标记。

| 参数   | 类型   | 必填 | 说明                                |
|--------|--------|------|-------------------------------------|
| `path` | string | 是   | 目录路径（空字符串 = 根目录）        |

### 8.7.12 目录树

```
@Tool: directoryTree
```

返回目录的递归树视图，JSON 格式。

| 参数   | 类型   | 必填 | 说明                                |
|--------|--------|------|-------------------------------------|
| `path` | string | 是   | 目录路径（空字符串 = 根目录）        |

### 8.7.13 文件信息

```
@Tool: getFileInfo
```

返回文件或目录的详细元数据（大小、创建/修改/访问时间、权限，文件还包含行数）。

| 参数   | 类型   | 必填 | 说明                            |
|--------|--------|------|---------------------------------|
| `path` | string | 是   | 相对于用户文件目录的路径          |

### 8.7.14 下载链接

```
@Tool: downloadFileUrl
```

生成原始文件下载链接。自动创建临时 `file_info` 记录（`usage="temp"`）。

| 参数   | 类型   | 必填 | 说明                            |
|--------|--------|------|---------------------------------|
| `path` | string | 是   | 相对于用户文件目录的路径          |

### 8.7.15 预览链接

```
@Tool: viewFileUrl
```

生成在线预览链接。支持 PDF、Word、Excel、PPT、图片、Markdown 等格式。自动创建临时 `file_info` 记录。

| 参数   | 类型   | 必填 | 说明                            |
|--------|--------|------|---------------------------------|
| `path` | string | 是   | 相对于用户文件目录的路径          |

### 8.7.16 删除文件或目录

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

## 9. 数据模型

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

### 9.7 文件存储配置

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `spring.ai.loom.agent.fileBasePath` | string | `.local/file` | 上传文件的根目录 |
| `spring.ai.loom.agent.knowledgeBasePath` | string | `.local/knowledge` | 知识库文件的根目录 |

> 同目录下同名文件自动追加序号：`file.txt` → `file(1).txt` → `file(2).txt`。

### 9.7.1 文件工具配置（`IFileTool`）

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

### 9.8 Maven 构建配置

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

### 9.9 工具组开关

所有内置工具组**默认全部启用**（`matchIfMissing=true`）。在 yml 中将下列任一属性设为 `false` 即可关闭对应工具组。

| 属性                                          | 类型     | 默认值 | 说明                                                                                            |
|-----------------------------------------------|----------|-------|-------------------------------------------------------------------------------------------------|
| `spring.ai.loom.agent.time.enabled`           | boolean  | `true` | 时间工具（`ITimeTool` — 获取当前时间、时区转换）                                                  |
| `spring.ai.loom.agent.file.enabled`           | boolean  | `true` | 文件工具（`IFileTool` — 16 个基于路径的读写/编辑/搜索/删除操作）                                   |
| `spring.ai.loom.agent.skill.enabled`          | boolean  | `true` | 技能工具（`ISkillTool` — 列出技能、获取技能详情）                                                  |
| `spring.ai.loom.agent.git.enabled`            | boolean  | `false` | Git 工具（`IGitTool` — 31 个 git 操作）。**opt-in** —— 端到端部署走 `ICompileAndDeployTool`。 |
| `spring.ai.loom.agent.maven.enabled`          | boolean  | `false` | Maven 工具（同时需要 classpath 上有 `maven-invoker`）。**opt-in** —— 部署场景的编译/打包走 `ICompileAndDeployTool`。 |
| `spring.ai.loom.agent.git.username`           | string   | —     | HTTP(S) Git 认证用户名（clone/pull/push）                                                          |
| `spring.ai.loom.agent.git.token`              | string   | —     | HTTP(S) Git 认证 token / 密码                                                                    |
| `spring.ai.loom.agent.gitUsername`            | string   | —     | **遗留**顶层别名，等价于 `git.username`                                                            |
| `spring.ai.loom.agent.gitToken`               | string   | —     | **遗留**顶层别名，等价于 `git.token`                                                               |

**示例 — 关闭 Git 与 Maven**：

```yaml
spring:
  ai:
    loom:
      agent:
        git:
          enabled: false
        maven:
          enabled: false
```

> 即便工具组被关闭，你仍可以通过自定义 `@Bean IGitTool` / `@Bean IMavenTool` 重新启用 —— `@ConditionalOnMissingBean` 始终优先采用用户提供的 Bean。

### 9.10 端到端部署配置（`ICompileAndDeployTool`）

`ICompileAndDeployTool` 在单次 LLM tool call 内完成 `git clone → mvn package → docker build → docker run → health check` 整条部署流水线。所有配置项均在 `spring.ai.loom.agent.compile.*` 下。

| 属性                                              | 类型     | 默认值       | 说明                                                                                                          |
|---------------------------------------------------|----------|-------------|---------------------------------------------------------------------------------------------------------------|
| `spring.ai.loom.agent.compile.enabled`            | boolean  | `true`      | 是否启用端到端部署工具。默认开启，是部署场景的推荐入口。                                                                            |
| `spring.ai.loom.agent.compile.imageTemplates`     | map<string, ImageTemplate> | `java17` / `java21` / `nginx` / `python3` | 预置基础镜像别名（`ImageTemplate { image, command[] }`），可通过工具入参 `baseImage` 选择。                              |

**基础镜像模板**（可选）：预置 `java17` / `java21` / `nginx` / `python3` 四个模板，可通过 yml 覆盖或新增。工具入参 `baseImage` 传别名即选中对应模板，传完整镜像名（如 `openjdk:17-slim`）则直接用，command 走 java17 兜底。

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
| 16 | `GET` | `/spring/ai/chat/loom/mcp` | 获取 MCP 工具列表 |
| 17 | `GET` | `/spring/ai/loom/skill` | 获取技能列表 |
| 18 | `PUT` | `/spring/ai/loom/skill` | 创建/更新技能 |
| 19 | `GET` | `/spring/ai/loom/skill/{name}` | 获取单个技能 |
| 20 | `DELETE` | `/spring/ai/loom/skill/{name}` | 删除技能 |
| — | `GET` | `/spring/ai/loom` | 重定向到 UI 首页 |
