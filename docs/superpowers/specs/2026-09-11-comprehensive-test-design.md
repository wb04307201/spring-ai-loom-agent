# 全面测试设计 — 单元 + 浏览器 E2E + 样式检测 + 补充维度

日期:2026-09-11
状态:已批准(brainstorming 会话)
分支:dev

## 1. 背景与目标

Spring AI LoomAgent 当前测试资产:

- `spring-ai-loom-agent-test` 模块 83 个测试类:~380 单元测试(surefire 默认跑)+ ~80 IT(无 failsafe,需显式 `-Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false`,且要求从 wiped `~/.loom/datasource` + `target/test-ds` 起跑)
- core lib 模块 26 个测试类
- 现有"前端测试"全部是文本契约测试(`ui/*ContractTest` grep 源码断言字符串),**没有任何真实浏览器渲染/样式验证**
- Java 侧 Playwright 1.50.0 已在 test 模块 classpath(产品功能 `IHtmlRenderTool` 用),`HtmlRenderEngineIT` 已验证真 Chromium 可跑(assumeTrue 环境守卫模式)

空白:浏览器 E2E、样式视觉回归、a11y、Lighthouse 审计。

近期变更热点(回归重点):

1. `rag.enabled` 知识空间全局开关 + `/api/features` + 前端 ks-button 门控(f58b1d7 / 6645fc8 / 775af32)
2. 子任务/定时任务 RBAC 工具过滤安全修复(f7b49d6 / a1819cb / 9d26fe7)
3. HtmlRenderEngine / IHtmlRenderTool 新特性(7e33ae6 / 1ab0d5d / e76cf1d)
4. CapabilityService 重构波及的 DefaultChat 工具过滤(2ffc2b2 / a248bc5)

## 2. 已决策项(brainstorming 问答记录)

| 决策点 | 选择 |
|---|---|
| 浏览器测试技术路线 | **Java Playwright IT**(纳入 mvn 体系,零 Node 工具链) |
| 样式检测手段 | **四种全上**:计算样式断言 + 截图基线对比 + AI 视觉审查 + Lighthouse 审计 |
| LLM 依赖 | **非 LLM 为主 + 可选 LLM 烟雾**(无 `DASHSCOPE_PERSON_TOKEN_API_KEY` 则 assumeTrue 跳过) |
| 覆盖范围 | **全面覆盖分层 P0-P2** |
| 数据环境 | E2E 独立数据目录 `target/e2e-ds` + `target/e2e-files`,**绝不触碰 `~/.loom`**;`@SpringBootTest(RANDOM_PORT)` 同 JVM 起应用 |

## 3. 总体结构 — 四层测试金字塔

```
L4 一次性人工评估   AI 视觉审查 + Lighthouse 审计(出报告,不入回归)
L3 浏览器 E2E       Java Playwright *BrowserIT(P0 热点 / P1 全页面 / P2 admin 深路径 / LLM 烟雾可选)
L2 样式回归         计算样式断言 IT + 截图基线对比 IT(纯 Java,入 mvn 回归)
L1 单元 + 现有 IT   380 单元 + 80 IT(先跑基线确认全绿,作为起点门禁)+ 少量新增单测
```

全部落在 `spring-ai-loom-agent-test` 模块,新建 `browser/` 测试包。

## 4. L3 浏览器 E2E 设计

### 4.1 基础设施

**`BrowserTestBase` 抽象基类**:

- `@SpringBootTest(webEnvironment = RANDOM_PORT)`
- Playwright `Browser` 每测试类共享(per-class 静态),`BrowserContext` 每测试方法新建(隔离 cookie/storage)
- 属性覆盖:`spring.datasource.url=jdbc:h2:./target/e2e-ds/db`(suite 起跑前 wipe)、`spring.ai.loom.agent.fileBasePath=./target/e2e-files/file`、`knowledgeBasePath=./target/e2e-files/knowledge`
- Chromium 缺失守卫:`assumeTrue(可执行文件可探测)`,沿用 `HtmlRenderEngineIT` 模式;一次性环境准备 = `playwright install chromium`(Windows 本地)
- 登录 helper:V1.0 种子 admin 账号走真实登录表单(login.html 输入 → cookie 落地);RBAC 场景普通用户通过 admin API 预置(不走 UI,快)
- console error 收集器:每方法监听 `page.onConsoleMessage` / `page.onPageError`,P1 体检统一断言无 error

**Page Object**(13 页对应封装,按需渐进):
`LoginPage` / `IndexPage` / `AdminConsolePage` / `AdminRolesPage` / `AdminUsersPage` / `AdminMcpsPage` / `AdminConversationPage` / `AdminMarketSkillsPage` / `AdminKnowledgeMarketPage` / `AdminStatsPage`。

