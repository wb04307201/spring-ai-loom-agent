# Spring AI LoomAgent API Documentation

> **Base URL**: `http://localhost:8080` (default port for the test environment)
> **Version**: 1.1.35
> **Authentication**: The project uses a **BFF (Backend-For-Frontend) + HttpOnly Cookie** auth model. After login, the server sets a `loom-agent-session` cookie via `Set-Cookie` header. The browser automatically includes this cookie in subsequent requests. No token storage or manual header management is required.

---

## Table of Contents

- [1. User Authentication](#1-user-authentication)
- [2. Conversation Management](#2-conversation-management)
- [3. Streaming Chat](#3-streaming-chat)
- [4. File Management](#4-file-management)
- [5. Knowledge Base Management](#5-knowledge-base-management)
- [6. Skill Management](#6-skill-management)
- [7. MCP Tools](#7-mcp-tools)
- [8. Terminal Management](#8-terminal-management)
- [9. Data Models](#9-data-models)
- [10. Configuration Properties](#10-configuration-properties)

---

## 1. User Authentication

### 1.1 Check Auto-Login Status

```
POST /spring/ai/loom/user/isAutoLogin
```

**Request Body**: None

**Response**: `boolean`

| Value     | Description               |
|-----------|---------------------------|
| `true`    | Auto-login is enabled     |
| `false`   | Auto-login is not enabled |

---

### 1.2 User Login

```
POST /spring/ai/loom/user/login
```

**Request Body**:

| Field      | Type   | Required | Description   |
|------------|--------|----------|---------------|
| `username` | string | Yes      | Username      |
| `verified` | string | No       | Verification info |

**Example**:

```json
{
  "username": "testuser",
  "verified": ""
}
```

**Response** (`UserResponseRecord`):

| Field      | Type   | Description     |
|------------|--------|-----------------|
| `token`    | string | Session token (also set as HttpOnly cookie) |
| `nickname` | string | User nickname   |

**Response Headers**:
- `Set-Cookie: loom-agent-session=<token>; Max-Age=86400; Path=/; HttpOnly; SameSite=Lax`

**Example**:

```json
{
  "token": "567fb50c-b293-403b-a903-f6b7b597c318",
  "nickname": "用户"
}
```

---

### 1.3 User Logout

```
POST /spring/ai/loom/user/logout
```

**Request Body**: None

**Response**: `true` (boolean)

**Effect**: The session token is invalidated server-side and the `loom-agent-session` cookie is cleared (Max-Age=0).

---

## 2. Conversation Management

### 2.1 List Conversations

```
GET /spring/ai/loom/conversation
```

**Headers**: Must include authentication info.

**Response**: `ConversationRecord[]`

| Field            | Type   | Description        |
|------------------|--------|--------------------|
| `conversationId` | string | Conversation ID    |
| `title`          | string | Conversation title |

**Example**:

```json
[
  {
    "conversationId": "conv-001",
    "title": "First Conversation"
  }
]
```

---

### 2.2 Get Conversation History

```
GET /spring/ai/loom/conversation/{conversationId}
```

**Path Parameters**:

| Parameter        | Type   | Description     |
|------------------|--------|-----------------|
| `conversationId` | string | Conversation ID |

**Response**: `Message[]` — Spring AI chat memory message list.

---

### 2.3 Delete Conversation

```
DELETE /spring/ai/loom/conversation/{conversationId}
```

**Path Parameters**:

| Parameter        | Type   | Description     |
|------------------|--------|-----------------|
| `conversationId` | string | Conversation ID |

**Response**: `true` (boolean)

---

## 3. Streaming Chat

### 3.1 SSE Streaming Chat

```
POST /spring/ai/loom/stream
Content-Type: application/json
Accept: text/event-stream
```

**Request Body** (`ChatRequestRecord`):

| Field            | Type     | Required | Description                                      |
|------------------|----------|----------|--------------------------------------------------|
| `message`        | string   | Yes      | User message content                             |
| `conversationId` | string   | No       | Conversation ID. A new session is created if omitted. |
| `mcps`           | string[] | No       | List of MCP tool names to enable                 |
| `knowledgeId`    | string   | No       | Knowledge base ID for RAG retrieval              |
| `fileIds`        | string[] | No       | List of associated file IDs (supports multiple files) |

**Example**:

```json
{
  "message": "Please summarize this document",
  "conversationId": "conv-001",
  "knowledgeId": "kb-001",
  "mcps": [],
  "fileIds": ["file-abc123", "file-def456"]
}
```

**Response**: SSE (Server-Sent Events) stream.

Each event returns a `ChatResponseRecord`:

| Field              | Type   | Description                    |
|--------------------|--------|--------------------------------|
| `content`          | string | AI response text fragment      |
| `reasoningContent` | string | Reasoning/thinking trace (optional) |

**SSE Event Example**:

```
data: {"content":"Hello","reasoningContent":""}

data: {"content":"!","reasoningContent":""}

data: {"content":"How can","reasoningContent":""}

data: {"content":"I help you?","reasoningContent":""}
```

---

## 4. File Management

### 4.1 Upload File

```
POST /spring/ai/loom/file/upload
Content-Type: multipart/form-data
```

**Form Fields**:

| Field  | Type | Required | Description       |
|--------|------|----------|-------------------|
| `file` | file | Yes      | File to upload    |

**Response**:

| Field    | Type   | Description                              |
|----------|--------|------------------------------------------|
| `fileId` | string | File ID returned after upload            |
| `status` | string | Upload status, `"success"` on success    |

**Example**:

```json
{
  "fileId": "file-abc123",
  "status": "success"
}
```

---

### 4.2 List File Tree

```
GET /spring/ai/loom/file
GET /spring/ai/loom/file/tree
```

**Response**: Directory tree JSON (recursive structure).

| Field      | Type   | Description                          |
|------------|--------|--------------------------------------|
| `name`     | string | File or directory name               |
| `type`     | string | `"file"` or `"directory"`            |
| `size`     | number | File size in bytes (files only)      |
| `children` | array  | Child items (directories only)       |

**Example**:

```json
{
  "name": ".",
  "type": "directory",
  "children": [
    {
      "name": "report.pdf",
      "type": "file",
      "size": 102400
    },
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

### 4.3 View File by Path

```
GET /spring/ai/loom/file/by-path/view?path=report.pdf
```

**Query Parameters**:

| Parameter | Type   | Required | Description                                          |
|-----------|--------|----------|------------------------------------------------------|
| `path`    | string | Yes      | File path relative to user's file directory          |

**Response**: `307 Temporary Redirect` → `/file/view/{fileId}` (WOPI file viewer).

> If the file is not yet registered in `file_info`, a temporary record (`usage="temp"`) is auto-created to obtain a `fileId`.

---

### 4.4 Download File by Path

```
GET /spring/ai/loom/file/by-path/download?path=report.pdf
```

**Query Parameters**:

| Parameter | Type   | Required | Description                                          |
|-----------|--------|----------|------------------------------------------------------|
| `path`    | string | Yes      | File path relative to user's file directory          |

**Response**: `307 Temporary Redirect` → `/wopi/files/{fileId}/contents`.

---

### 4.5 Download File by ID

```
GET /spring/ai/loom/file/{id}/download
```

**Path Parameters**:

| Parameter | Type   | Description |
|-----------|--------|-------------|
| `id`      | string | File ID     |

**Response**: File binary stream, `Content-Disposition` header includes original filename.

---

## 5. Knowledge Base Management

### 5.1 Check Knowledge Upload Status

```
GET /spring/ai/loom/knowledge/checkKnowledgeUpload
```

**Response**: `boolean` — Whether knowledge upload functionality is available.

---

### 5.2 List Knowledge Bases

```
GET /spring/ai/loom/knowledge
```

**Response**: `KnowledgeRecord[]`

| Field      | Type   | Description            |
|------------|--------|------------------------|
| `id`       | string | Knowledge base ID      |
| `username` | string | Creator username       |
| `name`     | string | Knowledge base name    |

**Example**:

```json
[
  {
    "id": "kb-001",
    "username": "admin",
    "name": "Product Docs"
  }
]
```

---

### 5.3 Create Knowledge Base

```
PUT /spring/ai/loom/knowledge
Content-Type: application/json
```

**Request Body** (`KnowledgeRecord`):

| Field  | Type   | Required | Description         |
|--------|--------|----------|---------------------|
| `name` | string | Yes      | Knowledge base name |

**Example**:

```json
{
  "name": "Product Docs"
}
```

**Response**: The created `KnowledgeRecord` (including the generated `id`).

---

### 5.4 Delete Knowledge Base

```
DELETE /spring/ai/loom/knowledge/{knowledgeId}
```

**Path Parameters**:

| Parameter     | Type   | Description       |
|---------------|--------|-------------------|
| `knowledgeId` | string | Knowledge base ID |

> Deleting a knowledge base cascades to clean up associated files and vector data.

**Response**: `number` — Number of deleted records.

---

### 5.5 Upload File to Knowledge Base

```
POST /spring/ai/loom/knowledge/{knowledgeId}/upload
Content-Type: multipart/form-data
```

**Path Parameters**:

| Parameter     | Type   | Description       |
|---------------|--------|-------------------|
| `knowledgeId` | string | Knowledge base ID |

**Form Fields**:

| Field  | Type | Required | Description       |
|--------|------|----------|-------------------|
| `file` | file | Yes      | File to upload    |

**Response**:

```json
{
  "fileId": "file-xyz789",
  "status": "success"
}
```

---

### 5.6 List Files in Knowledge Base

```
GET /spring/ai/loom/knowledge/{knowledgeId}/file
```

**Path Parameters**:

| Parameter     | Type   | Description       |
|---------------|--------|-------------------|
| `knowledgeId` | string | Knowledge base ID |

**Response**: `FileRecord[]` (same format as [4.2](#42-list-files)).

---

### 5.7 Delete File from Knowledge Base

```
DELETE /spring/ai/loom/knowledge/{knowledgeId}/file/{fileId}
```

**Path Parameters**:

| Parameter     | Type   | Description       |
|---------------|--------|-------------------|
| `knowledgeId` | string | Knowledge base ID |
| `fileId`      | string | File ID           |

**Response**: `number` — Number of deleted records.

---

## 6. Skill Management

> All skills live in the database (tables `market_skill` / `user_skill` / `role_skill`). The yml `skills[]` block is no longer read — it was replaced by 6 system-seeded `market_skill` rows plus an admin-controlled market workflow.
>
> Three sources show up in the `source` field of `user_skill`:
> - `USER_CREATED` — created by the user via API or chat UI; **fully editable** (name/desc/content/default_loaded)
> - `MARKET_PULLED` — pulled from the approved market; **editable desc / default_loaded only** (content is locked to the market snapshot; re-pull to update)
> - `ROLE_GRANTED` — auto-injected from a role authorization; **read-only** (locked=true; cannot edit or delete)
> - `MARKET_VIEW` — admin-only union view that bundles all `APPROVED` market skills + admin's own `PENDING`; shown with a `市` badge in the chat UI; not actually written to `user_skill`
>
> Every list/get call auto-syncs `role_skill` → `user_skill` for the current user, so a freshly authorized skill is visible immediately on next list.

---

### 6.1 List Current User's Skills

```
GET /spring/ai/loom/skill
```

**Response**: `SkillRecord[]` (the LLM-facing view)

| Field         | Type    | Description                                                                                                |
|---------------|---------|------------------------------------------------------------------------------------------------------------|
| `name`        | string  | Skill name                                                                                                 |
| `description` | string  | Skill description                                                                                          |
| `load`        | boolean | Whether the LLM preloads this skill into the system prompt                                                  |
| `content`     | string  | Skill content (resolved: if stored as `classpath:xxx`, the resource is read at this point)                 |
| `source`      | string  | `USER_CREATED` / `MARKET_PULLED` / `ROLE_GRANTED` / (admin only) `MARKET_VIEW`                              |

For admins, this list also includes the `MARKET_VIEW` union (all approved + own PENDING).

---

### 6.2 Create / Overwrite a Skill (USER_CREATED)

```
PUT /spring/ai/loom/skill
Content-Type: application/json
```

**Request Body** (`SkillRecord`):

| Field         | Type    | Required | Description                                                                              |
|---------------|---------|----------|------------------------------------------------------------------------------------------|
| `name`        | string  | Yes      | Skill name                                                                               |
| `description` | string  | No       | Skill description                                                                        |
| `load`        | boolean | No       | Whether the LLM preloads this skill; defaults to `true`                                  |
| `content`     | string  | Yes      | Skill content / prompt template (supports `classpath:` prefix which is resolved on read) |

> `{param}` placeholders inside `content` are LLM-interpreted at runtime, not declared as structured form fields. MCP tool references inside `content` use `@tool_name` and resolve to the role-authorized MCPs.

**Example**:

```json
{
  "name": "email_writer",
  "description": "Professional email writing assistant",
  "load": true,
  "content": "You are an email assistant. The recipient is {recipient}..."
}
```

**Response**: `true` (boolean) — `false` if a `ROLE_GRANTED` skill with the same name is locked.

---

### 6.3 Patch Description / Default-Loaded

```
PATCH /spring/ai/loom/skill/{name}
Content-Type: application/json
```

For `MARKET_PULLED` and `USER_CREATED` skills — change `description` and/or `default_loaded` without overwriting content. Returns `400` if the skill is `ROLE_GRANTED` (locked).

**Request Body** (`UserSkillPatchRequest`):

| Field           | Type    | Description                                       |
|-----------------|---------|---------------------------------------------------|
| `description`   | string  | New description (omit to leave unchanged)         |
| `defaultLoaded` | boolean | New default-loaded flag (omit to leave unchanged) |

---

### 6.4 Get a Single Skill (LLM-facing)

```
GET /spring/ai/loom/skill/{name}
```

**Path Parameters**:

| Parameter | Type   | Description |
|-----------|--------|-------------|
| `name`    | string | Skill name  |

**Response**: `SkillRecord`. For admins, falls back to the market view if no local copy exists.

---

### 6.5 Delete a Skill

```
DELETE /spring/ai/loom/skill/{name}
```

Returns `400` if the skill is `ROLE_GRANTED` (locked). Returns `true` on success.

---

### 6.6 Trigger Role Sync Manually

```
POST /spring/ai/loom/skill/sync
```

Re-runs the `role_skill` → `user_skill` sync for the current user. Mostly for debugging — the same sync runs automatically on every list/get.

---

### 6.7 Skill Market — Browse (any user)

```
GET /spring/ai/loom/market-skills
```

Returns all `market_skill` rows with `status='APPROVED'`, ordered by `author, name, version DESC`. Each item has the full `MarketSkill` model (`id`, `name`, `description`, `content`, `version`, `author`, `status`, `submittedAt`, `reviewedAt`, `reviewedBy`, `reviewComment`).

---

### 6.8 Skill Market — View One

```
GET /spring/ai/loom/market-skills/{id}
```

**Path Parameters**: `id` = `market_skill.id` (Long)

---

### 6.9 Skill Market — Pull into My `user_skill`

```
POST /spring/ai/loom/market-skills/{id}/pull
```

Creates / updates a `MARKET_PULLED` `user_skill` row from the given `market_skill`. Throws `400` if:
- The market skill isn't `APPROVED`
- A `ROLE_GRANTED` lock with the same name already exists
- The same name is already in your `user_skill` (refreshes content silently)

---

### 6.10 Submit My Skill to Market

```
POST /spring/ai/loom/user/market-skills
Content-Type: application/json
```

Submits a new `market_skill` row with `status=PENDING` and `author=currentUser`.

**Request Body** (`MarketSkillSubmitRequest`):

| Field        | Type   | Required | Description                                        |
|--------------|--------|----------|----------------------------------------------------|
| `name`       | string | Yes      | Skill name                                         |
| `description`| string | No       | Description                                        |
| `content`    | string | Yes      | Prompt template                                    |
| `version`    | string | Yes      | SemVer-ish version string                          |

Constraint: `(author, name, version)` must be unique. Pending duplicates require re-submitting under a new version.

---

### 6.11 Admin — Market CRUD (any status)

> Admin-only. Auth-gated by `auth.adminPathPatterns` and double-checked in the handler.

| Method | Path                                              | Description                                       |
|--------|---------------------------------------------------|---------------------------------------------------|
| GET    | `/spring/ai/loom/admin/market-skills`             | List **all** (PENDING/APPROVED/REJECTED)        |
| GET    | `/spring/ai/loom/admin/market-skills/pending`     | List only `PENDING`                              |
| POST   | `/spring/ai/loom/admin/market-skills`             | Create directly with `status=APPROVED` (skip review). Body: `MarketSkillUpsertRequest` |
| PUT    | `/spring/ai/loom/admin/market-skills/{id}`        | Edit any field of any market skill                |
| DELETE | `/spring/ai/loom/admin/market-skills/{id}`        | Cascade-deletes from `user_skill` and `role_skill` |
| POST   | `/spring/ai/loom/admin/market-skills/{id}/approve`| Approve a PENDING submission                     |
| POST   | `/spring/ai/loom/admin/market-skills/{id}/reject` | Reject a PENDING submission. Body: `{comment}`    |

`MarketSkillUpsertRequest`:

| Field      | Type   | Required | Description                                          |
|------------|--------|----------|------------------------------------------------------|
| `name`     | string | Yes      | Skill name                                           |
| `description`| string| No       | Description                                          |
| `content`  | string | Yes      | Prompt template                                      |
| `version`  | string | Yes      | Version string                                       |
| `status`   | string | No       | Defaults to `APPROVED` if omitted                     |

---

### 6.12 Admin — Authorize a Skill to a Role

```
GET /spring/ai/loom/admin/roles/{code}/skills
PUT /spring/ai/loom/admin/roles/{code}/skills
```

`GET` returns the role's currently authorized `market_skill` list as `RoleSkillItem[]` (each item has `marketSkillId` + `defaultLoaded`).

`PUT` replaces the whole list. Request body:

```json
{
  "items": [
    {"marketSkillId": 1, "defaultLoaded": true},
    {"marketSkillId": 5, "defaultLoaded": false}
  ]
}
```

`defaultLoaded` defaults to `true` if omitted. The role's users will see these skills injected into their `user_skill` (source=`ROLE_GRANTED`, locked=true) on their next list/sync.

---

## 7. MCP Tools

### 7.1 List MCP Servers and Tools

```
GET /spring/ai/chat/loom/mcp
```

**Response**: `McpRecord[]`

| Field             | Type         | Description                                      |
|-------------------|--------------|--------------------------------------------------|
| `name`            | string       | MCP server name                                  |
| `title`           | string       | MCP server title (display label)                 |
| `version`         | string       | MCP version                                      |
| `description`     | string       | MCP description                                  |
| `defaultSelected` | boolean      | Whether selected by default                      |
| `tools`           | ToolRecord[] | Tool list                                        |

**ToolRecord**:

| Field         | Type   | Description     |
|---------------|--------|-----------------|
| `name`        | string | Tool name       |
| `description` | string | Tool description |

**Example**:

```json
[
  {
    "name": "weather-mcp",
    "title": "Weather",
    "version": "1.1.35",
    "description": "Provides real-time weather query service",
    "defaultSelected": true,
    "tools": [
      {
        "name": "getWeather",
        "description": "Query current weather for a specified city"
      }
    ]
  }
]
```

---

## 8. Terminal Management

### 8.1 Start Process

```
@Tool: startProcess
```

Start a terminal process or REPL session. Supports two modes: **Shell mode** (one-shot command like `ls`, `cat`) and **REPL mode** (long-running interactive session like `python`, `node`). REPL mode uses PTY (pseudo-terminal) when available for full terminal interaction.

| Parameter    | Type    | Required | Description                                                                                     |
|--------------|---------|----------|-------------------------------------------------------------------------------------------------|
| `command`    | string  | Yes      | Command to execute. Shell mode: any shell command. REPL mode: interpreter (e.g. `python`, `node`) |
| `workingDir` | string  | No       | Working directory (default: `.local/file/{username}/`)                                          |
| `repl`       | boolean | No       | Whether REPL mode. `true` = long interactive session; `false`/omitted = one-shot command        |
| `timeout`    | long    | No       | Wait timeout in milliseconds (default 30000ms)                                                   |

---

### 8.2 Interact with Process

```
@Tool: interactWithProcess
```

Send input to a running REPL session and wait for response.

| Parameter    | Type    | Required | Description                                  |
|--------------|---------|----------|----------------------------------------------|
| `sessionId`  | string  | Yes      | Session ID (returned by `startProcess`)      |
| `input`      | string  | Yes      | Input to send (newline auto-appended)        |
| `timeout`    | long    | No       | Response wait timeout in ms (default 10000)  |

---

### 8.3 Read Process Output

```
@Tool: readProcessOutput
```

Read output from a running process. Supports three modes: `new` (unread content since last read, default), `tail` (last N lines), `absolute` (from character position N).

| Parameter    | Type    | Required | Description                                                                |
|--------------|---------|----------|----------------------------------------------------------------------------|
| `sessionId`  | string  | Yes      | Session ID                                                                 |
| `mode`       | string  | No       | Read mode: `new` / `tail` / `absolute`                                     |
| `position`   | int     | No       | Absolute character position (only when `mode=absolute`)                    |
| `lines`      | int     | No       | Number of lines (only when `mode=tail`, default 50)                        |

---

### 8.4 Force Terminate

```
@Tool: forceTerminate
```

Force-terminate a managed terminal session.

| Parameter    | Type    | Required | Description    |
|--------------|---------|----------|----------------|
| `sessionId`  | string  | Yes      | Session ID     |

---

### 8.5 List Sessions

```
@Tool: listSessions
```

List all active terminal sessions for the current user.

---

### 8.6 Get Process Info

```
@Tool: getProcessInfo
```

Get detailed info for a single session, including full output, process state, working directory, PTY mode, etc.

| Parameter    | Type    | Required | Description    |
|--------------|---------|----------|----------------|
| `sessionId`  | string  | Yes      | Session ID     |

---

### 8.7 Send Signal

```
@Tool: sendSignal
```

Send a control signal to a terminal session. PTY mode supports: `interrupt` (Ctrl+C), `eof` (Ctrl+D), `quit` (Ctrl+\\). Non-PTY mode only supports `interrupt` via `destroy`.

| Parameter    | Type    | Required | Description                                              |
|--------------|---------|----------|----------------------------------------------------------|
| `sessionId`  | string  | Yes      | Session ID                                               |
| `signal`     | string  | Yes      | Signal type: `interrupt` / `eof` / `quit`                |

---

### 8.8 List System Processes

```
@Tool: listProcesses
```

List all running OS processes (like `ps` or Task Manager). Supports pagination.

| Parameter     | Type   | Required | Description                              |
|---------------|--------|----------|------------------------------------------|
| `maxResults`  | int    | No       | Max results per page (default 50, max 200) |
| `page`        | int    | No       | Page number, 0-based (default 0)          |

---

### 8.9 Kill Process

```
@Tool: killProcess
```

Force-terminate a system process by PID.

| Parameter | Type    | Required | Description                                      |
|-----------|---------|----------|--------------------------------------------------|
| `pid`     | long    | Yes      | Process ID                                       |
| `force`   | boolean | No       | Whether to use forceful termination (default true) |

---

## 9. Data Models

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

> **Note**: The `token` is also set as an HttpOnly `Set-Cookie` header (`loom-agent-session`). The browser will automatically include this cookie in subsequent requests. Clients do not need to store or send the token manually.

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

> The response shape is the same as the PUT request body (`name` / `description` / `load` / `content`), plus a `source` field set by the server to indicate where the skill was loaded from. `source` is one of `USER_CREATED` / `MARKET_PULLED` / `ROLE_GRANTED` / `MARKET_VIEW`.

---

## 10. Configuration Properties

All properties are prefixed with `spring.ai.loom.agent` in `application.yml`.

### 10.1 Basic Configuration

| Property                                  | Type    | Default                 | Description                        |
|-------------------------------------------|---------|-------------------------|------------------------------------|
| `spring.ai.loom.agent.defaultSystem`      | string  | Skill discovery prompt  | Default system prompt              |
| `spring.ai.loom.agent.init`               | boolean | `true`                  | Whether to initialize the ChatClient |

### 10.2 RAG Configuration

| Property                                                  | Type    | Default | Description                                    |
|-----------------------------------------------------------|---------|---------|------------------------------------------------|
| `spring.ai.loom.agent.rag.similarityThreshold`            | double  | `0.0`   | Vector retrieval similarity threshold          |
| `spring.ai.loom.agent.rag.topK`                           | int     | `4`     | Number of top results to retrieve              |
| `spring.ai.loom.agent.rag.defaultPromptTemplate`          | string  | —       | Default RAG prompt template                    |
| `spring.ai.loom.agent.rag.defaultEmptyContextPromptTemplate` | string | —    | Default response when no context is available  |
| `spring.ai.loom.agent.rag.enabledKeyword`                 | boolean | `false` | Whether to enable keyword retrieval            |
| `spring.ai.loom.agent.rag.enabledSummary`                 | boolean | `false` | Whether to enable summary generation           |

### 10.3 MCP Configuration

`spring.ai.loom.agent.mcps` is an array. Each entry contains:

| Property                | Type    | Description                          |
|-------------------------|---------|--------------------------------------|
| `name`                  | string  | MCP server name                      |
| `title`                 | string  | Display label                        |
| `description`           | string  | Description info                     |
| `defaultSelected`       | boolean | Whether selected by default          |
| `tools[].name`          | string  | Tool name                            |
| `tools[].description`   | string  | Tool description                     |

### 10.4 Skill Configuration (no longer read from yml)

The `spring.ai.loom.agent.skills[]` yml block is **no longer read**. See [§6 Skill Management](#6-skill-management) for the database-driven flow. 6 system skills are seeded on first launch.

### 10.5 JVector Configuration

| Property                                        | Type   | Default                | Description                  |
|-------------------------------------------------|--------|------------------------|------------------------------|
| `spring.ai.loom.agent.jvector.indexPath`          | string | `.local/jvector-index` | Vector index storage path    |
| `spring.ai.loom.agent.jvector.m`                  | int    | `16`                   | HNSW graph parameter M       |
| `spring.ai.loom.agent.jvector.efConstruction`    | int    | `100`                  | ef parameter at build time   |
| `spring.ai.loom.agent.jvector.efSearch`          | int    | `10`                   | ef parameter at search time  |

### 10.6 Authentication Configuration

| Property                              | Type    | Default                | Description                                          |
|---------------------------------------|---------|------------------------|------------------------------------------------------|
| `spring.ai.loom.agent.auth.enabled`   | boolean | `true`                 | Authentication master switch                         |
| `spring.ai.loom.agent.auth.pathPatterns` | string[] | `["/spring/ai/loom/**"]` | Paths requiring authentication (Ant-style patterns)  |
| `spring.ai.loom.agent.auth.excludePathPatterns` | string[] | (see below) | Paths excluded from authentication |
| `spring.ai.loom.agent.auth.cookie.name` | string  | `loom-agent-session`   | Session cookie name                                  |
| `spring.ai.loom.agent.auth.cookie.maxAge` | int    | `86400`                | Cookie max age in seconds (24 hours)                 |

### 10.7 File Storage Configuration

| Property                              | Type    | Default                | Description                                          |
|---------------------------------------|---------|------------------------|------------------------------------------------------|
| `spring.ai.loom.agent.fileBasePath`   | string  | `.local/file`          | Root directory for uploaded files                    |
| `spring.ai.loom.agent.knowledgeBasePath` | string | `.local/knowledge`    | Root directory for knowledge base files              |

> Files uploaded to the same directory with duplicate names are automatically renamed with a suffix: `file.txt` → `file(1).txt` → `file(2).txt`.

### 10.7.1 File Tool Configuration (`IFileTool`)

The file tool (`IFileTool`) is configured via `spring.ai.loom.agent.file.*` with a set of default safety/resource limits tailored for LLM tool-call scenarios.

| Property                                          | Type     | Default                                                                                                | Description                                                                                                                                                                                                                                                                              |
|---------------------------------------------------|----------|--------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `spring.ai.loom.agent.file.enabled`               | boolean  | `true`                                                                                                 | Whether to enable the file tool                                                                                                                                                                                                                                                          |
| `spring.ai.loom.agent.file.maxFileSize`           | long     | `5242880` (5 MB)                                                                                       | Per-call upper bound on file read/write size (bytes). Exceeding this is rejected outright, **to avoid OOM and LLM context overflow**.                                                                                                                                                   |
| `spring.ai.loom.agent.file.maxMediaSize`          | long     | `1048576` (1 MB)                                                                                       | Upper bound on media files (images / audio). Base64-encoded size ≈ 4/3 of the original, so the limit is stricter than for text.                                                                                                                                                          |
| `spring.ai.loom.agent.file.maxWalkDepth`          | int      | `5`                                                                                                    | Upper bound on depth for `directoryTree` / recursive listing / search.                                                                                                                                                                                                                   |
| `spring.ai.loom.agent.file.maxWalkEntries`        | int      | `1000`                                                                                                 | Upper bound on entries returned per `listDirectory` / `directoryTree` call.                                                                                                                                                                                                              |
| `spring.ai.loom.agent.file.maxSearchResults`      | int      | `500`                                                                                                 | Upper bound on `searchFiles` hit count.                                                                                                                                                                                                                                                  |
| `spring.ai.loom.agent.file.deleteConfirmToken`    | string   | `I_CONFIRM_DELETE`                                                                                     | The `deleteFileOrDirectory` tool requires this exact string as an explicit argument before it executes (**guards against accidental LLM deletions**). Can be replaced with a shorter token (e.g. `YES`) to save tokens.                                                                     |
| `spring.ai.loom.agent.file.excludedDirs`          | string[] | `[".git", "node_modules", "target", "build", "dist", ".idea", ".vscode", ".gradle", "out", "bin"]`     | Directory names skipped during traversal (exact match, not glob). Keeps `directoryTree` / `searchFiles` from dumping tens of thousands of `target/classes/*.class` entries on a Spring Boot project.                                                                                  |

**Safety mechanisms**:

- All path resolution is delegated to `PathSecurityUtils.assertInsideUserDir(resolved, userDir, mustExist)`, which uniformly handles:
  - `..` traversal (a single `Path.normalize` defeats it)
  - **Symlink escape** (`Path.toRealPath` follows the chain) — even if the user drops a symlink inside `userDir` pointing at `C:\Windows`, the tool will not read through it
  - Size-bypass on case-insensitive filesystems (Windows / macOS)
- Atomic writes: `writeFile` / `editFile` write to a `.tmp` file and then `Files.move(ATOMIC_MOVE)` it into place, so a mid-write power loss won't corrupt the target. Falls back to non-atomic replacement across volumes.
- `editFile` uniqueness check: if `oldText` matches more than once in the file the call is rejected, forcing the LLM to provide more precise surrounding context.

### 10.8 End-to-End Deployment Configuration (`ICompileAndDeployTool`)

`ICompileAndDeployTool` performs the full deployment pipeline in a single LLM tool call: `git clone → buildTool build (maven / npm / pip) → docker build → docker run → health check`. Supports Maven, Node.js (backend and static-frontend → nginx), and Python projects. All settings live under `spring.ai.loom.agent.compile.*`.

| Property                                          | Type     | Default                          | Description                                                                                                  |
|---------------------------------------------------|----------|----------------------------------|--------------------------------------------------------------------------------------------------------------|
| `spring.ai.loom.agent.compile.enabled`            | boolean  | `true`                           | Whether to register the end-to-end deploy tool (default enabled)                                            |
| `spring.ai.loom.agent.compile.mavenHome`          | string   | auto-discover                    | Optional Maven install dir; falls back to `maven.mavenHome` and PATH                                         |
| `spring.ai.loom.agent.compile.dockerCmd`          | string   | `docker`                         | Optional override for the docker CLI binary                                                                  |
| `spring.ai.loom.agent.compile.imageTemplates`     | map      | (6 pre-set templates)            | Pre-set base-image templates keyed by alias; see below                                                       |
| `spring.ai.loom.agent.compile.extraRunArgs`       | string[] | `[]`                             | Extra `docker run` args injected between `--name` and the image name                                         |

**Base-image templates** (built-in):

| Alias      | Image                                    | Default ENTRYPOINT                          |
|------------|------------------------------------------|---------------------------------------------|
| `java17`   | `eclipse-temurin:17-jre-alpine`          | `["java","-jar","app.jar"]`                 |
| `java21`   | `eclipse-temurin:21-jre-alpine`          | `["java","-jar","app.jar"]`                 |
| `nginx`    | `nginx:1.27-alpine`                      | `["nginx","-g","daemon off;"]`              |
| `python3`  | `python:3.12-slim`                       | `["python","app.py"]`                       |
| `node20`   | `node:20-alpine`                         | `["node","dist/index.js"]`                   |
| `node20-serve` | `nginx:1.27-alpine`                  | `["nginx","-g","daemon off;"]`              |

Tool-call parameters (Map, case-insensitive, all optional except `gitUrl`, `port`, and `containerPort`):

- `gitUrl` (required) — git repository URL
- `gitUsername` / `gitPassword` — credentials for private repos
- `branch` — branch to clone (defaults to remote HEAD)
- `port` — host port the container will publish (the port the caller accesses via `http://localhost:{port}/{healthPath}`)
- `containerPort` — Container port the application listens on inside the container (required, no yml fallback; reference `server.port` in application.yml)
- `subDir` — Subdirectory of a multi-module repo to deploy (**required** when root has no `pom.xml` — tool returns `fail` otherwise)
- `buildTool` — Build tool / project type: `maven` / `npm` / `npm-frontend` / `pip` (optional; auto-detected from `pom.xml` / `package.json` / `requirements.txt` / `pyproject.toml`)
- `imageName` / `containerName` — Docker image and container names (defaults derived from timestamp)
- `healthPath` — both the health-check path and the access URL path (e.g. `healthPath=sql-forge-demo` → `http://localhost:8080/sql-forge-demo`)
- `baseImage` — template alias (`java17`/`java21`/`nginx`/`python3`/`node20`/`node20-serve`) or full image name (e.g. `openjdk:17-slim`)
- `runCommand` — string array overriding the template's default ENTRYPOINT (rare)

Example yml overriding the default templates:

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

Example tool invocation:

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

### 10.9 Git Configuration (`IGitTool`)

`IGitTool` provides Git operations (init, clone, status, commit, branch, etc.) via Eclipse JGit. **Disabled by default** — opt in with `git.enabled=true`.

| Property                              | Type    | Default                | Description                                          |
|---------------------------------------|---------|------------------------|------------------------------------------------------|
| `spring.ai.loom.agent.git.enabled`    | boolean | `false`                | Whether to register the Git tool (default false; set to true to enable) |
| `spring.ai.loom.agent.git.username`   | string  | —                      | Git username for remote authentication               |
| `spring.ai.loom.agent.git.token`      | string  | —                      | Git token / password for remote authentication       |

Example:

```yaml
spring:
  ai:
    loom:
      agent:
        git:
          enabled: true   # default false; set to true to enable
          username: your-username
          token: your-token
```

### 10.10 Maven Build Configuration

| Property                                          | Type     | Default    | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
|---------------------------------------------------|----------|------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `spring.ai.loom.agent.maven.enabled`              | boolean  | `false`    | Whether to register the Maven tool (**opt-in**) — compile/package for deployment scenarios is handled by `ICompileAndDeployTool`                                                                                                                                                                                                                                                                                                                                                                              |
| `spring.ai.loom.agent.maven.mavenHome`            | string   | —          | Maven install directory. **When empty, the tool auto-discovers**: it tries the `MAVEN_HOME` / `M2_HOME` environment variables first, then scans common Windows paths (e.g. `C:\developer\apache-maven-*`, `C:\Program Files\Apache Maven`). Auto-discovery does **not** rely on the system `PATH`, so a broken or shadowing `mvn` wrapper (e.g. a global npm `mvn`) won't cause `maven-invoker` to throw `Error configuring command line`.                                                                    |
| `spring.ai.loom.agent.maven.localRepository`      | string   | —          | Local repository path (uses the default path when empty)                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `spring.ai.loom.agent.maven.maxOutputLines`       | int      | `200`      | Maximum output lines (truncated when exceeded)                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `spring.ai.loom.agent.maven.defaultTimeoutMs`     | long     | `300000`   | Default execution timeout in milliseconds (5 minutes)                                                                                                                                                                                                                                                                                                                                                                                                                                                       |

> All Maven tool operations are scoped to `{fileBasePath}/{username}/`; paths outside that range are rejected.
>
> **Troubleshooting tip — `MavenInvocationException: Error configuring command line`**: this means `maven-invoker` could not find a usable `mvn` / `mvn.cmd`. The tool startup log prints the resolved `mavenHome` together with a diagnostic hint listing every path it searched, the environment variables it looked at, and how to fix it. The most common cause is a broken or shadowing `mvn` on `PATH` (e.g. a global npm `mvn` wrapper); in that case, explicitly set `spring.ai.loom.agent.maven.mavenHome` in `application.yml` to point at the real Maven install directory to bypass it.
>
> **Troubleshooting tip — "file is locked" errors when deleting the project directory on Windows**: in older versions this was caused by `maven-invoker 3.3.0` / `plexus-utils 3.3.0` because (a) the JVM shutdown hook they register on the exception/cancel path never releases the held `Process` reference, and (b) `Invoker.execute()` does not expose the child-process handle, so it cannot propagate cancel/timeout down to the mvn child process. The result: a cancelled or timed-out Maven call would leave the mvn child running and continuing to hold mmap handles on `target/classes` and `~/.m2/repository/*.jar`, locking those files on Windows. **The new version no longer uses `Invoker.execute()` to run the process** — it forks mvn directly with `ProcessBuilder`, does a clean timeout via `Process.waitFor(timeout, unit)`, then calls `Process.destroyForcibly()` and explicitly closes the streams on timeout. **No JVM shutdown hook is registered any more, so the mvn child is always killed on timeout/cancel.** If you still see locks after upgrading, it is most likely an orphan mvn process left behind by a previous JVM — find it with `tasklist /FI "IMAGENAME eq cmd.exe"` and `taskkill /F /PID <pid>` it.

### 10.11 Tool Group Switches

All built-in tool groups are **enabled by default** (`matchIfMissing=true`). Set any of the following properties to `false` in yml to turn off the corresponding tool group.

| Property                                | Type     | Default | Description                                                                                                                                                                       |
|-----------------------------------------|----------|---------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `spring.ai.loom.agent.time.enabled`     | boolean  | `true`  | Time tool (`ITimeTool` — get current time, convert between timezones)                                                                                                            |
| `spring.ai.loom.agent.file.enabled`     | boolean  | `true`  | File tool (`IFileTool` — 16 path-based read/write/edit/search/delete operations)                                                                                                 |
| `spring.ai.loom.agent.skill.enabled`    | boolean  | `true`  | Skill tool (`ISkillTool` — list skills, get skill details)                                                                                                                       |
| `spring.ai.loom.agent.git.enabled`      | boolean  | `false` | Git tool (`IGitTool` — 28 git operations). **Opt-in** — end-to-end deployment goes through `ICompileAndDeployTool`.                                                              |
| `spring.ai.loom.agent.maven.enabled`    | boolean  | `false` | Maven tool (also requires `maven-invoker` on the classpath). **Opt-in** — compile/package for deployment scenarios goes through `ICompileAndDeployTool`.                        |
| `spring.ai.loom.agent.git.username`     | string   | —       | HTTP(S) Git auth username (clone/pull/push)                                                                                                                                      |
| `spring.ai.loom.agent.git.token`        | string   | —       | HTTP(S) Git auth token / password                                                                                                                                                |
| `spring.ai.loom.agent.gitUsername`      | string   | —       | **Legacy** top-level alias, equivalent to `git.username`                                                                                                                         |
| `spring.ai.loom.agent.gitToken`         | string   | —       | **Legacy** top-level alias, equivalent to `git.token`                                                                                                                            |

**Example — enable the Git tool**:

```yaml
spring:
  ai:
    loom:
      agent:
        git:
          enabled: true   # default false; set to true to enable
```

> Even when a tool group is turned off, you can still re-enable it by providing your own `@Bean IGitTool` / `@Bean IMavenTool` — `@ConditionalOnMissingBean` always takes precedence over the auto-configured bean.

---

## Appendix: Endpoint Summary

| #  | Method   | Path                                                    | Description                          |
|----|----------|---------------------------------------------------------|--------------------------------------|
| 1  | `POST`   | `/spring/ai/loom/user/isAutoLogin`                      | Check auto-login status              |
| 2  | `POST`   | `/spring/ai/loom/user/login`                            | User login (sets session cookie)     |
| 2a | `POST`   | `/spring/ai/loom/user/logout`                           | User logout (invalidates session)    |
| 3  | `GET`    | `/spring/ai/loom/conversation`                          | List conversations                   |
| 4  | `GET`    | `/spring/ai/loom/conversation/{id}`                     | Get conversation history             |
| 5  | `DELETE` | `/spring/ai/loom/conversation/{id}`                     | Delete conversation                  |
| 6  | `POST`   | `/spring/ai/loom/stream`                                | SSE streaming chat                   |
| 7  | `POST`   | `/spring/ai/loom/file/upload`                           | Upload file                          |
| 8  | `GET`    | `/spring/ai/loom/file` or `/spring/ai/loom/file/tree` | List file tree (directory tree JSON) |
| 8a | `GET`    | `/spring/ai/loom/file/by-path/view`                     | View file by path (redirects)        |
| 8b | `GET`    | `/spring/ai/loom/file/by-path/download`                 | Download file by path (redirects)    |
| 8c | `GET`    | `/spring/ai/loom/file/{id}/download`                    | Download file by ID (binary stream)  |
| 9  | `GET`    | `/spring/ai/loom/knowledge/checkKnowledgeUpload`        | Check knowledge base status          |
| 10 | `GET`    | `/spring/ai/loom/knowledge`                             | List knowledge bases                 |
| 11 | `PUT`    | `/spring/ai/loom/knowledge`                             | Create knowledge base                |
| 12 | `DELETE` | `/spring/ai/loom/knowledge/{id}`                        | Delete knowledge base (cascade)      |
| 13 | `POST`   | `/spring/ai/loom/knowledge/{id}/upload`                 | Upload file to knowledge base        |
| 14 | `GET`    | `/spring/ai/loom/knowledge/{id}/file`                   | List files in knowledge base         |
| 15 | `DELETE` | `/spring/ai/loom/knowledge/{id}/file/{fileId}`         | Delete file from knowledge base      |
| 16 | `GET`    | `/spring/ai/chat/loom/mcp`                              | Get MCP servers and tools            |
| 17 | `GET`    | `/spring/ai/loom/skill`                                 | List all skills                      |
| 18 | `PUT`    | `/spring/ai/loom/skill`                                 | Create or update a skill             |
| 19 | `GET`    | `/spring/ai/loom/skill/{name}`                          | Get skill details                    |
| 20 | `DELETE` | `/spring/ai/loom/skill/{name}`                          | Delete a skill                       |
