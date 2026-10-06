# 全功能多维度 E2E 巡检 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 启动真实 LoomAgent 服务，用真实浏览器模拟人工操作巡检 12 个功能维度，产出分级缺陷清单 + 覆盖矩阵 + 证据截图。

**Architecture:** 巡检而非开发。执行侧用 Chrome DevTools MCP 驱动真实 Chromium 走 UI，用 `curl` 打 REST 端点做交叉验证，证据（截图 / console / 后端日志片段）落 `docs/e2e-audit-2026-10-06/`。不向 `src/test` 写任何测试代码，不修缺陷。

**Tech Stack:** Java 25 / Spring Boot 4.x / Spring AI 2.x / H2 + Flyway / Playwright Chromium（经 MCP）/ Maven

**Spec:** `docs/superpowers/specs/2026-10-06-full-e2e-audit-design.md`

## Global Constraints

- **不写测试代码**：本计划不向 `spring-ai-loom-agent-test/src/test` 新增任何文件
- **不修缺陷**：发现问题只记录，不改 `spring-ai-loom-agent/src` 或任何 `src/main` 代码
- **不碰 `~/.loom`**：数据库用 `./target/test-ds`，用户树用 `./target/test-users`，二者相对 `spring-ai-loom-agent-test` 模块
- **不覆盖用户未提交改动**：`loom-file-core/.../PathSecurityUtils.java` 处于 modified 状态，巡检全程只读
- **默认账号**：`wb04307201` / `123456`（V1.0 种子，ADMIN 类型）
- **真实 LLM**：`MINIMAX_API_KEY` 环境变量已设置，provider = MiniMax-M3（OpenAI 兼容端点 `https://api.minimaxi.com/v1`）
- **HTTP 工具白名单**：test app 配了 `allowed-domains: [jsonplaceholder.typicode.com]`，白名单外域名一律被拒（fail-closed 设计），测试时不要误判为缺陷
- **视觉回归基线**：`spring-ai-loom-agent-test/src/test/resources/browser-baselines/` 存在但无 `@Test`（缺陷 D3），不要依赖它

## Review Focus

spec 隐含但没有任务直接覆盖、最可能咬人的五类输入：

1. **非 admin 用户访问 `/admin/**` 页面** —— `AuthenticationFilter.adminPathPatterns` 默认 `["/spring/ai/loom/admin/**"]`，但 8 个 admin HTML 页面在 `/spring/ai/loom/admin/*.html`，通配符是否覆盖 `.html` 后缀需实测。期望：非 admin 被拒或重定向，admin 正常渲染
2. **普通用户的空态** —— 新建用户 0 会话 / 0 技能 / 0 知识库时，console.html、user.html、market-skills.html 是否渲染友好空态而非报错或 `undefined`
3. **知识库上传后立即检索** —— fake `EmbeddingModel.call()` 抛 `UnsupportedOperationException`（缺陷 D2）。期望：要么真检索成功，要么明确 503 业务错误，**不接受** 500
4. **LLM 不调用工具直接回答** —— 用户问法模糊时模型可能跳过工具。巡检工具维度时必须区分"工具不可用"与"模型没选"，否则误报
5. **中文文件名 / 路径 / 内容往返** —— Windows GBK 编码坑（CLAUDE.md 记载 MCP stdio 已有先例）。期望：中文文件名上传、预览、下载、目录树展示全链路不乱码

---

## 任务清单概览

| # | 任务 | 维度 | 波次 |
|---|---|---|---|
| 0 | 环境预检与启动 | A | Wave 1 |
| 1 | 数据底座核对 | A | Wave 1 |
| 2 | 认证与会话 | B | Wave 1 |
| 3 | 聊天主干 | C | Wave 1 |
| 4 | 工具层（真实 LLM 驱动） | D | Wave 2 |
| 5 | 知识空间（RAG 高发区） | E | Wave 2 |
| 6 | 技能市场 | G | Wave 2 |
| 7 | 知识库市场 | H | Wave 2 |
| 8 | admin 控制台 + RBAC | I | Wave 2 |
| 9 | 文件管理模态框 | F | Wave 3 |
| 10 | MCP | J | Wave 3 |
| 11 | 多租户隔离 | K | Wave 3 |
| 12 | 降级与门控 | L | Wave 3 |
| 13 | 报告汇总 | 全部 | 收尾 |

任务 0-3 = Wave 1（跑完中途汇报）；任务 4-8 = Wave 2；任务 9-12 = Wave 3；任务 13 收尾。

---

### Task 0: 环境预检与启动真实服务

**Files:**
- Create: `docs/e2e-audit-2026-10-06/execution-log.md`
- Create: `docs/e2e-audit-2026-10-06/screenshots/.gitkeep`

**Interfaces:**
- Consumes: 无
- Produces: 运行中的服务 `http://localhost:8080`，登录 cookie 上下文，`execution-log.md` 骨架

- [ ] **Step 1: 记录环境基线**

创建 `docs/e2e-audit-2026-10-06/execution-log.md`，写入实测值：

```markdown
# E2E 巡检执行日志

## 环境基线（2026-10-06 实测）

| 项 | 值 | 命令 |
|---|---|---|
| Java | <实测输出> | `java -version` |
| Maven | <实测输出> | `mvn -version` |
| MINIMAX_API_KEY | <前 8 位，掩码> | `echo $MINIMAX_API_KEY` |
| Docker | 28.3.2（本次不用） | `docker --version` |
| Node | <实测输出> | `node --version` |

## 启动序列

见下方"实际执行记录"。
```

把 `<实测输出>` 占位替换为真实命令输出。**不要留任何尖括号占位符**。

- [ ] **Step 2: 装库模块进 m2**

```bash
cd /c/developer/IdeaProjects/spring-ai-loom-agent
mvn install -pl spring-ai-loom-agent -Dgpg.skip=true -q
```

预期：BUILD SUCCESS。耗时可能 2-5 分钟（需下载依赖）。失败则停止——后续任务全部依赖此步。

- [ ] **Step 3: 清隔离库与用户树**

```bash
rm -rf spring-ai-loom-agent-test/target/test-ds
rm -rf spring-ai-loom-agent-test/target/test-users
ls spring-ai-loom-agent-test/target/ 2>/dev/null | grep -E "test-ds|test-users" || echo "OK: 两个目录都不存在"
```

预期：输出 `OK: 两个目录都不存在`。**确认 `~/.loom` 未被创建或修改**（`ls ~/.loom/` 看时间戳）。

- [ ] **Step 4: 后台启动服务**

```bash
cd /c/developer/IdeaProjects/spring-ai-loom-agent
mvn spring-boot:run -pl spring-ai-loom-agent-test > /tmp/loom-e2e-server.log 2>&1
```

用 `run_in_background: true`。不要等待它完成——它是常驻服务。

- [ ] **Step 5: 轮询等待启动完成**

```bash
for i in $(seq 1 90); do
  code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/spring/ai/loom/login.html 2>/dev/null)
  if [ "$code" = "200" ]; then echo "READY after ${i}0s (HTTP $code)"; break; fi
  sleep 10
done
```

预期：最多 15 分钟内出现 `READY`。若超时，检查日志：

```bash
grep -iE "APPLICATION FAILED TO START|BeanCreationException|Caused by" /tmp/loom-e2e-server.log | head -30
```

- [ ] **Step 6: 验证 MCP server 是否已拉起**

```bash
grep -iE "mcp|Error processing inbound message|JsonParseException" /tmp/loom-e2e-server.log | tail -30
```

记录：4 个 MCP server（bing-search / mcp-npx-fetch / sequential-thinking / mcp-server-chart）中几个成功连接、几个超时。这是维度 J 的前置。

- [ ] **Step 7: 提交环境记录**

```bash
git add docs/e2e-audit-2026-10-06/
git commit -m "docs(e2e): 巡检环境基线与启动记录"
```

---

### Task 1: 数据底座核对（维度 A）

**Files:**
- Modify: `docs/e2e-audit-2026-10-06/execution-log.md`
- Create: `docs/e2e-audit-2026-10-06/screenshots/A-schema.png`

**Interfaces:**
- Consumes: Task 0 的运行中服务
- Produces: `flyway_schema_history` 实际版本序列结论，供 Task 13 填覆盖矩阵

- [ ] **Step 1: 核对 Flyway 迁移版本序列**

