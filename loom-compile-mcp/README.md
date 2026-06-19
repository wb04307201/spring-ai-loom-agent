# Loom Compile MCP Server

A standalone MCP (Model Context Protocol) server that provides end-to-end compile and deploy as a single AI-callable tool. Orchestrates the entire pipeline: git clone → build → docker build → docker run → health check.

## Quick Start

### stdio Mode (jbang)

Run the MCP server without local installation via [jbang](https://www.jbang.dev/). Configure in your MCP client (Claude Desktop, Cursor, etc.):

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

## Building from Source

```bash
cd loom-compile-mcp
mvn clean package -DskipTests
java -jar target/loom-compile-mcp-1.0-SNAPSHOT.jar
```

The server starts in stdio mode by default. For SSE (HTTP) mode, add `--spring.main.web-application-type=servlet` and configure `server.port`.

## Configuration

All properties under `loom.compile.mcp`:

| Property | Default | Description |
|----------|---------|-------------|
| `basePath` | `.local/file` | Root directory for workspaces |
| `mavenHome` | *(auto-detect)* | Maven installation directory |
| `mavenTimeoutMs` | `600000` (10 min) | Maven build timeout |
| `dockerBuildTimeoutMs` | `600000` (10 min) | Docker build timeout |
| `dockerRunTimeoutMs` | `60000` (1 min) | Docker run timeout |
| `healthCheckMaxWaitMs` | `60000` (1 min) | Health check max wait |
| `healthCheckIntervalMs` | `2000` (2 sec) | Health check polling interval |
| `keepWorkspace` | `false` | Keep workspace dir after deploy (for debugging) |
| `extraRunArgs` | `[]` | Extra `docker run` arguments |
| `imageTemplates` | *(see below)* | Base image templates |

### Default Image Templates

| Alias | Base Image | Default Command |
|-------|-----------|-----------------|
| `java17` | `eclipse-temurin:17-jre` | `java -jar` |
| `java21` | `eclipse-temurin:21-jre` | `java -jar` |
| `nginx` | `nginx:stable-alpine` | *(nginx default)* |
| `python3` | `python:3.11-slim` | `python` |
| `node20` | `node:20-alpine` | `node` |
| `node20-serve` | `node:20-alpine` | `npx serve -s` |

## Available Tool (1)

### `compileAndDeploy`

End-to-end compile and deploy in a single call.

**Parameters** (passed as a Map):

| Parameter | Required | Description |
|-----------|----------|-------------|
| `gitUrl` | ✅ | Git repository URL |
| `port` | ✅ | Host port to expose |
| `containerPort` | ✅ | Container port (app listens on) |
| `branch` | | Branch to clone (default: remote HEAD) |
| `subDir` | | Sub-directory for multi-module repos |
| `buildTool` | | Build stack: `maven` / `npm` / `npm-frontend` / `pip` |
| `baseImage` | | Base image (alias or full name) |
| `runCommand` | | Override container start command |
| `imageName` | | Docker image name (auto-generated if omitted) |
| `containerName` | | Container name (auto-generated if omitted) |
| `healthPath` | | Health check path (default: `/`) |
| `gitUsername` | | Git username for private repos |
| `gitPassword` | | Git password/token for private repos |

### Auto-detection

When `buildTool` is not specified, the tool auto-detects from marker files:

| Marker File | Detected Build Tool |
|-------------|-------------------|
| `pom.xml` | `maven` |
| `package.json` | `npm` (backend) |
| `requirements.txt` / `pyproject.toml` | `pip` |

### Example

```json
{
  "gitUrl": "https://github.com/example/spring-boot-app.git",
  "port": 8080,
  "containerPort": 8080,
  "buildTool": "maven",
  "baseImage": "java17"
}
```

## Pipeline Steps

1. **Clone** — JGit clone with optional credentials
2. **Resolve** — Auto-detect project structure (single/multi-module)
3. **Build** — Run build tool (mvn package / npm ci + build / pip install)
4. **Dockerfile** — Generate Dockerfile from image template
5. **Docker Build** — Build Docker image
6. **Docker Run** — Start container with port mapping
7. **Health Check** — HTTP polling until ready or timeout

## Related Projects

Part of the [Spring AI LoomAgent](https://github.com/wb04307201/spring-ai-loom-agent) ecosystem:

| MCP Server | Description |
|------------|-------------|
| [loom-file-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-file-mcp) | File system operations (14 tools) |
| [loom-git-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-git-mcp) | Git operations via JGit (14 tools) |
| [loom-maven-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-maven-mcp) | Maven build operations (6 tools) |

## Dependencies

- `loom-compile-core` — Core compile & deploy pipeline (no Spring dependency)
- `loom-process-core` — Cross-platform process management
- `spring-ai-starter-mcp-server` — Spring AI MCP Server
- `spring-boot-starter` — Spring Boot

## Troubleshooting

| Issue | Solution |
|-------|----------|
| `basePath` does not exist | Create the directory manually; the server does not auto-create it |
| Permission denied on file operations | Ensure the process user has read/write access to `basePath` |
| Timeout on large files | Increase `mavenTimeoutMs`, `dockerBuildTimeoutMs`, or `healthCheckMaxWaitMs` |
| jbang not found | Install jbang: https://www.jbang.dev/download/ |
