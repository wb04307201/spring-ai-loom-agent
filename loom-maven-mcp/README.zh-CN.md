# Loom Maven MCP 服务

独立的 MCP（模型上下文协议）服务，将 Maven 构建操作暴露为 AI 可调用的工具。以子进程方式执行 Maven，具备超时控制和进程树管理。

## 快速启动

### stdio 模式（jbang）

使用 [jbang](https://www.jbang.dev/) 无需本地安装即可运行 MCP 服务器，在 MCP 客户端（Claude Desktop、Cursor 等）中配置如下：

```json
{
  "mcpServers": {
    "loom-maven-mcp": {
      "command": "jbang",
      "args": [
        "io.github.wb04307201:loom-maven-mcp:1.0-SNAPSHOT",
        "--loom.maven.mcp.basePath=/workspace",
        "--loom.maven.mcp.mavenHome=/opt/maven",
        "--loom.maven.mcp.defaultTimeoutMs=300000"
      ]
    }
  }
}
```

## 本地构建

```bash
cd loom-maven-mcp
mvn clean package -DskipTests
java -jar target/loom-maven-mcp-1.0-SNAPSHOT.jar
```

默认以 stdio 模式启动。如需 SSE（HTTP）模式，添加 `--spring.main.web-application-type=servlet` 并配置 `server.port`。

## 配置项

所有配置在 `loom.maven.mcp` 前缀下：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `basePath` | `.local/file` | 相对路径解析的根目录 |
| `mavenHome` | *（自动检测）* | Maven 安装目录 |
| `localRepository` | *（默认 ~/.m2）* | 本地 Maven 仓库路径 |
| `maxOutputLines` | `200` | Maven 输出最大返回行数 |
| `defaultTimeoutMs` | `300000`（5 分钟） | 默认执行超时（毫秒） |

## 可用工具（6 个）

| 工具 | 说明 | 等效命令 |
|------|------|----------|
| `mavenExecute` | 通用 Maven 执行，可指定任意 goals | `mvn <goals...>` |
| `mavenBuild` | 编译项目 | `mvn compile` |
| `mavenPackage` | 打包项目（默认跳过测试） | `mvn package -DskipTests` |
| `mavenTest` | 运行单元测试 | `mvn test` |
| `mavenDependencyTree` | 查看依赖树 | `mvn dependency:tree` |
| `mavenValidate` | 验证项目结构 | `mvn validate` |

### mavenExecute 参数

通用入口接受以下参数：

| 参数 | 必填 | 说明 |
|------|------|------|
| `goals` | ✅ | Maven goals 列表，如 `["clean", "package"]` |
| `pomPath` | | pom.xml 路径（相对或绝对） |
| `workingDir` | | 工作目录（相对或绝对） |
| `properties` | | Maven 属性键值对 |
| `timeoutMs` | | 执行超时（毫秒） |

### 其它工具参数

| 工具 | 参数 | 必填 | 说明 |
|------|------|------|------|
| `mavenBuild` | `skipTests` | | 编译时跳过测试 |
| `mavenPackage` | `skipTests` | | 打包时跳过测试（默认 true） |
| `mavenTest` | `testPattern` | | 测试类匹配模式，如 `*ServiceTest` |
| `mavenDependencyTree` | `includeScope` | | 按依赖范围过滤：`compile`/`runtime`/`test`/`provided` |

## 特性

- **超时控制** — 超时后杀死整个 Maven 进程树（包括子 JVM 进程）
- **输出截断** — 限制输出行数，防止信息过载
- **跨平台** — 自动处理 Windows `mvn.cmd` 和 Unix `mvn`
- **进程树杀死** — Windows 使用 `taskkill /F /T`，Unix 使用递归 `kill -9`

## 关联项目

属于 [Spring AI LoomAgent 灵梭](https://github.com/wb04307201/spring-ai-loom-agent) 生态：

| MCP 服务 | 说明 |
|----------|------|
| [loom-file-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-file-mcp) | 文件系统操作（14 个工具） |
| [loom-git-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-git-mcp) | 基于 JGit 的 Git 操作（14 个工具） |
| [loom-compile-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-compile-mcp) | 端到端部署流水线（1 个工具） |

## 依赖

- `loom-maven-core` — 核心 Maven 执行逻辑（无 Spring 依赖）
- `loom-process-core` — 跨平台进程管理工具
- `spring-ai-starter-mcp-server` — Spring AI MCP 服务端
- `spring-boot-starter` — Spring Boot

## 常见问题

| 问题 | 解决方案 |
|------|----------|
| `basePath` 目录不存在 | 手动创建目录，服务不会自动创建 |
| 文件操作权限不足 | 确保进程用户对 `basePath` 有读写权限 |
| 大文件操作超时 | 增大 `maxOutputLines` 或 `defaultTimeoutMs` |
| 找不到 jbang | 安装 jbang：https://www.jbang.dev/download/ |
