# 2026-10-01 spring-ai-loom-agent 全功能 Browser E2E 测试 — 设计 spec

| 项 | 值 |
|---|---|
| 日期 | 2026-10-01 (Asia/Shanghai) |
| 分支 | `dev-sp2` |
| 作者 | Claude Opus 5 (对话内) + 用户 wb04307201 (协作) |
| 状态 | 设计草案,等待用户 review |
| 上轮 | `docs/notes/2026-09-30-browser-e2e-report.md` (PARTIAL PASS,深度简化) |
| 关系 | 不依赖上轮结论,按全新 spec 重做(11 子系统,与上轮 8 子系统有 60% 覆盖重合) |

---

## 1. 背景与目标

### 1.1 背景

上轮 (2026-09-30) 全功能 Browser E2E 测试在 ~50 min 内跑完 8 个子系统,结论 **PARTIAL PASS** — 可达性 + 关键路径全 OK,但深度被刻意简化("E2E-2/3/4 简化" 出现 7 次),多处关键路径未实际验证:

- Skill `sync` 端点返回 true 但实际写库行为可疑(报告 P1)
- admin 默认无 RBAC tool 授权,需 admin 控制台手动授权(报告 P2)
- Schedule 10s 短间隔实际触发未跑(报告 P3)
- Maven MCP 工具需要 `env` 注入(报告 P4)

此外,上轮未覆盖 **画板 / 对话历史 / Skill picker / 市场审批流** 这 4 个子系统。本轮目标是按全新 spec 全深度重做。

### 1.2 目标

对 `spring-ai-loom-agent` 的 **11 个用户面向子系统**(知识库除外)进行**全深度**(Smoke + E2E + 边界 + 回归)浏览器端到端测试:

- **覆盖**:登录、聊天核心、RBAC Tool、Skill library+market、Sub-task、Schedule、File management、Admin console(不含 KB)、画板、对话历史、Skill picker+市场审批流
- **深度**:Smoke + E2E(happy path 至少 3 场景) + 边界(错误输入 / 异常路径 / 上轮遗留项)
- **驱动**:用户真实 Chrome(经 Claude `mcp__chrome-devtools__*` MCP,主对话 driver)
- **LLM**:项目现有 Anthropic 兼容端点(真实 API 调用,接受 ~$1-3 费用 + 偶发超时)
- **产出**:本 spec + plan + 11 个 Markdown 测试剧本(草稿),下次执行产出 HTML/MD 报告 + baseline 截图集

### 1.3 非目标

- ❌ 跑完后进入正式 CI(本次产出作为**初始 baseline**,后续回归可重跑)
- ❌ 重写或修改现有 `*BrowserIT.java` Java 套件
- ❌ 性能 / 负载 / 并发测试(本次仅验证功能正确性)
- ❌ 国际化 / a11y / 跨浏览器(本次仅 Chromium-based Chrome)
- ❌ Maven / Compile / Render **工具实际触发**(上轮仅验证可见性,本次维持) — 节省 wall clock(避免 docker build / 长 mvn compile / Chromium 下载)

---

## 2. 范围

### 2.1 包含子系统清单 (11)

