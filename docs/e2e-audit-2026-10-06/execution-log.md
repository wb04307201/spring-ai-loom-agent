# E2E 巡检执行日志

巡检日期：2026-10-06
分支：`dev-sp2`（BASE `7421e552`）
Spec：`docs/superpowers/specs/2026-10-06-full-e2e-audit-design.md`
Plan：`docs/superpowers/plans/2026-10-06-full-e2e-audit.md`

## 环境基线（实测）

| 项 | 值 | 命令 |
|---|---|---|
| Java | 25.0.3（LTS，**默认字符集 UTF-8**） | `java -version` |
| Maven | 3.9.16（`C:\developer\apache-maven-3.9.16`） | `mvn -version` |
| Node | v24.13.0 | `node --version` |
| MINIMAX_API_KEY | `sk-cp-sP...`（长度 125，已设置） | `echo $MINIMAX_API_KEY` |
| Docker | 28.3.2（本次不跑部署管线） | `docker --version` |
| Playwright Chromium | `~/AppData/Local/ms-playwright/` 下 4 个版本 | `ls` |

> **UTF-8 备注**：CLAUDE.md 记载 MCP Java SDK 0.18.x `StdioClientTransport` 入站读取用平台默认字符集，
> 中文 Windows JDK 17 GBK 下会炸。本机 JDK 25 默认 UTF-8，预期免疫 —— 维度 J 实测确认。

## 启动序列（Task 12 恢复态请逐字复制）

> ⚠️ **计划缺陷已修正**：`mvn spring-boot:run -pl spring-ai-loom-agent-test` 裸跑会连
> `~/.loom/datasource`（`src/main/resources/application.yml` 生效），隔离配置在
> `src/test/resources/application.yml` 只在测试类路径下加载。**必须显式覆盖三项**：

```bash
cd /c/developer/IdeaProjects/spring-ai-loom-agent
mvn install -pl spring-ai-loom-agent -Dgpg.skip=true -q
rm -rf spring-ai-loom-agent-test/target/test-ds
rm -rf spring-ai-loom-agent-test/target/test-users
mvn spring-boot:run -pl spring-ai-loom-agent-test \
  -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:h2:file:./target/test-ds/db;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE --spring.ai.loom.agent.datasource-dir=./target/test-ds --spring.ai.loom.agent.users-base-path=./target/test-users" \
  > /tmp/loom-e2e-server.log 2>&1
```

> 路径相对于 **test 模块工作目录**（`spring-boot:run` 的执行目录），故是 `./target/...`。

**停服**（`TaskStop` 不够 —— Maven fork 的 java 子进程会存活继续占 8080，实测）：

```bash
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'LoomAgentTestApplication|spring-ai-loom-agent-test\\\\target' } | Select-Object ProcessId,ParentProcessId"
# 拿到应用 PID 后，连同 Maven 父进程一起：
taskkill //PID <mvnPid> //T //F
```

关闭 RAG 的降级启动（Task 12 用，**同样带三项覆盖**）：

```bash
mvn spring-boot:run -pl spring-ai-loom-agent-test \
  -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:h2:file:./target/test-ds/db;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE --spring.ai.loom.agent.datasource-dir=./target/test-ds --spring.ai.loom.agent.users-base-path=./target/test-users --spring.ai.loom.agent.rag.enabled=false" \
  > /tmp/loom-e2e-server-norag.log 2>&1 &
```

## 登录与凭据

| 账号 | 密码 | 类型 | 用途 |
|---|---|---|---|
| `wb04307201` | `123456` | ADMIN | admin 视角全部任务 |
| `e2euser` | `TestE2E2026` | USER | 非 admin 视角（Task 2 建，Task 6/7/8/11 用） |

会话 cookie 名：`loom-agent-session`（HttpOnly，`evaluate_script` 读不到，用 `curl -i` 取 `Set-Cookie`）

- admin cookie 值：见 /tmp/admin-cookie.txt（每次登录会变，subagent 需自行用 curl 重新取）
- e2euser cookie 值：见 /tmp/e2euser-cookie.txt（同上，会变）

## 数据隔离

| 项 | 路径 |
|---|---|
| 数据库 | `./spring-ai-loom-agent-test/target/test-ds/` |
| 用户树 | `./spring-ai-loom-agent-test/target/test-users/` |
| 巡检产物 | `docs/e2e-audit-2026-10-06/` |
| 服务日志 | `/tmp/loom-e2e-server.log` |

**不触碰 `~/.loom`**（MCP server 自身的 `~/.loom/mcp` 沙箱除外，那是 MCP 进程自己的配置目录）。

## H2 查询用命令模板

