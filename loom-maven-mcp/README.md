# Loom Maven MCP Server

A standalone MCP (Model Context Protocol) server that exposes Maven build operations as AI-callable tools. Executes Maven as a subprocess with timeout control and process tree management.

## Quick Start

### stdio Mode (jbang)

Run the MCP server without local installation via [jbang](https://www.jbang.dev/). Configure in your MCP client (Claude Desktop, Cursor, etc.):

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

## Building from Source

```bash
cd loom-maven-mcp
mvn clean package -DskipTests
java -jar target/loom-maven-mcp-1.0-SNAPSHOT.jar
```

The server starts in stdio mode by default. For SSE (HTTP) mode, add `--spring.main.web-application-type=servlet` and configure `server.port`.

## Configuration

All properties under `loom.maven.mcp`:

| Property | Default | Description |
|----------|---------|-------------|
| `basePath` | `.local/file` | Root directory for resolving relative paths |
| `mavenHome` | *(auto-detect)* | Maven installation directory |
| `localRepository` | *(default ~/.m2)* | Local Maven repository path |
| `maxOutputLines` | `200` | Max lines of Maven output returned |
| `defaultTimeoutMs` | `300000` (5 min) | Default execution timeout (milliseconds) |

## Available Tools (6)

| Tool | Description | Equivalent Command |
|------|-------------|-------------------|
| `mavenExecute` | Generic Maven execution with arbitrary goals | `mvn <goals...>` |
| `mavenBuild` | Compile the project | `mvn compile` |
| `mavenPackage` | Package the project (skips tests by default) | `mvn package -DskipTests` |
| `mavenTest` | Run unit tests | `mvn test` |
| `mavenDependencyTree` | View dependency tree | `mvn dependency:tree` |
| `mavenValidate` | Validate project structure | `mvn validate` |

### mavenExecute Parameters

The generic entry point accepts:

| Parameter | Required | Description |
|-----------|----------|-------------|
| `goals` | ✅ | List of Maven goals, e.g. `["clean", "package"]` |
| `pomPath` | | Path to pom.xml (relative or absolute) |
| `workingDir` | | Working directory (relative or absolute) |
| `properties` | | Maven properties as key-value map |
| `timeoutMs` | | Execution timeout in milliseconds |

### Other Tool Parameters

| Tool | Parameter | Required | Description |
|------|-----------|----------|-------------|
| `mavenBuild` | `skipTests` | | Skip tests during compilation |
| `mavenPackage` | `skipTests` | | Skip tests during packaging (default: true) |
| `mavenTest` | `testPattern` | | Test class pattern, e.g. `*ServiceTest` |
| `mavenDependencyTree` | `includeScope` | | Filter by scope: `compile`/`runtime`/`test`/`provided` |

## Features

- **Timeout control** — kills entire Maven process tree on timeout (including child JVM)
- **Output truncation** — limits output to `maxOutputLines` to prevent overwhelming the LLM
- **Cross-platform** — handles Windows `mvn.cmd` and Unix `mvn` automatically
- **Process tree killing** — on Windows uses `taskkill /F /T`, on Unix uses recursive `kill -9`

## Related Projects

Part of the [Spring AI LoomAgent](https://github.com/wb04307201/spring-ai-loom-agent) ecosystem:

| MCP Server | Description |
|------------|-------------|
| [loom-file-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-file-mcp) | File system operations (14 tools) |
| [loom-git-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-git-mcp) | Git operations via JGit (14 tools) |
| [loom-compile-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-compile-mcp) | End-to-end deploy pipeline (1 tool) |

## Dependencies

- `loom-maven-core` — Core Maven execution logic (no Spring dependency)
- `loom-process-core` — Cross-platform process management utilities
- `spring-ai-starter-mcp-server` — Spring AI MCP Server
- `spring-boot-starter` — Spring Boot

## Troubleshooting

| Issue | Solution |
|-------|----------|
| `basePath` does not exist | Create the directory manually; the server does not auto-create it |
| Permission denied on file operations | Ensure the process user has read/write access to `basePath` |
| Timeout on large files | Increase `maxOutputLines` or `defaultTimeoutMs` |
| jbang not found | Install jbang: https://www.jbang.dev/download/ |