### 4.2 P0 回归热点(最高优先)

| # | 用例 | 断言 |
|---|---|---|
| P0-1 | 登录链路 | login.html 表单登录 → 跳 index.html;HttpOnly cookie 存在;未认证直接调 API → 401;非 admin 用户访问 `/admin/**` → 403 |
| P0-2 | 知识空间门控 | 两个 profile(`rag.enabled=true` / `false`)分别启动:GET `/api/features` 返回 `{knowledge: true/false}`;前端 ks-button 相应显隐 |
| P0-3 | RBAC 工具过滤 | 无角色用户打开工具弹窗:只见 7 个 universal(schedule/subtask/knowledge/time/skill/file/askUser),不见 git/maven/compile/render;admin API 给角色授 `tool_git` 并分配用户后,该用户可见 |
| P0-4 | 子任务 RBAC 入口 | start_sub_task 工具入口对 universal 用户可见;子任务继承角色过滤(已有 `SubTaskRbacFilterIT` 后端覆盖,浏览器级补前端可见性) |

### 4.3 P1 全页面体检(13 页)

每页统一断言:

1. HTTP 200 加载成功
2. console 无 error(覆盖 marked.js 本地资源、logo/favicon 404、JS 模块加载失败)
3. 关键元素可见(标题/主容器/导航)
4. 无水平溢出(`document.scrollWidth <= window.innerWidth + 1`)
5. 软性能预算:load 事件 < 3s(本地环境,记录不断言失败)

主应用(index.html)加深:

- 侧边栏会话 CRUD(新建/重命名/删除)
- 文件管理模态:目录树展开、文件列表来自 `target/e2e-files` 隔离目录
- 工具弹窗:universal 工具**不展示** checkbox(无感调用契约)
- 消息输入框发送态(LLM 关闭时验证错误提示 UI 不崩溃即可,真实流走 LLM 烟雾)

### 4.4 P2 admin 深路径

| 页面 | 用例 |
|---|---|
| roles.html | 创建角色 → 授权本地工具组(动态拉 `/admin/capabilities`,非硬编码)→ 分配用户 → 删除角色级联清 5 张子表(前端确认弹窗 + 后端断言) |
| console.html | 用户角色分配弹窗对 ADMIN 用户同样打开 + strict RBAC 提示句 |
| market-skills.html | 审批流:PENDING → APPROVED;PENDING → REJECTED(拒绝评论必填校验);官方徽章/featured 展示 |
| knowledge-market.html | KB 市场 CRUD + tag 系统 + filter UI |
| stats.html | 月度 Token 用量区块 + askUser 提问卡片日志区块渲染(数据源 `/admin/ask-logs`) |
| user.html / mcps.html / conversation.html | 基本 CRUD 冒烟(列表加载 + 一个写操作 + 删除) |

### 4.5 LLM 烟雾(`ChatSmokeBrowserIT`,独立可跳过)

- 守卫:`assumeTrue(env DASHSCOPE_PERSON_TOKEN_API_KEY 非空)`
- 用例 1:真实聊天一轮,SSE 流式输出,markdown 正确渲染(代码块/列表/链接)
- 用例 2:诱导触发 time 工具调用("现在几点"),验证工具调用卡片/结果出现在流中
- 不测 LLM 输出内容正确性(非确定性),只测链路通 + UI 渲染不崩

## 5. L2 样式回归设计(入 mvn 回归)

### 5.1 计算样式断言(`StyleTokenBrowserIT`)

- **设计 token**:`getComputedStyle(document.documentElement).getPropertyValue('--primary')` = `#6366f1`、`--bg` = `#f8fafc` 等 style.css 定义的核心变量;顶栏高度 60px;body font-family 已加载(document.fonts.check)
- **布局健康**:关键容器可见且尺寸 > 0;文本元素前景/背景对比度 ≥ WCAG AA 4.5:1(取主要文本节点,纯 Java 计算相对亮度)
- **登录页 A+B 布局契约**:白顶栏(背景近白 + 高 60px + logo/品牌文案)+ 居中品牌卡(圆形 logo + 表单元素齐备)
- **主应用与登录页视觉一致性**:两页 `--primary` 计算值相等(无感切换契约)

### 5.2 截图基线对比(`VisualBaselineBrowserIT`)

