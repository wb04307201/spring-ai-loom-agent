# Loom Git MCP Server

A standalone MCP (Model Context Protocol) server that exposes Git operations as AI-callable tools. Based on JGit for all core operations.

## Quick Start

### stdio Mode (jbang)

Run the MCP server without local installation via [jbang](https://www.jbang.dev/). Configure in your MCP client (Claude Desktop, Cursor, etc.):

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

## Building from Source

```bash
cd loom-git-mcp
mvn clean package -DskipTests
java -jar target/loom-git-mcp-1.0-SNAPSHOT.jar
```

The server starts in stdio mode by default. For SSE (HTTP) mode, add `--spring.main.web-application-type=servlet` and configure `server.port`.

## Configuration

All properties under `loom.git.mcp`:

| Property | Default | Description |
|----------|---------|-------------|
| `basePath` | `.local/file` | Root directory for git operations |
| `gitUsername` | `""` | Git username for remote authentication |
| `gitToken` | `""` | Git token/password for remote authentication |
| `remoteTimeoutSeconds` | `60` | Timeout for clone/pull/fetch/push (seconds) |

## Available Tools (14)

### Repository Lifecycle
| Tool | Description |
|------|-------------|
| `gitInit` | Initialize a new Git repository |
| `gitClone` | Clone from remote URL (supports shallow/bare/mirror) |

### Basic Operations
| Tool | Description |
|------|-------------|
| `gitStatus` | Show working tree status (staged/unstaged/untracked) |
| `gitAdd` | Stage files (supports `paths` filter, `--update`, `--all`, `--force`) |
| `gitCommit` | Create commit (supports amend, allow-empty, no-verify) |
| `gitDiff` | Show diffs (supports staged, name-only, stat, context lines) |

### History & Inspection
| Tool | Description |
|------|-------------|
| `gitLog` | View commit history with filtering (author, date, grep, file) |
| `gitBlame` | Line-by-line authorship with optional line range |

### Branch & Merge
| Tool | Description |
|------|-------------|
| `gitBranch` | Manage branches (list/create/delete/rename/show-current) |
| `gitCheckout` | Switch branches or restore files (set `createBranch=true` to create, pass `paths` to restore specific files) |
| `gitMerge` | Merge branches (supports squash, no-ff, abort) |

### Remote Operations
| Tool | Description |
|------|-------------|
| `gitPull` | Pull with merge or rebase strategy |
| `gitPush` | Push with force, tags, dry-run, delete remote branch |

### Working Directory
| Tool | Description |
|------|-------------|
| `gitSetWorkingDir` | Set working directory, returns absolute path for subsequent calls |

## Usage Pattern

```
1. Call gitSetWorkingDir(path) → get absolute working directory path
2. Pass the returned path as workingDir to all subsequent git operations
```

## Related Projects

Part of the [Spring AI LoomAgent](https://github.com/wb04307201/spring-ai-loom-agent) ecosystem:

| MCP Server | Description |
|------------|-------------|
| [loom-file-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-file-mcp) | File system operations (14 tools) |
| [loom-maven-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-maven-mcp) | Maven build operations (6 tools) |
| [loom-compile-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-compile-mcp) | End-to-end deploy pipeline (1 tool) |

## Dependencies

- `loom-git-core` — Core Git operations based on JGit (no Spring dependency)
- `spring-ai-starter-mcp-server` — Spring AI MCP Server
- `spring-boot-starter` — Spring Boot

## Troubleshooting

| Issue | Solution |
|-------|----------|
| `basePath` does not exist | Create the directory manually; the server does not auto-create it |
| Permission denied on file operations | Ensure the process user has read/write access to `basePath` |
| Timeout on large files | Increase `defaultTimeoutMs` |
| jbang not found | Install jbang: https://www.jbang.dev/download/ |
