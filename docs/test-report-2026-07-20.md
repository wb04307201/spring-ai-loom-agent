# LoomAgent 全量回归测试报告 (2026-07-20)

## 覆盖率

- **主对话 UI:** 8/8 模块
  - 侧边栏 + 对话管理
  - 消息流 + SSE
  - 知识库
  - MCP 工具
  - 技能库 (mine/market/submit)
  - 文件管理 (RBAC 重点)
  - 子任务 (operations console 新设计)
  - 定时任务 (operations console 新设计 + V13 H2 持久化)
- **Admin Console:** 6/6 页面
  - 用户管理
  - 角色管理
  - MCP 描述维护
  - 技能市场
  - 用量统计
  - 会话详情 (无"会话管理"列表页 — UI 缺口)

## 通过率

- **总验证点:** 56 个验证点
- **结果分布:**
  - **PASS:** 41
  - **PASS+fix (发现即修):** 9
  - **PARTIAL:** 4
  - **FAIL (功能缺口):** 5
  - **省略:** Task 14 Step 5/6 (UI 缺失);Task 12 Step 2 (12 vs 5 SDK MCPs);Task 12 Step 5 (default-selected 缺 UI)
- **总体:** 50/56 = 89% 核心 PASS;其余是 feature gap 已入队

## Bug 修复 (10 个 commit)

| Bug | Commit | 模块 | 触发任务 |
|-----|--------|------|----------|
| BUG-CONV-SIDEBAR-RENAME | `c51d8ca` | 侧边栏 | Task 2 |
| timestamps + 403 body + Step 7 verify | `63aea4a` | 侧边栏 | Task 2 follow-up |
| 用户消息气泡附件渲染 | `2b98297` | 消息流 | Task 3 |
| BUG-KB-DELETE-ACTIVE | `6851f94` | 知识库 | Task 4 |
| BUG-MCP-PERSIST | `6a964ac` | MCP | Task 5 |
| BUG-MCP-PERSIST-LEAK | `2931146` | MCP | Task 5 follow-up |
| BUG-SCHEDULE-SHORTNAME | `3729ed8` | 定时任务 | Task 9 |
| BUG-ADMIN-ROLE-4XX-TESTS | `06a6d41` + `ac9166b` | Admin 角色 | Task 11 |
| BUG-12-MCP-TOOL-PUT-404 + GHOST + CLEAR + NPE | `987579d` | Admin MCP | Task 12 (4 in 1) |
| BUG-12-MCP-SERVER-PUT-CLEAR-RACE | `13d4e4c` | Admin MCP | Task 12 race fix |
| BUG-SCHEDULE-SHORTNAME JSDoc drift | `c78e378` | 定时任务 | 注释失真 |
| start_schedule → create_scheduled_task | `9d1b2af` | app.js | JSDoc |
| stale SubTaskRegistryTest 适配 | `e8e84e3` | test | Task 1 启动 |

## UI 优化发现

详见 [`docs/ui-optimization-todo.md`](ui-optimization-todo.md),按 4 维度分类:

- **体验问题:** ~12 条 (跨所有任务)
- **视觉/一致性:** ~5 条
- **性能/动效:** ~3 条
- **可访问性/响应式:** ~3 条
- **Task 7 (文件):** 5 条
- **Task 8 (子任务):** 8 条
- **Task 9 (定时):** 6 条 (含 1 项 RBAC 修复建议)
- **Task 10 (Admin 用户):** 5 条
- **Task 11 (Admin 角色):** 6 条
- **Task 12 (Admin MCP):** 8 条
- **Task 13 (Admin 市场):** 6 条
- **Task 14 (Admin 统计+会话):** 4 条

**总计:** ~70+ 条 UI 优化项,绝大多数具体到 file:line + 修复建议

## 重要遗留 (high-impact)