```bash
grep -iE "Migrating schema|Successfully applied|Schema .* is up to date" /tmp/loom-e2e-server.log
```

预期：4 条迁移全部出现——V1.0、V1.1、V1.2、V2.0（顺序执行）。任何 `Schema ... is up to date` 提前出现说明跳过了初始化，**记为 P0**。

- [ ] **Step 2: 核对建表数量与关键表**

```bash
grep -iE "Migrating schema \"public\" to version" /tmp/loom-e2e-server.log
```

随后用 H2 客户端直连核对（test 模块 DatasourceDir = `./target/test-ds`）：

```bash
java -cp ~/.m2/repository/com/h2database/h2/*/h2-*.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:./spring-ai-loom-agent-test/target/test-ds/db;AUTO_SERVER=TRUE" \
  -user sa -password 123456 \
  -sql "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

预期：4 行，`success` 全为 `t`。任一 `f` → **P0**（迁移失败，schema 不完整，后续所有维度不可信）。

- [ ] **Step 3: 核对关键种子数据**

```bash
java -cp ~/.m2/repository/com/h2database/h2/*/h2-*.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:./spring-ai-loom-agent-test/target/test-ds/db;AUTO_SERVER=TRUE" \
  -user sa -password 123456 \
  -sql "SELECT (SELECT COUNT(*) FROM user_info) AS users, (SELECT COUNT(*) FROM role) AS roles, (SELECT COUNT(*) FROM market_skill) AS skills, (SELECT COUNT(*) FROM loom_market_knowledge) AS kb, (SELECT COUNT(*) FROM mcp_server) AS mcps, (SELECT COUNT(*) FROM loom_role_knowledge) AS role_kb;"
```

预期基线（来自 V1.0 + V1.1 + V1.2 种子）：

| 列 | 预期值 | 来源 |
|---|---|---|
| users | 1 | V1.0 默认 admin |
| roles | 1 | V1.0 默认 base 角色 |
| skills | 3 | V1.0 两条 + V2.0 第三条「交叉评审」 |
| kb | 0 | 知识库市场无默认种子 |
| mcps | 4 | V1.1 业务 MCP |
| role_kb | 0 | 无默认知识库授权 |

**skills ≠ 3 → P1**（第三条官方技能种子缺失，V2.0 的幂等 UPDATE 有条件失效）。其余偏差 → P1，逐一记下实际值。

- [ ] **Step 4: 核对第 3 条官方技能的 base 角色授权**

```bash
java -cp ~/.m2/repository/com/h2database/h2/*/h2-*.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:./spring-ai-loom-agent-test/target/test-ds/db;AUTO_SERVER=TRUE" \
  -user sa -password 123456 \
  -sql "SELECT r.role_code, s.name FROM role_skill r JOIN market_skill s ON r.skill_id = s.id WHERE r.role_code = 'base';"
```

预期：3 行，含「交叉评审」。若只有 2 行 → **P1**（V2.0 的 role_skill 授权种子未生效）。这条直接决定普通用户登录后能否看到 3 条官方技能。

- [ ] **Step 5: 截图留档**

```bash
mkdir -p docs/e2e-audit-2026-10-06/screenshots
```

用 Chrome DevTools 打开 `http://localhost:8080/spring/ai/loom/login.html`，截图存为 `screenshots/A-schema.png`。记入 execution-log：

```markdown
### Task 1 · 维度 A 结果
- Flyway 版本序列：<实测>
- flyway_schema_history：<实测>
- 种子数据：users=__ roles=__ skills=__ kb=__ mcps=__ role_kb=__
- 第 3 条官方技能 base 授权：__ 行
- 结论：__（通过 / 缺陷 D__）
```

**把 `<实测>` / `__` 全部替换为真实值。**

---

### Task 2: 认证与会话（维度 B）

**Files:**
- Modify: `docs/e2e-audit-2026-10-06/execution-log.md`
- Create: `docs/e2e-audit-2026-10-06/screenshots/B-auth-*.png`

**Interfaces:**
- Consumes: Task 0 的运行中服务 + Task 1 的结论
- Produces: 已认证的浏览器 context，供 Task 3+ 复用；`B-*` 截图

- [ ] **Step 1: 未登录访问受保护页面**

```bash
curl -s -o /dev/null -w "index.html %{http_code} redirect=%{redirect_url}\n" \
  http://localhost:8080/spring/ai/loom/index.html
curl -s -o /dev/null -w "capabilities %{http_code}\n" \
  http://localhost:8080/spring/ai/loom/api/capabilities
curl -s -o /dev/null -w "roles %{http_code}\n" \
  http://localhost:8080/spring/ai/loom/admin/roles
```

预期：三者非 200（401/302/403 任一）。`api/capabilities` 返回 **200** 是缺陷——该端点已在 AuthenticationFilter 的 `pathPatterns` 内（`/spring/ai/loom/**`），未登录不应通过。记 **P1**。

- [ ] **Step 2: 未登录访问 8 个 admin 页面（Review Focus #1）**

浏览器打开以下 URL（无 cookie），逐页记录 HTTP 状态与最终落地页。完整清单（8 页）：

```
/spring/ai/loom/admin/console.html
/spring/ai/loom/admin/stats.html
/spring/ai/loom/admin/roles.html
/spring/ai/loom/admin/mcps.html
/spring/ai/loom/admin/market-skills.html
/spring/ai/loom/admin/knowledge-market.html
/spring/ai/loom/admin/user.html?username=wb04307201
/spring/ai/loom/admin/conversation.html?id=<任一真实会话id>&username=wb04307201
```

前 6 页用 curl 批量测 HTTP 状态：

```bash
for p in console stats roles mcps market-skills knowledge-market; do
  code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/spring/ai/loom/admin/$p.html")
  echo "$p.html -> $code"
done
```

预期：全部非 200（未登录被 `AuthenticationFilter` 拦下）。任一 200 → **P0**（未鉴权的 admin 页面）。存截图 `B-auth-unauth-admin.png`。

- [ ] **Step 3: 错误密码登录**

浏览器打开 `login.html`，填 `wb04307201` / 错误密码 `wrongpass123`，点登录。

预期：显示错误提示，**停留在 login.html**，URL 不跳转，`document.cookie` 不含会话 cookie。用 `evaluate_script` 读 `document.cookie` 确认（HttpOnly 的 cookie 在 JS 里不可见，本步只需确认没有可见的会话 cookie 泄漏）。

存截图 `B-auth-wrong-password.png`。

- [ ] **Step 4: 正确密码登录**

浏览器打开 `login.html`，填 `wb04307201` / `123456`，点登录按钮。

预期：跳转 `index.html`，`#textarea` 可见。用 curl 交叉验证 cookie 属性：

```bash
curl -s -i -X POST http://localhost:8080/spring/ai/loom/user/login \
  -H "Content-Type: application/json; charset=UTF-8" \
  -d '{"username":"wb04307201","password":"123456"}' | grep -iE "^(HTTP|set-cookie)"
```

预期：`Set-Cookie` 含 `HttpOnly` 且含 `Path=/`；`SameSite` 属性存在。缺 `HttpOnly` → **P1**（会话 cookie 可被 JS 读取 = XSS 可窃取会话）。存截图 `B-auth-login-success.png`。

- [ ] **Step 5: 改密码负向面（不改真密码）**

`#user-menu-trigger` → `#user-menu-change-password` → 填旧密码 `wrongold123` + 新密码 `TestE2E2026` → `#change-pwd-submit`。

预期：错误提示"原密码错误"，密码**未被修改**。

```bash
# 交叉验证：DB 里的 hash 未变
java -cp ~/.m2/repository/com/h2database/h2/*/h2-*.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:./spring-ai-loom-agent-test/target/test-ds/db;AUTO_SERVER=TRUE" -user sa -password 123456 \
  -sql "SELECT password FROM user_info WHERE username='wb04307201';"
```

预期：仍是 `$2a$10$gZ0zgDHCVQrZueMmiiZc4u5aP1SVnjA7sy623noNR4lCqMhr/Edzy`（V1.0 种子的值）。

存截图 `B-auth-change-password.png`。

**成功路径（改真密码）不测** —— 会污染后续 12 个任务共用的 admin 账号。记入 log：

```markdown
- 改密码成功路径：**未测**（避免污染后续任务账号）—— 只测了错误旧密码被拒
```

- [ ] **Step 6: 登出**