| # | 子系统 | 入口 | 角色 | 上轮覆盖 | 本轮差异 |
|---|---|---|---|---|---|
| 1 | 登录 + Cookie 认证 | `/login.html` | admin + 普通 user | PARTIAL | **全深度**(E2E-2/3/4 全开) |
| 2 | 聊天核心 | `/index.html` | admin + 普通 user | PARTIAL | **全深度**(异常路径 + 重试 + 长 prompt) |
| 3 | RBAC Tool picker | (聊天 UI) | admin + 普通 user | PARTIAL | **闭环验证**(admin 授权 RBAC tool → user 实测调用) |
| 4 | Skill library + market 基础 | `/admin/skills` | admin + user | PARTIAL | **深度 sync 写库**(上轮 P1) |
| 5 | Sub-task 生命周期 | (聊天 UI) | admin + user | PARTIAL | **完整轮转**(PENDING→RUNNING→COMPLETED,失败场景) |
| 6 | Schedule 短间隔 | (聊天 UI) | admin + user | SMOKE | **完整触发 + 取消**(上轮 P3,10s 间隔) |
| 7 | File management | (modal) | admin + user | PARTIAL | **上传 / 预览 / 下载实操** |
| 8 | Admin console(不含 KB) | `/admin/*` | admin | PASS | **回归为主** + 角色分配 / 工具授权 / MCP 描述 |
| 9 | 画板 (Canvas Board) | ✎ 按钮 | admin + user | ❌ 未测 | **新增** |
| 10 | 对话历史 | 历史侧栏 | admin + user | ❌ 未测 | **新增** |
| 11 | Skill picker + 市场审批流 | 聊天 picker + `/admin/skills` | admin + user | ❌ 未测 | **新增** |

### 2.2 排除清单(明确)

- ❌ **知识库 / RAG**:KB 上传、`/admin/knowledge`、role_knowledge 授权、KB 检索 — 严格按用户要求排除
- ❌ **4 个独立 MCP server 模块**(`loom-{file,git,maven,compile}-mcp`):外部 client 调用,不在 web UI 路径
- ❌ **Maven / Compile / Render 实际调用**:仅验证 tool picker 可见性 + 选中状态,不触发(节省 wall clock)
- ❌ **跨浏览器**:仅 Chrome(Chromium-based)
- ❌ **`CHANGELOG.md` / `RELEASE-*.md` 生成**(项目无此习惯)

### 2.3 RBAC Tool 范围(本轮重要边界)

**主库 11 个 `@ToolGroup` 工具**:

| tool id | 类型 | 本轮处理 |
|---|---|---|
| `tool_askUser` | universal | ✅ 测(触发 + 卡片渲染 + 答案回写) |
| `tool_schedule` | universal | ✅ 测(短间隔 10s 完整触发) |
| `tool_subtask` | universal | ✅ 测(完整生命周期) |
| `tool_file` | universal | ✅ 测(读 / 写 / 列 / 删 + 桥接 fileId) |
| `tool_skill` | universal | ✅ 测(getSkill + createOrUpdateSkill + sync) |
| `tool_knowledge` | universal | ⚠️ 跳过(知识库整体排除) |
| `tool_time` | universal | ✅ 测(getCurrentTime + convertTime) |
| `tool_git` | RBAC | ⚠️ 仅可见性,不触发 clone |
| `tool_maven` | RBAC | ❌ 跳过实际调用 |
| `tool_compile` | RBAC | ❌ 跳过实际调用 |
| `tool_render` | RBAC | ⚠️ 仅可见性,不触发 Chromium 下载 |

**`mcp-servers.json` 4 个 stdio MCP server**(spring-ai-loom-agent-test 启动时自动 spawn):

| mcp name | 类型 | 本轮处理 |
|---|---|---|
| `bing-cn` | 网页抓取 | ✅ 测(简单 fetch) |
| `sequential-thinking` | 推理 | ✅ 测(推理链) |
| `fetch` | URL 抓取 | ✅ 测 |
| `chart` | 图表生成 | ⚠️ 仅可见性 |

---

## 3. 测试深度分层

### 3.1 Smoke 层(每个子系统必须有)

| 项 | 标准 |
|---|---|
| 可达性 | URL 200 OK,关键元素渲染 |
| 控制台 | 0 个 error(允许 warn) |
| 网络 | 0 个 5xx |
| 截图 | 1 张 baseline(子系统入口) |

### 3.2 E2E 层(每个子系统 3-5 个 happy path)

| 类型 | 说明 |
|---|---|
| **主流程** | 用户实际使用路径,含点击 / 输入 / 等待 SSE |
| **跨子系统联动** | 如 chat → 触发 skill → 更新 market |
| **角色权限** | admin / 普通 user 对比 |
| **持久化** | DB 写入后重启验证 |

