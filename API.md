# Spring AI LoomAgent API Documentation

> **Base URL**: `http://localhost:8089` (default port for the test environment)
> **Version**: 1.0.0
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

### 6.1 List Skills

```
GET /spring/ai/loom/skill
```

**Response**: `SkillDocument[]`

| Field            | Type             | Description                                                      |
|------------------|------------------|------------------------------------------------------------------|
| `name`           | string           | Skill name                                                       |
| `description`    | string           | Skill description                                                |
| `defaultPreload` | boolean          | Whether preloaded by default                                     |
| `tools`          | string[]         | List of associated tool names                                    |
| `content`        | string             | Skill content (supports `classpath:` prefix to load from classpath) |
| `params`         | SkillParamProperty[] | Skill parameter definitions                                  |
| `source`         | string           | Skill source (`configuration` or `database`)                     |

---

### 6.2 Create/Update Skill

```
PUT /spring/ai/loom/skill
Content-Type: application/json
```

**Request Body** (`SkillProperty`):

| Field            | Type                 | Required | Description                                              |
|------------------|----------------------|----------|----------------------------------------------------------|
| `name`           | string               | Yes      | Skill name                                               |
| `description`    | string               | Yes      | Skill description                                        |
| `defaultPreload` | boolean              | No       | Whether preloaded by default, defaults to `true`         |
| `tools`          | string[]             | No       | List of associated tool names                            |
| `content`        | string               | No       | Skill content (supports `classpath:` prefix)         |
| `params`         | SkillParamProperty[] | No       | Skill parameter definitions                              |

**SkillParamProperty**:

| Field            | Type     | Required | Description                                            |
|------------------|----------|----------|--------------------------------------------------------|
| `name`           | string   | Yes      | Parameter name                                         |
| `label`          | string   | Yes      | Parameter display label                                |
| `type`           | string   | No       | Parameter type: `TEXT` / `SELECT` / `TEXT_AREA`        |
| `required`       | boolean  | No       | Whether required                                       |
| `defaultValue`   | string   | No       | Default value                                          |
| `placeholder`    | string   | No       | Placeholder text                                       |
| `options`        | Option[] | No       | Dropdown options (used when `type=SELECT`)             |

**Option**:

| Field   | Type   | Description       |
|---------|--------|-------------------|
| `label` | string | Option label text |
| `value` | string | Option value      |

**Example**:

```json
{
  "name": "email_writer",
  "description": "Professional email writing assistant",
  "defaultPreload": true,
  "tools": [],
  "params": [
    {
      "name": "recipient",
      "label": "Recipient",
      "type": "TEXT",
      "required": true,
      "placeholder": "Enter recipient email"
    },
    {
      "name": "tone",
      "label": "Tone",
      "type": "SELECT",
      "required": false,
      "defaultValue": "formal",
      "options": [
        { "label": "Formal", "value": "formal" },
        { "label": "Friendly", "value": "friendly" },
        { "label": "Concise", "value": "concise" }
      ]
    },
    {
      "name": "content",
      "label": "Email Content",
      "type": "TEXT_AREA",
      "required": true,
      "placeholder": "Enter main content of the email"
    }
  ]
}
```

**Response**: `true` (boolean)

---

### 6.3 Get Single Skill

```
GET /spring/ai/loom/skill/{name}
```

**Path Parameters**:

| Parameter | Type   | Description |
|-----------|--------|-------------|
| `name`    | string | Skill name  |

**Response**: `SkillDocument`

---

### 6.4 Delete Skill

```
DELETE /spring/ai/loom/skill/{name}
```

**Path Parameters**:

| Parameter | Type   | Description |
|-----------|--------|-------------|
| `name`    | string | Skill name  |

**Response**: `true` (boolean)

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
    "version": "1.0.0",
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

### SkillDocument

Inherits `SkillProperty`, with an additional `source` field indicating the origin.

---

## 9. Configuration Properties

All properties are prefixed with `spring.ai.loom.agent` in `application.yml`.

### 9.1 Basic Configuration

| Property                                  | Type    | Default                 | Description                        |
|-------------------------------------------|---------|-------------------------|------------------------------------|
| `spring.ai.loom.agent.defaultSystem`      | string  | Skill discovery prompt  | Default system prompt              |
| `spring.ai.loom.agent.init`               | boolean | `true`                  | Whether to initialize the ChatClient |

