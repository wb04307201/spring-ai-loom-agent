# 第六轮全面测试报告(2026-09-12)

> 触发:用户树路径模型重构(commits `62fd521..2fb9655`,breaking 存储布局变更)落地后的全量回归 + 用户要求"全面测试(单元 + Chrome + MCP + 其他维度)"
> 环境:Windows 11 + Chrome DevTools MCP,真实 DashScope qwen3.8-max,4 外部 MCP server(base 种子),**`rm -rf ~/.loom` 全新库**(Flyway V1.0 + V1.1),:8080
> 方法:Maven 回归门(L0)+ 清库 IT gate 含 14 个 Playwright 浏览器 IT(L1)+ Chrome 活体功能/安全/性能/a11y/响应式(L2)+ 真实 LLM 端到端烟雾(L3)+ **MCP 4 server stdio 冒烟(L4,本轮新增层)**

---

## 结论

**全绿,0 缺陷。** 用户树重构后的 6 个回归靶点全部亲眼核验通过;L0/L1 与重构前基线完全持平;L2 性能/a11y/SEO 维持第五轮满分;L4 首次覆盖 4 个 loom-*-mcp server(30 项断言全过),同时暴露 1 个**历史设计观察项**(OBS-R6-1,非本轮引入,见下)。

| 维度 | 结果 | 说明 |
|------|------|------|
| L0 单元回归门 | ✅ | lib **188/0** · test **470/0**(重构后新基线,+8 新测试) |
| L1 清库 IT gate | ✅ | **186/0/3skip**(14 浏览器 IT 全绿:视觉基线 10 页 / LLM 烟雾 / auth / RBAC / market / style token) |
| L2 功能(Chrome 活体) | ✅ | 登录→上传→**落盘用户树**→文件管理 UI→预览桥接→admin console/roles/stats |
| L2 安全 | ✅ | 匿名全站 401 / admin 拦截 / **5 种路径穿越变体打新沙箱全 404** / 错误密码 401 / HttpOnly cookie |
| L2 性能(Core Web Vitals) | ✅ | **LCP 198ms · CLS 0.00 · TTFB 2ms**(第五轮 213/0.00/3,持平略优) |
| L2 a11y + SEO(Lighthouse) | ✅ | **Accessibility 100 · Best-Practices 100 · SEO 100 · Agentic 100**(39 passed / 0 failed) |
| L2 响应式 | ✅ | 390×844 mobile emulation **0 水平溢出**(scrollWidth==innerWidth==390) |
| L3 真实 LLM 端到端 | ✅ | qwen3.8-max:`getCurrentTime` 工具调用成功,SSE 流 + 推理折叠区正常,RBAC 清单精确(4 RBAC tool 全 disabled,无越权) |
| L4 MCP stdio 冒烟(新) | ✅ | file/git/maven/compile 4 server:**handshake + tools/list 计数(14/14/6/1)+ 沙箱落盘 + 穿越拒绝 + 错误契约,30 断言全过** |

---

## 重构回归靶点核验(6/6 通过)

| # | 靶点 | 核验方式 | 结果 |
|---|------|----------|------|
| 1 | 清库全新安装 | `rm -rf ~/.loom` → 启动 → Flyway `V1.0 init` + `V1.1 init app data` 迁移日志 → admin 登录成功 | ✅ |
| 2 | 用户树真实落盘 | Chrome 上传 `img1.jpg` → `~/.loom/users/wb04307201/file/img1.jpg` 存在(432KB);**旧 `~/.loom/file/` 不再创建**;`~/.loom/` 下只有 `datasource/ + users/` | ✅ |
| 3 | 文件管理模态框 | Chrome 打开 📁 文件模态框:列出 `img1.jpg 421.96 KB` + 预览/下载按钮;`file/tree` API 返回根节点 = `wb04307201`,**无 compile-workspaces 混入** | ✅ |
| 4 | file by-path 桥接 | 预览按钮 → 新页 `image.html?id=4d23ce58-...` 加载真实图片(2464×1500 解码成功)= `getOrCreateFileId`(LoomPaths 改造)→ temp fileId → file-view 全链路通 | ✅ |
| 5 | 启动无绑定错误 | live 启动日志 grep binding/placeholder/ERROR:0 条属性绑定异常(唯一 ERROR 为 logback 布局噪音,第五轮同款) | ✅ |
| 6 | MCP 默认沙箱 | file-mcp `list_allowed_directories` → `C:\Users\wb043\.loom\mcp`;write/read/delete 往返真实落盘该目录;`../smoke6-escape.txt` 穿越被拒(沙箱外无文件) | ✅ |

