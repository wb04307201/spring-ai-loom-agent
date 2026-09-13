# Spring AI LoomAgent —— 灵梭

<div align="right">
 <a href="README.md">English</a> | 中文
</div>

> Spring Boot AI Agent 开箱即用解决方案——让你的应用 **能对话**、**有记忆**、**会思考**、**可动手**——!

[Maven Central](https://img.shields.io/maven-central/v/io.github.wb04307201/spring-ai-loom-agent-spring-boot-starter?style=flat-square)
[![star](https://gitee.com/wb04307201/spring-ai-loom-agent/badge/star.svg?theme=dark)](https://gitee.com/wb04307201/spring-ai-loom-agent)
[![fork](https://gitee.com/wb04307201/spring-ai-loom-agent/badge/fork.svg?theme=dark)](https://gitee.com/wb04307201/spring-ai-loom-agent)
[![star](https://img.shields.io/github/stars/wb04307201/spring-ai-loom-agent)](https://github.com/wb04307201/spring-ai-loom-agent)
[![fork](https://img.shields.io/github/forks/wb04307201/spring-ai-loom-agent)](https://github.com/wb04307201/spring-ai-loom-agent) 
![License](https://img.shields.io/badge/License-Apache2.0-blue.svg) ![JDK](https://img.shields.io/badge/JDK-17+-green.svg) ![SpringBoot](https://img.shields.io/badge/Spring%20Boot-3+-green.svg) ![SpringAI](https://img.shields.io/badge/Spring%20AI-1+-green.svg)

<table>
  <tr>
    <td style="padding: 0 10px; border: none; text-align: center;">
 <img src="docs/project-overview-zh.png" alt="Spring AI LoomAgent 项目概览" style="width: 50%" />
    </td>
    <td style="padding: 0 10px; border: none; text-align: center;">
 <img src="docs/loom-agent-ui-test.png" alt="Spring AI LoomAgent UI" style="width: 50%" />
    </td>
  </tr>
</table>

---

## 功能特性

> **6 大核心**：💬 对话 · 📚 知识库 · 文件 · 🔧 MCP · 🧠 技能 · 🛡 权限
> **平台功能**：🧠 技能市场 · 知识市场 · 🎛 管理控制台
> **高级特性**：🧩 子任务 · ⏰ 定时任务 · 🖼 多模态 —— 引一个依赖，开箱即用——- **💬 流式对话** — SSE 多轮聊天，推理过程折叠展示，消息复制/下载；支持图片 + 文档**多模态混合输入**
- **📚 RAG 知识库** — 多知识库管理，Tika 解析 + 向量化，内置 H2 持久化向量库（JVector HNSW 内存索引；可替换为任意 Spring AI 向量存储）
- **🔧 MCP 工具集成** — 同步/异步双模式；可用工具按**角色授权**下发，会话内按需勾选启用
- **🧠 技能市场** — Prompt 模板存库，**3 种来源**（自建 / 市场拉取 / 角色授权）；审批流：提交 → PENDING → admin 审批通过/拒绝，REJECTED 重新提交时旧行归档；拉取拒绝覆盖同名 USER_CREATED；已共享的自建不允许删；admin 可新建（直发 APPROVED）/ 编辑 / 审批 / 下架；去掉 version 字段；技能用 `@工具名` 调用 MCP
- **🧩 子任务 & ⏰ 定时任务** — 主对话把任务委派给"子模型"同步执行；LLM 可创建定时任务，触发时以子任务运行，重启自动恢复
- **🙋 AskUser 交互工具** — AI 在聊天流中以选项卡片向你提问（单选/多选/自定义输入），作答后无缝继续；子任务与定时任务不会打扰你
- **🛡 RBAC 权限** — 两级：用户类型（管理员 / 普通）+ 业务角色；admin 看全部，普通用户按角色授权取并集
- **🎛 管理控制台** — 侧边栏 SPA：用户 / 角色 / Skill 市场 / 知识库市场 / MCP 描述 / 日志（原用量统计）六大模块，admin 路径鉴权
- **📁 文件管理** — 磁盘存储 + H2 元数据，上传 / 预览 / 下载，聊天附件自动桥接
- **🧰 内置工具** — universal(所有登录用户可见):时间 / 文件 / 技能 / 知识检索 / 子任务 / 定时 / 用户提问;RBAC 控制(admin 按角色授权):Git / Maven / 端到端部署 / HTML 渲染截图;详见 [TOOLS.zh-CN.md](docs/TOOLS.zh-CN.md)
- **⚙️ 开箱即用工程化** — Spring Boot 自动配置，全组件 `@ConditionalOnMissingBean` 可替换，Flyway 迁移，广泛支持各类聊天 / 嵌入 / 向量存储后端

## 内置工具

所有工具遵循 **接口 + 默认实现** 模式——每个组件均通过 `@ConditionalOnMissingBean` 注册，用户可提供自定义实现替换任意组件——

| 工具 | 接口 | 方法数 | 可见性 | 配置属性 |
|------|------|--------|----------|----------|
| 时间 | `ITimeTool` | 2 | ✅ universal | —（`time.enabled` 已废弃） |
| 文件 | `IFileTool` | 16 | ✅ universal | `file.*` 限额（`file.enabled` 已废弃） |
| 技能 | `ISkillTool` | 2 | ✅ universal | —（`skill.enabled` 已废弃） |
| 知识库 | `IKnowledgeTool` | 1 | ✅ universal | 由 `rag.enabled`（VectorStore）门控 |
| 子任务 | `ISubTaskTool` | 4 | ✅ universal | `subtask.max-concurrent` / `max-history` |
| 定时 | `IScheduleTool` | 4 | ✅ universal | `flex.schedule.limits.*` |
| 用户提问 | `IAskUserTool` | 1 | ✅ universal | `askuser.timeoutSeconds` |
| Git | `IGitTool` | 28 | 🔐 RBAC（`tool_git`） | `git.username` / `git.token` |
| Maven | `IMavenTool` | 6 | 🔐 RBAC（`tool_maven`） | `maven.mavenHome` 等 |
| 编译部署 | `ICompileAndDeployTool` | 1 | 🔐 RBAC（`tool_compile`） | `compile.*` |
| HTML 渲染截图 | `IHtmlRenderTool` | 1 | 🔐 RBAC（`tool_render`）+ classpath 门控 | 引入 playwright 依赖 + 控制台授权 |

> **universal** = 对所有登录用户可见（`@ToolGroup(defaultGranted=true)`）。**RBAC** = 仅当管理员给角色授权该工具组后可见（`role_tool` 表）。旧的 `*.enabled` yml 开关自 M3 起不再控制任何工具 —— 见 [TOOLS.zh-CN.md §1](docs/TOOLS.zh-CN.md)。

完整的 `@Tool` 方法签名、参数说明和配置参考见 [TOOLS.zh-CN.md](docs/TOOLS.zh-CN.md)——### 编译部署工具
![img_7.png](docs/img_7.png)

### Admin Console

![管理控制台 — 用户 / 角色 / 技能市场 / 知识库市场 / MCP / 日志](docs/img_admin-console.png)

### 独立 MCP 服务

文件、Git、Maven、编译部署各有**独立 MCP 服务模块** — core 层无 Spring 依赖，可通过 jbang 以 stdio 模式运行，供 Claude Desktop、Cursor 等任何 MCP 兼容 agent 使用：

| MCP 服务 | 说明 | README |
|----------|------|--------|
| `loom-file-mcp` | 文件系统操作 — 读写、编辑、搜索、目录浏览、删除（14 个工具） | [EN](loom-file-mcp/README.md) · [中文](loom-file-mcp/README.zh-CN.md) |
| `loom-git-mcp` | 基于 JGit 的 Git 操作 — clone、commit、push、merge、rebase 等（14 个工具） | [EN](loom-git-mcp/README.md) · [中文](loom-git-mcp/README.zh-CN.md) |
| `loom-maven-mcp` | Maven 构建操作 — 执行、编译、打包、测试、依赖树、校验（6 个工具） | [EN](loom-maven-mcp/README.md) · [中文](loom-maven-mcp/README.zh-CN.md) |
| `loom-compile-mcp` | 端到端部署流水线 — git clone → 构建 → docker build → docker run → 健康检查（1 个工具） | [EN](loom-compile-mcp/README.md) · [中文](loom-compile-mcp/README.zh-CN.md) |

## 快速添加聊天界面
### 1. 引入聊天依赖
```xml
<dependency>
 <groupId>io.github.wb04307201</groupId>
 <artifactId>spring-ai-loom-agent-spring-boot-starter</artifactId>
 <version>1.1.41</version>
</dependency>
```

### 2. 添加Spring AI模型依赖
测试应用使用 Spring AI 的 对接阿里百炼：
```xml
<dependency>
 <groupId>com.alibaba.cloud.ai</groupId>
 <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
 <version>1.1.2.3</version>
</dependency>
```
```yaml
 dashscope:
 api-key: ${DASHSCOPE_API_KEY}
 chat:
 options:
 model: qwen3.7-plus
 multi_model: true
 enable_thinking: true
```

> [使用其他模型可参考](https://docs.spring.io/spring-ai/reference/api/chatmodel.html)

> **注意**: 如需基于文档进行问答，请确保模型支持多模态输入（如 `multi_model: true`），文档内容会通过 System Prompt 注入——### 3. 启动项目
访问`http://localhost:8080/spring/ai/loom`

![img.png](docs/img.png)
![img_6.png](docs/img_6.png)
![img_5.png](docs/img_5.png)

![知识库市场 — V22 两段式：列表 → 详情](docs/img_kb-market.png)

## 文档上传与对话
点击输入框左侧 `+` 按钮，可上传图片或文档文件——上传后在输入框中输入问题发送即可——### 支持的文档格式
PDF、DOCX、XLSX、PPTX、MD、TXT、HTML、CSV、RTF 等——### 工作原理
1. **图片**: 作为 Media 类型直接传递给多模态大模型（需模型支持，如 DashScope qwen 系列）
2. **文档**: 通过 Apache Tika 提取文本内容，作为 System Prompt 注入对话上下文
3. **混合场景**: 可同时上传图片和文档，模型会综合图片视觉信息与文档文本内容进行回答

### 文件下载、预览和删除
上传和生成的文件可通过 MCP 工具 `downloadFileUrl` 获取下载链接，也可以通过 MCP 工具 `viewFileUrl` 获取预览链接——文件和目录可通过 MCP 工具 `deleteFileOrDirectory` 删除（需显式传入 `I_CONFIRM_DELETE` 确认 — token 可在 `spring.ai.loom.agent.file.deleteConfirmToken` 改，支持递归删除目录，并清理已删除文件对应的临时 `file_info` 记录）——"文件"入口可统一查看、预览、下载和删除所有非知识库文件（含工具上传的文件和 git 仓库）——## 更换其它RAG以替换默认实现
下面以qdrant向量数据库为例，添加依赖和配置：
```xml
<dependency>
 <groupId>org.springframework.ai</groupId>
 <artifactId>spring-ai-starter-vector-store-qdrant</artifactId>
</dependency>
```

添加配置：
```yaml
spring:
 ai:
 vectorstore:
 qdrant:
 host: localhost
 port: 6334
 collection-name: qwen-collection-name
```

其它rag可选配置如下：
```yaml
spring:
 ai:
 loom:
 agent:
 rag:
 enabled: true # 知识空间全局开关,默认 true。设 false 跳过 VectorStore/H2 JVector 初始化(零 embedding 调用)并隐藏 📚 知识空间按钮
 similarityThreshold: 0.50 # 相似度阈值,默认0.0
 top-k: 4 # top-k，默认4
```

> `rag.enabled=false`(或容器内无 `EmbeddingModel` bean,例如 `spring.ai.model.embedding.text=none`)会干净地关闭整条 RAG 链 —— 应用照常启动,知识库元数据 CRUD 保留,前端隐藏知识空间入口。`GET /spring/ai/loom/api/features` 返回 `{ "knowledge": false }`。

## MCP服务
以时间MCP服务为例，添加依赖：
```xml
<dependency>
 <groupId>org.springframework.ai</groupId>
 <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

添加配置：
```yaml
spring:
 ai:
 mcp:
 client:
 stdio:
 servers-configuration: classpath:mcp-servers.json
```

mcp-servers.json:
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

MCP服务按钮可弹出面板查看目前拥有的MCP服务信息：
![img_3.png](docs/img_3.png)

![技能市场 — V20 两段式：列表 → 详情](docs/img_skill-market.png)

可以通过配置为工具添加中文名和描述：
```yaml
spring:
 ai:
 loom:
 agent:
 mcps:
 - name: spring-ai-mcp-client - time
 title: 时间
 description:
 一个提供时间和时区转换功能的模型上下文协议服务——该服务使大型语言模型能够获取当前时间信息，并使用IANA时区名称进行时区转换，同时具备自动检测系统时区的功能——tools:
 - name: get_current_time
 description: 获取指定时区的当前时间
 - name: convert_time
 description: 在不同时区之间转换时间
```

## 技能市场

技能是给 LLM 用的 prompt 模板，描述固定的工作流——**数据完全在数据库里**（不再读 yml 的 `skills[]` 段），分三张表：

| 表 | 作用 |
|----------------|--------------------------------------------------------------------------------------------|
| `market_skill` | 公共 **Skill 市场** — 每条只有 `(author, name)` 唯一约束（`version` 字段已移除）；admin 可新增（直发 `APPROVED`）/ 编辑 / 审批 / 下架 |
| `user_skill` | 用户本地的 Skill 副本（`source = USER_CREATED / MARKET_PULLED / ROLE_GRANTED`）；关联 `market_skill_id` 时禁止删除；拉取时拒绝覆盖同名 `USER_CREATED` |
| `role_skill` | 角色 → market_skill 的授权关系（给某个角色下放哪些 Skill）；admin 通过 `setRoleSkills` 写入，被授权技能在用户下次技能 list/get 时懒同步进各自的 `user_skill`（`ROLE_GRANTED` 条目被锁定） |

### 6 个系统种子技能

演示应用首次启动时，`V1.1` 迁移会把 6 个系统技能直接 seed 进**默认 admin 用户**的 `user_skill`（`source=USER_CREATED`、`default_loaded=true`），让新装环境开箱即用 — 包括 **网络月度事件报告**、**HTTP 测试**、**部署项目**、**自动 E2E** 等。admin 可随时在 **Skill 市场** 管理页新建 / 编辑 / 审批 / 下架市场技能。

### 普通用户的技能生命周期

1. **创建** — 聊天 UI → 技能库 → **我的** Tab → **+ 新增**，或 `PUT /spring/ai/loom/skill`。写入 `user_skill`，`source=USER_CREATED`，完全可编辑（名称/描述/内容/默认加载）。
2. **提交到市场** — 技能库 → **共享** Tab，点击自建 Skill → 详情面板「共享到市场」。提交后 `status=PENDING`，等待 admin 审批通过/拒绝（拒绝必须填评论）；无需版本号。同 `(author, name)` 重复提交：原行为 **REJECTED** → 旧行整行归档到 `market_skill_archive`（保留拒绝评论/审核人/时间），新建一条 PENDING 行（新 id）；原行为 **PENDING / APPROVED** → 仅原地更新内容，状态不动（APPROVED 永不降级）。
3. **从市场拉取** — 技能库 → **市场** Tab，点击列表项 → 右侧详情面板「拉取到我的 Skill」。写入 `user_skill`，`source=MARKET_PULLED`；同名重新拉取即刷新内容（UPSERT，无报错）。**注意**：已有同名 `USER_CREATED` 时拒绝拉取（403）—— 需先「复制为我的技能」。
4. **通过角色授权获得** — admin 给角色授权 market_skill 后，每次技能 list/get 自动同步到你的 `user_skill`（`source=ROLE_GRANTED, locked=true`），**不能改不能删**（角色锁的是该市场条目）。

### admin 的额外能力

- **新建**（直发 `APPROVED`，`created_by_kind='ADMIN'`）、**编辑 / 审批通过 / 拒绝 / 下架 (delete)** 任意 `market_skill`——作者从聊天 UI 发布进 PENDING，admin 审核或直接新建
- 给任意角色授权任意 APPROVED 的 market_skill（写入 `role_skill`，用户在下次技能 list/get 时自动同步）
- 下架级联清理 `user_skill`（拉取者）+ `role_skill`（角色授权），无孤儿记录

### 权限矩阵

| 操作 | USER_CREATED | MARKET_PULLED | ROLE_GRANTED |
|-------------|--------------|---------------|--------------|
| 改 name | ✗（PK） | ✗ | ✗ |
| 改 description | ✅ | ✗（锁定为市场快照） | ✗ |
| 改 content | ✅ | ✗（重新拉取）| ✗ |
| 改 default_loaded | ✅ | ✅ | ✗ |
| 删除 | ✅ | ✅ | ✗ |
| 提交到市场 | ✅ | ✗ | ✗ |

### 聊天 UI 用法

点 **🧠 技能库** 按钮打开 —— 四个 Tab：

- **我的** — 你的 `user_skill`（admin 也只看自己的 `user_skill`，无 union view）。点技能看详情，按 **应用**（覆盖 textarea + **直接发给大模型**）或 **复制**（覆盖 textarea，不发送）。
- **市场** — 浏览所有 `APPROVED` market skill，点 **拉取** 拉到自己名下（已有同名 `USER_CREATED` 时拒绝）。
- **共享** — 选自建 skill，提交到 PENDING，等 admin 审批。
- **我的发布** — 查看自己提交到市场的技能状态（PENDING / APPROVED / REJECTED，展示拒绝原因）。任意状态可撤回（按钮文案随状态变化：撤回投稿 / 下架并删除 / 删除被拒记录）；作者撤回删除市场条目并清空自己 `user_skill.market_skill_id` 反向链接 —— 他人已拉取的副本保留但不再同步更新（级联清理 `user_skill` + `role_skill` 的是 **admin** 下架/删除）。REJECTED 可重新提交（旧行归档 + 新建 PENDING 行）。

`content` 里通过 `@工具名` 引用 MCP 工具，可用 MCP 由角色授权决定（不是 yml）。完整 REST API 见 [docs/API.zh-CN.md → §6 技能管理](docs/API.zh-CN.md#6-技能管理)。

## 知识库 & 知识市场

知识库存储用于 RAG 检索的文档。知识空间弹窗有四个 Tab：

- **我的** — 自己的知识库：创建、上传文档、删除。
- **市场** — 浏览已审批的市场知识库，**添加到我的知识库**（两段式：点列表项 → 右侧详情面板确认按钮）。
- **共享** — 自己尚未共享的知识库，点列表项 → 右侧详情面板点「共享到市场」（提交进 PENDING，等 admin 审批）。
- **我的发布** — 查看自己提交到市场的知识库状态（PENDING / APPROVED / REJECTED，展示拒绝原因）。点列表项 → 右侧详情面板撤回按钮（文案随状态变化）；知识库撤回会级联清理 `loom_user_knowledge`（订阅者行）+ `loom_role_knowledge`（角色授权）。REJECTED 可重新提交 —— 旧行归档（`loom_market_knowledge_archive`）+ 新建 PENDING 行。

市场流程：提交 → PENDING → admin 审批通过 → APPROVED → 其他用户可订阅。也可通过角色授权自动下发知识库给用户（类似技能）。知识库市场 REST API 见 [docs/API.zh-CN.md → §5.8 知识市场](docs/API.zh-CN.md#58-知识市场)。

## 管理控制台

管理控制台采用**侧边栏导航的 SPA 风格**——admin 登录后，所有 admin 页面共享固定的左侧 sidebar：

| 区块 | 路径 | 用途 |
|--------------|-----------------------------------|---------------------------------|
| 用户管理 | `admin/console.html` | 用户列表 + 分配角色 + 批量清理会话内容 |
| 角色管理 | `admin/roles.html` | 业务角色 + 给角色授权 MCP / Skill / 知识库 |
| 技能市场 | `admin/market-skills.html` | 审批通过 / 拒绝 / 直接新建 / 编辑 / 下架 Skill |
| 知识库市场 | `admin/knowledge-market.html` | 审批通过 / 拒绝 / 直接新建 / 编辑 / 下架知识库 |
| MCP 描述维护 | `admin/mcps.html` | 给 SDK MCP 工具维护中文描述 |
| 日志 | `admin/stats.html` | 月度 Token 用量（年 + 月筛选） |
| 返回主页 | `/` | 回到聊天首页 |

- **未登录跳 login**: 所有 admin HTML 路径都受鉴权保护——未登录访问 302 重定向到 `/spring/ai/loom/login.html`；API 调用返 401。
- **"清理聊天内容" 唯一入口**: 只保留 `控制台 → 批量清理` 按钮——原 user 行 / conversation 行的"清理内容"/"一键清理"按钮已整合删除。
- **Role gating**: 所有 admin 路径都要求 `user_info.type = 'ADMIN'`——非 admin 访问 admin URL 被重定向回聊天首页。




---

- 内置工具详细说明（时间/技能/文件/Git/Maven/编译部署）：[TOOLS.zh-CN.md](docs/TOOLS.zh-CN.md)
- 独立 MCP 服务用法（文件/Git/Maven/编译部署）：见上方 [内置工具 → 独立 MCP 服务](#独立-mcp-服务) 章节
- 其他配置和扩展点说明：[Spring AI LoomAgent 自定义能力总览](docs/CUSTOMIZATION.zh-CN.md)
- 自定义UI界面对接API参考：[Spring AI LoomAgent API 文档](docs/API.zh-CN.md)