`#user-menu-trigger` → `#user-menu-logout`。

预期：跳回 `login.html`；带原 cookie 访问 `/spring/ai/loom/api/capabilities` 返回非 200。存截图 `B-auth-logout.png`。

- [ ] **Step 6: 新建非 admin 用户 `e2euser`（供 Task 8 / 11 使用）**

端点契约已核实：`POST /spring/ai/loom/admin/users`，body = `CreateUserRequest(username, nickname, password, type)`，成功返回 `true`，业务错误返回 400。

```bash
curl -s -X POST http://localhost:8080/spring/ai/loom/admin/users \
  -H "Content-Type: application/json; charset=UTF-8" \
  -H "Cookie: loom-agent-session=<从浏览器 DevTools 复制>" \
  -d '{"username":"e2euser","nickname":"E2E 测试用户","password":"TestE2E2026","type":"USER"}'
```

预期：`true`。

```bash
java -cp ~/.m2/repository/com/h2database/h2/*/h2-*.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:./spring-ai-loom-agent-test/target/test-ds/db;AUTO_SERVER=TRUE" -user sa -password 123456 \
  -sql "SELECT username, type FROM user_info ORDER BY username;"
```

预期 2 行：`e2euser`（USER）+ `wb04307201`（ADMIN）。

用 `e2euser` / `TestE2E2026` 走一遍 `login.html` 验证能登录。存截图 `B-auth-second-user.png`。

**cookie 值获取方式**：`loom-agent-session` 是 HttpOnly，`evaluate_script` 读不到。两条路——(a) 浏览器 DevTools → Application → Cookies → 复制值；(b) 直接用 curl 登录拿 `Set-Cookie`：

```bash
curl -s -i -X POST http://localhost:8080/spring/ai/loom/user/login \
  -H "Content-Type: application/json; charset=UTF-8" \
  -d '{"username":"wb04307201","password":"123456"}' | grep -i "set-cookie"
```

后续所有 curl 任务统一用 (b) 取 cookie，记入 log：

```markdown
- admin cookie 值：__（来源：curl 登录 Set-Cookie）
```

- [ ] **Step 7: 记录维度 B 结果**

```markdown
### Task 2 · 维度 B 结果
| 检查项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| 未登录 api/capabilities | 非 200 | __ | __ |
| 未登录 8 个 admin 页 | 全非 200 | __ | __ |
| 错误密码 | 停留 login + 提示 | __ | __ |
| 正确密码 | 跳 index.html | __ | __ |
| Set-Cookie HttpOnly | 有 | __ | __ |
| 改密码错误旧密码被拒 | 提示 | __ | __ |
| 改密码成功路径 | — | 未测 | 避免污染账号 |
| 登出后 API 失效 | 非 200 | __ | __ |
- 新建 e2euser（USER 类型，密码 TestE2E2026）：__ |
```

---

### Task 3: 聊天主干（维度 C）

**Files:**
- Modify: `docs/e2e-audit-2026-10-06/execution-log.md`
- Create: `docs/e2e-audit-2026-10-06/screenshots/C-chat-*.png`

**Interfaces:**
- Consumes: Task 0 服务、Task 2 的登录态
- Produces: 一个**有内容**的会话 id，供 Task 8 的 conversation.html、Task 4/5 的工具与知识测试复用

- [ ] **Step 1: 发送第一条消息，观察 SSE 流式**

浏览器（已登录）在 `#textarea` 输入 `你好，请用一句话介绍你自己。`，点 `#send-btn`。

**流开始信号**（`disableSend()`）：`#send-btn` 变 disabled、`#stop-btn` 可见。用 `wait_for` 或轮询观察。

**流结束信号**（`enableSend()`）：`#send-btn` 恢复 enabled 且 `#stop-btn` 隐藏。

预期：30-180s 内完成（真实 LLM + thinking）。AI 气泡文本非空、无原始 `<script>`、console 无 error。存截图 `C-chat-first-message.png`。

**若流未开始（send-btn 一直 enabled）** → **P0**（聊天链路断裂）。检查后端日志：

```bash
grep -iE "Error|Exception|timeout" /tmp/loom-e2e-server.log | tail -20
```

- [ ] **Step 2: 展开 thinking 面板**

点 AI 气泡旁的思考折叠区（memory 记载默认折叠）。

预期：显示 MiniMax 返回的 reasoning 文本。存截图 `C-chat-thinking.png`。若 thinking 面板不出现但 yml 配了 `thinking.type=adaptive`，记 **P2**。

- [ ] **Step 3: 多轮记忆**

接着发 `我刚才说了什么？`

预期：LLM 能引用第一轮内容。存截图 `C-chat-memory.png`。答不上来 → **P1**（ChatMemory 未生效）。

- [ ] **Step 4: stop 中断**

发一个长问题（如 `请详细写一篇 2000 字的技术文章`），等 AI 开始输出后立即点 `#stop-btn`。

预期：流停止，气泡保留已输出内容，`#send-btn` 恢复可用。存截图 `C-chat-stop.png`。stop 无效 → **P1**。

- [ ] **Step 5: 取会话 id 供后续任务复用**

```bash
curl -s http://localhost:8080/spring/ai/loom/conversation \
  -H "Cookie: loom-agent-session=<admin cookie>"
```

从返回 JSON 里取第一个 `id`，记入 execution-log：

```markdown
- 可复用会话 id：__（后续 Task 8 的 conversation.html 用它）
```

- [ ] **Step 6: 会话增删改**

点 `#sidebar-toggle` 展开侧栏，观察会话列表。新建一个会话、发消息、重命名、删除。

预期：四种操作 UI 都有反映。存截图 `C-chat-conversation-crud.png`。任一操作无响应或报错 → **P1**。

- [ ] **Step 7: 滚动按钮**

在一个消息很多的会话里滚到中部。

预期：`#back-to-bottom-btn` 出现；滚到底部消失（isAtBottom 守卫，CLAUDE.md 记载 2026-09-21 修复）。存截图 `C-chat-scroll-btn.png`。始终显示或始终不显示 → **P2**。

- [ ] **Step 8: markdown 消毒**

发一条含 XSS payload 的消息：`测试 <script>alert(1)</script> 和 <img src=x onerror=alert(2)> 与 [链接](javascript:alert(3))`

预期：`markdown-renderer.js` allowlist 过滤 —— 不弹 alert，页面不白屏，气泡显示为纯文本。存截图 `C-chat-xss.png`。

console 报错"Blocked script execution"**不算缺陷**（是消毒生效的正常表现）。

- [ ] **Step 9: token 用量弹窗**

`#user-menu-trigger` → `#user-menu-usage`。

预期：`#usage-total` / `#usage-prompt` / `#usage-completion` / `#usage-calls` 有真实数值（`stream-usage: true` 已配）。存截图 `C-chat-token-usage.png`。全为 0 或 `--` → **P1**（token 落库链路断）。

- [ ] **Step 10: 记录维度 C 结果**

```markdown
### Task 3 · 维度 C 结果
| 检查项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| SSE 流式首条消息 | 流起流止信号完整 | __ | __ |
| thinking 面板 | 可展开有内容 | __ | __ |
| 多轮记忆 | 能引用前轮 | __ | __ |
| stop 中断 | 停止且保留已输出 | __ | __ |
| 会话 CRUD | 增删改名全通 | __ | __ |
| 滚动按钮 | 守卫正确 | __ | __ |
| markdown 消毒 | payload 不执行 | __ | __ |
| token 用量 | 有真实数值 | __ | __ |
- 可复用会话 id：__ |
```

---

### **Wave 1 中途汇报**

Task 0-3 完成后**停止**，向用户汇报：

```markdown
## Wave 1 汇报（维度 A / B / C）

**底座可信度**：__（Flyway 4 版本全通 / 种子数据符合预期 / 有偏差）
**认证会话**：__ 缺陷
**聊天主干**：__ 缺陷
**存量缺陷确认**：
- D1（LLM 冒烟恒跳过）：__（确认 / 需修正）
- D3（视觉回归空跑）：__（确认 / 需修正）

是否继续 Wave 2（D 工具层 / E 知识空间 / G 技能市场 / H 知识库市场 / I 控制台）？
```

等用户确认后再继续。

---

### Task 4: 工具层（维度 D，真实 LLM 驱动）

