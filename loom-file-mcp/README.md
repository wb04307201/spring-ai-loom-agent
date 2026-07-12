# Loom File MCP Server

A standalone MCP (Model Context Protocol) server that exposes file system operations as AI-callable tools.

## Quick Start

### stdio Mode (jbang)

Run the MCP server without local installation via [jbang](https://www.jbang.dev/). Configure in your MCP client (Claude Desktop, Cursor, etc.):

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

## Building from Source

```bash
cd loom-file-mcp
mvn clean package -DskipTests
java -jar target/loom-file-mcp-1.0-SNAPSHOT.jar
```

The server starts in stdio mode by default. For SSE (HTTP) mode, add `--spring.main.web-application-type=servlet` and configure `server.port`.

## Configuration

All properties under `loom.file.mcp`:

| Property | Default | Description |
|----------|---------|-------------|
| `basePath` | `.local/file` | Root directory for all file operations |
| `maxFileSize` | `10485760` (10 MB) | Max text file size (bytes) |
| `maxMediaSize` | `52428800` (50 MB) | Max media file size (bytes) |
| `maxWalkDepth` | `5` | Max directory traversal depth |
| `maxWalkEntries` | `1000` | Max entries per directory traversal |
| `maxSearchResults` | `100` | Max search results |
| `excludedDirs` | `.git, node_modules, .idea, target, .vscode` | Directories to skip |
| `deleteConfirmToken` | `I_CONFIRM_DELETE` | Confirmation token for delete operations |

## Available Tools (16)

### Read Operations
| Tool | Description |
|------|-------------|
| `readTextFile` | Read file as text. Supports `head`/`tail` for partial reads |
| `readMediaFile` | Read image/audio file, returns base64 + MIME type |
| `readMultipleFiles` | Batch read multiple files in one call |

### Write / Edit Operations
| Tool | Description |
|------|-------------|
| `writeFile` | Create or overwrite a file |
| `editFile` | Line-based edits with oldText/newText replacement, returns git-style diff |

### Directory Operations
| Tool | Description |
|------|-------------|
| `createDirectory` | Create directory (nested) |
| `moveFile` | Move or rename files |
| `searchFiles` | Glob-pattern recursive search |
| `listAllowedDirectories` | Show the base directory (absolute path) |
| `listDirectory` | List directory contents with optional depth |
| `listDirectoryWithSizes` | List contents with file sizes |
| `directoryTree` | JSON-formatted recursive tree view |
| `getFileInfo` | File/directory metadata (size, times, line count) |

### Delete
| Tool | Description |
|------|-------------|
| `deleteFileOrDirectory` | Delete file or directory (recursive). Requires `confirm` token |

## Security

- All paths are resolved relative to `basePath` — absolute paths and `..` traversal are rejected
- Symlink escape prevention via `PathSecurityUtils`
- Delete requires explicit confirmation token to prevent accidental data loss
- File size limits prevent OOM on large reads/writes

## Related Projects

Part of the [Spring AI LoomAgent](https://github.com/wb04307201/spring-ai-loom-agent) ecosystem:

| MCP Server | Description |
|------------|-------------|
| [loom-git-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-git-mcp) | Git operations via JGit (28 tools) |
| [loom-maven-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-maven-mcp) | Maven build operations (6 tools) |
| [loom-compile-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-compile-mcp) | End-to-end deploy pipeline (1 tool) |

## Dependencies

- `loom-file-core` — Core file operations (no Spring dependency)
- `spring-ai-starter-mcp-server` — Spring AI MCP Server
- `spring-boot-starter` — Spring Boot

## Troubleshooting

| Issue | Solution |
|-------|----------|
| `basePath` does not exist | Create the directory manually; the server does not auto-create it |
| Permission denied on file operations | Ensure the process user has read/write access to `basePath` |
| Timeout on large files | Increase `maxFileSize` or `maxMediaSize` |
| jbang not found | Install jbang: https://www.jbang.dev/download/ |
