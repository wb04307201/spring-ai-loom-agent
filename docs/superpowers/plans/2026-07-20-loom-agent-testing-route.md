# LoomAgent 全量回归测试路线 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过 chrome-devtools MCP 对 LoomAgent 主对话 UI 和 Admin Console 做全量回归测试,边测边修,并产出 UI 优化清单和测试报告。

**Architecture:** 4 阶段串行 — 启动冒烟 → 主对话 UI 8 模块回归 → Admin Console 6 页面回归 → 收尾。每个模块用 chrome-devtools 的 `take_snapshot` / `click` / `fill` / `evaluate_script` / `take_screenshot` 走验证点;发现 bug 立即修并单 commit;UI 优化点持续记录到 `docs/ui-optimization-todo.md`,最后出测试报告。

**Tech Stack:** Spring Boot 3.x + Spring AI 1.x / H2 + Flyway / JVector / Vue (前端) / chrome-devtools MCP (浏览器驱动)

## Global Constraints

- **JDK:** 17+
- **Build tool:** Maven (multi-module)
- **服务启动命令:** `mvn spring-boot:run -pl spring-ai-loom-agent-test`
- **服务端口:** 默认 8080,前端入口 `http://localhost:8080/spring/ai/loom/`
- **默认账号:** admin / admin (admin 角色,可访问 `/admin/console.html`)
- **bug fix commit 规范:** `fix(模块): 描述 (BUG-XXX-YYY)` 沿用仓库现有
- **UI 优化 commit 规范:** `ui(模块): 描述`
- **临时数据命名:** 所有测试期间创建的数据用 `tmp-` 前缀,阶段 4 一次性清
- **截图归档:** `.claude/test-screenshots/2026-07-20/{模块}_{验证点}.png`
- **UI 优化清单:** `docs/ui-optimization-todo.md` (4 维度分类: 体验/视觉/性能/可访问性)
- **测试报告:** `docs/test-report-2026-07-20.md`
- **总工作时间预估:** 4-5 小时

---

## Task 1: 启动服务 + 冒烟测试

**Files:**
- Read: `spring-ai-loom-agent-test/src/main/resources/application.yml`
- Create: `.claude/test-screenshots/2026-07-20/01-smoke.png`

**Interfaces:**
- 启动后服务地址: `http://localhost:8080/spring/ai/loom/`

- [ ] **Step 1: 编译整个项目**

```bash
mvn clean install -Dgpg.skip=true -DskipTests
```

Expected: BUILD SUCCESS,4 个 module 都 install 成功。

- [ ] **Step 2: 后台启动测试应用**

```bash
mvn spring-boot:run -pl spring-ai-loom-agent-test
```

Expected: 日志最后出现 `Started Application in X.XXX seconds` 字样,服务监听 8080。**用 `run_in_background: true` 启动,不要阻塞。**

- [ ] **Step 3: 打开 login 页**

```python
mcp__chrome-devtools__new_page(url="http://localhost:8080/spring/ai/loom/login.html")
```

Expected: 看到 login 表单,username/password 两个输入框 + 登录按钮。

- [ ] **Step 4: 用 admin/admin 登录**

通过 `take_snapshot` 拿到登录按钮 uid,`fill` 填账号密码,`click` 登录。

Expected: 跳转到主聊天页,侧边栏可见,工具栏 6 个按钮可见。

- [ ] **Step 5: 发送 hello 测试 SSE**

通过 `take_snapshot` 拿到输入框 uid,`type_text` 输入 "hello",按回车。

Expected: 看到流式回复出现(SSE 事件),markdown 渲染正确。

- [ ] **Step 6: 截图冒烟完成状态**

```python
mcp__chrome-devtools__take_screenshot(filename=".claude/test-screenshots/2026-07-20/01-smoke.png", type="png", scale="css")
```

- [ ] **Step 7: 标记冒烟通过**

如果步骤 1-6 全部通过,在 todo 里把 Task 1 标 completed。

---

## Task 2: 侧边栏 + 对话管理回归

**Files:**
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js` (搜 sidebar)
- Modify (if bug): 上述文件 + commit
- Create: `.claude/test-screenshots/2026-07-20/02-sidebar-*.png`

**Interfaces:**
- 侧边栏选择器: `#sidebar` / `.sidebar-list` / `.sidebar-empty` / `.sidebar-header`

