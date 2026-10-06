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

- admin cookie 值：待 Task 2 填写
- e2euser cookie 值：待 Task 2 填写

## 数据隔离

| 项 | 路径 |
|---|---|
| 数据库 | `./spring-ai-loom-agent-test/target/test-ds/` |
| 用户树 | `./spring-ai-loom-agent-test/target/test-users/` |
| 巡检产物 | `docs/e2e-audit-2026-10-06/` |
| 服务日志 | `/tmp/loom-e2e-server.log` |

**不触碰 `~/.loom`**（MCP server 自身的 `~/.loom/mcp` 沙箱除外，那是 MCP 进程自己的配置目录）。

## H2 查询用命令模板

```bash
java -cp ~/.m2/repository/com/h2database/h2/*/h2-*.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:./spring-ai-loom-agent-test/target/test-ds/db;AUTO_SERVER=TRUE" \
  -user sa -password 123456 -sql "<SQL>"
```

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
