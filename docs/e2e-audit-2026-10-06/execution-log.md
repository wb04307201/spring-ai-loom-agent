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