- [ ] **Step 1: 验证空状态**

如果当前没有对话,看侧边栏是否显示"暂无对话"。如果已有对话,先全部删除到空。

- [ ] **Step 2: 新建对话**

点工具栏 `+` 按钮(检查是否被 `6503575` commit 隐藏逻辑正确: 仅当侧边栏为空时才显示)。

Expected: 新对话出现在侧边栏头部,自动切换为当前对话。

- [ ] **Step 3: 再新建 2 个对话,测试切换**

逐个点击,验证消息流随之切换,当前对话高亮。

- [ ] **Step 4: 重命名对话**

悬停对话项 → 点重命名图标 → inline edit → 输入新名字 → 回车。

Expected: 名字保存,刷新页面后仍在。

- [ ] **Step 5: 删除一个对话**

悬停 → 点删除 → 二次确认框 → 确认。

Expected: 对话从列表移除,如果删的是当前对话,自动切到下一个或空状态。

- [ ] **Step 6: 删除全部对话**

逐个删除,看最终空状态"暂无对话"。

- [ ] **Step 7: 截图每个验证点**

侧边栏新建/切换/重命名/删除 各一张截图。

- [ ] **Step 8: 记录发现**

- bug → 修 + 单 commit
- UI 优化 → append 到 `docs/ui-optimization-todo.md` (格式: `- [ ] (体验) 侧边栏: ...`)

---

## Task 3: 消息流 + SSE 回归

**Files:**
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js` (搜 SSE / fetch / EventSource)
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/style.css`
- Modify (if bug): 上述文件
- Create: `.claude/test-screenshots/2026-07-20/03-message-*.png`

**Interfaces:**
- SSE endpoint: `/spring/ai/loom/sse/chat`
- 消息容器选择器: `#messages` 或类似

- [ ] **Step 1: 基础聊天**

发 "你好",看 SSE 流式输出(逐字出现,不是整段出现)。

- [ ] **Step 2: Markdown 渲染**

发一段含代码块的消息:
````
写一个 Python hello world,用代码块
````
看代码块是否高亮、复制按钮是否可用。

- [ ] **Step 3: 长消息**

发 "给我讲一个 500 字的故事",看消息气泡是否过长被截断、是否需要展开。

- [ ] **Step 4: 滚动到底部**

发多条消息后,看是否有"滚动到底部"按钮、长消息下是否自动跟随。

- [ ] **Step 5: 附件上传**

发消息时上传 .txt 文件(用 chrome-devtools upload_file 或 file input),看附件气泡样式。

- [ ] **Step 6: 工具调用 trace**

触发 MCP 工具(简单方式: "用 time 工具告诉我现在几点"),看是否有 tool_call 中间步骤展示。

- [ ] **Step 7: 错误处理**

- 发送超长消息(> 100KB)看是否友好提示
- 主动停掉服务 5s 再发消息看前端 timeout 表现 → 重启服务

- [ ] **Step 8: 截图 + 记录**

每验证点截图。bug 修,UI 优化记 todo。

---

## Task 4: 知识库 (ks-modal) 回归

**Files:**
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html` (搜 ks-modal)
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js` (搜 ksButton / knowledge)
- Modify (if bug): 上述文件
- Create: `.claude/test-screenshots/2026-07-20/04-knowledge-*.png`

**Interfaces:**
- 知识库模态框 id: `ks-modal-overlay`
- 数据接口: `IKnowledge` 在后端

- [ ] **Step 1: 打开知识库模态框**

点工具栏知识库按钮,看模态框打开。

- [ ] **Step 2: 新建知识库**

输入 `tmp-test-kb` → 创建。

Expected: 出现在侧边栏知识库列表。

- [ ] **Step 3: 上传文件**

上传 1 个 .txt 文件 + 1 个 .md 文件。

Expected: 文件出现在知识库文件列表,进度条走完。

- [ ] **Step 4: 等待向量化**

观察是否有"向量化中"提示,完成后能否在主对话里引用。

- [ ] **Step 5: 聊天引用知识库**