### 3.3 边界层(每个子系统 2-4 个 edge case)

| 类型 | 说明 |
|---|---|
| **错误输入** | 空字符串 / 超长 / 特殊字符 / SQL 注入 / XSS 尝试 |
| **异常路径** | 网络中断 / LLM 超时 / Cookie 过期 |
| **并发** | 多 tab 同账号 / 快速重复点击 |
| **状态边界** | 空状态 / 加载中 / 已禁用 / 已归档 |

### 3.4 回归层(本轮特别要求)

| 范围 | 说明 |
|---|---|
| **上轮 PASS 项** | admin 控制台 / 登录基础 / 画板(新增无回归) |
| **上轮遗留 4 项** | Skill sync / RBAC 授权闭环 / Schedule 10s / Maven env(本轮不实际跑 Maven,但要验证 RBAC 授权闭环通用路径) |

---

## 4. 严重度分类(问题分级)

| 等级 | 定义 | 例子 |
|---|---|---|
| **CRITICAL** | 崩溃 / 丢失数据 / 安全洞 / 数据竞争 / 阻塞主流程 | 登录死循环 / 聊天 SSE 永不结束 / 删除用户后挂数据 |
| **MAJOR** | 功能不可用 / 错误结果 / 状态卡死 / 文档缺失关键步骤 | admin 改角色不生效 / Schedule 创建后无记录 |
| **MINOR** | 边界 case 行为不当 / 可变通 / 错误消息含技术术语 | 空 KB 列表显示 undefined / 错误消息含 stacktrace |
| **UI** | 样式 / 对齐 / 动画 / 文案 / 交互流畅度 / a11y | 字体错位 / 按钮 hover 无反馈 / 移动端溢出 |

**判定标准**:
- CRITICAL = 阻断 E2E 主流程,无法继续测试
- MAJOR = 功能明确错误,但有 workaround 可继续
- MINOR = 边界 / 体验缺陷,不阻断
- UI = 纯视觉 / 交互问题,不涉及逻辑

**修复成本评估**(在最终报告):
- S (< 1 小时)
- M (1-4 小时)
- L (> 4 小时)

---

## 5. 交付物结构

### 5.1 本次会话产出(计划文档先行)

```
docs/superpowers/specs/2026-10-01-browser-e2e-design.md  # 本文档
docs/superpowers/plans/2026-10-01-browser-e2e.md          # 实施 plan
test/e2e/2026-10-01-00-fixtures.md                        # fixture 启动剧本
test/e2e/2026-10-01-01-login.md                           # 子系统 #1
test/e2e/2026-10-01-02-chat.md                            # 子系统 #2
test/e2e/2026-10-01-03-rbac-tool.md                       # 子系统 #3
test/e2e/2026-10-01-04-skill.md                           # 子系统 #4
test/e2e/2026-10-01-05-subtask.md                         # 子系统 #5
test/e2e/2026-10-01-06-schedule.md                        # 子系统 #6
test/e2e/2026-10-01-07-file.md                            # 子系统 #7
test/e2e/2026-10-01-08-admin.md                           # 子系统 #8
test/e2e/2026-10-01-09-canvas.md                          # 子系统 #9 画板
test/e2e/2026-10-01-10-conversation.md                    # 子系统 #10 对话历史
test/e2e/2026-10-01-11-skill-approval.md                  # 子系统 #11 Skill picker + 审批
```

### 5.2 下次会话产出(执行后)

```
docs/notes/2026-10-01-browser-e2e-report.md                 # Markdown 报告
docs/notes/2026-10-01-browser-e2e-report.html               # HTML 报告
docs/notes/browser-baselines-2026-10-01/                    # 截图集
  ├── 01-login/
  │   ├── login.png
  │   ├── admin-logged-in.png
  │   └── ...
  ├── 02-chat/
  ├── ...
  └── 11-skill-approval/
```

---

## 6. 测试基础设施

### 6.1 环境