> ⚠️ **计划缺陷已修正（原模板三处错误）**：
> ① `-cp ~/.m2/.../h2-*.jar` 通配符在 Git Bash 展开成多个 jar，`-cp` 只取到 `-sources.jar`（无主类）→ 须用**显式路径**
> ② 未加 `-Dfile.encoding=UTF-8` 时中文输出乱码
> ③ `flyway_schema_history` 与 `role_skill` 的列名/大小写需按实际 schema 核对（见下）

**正确模板**：

```bash
H2JAR="$HOME/.m2/repository/com/h2database/h2/2.4.240/h2-2.4.240.jar"
H2() { java -Dfile.encoding=UTF-8 -cp "$H2JAR" org.h2.tools.Shell \
  -url "jdbc:h2:file:./spring-ai-loom-agent-test/target/test-ds/db;AUTO_SERVER=TRUE" \
  -user sa -password 123456 -sql "$1" 2>&1 | grep -v "^$"; }
```

**表名/列名的三个坑**：

| 对象 | 坑 | 正确写法 |
|---|---|---|
| `flyway_schema_history` | 表名与列名均为**小写**，非 H2 默认大写 | 列名加双引号：`SELECT "version","description","success" FROM "flyway_schema_history"` |
| `INFORMATION_SCHEMA` 查询 | 需限定 `TABLE_SCHEMA='PUBLIC'` | `WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='flyway_schema_history'` |
| `role_skill` | **没有 `skill_id` 列** | 列名是 `market_skill_id`；另有 `default_loaded` / `sort_order` |

**PUBLIC schema 表总数：39**

## 实际执行记录

（各任务在此追加）

### Task 0 · 环境预检与启动

- 库模块安装：`mvn install -pl spring-ai-loom-agent` → BUILD SUCCESS，**231 个单测全通过**
- 隔离目录：`target/test-ds`、`target/test-users` 已清空重建
- **计划缺陷修正**：`spring-boot:run` 裸跑会连 `~/.loom/datasource`（`src/main/resources/application.yml` 生效），
  隔离配置在 `src/test/resources/application.yml` 只在测试类路径加载。已改为命令行显式覆盖三项，
  实测数据源 = `jdbc:h2:file:./target/test-ds/db` ✓
- **Flyway**：`<< Empty Schema >>` → 依次迁移 `1.0 - init` / `1.1 - mcp data` / `1.2 - init app data` / `2.0 - init`
  → `Successfully applied 4 migrations to schema "PUBLIC", now at version v2.0`
- **MCP**：4 个 server 全部连接成功 —— `sequential-thinking-server` / `bing-cn-search` /
  `@tokenizin/mcp-npx-fetch` / `mcp-server-chart`；`Error processing inbound message` / `JsonParseException`
  计数 = **0** ⇒ JDK 25 默认 UTF-8 免疫 CLAUDE.md 记载的 MCP GBK 坑 ✓
- 服务就绪：`http://localhost:8080/spring/ai/loom/login.html` HTTP 200
- 停服方式：`taskkill /PID <mvnPid> /T /F`（`TaskStop` 杀不掉 fork 出的应用 java 进程）

### Task 1 · 维度 A 结果 —— 数据底座（控制器独立核对）

> 说明：Task 1 subagent 执行中卡在 H2 命令（计划模板缺陷，见上）。控制器用修正后的
> 命令独立完成了全部核对，结论已确定；subagent 回来后只做交叉印证。

**Flyway 版本序列**（`<< Empty Schema >>` → v2.0，与启动日志一致）：

| version | description | success |
|---|---|---|
| null | << Flyway Schema History table created >> | TRUE |
| 1.0 | init | TRUE |
| 1.1 | mcp data | TRUE |
| 1.2 | init app data | TRUE |
| 2.0 | init | TRUE |

5 行全 `success=TRUE`，**零缺陷**。

**种子数据计数**（对照 spec 预期基线）：

| 列 | 预期 | 实测 | 结论 |
|---|---|---|---|
| users | 1 | **1** | ✓ |
| roles | 1 | **1** | ✓ |
| skills | 3 | **3** | ✓ V2.0 第 3 条种子生效 |
| kb | 0 | **0** | ✓ |
| mcps | 4 | **4** | ✓ |
| role_kb | 0 | **0** | ✓ |

**base 角色 skill 授权**（V2.0 第 3 条是关键验证点）：

| role_code | name | default_loaded |
|---|---|---|
| base | STAR-IJ 讲清一件事 | TRUE |
| base | 靶心人公式 讲好一个故事 | TRUE |
| base | 交叉评审 | TRUE |

**3 行** ⇒ 第 3 条官方技能「交叉评审」已授权给 base 角色，**普通用户登录可见 3 条官方技能**。

**user_role**：`(wb04307201, base)` 1 行 ⇒ 默认 admin 已有基础角色，符合设计。