回到主对话,发: "从知识库 tmp-test-kb 总结上面的内容"。

Expected: LLM 引用了知识库内容,响应中能看到 RAG 痕迹。

- [ ] **Step 6: 删除知识库**

点删除 → 二次确认 → 知识库消失。

- [ ] **Step 7: 截图 + 记录**

- [ ] **Step 8: 清理临时**

`tmp-test-kb` 留到阶段 4 统一清理。

---

## Task 5: MCP 工具 (mcp-modal) 回归

**Files:**
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html` (搜 mcp-modal)
- Read: `spring-ai-loom-agent-test/src/main/resources/mcp-servers.json`
- Modify (if bug): 上述文件
- Create: `.claude/test-screenshots/2026-07-20/05-mcp-*.png`

**Interfaces:**
- MCP 模态框 id: `mcp-modal-overlay`

- [ ] **Step 1: 打开 MCP 模态框**

点工具栏 MCP 按钮,看模态框打开。

- [ ] **Step 2: 看默认工具列表**

检查默认 14 个 mcp_tool 是否都在,信息完整(名称/描述/默认选中状态)。

- [ ] **Step 3: 启用/禁用切换**

toggle 一个工具的状态,刷新模态框看是否保留。

- [ ] **Step 4: 真实工具调用**

回到主对话,发: "用 time 工具告诉我现在几点"。

Expected: SSE 流中能看到 tool_call 步骤,最终返回时间。

- [ ] **Step 5: 多工具联动**

如果有多个可用工具,发一条可能触发多个 tool_call 的请求(根据可用工具灵活调整)。

- [ ] **Step 6: 截图 + 记录**

---

## Task 6: 技能 (skills-modal) 回归

**Files:**
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html` (搜 skills-modal)
- Read: `spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql` (查 system skill seed)
- Modify (if bug): 上述文件
- Create: `.claude/test-screenshots/2026-07-20/06-skills-*.png`

**Interfaces:**
- 技能模态框 id: `skills-modal-overlay`
- 3 tab: mine / market / submit

- [ ] **Step 1: 打开技能模态框**

点工具栏技能按钮。

- [ ] **Step 2: mine tab**

看我的技能列表(默认 admin 应该能看到 6 个 system skill + 自己的)。

- [ ] **Step 3: market tab**

浏览市场,看技能卡片信息是否完整(名称/描述/作者/标签)。

- [ ] **Step 4: 申请一个市场技能**

点"申请"按钮,看是否进入我的技能列表(pending 状态)。

- [ ] **Step 5: submit tab**

填写名称/描述/内容,提交一个新技能 `tmp-test-skill`。

Expected: 后台进入待审批状态。

- [ ] **Step 6: 技能调用测试**

在主对话里发能触发技能的消息(根据实际 system skill 调整,例如让它查时间/查技能)。

- [ ] **Step 7: 截图 + 记录**

---

## Task 7: 文件管理 (file-modal) 回归 — RBAC 重点

最近修过 `BUG-RBAC-FILE-WOPI`,重点回归 + 多账号对比。

**Files:**
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html` (搜 file-modal)
- Read: `spring-ai-loom-agent/src/main/java/.../file/` 相关
- Modify (if bug): 上述文件
- Create: `.claude/test-screenshots/2026-07-20/07-file-*.png`

**Interfaces:**
- 文件模态框 id: `file-modal-overlay`
- 文件存储根: `${user.home}/.loom/file/{username}/`

- [ ] **Step 1: 打开文件模态框**

点工具栏文件管理按钮。

- [ ] **Step 2: 目录树渲染**

看 `~/.loom/file/{admin}/` 的目录树是否正确展开。

- [ ] **Step 3: 上传多文件**

上传 3 个不同文件(含重名,如 `note.txt` 和 `note.txt` 测试序号追加)。

Expected: 重名文件被命名为 `note(1).txt`。

- [ ] **Step 4: 预览**

点一个文件的预览按钮,看是否弹窗/iframe 正确显示。

- [ ] **Step 5: 下载**

点下载,看是否触发 blob 下载流。

- [ ] **Step 6: 删除**

点删除 → 二次确认 → 文件从列表移除,实际磁盘文件也被删除。

- [ ] **Step 7: 跨用户隔离测试 (RBAC 重点)**

- 登出 admin
- 创建新用户 `tmp-user-a`(可走 admin 创建或 login 页注册,如支持)
- 登录 `tmp-user-a`
- 打开文件模态框 → **应该看不到 admin 的文件**
- 上传一个文件 → 只在自己的目录里

- [ ] **Step 8: 截图 + 记录**

特别留意:
- BUG-RBAC-FILE-WOPI 修复点(LoomAgentAuth + pathPatterns scope)是否真生效
- 预览/下载的 fileId 桥接是否正常

---

## Task 8: 子任务 (subtask-modal) 回归 — operations console 新设计

最近修过 `BUG-RBAC-SUBTASK-KILL` + 整 modal 重设计。

**Files:**
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html` (搜 subtask-modal)
- Read: `spring-ai-loom-agent/src/main/java/.../subtask/` 相关
- Modify (if bug): 上述文件
- Create: `.claude/test-screenshots/2026-07-20/08-subtask-*.png`

