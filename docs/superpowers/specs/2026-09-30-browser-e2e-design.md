# 2026-09-30 spring-ai-loom-agent 全功能 Browser E2E 测试 — 设计 spec

| 项 | 值 |
|---|---|
| 日期 | 2026-09-30(Asia/Shanghai) |
| 分支 | `dev-sp2` |
| 作者 | Claude Opus 5(对话内)+ 用户 wb04307201(协作) |
| 状态 | **设计草案,等待用户 review** |
| 关联 | 紧随今日 `f93d7f01` 依赖升级 review(commit `70b8839e` / `8b832990` / `403d7e9e` / `3db047b4` 之后) |

---

## 1. 背景与目标

### 1.1 背景

`f93d7f01` 完成的 Spring AI 2.0.1 + Spring Boot 4.1.1 + JDK 17→25 升级涉及 8 个 Java 模块、646 行 API break 适配。code 侧 review 已确认零意外语义漂移(详见今日提交的过程日志 `.aqg/process-log/2026-09-30-f93d7f01-review-process.md`)。

但 code review 不能替代**端到端验证**:浏览器视角下的 SSE 流式聊天、RBAC 工具调度、Skill market 审批流、Sub-task 生命周期、Schedule 触发、File management、Admin console 授权等运行时行为,**从未在真实 Chrome + 真实 Anthropic LLM 端点上跑过**。

### 1.2 目标

对 `spring-ai-loom-agent` 的 **8 个用户面向子系统**(知识库除外)进行**全功能、全深度**浏览器端到端测试:
- **覆盖**:聊天、登录认证、Skill library+market、Admin console(不含 KB)、RBAC Tool、Sub-task、Schedule、File management
- **深度**:Smoke + E2E + 视觉回归(initial baseline)
- **驱动**:用户真实 Chrome(经由 Claude 的 `mcp__chrome-devtools__*` MCP)
- **LLM**:项目现有 Anthropic 兼容端点(真实 API 调用)
- **产出**:spec + 8 个 Markdown 测试剧本 + HTML 报告 + Markdown 报告 + initial baseline 截图集

### 1.3 非目标

- ❌ 跑完后进入正式 CI(本次产出作为**初始 baseline**,后续回归可重跑)
- ❌ 重写或修改现有 `*BrowserIT` Java Playwright 套件
- ❌ 性能 / 负载 / 并发测试(本次仅验证功能正确性)
- ❌ 国际化 / a11y / 跨浏览器(本次仅 Chromium-based Chrome)

---

## 2. 范围

### 2.1 包含(8 个子系统)| # | 子系统 | 入口 URL | 含 RBAC 角色 |
|---|---|---|---|
| 1 | 登录 + Cookie 认证 | `/login.html` | — |
| 2 | 聊天核心 | `/index.html` | admin + 普通 user |
| 3 | RBAC Tool | (含在聊天 UI) | admin(11 个 `@ToolGroup` 全开)+ 普通 user(7 个 universal) |
| 4 | Skill library + market | `/admin/skills` + `/skills` | admin(市场管理)+ 普通 user(浏览) |
| 5 | Sub-task | (含在聊天 UI) | admin + 普通 user |
| 6 | Schedule | (含在聊天 UI) | admin + 普通 user |
| 7 | File management | (modal 弹出) | admin + 普通 user |
| 8 | Admin console(不含 KB 配置页) | `/admin/*` | admin |

### 2.2 不包含(明确排除)

- ❌ **知识库 / RAG**:KB 上传、`/admin/knowledge`、role_knowledge 授权、KB 检索 — 严格按用户 Q6 排除
- ❌ **4 个独立 MCP server 模块**(`loom-{file,git,maven,compile}-mcp`):外部 client 调用,不在 web UI 路径
- ❌ **`spring-ai-loom-agent-spring-boot-starter`** / **autoconfigure** 的单元测试(已有 Maven 测试覆盖)
- ❌ **`CHANGELOG.md`** / **`RELEASE-*.md`** 生成(项目无此习惯,见 CLAUDE.md)