---

## L2 Chrome 活体测试详情

### 功能
- 登录(wb04307201/123456)→ 无感跳转 index,顶栏"吴博 管理员"徽章,侧边栏正常
- **上传附件 → 发送**:img1.jpg 上传 200,聊天输入区显示缩略图 blob
- admin console:用户列表 + ADMIN 徽章;roles:`base` 角色种子 = **0 本地工具授权 + 4 MCP(sequential-thinking/bing/memory/npx-fetch)+ 2 官方技能(marketSkillId 1/2, defaultLoaded)** — 精确匹配 V1.0 种子
- stats.html:月度 Token 用量表 + askUser 提问卡片区块渲染正常(本轮 LLM 对话产生 719,337 tokens / 89 条流式 usage 行)
- capabilities API:9 项 = 4 RBAC tool(**全 disabled**)+ 4 base MCP enabled + cn-weather disabled — strict RBAC 契约未回归

### 安全(curl 探测,认证 cookie = /tmp/r6-cookies.txt)
- 匿名:`/api/capabilities` `/api/features` `/skill` `/admin/users` `admin/console.html` 全 **401**;`index.html` 200(在 excludePathPatterns 白名单,与 AuthFlowBrowserIT 契约一致 — 未登录访问 index 由前端跳 login)
- **路径穿越打新沙箱**(认证后):`../../etc/passwd` / `..%2f..%2fetc%2fpasswd` / `C:/Windows/win.ini` / `....//....//etc/passwd` / `%2e%2e%2f%2e%2e%2fetc%2fpasswd` → **全 404**;`file/tree?path=..` → 200 但返回 user 自身目录(无逃逸)
- 错误密码 → **401**;session cookie `#HttpOnly` + path=/

### 性能 / a11y / 响应式
- performance trace(index,reload):**LCP 198ms**(TTFB 2ms + render delay 196ms)· **CLS 0.00** — 对比第五轮(213ms/0.00/3ms)持平略优,重构零性能回归
- Lighthouse navigation(desktop):**4 类全 100**,39 passed / 0 failed — 报告存 `spring-ai-loom-agent-test/target/run6-lighthouse/`
- 390×844 mobile + touch emulation:`scrollWidth == innerWidth == 390`,**0 水平溢出**(桌面 resize 下 sidebar 收起态 left=-280 属设计内 off-canvas,非溢出)
- gzip(O-4 回归):`Content-Encoding: gzip` 生效;favicon(O-3 回归):`/static/logo.png` 200;Chrome console **零 error/warn**

## L3 真实 LLM 端到端

- 对话:「请用一句话告诉我你能访问哪些工具组,并调用时间工具报告北京时间(Asia/Shanghai)」
- qwen3.8-max:思考过程折叠区正常 → **调用 `getCurrentTime`** → 回答「北京时间当前为:2026-09-12 20:40:09(周六晚)」(与宿主机时钟一致)
- 工具组自述清单精确反映 RBAC:时间/文件/技能/知识库/子任务/定时/askUser + 4 base MCP,**无 git/maven/compile/render 越权宣称**
- DB 侧核验:`loom_tool_call_log` 恰 1 行 `getCurrentTime`;`loom_chat_reasoning` 1 行;`loom_chat_usage` 89 行(SSE 每 chunk 一行,时间戳 20:40:09–20:40:17 与本轮对话吻合)

## L4 MCP stdio 冒烟(本轮新增,30 断言全过)

驱动:`target/run6-mcp/mcp_smoke.py`(Python stdio JSON-RPC:initialize → tools/list → tools/call)。因 4 个 repackaged jar 被本会话自身 MCP 客户端进程锁定(第五轮同款 gotcha),spawn 方式改用 `target/classes + dependency:build-classpath`,与 jar 等价。