**结论：维度 A 通过，零缺陷。** 底座可信，Wave 1 后续任务结论有效。

### Task 2 · 维度 B 结果 —— 认证与会话

> subagent 卡在浏览器登出循环，控制器接管完成。截图 B-auth-*.png 5 张由 subagent 产出。

**未登录访问（期望：全部非 200）**：

| 目标 | 实测 | 结论 |
|---|---|---|
| `admin/users` | **401** | ✓ |
| `admin/capabilities` | **401** | ✓ |
| `admin/roles` | **401** | ✓ |
| `api/capabilities` | **401** | ✓ |
| `knowledge` | **401** | ✓ |
| `conversation` | **401** | ✓ |
| `admin/console.html` | **401** | ✓ |
| `admin/stats.html` | **401** | ✓ |
| `admin/roles.html` | **401** | ✓ |
| `admin/mcps.html` | **401** | ✓ |
| `admin/market-skills.html` | **401** | ✓ |
| `admin/knowledge-market.html` | **401** | ✓ |

12/12 全部 401，**零漏网**。Review Focus #1（admin 页未鉴权）通过。

**会话 cookie 属性**：

```
Set-Cookie: loom-agent-session=<uuid>; Expires=Wed, 07 Oct 2026 02:47:43 GMT;
            Max-Age=86400; Path=/; HttpOnly; SameSite=Lax
```

`HttpOnly` ✓ `SameSite=Lax` ✓ `Path=/` ✓ `Max-Age=86400`（24h，与 `CookieProperty` 默认一致）✓

**RBAC 负向面（Review Focus #1 后半）**：

| 目标 | e2euser 实测 | 结论 |
|---|---|---|
| `admin/users` | **302** | ✓ 重定向，非越权 |
| `admin/capabilities` | **302** | ✓ |
| `admin/roles` | **302** | ✓ |
| `admin/mcps` | **302** | ✓ |
| `api/capabilities`（自己的） | **200** | ✓ |
| `conversation`（自己的） | **200** | ✓ |
| `skill`（自己的） | **200** | ✓ |

strict RBAC 生效：非 admin 访问 `/admin/**` 一律 302，自己的端点正常。

**用户创建**：`POST /admin/users` 返回 `true`，`user_info` 现有 2 行：

| username | nickname | type |
|---|---|---|
| e2euser | E2E Test User | USER |
| wb04307201 | 吴博 | ADMIN |

`e2euser` / `TestE2E2026` 登录验证 **HTTP 200** ✓ —— Task 6/7/8/11 依赖的资产已就绪。

**改密码成功路径**：未测（刻意跳过 —— 会污染后续 12 个任务共用的 admin 账号）。
**计划中一处需订正**：原计划写"改密码负向面"用错误旧密码，此项未完成（subagent 中断）。

**结论：维度 B 通过，零产品缺陷。** 唯一 500 是巡检工具事故（H2 Shell 关闭数据库），
重启后同一请求返回 `true` —— 证明 `DefaultUser.createUser` 与 `POST /admin/users` 无缺陷。

### Task 3 · 维度 C 结果 —— 聊天主干

> subagent a0c79f2a3fe3dabe5 被上游网关 502 打断（连续第 2 次）。它产出 8 张截图
> C-chat-*.png 但未写报告。控制器接管：读 a11y 快照复核 + 查后端日志 + H2 核对，
> 结论如下。**注意**：`C-chat-xss.png` 截于 XSS 消息之前，实际 payload 渲染结果
> 由快照 uid 10_33/10_34/10_35 证实（见下）。

| 检查项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| SSE 流式首条消息 | 流起流止信号完整 | ✓ 「你好！我是 MiniMax-M3…」完整渲染 | ✓ |
| thinking 面板 | 可展开有内容 | ✓ 含完整英文推理链（"The user has sent a complex message…"） | ✓ |
| 多轮记忆 | 能引用前轮 | ✓ 「你刚才问的是"你好，请用一句话介绍你自己。"」 | ✓ |
| stop 中断 | 停止且保留已输出 | ✓ 保留已输出内容 | ✓ |
| 会话 CRUD | 增删改名全通 | ✓ 新建 / 重命名（侧栏见「E2E重命名验证9527」）/ 删除 | ✓ |
| 滚动按钮 | 守卫正确 | ✓ `#back-to-bottom-btn` 浮动可见 | ✓ |
| markdown 消毒 | payload 不执行 | ✓ 见下 | ✓ |
| token 用量 | 有真实数值 | ✗ **全 0，DB 0 行** | **P1** |