**Interfaces:**
- 子任务模态框 id: `subtask-modal-overlay`
- 子任务模态框内容 class: `operations-modal`
- SubTaskRegistry 已知按 username scope

- [ ] **Step 1: 打开子任务模态框**

点工具栏子任务按钮。

Expected: 新 operations console 风格(`f100a6c` 设计),布局跟旧版明显不同。

- [ ] **Step 2: 创建子任务**

输入 prompt `tmp-test: 帮我总结今天天气`(任意 prompt)→ 创建。

Expected: 进入 pending → running → done 状态切换。

- [ ] **Step 3: 列表实时刷新**

观察状态变化是否有进度条/动画。

- [ ] **Step 4: 取消单个**

创建一个会运行较长的子任务(让 LLM 写长文),点取消。

Expected: 状态变 cancelled。

- [ ] **Step 5: 取消全部**

创建多个子任务,点 cancel-all 按钮。

Expected: 所有进行中子任务变 cancelled。

- [ ] **Step 6: 历史面板**

完成后看历史记录是否完整(prompt/状态/结果/时间)。

- [ ] **Step 7: 跨用户隔离 (RBAC 重点)**

切到 `tmp-user-a`,打开子任务模态框,**应该看不到 admin 的子任务**。

- [ ] **Step 8: 视觉一致性**

跟前一个 commit `f100a6c` 设计的定时任务 modal 视觉对齐情况(后面 Task 9 一起对比)。

- [ ] **Step 9: 截图 + 记录**

特别留意:
- BUG-RBAC-SUBTASK-KILL 修复点(SubTaskRegistry.kill + router scope)是否真生效
- 视觉是否符合 operations console 设计语言

---

## Task 9: 定时任务 (schedule-modal) 回归 — operations console 新设计 + V13 H2 持久化

