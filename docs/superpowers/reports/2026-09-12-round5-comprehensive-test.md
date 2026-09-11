# 第五轮全面测试报告(2026-09-12)

> 触发:F-2 a11y 对比度修复(commit `91799f3`)落地后的全量验证 + 用户要求"全面测试(单元 + Chrome + 其他维度)"
> 环境:Windows 11 + Chrome DevTools MCP,真实 DashScope qwen3.8-max,4 MCP server(base 种子),全新库(Flyway V1.0 + V1.1),:8080
> 方法:Maven 回归门(L0)+ 清库 IT gate 含 15 个 Playwright 浏览器 IT(L1)+ Chrome 活体功能/安全/性能/a11y/响应式(L2)+ 真实 LLM 端到端烟雾(L3)

---

## 结论

**L0 回归门首曝 2 个缺陷(均源于 F-2 commit `91799f3` 的收尾遗漏)—— 已修复(commits `4f07049` + `6e90aa4`),全量回归门复绿**;另发现 4 个 Minor 观察项(陈旧文案 / SEO / favicon / gzip),用户裁决全修(commit `d355ce0`),修复后 Lighthouse **4 类全 100 分**。

| 维度 | 结果 | 说明 |
|------|------|------|
| L0 单元回归门 | ❌→✅ **2 缺陷已修** | lib **195/0** · test **463/0**(修前 1 红) |
| L1 清库 IT gate | ✅ | **186/0/3skip**(15 浏览器 IT 全绿:视觉基线 10 页 / LLM 烟雾 / auth / RBAC / market / style token) |
| L2 功能(Chrome 活体) | ✅ | 登录→index→真实 LLM 对话(工具调用 + SSE 流 + 推理轨迹)→admin console/roles 授权面板 |
| L2 安全 | ✅ | 匿名全站 401 / admin 端点拦截 / 路径穿越被限流(404)/ 错误密码 401 / HttpOnly cookie |
| L2 性能(Core Web Vitals) | ✅ | **LCP 213ms · CLS 0.00 · TTFB 3ms**(优秀) |
| L2 a11y(Lighthouse) | ✅ | 修前 SEO 80;**O-2/O-3 修复后 Accessibility 100 · Best-Practices 100 · SEO 100 · Agentic 100**(navigation 模式,0 failed audit) |
| L2 响应式 | ✅ | 390px 移动端 **0 水平溢出**(B3 `min-width:0` 修复仍生效) |
| L3 真实 LLM 端到端 | ✅ | qwen3.8-max:`getCurrentTime(Asia/Shanghai)` 工具调用成功,SSE 流式渲染 + 推理折叠区正常,RBAC 工具清单精确 |

---

## 缺陷与修复(均源于 F-2 commit `91799f3`)

### DEFECT-R5-1(Important,gate-blocking):a11y 换肤漏改单元契约测试

**现象**:`mvn install` 回归门 1 红 —— `AskUserCardStyleContractTest.askUserCardBlockUsesRealDesignTokens:43`。

**根因**:F-2 有意将主色 fallback `#6366f1 → #4f46e5`(达 WCAG AA 6.0:1),并同步更新了浏览器侧 `StyleTokenBrowserIT`(computed-style 断言 `#4f46e5`),**却漏改** unit 侧 `AskUserCardStyleContractTest` —— 该测试第 43 行钉死了旧字面值 `var(--primary-color, #6366f1)`。产品 CSS 正确(新值即预期 a11y 值),是测试断言陈旧。同测试的 44–46 行(border/text/success)只校验 token **名**不带 hex —— 唯独 primary 过钉,是它自身的不一致。

**修复**(commit `6e90aa4`):解钉具体 hex,只断言消费真实 token 名 `var(--primary-color`,与 44–46 行同粒度;权威值锁定交给 `StyleTokenBrowserIT` 的 computed-style 断言(单一真源),避免随换肤再次脆裂。

### DEFECT-R5-2(Minor):F-2 批量替换漏网 rgba 残留

**现象**:`style.css:3971` `.askuser-opt:hover` 仍是 `background: rgba(99,102,241,.04)`;全仓 `rgba(79,70,229` 出现 **0 次** —— F-2 commit message 声称的 `rgba(99,102,241 → rgba(79,70,229` 替换腿实际从未生效。