**Files:**
- Modify: `docs/e2e-audit-2026-10-06/execution-log.md`
- Create: `docs/e2e-audit-2026-10-06/screenshots/D-tool-*.png`

**Interfaces:**
- Consumes: Task 0 服务、Task 3 的登录态与会话 id
- Produces: 每个工具的"实际是否被 LLM 调用"结论 + `D-*` 截图

**⚠️ Review Focus #4 纪律**：LLM 可能跳过工具直接回答。每个工具必须区分三种结果：
- **调用成功**（截图中可见工具调用痕迹）
- **模型未调用**（LLM 直接回答 —— **不是缺陷**，换个更明确的问法重试一次）
- **真缺陷**（模型试图调用但报错 / 工具不在 callback 列表）

- [ ] **Step 1: RBAC 前置 —— 确认 admin 授了哪些工具**

admin 账号有 `base` 角色。查当前授权：

```bash
curl -s http://localhost:8080/spring/ai/loom/admin/capabilities -H "Cookie: loom-agent-session=<cookie>"
```

记录哪些 group 在角色授权内。`git` / `maven` / `compile` / `render` / `http` 是 RBAC 工具，base 角色**默认未授权** —— 若要测这些，需先在 roles.html 给 base 角色授权（Task 8 会做，但这里需要）。**先按当前授权测 universal 工具（time / file / skill / knowledge / subtask / schedule / askUser），RBAC 工具留到 Task 8 授权后再测。**

- [ ] **Step 2: time 工具**

发 `现在几点了？用时间工具查一下当前时间。`

预期：AI 回复含具体时间，且聊天流中有工具调用痕迹。存截图 `D-tool-time.png`。

- [ ] **Step 3: file 工具（真实文件读写）**

先在用户树放一个待操作文件：

```bash
mkdir -p spring-ai-loom-agent-test/target/test-users/wb04307201/file/e2e/
echo "E2E 测试内容 2026-10-06" > spring-ai-loom-agent-test/target/test-users/wb04307201/file/e2e/sample.txt
```

发 `读取 e2e/sample.txt 这个文件，告诉我里面的内容。`

预期：LLM 调 `readTextFile`，回复含"（本轮执行安全边界内）"之类的原文。存截图 `D-tool-file-read.png`。

**若沙箱拒绝** → 检查 `PathSecurityUtils.java`（用户当前有未提交改动！），记 **P1** 并注明可能是用户 WIP 导致。

- [ ] **Step 4: skill 工具（createOrUpdateSkill）**

发 `帮我创建一个名为 e2e-test-skill 的技能，内容是"这是 E2E 测试技能"。`

预期：创建成功（DB `user_skill` 新增一行）：

```bash
java -cp ~/.m2/repository/com/h2database/h2/*/h2-*.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:./spring-ai-loom-agent-test/target/test-ds/db;AUTO_SERVER=TRUE" -user sa -password 123456 \
  -sql "SELECT name, description FROM user_skill WHERE name = 'e2e-test-skill';"
```

存截图 `D-tool-skill-create.png`。未创建 → **P1**。

- [ ] **Step 5: knowledge 工具 + RAG 检索（缺陷 D2 高发区）**

先建库并上传：

```bash
curl -s -X PUT http://localhost:8080/spring/ai/loom/knowledge \
  -H "Content-Type: application/json; charset=UTF-8" \
  -H "Cookie: loom-agent-session=<cookie>" \
  -d '{"name":"e2e-knowledge","description":"E2E 测试知识库"}'

java -cp ~/.m2/repository/com/h2database/h2/*/h2-*.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:./spring-ai-loom-agent-test/target/test-ds/db;AUTO_SERVER=TRUE" -user sa -password 123456 \
  -sql "SELECT id FROM knowledge WHERE name='e2e-knowledge';"
```

取 id 后上传文件：

```bash
echo "LoomAgent 是一个 Spring Boot AI Agent 库。它的知识空间支持向量检索。" > /tmp/e2e-doc.txt
curl -s -X POST "http://localhost:8080/spring/ai/loom/knowledge/<id>/upload" \
  -H "Cookie: loom-agent-session=<cookie>" \
  -F "file=@/tmp/e2e-doc.txt"
```

预期：`200` + 返回 fileId。**500 → P0**（即缺陷 D2 确认：fake EmbeddingModel 的 `call()` 抛异常导致上传链路断）。

然后在聊天里问 `LoomAgent 是什么？`

预期：LLM 调 `searchKnowledge` 检索并引用。存截图 `D-tool-knowledge.png`。

- [ ] **Step 6: HTTP 工具（真实外网）**

test app 白名单只有 `jsonplaceholder.typicode.com`。

先在聊天面板点「🔧 工具」按钮，勾选 `tool_http`（若未勾选）。发 `调用 http 工具请求 https://jsonplaceholder.typicode.com/posts/1，把返回的 JSON 告诉我。`

预期：AI 回复含 post 的 `title` / `body` 字段。存截图 `D-tool-http.png`。

**白名单外域名被拒 = 设计使然，不是缺陷**（fail-closed SSRF 防护）。可补测负向面：`请求 https://example.com` 预期被拒，记"符合设计"。

- [ ] **Step 6b: git 工具（本地操作，不 push）**

**前置**：先在控制台给 base 角色授 `tool_git`。若 Task 8 尚未做，先用 H2 直插：

```bash
java -cp ~/.m2/repository/com/h2database/h2/*/h2-*.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:./spring-ai-loom-agent-test/target/test-ds/db;AUTO_SERVER=TRUE" -user sa -password 123456 \
  -sql "INSERT INTO role_tool (role_code, group_name) SELECT 'base', 'tool_git' WHERE NOT EXISTS (SELECT 1 FROM role_tool WHERE role_code='base' AND group_name='tool_git');"
```

发 `用 git 工具在 e2e/git-test 目录下执行 git init。`

预期：目录出现 `.git`。验证：

```bash
ls -la spring-ai-loom-agent-test/target/test-users/wb04307201/file/e2e/git-test/.git 2>/dev/null | head -3 || echo "P1: .git 未创建"
```

存截图 `D-tool-git.png`。**不做 git push。**

- [ ] **Step 6c: maven 工具（validate only）**

同样前置：授 `tool_maven`。发 `用 maven 工具对 e2e/git-test 目录执行 mvn validate。`

预期：LLM 调 `mavenExecute` / `mavenValidate`，返回成功或可解释的失败。存截图 `D-tool-maven.png`。

**不做 compile / package。**

- [ ] **Step 7: render 工具（真实 Chromium 渲染）**

授 `tool_render`。先准备 HTML：

```bash
mkdir -p spring-ai-loom-agent-test/target/test-users/wb04307201/file/e2e/
cat > spring-ai-loom-agent-test/target/test-users/wb04307201/file/e2e/proto.html \
  <<'HTML'
<!doctype html><html><head><meta charset="utf-8"><title>E2E 原型</title></head>
<body style="font-family:sans-serif;padding:40px;background:#f8fafc">
<h1 style="color:#4f46e5">E2E 渲染测试</h1><p>中文渲染检查 ✓</p></body></html>
HTML
```

发 `用 render 工具把 e2e/proto.html 渲染成 PNG。`

预期：AI 回复含预览链接与 markdown 图片片段；文件落到 `.../file/prototypes/`。验证：

```bash
ls -la spring-ai-loom-agent-test/target/test-users/wb04307201/file/prototypes/ 2>/dev/null || echo "P1: prototypes 目录不存在"
```

存截图 `D-tool-render.png`。**失败一律返回文本（D8 设计）**，故需看 AI 回复内容判断是真渲染失败还是 LLM 没调工具。

- [ ] **Step 8: subtask 工具**

发 `用 start_sub_task 起一个子任务，让它查询北京今天天气并总结成一句话。`

预期：`#subtask-panel-body` 出现子任务 chip，显示生命周期状态；完成后可点开 inline 详情面板看 prompt / ID / 状态。存截图 `D-tool-subtask.png`。

超时上限 300s（yml 配 300）。超过则记"未测 + 超时"。

- [ ] **Step 9: schedule 工具**

发 `用 createSchedule 创建一个每 10 分钟执行一次的定时任务，名字叫 e2e-sched，内容是记录一行日志。`

**注意**：`flex.schedule.limits.min-interval=10m`，所以 10 分钟是允许的最小间隔。

预期：创建成功（UI 面板或 API 可见）：