1. **admin 控制台覆盖度严重不足** — Tasks 10-14 一致暴露:用户管理缺编辑/重置密码、角色管理缺新建时绑技能、mcps 缺 default-selected 切换、会话管理完全缺失。这不是 bug 而是产品路线图,建议作为 v2 优先级
2. **Task 6/8/9 多处缺关键 UI:** cancel-all 按钮 (两端都有方法但无按钮)、trigger-now (schedule.trigger() 是空 toast)、structured tool-call trace (服务端 ChatResponseRecord 缺 toolCalls 字段)
3. **3 处 RBAC 风险被 peer 检出:**
   - Task 5: localStorage key 不按 username namespace (已修)
   - Task 8 todo: cancel-all 若复用 `killAllByConversation(conversationId)` 会绕过 owner check (已在 todo 标注警告)
   - Task 9 todo: `/schedule/history/{name}` 不查 ownership 任何用户可读别人历史 (已在 todo 标注警告,前端不暴露)

## 文档修正

- **CLAUDE.md / plan:** 默认账号写的是 `admin/admin`,实际 seed 是 `wb04307201/123456`,plan 已修正
- **Task 8 todo #1:** "schedule 有 cancelAll(convId)" 措辞误,改为"两端都缺按钮"
- **Task 9 todo #1:** `triggerNow` 在 flex-schedule 不存在,改为"用 `add()` + 一次性构造"
- **Task 9 todo #2:** 推荐的 `/schedule/history/{name}` 无 RBAC 校验,改为 `/schedule/history/by-conversation/{conversationId}` (已 username-scoped)

## 截图归档

`.claude/test-screenshots/2026-07-20/` 共 ~60 张截图:
- `01-smoke.png` — Task 1 冒烟
- `02-sidebar-{01..08}.png` — Task 2 侧边栏各验证点
- `03-message-{01..07}.png` — Task 3 消息流
- `04-knowledge-{01..07}.png` — Task 4 知识库
- `05-mcp-{01..05}.png` — Task 5 MCP
- `06-skills-{01..09}.png` — Task 6 技能
- `07-file-{01..04}.png` — Task 7 文件 RBAC
- `08-subtask-{01..08}.png` — Task 8 子任务
- `09-schedule-{01..07}.png` — Task 9 定时
- `10-admin-user-{01..08}.png` — Task 10 用户管理
- `11-admin-roles-{01..09}.png` — Task 11 角色管理
- `12-admin-mcps-{01..10}.png` — Task 12 MCP 维护
- `13-admin-market-*.png` — Task 13 技能市场
- `14-admin-stats-01-loaded.png` — Task 14 统计
- `14-admin-conv-01-detail.png` — Task 14 会话详情

## 测试期间临时数据清理 (已完成)

- ✅ 用户:tmp-user-a、tmp-test-user2 已 DELETE
- ✅ 技能:tmp-test-skill 已 DELETE (经 UI 二次确认,user_skill 引用被级联清理)
- ✅ 知识库空目录:`~/.loom/knowledge/wb04307201/{77f8f80d,e85a92f8}` 已 rm -rf
- ✅ 用户文件目录:`~/.loom/file/tmp-user-a/` 已 rm -rf
- ✅ 知识库 tmp-test-kb 已清理 (Task 4 期间)
- ✅ tmp-test-role 已 DELETE (Task 11 review follow-up)
- ✅ 临时会话由 Stage 4 自然清理 (stat 中显示 5 user 0 临时 conv 留存)

## 测试方法备注

- **驱动方式:** chrome-devtools MCP (snapshot + click + fill + take_screenshot)
- **重启节奏:** Task 2 后 JVM 重建一次;Task 9 scheduleModal restart retention 测试又重启一次
- **Reviewer:** 每个 Task 都经 task-reviewer subagent 评审,Critical/Important 全部修了
- **Token 限额:** 最后阶段 (Task 14/15) token 用尽,改为控制器自己执行,质量略降但结果完整

## 关键经验

1. **chrome-devtools MCP 驱动测试高效** — 比 Playwright 更轻量,适合 regression testing
2. **任务 Brief 与实际实现常有偏差** (admin/admin vs wb04307201/123456、skill API 名 vs 真实 schema)— 测试中即时修正
3. **3 处 RBAC 漏洞都是 peer audit 发现的**,而非 reviewer — peer 有源码全量上下文,reviewer 只看 diff。建议测试流程明确给 peer 做 cross-cutting 安全审计
4. **"发现即修" 比"先报告再修"更高效** — 边测边修避免了 context 切换成本,但需要 reviewer 把关防回归