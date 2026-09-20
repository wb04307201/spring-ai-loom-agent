# Spring AI LoomAgent

<div align="right">
 <a href="README.zh-CN.md">中文</a> | English
</div>

> Spring Boot AI Agent — an out-of-the-box solution that makes your app **converse**, **remember**, **think**, and **act**.

![Maven Central](https://img.shields.io/maven-central/v/io.github.wb04307201/spring-ai-loom-agent-spring-boot-starter?style=flat-square)
[![star](https://gitee.com/wb04307201/spring-ai-loom-agent/badge/star.svg?theme=dark)](https://gitee.com/wb04307201/spring-ai-loom-agent)
[![fork](https://gitee.com/wb04307201/spring-ai-loom-agent/badge/fork.svg?theme=dark)](https://gitee.com/wb04307201/spring-ai-loom-agent)
[![star](https://img.shields.io/github/stars/wb04307201/spring-ai-loom-agent)](https://github.com/wb04307201/spring-ai-loom-agent)
[![fork](https://img.shields.io/github/forks/wb04307201/spring-ai-loom-agent)](https://github.com/wb04307201/spring-ai-loom-agent) 
![License](https://img.shields.io/badge/License-Apache2.0-blue.svg) ![JDK](https://img.shields.io/badge/JDK-17+-green.svg) ![SpringBoot](https://img.shields.io/badge/Spring%20Boot-3+-green.svg) ![SpringAI](https://img.shields.io/badge/Spring%20AI-1+-green.svg)

<table>
  <tr>
    <td style="padding: 0 10px; border: none; text-align: center;">
 <img src="docs/project-overview-en.png" alt="Spring AI LoomAgent Overview" style="width: 50%" />
    </td>
    <td style="padding: 0 10px; border: none; text-align: center;">
 <img src="docs/loom-agent-ui-test.png" alt="Spring AI LoomAgent UI" style="width: 50%" />
    </td>
  </tr>
</table>

---

## Features

> **7 Pillars**: 💬 Chat · 📚 Knowledge · 📁 Files · 🔧 MCP · 🧠 Skill · 🎨 Canvas · 🛡 RBAC
> **Platform**: 🧠 Skill Market · Knowledge Market · 🎛 Admin Console
> **Advanced**: 🧩 Sub-tasks · ⏰ Scheduled tasks · 🖼 Multimodal — one dependency, batteries included.

- **💬 Streaming Chat** — SSE multi-turn, collapsible reasoning, message copy/download; **multimodal** image + document mixed input
- **📚 RAG Knowledge Base** — Multi-KB management, Tika parsing + vectorization, built-in H2-backed vector store (JVector HNSW in-memory index; swap in any Spring AI vector store)
- **🔧 MCP Tool Integration** — Sync/async dual mode; available tools gated by **role authorization**, enabled per chat
- **🧠 Skill Market** — DB-stored prompt templates, **3 sources** (self-built / market-pulled / role-granted); approval flow (submit → PENDING → admin approve/reject, rejected re-submits archive the old row); pull rejects overwriting same-name USER_CREATED; remove blocked when `market_skill_id` is set; admin can create (lands APPROVED immediately) / edit / approve / reject; no version field. Skills call MCP via `@tool_name`. Frontend chat input supports `/` picker for precise skill selection.
- **🧩 Sub-tasks & ⏰ Scheduled Tasks** — Delegate a slice of work to a synchronous "sub-model"; LLM-created schedules run as sub-tasks and survive restarts
- **🙋 AskUser Interactive Tool** — the AI asks you questions inline in the chat stream via option cards (single-select / multi-select / custom input), then seamlessly continues after you answer; sub-tasks and scheduled tasks never interrupt you
- **🛡 RBAC** — Two levels: user type (admin / user) + business roles; admin sees all, normal users get the union of their roles' grants
- **🎛 Admin Console** — Sidebar SPA: users / roles / skill market / knowledge market / MCP descriptions / logs (formerly usage stats); admin-gated
- **📁 File Management** — Disk storage + H2 metadata, upload / preview / download, chat-attachment bridging
- **🎨 Canvas Board** — full-screen drawing modal: 6 drawing tools + 18 web-UI stencils (CRUD prototype skeleton pieces) with a select tool for move / resize / label editing; exports a white-background PNG as a chat attachment, forming a prototype workflow with multimodal models or the HTML render tool
- **🧰 Built-in Tools** — universal (visible to every logged-in user): time / file / skill / knowledge / sub-task / schedule / askUser; RBAC-gated (admin grants per role): git / maven / end-to-end deploy / html-render; see [TOOLS.md](docs/TOOLS.md)
- **⚙️ Batteries-included Engineering** — Spring Boot auto-config, every bean replaceable via `@ConditionalOnMissingBean`, Flyway migrations, broad chat / embedding / vector-store support

## Built-in Tools

All tools follow the **interface + default implementation** pattern. Every component is registered with `@ConditionalOnMissingBean`, allowing consumers to replace any piece with a custom implementation.

| Tool | Interface | Methods | Visibility | Config Property |
|------|-----------|---------|---------|-----------------|
| Time | `ITimeTool` | 2 | ✅ universal | — (`time.enabled` deprecated) |
| File | `IFileTool` | 16 | ✅ universal | `file.*` limits (`file.enabled` deprecated) |
| Skill | `ISkillTool` | 2 | ✅ universal | — (`skill.enabled` deprecated) |
| Knowledge | `IKnowledgeTool` | 1 | ✅ universal | gated by `rag.enabled` (VectorStore) |
| Sub-task | `ISubTaskTool` | 4 | ✅ universal | `subtask.max-concurrent` / `max-history` |
| Schedule | `IScheduleTool` | 4 | ✅ universal | `flex.schedule.limits.*` |
| AskUser | `IAskUserTool` | 1 | ✅ universal | `askuser.timeoutSeconds` |
| Git | `IGitTool` | 28 | 🔐 RBAC (`tool_git`) | `git.username` / `git.token` |
| Maven | `IMavenTool` | 6 | 🔐 RBAC (`tool_maven`) | `maven.mavenHome` etc. |
| Compile & Deploy | `ICompileAndDeployTool` | 1 | 🔐 RBAC (`tool_compile`) | `compile.*` |
| Html Render | `IHtmlRenderTool` | 1 | 🔐 RBAC (`tool_render`) + classpath-gated | add `playwright` dep + grant in admin console |

> **universal** = visible to every logged-in user (`@ToolGroup(defaultGranted=true)`). **RBAC** = visible only after an admin grants the tool group to a role (`role_tool` table). The legacy `*.enabled` yml switches no longer gate any tool since M3 — see [TOOLS.md §1](docs/TOOLS.md).

For full `@Tool` method signatures, parameter details, and configuration reference, see [TOOLS.md](docs/TOOLS.md).

### Admin Console

![Admin console — users / roles / skill-market / knowledge-market / MCP / logs](docs/img_admin-console.png)

### Standalone MCP Servers

File, Git, Maven, and Compile each have a **standalone MCP server module** — the core layer has no Spring dependency and can be deployed via jbang to any MCP-compatible agent (Claude Desktop, Cursor, etc.):

| MCP Server | Description | README |
|------------|-------------|--------|
| `loom-file-mcp` | File system operations — read, write, edit, search, directory browsing, delete (14 tools) | [EN](loom-file-mcp/README.md) · [中文](loom-file-mcp/README.zh-CN.md) |
| `loom-git-mcp` | Git operations via JGit — clone, commit, push, merge, rebase, and more (14 tools) | [EN](loom-git-mcp/README.md) · [中文](loom-git-mcp/README.zh-CN.md) |
| `loom-maven-mcp` | Maven build operations — execute, build, package, test, dependency tree, validate (6 tools) | [EN](loom-maven-mcp/README.md) · [中文](loom-maven-mcp/README.zh-CN.md) |
| `loom-compile-mcp` | End-to-end deploy pipeline — git clone → build → docker build → docker run → health check (1 tool) | [EN](loom-compile-mcp/README.md) · [中文](loom-compile-mcp/README.zh-CN.md) |

## Quick Start: Add a Chat Interface

### 1. Add LoomAgent Dependency
```xml
<dependency>
 <groupId>io.github.wb04307201</groupId>
 <artifactId>spring-ai-loom-agent-spring-boot-starter</artifactId>
 <version>1.1.46</version>
</dependency>
```

### 2. Add a Spring AI Model Dependency
The test application uses Alibaba's Qwen (DashScope) via Spring AI Alibaba. Swap the dependency and config for any other provider:
```xml
<dependency>
 <groupId>com.alibaba.cloud.ai</groupId>
 <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
 <version>1.1.2.3</version>
</dependency>
```

```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      chat:
        options:
          model: qwen3.7-plus
          multi_model: true
          enable_thinking: true
```

> [For other models, see the Spring AI docs](https://docs.spring.io/spring-ai/reference/api/chatmodel.html).

> **Note**: For document-based Q&A, ensure the model supports multimodal input (e.g., `multi_model: true`). Document content is injected via System Prompt.

### 3. Start the Project
Visit `http://localhost:8080/spring/ai/loom`

![img.png](docs/img.png)
![img_6.png](docs/img_6.png)
![img_5.png](docs/img_5.png)

![Knowledge Market — V22 two-stage list → detail panel](docs/img_kb-market.png)

## Document Upload & Conversation
Click the `+` button next to the input field to upload images or documents. After uploading, type your question and send it. Click the **palette button** right of `+` to open the **drawing canvas**: freehand drawing (pen / line / rectangle / ellipse / text / eraser, with color swatches, stroke width, undo/redo) plus 18 built-in web UI stencils in a categorized "Components ▾" panel — CRUD skeleton pieces (data table with comma-separated column headers, form field, action bar, pagination, tabs, dialog, status tag, switch, textarea, breadcrumb) and basic controls — all movable, resizable, double-click to edit labels, for quickly sketching admin CRUD prototypes; **Confirm** exports a white-background PNG that is attached to the message like any uploaded image (pairs with multimodal models or the HTML render tool for a prototype workflow).

### Supported Document Formats
PDF, DOCX, XLSX, PPTX, MD, TXT, HTML, CSV, RTF, and more.

### How It Works
1. **Images**: Passed as Media type directly to the multimodal model (requires model support, e.g., DashScope Qwen series)
2. **Documents**: Text content extracted via Apache Tika, injected as System Prompt into the conversation context
3. **Mixed scenarios**: Images and documents can be uploaded together; the model synthesizes visual information and document text

### File Download, Preview, and Deletion
Uploaded and generated files can get download links via MCP tool `downloadFileUrl`, or preview links via MCP tool `viewFileUrl`. Files and directories can be removed via MCP tool `deleteFileOrDirectory` (requires explicit `I_CONFIRM_DELETE` confirmation — token configurable via `spring.ai.loom.agent.file.deleteConfirmToken`, supports recursive directory removal, and cleans up temporary `file_info` records).

The "File" entry provides unified browsing, previewing, downloading, and deleting for all non-knowledge-base files (including tool uploads and git repositories).

## MCP Services

Taking the time MCP service as an example, add the dependency:

```xml
<dependency>
 <groupId>org.springframework.ai</groupId>
 <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

Add configuration:

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          servers-configuration: classpath:mcp-servers.json
```

`mcp-servers.json`:

```json
{
  "mcpServers": {
    "time": {
      "command": "uvx",
      "args": [
        "mcp-server-time",
        "--local-timezone=Asia/Shanghai"
      ]
    }
  }
}
```

`Tool` button opens a panel showing available tools:

![img_3.png](docs/img_3.png)

![Skill Market — V20 two-stage list → detail panel](docs/img_skill-market.png)

## Skill Market

Skills are prompt templates for the LLM describing reusable workflows. The data is fully managed in the database (no yml), via the chat UI's **🧠 Skill Library** and the admin console's **Skill Market** page.

- **Three sources** — self-built (fully editable) / market-pulled (re-pull refreshes content; pull is rejected when a same-name self-built skill exists — use "copy as my skill" first) / role-granted (auto-synced after an admin grants it to a role; locked, cannot be edited or deleted)
- **Approval flow** — submit a self-built skill via the **Share** tab → `PENDING` → admin approves / rejects (a reject requires a comment); re-submitting after rejection archives the old row and opens a fresh review; same-name re-submits update content in place and `APPROVED` never demotes; the **My Publishes** tab shows status and supports withdraw
- **Role distribution** — admins grant `APPROVED` market skills to roles; they auto-sync into role members' skill lists; unpublishing cascades to pulls and grants — no orphans
- **Out of the box** — the library ships 2 official skills (STAR-IJ explain-one-thing clearly / bullseye formula tell-a-good-story); the demo app seeds 6 demo skills on first launch (Monthly Event Report, HTTP Test, etc.)
- **Usage** — type `/` in the chat input to open the picker for precise skill selection (applies into the input box); inside a skill's `content`, reference MCP tools by `@tool_name` — available MCPs follow role authorization

For the full REST API, see [docs/API.md → §6 Skill Management](docs/API.md#6-skill-management).

## Knowledge Base & Knowledge Market

Knowledge bases store documents for RAG retrieval. The knowledge space modal has four tabs:

- **我的** — your own knowledge bases. Create, upload documents, delete.
- **市场** — browse approved market knowledge bases and **添加到我的知识库** (subscribe).
- **共享** — your own knowledge bases not yet shared. Click **共享到市场** to submit for admin approval.
- **我的发布** — track your market submissions (PENDING / APPROVED / REJECTED, with the reject reason shown). Withdraw is available at any status (label varies: 撤回投稿 / 下架并删除 / 删除被拒记录) — withdrawing deletes the market entry and, on the knowledge side, cascades cleanup of `loom_user_knowledge` subscriber rows + `loom_role_knowledge` grants. REJECTED entries can be re-submitted — the old row is archived (`loom_market_knowledge_archive`) and a fresh PENDING row is created.

Market workflow: submit → PENDING → admin approve → APPROVED → other users can subscribe. Role-based authorization can also auto-grant knowledge bases to users (similar to skills).

For the knowledge market REST API, see [docs/API.md → §5.8 Knowledge Market](docs/API.md#58-knowledge-market).

### Replace the Default RAG Implementation
The following example uses Qdrant as the vector store. Add the dependency:
```xml
<dependency>
 <groupId>org.springframework.ai</groupId>
 <artifactId>spring-ai-starter-vector-store-qdrant</artifactId>
</dependency>
```

Add configuration:

```yaml
spring:
  ai:
    vectorstore:
      qdrant:
        host: localhost
        port: 6334
        collection-name: qwen-collection-name
```

Optional RAG configuration:

```yaml
spring:
  ai:
    loom:
      agent:
        rag:
          enabled: true # Global knowledge-space switch, default true. Set false to skip VectorStore/H2 JVector init (zero embedding calls) and hide the 📚 knowledge-space button
          similarityThreshold: 0.50 # Similarity threshold, default 0.0
          top-k: 4 # Top-k results, default 4
```

> Setting `rag.enabled=false` (or providing no `EmbeddingModel` bean, e.g. `spring.ai.model.embedding.text=none`) cleanly disables the whole RAG chain — the app still starts, knowledge-base metadata CRUD survives, and the frontend hides the knowledge-space entry. `GET /spring/ai/loom/api/features` reports `{ "knowledge": false }`.

## Admin Console

The admin console is a sidebar-navigated single-page-app shell. After admin login, all admin pages share a fixed left sidebar:

| Section | Path | Purpose |
|-----------------|-------------------------------|--------------------------------------|
| 用户管理 | `admin/console.html` | User list + role assignment + batch content cleanup |
| 角色管理 | `admin/roles.html` | RBAC roles + grant MCP / Skill / Knowledge |
| 技能市场 | `admin/market-skills.html` | Approve / reject / directly create / edit / delete Skill |
| 知识库市场 | `admin/knowledge-market.html` | Approve / reject / directly create / edit / delete Knowledge |
| MCP 描述维护 | `admin/mcps.html` | Maintain Chinese descriptions for SDK MCP tools |
| 日志 | `admin/stats.html` | Monthly Token usage (year + month filter) |
| 用户详情 | `admin/user.html?username=X` | 单用户视图:6 个月用量 + 角色 + 提问卡片日志 + 会话列表 |
| 返回主页 | `/` | Back to chat home page |

- **未登录跳 login**: All admin HTML paths are auth-protected. Unauthenticated access 302-redirects to `/spring/ai/loom/login.html`; API calls 401.
- **"清理聊天内容" 唯一入口**: Only `控制台 → 批量清理` button. The duplicate "清理内容" / "一键清理" buttons in user row / conversation row were consolidated.
- **Role gating**: All admin paths require `user_info.type = 'ADMIN'`. Non-admin attempting admin URL is redirected back to chat home.

---



---

- For built-in tool reference (time / skill / file / git / maven / compile), see: [TOOLS.md](docs/TOOLS.md)
- For standalone MCP server usage (file / git / maven / compile), see the [Built-in Tools → Standalone MCP Servers](#standalone-mcp-servers) section above
- For more configuration and extension points, see: [Spring AI LoomAgent Customization Guide](docs/CUSTOMIZATION.md)
- For custom UI integration and API reference, see: [Spring AI LoomAgent API Documentation](docs/API.md)