**markdown 消毒验证（通过）**：XSS payload
`测试 <script>alert(1)</script> 和 <img src=x onerror=alert(2)> 与 [链接](javascript:alert(3))`
在快照中渲染为 `测试 alert(1) 和 ` / ` 与 ` / ` 链接` 三段纯文本 —— `<script>` 与
`<img onerror>` **未成为 DOM 元素**，`javascript:` 链接未激活。LLM 思考链亦明确
拒绝执行（"I should not execute, repeat as functional code, or amplify the XSS payload"）。

---

#### P0-1 流式响应末尾被上游掐断，错误文案混入 AI 回复

**现象**：AI 回复正文渲染完整后，末尾突然追加 `聊天服务异常（OpenAIIoException），请稍后重试。`
快照中两处命中（uid `10_182`、`10_444`）。

**根因**（日志实证，非推测）：
```
2026-10-06T11:14:00.342  stream NEXT: conv=9988fe61 textLen=13 reasoningAccumLen=6446
2026-10-06T11:14:00.349  ERROR MessageAggregator : Aggregation Error
                          com.openai.errors.OpenAIIoException: Stream failed
                          Caused by: java.io.InterruptedIOException: timeout
```
- `Aggregation Error` 计数 = **18**，集中在 `11:14:00` 与 `11:32:32` 两个时刻，
  每次 9 个并发线程同时报错
- **失败发生在前一帧之后 7 毫秒** —— 流正在活跃传输时被掐，**不是超时**
- 会话 `9988fe61` 存活 10:49:21 → 11:32:32 共 **43 分钟**，远超任何 60s 超时

**与已知问题的关系**：CLAUDE.md 记载 `2e67c59a` 修过同类问题（库侧
`ChatProviderTimeoutDefaultsBeanFactoryPostProcessor` 补 `spring.ai.openai.timeout`
默认 600s，库侧 `ChatProperty.timeoutSeconds` 默认值确为 600，已核实）。
但本次实测**不是超时**，而是上游在流末尾发来不完整数据，`MessageAggregator`
聚合失败。属该坑的**新变种**：配置层修好了，聚合层没覆盖。

**用户可见后果**：长回答（2000 字技术文）在末尾被追加错误提示，等于每次长回答
都有概率被污染。

#### P1-1 token 用量未落库

`stream-usage: true` 已配（test app yml），但：
```
SELECT COUNT(*) FROM loom_chat_usage;  →  0 行
```
前端 `#usage-total` / `#usage-prompt` / `#usage-completion` / `#usage-calls`
五个数值全显示 0。多轮长对话后仍为 0 行 ⇒ token 统计链路完全未工作。

#### P2-1 欢迎语与真实身份不符

页面标题固定渲染「你好！我是你的 AI 助手」，而 LLM 实际自述为
「我是 MiniMax-M3，一个由 MiniMax 开发的智能助手」。切换 provider 后标题不变。

**可复用会话 id**（供 Task 8 的 conversation.html 流水视图）：

| conversation_id | 轮次 |
|---|---|
| `9988fe61-dc5f-4b95-a11b-a2b59f793d71` | 9 |
| `8410f793-aaab-4dd5-bf89-f834467ed2eb` | 2 |
| `aff1a151-36e3-4203-b0ec-b67a2d45bff4` | 2 |

**结论：维度 C 发现 1 个 P0 + 1 个 P1 + 1 个 P2。** 聊天主干功能可用
（流式/thinking/记忆/stop/CRUD/消毒 全通过），但长回答末尾必被错误文案污染。

### Task 4 · 维度 D 工具层（控制器接管执行）

> subagent a5a48698e194f8bd0 被上游网关 `400 Model is unavailable` 打断
> （第 3 次 subagent 失败：2× 502 + 1× 400）。它留下 Step 1 的 RBAC 授权快照，
> 其余由控制器接管。

**Step 1 · RBAC 授权快照**（subagent 产出，已核实）：

| type | id | effectiveEnabled | 工具数 |
|---|---|---|---|
| LOCAL | tool_compile | false | 1 |
| LOCAL | tool_git | false | 28 |
| LOCAL | tool_http | false | 5 |
| LOCAL | tool_maven | false | 6 |
| LOCAL | tool_render | false | 1 |
| MCP | @tokenizin-agency/mcp-npx-fetch | true | 4 |
| MCP | bing-search | true | 2 |
| MCP | mcp-server-chart | true | 27 |
| MCP | sequential-thinking | true | 1 |

7 个 universal 工具（time/file/skill/knowledge/subtask/schedule/askUser）**不出现在**
`/api/capabilities` 里 —— 符合设计（走 `defaultGranted=true` 放行，不经 `role_tool`）。
5 个 RBAC 工具全部未授权，与 CLAUDE.md 记载一致。

**工具核对结果**：

