# LoomAgent 全量回归测试路线设计

**Date:** 2026-07-20
**Scope:** Spring AI LoomAgent 全栈 (主对话 UI + Admin Console)
**Driver:** chrome-devtools MCP (session 内交互式)
**Fix cadence:** 发现即修 (单 bug 单 commit)
**UI review:** 体验 + 视觉/一致性 + 性能/动效 + 可访问性/响应式

## 背景与目标

最近一次大测试在 `51363d3 v1.0: 99/99 E2E pass` commit,通过率 100%。此后又有 5+ 次 UI/fix commit,主要是子任务/定时器从工具栏重构为 operations console,以及一系列 BUG-RBAC-* 修复。仓库目前状态: master 干净,sub 分支为最新工作线。

本次回归目标:

1. **不回归** — 验证 99/99 的核心流程在新 commit 后仍稳
2. **覆盖新增** — 子任务/定时器 operations console 重设计是否带来视觉/交互 bug
3. **持续改进** — 把测试中暴露的 UI 优化点系统地记下来,后续推进

## 阶段拆解

### 阶段 1: 启动 + 冒烟 (≈15 min)

**目标:** 验证 dev 环境跑得起来,login 通,主页面能加载。

步骤:

1. `mvn clean install -Dgpg.skip=true -DskipTests` (确认编译过,后台跑)
2. `mvn spring-boot:run -pl spring-ai-loom-agent-test` (后台启动,日志盯住直到 "Started" 字样)
3. 用 chrome-devtools `navigate_page` 打开 `http://localhost:8080/spring/ai/loom/`
4. 验证 login 页加载、`wb04307201/123456` 默认账号能登录
5. 主聊天页渲染: 侧边栏 + 工具栏 6 个按钮 + 消息流输入框可见

**Smoke 通过标准:** 至少能发一条 hello 给 LLM 拿到 SSE 回复。

### 阶段 2: 主对话 UI 全模块回归

7 个工具栏按钮对应 7 个模态框,加上侧边栏和消息流。**每个模块独立一段,发现问题立即修,修完再继续下一个**。

#### 2.1 侧边栏 + 对话管理

| 验证点 | 操作 |
|--------|------|
| 新建对话 | 点 "+" → 检查新对话出现在侧边栏头部 |
| 切换对话 | 点旧对话 → 检查消息流切换 |
| 删除对话 | 悬停 → 点删除 → 二次确认 → 列表移除 |
| 重命名 | inline edit → 检查保存生效 |
| toolbar 新建按钮 | 验证 `6503575` 修复: 当模态框为空时隐藏 |
| 空状态 | 全删后看 "暂无对话" 提示 |

#### 2.2 消息流 + SSE

