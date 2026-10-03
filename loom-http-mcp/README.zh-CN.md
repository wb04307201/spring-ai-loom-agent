# Loom HTTP MCP 服务

独立的 MCP（Model Context Protocol）服务，将 HTTP 调用能力暴露为 AI 可调用的工具和资源。
服务底层包装了 [loom-http-core](../loom-http-core) —— 一个无 Spring 依赖的纯 Java 引擎，
通过 MCP 传输对外提供能力。

## 快速启动

### stdio 模式（jbang）

使用 [jbang](https://www.jbang.dev/) 无需本地安装即可运行 MCP 服务器，在 MCP 客户端
（Claude Desktop、Cursor 等）中配置如下：

```json
{
 "mcpServers": {
 "loom-http-mcp": {
 "command": "jbang",
 "args": [
 "io.github.wb04307201:loom-http-mcp:1.0-SNAPSHOT",
 "--loom.http.mcp.basePath=/workspace/http-mcp-data"
 ]
 }
 }
}
```

## 本地构建

```bash
cd loom-http-mcp
mvn clean package -DskipTests
java -jar target/loom-http-mcp-1.0-SNAPSHOT.jar
```

默认以 stdio 模式启动。如需 SSE（HTTP）模式，添加 `--spring.main.web-application-type=servlet` 并配置 `server.port`。

## 配置项

所有配置在 `loom.http.mcp` 前缀下：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `basePath` | `~/.loom/http-mcp` | profiles / systems / history JSON 的存储根。**刻意不共享 `~/.loom/mcp/`** —— 详见"安全特性" |
| `failClosed` | `false` | 若为 `true`，`allowedDomains` 为空时拒绝所有请求（内部工具模式为 true；jar 默认 false 以兼容 http-mcp 1.1.1 的 fail-open 行为） |
| `autoReload` | `true` | 启用 FileWatcher 监听 profile / system 文件变化，并向已连接客户端推送 `resources/list_changed` |

## 可用工具（19 个）

### 调用（2）
| 工具 | 说明 |
|------|------|
| `invoke_endpoint` | 调用已注册系统上的 HTTP 端点。16 步流水线：白名单→路径模板→header 解析→请求→大响应落盘→断言→JSONPath 提取→脱敏→历史 |
| `http_batch` | 并发批量调用 `invoke_endpoint`。`concurrency` 默认 5，`failPolicy: continue / stopOnFirst` |

### 通用 HTTP（4）
| 工具 | 说明 |
|------|------|
| `http_get` / `http_post` / `http_put` / `http_delete` | 通用 HTTP 动词。每次调用合成一个 `_ad_hoc` 系统记录，走同一 16 步流水线 |

### Profile 管理（3）
| 工具 | 说明 |
|------|------|
| `add_profile` / `update_profile` / `remove_profile` | 认证 profile 的 CRUD（Bearer / Basic / apiKey-header / apiKey-query） |

### System 管理（4）
| 工具 | 说明 |
|------|------|
| `register_system` / `update_system` / `remove_system` / `refresh_system` | 系统记录的 CRUD；refresh 拉取 OpenAPI 元数据 |

### Endpoint 管理（5）
| 工具 | 说明 |
|------|------|
| `add_endpoint` / `update_endpoint` / `remove_endpoint` / `list_endpoints` / `get_endpoint` | 在 OpenAPI 之上手工维护端点定义 |

### History（1）
| 工具 | 说明 |
|------|------|
| `get_request_history` | 查询请求历史 JSONL。`system` 省略 = 全部系统；`limit` 默认 50，上限 500；`statusFilter: succeeded / failed / all` |

## 可用资源（4 个）

所有资源均采用单 URI + `@McpArg` 形式（Spring AI 2.x 的 `@McpResource` 不绑定 URI 模板变量）。

| URI | 参数 | 说明 |
|-----|------|------|
| `system://list` | (无) | 所有已注册系统的索引 |
| `system://by-name` | `system` | 单个系统的 JSON |
| `system://endpoints` | `system`, `tag?`, `source?` | 某系统的合并端点清单 |
| `system://endpoint` | `system`, `method`, `path` | 单个端点的完整 schema |

## 安全特性

- **Profile 包含明文凭据**（`auth.token` / `auth.password` / `auth.apiKey`），
  存于 `basePath/profiles/`。**默认 `basePath = ~/.loom/http-mcp/`** —— 刻意不共享
  `~/.loom/mcp/`（MCP 共享沙箱），以防 loom-file-mcp 的 universal `tool_file`
  工具误读到凭据。
- 白名单判定发生在**请求发出之前**（spec §4.4），不是事后过滤响应。
- `failClosed=true` 将空白名单由 fail-open（jar 默认）翻转为 fail-closed
  （内部工具模式）。需要更严格部署时启用。
- 历史 JSONL 中的敏感 header / body 字段由 `SensitiveFieldMasker` 脱敏。

## 关联项目

属于 [Spring AI LoomAgent 灵梭](https://github.com/wb04307201/spring-ai-loom-agent) 生态：

| MCP 服务 | 说明 |
|----------|------|
| [loom-file-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-file-mcp) | 文件操作（14 个工具） |
| [loom-git-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-git-mcp) | 基于 JGit 的 Git 操作（28 个工具） |
| [loom-maven-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-maven-mcp) | Maven 构建（6 个工具） |
| [loom-compile-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-compile-mcp) | 端到端部署（1 个工具） |

## 依赖

- `loom-http-core` —— 纯 Java 引擎（无 Spring）
- `spring-ai-starter-mcp-server` —— Spring AI MCP 服务端
- `spring-boot-starter` —— Spring Boot

## 常见问题

| 问题 | 解决方案 |
|------|----------|
| `basePath` 不可写 | `chmod` 该路径或重设 `loom.http.mcp.basePath` |
| 启动后工具列表为空 | 确认 `spring.ai.mcp.server.annotation-scanner.enabled=true`（本模块 yml 默认开启） |
| 资源数据陈旧 | FileWatcher 应在 fs 变更时推送 `list_changed`；检查 `loom.http.mcp.autoReload=true` |
| `~/.loom/mcp/` 下出现凭据文件 | **这是安全事件** —— 见"安全特性"；默认 `basePath` 设为 `~/.loom/http-mcp/` 正是为防止此情况 |