| 工具 | 判定 | 证据 |
|---|---|---|
| **file** | ✓ 调用成功 | LLM 调 `readTextFile`，读回 `"E2E 测试内容 2026-10-06"` 与文件系统**逐字一致** |
| **time** | ✓ 调用成功 | LLM 调 `getCurrentTime`，返回 `2026-10-06（周一）18:41:52 Asia/Shanghai`，与后端日志时间戳吻合到秒级 |
| **knowledge** | ⚠ 链路通/结果无意义 | 上传 **200**（非 500），向量落库，但 **dim=4**（见 P1-2） |

**file 工具实证** —— 思考链原文：
> 用户想读取 e2e/sample.txt 文件并原样输出内容。我直接调用 readTextFile 工具即可。
> 读取成功，内容是 `"E2E 测试内容 2026-10-06"`，原样返回给用户即可。

**time 工具实证** —— 思考链原文：
> The user is asking for the current date and time. Let me call the getCurrentTime tool.
> ... The tool gave me the current time.

---

#### P1-2 知识库向量维度为 4（test app 配置缺陷）

**D2 重新定级**：spec §2 原判 P0（预期上传 500）。**读源码后修正为 P1**。

**修正依据**：`H2JVectorStore` 只调 `embeddingModel.embed(...)`（L208 写入 / L309 查询），
**从不调 `call()`**；全库 grep 无任何 `.call(embed...)` 调用点。而 fake `EmbeddingModel`
的 `embed()` **有实现**（4 维随机向量），只有 `call(EmbeddingRequest)` 抛
`UnsupportedOperationException`。

**实测**：
```
PUT  /knowledge {"name":"e2e-knowledge",...}  → 200 {"id":"dbee7b15-..."}
POST /knowledge/dbee7b15.../upload            → 200 {"status":"success","fileId":"dde514b0-..."}

SELECT document_id, dim, LENGTH(embedding) FROM loom_vector_store;
→ c41501f4-...  |  dim=4  |  15 bytes
```

| 检查项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| 上传返回 | 200 或 503 | **200** | ✓ 链路通 |
| 向量落库 | 有行 | **1 行** | ✓ |
| 文档内容 | 正确 | ✓ 中文完整 | ✓ |
| **向量维度** | 真实模型维度（1024+） | **dim=4** | **P1** |

**定级理由**：上传与检索链路**都能跑通**，但向量是
`new Random(text.hashCode())` 生成的 4 维随机数 —— 检索排序**语义上无意义**
（问 A 返回随机相似文档，不是真正相关内容）。知识库作为 RAG 核心能力，
在本 test app 配置下**不可用于验证**。

**影响**：这是 test app 配置问题（`TestAnthropicOptionsConfig` 位于 `src/main/java`），
**不是库代码缺陷**。但它使 Task 5（维度 E）的检索断言失去意义 ——
Task 5 只能断言"链路不崩"，不能断言"检索到相关内容"。

**执行备注**：`evaluate_script` 跑 >120s 的长 async 轮询会撞 Chrome DevTools 的
`protocolTimeout`（`Runtime.callFunctionOn timed out`）。改用短轮询（每次只读状态，
非阻塞）+ Bash `sleep` 控制间隔。

---

**skill 工具** —— ✓ 调用成功

**问法**：`请用技能工具帮我创建一个名为 e2e-test-skill 的技能，描述是"E2E 测试技能"，内容是"这是 E2E 测试技能的内容"。`

**实测**（DB 核对）：
```sql
SELECT name, description FROM user_skill WHERE name='e2e-test-skill';
→ e2e-test-skill | E2E 测试技能
```
**判定：调用成功** ✓ —— LLM 调用 `createOrUpdateSkill`，`user_skill` 表新增行，
name/description 与请求**完全一致**。该技能供 Task 11（多租户隔离）验证 e2euser 不可见。

---

**http 工具** —— ✓ 调用成功

**问法**：`请调用 http 工具请求 https://jsonplaceholder.typicode.com/posts/1 这个接口，把返回的 JSON 里的 title 字段告诉我。`

**实测**：
> 该请求返回的 title 字段是：
> sunt aut facere repellat provident occaecati excepturi optio reprehenderit

**判定：调用成功** ✓ —— LLM 调 `invokeEndpoint` 真实请求外网，
返回的 title 与 jsonplaceholder **真实数据逐字一致**（`posts/1` 的 title 确为此串）。
白名单（`allowed-domains: [jsonplaceholder.typicode.com]`）放行生效。

---

**render 工具** —— ✓ 调用成功（真实 Chromium 渲染）

**前置**：`INSERT INTO role_tool` 授 `base` 角色 `tool_render`（原未授权，符合设计）。

**问法**：`请调用 render 工具，把 e2e/proto.html 这个文件渲染成 PNG 图片。`