### 9.2 RAG Configuration

| Property                                                  | Type    | Default | Description                                    |
|-----------------------------------------------------------|---------|---------|------------------------------------------------|
| `spring.ai.loom.agent.rag.similarityThreshold`            | double  | `0.0`   | Vector retrieval similarity threshold          |
| `spring.ai.loom.agent.rag.topK`                           | int     | `4`     | Number of top results to retrieve              |
| `spring.ai.loom.agent.rag.defaultPromptTemplate`          | string  | —       | Default RAG prompt template                    |
| `spring.ai.loom.agent.rag.defaultEmptyContextPromptTemplate` | string | —    | Default response when no context is available  |
| `spring.ai.loom.agent.rag.enabledKeyword`                 | boolean | `false` | Whether to enable keyword retrieval            |
| `spring.ai.loom.agent.rag.enabledSummary`                 | boolean | `false` | Whether to enable summary generation           |

### 9.3 MCP Configuration

`spring.ai.loom.agent.mcps` is an array. Each entry contains:

| Property                | Type    | Description                          |
|-------------------------|---------|--------------------------------------|
| `name`                  | string  | MCP server name                      |
| `title`                 | string  | Display label                        |
| `description`           | string  | Description info                     |
| `defaultSelected`       | boolean | Whether selected by default          |
| `tools[].name`          | string  | Tool name                            |
| `tools[].description`   | string  | Tool description                     |

### 9.4 Skill Configuration

`spring.ai.loom.agent.skills` is an array. Each entry contains the fields defined in [SkillProperty](#62-createupdate-skill).

### 9.5 JVector Configuration

| Property                                        | Type   | Default                | Description                  |
|-------------------------------------------------|--------|------------------------|------------------------------|
| `spring.ai.loom.agent.jvector.indexPath`          | string | `.local/jvector-index` | Vector index storage path    |
| `spring.ai.loom.agent.jvector.m`                  | int    | `16`                   | HNSW graph parameter M       |
| `spring.ai.loom.agent.jvector.efConstruction`    | int    | `100`                  | ef parameter at build time   |
| `spring.ai.loom.agent.jvector.efSearch`          | int    | `10`                   | ef parameter at search time  |

### 9.6 Authentication Configuration

| Property                              | Type    | Default                | Description                                          |
|---------------------------------------|---------|------------------------|------------------------------------------------------|
| `spring.ai.loom.agent.auth.enabled`   | boolean | `true`                 | Authentication master switch                         |
| `spring.ai.loom.agent.auth.pathPatterns` | string[] | `["/spring/ai/loom/**"]` | Paths requiring authentication (Ant-style patterns)  |
| `spring.ai.loom.agent.auth.excludePathPatterns` | string[] | (see below) | Paths excluded from authentication |
| `spring.ai.loom.agent.auth.cookie.name` | string  | `loom-agent-session`   | Session cookie name                                  |
| `spring.ai.loom.agent.auth.cookie.maxAge` | int    | `86400`                | Cookie max age in seconds (24 hours)                 |

### 9.7 File Storage Configuration

| Property                              | Type    | Default                | Description                                          |
|---------------------------------------|---------|------------------------|------------------------------------------------------|
| `spring.ai.loom.agent.fileBasePath`   | string  | `.local/file`          | Root directory for uploaded files                    |
| `spring.ai.loom.agent.knowledgeBasePath` | string | `.local/knowledge`    | Root directory for knowledge base files              |

> Files uploaded to the same directory with duplicate names are automatically renamed with a suffix: `file.txt` → `file(1).txt` → `file(2).txt`.

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
| 15 | `DELETE` | `/spring/ai/loom/knowledge/{id}/file/{fileId}`          | Delete file from knowledge base      |
| 16 | `GET`    | `/spring/ai/chat/loom/mcp`                              | List MCP tools                       |
| 17 | `GET`    | `/spring/ai/loom/skill`                                 | List skills                          |
| 18 | `PUT`    | `/spring/ai/loom/skill`                                 | Create/update skill                  |
| 19 | `GET`    | `/spring/ai/loom/skill/{name}`                          | Get single skill                     |
| 20 | `DELETE` | `/spring/ai/loom/skill/{name}`                          | Delete skill                         |
| —  | `GET`    | `/spring/ai/loom`                                       | Redirect to UI home page             |