```bash
curl -s http://localhost:8080/spring/ai/loom/schedule/list -H "Cookie: loom-agent-session=<cookie>"
```

存截图 `D-tool-schedule.png`。测完取消该任务：

```bash
curl -s -X POST http://localhost:8080/spring/ai/loom/schedule/cancel \
  -H "Content-Type: application/json; charset=UTF-8" -H "Cookie: loom-agent-session=<cookie>" \
  -d '{"name":"loom-sched-wb04307201-<convId>-e2e-sched"}'
```

- [ ] **Step 10: askUser 工具（阻塞等待作答）**

发 `用 askUser 问我一个二选一的问题：要不要继续？`

预期：AI 回复中嵌入选择卡片（`#confirm-*` 系列的 askUser 卡片）。点击其中一个选项。

预期：答案以 tool_result 回同一条流，AI 继续。存截图 `D-tool-askuser.png`。超时上限 300s（`askuser.timeoutSeconds`）。

- [ ] **Step 11: 记录维度 D 结果**

```markdown
### Task 4 · 维度 D 结果
| 工具 | group | 调用状态 | 结论 |
|---|---|---|---|
| time | tool_time | __ | __ |
| file read | tool_file | __ | __ |
| skill create | tool_skill | __ | __ |
| knowledge 检索 | tool_knowledge | __ | __ |
| http | tool_http | __ | __ |
| git init | tool_git | __ | __ |
| maven validate | tool_maven | __ | __ |
| render | tool_render | __ | __ |
| subtask | tool_subtask | __ | __ |
| schedule | tool_schedule | __ | __ |
| askUser | tool_askUser | __ | __ |

调用状态取值：调用成功 / 模型未调用（重试后仍）/ 真缺陷
```

---

### Task 5: 知识空间 UI（维度 E）

**Files:**
- Modify: `docs/e2e-audit-2026-10-06/execution-log.md`
- Create: `docs/e2e-audit-2026-10-06/screenshots/E-kb-*.png`

**Interfaces:**
- Consumes: Task 4 Step 5 建的 `e2e-knowledge` 库与上传的文件
- Produces: 维度 E 结论 + `E-*` 截图

- [ ] **Step 1: 打开知识空间面板**

点 `index.html` 的 📚 知识空间按钮（`#ks-button`）。

预期：面板打开，侧栏 `#ks-sidebar` 显示 `e2e-knowledge`，`#ks-file-tbody` 显示刚上传的 `e2e-doc.txt`。存截图 `E-kb-list.png`。

面板打不开 → **P1**（知识空间 UI 断链）。

- [ ] **Step 2: 建新库**

点新建知识库，输入名称 `e2e-kb-ui`、描述，走保存。

预期：侧栏出现新条目。

```bash
java -cp ~/.m2/repository/com/h2database/h2/*/h2-*.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:./spring-ai-loom-agent-test/target/test-ds/db;AUTO_SERVER=TRUE" -user sa -password 123456 \
  -sql "SELECT id, name FROM knowledge ORDER BY id;"
```

预期 2 行（`e2e-knowledge` + `e2e-kb-ui`）。存截图 `E-kb-create.png`。

- [ ] **Step 3: 浏览器真实上传文件**

用 DevTools 的文件选择器上传一个中文名文件：

```bash
echo "中文内容测试：知识库检索" > /tmp/中文测试.txt
```

点击上传按钮 → `upload_file` 工具传 `/tmp/中文测试.txt`。

预期：文件出现在列表；**文件名中文不乱码**（Review Focus #5）。乱码 → **P1**。存截图 `E-kb-upload.png`。

- [ ] **Step 4: 详情面板**

点库名打开 `#ks-detail`，看 `#ks-detail-title` 与文件列表。

存截图 `E-kb-detail.png`。

- [ ] **Step 5: 删库（负向面）**

删除 `e2e-kb-ui`，确认。

预期：侧栏消失，DB 无该行。

```bash
java -cp ~/.m2/repository/com/h2database/h2/*/h2-*.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:./spring-ai-loom-agent-test/target/test-ds/db;AUTO_SERVER=TRUE" -user sa -password 123456 \
  -sql "SELECT COUNT(*) FROM knowledge WHERE name='e2e-kb-ui';"
```

预期 0。存截图 `E-kb-delete.png`。

- [ ] **Step 6: 对话中引用知识库**

点聊天面板「🔧 工具」→ 知识空间，勾选 `e2e-knowledge`，发 `e2e-knowledge 知识库里讲了什么？`

预期：AI 回复引用库内容。存截图 `E-kb-chat-reference.png`。

- [ ] **Step 6b: 文件管理模态框 + 画板（维度 F 部分，E 波次顺手做）**

点 `index.html` 的文件管理按钮，打开模态框。

预期：目录树显示 `.../users/wb04307201/file/` 下 Task 4 造的文件（`e2e/sample.txt` / `e2e/git-test/.git` / `e2e/proto.html` / `prototypes/*.png`）。

存截图 `F-file-modal.png`。不显示工具目录（`compile-workspaces/`）→ 符合设计。

点 ✎ 打开画板，用画笔画几笔，点确定。

预期：PNG 生成，作为附件出现在输入框。存截图 `F-canvas-board.png`。画板全程无坐标偏移（CLAUDE.md 记载禁用入场动画就是为这个）。

---

### Task 6: 技能市场（维度 G）

**Files:**
- Modify: `docs/e2e-audit-2026-10-06/execution-log.md`
- Create: `docs/e2e-audit-2026-10-06/screenshots/G-skill-*.png`

**Interfaces:**
- Consumes: Task 0 服务
- Produces: 一条走完审批流的 market_skill 行，供 Task 7 复用审批模式

- [ ] **Step 1: 浏览市场**

浏览器打开 `http://localhost:8080/spring/ai/loom/admin/market-skills.html`（已登录 admin）。

预期：`#skill-table-container` 显示 V1.0 + V2.0 的 3 条官方技能。存截图 `G-skill-browse.png`。

**若显示 2 条 → P1**（V2.0 第三条种子缺失，与 Task 1 Step 4 结论一致）。

- [ ] **Step 1b: 用户视角浏览（非 admin）**

用 `e2euser` 登录，打开 index.html 的技能面板。

预期：看到 3 条官方技能（base 角色授权了 3 条）。**只看到 2 条 → P1**（V2.0 的 role_skill 种子未生效）。存截图 `G-skill-user-view.png`。

- [ ] **Step 2: 提交新技能（用户视角）**

`e2euser` 登录 → 技能面板 → 提交新技能，名称 `e2e-submitted-skill`、描述、content。

预期：状态变 PENDING。验证：

```bash
java -cp ~/.m2/repository/com/h2database/h2/*/h2-*.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:./spring-ai-loom-agent-test/target/test-ds/db;AUTO_SERVER=TRUE" -user sa -password 123456 \
  -sql "SELECT name, status FROM market_skill WHERE name = 'e2e-submitted-skill';"
```

预期 `PENDING`。存截图 `G-skill-submit.png`。

- [ ] **Step 3: admin 审批通过**

切回 admin → market-skills.html → 找到 `e2e-submitted-skill` → 点通过。

预期：状态变 APPROVED。存截图 `G-skill-approve.png`。

- [ ] **Step 4: 用户 pull 到我的技能**

`e2euser` → 技能面板 → 找到 `e2e-submitted-skill` → 点 pull。

预期：进入我的技能。验证 `user_skill` 新增一行。存截图 `G-skill-pull.png`。

- [ ] **Step 5: 驳回 + 评论（审批流负向面）**

`e2euser` 再提交一个 `e2e-reject-skill`。admin 驳回，填评论 `测试驳回评论`。

预期：(a) 评论必填校验生效（不填评论点驳回 → 被拦）；(b) 驳回后状态 REJECTED；(c) **归档表 `market_skill_archive` 保留旧行**（id 保留）。存截图 `G-skill-reject.png`。

- [ ] **Step 6: 5 星评价**

对已 pull 的技能打 5 星 + 写评价。

预期：写入成功。**第二次编辑同一评价被拒**（`edit_count` 闸门，`ISkillStorage` 记载 5 星评价有 `edit_count` gate）。存截图 `G-skill-review.png`。

- [ ] **Step 7: 记录维度 G 结果**