- **JDK**: 25.0.3 (`JAVA_HOME=/c/Program Files/Java/jdk-25.0.3`)
- **App**: spring-ai-loom-agent-test (Spring Boot 4.x + Spring AI 2.x)
- **DB**: H2 文件模式,`~/.loom/datasource/db.mv.db`
- **LLM**: DashScope `/apps/anthropic` qwen3.8-max (enable_thinking=true)
- **Anthropic HTTP timeout**: 600s(流式 sub-task 不被截断)

### 6.2 默认账号(来自 V1.0__init.sql)

```
admin:    wb04307201 / 123456  (BCrypt cost=10,角色 = ADMIN + base)
普通 user: 测试中由 admin 在 /admin/users 创建
```

### 6.3 隔离约定

- 用户树: `./target/e2e-files/users` (`users-base-path`,相对 spring-ai-loom-agent-test)
- DB: `./target/test-ds` (独立 Flyway)
- baseline 截图: `docs/notes/browser-baselines-2026-10-01/`
- 上轮目录: `docs/notes/browser-baselines-2026-09-30/`(保留对照)
- `mvn clean` 清空上述隔离目录

### 6.4 Chrome DevTools MCP 用法

| 操作 | 工具 |
|---|---|
| 新建 page | `mcp__chrome-devtools__new_page` url=`<URL>` |
| snapshot | `mcp__chrome-devtools__take_snapshot` pageId |
| 点击 | `mcp__chrome-devtools__click` pageId uid=`<uid>` |
| 填表 | `mcp__chrome-devtools__fill` pageId uid=`<uid>` value=`<val>` |
| 多表单同填 | `mcp__chrome-devtools__fill_form` pageId elements=[...] |
| 等待文本 | `mcp__chrome-devtools__wait_for` pageId text=[...] |
| 网络监听 | `mcp__chrome-devtools__list_network_requests` pageId filter=`<regex>` |
| 控制台 | `mcp__chrome-devtools__list_console_messages` pageId |
| 截图 | `mcp__chrome-devtools__take_screenshot` pageId filePath=`<path>` |
| 评估 JS | `mcp__chrome-devtools__evaluate_script` pageId function=`() => {...}` |
| 历史前进 | `mcp__chrome-devtools__navigate_page` type=back/forward/url/reload |
| 关闭页 | `mcp__chrome-devtools__close_page` pageId |

**单 page 复用**: 11 子系统测试**复用同一 Chrome page**(不重启 Chrome),Cookie 跨子系统持久。

---

## 7. 失败处理

### 7.1 重试策略

- 每个剧本 **3 次重试**
- 3 次仍 fail → 标 FAIL 继续下一个子系统
- 重试间隔 5s(避免 LLM 限流)

### 7.2 中止条件

- **CRITICAL 失败 = 0 容忍**:登录不可用 / 聊天核心不可用 → **立即 abort 后续子系统**,出紧急报告
- MAJOR / MINOR / UI 不中止

### 7.3 SSE 超时检测(剧本 02-chat / 05-subtask / 06-schedule 共用)

```
def 监听 SSE 流:
    start = now()
    while now() - start < 90s:
        req = list_network_requests(filter="/spring/ai/loom/api/chat")
        if 含 "data: [DONE]" 或 含 "event: done":
            return PASS
        sleep 2s
    return FAIL_TIMEOUT
```

### 7.4 MCP server 启动慢保护(剧本 03-rbac-tool)

- 单 MCP server 超时 60s → 跳过该 server 的工具,继续其他
- 不因 MCP server 问题 fail 整个子系统

---

## 8. 验收标准

### 8.1 子系统级

- 每个子系统产出 1 张 baseline 截图(子系统入口)
- 子系统内每个 E2E 用例产出 1 张过程截图(关键状态)
- 失败用例产出 1 张失败现场截图 + 失败原因 + console log 片段

### 8.2 全局

- 11 子系统全跑完(允许个别 SKIP 但必须标 reason)
- 严重度分类完整(每条问题必须归类)
- **值得修改的清单**:
  - CRITICAL 必须修(无遗漏)
  - MAJOR 列出 + 修复成本评估
  - MINOR 列出(可选修复)
  - UI 列出(可批量修)