### 2.3 RBAC Tool 范围精确清单

**主库 11 个 `@ToolGroup` 工具**(自动随 chat 触发,无需额外进程):

| tool id | 类型 | 接口 | 入口 tool 方法 |
|---|---|---|---|
| `tool_askUser` | universal | `IAskUserTool` | `askUser` |
| `tool_schedule` | universal | `IScheduleTool` | `createSchedule` / `cancelSchedule` / ... |
| `tool_subtask` | universal | `ISubTaskTool` | `startSubTask` / `listSubTasks` / ... |
| `tool_file` | universal | `IFileTool` | `readTextFile` / `writeFile` / ... |
| `tool_skill` | universal | `ISkillTool` | `getSkill` / `createOrUpdateSkill` |
| `tool_knowledge` | universal | `IKnowledgeTool` | `searchKnowledge` |
| `tool_time` | universal | `ITimeTool` | `getCurrentTime` / `convertTime` |
| `tool_git` | RBAC | `IGitTool` | 28 个 git 命令 |
| `tool_maven` | RBAC | `IMavenTool` | 6 个 maven 命令 |
| `tool_compile` | RBAC | `ICompileAndDeployTool` | `compileAndDeploy` |
| `tool_render` | RBAC | `IHtmlRenderTool` | `renderHtmlFile` |

**`mcp-servers.json` 4 个 stdio MCP server**(spring-ai-loom-agent-test 启动时自动 spawn):

| mcp name | 包 |
|---|---|
| `bing-search` | `bing-cn-mcp` |
| `@tokenizin-agency/mcp-npx-fetch` | `@tokenizin/mcp-npx-fetch` |
| `sequential-thinking` | `@modelcontextprotocol/server-sequential-thinking` |
| `mcp-server-chart` | `@antv/mcp-server-chart` |

合计 **15 capability** 可通过 web UI 聊天触发。

---

## 3. 架构

### 3.1 Driver + 剧本 + Reporter 三段

```
┌────────────────────────────────────────────────────────────────┐
│  Driver(我 — Claude 主对话)                                    │
│                                                                │
│  1. mvn spring-boot:run 后台拉起                              │
│  2. 轮询 Spring 横幅 / Tomcat started on port 8080           │
│  3. 清库 + 重跑 Flyway + fixture seed                         │
│  4. 用 mcp__chrome-devtools__* 打开真实 Chrome → index.html  │
│  5. 逐个跑 8 个 Markdown 剧本                                  │
│  6. 每个剧本:take_snapshot → 断言 → take_screenshot 留证    │
│  7. 收集每场景 pass/fail + 截图路径                            │
│  8. 生成 HTML + Markdown 报告 + baseline 归档                  │
└────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────┐
│  剧本层(人工可读 + Driver 可执行)                              │
│                                                                │
│  test/e2e/00-fixtures.md         admin + 普通 user 初始化      │
│  test/e2e/01-login.md            登录认证                       │
│  test/e2e/02-chat.md             聊天核心                       │
│  test/e2e/03-rbac-tool.md       11 @ToolGroup + 4 MCP server  │
│  test/e2e/04-skill.md            Skill library + market         │
│  test/e2e/05-subtask.md          Sub-task 生命周期             │
│  test/e2e/06-schedule.md         Schedule 短间隔触发            │
│  test/e2e/07-file.md             File management                │
│  test/e2e/08-admin.md Admin console(不含 KB)│  test/e2e/REPORT_TEMPLATE.md 报告模板                    │
└────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────┐
│  报告层                                                         │
│                                                                │
│  target/test-reports/browser-2026-09-30.html       (HTML)    │
│  docs/notes/2026-09-30-browser-e2e-report.md      (Markdown)│  docs/notes/browser-baselines-2026-09-30/      (baseline)  │
└────────────────────────────────────────────────────────────────┘
```

### 3.2 关键约束