| server | 断言 | 结果 |
|---|---|---|
| **file-mcp** | handshake ok · tools=**14** · `list_allowed_directories`= `~/.loom/mcp` · write→磁盘真实落盘→read 往返 · `../` 穿越拒绝 · delete 无 token 拒绝 · delete 带 `I_CONFIRM_DELETE` 成功 | 8/8 ✅ |
| **git-mcp** | handshake ok · tools=**14** · `git_init` 建 repo 于沙箱 · `git_status`(绝对 workingDir)返回「分支:master/干净」· `git_init ../evil-repo` 拒绝 | 5/5 ✅ |
| **maven-mcp** | handshake ok · tools=**6** · `maven_validate` 无 pom → 有界错误文本(`[Failed] Maven Execution ... POM: ...`),不真跑构建 | 3/3 ✅ |
| **compile-mcp** | handshake ok · tools=**1** · `compile_and_deploy {}` → `Parameter error: gitUrl is required` · 参数校验失败**不触 docker** | 4/4 ✅ |

清理:MCP 沙箱测试残留(smoke6-hello.txt / smoke6-repo)已删,`~/.loom/mcp/` 空。

### OBS-R6-1(Minor 观察项,历史设计,非本轮引入)

`loom-git-mcp` 除 `git_init`/`git_clone`(basePath 锚定 + resolvePath 沙箱校验)外,其余 12 个工具的 `workingDir` 是 **`Paths.get(workingDir)` 直接透传**(`LoomGitMcpService.java:47` 等)——类 javadoc 明写"workingDir 由调用方(LLM)显式传递",属**有意设计**(与主库 DefaultGitTool 的强沙箱不同),但意味着 git-mcp 的 basePath 沙箱只覆盖 init/clone 两个入口,LLM 传任意绝对路径可对全盘仓库做 status/log/add/commit/push。单租户 stdio 部署下风险可接受(调用方即机主);若未来 git-mcp 面向多租户/远端暴露,需补 basePath 锚定。**建议:暂不修,记录在案。**

---

## 缺陷清单

**无。** L0-L4 全程 0 gate-blocking / 0 Important / 0 Minor 缺陷;1 个历史设计观察项(OBS-R6-1)留档不修。

## 测试卫生

- 测试前:`rm -rf ~/.loom`(用户已确认)+ 清 `target/{test-ds,e2e-files,surefire-reports,test-users}` — 同时完成了"升级=清库重跑"路径的实战验证
- 测试后 live `~/.loom` 内容:`datasource/`(本轮全新库:1 个测试会话 + 1 次真实 LLM 调用 + 89 usage 行 + 1 tool log + 1 reasoning)+ `users/wb04307201/file/img1.jpg`(上传测试)+ `mcp/`(空)。如需纯净种子态:`rm -rf ~/.loom/datasource` 重启
- 陈旧实例:8080 无占用;但发现 2 个 **2026-09-11 启动的僵尸 LoomAgentTestApplication JVM**(PID 7364/51368,无端口监听)+ 6 个会话 MCP jar 进程 — 未动(属会话基础设施),live 测试用新实例(PID 59036,测毕已 kill)
- 产物:`target/run6-shots/`(index-fresh / chat-llm-complete / index-mobile-390)· `target/run6-lighthouse/report.{json,html}` · `target/run6-mcp/`(驱动脚本 + 4 份 stderr 日志)· `target-l0-run6.log` / `target-l1-run6.log` / `target-live-run6.log`(均在 gitignore 的 target/ 或仓根待清)

## 复现命令

```bash
# L0 单元回归门(排除被会话 MCP 占用的 *-mcp jar)
mvn install -Dgpg.skip=true -pl '!loom-file-mcp,!loom-git-mcp,!loom-maven-mcp,!loom-compile-mcp'

# L1 清库 IT gate
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/{test-ds,e2e-files,surefire-reports,test-users}
mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false

# L2/L3 活体(全新 ~/.loom)
rm -rf ~/.loom && mvn spring-boot:run -pl spring-ai-loom-agent-test   # :8080,Chrome DevTools MCP 驱动

# L4 MCP 冒烟(先出 classpath,再跑驱动;jar 未被锁时可直接 java -jar)
for m in loom-file-mcp loom-git-mcp loom-maven-mcp loom-compile-mcp; do
  mvn -q dependency:build-classpath -pl $m -Dmdep.outputFile=target/cp.txt -Dgpg.skip=true; done
PYTHONIOENCODING=utf-8 python spring-ai-loom-agent-test/target/run6-mcp/mcp_smoke.py all
```