**实测**：
```
spring-ai-loom-agent-test/target/test-users/wb04307201/file/prototypes/
  proto-20261006185053.png   36774 bytes
```

**图片内容核验**（人工读图）：紫色标题「E2E 渲染测试」、正文「中文渲染检查 ✓」，
与 `proto.html` 源文件**逐字一致**；`#4f46e5` 主色正确渲染；中文与 ✓ 符号无乱码；
2880×1800 像素 = 1440×900 CSS @2x，与 `render.deviceScaleFactor=2` 配置吻合。

**判定：调用成功** ✓ —— 无头 Chromium 真实渲染，route abort + CSP 屏蔽外联生效
（页面无外部资源请求），输出落 `prototypes/` 并经 FileIdBridge 桥接。

---

**subtask 工具** —— ✓ 调用成功（附 1 处待查）

**问法**：`请用 start_sub_task 起一个子任务，让它用一句话说明什么是 REST 架构。`

**实测**：
```
SELECT COUNT(*) FROM loom_subtask_history;  →  1 行
日志：loomSubTask 线程池痕迹出现
```

AI 回复（含子任务返回值）：
> 子任务返回的一句话：REST 是一种基于 HTTP、利用 URI 标识资源并通过 GET、POST
> 等标准方法操作资源的无状态客户端-服务器架构风格。

**判定：调用成功** ✓ —— LLM 调 `start_sub_task`，子任务在 `loomSubTaskExecutor`
池执行并返回结果，主对话正确收口。思考链还正确解释了子任务适用场景
（"主要价值在于处理需要大量 reasoning、多步工具调用或大文件"），
与 `@Tool` 描述的正向引导措辞一致。

**待查（P2 候选）**：`#subtask-panel-body` 显示「0 运行中 / 0 已完成 / 0 失败」，
而 DB 有 1 行历史。可能是面板只统计当前会话的活跃子任务、历史需另开视图 ——
本次未展开核实，列入未测项。

---

**schedule 工具** —— ✓ 调用成功

**问法**：`请用 createSchedule 创建一个定时任务，名字叫 e2e-sched，每隔 15 分钟执行一次，内容是"记录一行日志"。`

**实测**：
```json
GET /schedule/list →
[{"taskName":"loom-sched-wb04307201-98044738-9bda-49f7-bd5e-1dcd10fc2420-e2e-sched",
  "taskType":"FIXED_DELAY","schedule":"PT15M/PT0S"}]
```
DB `loom_scheduled_task` 同步落库 1 行。

**判定：调用成功** ✓ —— LLM 调 `createSchedule`。命名空间
`loom-sched-{user}-{conv}-{name}` 与设计**完全一致**；间隔 `PT15M` 与请求的
15 分钟一致（≥ `flex.schedule.limits.min-interval=10m`，未越界）。
测毕已 `POST /schedule/cancel` 清理，列表回到 `[]`。

---

**askUser 工具** —— ✓ 调用成功（完整闭环）

**问法**：`请用 askUser 工具问我一个二选一的问题：午餐吃面条还是米饭？`

**实测**（卡片 DOM）：
```
.askuser-card
  标题：午餐选择          ⏳ 3:23（倒计时）
  问题：午餐你想吃面条还是米饭？
  背景：今天中午不知道吃什么,快速二选一。
  ○ 面条  汤面/拌面/炒面,各种面食
  ○ 米饭  盖饭/炒饭/套餐,配菜下饭
  [提交答案]
```
点选「面条」并提交后：
- 卡片状态变为「**已答 ✓**」
- 答案以 tool_result 回流：`✓ 午餐你想吃面条还是米饭？ → 面条`
- AI 继续对话：「用户选了 面条 🍜 那就来碗热腾腾的面吧!...」

**判定：调用成功** ✓ —— 阻塞式提问 → 用户作答 → tool_result 回流 → LLM 续答，
完整闭环。卡片倒计时（`askuser.timeoutSeconds=300`）、多选项渲染、提交后状态
更新全部正常。

---

#### P1-3 schedule 工具的 expression 解析不支持 ISO-8601，且静默返回 -1

**现象**（LLM 思考链原文，非推测）：
> 成功了!格式是 "15m" 而不是 ISO-8601 的 "PT15M"。
> 💡 **踩坑提示**：这个平台 `fixed_delay`/`fixed_rate` 的 `expression` 用的是
> **简写字符串**（如 `15m`、`30s`、`2h`），不是标准 ISO-8601 Duration（`PT15M`）。
> 我第一次用了 `PT15M` 被平台解析成了 `PT-1S` 直接报错。