| 约束 | 处理 |
|---|---|
| Chrome DevTools MCP 仅主对话可用,subagent 拿不到 | 我作为 driver 串行跑 8 个剧本;**不可并行** |
| 单 Chrome 实例同时只能开 1 个 page | 每个子系统测试**复用同一 page**(不重启 Chrome)|
| 真实 Anthropic LLM 调用 | API key 由 `application.yml` 提供(项目已配);**预计 40-60 次 LLM 调用** |
| LLM 流式响应(SSE) | 用 `list_network_requests` 监听 `/spring/ai/loom/api/chat` 流,直到 `[DONE]` |
| Chrome DevTools MCP 工具集 | `take_snapshot` / `click` / `type_text` / `take_screenshot` / `list_console_messages` / `list_network_requests` / `wait_for` / `evaluate_script` |

---

## 4. 测试矩阵(8 子系统 × 3 维度)

### 4.1 子系统 #1 — 登录 + Cookie 认证

| 维度 | 用例 | 期望 |
|---|---|---|
| Smoke | 访问 `/login.html` | 页面渲染,form 元素(username/password/submit)存在 |
| E2E | admin 登录(wb04307201) | 跳转 `index.html`,chat input 可用 |
| E2E | 普通 user 登录(test-user-1) | 跳转 `index.html`,但 admin 入口隐藏(检查 `🔧 工具` 按钮含 RBAC 工具) |
| E2E | 错误密码登录 | 停留在 `login.html`,无 cookie 设置 |
| E2E | Cookie 持久化 | 登录后关浏览器重开,直接进 `index.html`(不清 cookie) |
| 视觉 | login.html baseline | 截图 `docs/notes/browser-baselines-2026-09-30/login/login.png` |

### 4.2 子系统 #2 — 聊天核心

| 维度 | 用例 | 期望 |
|---|---|---|
| Smoke | `/index.html` 可达 | chat input + send button 存在 |
| E2E | 发送「你好」 | SSE 流式响应非空;流完整结束(`[DONE]` marker);响应包含中文 |
| E2E | 发送「现在几点了」 | 触发 `tool_time.getCurrentTime` 调用;tool call 日志可见 |
| E2E | 文件附件上传 + 引用 | 点击 `+` → 上传文件 → chat input 出现附件 chip → 发送引用 → LLM 引用文件名 |
| E2E | 画板导出 + 附件 | 点击 ✎ → 画一条线 → 确定 → PNG 附件 chip 出现 → 发送引用 |
| E2E | Markdown 渲染 | 发送「用 # 标题 ## 子标题 列个示例」 | 响应包含 `<h1>/<h2>/<ul>` 渲染 |
| E2E | thinking 折叠面板 | 启用 thinking 模型 → 响应包含思考块 → 可折叠/展开 |
| 视觉 | index-baseline + chat-response-baseline | 截图集 |

### 4.3 子系统 #3 — RBAC Tool

| 维度 | 用例 | 期望 |
|---|---|---|
| Smoke | 工具按钮可点 | `🔧 工具` 弹窗列出 capability |
| E2E (admin) | 触发 `tool_file.readTextFile` | 发送「读 ~/.loom/datasource/.../db.mv.db 大小」 → LLM 调用 readTextFile,tool 日志可见 |
| E2E (admin) | 触发 `tool_skill.createOrUpdateSkill` | 发送「创建一个 skill 叫 test-skill 内容是 hello」 → 创建成功 + 列表可见 |
| E2E (admin) | 触发 4 个 npx MCP | 发送「用 sequential-thinking 思考 X」 → 触发 `sequential-thinking` MCP |
| E2E (普通 user) | 触发 universal tool (askUser) | 发送「问我一个问题」 → askUser 卡片出现 → 点击选项 → 答案回流 |
| E2E (普通 user) | RBAC 工具不可见 | 工具按钮弹窗**不含** `tool_git / tool_maven / tool_compile / tool_render` |
| 视觉 | tool-picker-baseline | 截图(普通 user 视角)|

### 4.4 子系统 #4 — Skill library + market