**Files:**
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html` (搜 schedule-modal)
- Read: `spring-ai-loom-agent/src/main/java/.../schedule/` 相关
- Modify (if bug): 上述文件
- Create: `.claude/test-screenshots/2026-07-20/09-schedule-*.png`

**Interfaces:**
- 定时模态框 id: `schedule-modal-overlay`
- 定时模态框内容 class: `operations-modal`(跟子任务一致)
- 持久化表: `loom_scheduled_task` (V13 migration)
- Restore listener: `ScheduleRestoreListener` 在 ApplicationReadyEvent 时装载

- [ ] **Step 1: 打开定时任务模态框**

点工具栏定时任务按钮。

Expected: 同子任务的 operations console 风格,视觉一致。

- [ ] **Step 2: 创建定时任务**

- 名称: `tmp-test-schedule`
- 表达式/cron: 用最短间隔(`flex.schedule.limits.min-interval` 默认 10m,这里根据 yml 调整)或选用 one-shot
- 创建

Expected: 出现在任务列表,显示下次触发时间。

- [ ] **Step 3: 列表**

看任务信息(名称/cron/下次触发/状态)。

- [ ] **Step 4: 取消**

点取消按钮 → 任务从列表移除(应同时删 H2 row,`31a8c98` 修复过)。

- [ ] **Step 5: 历史面板**

手动触发或等自动触发后,看历史记录。

- [ ] **Step 6: 重启后保留测试**

- 创建几个定时任务
- 停服务 (Ctrl+C 启的后台进程)
- 重启服务
- 验证: 任务是否被 `ScheduleRestoreListener` 恢复,`createdAt` 是否保留(应保持,max-lifetime 跨重启累积)

- [ ] **Step 7: 过期清理测试**

- 创建 72h+ 前的任务 (直接 SQL 改 `created_at` 或临时改 yml limits)
- 重启服务
- 验证: 过期任务被清理

- [ ] **Step 8: 视觉一致性对比**

跟 Task 8 子任务模态框对比,看是否同一设计语言。

- [ ] **Step 9: 截图 + 记录**

---

## Task 10: Admin Console — 登录 + 用户管理

切到 admin 账号访问 `/admin/console.html`。

**Files:**
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/user.html`
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/user.js`
- Modify (if bug): 上述文件
- Create: `.claude/test-screenshots/2026-07-20/10-admin-user-*.png`

- [ ] **Step 1: 访问 admin console**

```python
mcp__chrome-devtools__navigate_page(url="http://localhost:8080/spring/ai/loom/admin/console.html")
```

Expected: 用 admin 账号登录后能访问,其他角色被拒。

- [ ] **Step 2: 打开用户管理页**

点用户管理 tab。

- [ ] **Step 3: 创建用户 `tmp-test-user`**

填昵称/密码/角色 → 保存。

Expected: 用户列表出现新用户。

- [ ] **Step 4: 编辑用户**

修改昵称 → 保存 → 看列表更新。

- [ ] **Step 5: 重置密码**

点重置密码 → 输入新密码 → 保存。

- [ ] **Step 6: 删除用户**

点删除 → 二次确认 → 用户从列表移除。

- [ ] **Step 7: 角色分配**

把用户角色从 user 改为 admin(或反之),保存。

- [ ] **Step 8: 截图 + 记录**

---

## Task 11: Admin Console — 角色管理

**Files:**
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/roles.html`
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/roles.js`
- Modify (if bug)
- Create: `.claude/test-screenshots/2026-07-20/11-admin-roles-*.png`

- [ ] **Step 1: 打开角色管理页**

- [ ] **Step 2: 创建角色 `tmp-test-role`**

- [ ] **Step 3: 给角色绑技能**

从技能列表选几个绑给该角色。

- [ ] **Step 4: 给角色授 MCP**

- [ ] **Step 5: 删除角色**

- [ ] **Step 6: 截图 + 记录**

---

## Task 12: Admin Console — MCP 管理

**Files:**
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/mcps.html`
- Modify (if bug)
- Create: `.claude/test-screenshots/2026-07-20/12-admin-mcps-*.png`

- [ ] **Step 1: 打开 MCP 管理页**

- [ ] **Step 2: 看默认 12 个 MCP server 列表**

- [ ] **Step 3: 编辑 server 信息**

- [ ] **Step 4: 看 tool 列表维护**

- [ ] **Step 5: default-selected 状态切换**

- [ ] **Step 6: 截图 + 记录**

---

## Task 13: Admin Console — 技能市场

**Files:**
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/skills-market.html`
- Modify (if bug)
- Create: `.claude/test-screenshots/2026-07-20/13-admin-market-*.png`

- [ ] **Step 1: 打开技能市场页**

- [ ] **Step 2: 看 Task 6 提交的 `tmp-test-skill` 是否在待审批列表**

- [ ] **Step 3: 审批通过**

- [ ] **Step 4: 上下架操作**

- [ ] **Step 5: 截图 + 记录**

---

## Task 14: Admin Console — 统计 + 会话管理

**Files:**
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/stats.html`
- Read: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/conversation.html`
- Modify (if bug)
- Create: `.claude/test-screenshots/2026-07-20/14-admin-stats-*.png`, `15-admin-conv-*.png`

- [ ] **Step 1: 打开统计页**

- [ ] **Step 2: 图表加载是否正常**

- [ ] **Step 3: 数据准确性 (对比主对话侧)**

- [ ] **Step 4: 打开会话管理页**

- [ ] **Step 5: 看所有用户对话列表**

- [ ] **Step 6: 强制删除一个 tmp-user 的对话**

- [ ] **Step 7: 截图 + 记录**

---

## Task 15: 收尾 — 清理 + 报告 + UI 优化清单

**Files:**
- Create: `docs/ui-optimization-todo.md`
- Create: `docs/test-report-2026-07-20.md`

- [ ] **Step 1: 整理所有 fix commit**

```bash
git log --oneline -20
```

列出本次测试中所有的 fix/ui commit,记录每个修了什么。

- [ ] **Step 2: 整理 UI 优化清单**

读整个会话期间累积记下的 UI 优化点,按 4 维度分类写入 `docs/ui-optimization-todo.md`:

```markdown
# LoomAgent UI 优化待办 (2026-07-20 测试)