**根因**:F-2 用 perl hash 一次性映射做批量替换,rgba 那条 pattern 未匹配到 `rgba(99,102,241,.04)`(推测空格/格式差异),该行 border-color 的 `#6366f1→#4f46e5` 被改了,同行的 rgba 却漏掉。

**影响**:Minor —— 4% 透明度的 hover 底色 wash(非文字),对比度影响可忽略;但属 F-2 "全站替换"口径的 spec-drift,且新旧主色 hue 不一致(#6366f1 vs #4f46e5 的 alpha 形式)。

**修复**(commit `4f07049`):`rgba(99,102,241,.04)` → `rgba(79,70,229,.04)`(与新主色 #4f46e5 = rgb(79,70,229) 对齐)。

### 防回归:新增 `A11yTokenDriftContractTest`(commit `6e90aa4`)

纯文本扫描 5 个前端资源(`style.css` / `login.css` / `login.html` / `admin/console.css` / `admin/stats.js`),锁死 F-2 已废弃的 4 个旧色板 token(`#6366f1` / `#94a3b8` / `rgba(99,102,241` / `rgba(148,163,184`)不得复现。**该守卫在编写时即捕获了 DEFECT-R5-2 的残留**(证明有效),修复后转绿。未来任何换肤遗漏都会被快速 `mvn test` 门拦下。

---

## L1 清库 IT gate 详情(186/0/3skip)

15 个 Playwright 浏览器 IT 全绿(节选):
- **VisualBaselineBrowserIT** — 10 页固定 viewport 全页截图 vs `browser-baselines/`(0.5% diff 阈值),含 rgba 修复后复验无回归
- **StyleTokenBrowserIT** — 10 核心 CSS 变量 computed-style 精确 = #4f46e5 等 + body 文本对比度 ≥ 4.5(WCAG AA)+ 登录页 A+B 布局契约
- **ChatSmokeBrowserIT** — 真实 SSE 流渲染 + 工具链路(env 守卫,DashScope key 存在 → 实跑)
- **AuthFlowBrowserIT**(6)— 登录/401/302/HttpOnly cookie/登出
- **RbacToolsBrowserIT**(4)— capabilities 过滤 + universal 无 checkbox + 授权可见性
- **PageHealthBrowserIT**(11)— 全页 200 / console 零 error / 关键元素 / 无水平溢出 / load<3s
- **RolesAdminBrowserIT / ConsoleAdminBrowserIT / MarketSkillsAdminBrowserIT / KnowledgeMarketAndStatsBrowserIT / IndexInteractionsBrowserIT / FeatureGateBrowserIT / MarkdownSanitizeBrowserIT** — 全绿
- 服务层 IT:market / review / stats / tag / approval-flow / RBAC-cascade(DEFECT-Q3-1 回归锁)/ RAG-presence / H2VectorStore 全绿

3 skip 均为 env-guarded 合法跳过:Market Acceptance(1)+ DefaultMavenTool 真实项目编译(2,`maven.enabled=false` opt-in)。

---

## L2 Chrome 活体测试详情

### 功能
- 登录(admin/123456)→ 无感跳转 `index.html`,顶栏 admin 徽章,侧边栏会话历史
- **真实 LLM 对话**:`请用一句话告诉我你能访问哪些工具组 + 调用时间工具报北京时间` → qwen3.8-max 调 `getCurrentTime(Asia/Shanghai)` 返回 `2026-09-12 05:39:49`(正确),SSE 流式渲染 + "思考过程"折叠区 + 一句话答案;工具组清单精确反映 RBAC(7 universal + 4 base MCP,**无** git/maven/compile/render 越权)
- **开箱种子核验**:capabilities 9 个(4 MCP enabled = bing/memory/sequential-thinking/npx-fetch;5 disabled = 4 RBAC tool + cn-weather);skills 8 个(2 ROLE_GRANTED = STAR-IJ + 靶心人;6 USER_CREATED 系统种子)
- admin console(用户列表 + base 徽章 + 分配角色)/ roles 授权面板(0 本地工具已授权 / 4 可选 = compile·git·maven·render;4 MCP 已授权带排序 + 默认启用勾选;2 技能已授权)—— 精确匹配 base 种子

### 安全(curl 探测 + Chrome)
- 匿名(无 cookie):`/api/capabilities` `/api/features` `/skill` `/admin/*` 全部 **401**
- admin 静态页匿名:**401**(AuthenticationFilter 拦截)
- 路径穿越(认证后,真实 REST 端点 `file/by-path/view`):`../../etc/passwd` / `C:/Windows/win.ini` / `....//` 变体 → 全部 **404**(未泄漏,confined 到 user 目录);`file/tree?path=..` → 200 但返回 user 自身目录(无逃逸)
- 错误密码登录 → **401**;会话 cookie = `#HttpOnly_` + path=/ + SameSite(无 localStorage token)

### 性能(performance_start_trace,index 页)
- **LCP 213ms**(TTFB 3ms + render delay 210ms)· **CLS 0.00** · 无 render-blocking 实质影响
- 唯一优化点:文档未启用 gzip 压缩(DocumentLatency 估 15.7kB 可省)—— 见观察项 O-4

### a11y(Lighthouse,desktop snapshot)
- **Accessibility 100 / 100**(F-2 对比度修复复验通过)· **Best-Practices 100** · **Agentic Browsing 100** · **SEO 80**(唯一扣分:缺 meta-description,见 O-2)

### 响应式
- 390×844 移动端 emulation:全页截图 + `scrollWidth == innerWidth == 390`,**0 水平溢出元素**(B3 `min-width:0 + box-sizing:border-box` 修复仍生效)

---

## 观察项修复记录(Minor,用户裁决全修 → commit `d355ce0`)

| # | 观察项 | 影响 | 修复 |
|---|--------|------|------|
| O-1 | `roles.html` 标签"授权本地工具组(M5:对应 **9 个** I*Tool 接口)"—— 实际 11 个 @ToolGroup(7 universal + 4 RBAC) | 陈旧文案,admin 可见;功能正确(面板精确列 4 RBAC 工具) | 改为描述性文案"仅 RBAC 工具;universal 不在此列"(去硬编码计数,防再漂移) |
| O-2 | 全 10 个 HTML 页缺 `<meta name="description">` | Lighthouse SEO 80(从未存在,非回归) | 10 页按页定制补 meta-description → **SEO 100** |
| O-3 | 无 favicon → 真实 Chrome 每页 `/favicon.ico` 404 + console error(headless Playwright IT 不请求,故从未被 IT 捕获) | console 噪音,非功能缺陷 | 10 页补 `<link rel="icon" href="/static/logo.png">` → console **零 error** |
| O-4 | server 未启用响应压缩(`server.compression`) | index.html 23.5kB 未 gzip,trace 估省 15.7kB | test app yml 开 gzip(html/css/js/json/svg,min 2KB)→ `Content-Encoding: gzip` 实测生效 |

**修复后复验**:Lighthouse navigation 模式 4 类全 **100**(Accessibility / Best-Practices / SEO / Agentic,0 failed audit,44 passed);视觉基线 10 页 diff=**0.0000%**(head 变更不影响像素,无需重建基线);PageHealth + AuthFlow + StyleToken + VisualBaseline IT **31/31 绿**;真实 Chrome 登录→index console 零 error、`/static/logo.png` 200。

---

## 测试卫生

- IT gate 用 `./target/test-ds` + `./target/e2e-files`(隔离),`mvn clean` 即清
- Chrome 活体测试用 `~/.loom/datasource`(live app DB):创建了 1 个测试会话("请用一句话告诉我...")+ 触发了 1 次真实 LLM 调用 + getCurrentTime 工具日志 —— 均落在 live 库,如需纯净种子态可 `rm -rf ~/.loom/datasource` 后重启
- Lighthouse 报告:`spring-ai-loom-agent-test/target/run5-lighthouse/report.{json,html}`
- Chrome 截图:`spring-ai-loom-agent-test/target/run5-shots/`(login / index-fresh / chat-llm-complete / index-mobile-390)
- 起始 8080 上有一个 2026-09-11 22:42 的**陈旧实例**(PID 52112,F-2 commit 之前启动)持有端口 + datasource 锁 —— 已 kill,重启全新实例(PID 57952)方得干净种子态

---

## 复现命令

```bash
# L0 单元回归门(排除被会话 MCP 占用的 *-mcp jar)
mvn install -Dgpg.skip=true -pl '!loom-file-mcp,!loom-git-mcp,!loom-maven-mcp,!loom-compile-mcp'

# L1 清库 IT gate
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports spring-ai-loom-agent-test/target/e2e-files
mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false

# L2 活体
mvn spring-boot:run -pl spring-ai-loom-agent-test   # :8080,再用 Chrome DevTools MCP 驱动
```