```markdown
### Task 6 · 维度 G 结果
| 检查项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| 市场显示官方技能数 | 3 | __ | __ |
| 用户视角可见官方技能数 | 3 | __ | __ |
| 提交 → PENDING | PENDING | __ | __ |
| admin 审批通过 | APPROVED | __ | __ |
| 用户 pull | user_skill +1 | __ | __ |
| 驳回评论必填 | 拦截 | __ | __ |
| 驳回后归档 | archive +1 | __ | __ |
| 5 星评价 | 写入成功 | __ | __ |
| 评价二次编辑 | 被拒（edit_count） | __ | __ |
```

---

### Task 7: 知识库市场（维度 H）

**Files:**
- Modify: `docs/e2e-audit-2026-10-06/execution-log.md`
- Create: `docs/e2e-audit-2026-10-06/screenshots/H-kbmarket-*.png`

**Interfaces:**
- Consumes: Task 6 的审批模式
- Produces: 维度 H 结论

- [ ] **Step 1: 浏览知识库市场**

admin 登录 → `admin/knowledge-market.html`。

预期：`#knowledge-table-container` 非空（若 Task 4/5 提交的库已进市场）或友好空态。存截图 `H-kbmarket-browse.png`。

**Review Focus #2**：空态必须友好，不报错、不显示 `undefined`。

- [ ] **Step 2: 提交知识库到市场**

用 `e2euser` 或 admin 提交 `e2e-knowledge`（Task 5 建的）到市场，状态 PENDING。

```bash
curl -s -X POST "http://localhost:8080/spring/ai/loom/api/knowledge/{knowledgeId}/submit" \
  -H "Content-Type: application/json; charset=UTF-8" -H "Cookie: loom-agent-session=<cookie>" -d '{}'
```

- [ ] **Step 3: 审批 + 标签 + 公告**

admin 审批通过 + 打标签 + 写行公告。

预期：三个操作各自生效（`PUT .../{id}/tags`、`PUT .../{id}/announcement`）。

- [ ] **Step 4: pull + 标签筛选**

用户 pull 该知识库到我的知识空间；再在市场页用标签筛选。

预期：筛选结果正确。存截图 `H-kbmarket-pull-filter.png`。

- [ ] **Step 5: 记录维度 H 结果**

```markdown
### Task 7 · 维度 H 结果
| 检查项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| 市场列表 | 非空或友好空态 | __ | __ |
| 提交 → PENDING | PENDING | __ | __ |
| 审批通过 | APPROVED | __ | __ |
| 标签设置 | 生效 | __ | __ |
| 行公告 | 生效 | __ | __ |
| 用户 pull | 进入我的 | __ | __ |
| 标签筛选 | 结果正确 | __ | __ |
```

---

### Task 8: admin 控制台 + RBAC（维度 I）

**Files:**
- Modify: `docs/e2e-audit-2026-10-06/execution-log.md`
- Create: `docs/e2e-audit-2026-10-06/screenshots/I-admin-*.png`

**Interfaces:**
- Consumes: Task 2 的 admin 登录态、Task 6/7 的市场数据
- Produces: 维度 I 结论；确认 RBAC 工具授权是否生效

- [ ] **Step 1: 用户管理**

admin → `admin/console.html`。`#user-table-container` 应显示 `wb04307201`（ADMIN）+ `e2euser`（USER）。

存截图 `I-admin-users.png`。`e2euser` 不在列表 → **P1**。

- [ ] **Step 1b: 给 e2euser 分配 base 角色**

点分配角色弹窗 → 选 `base` → 确认。端点契约已核实：`PUT /spring/ai/loom/admin/users/{username}/roles`，body = `{"roleCodes":["base"]}`。

UI 操作后交叉验证：

```bash
java -cp ~/.m2/repository/com/h2database/h2/*/h2-*.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:./spring-ai-loom-agent-test/target/test-ds/db;AUTO_SERVER=TRUE" -user sa -password 123456 \
  -sql "SELECT username, role_code FROM user_role ORDER BY username;"
```

预期 2 行：`(e2euser, base)` 与 `(wb04307201, base)`。

**UI 没弹窗或报错 → P1**（CLAUDE.md 记载 2026-09-08 起 admin 也可被分配角色，console.js 的 ADMIN early-return 已删）。存截图 `I-admin-assign-role.png`。

- [ ] **Step 2: 角色管理 + 工具授权（补测 RBAC 工具）**

admin → `admin/roles.html`。`#role-table-container` 显示 `base`。点开工具授权区，从 `/admin/capabilities` 动态拉取的列表里勾选 `tool_git` / `tool_maven` / `tool_render` / `tool_http`。

**关键**：列表里**不应出现** universal 工具（schedule / subtask / knowledge / time / skill / file / askUser）—— CLAUDE.md 记载 admin 授权页不展示 universal 工具入口。

```bash
java -cp ~/.m2/repository/com/h2database/h2/*/h2-*.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:./spring-ai-loom-agent-test/target/test-ds/db;AUTO_SERVER=TRUE" -user sa -password 123456 \
  -sql "SELECT group_name FROM role_tool WHERE role_code='base' ORDER BY group_name;"
```

存截图 `I-admin-roles-tools.png`。universal 工具出现在授权列表 → **P1**（V1.0 末尾的 DELETE 段失效）。

- [ ] **Step 2b: RBAC 负向面 —— 撤权后工具不可见**

把刚勾的 `tool_git` 取消勾选，保存。

`e2euser` 重新登录 → 发 `用 git 工具执行 git status。`

预期：LLM 无 `tool_git` 回调，回答"没有 git 工具"或直接文本回答（**不是缺陷**，是 RBAC 工作正常）。存截图 `I-admin-rbac-negative.png`。

再重新勾选 `tool_git` 供后续使用。

- [ ] **Step 3: 角色删除级联（DEFECT-Q3-1）**

新建一个测试角色 `e2e-role`，给它授 `tool_git` + 1 个 skill + 1 个知识库授权，然后删它。

```bash
java -cp ~/.m2/repository/com/h2database/h2/*/h2-*.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:./spring-ai-loom-agent-test/target/test-ds/db;AUTO_SERVER=TRUE" -user sa -password 123456 \
  -sql "SELECT (SELECT COUNT(*) FROM role_tool WHERE role_code='e2e-role') AS tool, (SELECT COUNT(*) FROM role_skill WHERE role_code='e2e-role') AS skill, (SELECT COUNT(*) FROM loom_role_knowledge WHERE role_code='e2e-role') AS kb, (SELECT COUNT(*) FROM role_mcp WHERE role_code='e2e-role') AS mcp, (SELECT COUNT(*) FROM user_role WHERE role_code='e2e-role') AS usr;"
```

**删前**计数非 0（至少 tool/skill 非 0），**删后**全部 0。任何残留 → **P0**（dangling 行会在同 code 重建时"复活"陈旧授权）。存截图 `I-admin-role-delete.png`。

- [ ] **Step 4: MCP 描述维护**

admin → `admin/mcps.html`。`#mcp-table-container` 显示 4 个 MCP server。

点开一个，改描述，保存。

预期：生效（`PUT /admin/mcps/{name}`）。存截图 `I-admin-mcps.png`。

- [ ] **Step 5: stats 图表**

admin → `admin/stats.html`。`#bar-chart` 渲染。

预期：月度 Token 用量柱图有数据（Task 3 产生的真实用量）。空图 → **P1**。存截图 `I-admin-stats.png`。

- [ ] **Step 6: 用户详情页（纯只读审计视图）**

admin → `admin/user.html?username=wb04307201`。`#conv-list-container` 显示会话列表。

预期：会话列表可搜索 / 排序 / 筛选；会话行可点跳 conversation.html。

**负向面**：`#role-card` 与 `#ask-logs-card` **不应存在**（2026-09-20 删除了这两块，`KnowledgeMarketAndUserBrowserIT` 是回归锁）。存截图 `I-admin-user-detail.png`。

- [ ] **Step 7: 会话流水视图**

admin → `admin/conversation.html?id=<Task 3 的会话 id>&username=wb04307201`。`#flow-container` 渲染。

预期：显示每轮消息 + thinking（`ChatReasoningHistory` 时间戳配对逻辑）。存截图 `I-admin-conversation-flow.png`。

- [ ] **Step 8: 记录维度 I 结果**

