# HTTP 调用能力双模接入 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `http-mcp` 的通用 HTTP 客户端能力按仓库既有 loom-file 双模模式接入 —— `loom-http-core`（引擎）+ `IHttpTool`（内部工具）+ `loom-http-mcp`（独立 jar）。

**Architecture:** 三层。`loom-http-core` 装引擎与存储服务，零 `@Tool`/`@McpTool`/`@Component`，以 `HttpStorage`（存储根作为数据传入）+ `HttpEngine`（唯一门面，全线返回 String）为核心契约。两个薄壳共用同一引擎：内部壳 `DefaultHttpTool`（`@Tool` + 尾部 `ToolContext`，存储根经 `LoomPaths.userHttpDir(usersBasePath, username)` 每请求派生）；jar 壳 `LoomHttpMcpService`（`@McpTool` snake_case，存储根取 `loom.http.mcp.basePath` 属性）。

**Tech Stack:** JDK 25 / Spring Boot 4.1.1 / Spring AI 2.0.1 / Maven 多模块 / JUnit 5 + AssertJ / WireMock 3.5.4 / Playwright

**Spec:** `docs/superpowers/specs/2026-10-02-http-mcp-dual-mode-design.md`

**源项目：** `C:\developer\IdeaProjects\http-mcp`（Boot 3.5.13 / Spring AI 1.1.8 / Java 17）。**该目录在 Task 14 完成后删除**，在此之前它是唯一参考源，**不得提前删除**。

## Global Constraints

以下约束适用于每一个任务，不再逐任务重复：

- **包名**：core 一律 `cn.wubo.loom.http.core.*`；jar 一律 `cn.wubo.loom.http.mcp.*`；内部工具 `cn.wubo.spring.ai.loom.agent.tool.http.*`
- **`loom-http-core` 允许依赖**：`spring-web`（仅 `RestClient` / `HttpHeaders` / `ResponseEntity` / `MediaType` / `HttpMethod`）、`jackson-databind`、`jackson-datatype-jsr310`、`json-path` 2.9.0、`swagger-parser` 2.1.22
- **`loom-http-core` 禁止依赖**：`spring-context`、`spring-boot`、`spring-ai`。core 内零 `@Component` / `@Service` / `@Autowired`，DI 全部手搭
- **`HttpEngine` 与所有门面方法一律返回 `String`**，绝不返回领域对象（http-mcp 规则 #3）
- **`JsonMappers` 绝不启用 `INDENT_OUTPUT`**（http-mcp 规则 #2）
- **`body` 参数类型必须是 `Map<String, Object>`**，绝不 `Object`（FIX-5）
- **工具命名**：内部 `@Tool` 用 camelCase 方法名（Spring AI 按方法名注册，不加 `name=`）；jar `@McpTool(name="snake_case")`
- **username 只从 `ToolContext.getContext().get("username")` 或 `UserContextHolder.getCurrentUser()` 取**，绝不从请求参数取
- **RBAC group 名**：`tool_http`（调用面）/ `tool_http_manage`（写面），均 `defaultGranted = false`
- **提交粒度**：每个 Task 一个 commit，沿用仓库既有前缀（`feat:` / `fix:` / `refactor:` / `docs:` / `test:` / `chore:`）
- **审计**：本变更触及信任边界输入 + 凭据 + 数据模型，按 AQG 属 `deep`，Task 13 的提交前须过 `audit-trigger.md` 检查清单

## Review Focus

spec 未显式规定、但真实使用中最可能咬人的五类输入。每个已在下方对应 Task 中落为测试：

1. **profile 存在但 `auth` 块缺字段** —— 期望明确报错而非静默发出无认证请求（Task 3 搬运 `ProfileValidatorTest`）
2. **上游返回超大响应体** —— 期望按 `maxResponseSizeBytes` 截断/落盘而非 OOM（Task 3 搬运 `InvokeScaleTest`）
3. **白名单为空的部署首次调用** —— 期望**拒绝**且**真的没有网络请求**（Task 4 新增断言）
4. **两个用户同名注册 system** —— 期望各自隔离，读不到对方 profile 凭据（Task 8 Step 5 `HttpPerUserIsolationTest`）
5. **LLM 传未注册的 system 名** —— 期望返回结构化错误 JSON 而非抛异常（Task 3 搬运 `InvokeFailureTest`）

## 阶段划分

- **阶段一（Task 1–4）**：`loom-http-core` + 289 个测试回归网跑通。**尚未接进聊天**，纯库阶段
- **阶段二（Task 5–9）**：内部工具模式接入 —— `LoomPaths` / 属性 / `IHttpTool` / RBAC / REST 写面 / 契约测试
- **阶段三（Task 10–12）**：`loom-http-mcp` 独立 jar
- **收尾（Task 13–14）**：文档同步 + 全量验证 + 删除源项目

---

（任务正文见下节。）