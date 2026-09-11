# 全面测试报告 — 2026-09-11

> 计划：[`docs/superpowers/plans/2026-09-11-comprehensive-test.md`](../superpowers/plans/2026-09-11-comprehensive-test.md)
> 四层测试：L1 单元 / L2 计算样式 + 截图基线 / L3 浏览器行为 IT / L4 AI 视觉审查 + Lighthouse
> 截图资产：[`assets/2026-09-11/`](assets/2026-09-11/)（10 页 × 2 断点 = 20 张）

## 执行摘要

**结论:四层测试全部完成,回归全绿(单元 457 + IT 186,0 失败;3 skip 均为 env 门控合法跳过)。**
过程中发现并当场修复 2 个**测试自身的顺序依赖缺陷**(`d1ba5e1`);产品侧发现 1 个 P1(admin 无移动断点)、
1 个 critical a11y(色彩对比度系统性不达标)与 3 个 P2 —— 全部登记 §4 follow-up,本轮未修。

| 层 | 结果 | 说明 |
|----|------|------|
| L1 单元 | ✅ 457/457 绿 | `mvn test -pl spring-ai-loom-agent-test`(wiped `~/.loom/datasource` 重跑,2026-09-11 20:57) |
| L2 计算样式 / 截图基线 | ✅ 全绿 | `StyleTokenBrowserIT` + `VisualBaselineBrowserIT` 10 页 diff=0.0000%(修复后) |
| L3 浏览器行为 IT | ✅ 186 run / 0 fail / 3 skip | `-Dtest='*IT'` wiped test-ds 全量 gate(21:38);skip = MarketAcceptanceIT×1 + DefaultMavenTool×2(env 门控) |
| L4 AI 视觉 + Lighthouse | ✅ 完成 | 20 截图 + 6 Lighthouse + 2 perf trace;findings V-1..V-6(§1) |

**IT gate 首跑发现并修复(测试缺陷,非产品 bug,commit `d1ba5e1`):**
1. `KnowledgeMarketAndStatsBrowserIT.statsPageRendersBothSections` — stats 表头断言 `contains("总 Token")`,但 `console.css` 对 th 施加 `text-transform:uppercase`,`innerText` 实际返回 "总 TOKEN";仅在前序 ChatSmoke IT 写入真实用量、表格走非空分支时暴露 → 改 `containsIgnoringCase`。
2. `VisualBaselineBrowserIT` admin-stats(3.16%)/admin-user(2.45%)超阈 — 全量 gate 下本类字母序靠后,`test-ds` 已残留前序 IT 的会话与 token 用量行,页面渲染非空态偏离干净库基线 → 新增 `@BeforeAll` 直连 H2 清空 6 张易变表(loom_chat_usage / loom_chat_reasoning / loom_tool_call_log / loom_subtask_history / SPRING_AI_CHAT_MEMORY / user_conversation)。

**产品 findings 分级**(详见 §1 / §4):P0 = 0;**P1 = 1**(V-1 admin 无移动断点);**critical a11y = 1**(§3.1 对比度 3 token 全站不达标);P2 = 3(V-2 文案不一致、meta-description 缺失、V-3 工具按钮换行)。

## 1. L4 视觉问题清单（页面 × 严重度 × 截图）

审查环境：chrome-devtools MCP，desktop 1440×900 / mobile 390×844（`emulate` viewport，dpr=2，mobile+touch），
未登录页用隔离 context（review-login / review-lh-login），登录态用隔离 context（review-clean，表单登录 wb04307201）。
评审清单：对齐 / 间距一致性 / 配色协调 / 圆角阴影统一 / 响应式破损 / 文本截断溢出 / 焦点态可见。