| 维度 | 用例 | 期望 |
|---|---|---|
| Smoke | 访问 `/admin/skills` (admin) | 页面可达,列表显示 2 个官方 skill |
| E2E (admin) | 创建新 skill | 名称 test-skill,内容 hello → 列表新增 |
| E2E (admin) | 审批 PENDING skill | 创建后 status=PENDING → admin 批准 → status=APPROVED |
| E2E (普通 user) | 加载市场 skill | 加载 base 角色 → user_skill 出现市场 skill |
| E2E | 在聊天调用 skill | 发送「用 STAR-IJ skill 写一段」 → LLM 加载 skill 内容并应用 |
| 视觉 | skill-library-baseline | 截图 |

### 4.5 子系统 #5 — Sub-task(生命周期轮转)

| 维度 | 用例 | 期望 |
|---|---|---|
| Smoke | sub-task chip 可显示 | (在聊天) |
| E2E | 触发 start_sub_task | 发送「请用子任务方式帮我写一个 Python 排序函数并测试」 → 主对话立刻收到 PENDING chip |
| E2E | 生命周期轮转 | 看到 chip 状态 PENDING → RUNNING → COMPLETED(默认超时 600s,实际 ~30-120s)|
| E2E | chip 详情展开 | 点击 chip → inline detail 面板(完整 prompt / ID / 状态 / 错误) |
| E2E | list_sub_tasks | 主对话发送「列出我的子任务」 → 看到刚创建的子任务 |
| E2E | cancel_sub_task | 触发一个长任务 → cancel → 状态变为 CANCELLED |
| 视觉 | sub-task-chip-baseline | 截图(COMPLETED 状态) |

### 4.6 子系统 #6 — Schedule(短间隔触发)

| 维度 | 用例 | 期望 |
|---|---|---|
| Smoke | (在聊天) | — |
| E2E | create_schedule 10s 间隔 | 发送「每 10 秒提醒我喝水」 → schedule 创建成功 |
| E2E | 等实际触发 | 等 ~15s → 触发记录可见(子任务方式跑跑) |
| E2E | cancel_schedule | 发送「取消喝水提醒」 → schedule 状态变为 CANCELLED |
| E2E | history | 发送「schedule 历史」 → 看到刚才触发记录 |
| 视觉 | (在聊天 UI 截图) | 截图 |

### 4.7 子系统 #7 — File management

| 维度 | 用例 | 期望 |
|---|---|---|
| Smoke | 文件管理模态框可达 | 点击文件按钮 → 模态框弹出 |
| E2E | 上传文件 | 选择本地文件 → 上传成功 → 列表出现 |
| E2E | 列出目录 | 树形目录展开,可见刚上传文件 |
| E2E | 预览 | 点击文件 → 预览模态框 / iframe 显示内容 |
| E2E | 下载 | 点击下载 → 文件下载到本地 |
| 视觉 | file-mgmt-baseline | 截图 |

### 4.8 子系统 #8 — Admin console(不含 KB 配置页)

| 维度 | 用例 | 期望 |
|---|---|---|
| Smoke | `/admin` 可达 (admin) | 用户列表 + 角色管理 + Skill 市场 + MCP 描述 + 仪表盘导航可见 |
| E2E | 用户列表 | 列出 admin + 普通 user + base 角色对应 user_role |
| E2E | 角色详情 | base 角色 → 已授权 tool / skill / MCP 列表 |
| E2E | 工具授权变更 | base 角色添加 `tool_git` → 普通 user 重新登录 → 工具按钮出现 git |
| E2E | Skill 市场审批 | 提交新 skill → PENDING → admin 批准 |
| E2E | 仪表盘 | token 用量柱图渲染 + 月度统计可见 |
| 视觉 | admin-console-baseline | 截图 |

---

## 5. 文件结构与 fixture 设计

### 5.1 新建文件

