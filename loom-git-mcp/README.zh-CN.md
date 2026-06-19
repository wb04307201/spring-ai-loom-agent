# Loom Git MCP 服务

独立的 MCP（模型上下文协议）服务，将 Git 操作暴露为 AI 可调用的工具。基于 JGit 实现所有核心操作。

## 快速启动

### stdio 模式（jbang）

使用 [jbang](https://www.jbang.dev/) 无需本地安装即可运行 MCP 服务器，在 MCP 客户端（Claude Desktop、Cursor 等）中配置如下：

```json
{
  "mcpServers": {
    "loom-git-mcp": {
      "command": "jbang",
      "args": [
        "io.github.wb04307201:loom-git-mcp:1.0-SNAPSHOT",
        "--loom.git.mcp.basePath=/workspace",
        "--loom.git.mcp.gitUsername=your-username",
        "--loom.git.mcp.gitToken=ghp_your_token_here"
      ]
    }
  }
}
```

## 本地构建

```bash
cd loom-git-mcp
mvn clean package -DskipTests
java -jar target/loom-git-mcp-1.0-SNAPSHOT.jar
```

默认以 stdio 模式启动。如需 SSE（HTTP）模式，添加 `--spring.main.web-application-type=servlet` 并配置 `server.port`。

## 配置项

所有配置在 `loom.git.mcp` 前缀下：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `basePath` | `.local/file` | Git 操作的根目录 |
| `gitUsername` | `""` | Git 用户名（用于远程认证） |
| `gitToken` | `""` | Git 令牌/密码（用于远程认证） |
| `remoteTimeoutSeconds` | `60` | 远程操作超时时间（秒） |

## 可用工具（14 个）

### 仓库生命周期
| 工具 | 说明 |
|------|------|
| `gitInit` | 初始化新的 Git 仓库 |
| `gitClone` | 从远程 URL 克隆（支持浅克隆/bare/mirror） |

### 基本操作
| 工具 | 说明 |
|------|------|
| `gitStatus` | 查看工作区状态（已暂存/未暂存/未跟踪） |
| `gitAdd` | 暂存文件（支持 `paths` 过滤、`--update`、`--all`、`--force`） |
| `gitCommit` | 创建提交（支持 amend、allow-empty、no-verify） |
| `gitDiff` | 查看差异（支持 staged、name-only、stat、上下文行数） |

### 历史与检查
| 工具 | 说明 |
|------|------|
| `gitLog` | 查看提交历史（支持按作者、日期、消息、文件过滤） |
| `gitBlame` | 逐行查看修改历史（支持行范围） |

### 分支与合并
| 工具 | 说明 |
|------|------|
| `gitBranch` | 管理分支（列出/创建/删除/重命名/显示当前） |
| `gitCheckout` | 切换分支或恢复文件（设 `createBranch=true` 创建分支，传 `paths` 恢复指定文件） |
| `gitMerge` | 合并分支（支持 squash、no-ff、abort） |

### 远程操作
| 工具 | 说明 |
|------|------|
| `gitPull` | 拉取更新（支持 merge 或 rebase 策略） |
| `gitPush` | 推送（支持 force、tags、dry-run、删除远程分支） |

### 工作目录
| 工具 | 说明 |
|------|------|
| `gitSetWorkingDir` | 设置工作目录，返回绝对路径供后续操作使用 |

## 使用模式

```
1. 调用 gitSetWorkingDir(path) → 获取绝对工作目录路径
2. 将返回的路径作为 workingDir 传入后续所有 git 操作
```

## 关联项目

属于 [Spring AI LoomAgent 灵梭](https://github.com/wb04307201/spring-ai-loom-agent) 生态：

| MCP 服务 | 说明 |
|----------|------|
| [loom-file-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-file-mcp) | 文件系统操作（14 个工具） |
| [loom-maven-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-maven-mcp) | Maven 构建操作（6 个工具） |
| [loom-compile-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-compile-mcp) | 端到端部署流水线（1 个工具） |

## 依赖

- `loom-git-core` — 核心 Git 操作库，基于 JGit（无 Spring 依赖）
- `spring-ai-starter-mcp-server` — Spring AI MCP 服务端
- `spring-boot-starter` — Spring Boot

## 常见问题

| 问题 | 解决方案 |
|------|----------|
| `basePath` 目录不存在 | 手动创建目录，服务不会自动创建 |
| 文件操作权限不足 | 确保进程用户对 `basePath` 有读写权限 |
| 大文件操作超时 | 增大 `defaultTimeoutMs` |
| 找不到 jbang | 安装 jbang：https://www.jbang.dev/download/ |
