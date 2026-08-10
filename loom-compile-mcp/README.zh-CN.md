# Loom Compile MCP 服务

独立的 MCP（模型上下文协议）服务，提供端到端编译部署功能。一次 AI 调用完成整个流水线：git clone → 构建 → docker build → docker run → 健康检查。

## 快速启动

### stdio 模式（jbang）

使用 [jbang](https://www.jbang.dev/) 无需本地安装即可运行 MCP 服务器，在 MCP 客户端（Claude Desktop、Cursor 等）中配置如下：

```json
{
 "mcpServers": {
 "loom-compile-mcp": {
 "command": "jbang",
 "args": [
 "io.github.wb04307201:loom-compile-mcp:1.0-SNAPSHOT",
 "--loom.compile.mcp.basePath=/workspace",
 "--loom.compile.mcp.mavenHome=/opt/maven",
 "--loom.compile.mcp.mavenTimeoutMs=600000"
 ]
 }
 }
}
```

## 本地构建

```bash
cd loom-compile-mcp
mvn clean package -DskipTests
java -jar target/loom-compile-mcp-1.0-SNAPSHOT.jar
```

默认以 stdio 模式启动。如需 SSE（HTTP）模式，添加 `--spring.main.web-application-type=servlet` 并配置 `server.port`。

## 配置项

所有配置在 `loom.compile.mcp` 前缀下：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `basePath` | `.local/file` | 工作区根目录 |
| `mavenHome` | *（自动检测）* | Maven 安装目录 |
| `mavenTimeoutMs` | `600000`（10 分钟） | Maven 构建超时 |
| `dockerBuildTimeoutMs` | `600000`（10 分钟） | Docker 构建超时 |
| `dockerRunTimeoutMs` | `60000`（1 分钟） | Docker 运行超时 |
| `healthCheckMaxWaitMs` | `60000`（1 分钟） | 健康检查最大等待时间 |
| `healthCheckIntervalMs` | `2000`（2 秒） | 健康检查轮询间隔 |
| `keepWorkspace` | `false` | 部署后保留工作区目录（用于调试） |
| `extraRunArgs` | `[]` | 额外的 `docker run` 参数 |
| `imageTemplates` | *（见下方）* | 基础镜像模板 |

### 默认镜像模板

| 别名 | 基础镜像 | 默认启动命令 |
|------|----------|-------------|
| `java17` | `eclipse-temurin:17-jre` | `java -jar` |
| `java21` | `eclipse-temurin:21-jre` | `java -jar` |
| `nginx` | `nginx:stable-alpine` | *（nginx 默认）* |
| `python3` | `python:3.11-slim` | `python` |
| `node20` | `node:20-alpine` | `node` |
| `node20-serve` | `node:20-alpine` | `npx serve -s` |

## 可用工具（1 个）

### `compileAndDeploy`

一次调用完成端到端编译部署。

**参数**（以 Map 形式传入）：

| 参数 | 必填 | 说明 |
|------|------|------|
| `gitUrl` | ✅ | Git 仓库 URL |
| `port` | ✅ | 宿主机对外端口 |
| `containerPort` | ✅ | 容器内应用端口 |
| `branch` | | 克隆分支（默认远程 HEAD） |
| `subDir` | | 多模块仓库的子目录 |
| `buildTool` | | 构建栈：`maven` / `npm` / `npm-frontend` / `pip` |
| `baseImage` | | 基础镜像（别名或完整镜像名） |
| `runCommand` | | 覆盖容器启动命令 |
| `imageName` | | Docker 镜像名（省略则自动生成） |
| `containerName` | | 容器名（省略则自动生成） |
| `healthPath` | | 健康检查路径（默认 `/`） |
| `gitUsername` | | 私有仓库 Git 用户名 |
| `gitPassword` | | 私有仓库 Git 密码/令牌 |

### 自动检测

未指定 `buildTool` 时，根据标记文件自动检测：

| 标记文件 | 检测到的构建栈 |
|----------|--------------|
| `pom.xml` | `maven` |
| `package.json` | `npm`（后端） |
| `requirements.txt` / `pyproject.toml` | `pip` |

### 示例

```json
{
 "gitUrl": "https://github.com/example/spring-boot-app.git",
 "port": 8080,
 "containerPort": 8080,
 "buildTool": "maven",
 "baseImage": "java17"
}
```

## 流水线步骤

1. **克隆** — JGit 克隆，支持认证
2. **解析** — 自动检测项目结构（单模块/多模块）
3. **构建** — 执行构建工具（mvn package / npm ci + build / pip install）
4. **Dockerfile** — 根据镜像模板生成 Dockerfile
5. **Docker 构建** — 构建 Docker 镜像
6. **Docker 运行** — 启动容器，映射端口
7. **健康检查** — HTTP 轮询直到就绪或超时

## 关联项目

属于 [Spring AI LoomAgent 灵梭](https://github.com/wb04307201/spring-ai-loom-agent) 生态：

| MCP 服务 | 说明 |
|----------|------|
| [loom-file-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-file-mcp) | 文件系统操作（14 个工具） |
| [loom-git-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-git-mcp) | 基于 JGit 的 Git 操作（14 个工具） |
| [loom-maven-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-maven-mcp) | Maven 构建操作（6 个工具） |

## 依赖

- `loom-compile-core` — 核心编译部署流水线（无 Spring 依赖）
- `loom-process-core` — 跨平台进程管理工具
- `spring-ai-starter-mcp-server` — Spring AI MCP 服务端
- `spring-boot-starter` — Spring Boot

## 常见问题

| 问题 | 解决方案 |
|------|----------|
| `basePath` 目录不存在 | 手动创建目录，服务不会自动创建 |
| 文件操作权限不足 | 确保进程用户对 `basePath` 有读写权限 |
| 大文件操作超时 | 增大 `mavenTimeoutMs`、`dockerBuildTimeoutMs` 或 `healthCheckMaxWaitMs` |
| 找不到 jbang | 安装 jbang：https://www.jbang.dev/download/ |
