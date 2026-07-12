# Loom File MCP 服务

独立的 MCP（模型上下文协议）服务，将文件系统操作暴露为 AI 可调用的工具。

## 快速启动

### stdio 模式（jbang）

使用 [jbang](https://www.jbang.dev/) 无需本地安装即可运行 MCP 服务器，在 MCP 客户端（Claude Desktop、Cursor 等）中配置如下：

```json
{
  "mcpServers": {
    "loom-file-mcp": {
      "command": "jbang",
      "args": [
        "io.github.wb04307201:loom-file-mcp:1.0-SNAPSHOT",
        "--loom.file.mcp.basePath=/workspace",
        "--loom.file.mcp.maxFileSize=10485760",
        "--loom.file.mcp.deleteConfirmToken=I_CONFIRM_DELETE"
      ]
    }
  }
}
```

## 本地构建

```bash
cd loom-file-mcp
mvn clean package -DskipTests
java -jar target/loom-file-mcp-1.0-SNAPSHOT.jar
```

默认以 stdio 模式启动。如需 SSE（HTTP）模式，添加 `--spring.main.web-application-type=servlet` 并配置 `server.port`。

## 配置项

所有配置在 `loom.file.mcp` 前缀下：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `basePath` | `.local/file` | 文件操作的根目录 |
| `maxFileSize` | `10485760`（10 MB） | 文本文件最大大小（字节） |
| `maxMediaSize` | `52428800`（50 MB） | 媒体文件最大大小（字节） |
| `maxWalkDepth` | `5` | 目录遍历最大深度 |
| `maxWalkEntries` | `1000` | 单次遍历最大条目数 |
| `maxSearchResults` | `100` | 搜索结果最大数量 |
| `excludedDirs` | `.git, node_modules, .idea, target, .vscode` | 遍历时跳过的目录 |
| `deleteConfirmToken` | `I_CONFIRM_DELETE` | 删除操作的确认令牌 |

## 可用工具（14 个）

### 读取操作
| 工具 | 说明 |
|------|------|
| `readTextFile` | 读取文件文本内容，支持 `head`/`tail` 局部读取 |
| `readMediaFile` | 读取图片/音频文件，返回 base64 编码 + MIME 类型 |
| `readMultipleFiles` | 批量读取多个文件 |

### 写入 / 编辑操作
| 工具 | 说明 |
|------|------|
| `writeFile` | 创建或覆盖文件 |
| `editFile` | 基于文本替换的行编辑，返回 git 风格 diff |

### 目录操作
| 工具 | 说明 |
|------|------|
| `createDirectory` | 创建目录（支持多级嵌套） |
| `moveFile` | 移动或重命名文件 |
| `searchFiles` | glob 模式递归搜索文件 |
| `listAllowedDirectories` | 显示基础目录的绝对路径 |
| `listDirectory` | 列出目录内容，支持递归深度 |
| `listDirectoryWithSizes` | 列出目录内容及文件大小 |
| `directoryTree` | JSON 格式递归目录树 |
| `getFileInfo` | 文件/目录元数据（大小、时间、行数） |

### 删除
| 工具 | 说明 |
|------|------|
| `deleteFileOrDirectory` | 删除文件或目录（递归），需要传入确认令牌 |

## 安全特性

- 所有路径相对于 `basePath` 解析 — 拒绝绝对路径和 `..` 穿越
- `PathSecurityUtils` 防止 symlink 越界
- 删除操作需要显式确认令牌，防止误删
- 文件大小限制防止大文件导致 OOM

## 关联项目

属于 [Spring AI LoomAgent 灵梭](https://github.com/wb04307201/spring-ai-loom-agent) 生态：

| MCP 服务 | 说明 |
|----------|------|
| [loom-git-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-git-mcp) | 基于 JGit 的 Git 操作（28 个工具） |
| [loom-maven-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-maven-mcp) | Maven 构建操作（6 个工具） |
| [loom-compile-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-compile-mcp) | 端到端部署流水线（1 个工具） |

## 依赖

- `loom-file-core` — 核心文件操作库（无 Spring 依赖）
- `spring-ai-starter-mcp-server` — Spring AI MCP 服务端
- `spring-boot-starter` — Spring Boot

## 常见问题

| 问题 | 解决方案 |
|------|----------|
| `basePath` 目录不存在 | 手动创建目录，服务不会自动创建 |
| 文件操作权限不足 | 确保进程用户对 `basePath` 有读写权限 |
| 大文件操作超时 | 增大 `maxFileSize` 或 `maxMediaSize` |
| 找不到 jbang | 安装 jbang：https://www.jbang.dev/download/ |