```
spring-ai-loom-agent/
├──test/e2e/                                   (NEW,untracked,后续可能纳入 git)
│ ├── 00-fixtures.md                          admin + 普通 user seed
│ ├── 01-login.md                             登录 + Cookie 认证
│ ├── 02-chat.md                              聊天核心
│ ├── 03-rbac-tool.md                        RBAC Tool + MCP server
│ ├── 04-skill.md                             Skill library + market
│ ├── 05-subtask.md                           Sub-task 生命周期
│ ├── 06-schedule.md                          Schedule 短间隔触发
│ ├── 07-file.md                              File management
│ ├── 08-admin.md Admin console
│ └── REPORT_TEMPLATE.md                      报告模板
├──docs/notes/
│ ├── browser-baselines-2026-09-30/          (NEW,baseline 截图)
│ │ ├── login/login.png
│ │ ├── chat/index-baseline.png
│ │ ├── chat/chat-response-baseline.png
│ │ ├── rbac-tool/tool-picker-admin.png
│ │ ├── rbac-tool/tool-picker-user.png
│ │ ├── skill/skill-library-baseline.png
│ │ ├── subtask/sub-task-chip-completed.png
│ │ ├── schedule/schedule-history.png
│ │ ├── file/file-mgmt-baseline.png
│ │ └── admin/admin-console-baseline.png
│ └── 2026-09-30-browser-e2e-report.md       (NEW,最终报告)
└──target/test-reports/
   └── browser-2026-09-30.html               (NEW,HTML 报告)
```

### 5.2 剧本格式(Markdown)

每个 `.md` 剧本用统一结构:

```markdown
# <子系统名> E2E 剧本

**目标**: <一句话描述>
**前置**: <依赖其他子系统结果 / fixture 状态>
**步骤**:
1. <可执行动作>+ 期望值
2. <可执行动作>+ 期望值
...
**断言**:
- <可观测断言>+ 通过条件
**失败处理**: <失败如何处理,例如重试 / 跳过 / 标 fail>
**截图**: <每个截图路径 + 时机>
**清理**: <是否需要在最后重置状态>
```

### 5.3 fixture 设计(`00-fixtures.md`)

**admin 账号**:
- 用户名 `wb04307201`(项目 V1.0 默认 seed)
- 密码(yml 已配,可能是 `123456` 或占位 — 测试时通过 admin UI 走流程验证)
- base 角色 + admin 类型

**普通 user 账号**:
- 用户名 `test-user-1`
- 密码 `TestPass123!`
- 仅 base 角色(无 admin),仅可见 universal tool
- 创建方式:`00-fixtures.md` 通过 `/admin/users` API + SQL seed

**数据库**:
- 清库 `rm -rf ~/.loom/datasource target/test-ds`(CLAUDE.md 「浏览器 IT 隔离约定」)
- Flyway 重跑,seed 默认 admin + 2 官方 skill

---

## 6. 执行流程

### 6.1 启动

1. 我 `Bash`: `mvn spring-boot:run -pl spring-ai-loom-agent-test -Dgpg.skip=true` 后台拉起
2. 轮询 `/spring/ai/loom/api/features` 直到 200 OK
3. 用 Chrome DevTools MCP `new_page` 打开 `http://localhost:8080/login.html`

### 6.2 Fixture

4. 按 `00-fixtures.md` 创建 test-user-1
5. 验证 test-user-1 可登录

### 6.3 8 个剧本串行执行

6. `01-login.md` → 验证登录功能,获取 cookie
7. `02-chat.md` → 验证聊天核心
8. `03-rbac-tool.md` → 验证 15 个 capability(admin 测全部,普通 user 测 universal)
9. `04-skill.md` → 验证 Skill 全链路
10. `05-subtask.md` → 验证子任务生命周期
11. `06-schedule.md` → 验证 schedule 短间隔触发
12. `07-file.md` → 验证文件管理
13. `08-admin.md` → 验证 admin console

### 6.4 报告生成

14. 收集每场景 pass/fail + 截图路径
15. 渲染 HTML(模板填充)+ Markdown(数据汇总)
16. 归档 baseline 截图

---

## 7. 错误处理

### 7.1 重试策略

