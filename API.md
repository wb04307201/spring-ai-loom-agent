# Spring AI LoomAgent API Documentation

> **Base URL**: `http://localhost:8089` (default port for the test environment)
> **Version**: 1.0.0
> **Authentication**: The project uses a **BFF (Backend-For-Frontend) + HttpOnly Cookie** auth model. After login, the server sets a `loom-agent-session` cookie via `Set-Cookie` header. The browser automatically includes this cookie in subsequent requests. No token storage or manual header management is required.

---

## 10. Configuration Properties

### 10.x End-to-End Deployment Configuration (`ICompileAndDeployTool`)

`ICompileAndDeployTool` performs the full deployment pipeline in a single LLM tool call: `git clone → mvn package → docker build → docker run → health check`. All settings live under `spring.ai.loom.agent.compile.*`.

| Property                                       | Type                      | Default                                            | Description                                                                                                                                          |
|------------------------------------------------|---------------------------|----------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| `spring.ai.loom.agent.compile.enabled`         | boolean                   | `true`                                             | Whether to enable the end-to-end deployment tool. Always on by default — this is the recommended entry point for deployment scenarios.              |
| `spring.ai.loom.agent.compile.baseImage`       | string                    | `eclipse-temurin:17-jre-alpine`                    | Fallback Docker base image used when the `baseImage` tool parameter is empty or unrecognized.                                                         |
| `spring.ai.loom.agent.compile.imageTemplates`  | map<string, ImageTemplate> | `java17` / `java21` / `nginx` / `python3`          | Predefined base-image aliases (`ImageTemplate { image, command[] }`), selectable via the `baseImage` tool parameter.                                |

**Base image templates** (optional): Built-in `java17` / `java21` / `nginx` / `python3` templates, override or add new ones via yml. Pass the template alias to the tool's `baseImage` parameter to select it; pass a full image name (e.g. `openjdk:17-slim`) to use it directly, with `command` falling back to java17.

```yaml
spring:
  ai:
    loom:
      agent:
        compile:
          base-image: eclipse-temurin:17-jre-alpine  # fallback
          image-templates:
            java17:
              image: eclipse-temurin:17-jre-alpine
              command: [java, -jar, app.jar]
            nginx:
              image: nginx:1.27-alpine
              command: [nginx, -g, "daemon off;"]
```

Example tool parameters:

```json
{
  "gitUrl": "https://gitee.com/wb04307201/sql-forge-demo.git",
  "port": 8081,
  "baseImage": "java17",
  "healthPath": "sql-forge-demo"
}
```