| 验证点 | 操作 |
|--------|------|
| 基础聊天 | 发 hello → 看 SSE 流式输出 |
| Markdown | 发含 ``` 的消息 → 看代码块 |
| 长消息滚动 | 滚动到底部按钮行为 |
| 复制按钮 | hover 消息气泡 → 复制内容 |
| 附件上传 | 上传 .txt → 看气泡附件样式 |
| 工具调用 trace | 触发 MCP tool → 看中间步骤展示 |

#### 2.3 知识库 (ks-modal)

| 验证点 | 操作 |
|--------|------|
| 新建知识库 | 输入名称 → 创建 |
| 上传文件 | 拖拽/点选 → 看文件列表 |
| 向量化进度 | 大文件时看进度条 |
| 聊天引用 | "基于知识库 xxx 回答 Y" |
| 删除知识库 | 二次确认 |

#### 2.4 MCP 工具 (mcp-modal)

| 验证点 | 操作 |
|--------|------|
| 工具列表 | 默认 14 个 mcp_tool 是否都在 |
| 启用/禁用 | toggle 后刷新看状态 |
| 真实调用 | 在对话里触发该 tool → 看 tool_call 日志 |
| 错误处理 | 错误 MCP 配置时是否友好提示 |

#### 2.5 技能 (skills-modal, 三 tab)

| 验证点 | 操作 |
|--------|------|
| mine tab | 我的技能列表 + 使用次数 |
| market tab | 浏览市场、申请 |
| submit tab | 提交新技能 → 后台 admin 审批 |
| 技能调用 | 在对话里引用 → 看 skill tool 触发 |

#### 2.6 文件管理 (file-modal)

最近修过 `BUG-RBAC-FILE-WOPI`,重点回归:

| 验证点 | 操作 |
|--------|------|
| 目录树渲染 | `~/.loom/file/{username}/` |
| 上传 | 多文件 + 重名序号 |
| 预览 | 文件预览按钮 → 弹窗/iframe |
| 下载 | 下载按钮 → blob 流 |
| 删除 | 二次确认 → 列表移除 |
| 跨用户隔离 | 用另一个非 admin 账号登录,看不到别人的文件 |

#### 2.7 子任务 (subtask-modal, operations console 新设计)

最近修过 `BUG-RBAC-SUBTASK-KILL` + 整 modal 改成 operations console。重点回归:

| 验证点 | 操作 |
|--------|------|
| 新建子任务 | 输入 prompt → 看进度 |
| 列表 | 实时刷新状态 (pending/running/done/failed) |
| 取消单个 | 取消按钮 → 状态切换 |
| 取消全部 | cancel-all 按钮 |
| 历史 | 完成后查看历史记录 |
| 跨用户隔离 | 用另一个账号看不到别人的子任务 |
| 视觉一致性 | 跟 `f100a6c` 设计的 operations console 风格是否统一 |

#### 2.8 定时任务 (schedule-modal, operations console 新设计)

最近加了 `ScheduleRestoreListener` + H2 持久化(V13)。重点回归:

| 验证点 | 操作 |
|--------|------|
| 创建定时 | 表达式/间隔 → 创建 |
| 列表 | 任务列表 + 下次触发时间 |
| 取消 | 取消按钮 → 状态切换 |
| 历史 | 历史面板 |
| 重启后保留 | 启服务 → 任务是否被 restore |
| 过期清理 | 创建 72h+ 前的任务 → 启动时是否清理 |

### 阶段 3: Admin Console 回归

需切到 admin 账号。6 个页面逐个:

#### 3.1 用户管理 (user.html)
- 新建/编辑/删除用户
- 角色分配
- 密码重置

#### 3.2 角色管理 (roles.html)
- 权限分配
- 技能绑定 (role_skill)
- MCP 授权 (role_mcp)

#### 3.3 MCP 管理 (mcps.html)
- MCP server 注册
- tool 列表维护
- default-selected 状态

#### 3.4 技能市场 (skills-market.html)
- 审批用户提交
- 上下架

#### 3.5 统计 (stats.html)
- 图表加载
- 数据准确性 (对比主对话侧)

#### 3.6 会话管理 (conversation.html)
- 看所有用户对话
- 强制删除

### 阶段 4: 收尾

1. 整理所有 fix commit,核对覆盖了哪些验证点
2. 把 UI 优化建议写到 `docs/ui-optimization-todo.md`
3. 写测试报告 `docs/test-report-2026-07-20.md`
4. 截图归档到 `.claude/test-screenshots/2026-07-20/`

## 关键约定

### Commit 规范

每个 bug 单独 commit,沿用仓库现有命名:

```
fix(模块): 描述 (BUG-XXX-YYY)
ui(模块): 描述
```

### 不做什么

- 不做无关重构 (CLAUDE.md 红线)
- 不改业务逻辑除非发现 bug
- 不更新 README 中"上次测试"的日期 (只在新版本发布时更新)

### 临时数据清理

测试期间创建的所有临时数据 (知识库/MCP/技能/会话/上传文件) 用 `tmp-` 前缀,阶段 4 一次性清:

- 数据库: `DELETE FROM ... WHERE name LIKE 'tmp-%'`
- 文件: `find ~/.loom -name "tmp-*" -exec rm -rf {} \;`
- H2 vector: tmp 前缀的 knowledge 重新清

### 测试期间截图

`take_screenshot` 截图统一存 `.claude/test-screenshots/2026-07-20/`,文件名用模块_验证点.png。

## 产物清单

| 产物 | 路径 | 说明 |
|------|------|------|
| 设计文档 | `docs/superpowers/specs/2026-07-20-loom-agent-testing-route-design.md` | 本文档 |
| UI 优化清单 | `docs/ui-optimization-todo.md` | 4 维度分类待办 |
| 测试报告 | `docs/test-report-2026-07-20.md` | 覆盖率、通过率、关键发现 |
| 截图 | `.claude/test-screenshots/2026-07-20/*.png` | 测试期间所有截图 |

## 风险与回滚

- **服务启动失败:** 看 mvn 日志 → 修依赖 → 不影响线上
- **bug 修复引发新 bug:** 修复后立即在相关模块做最小回归
- **admin 改坏:** H2 console 直接 SQL 回滚
- **操作不可逆:** 删除/取消等危险操作都用 `tmp-` 前缀,失败可清理

## 时间预估

| 阶段 | 预估时间 |
|------|----------|
| 阶段 1 | 15 min |
| 阶段 2 | 2-3 hours |
| 阶段 3 | 1 hour |
| 阶段 4 | 30 min |
| **总计** | **4-5 hours** |

(实际取决于 bug 数量,bug 多则需要更多时间修)