| # | 严重度 | 页面 | 问题 | 证据 / 截图 |
|---|--------|------|------|-------------|
| V-1 | **P1** | admin 全部 8 页（console / stats / user / roles / mcps / conversation / market-skills / knowledge-market） | **移动端无响应式断点**：共享 admin 布局 CSS（`admin/console.css`）无任何 `@media` 查询，`.admin-sidebar` 在 390px 下仍固定 220px 展开（`display:flex; position:static`），主内容区被压至 ~170px；`.table-container`（`overflow-x:auto`）可视宽仅 106px，600–1232px 宽的表格需横向滚动；工具栏按钮（"+ 新建用户" / "刷新"）竖排逐字换行。表格横向滚动策略本身存在（wrapper `overflow-x:auto`，无 document 级溢出），但移动端实际不可用 | `console-mobile-390.png`、`market-skills-mobile-390.png`、`stats-mobile-390.png`、`roles-mobile-390.png`、`mcps-mobile-390.png`、`user-mobile-390.png`、`conversation-mobile-390.png`；实测 `.admin-sidebar` w=220 @ innerW=390、`main` w=170、`.table-container` cw=106/sw=600（console）与 cw=106/sw=1232（market-skills）；`grep @media admin/*.css` = 0 |
| V-2 | P2 | `admin/roles.html` | 侧边栏导航文案不一致：指向 `mcps.html` 的链接在 roles.html 显示"**能力维护**"（`roles.html:35`），其余 5 页（console / knowledge-market / market-skills / mcps / stats）均为"**MCP 描述维护**" | `roles-desktop-1440.png`、`roles-mobile-390.png` |
| V-3 | P2 | `index.html`（mobile） | 6 个工具按钮（知识空间/工具/技能库/文件/子任务/定时）在 390px 下换行为两行——可接受但拥挤；侧边栏正确折叠为 ☰ hamburger，无 document 级横向溢出（`scrollWidth == innerWidth`，off-canvas 抽屉 left=-280 为折叠态正常值） | `index-mobile-390.png` |
| V-4 | info | `admin/user.html` / `admin/conversation.html` | 无 query 参数时 JS 直接 `window.location.replace("console.html")`（`user.js:7` / `conversation.js:8`）——**设计如此**的参数门控详情页，审查时带参访问（`?username=…` / `?id=…&username=…`）正常渲染 | `user-desktop-1440.png`、`conversation-desktop-1440.png` |
| V-5 | OK | `login.html`（desktop + mobile） | 品牌卡两断点均居中、无溢出（mobile 卡宽 350px / 左右 20px 留白，`body.scrollHeight == 844` 无滚动）；用户名输入框焦点环可见（紫色 outline）；顶部 60px 白顶栏与主应用一致 | `login-desktop-1440.png`、`login-mobile-390.png` |
| V-6 | OK | `index.html` / `knowledge-market.html`（mobile） | 无 document 级横向溢出；knowledge-market 无 `<table>`（卡片布局），`docOverflow=false` | `index-mobile-390.png`、`knowledge-market-mobile-390.png` |

## 2. Lighthouse 分数表

chrome-devtools MCP `lighthouse_audit`（navigation 模式）。**注意：该 MCP 集成不含 performance 类别**（tool 设计如此），
performance 以 CDP `performance_start_trace` 的 Core Web Vitals 补齐（§3）。

| 页面 | 设备 | Accessibility | Best Practices | SEO | Agentic Browsing | 失败审计数 |
|------|------|---------------|----------------|-----|------------------|-----------|
| `index.html` | desktop 1440×900 | 95 | 100 | 90 | 100 | 2 |
| `login.html` | desktop 1440×900 | 93 | 100 | 91 | 100 | 2 |
| `admin/console.html` | desktop 1440×900 | 92 | 100 | 90 | 100 | 2 |
| `admin/market-skills.html` | desktop 1440×900 | 92 | 100 | 90 | 100 | 2 |
| `index.html` | mobile 390×844 | 95 | 100 | 90 | 100 | 2 |
| `login.html` | mobile 390×844 | 93 | 100 | 91 | 100 | 2 |