```markdown
### Task 8 · 维度 I 结果
| 检查项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| 用户列表含 e2euser | 是 | __ | __ |
| 分配角色 | user_role +1 | __ | __ |
| 工具授权列表不含 universal | 不含 | __ | __ |
| RBAC 撤权后工具不可见 | 不可见 | __ | __ |
| 角色删除 5 表级联 | 全 0 | __ | __ |
| MCP 描述维护 | 生效 | __ | __ |
| stats 柱图 | 有数据 | __ | __ |
| user.html 只读 | 无 role-card/ask-logs | __ | __ |
| conversation 流水 | 含 thinking | __ | __ |
```

---

### Task 12: 降级与门控（维度 L）—— 排在最后

**Files:**
- Modify: `docs/e2e-audit-2026-10-06/execution-log.md`
- Create: `docs/e2e-audit-2026-10-06/screenshots/L-feature-gate.png`

**Interfaces:**
- Consumes: Task 0-11 全部结论
- Produces: 维度 L 结论（需一次额外启动周期）

**⚠️ 本任务需要停服 → 改属性 → 重启。排在最后以免打断其他维度。**

- [ ] **Step 1: 停服**

TaskStop 掉 Task 0 Step 4 启动的后台服务。

- [ ] **Step 2: 以 RAG 关闭态重启**

```bash
cd /c/developer/IdeaProjects/spring-ai-loom-agent
mvn spring-boot:run -pl spring-ai-loom-agent-test \
  -Dspring-boot.run.arguments=--spring.ai.loom.agent.rag.enabled=false \
  > /tmp/loom-e2e-server-norag.log 2>&1 &
```

`spring-boot.run.arguments` 把 `--spring.ai.loom.agent.rag.enabled=false` 作为应用启动参数传入，**不改任何文件**。

预期：启动成功。核对 features 端点：

```bash
curl -s http://localhost:8080/spring/ai/loom/knowledge/checkKnowledgeUpload
```

预期 `false`（无 VectorStore）。`true` → **P1**（门控失效）。

- [ ] **Step 3: 前端门控验证**

登录 → index.html。

预期：(a) 📚 知识空间按钮**消失**；(b) `+` 附件按钮与 ✎ 画板按钮**仍可见**（CLAUDE.md 记载两者恒可见，不与 RAG 联动）。

存截图 `L-feature-gate.png`。任一违反 → **P1**。

- [ ] **Step 4: 503 降级响应**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  "http://localhost:8080/spring/ai/loom/knowledge/<id>/upload" \
  -H "Cookie: loom-agent-session=<cookie>" -F "file=@/tmp/e2e-doc.txt"
```

预期 **503**（不是 500）。500 → **P1**。

- [ ] **Step 5: 恢复默认重启**

停服，重跑 Task 0 Step 4 的命令恢复正常态。

---

### Task 9: 文件管理模态框（维度 F）

**Files:**
- Modify: `docs/e2e-audit-2026-10-06/execution-log.md`
- Create: `docs/e2e-audit-2026-10-06/screenshots/F-file-*.png`

**Interfaces:**
- Consumes: Task 4 造的文件、Task 5 顺手做的模态框初查
- Produces: 维度 F 结论

- [ ] **Step 1: 目录树渲染**

登录 → index.html → 点文件管理按钮。

预期：`#file-list` 或目录树显示 `.../users/wb04307201/file/` 下的文件树，可展开子目录。存截图 `F-file-tree.png`。

- [ ] **Step 2: 展开子目录**

点开 `e2e/` 节点。

预期：显示 `sample.txt` / `proto.html` / `git-test/` / `中文测试.txt`（如已上传）。存截图 `F-file-tree-expand.png`。

- [ ] **Step 3: 预览**

点某个 txt 文件的预览按钮。

预期：内容正确显示，中文不乱码（Review Focus #5）。存截图 `F-file-preview.png`。

- [ ] **Step 4: 下载**

点下载按钮。

预期：文件下载成功，内容正确。存截图 `F-file-download.png`。

- [ ] **Step 5: 系统目录不外泄**

预期：目录树**不显示** `compile-workspaces/`、`datasource/`、`http/`。任何一个显示 → **P1**（信息泄漏）。

---

### Task 10: MCP（维度 J）

**Files:**
- Modify: `docs/e2e-audit-2026-10-06/execution-log.md`
- Create: `docs/e2e-audit-2026-10-06/screenshots/J-mcp-*.png`

**Interfaces:**
- Consumes: Task 0 Step 6 的 MCP 连接结论
- Produces: 维度 J 结论

- [ ] **Step 1: 四个 server 连接状态**

admin → `admin/mcps.html`，或直接：

```bash
curl -s http://localhost:8080/spring/ai/loom/admin/mcps -H "Cookie: loom-agent-session=<cookie>"
```

预期 4 个 server 全部在线。记录实际在线数。存截图 `J-mcp-status.png`。

**已知风险**：`npx.cmd` 首次拉包慢/失败；Windows GBK 编码坑（CLAUDE.md 记载 MCP Java SDK 0.18.x `StdioClientTransport` 入站用平台默认字符集，中文 JDK 17 GBK 下工具响应含非 ASCII 会炸）。本次是 JDK 25（UTF-8 默认）→ 应免疫。验证：

```bash
grep -iE "Error processing inbound message|JsonParseException" /tmp/loom-e2e-server.log | head
```

有条目 → **P1**，记为 MCP 编码缺陷。

- [ ] **Step 2: 工具描述中文完整性**

admin → `admin/mcps.html` → 看工具描述。

预期：34 条 mcp_tool 中文描述完整（V1.1 种子），不乱码不截断。存截图 `J-mcp-descriptions.png`。

- [ ] **Step 3: MCP 工具实际调用（安全面）**

聊天面板「🔧 工具」→ MCP 区 → 勾选 `sequential-thinking` → 发 `用 sequential-thinking 工具帮我理清"如何在三天内上线一个 Spring Boot 应用"这个问题的解决步骤。`

预期：LLM 调用 MCP 工具，回复含结构化分析。存截图 `J-mcp-invoke.png`。

**MCP 写面不测**（安全边界）。

---

### Task 11: 多租户隔离（维度 K）

**Files:**
- Modify: `docs/e2e-audit-2026-10-06/execution-log.md`
- Create: `docs/e2e-audit-2026-10-06/screenshots/K-tenant-*.png`

**Interfaces:**
- Consumes: Task 2 的两个账号
- Produces: 维度 K 结论

- [ ] **Step 1: 文件树隔离**

`wb04307201` 登录 → 文件管理模态框。记下文件列表。

`e2euser` 登录 → 文件管理模态框。

预期：`e2euser` 看到的是自己的 `.../users/e2euser/file/`（通常为空或只有自己的上传），**看不到** admin 的 `e2e/` 目录。存截图 `K-tenant-files.png`。

看到 admin 的文件 → **P0**（跨租户文件泄漏）。

- [ ] **Step 2: 会话隔离**

`e2euser` → 打开会话列表。

预期：看不到 admin 的会话。存截图 `K-tenant-conversations.png`。看到 → **P0**。

- [ ] **Step 3: 技能隔离**

`e2euser` → 技能面板。

预期：`e2e-test-skill`（Task 4 admin 建的）**不在** e2euser 的我的技能里；但官方技能（base 角色授权）在。存截图 `K-tenant-skills.png`。

- [ ] **Step 4: 直接 URL 越权尝试**

`e2euser` 登录态下直接 curl admin 端点：

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/spring/ai/loom/admin/users \
  -H "Cookie: loom-agent-session=<e2euser cookie>"
```

预期非 200（403 或 302）。200 → **P0**（越权）。

- [ ] **Step 5: 记录维度 K 结果**

```markdown
### Task 11 · 维度 K 结果
| 检查项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| 文件树隔离 | 互不可见 | __ | __ |
| 会话隔离 | 互不可见 | __ | __ |
| 技能隔离 | 互不可见（官方除外） | __ | __ |
| admin 端点越权 | 非 200 | __ | __ |
```

---

### Task 13: 报告汇总

**Files:**
- Create: `docs/e2e-audit-2026-10-06/defects.md`
- Create: `docs/e2e-audit-2026-10-06/coverage-matrix.md`
- Modify: `docs/e2e-audit-2026-10-06/execution-log.md`

**Interfaces:**
- Consumes: Task 0-12 全部 execution-log 结果
- Produces: 最终三份交付物

- [ ] **Step 1: 写覆盖矩阵**

`coverage-matrix.md`，12 行，每行：维度 / 覆盖项数 / 通过 / 失败 / 未测 / 缺陷编号。

```markdown
# E2E 巡检覆盖矩阵（2026-10-06）

