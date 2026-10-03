# Loom HTTP MCP Server

A standalone MCP server that exposes HTTP call capability as AI-callable tools
and resources. The server wraps [loom-http-core](../loom-http-core) — a pure
Java engine with no Spring dependency — behind an MCP transport.

## Quick Start

### stdio Mode (jbang)

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

## Building from Source

```bash
cd loom-http-mcp
mvn clean package -DskipTests
java -jar target/loom-http-mcp-1.0-SNAPSHOT.jar
```

The server starts in stdio mode by default. For SSE (HTTP) mode, add `--spring.main.web-application-type=servlet` and configure `server.port`.

## Configuration

All properties under `loom.http.mcp`:

| Property | Default | Description |
|----------|---------|-------------|
| `basePath` | `~/.loom/http-mcp` | Storage root for profiles / systems / history JSON. **Deliberately not `~/.loom/mcp/`** — see Security. |
| `failClosed` | `false` | If true, an empty `allowedDomains` rejects every request (internal-tool mode uses true; jar defaults to false to match http-mcp 1.1.1 behavior). |
| `autoReload` | `true` | Enable FileWatcher to detect on-disk profile / system changes and push `resources/list_changed` to connected clients. |

## Available Tools (19)

### Invocation (2)
| Tool | Description |
|------|-------------|
| `invoke_endpoint` | Call an HTTP endpoint on a registered system. 16-step pipeline: whitelist → path templating → header resolution → request → large-response file spill → assertions → JSONPath extraction → masking → history. |
| `http_batch` | Concurrent batch of `invoke_endpoint` calls. `concurrency` default 5, `failPolicy: continue / stopOnFirst`. |

### HTTP (4)
| Tool | Description |
|------|-------------|
| `http_get` / `http_post` / `http_put` / `http_delete` | Generic HTTP verbs. Synthesizes a `_ad_hoc` system record per call; routes through the same 16-step pipeline. |

### Profile Management (3)
| Tool | Description |
|------|-------------|
| `add_profile` / `update_profile` / `remove_profile` | CRUD for authentication profiles (Bearer / Basic / apiKey-header / apiKey-query). |

### System Management (4)
| Tool | Description |
|------|-------------|
| `register_system` / `update_system` / `remove_system` / `refresh_system` | CRUD for system records; refresh fetches OpenAPI metadata. |

### Endpoint Management (5)
| Tool | Description |
|------|-------------|
| `add_endpoint` / `update_endpoint` / `remove_endpoint` / `list_endpoints` / `get_endpoint` | Manual endpoint definitions on top of OpenAPI. |

### History (1)
| Tool | Description |
|------|-------------|
| `get_request_history` | Query request history JSONL. `system` omitted = all systems; `limit` default 50, max 500; `statusFilter: succeeded / failed / all`. |

## Available Resources (4)

All resources use single-URI + `@McpArg` form (Spring AI 2.x `@McpResource` does not bind URI template variables).

| URI | Parameters | Description |
|-----|------------|-------------|
| `system://list` | (none) | Index of all registered systems |
| `system://by-name` | `system` | One system JSON |
| `system://endpoints` | `system`, `tag?`, `source?` | Merged endpoints for a system |
| `system://endpoint` | `system`, `method`, `path` | Full schema for one endpoint |

## Security

- **Profiles contain plaintext credentials** (`auth.token` / `auth.password` / `auth.apiKey`).
  They live under `basePath/profiles/`. **By default `basePath = ~/.loom/http-mcp/`** —
  deliberately not `~/.loom/mcp/` (the shared MCP sandbox), to prevent loom-file-mcp's
  universal `tool_file` tools from reading credentials by accident.
- Whitelist enforcement happens **before** the request is sent (spec §4.4), not after
  filtering the response.
- `failClosed=true` flips blank-whitelist from fail-open (jar default) to fail-closed
  (internal-tool mode). Set it for stricter deployments.
- Sensitive headers / body fields are masked in history JSONL via `SensitiveFieldMasker`.

## Related Projects

Part of the [Spring AI LoomAgent](https://github.com/wb04307201/spring-ai-loom-agent) ecosystem:

| MCP Server | Description |
|------------|-------------|
| [loom-file-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-file-mcp) | File operations (14) |
| [loom-git-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-git-mcp) | Git via JGit (28) |
| [loom-maven-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-maven-mcp) | Maven build (6) |
| [loom-compile-mcp](https://github.com/wb04307201/spring-ai-loom-agent/tree/main/loom-compile-mcp) | End-to-end deploy (1) |

## Dependencies

- `loom-http-core` — pure-Java engine (no Spring)
- `spring-ai-starter-mcp-server` — Spring AI MCP server
- `spring-boot-starter` — Spring Boot

## Troubleshooting

| Issue | Solution |
|-------|----------|
| `basePath` not writable | `chmod` the path or set `loom.http.mcp.basePath` |
| Empty tools list after start | Ensure `spring.ai.mcp.server.annotation-scanner.enabled=true` (default in our yml) |
| Resources show stale data | FileWatcher should push `list_changed` on fs change; check `loom.http.mcp.autoReload=true` |
| Credentials visible in `~/.loom/mcp/` | **This is a security incident** — see Security; the default `basePath` is `~/.loom/http-mcp/` precisely to prevent this |