- baseline 截图集完整(每个子系统至少 1 张)

### 8.3 报告字数估算

- TL;DR ~ 200 字
- 子系统详细 ~ 11 × 300 字 = 3300 字
- 失败汇总 ~ 500 字
- 值得修改清单 ~ 1000 字
- 总计 ~ 5000 字 Markdown + 11 个子系统章节 × ~ 5 张截图 = 50+ 张 PNG

---

## 9. 已知风险

| # | 风险 | 缓解 |
|---|---|---|
| 1 | LLM 偶发超时(网络 / 限流) | 3 次重试 + 接受偶发超时(不视为 fail) |
| 2 | Maven 真实调用吃 wall clock | 仅验证可见性,不触发(本轮) |
| 3 | 画板 PNG 导出无 lib 验证 | 只看 chat 流是否收到附件 + 预览 |
| 4 | 4 MCP server 启动慢 | 单 server 60s 超时跳过 |
| 5 | Schedule 短间隔(10s)触发后状态变更,需短轮询 | list_schedule 5s 间隔轮询 ≤ 60s |
| 6 | RBAC 授权链路长(admin → role → tool)需多步 | 提前通过 admin 控制台预授权(setup 阶段) |
| 7 | 对话历史若 user 未发过消息则为空 | 预生成 3-5 轮对话(setup 阶段) |
| 8 | 审批流需 2 个 user(admin submit + 普通 user 浏览,或反之) | 提前创建 test-user-1(setup 阶段) |
| 9 | Anthropic thinking 折叠面板可能影响断言 | 沿用上轮模式,断言前 click 展开 |
| 10 | Cookie 跨子系统持久可能掩盖 fresh-login 问题 | 在 01-login 末尾清 cookie,在后续子系统重登 |

---

## 10. 与上轮对比表

| 维度 | 上轮 (2026-09-30) | 本轮 (2026-10-01) |
|---|---|---|
| 子系统数 | 8 | 11 (+ 画板 / 对话历史 / Skill picker+审批) |
| 深度 | PARTIAL (E2E-2/3/4 简化) | **全深度** (Smoke + E2E + 边界) |
| 驱动 | Chrome DevTools MCP | 同 |
| LLM | qwen3.8-max (enable_thinking) | 同 |
| 输出 | spec + plan + 8 剧本 + HTML + MD + baseline | spec + plan + 11 剧本 + (下次执行) HTML + MD + baseline |
| 路径 | `docs/notes/2026-09-30-*` | `docs/notes/2026-10-01-*` + `docs/notes/browser-baselines-2026-10-01/` |
| 修复深度 | Smoke + 关键路径 | Smoke + E2E + 边界 + 上轮遗留 4 项 |
| Maven 实际调用 | 跳过 | 跳过(维持) |
| Render 实际触发 | 跳过 | 跳过(维持) |

---

## 11. Review Checklist(用户 review 时使用)

- [ ] 范围:11 子系统,排除知识库 / Maven / Render 实际调用,符合预期
- [ ] 深度:Smoke + E2E + 边界 + 回归,符合"全深度"定义
- [ ] 严重度:CRITICAL/MAJOR/MINOR/UI 四档,符合预期
- [ ] 交付物路径:`dev-sp2` + `docs/superpowers/{specs,plans}/2026-10-01-*` + `test/e2e/2026-10-01-*`,符合预期
- [ ] 本次仅出 spec/plan/剧本草稿,**不执行**,符合"计划文档先行"
- [ ] baseline 截图集首次生成(2026-10-01 目录),下次跑才 diff
- [ ] 默认账号 wb04307201 / 123456(已确认 V1.0 seed)
- [ ] JDK 25.0.3 + JAVA_HOME(已确认 pom `--release 25`)
- [ ] 已知风险 10 项 + 缓解已列
- [ ] 与上轮对比表清晰