- 整剧本3 次重试
- 任何子步骤失败 → 整剧本重跑(非单步重试,避免部分状态)
- 3 次仍 fail → 标 fail 继续下一个

### 7.2 失败分类

| 类型 | 处理 |
|---|---|
| LLM 超时 / 流中断 | 重试,缩短 prompt 变体 |
| Chrome 元素找不到 | `take_snapshot` 重拿 DOM,retry 找元素;3 次仍 fail → abort |
| npx MCP server 启动慢(>30s) | 跳过该 MCP 工具,继续 |
| 数据库迁移失败 | abort,要求用户介入 |
| Maven 编译错误 | abort,要求用户介入 |
| `mvn spring-boot:run` 启不起来 | abort,要求用户介入 |

### 7.3 异常隔离

- 单子系统失败不影响后续子系统继续
- 每个子系统独立 baseline 截图(即使失败也留 `subsystem-failure.png`)
- 整体失败分类为 **CRITICAL / MAJOR / MINOR**:
  - CRITICAL:登录/聊天核心不可用 → 立刻 abort
  - MAJOR:单个工具调用失败 → 标 fail 继续
  - MINOR:视觉差异 / 慢响应 → 标 warning

---

## 8. 报告输出

### 8.1 HTML 报告 `target/test-reports/browser-2026-09-30.html`

- 每子系统 Pass/Fail 表格
- 失败原因 + 截图嵌入
- 总时间统计 + LLM 调用次数
- baseline 截图链接
- 单文件,自包含 CSS(不依赖外部 CDN,断网可看)

### 8.2 Markdown 报告 `docs/notes/2026-09-30-browser-e2e-report.md`

```markdown
# 2026-09-30 spring-ai-loom-agent 全功能 Browser E2E 测试报告

## TL;DR
- 测试时长: <HH:MM:SS>
- LLM 调用: <N> 次
- 子系统: 8 个,Pass/Fail 表格
- Baseline: <N> 张截图

## 详细结果
### 子系统 #1 — 登录 + Cookie 认证
- Pass: <用例列表>
- Fail: <用例列表> + 原因
- 截图: <路径>

### ... (8 个子系统)

## 失败汇总
- CRITICAL: <列表>
- MAJOR: <列表>
- MINOR: <列表>

## 已知问题
- <运行中发现的非测试 bug,例如 UI 不一致>

## 下一步建议
- <修复优先级>
```

### 8.3 Baseline 截图集

`docs/notes/browser-baselines-2026-09-30/{subsystem}/{scenario}.png`
- 共 ~10-15 张
- 命名:`{subsystem}-{scenario}.png`
- 不压缩原图(便于将来 regression diff)

---

## 9. 成本估算

### 9.1 时间

| 阶段 | 估计时长 |
|---|---|
| 写 spec + plans | 已完成 |
| mvn spring-boot:run 启动 | 30-60s |
| 8 个剧本串行 | 30-60 min(主要 LLM 流式等待 + Chrome 操作)|
| 报告生成 | 2-5 min |
| **总计** | **~45-90 min** |

### 9.2 Anthropic API 费用

- 模型:项目 yml 配的是 `qwen3.8-max` 走 `/apps/anthropic` 端点(从今日 commit `9369792e` 看到的)
- 实际费用取决于 model + token 数
- **保守估计**: ~$0.5-2

### 9.3 本地资源

- Chrome:~150-300 MB RAM
- Maven Spring Boot test app:~500 MB RAM
- 数据库(H2):~50 MB
- 总内存占用:~700-900 MB

---

## 10. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| Anthropic 端点偶发超时 | E2E 失败 | 重试 3 次 + prompt 变体 |
| npx MCP server 启动慢 | RBAC Tool 部分用例失败 | 跳过该工具,继续 |
| Sub-task 600s 超时 | 整个测试超时 | 仅触发短任务;若超时 abort |
| Schedule 触发延迟 | 测试时长变长 | 短间隔(10s)+ 容忍延迟 |
| 用户中途介入(关闭浏览器) | 全中断 | driver 状态保存到 markdown,可重跑 |
| 视觉 baseline 像素差异(Chrome vs Chromium) | 视觉断言误判 | baseline 用途仅作 record,不比对 |
| LLM 响应包含敏感内容被 Anthropic 拒答 | E2E 失败 | 改 prompt;重试用例 3 次后 skip |