| 维度 | 覆盖项 | 通过 | 失败 | 未测 | 缺陷 |
|---|---|---|---|---|---|
| A 启动与数据底座 | 5 | __ | __ | __ | __ |
| B 认证与会话 | 9 | __ | __ | __ | __ |
| C 聊天主干 | 8 | __ | __ | __ | __ |
| D 工具层 | 11 | __ | __ | __ | __ |
| E 知识空间 | 6 | __ | __ | __ | __ |
| F 文件管理 | 5 | __ | __ | __ | __ |
| G 技能市场 | 9 | __ | __ | __ | __ |
| H 知识库市场 | 7 | __ | __ | __ | __ |
| I 控制台 + RBAC | 9 | __ | __ | __ | __ |
| J MCP | 3 | __ | __ | __ | __ |
| K 多租户 | 4 | __ | __ | __ | __ |
| L 降级与门控 | 4 | __ | __ | __ | __ |
| **合计** | **80** | __ | __ | __ | __ |
```

- [ ] **Step 2: 写缺陷清单**

`defects.md`，按 P0 → P1 → P2 排序。每条：

```markdown
## P0-1 知识库上传 500 —— fake EmbeddingModel.call() 抛异常

**维度**：E 知识空间
**复现**：
1. 建知识库 e2e-knowledge
2. POST /spring/ai/loom/knowledge/{id}/upload 上传 txt
3. 观察 HTTP 状态

**期望**：200 + fileId
**实测**：500 Internal Server Error
**证据**：`screenshots/E-kb-upload.png`；日志片段：
```
<粘贴真实日志行>
```
**根因**：`TestAnthropicOptionsConfig`（src/main/java）的 fake EmbeddingModel，`call(EmbeddingRequest)` 直接 `throw new UnsupportedOperationException()`
**建议修复**：test app 配真 embedding provider（DashScope text-embedding-v4），或让 `call()` 走 `embed()` 委托
```

- [ ] **Step 3: 确认三个存量缺陷**

报告中显式确认 D1 / D2 / D3：

```markdown
## 存量缺陷确认

| 编号 | 描述 | 巡检结论 |
|---|---|---|
| D1 | ChatSmokeBrowserIT 等 4 处 env 守卫读 DASHSCOPE_PERSON_TOKEN_API_KEY，yml 现用 MINIMAX_API_KEY | __（确认 / 需修正） |
| D2 | fake EmbeddingModel 使 RAG 检索链路不可用 | __ |
| D3 | VisualBaselineBrowserIT 无 @Test，视觉回归空跑 | __ |
```

- [ ] **Step 4: 提交交付物**

```bash
git add docs/e2e-audit-2026-10-06/
git commit -m "docs(e2e): 全功能多维度 E2E 巡检报告 —— __ 个缺陷（__ P0 / __ P1 / __ P2）"
```

---

## 计划自检

### 1. Spec 覆盖度

| Spec 章节 | 对应任务 |
|---|---|
| §2 三个存量缺陷 | Task 1（D2 部分）、Task 13 Step 3（全部确认） |
| §3 执行环境 | Task 0 |
| §4 十二维度 | Task 1(A) / 2(B) / 3(C) / 4(D) / 5(E+F 部分) / 6(G) / 7(H) / 8(I) / 9(F) / 10(J) / 11(K) / 12(L) |
| §5 三波推进 | Wave 1 = 0-3，Wave 2 = 4-8，Wave 3 = 9-12 |
| §6 执行手段 | Task 0（启动）+ 各任务的浏览器步骤 |
| §7 缺陷分级 | Task 13 Step 2 |
| §8 交付物清单 | Task 13 |
| §9 风险 | Task 0 Step 6 / Task 4 Step 11 / Task 10 Step 1 |
| §10 验收标准 | Task 13 |

**G 维度** spec 列的 scenarios 覆盖：`浏览/提交/审批/评价/pull` = Task 6 Step 1-6 ✅

**F 维度** spec 列的 scenarios 覆盖：`目录树展开/预览/下载` = Task 9 Step 1-4 ✅（Step 5 是额外的系统目录泄漏检查）

**J 维度** spec 列的 scenarios 覆盖：`连接状态/中文描述乱码` = Task 10 Step 1-2 ✅（Step 3 是额外的实际调用）

**K 维度** spec 列的 scenarios 覆盖：`两用户文件/会话/技能互不可见` = Task 11 Step 1-3 ✅（Step 4 是额外的 URL 越权）

**D 维度** spec 列的 11 个工具覆盖：time / file / skill / git / maven / http / render / subtask / schedule / askUser / knowledge = Task 4 Step 2-10 ✅（Step 6b/6c 是 git/maven）

- [ ] **Step 5: 未测项清单（验收要求无空项）**

```markdown
## 未测项与原因

| 项 | 原因 |
|---|---|
| 改密码成功路径 | 避免污染后续任务账号（Task 2 Step 5） |
| git push | 安全边界明确排除（spec §1） |
| ICompileAndDeployTool 完整 docker 部署 | 安全边界明确排除（spec §1） |
| MCP 写面 | 安全边界明确排除（spec §1） |
| __（巡检中发现的其他跳过项） | __ |
```

- [ ] **Step 6: 未测项的自查 — 核对无 TBD**

`grep -n "TBD\|TODO\|__\|<实测" docs/superpowers/plans/2026-10-06-full-e2e-audit.md` 应只匹配到"模板占位说明"段落本身。

---

## 计划自检结论

---

### 2. 占位符扫描

已扫描，无 TBD / TODO / "类似 Task N" / "补充错误处理" 类空步骤。

`__` 与 `<实测>` 是**执行时填写的记录模板**，已在 Task 0 Step 1 与各任务末步明确要求"替换为真实值"，并有 Step 6 自查命令。

### 3. 类型/命名一致性

- 账号命名统一：`wb04307201`（ADMIN，密码 `123456`）、`e2euser`（USER，密码 `TestE2E2026`）
- 会话 id 在 Task 3 Step 5 定义，Task 8 Step 7 消费
- `e2e-knowledge` 库在 Task 4 Step 5 创建，Task 5 Step 1/6、Task 7 Step 2 复用
- 日志路径统一 `/tmp/loom-e2e-server.log`，Task 12 用 `-norag` 后缀区分
- 截图路径统一 `docs/e2e-audit-2026-10-06/screenshots/<维度字母>-<场景>.png`
- cookie 名 `loom-agent-session`（已核实 `LoomAgentProperties.CookieProperty.name` 默认值），HttpOnly 需用 `curl -i` 读 `Set-Cookie` 取值
- 端点契约已核实：`POST /admin/users` body=`CreateUserRequest(username,nickname,password,type)` 返回 `true`；`PUT /admin/users/{username}/roles` body=`{"roleCodes":[...]}`；`PUT /admin/mcps/{name}`；`POST /admin/market-skills/{id}/approve`

### 4. Review Focus 覆盖

| # | Review Focus 项 | 覆盖任务 |
|---|---|---|
| 1 | 非 admin 访问 admin 页 | Task 2 Step 2（8 页 curl 批量）+ Task 11 Step 4（URL 越权） |
| 2 | 普通用户空态 | Task 7 Step 1（知识库市场空态）—— ⚠️ **仅覆盖一处**，console/user 页空态未显式测，见下方 gap |
| 3 | 知识库上传后立即检索 | Task 4 Step 5（curl 上传 + 对话检索） |
| 4 | LLM 不调工具直接回答 | Task 4 任务级警告（区分三态） |
| 5 | 中文文件名往返 | Task 5 Step 3（上传）+ Task 9 Step 2-3（树展示 + 预览） |

**Gap 修复**：Review Focus #2 补 Task 6 Step 1b 之后的一句 —— `e2euser` 打开 console 相关视图时验证空态。实际上 Task 8 Step 1（console 用户列表）与 Task 7 Step 1（知识库市场）已覆盖两处。第三处（user.html 空态）在 Task 8 Step 6 隐含。判定：**可接受**，在 Task 13 Step 5 未测项清单中记录。

---

## 计划自检结论文档

（以上为自检记录，随计划一同提交。）