**根因**（读 `DefaultScheduleTool.parseSeconds` 源码确认）：
```java
String s = expression.trim().toLowerCase()   // "PT15M" → "pt15m"
        .replace("秒钟","s").replace("秒","s")
        .replace("secs","s").replace("sec","s")
        .replace("分钟","m").replace("分","m")
        .replace("minutes","m").replace("minute","m").replace("mins","m").replace("min","m");
// "pt15m" 不含以上任何模式 → 保持 "pt15m"
if (s.endsWith("m")) { ... parseLong("pt15") 失败 → 落到下面 }
if (s.endsWith("s")) s = s.substring(0, s.length()-1);   // 不以 s 结尾，不裁剪
return Long.parseLong("pt15m");   // NumberFormatException → return -1L
```
`-1` 被当作秒数使用 ⇒ 表现为「PT-1S」。

**问题有二**：
1. **不支持 ISO-8601** —— `PT15M` 是最符合直觉/最标准的写法（Java `Duration`
   的标准格式），却完全不被识别
2. **静默失败** —— 无法解析时返回 `-1L` 而非抛错或提示格式。调用方（LLM）
   只看到「PT-1S」这种无意义报错，无法反推真实原因
3. **`@Tool` 描述未说明格式** —— 描述只写了「类型 cron / fixed_delay / fixed_rate /
   one_shot」和限制，**完全没提 expression 的格式要求**

**定级 P1 理由**：功能最终可用（LLM 自行试错找到了 `15m`），但**首次调用必然失败**，
且错误信息误导（`PT-1S` 与真实原因无关）。对不熟悉该库的用户/模型，这是必然的
一次挫败。`@Tool` 描述补一句格式说明即可大幅缓解。

**建议修复方向**：
- `parseSeconds` 增加 ISO-8601 分支（`Duration.parse()` 回退）
- 无法解析时返回明确的错误信息而非 `-1`
- `@Tool` 描述补：`expression 格式：简写字符串，如 "15m"/"30s"/"2h"，或中文"15分钟"`

---

### Task 4 · 维度 D 汇总

| 工具 | 判定 | 证据要点 |
|---|---|---|
| file | ✓ | `readTextFile` 读回内容与文件系统逐字一致 |
| time | ✓ | `getCurrentTime` 返回值与后端日志吻合到秒级 |
| skill | ✓ | `createOrUpdateSkill` → `user_skill` 新增行，字段一致 |
| http | ✓ | `invokeEndpoint` 真实外网，title 与 jsonplaceholder 逐字一致 |
| render | ✓ | 无头 Chromium 渲染 PNG，图片与源逐字一致，@2x 尺寸正确 |
| subtask | ✓ | `loom_subtask_history` 1 行，主对话正确收口 |
| schedule | ⚠ | 创建成功（`loom-sched-*` 命名空间正确），但 **P1-3 格式解析坑** |
| askUser | ✓ | 卡片渲染 + 倒计时 + 作答回流 + LLM 续答，完整闭环 |
| knowledge | ⚠ | 上传 200 链路通，但 **P1-2 向量 dim=4** |

**未测（安全边界）**：git（本地 init）/ maven（validate）/ compile&deploy —— 
需额外授权且价值低于风险，列入未测项。

**维度 D 结论：9 个工具中 7 个完全通过，2 个链路通但有缺陷（P1-2 向量维度、P1-3 格式解析）。**

---

### Task 5 · 维度 E 知识空间 UI

**UI 层验证**（浏览器）：

| 检查项 | 实测 | 结论 |
|---|---|---|
| `#ks-button` 打开面板 | 面板正常展开 | ✓ |
| `#ks-sidebar` 列出知识库 | 显示 `e2e-knowledge` + 「自建」标签 + 描述 | ✓ |
| 点击条目 | 详情区加载 | ✓ |
| `#ks-detail` 文件列表 | `e2e-doc.txt` / 151 Bytes / 上传时间 / 预览·下载·删除 | ✓ |
| 截图 | `screenshots/E-kb-detail.png` | ✓ |

UI 层**零缺陷**。截图见 `E-kb-detail.png`。

---

#### Review Focus #5 · 中文文件名往返 —— 通过（客户端工具陷阱，非产品缺陷）

**三种上传方式对比**（codepoint 级证据）：

| 上传方式 | 存储的文件名 codepoints | 判定 |
|---|---|---|
| `e2e-doc.txt`（纯 ASCII，curl -F） | `U+0065 U+0032 U+0065 U+002D ...` 全正确 | ✓ |
| `中文测试.txt`（**curl -F**，Git Bash） | `U+FFFD U+FFFD U+FFFD U+0132 U+FFFD U+FFFD U+FFFD U+002E...` | ❌ 乱码 |
| `中文测试.txt`（**Python 显式 UTF-8**） | `U+4E2D U+6587 U+6D4B U+8BD5 U+002E U+0074 U+0078 U+0074` | ✓ **正确** |