---

## 11. 时间线

| 时间 | 阶段 |
|---|---|
| 22:24 (现在) | spec 草案 |
| 22:25-22:30 | 用户 review spec + 改 |
| 22:30-22:40 | 写 plans + 用户 review plans |
| 22:40 | 启 mvn spring-boot:run(后台)|
| 22:40-22:42 | 轮询就绪 |
| 22:42-22:50 | Fixture 初始化 |
| 22:50-23:30 | 跑 8 个剧本(并行度 1)|
| 23:30-23:35 | 报告生成 |
| 23:35 | 完成 |

(实际时长取决于 LLM 响应 + Chrome 操作速度)

---

## 12. 验收标准

本次测试**视为通过**当且仅当:
1. ✅ 8 个子系统全部跑过(无 skip)
3. ✅ **CRITICAL 类失败 = 0**(登录/聊天核心)
4. ⚠️ MAJOR 类失败 ≤ 3 个(单个工具调用失败)
5. ⚠️ MINOR 类警告 ≤ 10 个(视觉/响应时间)
6. ✅ 报告(HTML + Markdown)+ baseline 截图集 全部生成
7. ✅ 8 个 Markdown 剧本写在 test/e2e/

本次测试**视为部分通过**当:
- CRITICAL = 0
- 但 MAJOR > 3 或 MINOR > 10
- → 报告中标 "PARTIAL PASS",列出未通过项供后续修复

本次测试**视为失败**当:
- CRITICAL ≥ 1
- → 中止后续子系统 + 紧急报告 + 用户介入

---

## 附录 A — 术语表

| 术语 | 定义 |
|---|---|
| **Driver** | Claude 主对话(我),负责调度 Chrome DevTools MCP |
| **剧本** | `test/e2e/*.md` 文件,描述每个子系统的测试步骤 + 断言 |
| **Baseline** | `docs/notes/browser-baselines-2026-09-30/*.png` 初始截图,作为后续 regression 比对基线 |
| **Capability** | 主库 `@ToolGroup` 工具 + MCP server 暴露的工具,15 个 |
| **CRITICAL** | 阻塞性失败,登录/聊天核心不可用 |
| **MAJOR** | 单个工具调用失败 |
| **MINOR** | 视觉差异 / 响应慢 |

## 附录 B — 引用

- `.aqg/process-log/2026-09-30-f93d7f01-review-process.md` — 今日 code review 过程日志
- CLAUDE.md §「浏览器 IT 隔离约定」— `users-base-path = ./target/e2e-files/users` + `datasource = ./target/test-ds`
- CLAUDE.md §「Front-end」— 10 个页面 + static SPA
- `spring-ai-loom-agent-test/src/main/resources/mcp-servers.json` — 4 个 npx MCP server 配置
- `spring-ai-loom-agent-test/src/main/resources/application.yml` — Anthropic 端点 + 测试 fixture

---

## 附录 C — Spec Self-Review

按 brainstorming skill「Spec Self-Review」检查:

1. **Placeholder scan**: ✅ 无 TBD / TODO / 不完整段
2. **Internal consistency**: ✅ 范围(§2)、架构(§3)、测试矩阵(§4)三处引用一致;§4 8 个子系统对应 §2.1 列表;§5 文件结构对应 §4 测试矩阵
3. **Scope check**: ✅ 单一 plan 范围(spec + plans + 实现 + 跑 + 报告),非多 plan
4. **Ambiguity check**: ✅ 术语统一(Driver / 剧本 / Baseline / Capability 在附录 A 定义);断言明确(每用例有「期望」列)

---

**状态**: 等待用户 review → 同意后写 plans → 实施