## 3. Top issues（含 performance trace）

### 3.1 🔴 critical a11y：系统性色彩对比度不达标（WCAG AA 4.5:1）

Lighthouse `color-contrast` 在**全部 6 次审计**中失败，根因是 3 个设计 token，而非个别页面：

| Token 组合 | 实测对比度 | 出现位置（selector） |
|------------|-----------|---------------------|
| 弱化灰 `#94a3b8` on 白/浅底 | **2.45–2.56:1** | `header-right`、`.login-header p`（login）；`.admin-sidebar-subtitle`、`.admin-subtitle`、`#admin-username`、`table thead th`（admin 各页） |
| 主色 `#6366f1` on 浅底 | **3.94:1** | `.user-menu-badge`（index）；`.admin-sidebar-link span`（admin 各页） |
| 白字 on 主色 `#6366f1` 按钮 | **4.46:1** | `#new-chat-btn`（index）；`#create-user-btn` / `#create-skill-btn`（admin） |

表头 `thead th` 的 2.45:1 为全站最低。AA 要求正文 4.5:1（大号文本 3:1），以上均为正文级字号 → 全部不达标。

### 3.2 其余失败审计

- `meta-description`：6 页全部缺失（SEO 90–91 的唯一扣分项；内网应用影响有限）。

### 3.3 Performance（CDP trace 补测）

| 页面 / 设备 | LCP | LCP 分解 | CLS |
|-------------|-----|----------|-----|
| `index.html` desktop | **144 ms** | TTFB 2ms + render delay 142ms | 0.00 |
| `login.html` mobile | **573 ms** | TTFB 1ms + load 14ms + render delay 558ms | 0.00 |

两者均为 "good" 区间（LCP < 2.5s、CLS < 0.1）。login mobile 的 558ms render delay 主要来自品牌卡织线纹理背景 + 字体首绘，本地 H2 场景可接受。
trace 另提示：index 首文档响应未压缩（wasted ~15.7kB）、静态资源无长缓存（`Cache` insight）——内网单实例影响有限，记录备查。

## 4. 建议修复项（**不在本轮修**，立项 follow-up）

1. **F-1（P1，响应式）**：admin 布局加移动断点——`@media (max-width: 768px)` 下 `.admin-sidebar` 转 off-canvas 抽屉（对齐主应用 index 的 ☰ 模式）或折叠为 icon rail；`.table-container` 保留 `overflow-x:auto` 并给 main 区 `min-width:0`。验收：390px 下 main 内容区 ≥ 320px。
2. **F-2（🔴 a11y）**：替换 3 个不达标 token——弱化灰 `#94a3b8` → `#64748b`(4.76:1) 或更深；主色文字用途 → `#4f46e5`(6.0:1)；主色按钮底 → `#4f46e5` 保白字 6.0:1。改 `style.css` / `console.css` / `login.css` 变量后重跑 L2 对比度 IT + Lighthouse。
3. **F-3（P2）**：`roles.html:35` 导航文案统一为 "MCP 描述维护"。
4. **F-4（P2）**：6 页补 `<meta name="description">`。
5. **F-5（P2）**：index mobile 工具按钮行改横向滚动条或 3×2 网格，消除两行参差换行。

> 产品 bug（如有）走 `superpowers:systematic-debugging` 单独立项，不在本报告修。

---

## 附录 A：截图清单（assets/2026-09-11/）

login / index / console / stats / user / roles / mcps / conversation / market-skills / knowledge-market × {desktop-1440, mobile-390} 共 20 张。
mobile 截图均为 `emulate 390x844x2,mobile,touch` 真断点（`resize_page` 受浏览器窗口最小宽限制只能到 ~501px，已弃用该路径）。

## 附录 B：Lighthouse 原始报告

6 份 report.json/html 存于会话临时目录（`%TEMP%\chrome-devtools-mcp-*`），不入库；分数与失败明细已摘录于 §2–§3。