- 固定环境:viewport 1280×800、deviceScaleFactor=1、动画禁用(`prefers-reduced-motion` + CSS injection 停 transition)、固定字体环境(同机跑)
- 每页全页截图,与 `src/test/resources/browser-baselines/{page}.png` 逐像素 diff(BufferedImage,RGB 距离阈值 + 差异像素比例阈值 0.5%)
- `-DupdateBaselines=true` 系统属性重建基线
- 失败输出:actual / expected / diff 热力图三图落 `target/visual-diffs/`
- 首跑建基线;基线 PNG 提交入 git(LFS 不引入,13 页 × ~200KB 可接受)
- 已知限制:跨机器字体渲染差异可能误报 —— 约定基线只在同一台开发机更新,CI(如未来有)需同 OS

## 6. L4 一次性评估设计(出报告,不入回归)

### 6.1 AI 视觉审查

- 手动起 test 应用(`mvn spring-boot:run -pl spring-ai-loom-agent-test`)
- chrome-devtools MCP 对 13 页截图:桌面 1440×900 + 移动 390×844 两断点
- 目视评审维度:对齐/间距一致性/配色协调/圆角阴影统一/响应式布局破损/文本截断溢出/暗色模式(如存在)
- 产出:`docs/test-reports/2026-09-11-visual-review.md` 问题清单(按页面 × 严重度)

### 6.2 Lighthouse 审计

- chrome-devtools `lighthouse_audit`(desktop + mobile)跑 4 代表页:index / login / admin console / market-skills
- 维度:accessibility、best-practices、SEO(如适用)、performance
- a11y 严重项(critical)记录入报告,后续可选转 axe-core 注入回归断言(本期不做)
- 产出:分数表 + top issues 入同一报告

## 7. L1 补充单测

| 新增 | 内容 |
|---|---|
| `I18nKeysParityTest` | `zh-CN.json` / `en-US.json` key 集合完全一致 + 无空值 |
| `MarkdownRendererSanitizeTest` | markdown-renderer.js 消毒白名单逻辑:纯 Java 模拟不可行 → 改为在 BrowserIT 中 page.evaluate 直接调用 sanitize 函数,注入 `<script>`/`<img onerror>` 载荷断言被剥除(归 L3 但性质是单测) |

现有 380 单元 + 80 IT **不重写**,只作为基线门禁:任何一阶段开始前全绿。

## 8. 其他维度归属总表

| 维度 | 手段 | 层 |
|---|---|---|
| 安全-路径越权 | 已有 `PathSecurityTest` 等 | L1 复用 |
| 安全-鉴权 | P0-1 浏览器级 401/403 | L3 |
| XSS-消毒 | markdown sanitize page.evaluate 注入测试 | L3 |
| 性能预算 | P1 每页 load < 3s 软记录 + Lighthouse perf | L3/L4 |
| a11y | 对比度断言(L2)+ Lighthouse a11y 报告(L4) | L2/L4 |
| i18n | key 对齐单测 | L1 |
| 静态资源完整性 | console error 断言(marked/logo/favicon) | L3 P1 |

## 9. 执行顺序与交付物

1. **基线门禁**:wiped 状态跑 `mvn test` + `-Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false` 确认全绿;不绿先修
2. 环境准备:确认/安装 Chromium(`playwright install chromium`)
3. `BrowserTestBase` + Page Objects → **P0**(commit `test: ...`)
4. **P1** 全页面体检(commit)
5. **P2** admin 深路径(commit)
6. **L2** 样式:token 断言 → 截图基线首建(commit)
7. **L4**:起应用,AI 视觉审查 + Lighthouse,写报告(commit `docs:`)
8. 收尾:全量回归绿 + CLAUDE.md 增补浏览器测试运行说明 + 汇报

**交付物**:~15-20 个新测试类、13 页截图基线集、`docs/test-reports/2026-09-11-comprehensive-test-report.md`(汇总 + 视觉审查 + Lighthouse)、发现的问题清单(修复走 systematic-debugging 单独任务)。

## 10. 风险与对策

| 风险 | 对策 |
|---|---|
| 截图基线跨机器字体差异误报 | 基线只在同机更新;阈值 0.5%;diff 图人工复核后再 updateBaselines |
| RANDOM_PORT 下前端硬编码 URL | 前端全部相对路径(调研确认),Page Object 统一 `baseUrl` 注入 |
| e2e-ds H2 与 test-ds 冲突 | 独立目录 `target/e2e-ds`,BrowserIT 类不与其他 IT 并行跑(surefire 默认串行,forkCount=1) |
| LLM 烟雾不稳定 | assumeTrue 跳过 + 只断言链路不断言内容 |
| Playwright Browser 泄漏 | BrowserTestBase @AfterAll 强制 close;Context try-with-resources 模式 |
| admin API 预置用户依赖种子账号密码 | 密码从 V1.0__init.sql 种子读取,不硬编码新值 |