`U+4E2D U+6587 U+6D4B U+8BD5` = 「中文测试」—— **完全正确**。

**结论**：**服务端 UTF-8 处理正确，无缺陷**。curl 那次的乱码是
**Git Bash 的 curl 在 Windows 上以 GBK 编码 multipart 的 filename 字段**所致 ——
属客户端工具问题，与产品代码无关。

**这是本次巡检的第 2 个同类陷阱**（第 1 个是 curl 中文 JSON body 被 GBK 解码 →
`Invalid UTF-8 start byte` → 400）。凡在 Windows Git Bash 下用 curl 发**任何含中文的
请求体或文件名**，都会踩这个坑。**规避方式**：用 `--data-binary @file`（body）
或 Python/Java 显式 UTF-8 构造 multipart（文件名）。

---

### Task 5 · 维度 E 汇总

| 检查项 | 结论 |
|---|---|
| 知识空间面板打开 | ✓ |
| 知识库列表渲染 | ✓ |
| 文件列表 + 操作按钮 | ✓ |
| 中文文件名往返 | ✓（服务端正确；curl 乱码是客户端工具问题） |
| 上传链路 | ✓ 200（Task 4 已验） |
| **向量维度** | ❌ **P1-2**（dim=4，Task 4 已记） |
| 检索语义相关性 | **未测** —— 受 P1-2 影响，dim=4 的向量检索结果无意义，断言无价值 |

**维度 E 结论：UI 层零缺陷；核心能力（检索）受 test app 的 fake EmbeddingModel
限制不可验证（P1-2）。**

---

### Task 6 · 维度 G 技能市场

**全流程实测**（API 层，含 DB 核对）：

| # | 检查项 | 期望 | 实测 | 结论 |
|---|---|---|---|---|
| 1 | 市场官方技能数 | 3 | **3**（STAR-IJ / 靶心人公式 / 交叉评审） | ✓ |
| 2 | e2euser 提交 | PENDING | **PENDING**，id=33，author=e2euser | ✓ |
| 3 | admin 审批通过 | APPROVED | **APPROVED**，reviewedAt 已填 | ✓ |
| 4 | e2euser pull | user_skill +1 | **+1**，`source=MARKET_PULLED`，`marketSkillId=33` | ✓ |
| 5 | 空评论驳回 | 拦截 | **400** `"reject 需要评论(comment 不能为空)"` | ✓ |
| 6 | 带评论驳回 | REJECTED | **REJECTED** + 评论入库 | ✓ |
| 7 | 重投触发归档 | archive +1 | **archive id=34**（REJECTED + 评论保留） | ✓ |
| 8 | 重投后新行 | PENDING | **id=35 PENDING**（id 递增，旧行归档） | ✓ |
| 9 | 5 星评价 | 写入 | **200**，rating=5，editCount=0 | ✓ |
| 10 | PUT 首次修改 | 成功 | **200**，rating 5→4，**editCount 0→1** | ✓ |
| 11 | PUT 二次修改 | 403 | **403** | ✓ |

**关键机制核验**：

- **归档语义正确**（id-preserving）：驳回时**不**归档；**重投**时才把旧的 REJECTED 行
  移入 `market_skill_archive`，**保留原 id=34**，新行 id=35 进 `market_skill`。
  与 `ISkillStorage` javadoc「REJECTED re-submit archives old row (id-preserving)」一致。
- **`edit_count` 闸门位置正确**：`AbstractMarketReviewService` 的
  `submit`（POST）用 upsert **不校验** edit_count（首次提交允许，重复 POST 即覆盖）；
  `update`（PUT `/reviews/me`）才校验 `edit_count < 1`。实测 POST 两次均 200
  （设计行为，非缺陷），PUT 第二次 403 ✓
- **`created_by_kind`**：用户提交为 `USER` ✓

**维度 G 结论：11/11 全部通过，零缺陷。**

---

### 已测资产汇总（供 Task 7/8/11 复用）

| 资产 | 值 |
|---|---|
| 知识库 | `e2e-knowledge` id=`dbee7b15-755c-440e-8acd-a7816c1204ee` |
| 知识库文件 | `e2e-doc.txt`（151B）、`中文测试.txt`（3 个副本，2 个乱码 1 个正确） |
| 技能（已审批） | `e2e-submitted-skill` market id=**33**（APPROVED） |
| 技能（驳回中） | `e2e-reject-skill` market id=**35**（PENDING），archive id=**34** |
| 用户技能 | `e2e-test-skill`（admin 的）、`e2e-submitted-skill`（e2euser pull 的） |
| 可复用会话 | `9988fe61-dc5f-4b95-a11b-a2b59f793d71` 等 3 个 |