## 体验问题
- [ ] (来源: Task X) ...

## 视觉/一致性
- [ ] ...

## 性能/动效
- [ ] ...

## 可访问性/响应式
- [ ] ...
```

- [ ] **Step 3: 写测试报告**

写入 `docs/test-report-2026-07-20.md`:

```markdown
# LoomAgent 全量回归测试报告 (2026-07-20)

## 覆盖率
- 主对话 UI: 8/8 模块
- Admin Console: 6/6 页面

## 通过率
- 总验证点: X
- 通过: Y
- 失败: Z (见下方)

## Bug 修复
| Bug | Commit | 模块 |
|-----|--------|------|
| ... | ... | ... |

## UI 优化发现
- 体验 N 条
- 视觉 N 条
- 性能 N 条
- 可访问性 N 条

详见: docs/ui-optimization-todo.md

## 截图归档
.claude/test-screenshots/2026-07-20/
```

- [ ] **Step 4: 清理临时数据**

- 数据库: 通过 H2 console 或 REST 删除所有 `tmp-` 前缀记录
- 文件:
```bash
find ~/.loom -name "tmp-*" -exec rm -rf {} \;
find ~/.loom/file -type d -name "*tmp*" -exec rm -rf {} \;
```
- 临时账号: 删除 `tmp-user-a` / `tmp-test-user` 等

- [ ] **Step 5: 提交报告 + 优化清单**

```bash
git add docs/ui-optimization-todo.md docs/test-report-2026-07-20.md
git commit -m "docs(test): 全量回归测试报告 + UI 优化清单"
```

- [ ] **Step 6: 关闭后台服务**

TaskOutput → TaskStop 关闭 mvn spring-boot:run 进程。

---

## 自检 (写完 plan 后)

**Spec 覆盖检查:**

| Spec 章节 | 对应 Task |
|-----------|----------|
| 阶段 1 启动 + 冒烟 | Task 1 |
| 阶段 2.1 侧边栏 | Task 2 |
| 阶段 2.2 消息流 | Task 3 |
| 阶段 2.3 知识库 | Task 4 |
| 阶段 2.4 MCP | Task 5 |
| 阶段 2.5 技能 | Task 6 |
| 阶段 2.6 文件 (RBAC 重点) | Task 7 |
| 阶段 2.7 子任务 (operations console + RBAC) | Task 8 |
| 阶段 2.8 定时 (operations console + V13) | Task 9 |
| 阶段 3.1 用户管理 | Task 10 |
| 阶段 3.2 角色管理 | Task 11 |
| 阶段 3.3 MCP 管理 | Task 12 |
| 阶段 3.4 技能市场 | Task 13 |
| 阶段 3.5+3.6 统计 + 会话 | Task 14 |
| 阶段 4 收尾 | Task 15 |

**Placeholder scan:** 无 TBD/TODO。"bug 修 / 优化记" 描述具体不模糊。

**Type 一致性:** chrome-devtools 工具调用名跟 MCP schema 一致(click/fill/take_snapshot/take_screenshot/upload_file 等)。

---

## Execution Handoff

Plan 已保存到 `docs/superpowers/plans/2026-07-20-loom-agent-testing-route.md`。两种执行方式:

1. **Subagent-Driven (推荐)** — 我每个 Task 派一个 subagent 执行,Task 之间 review,快速迭代
2. **Inline Execution** — 在当前 session 用 executing-plans 批量执行,带 checkpoint

哪个?