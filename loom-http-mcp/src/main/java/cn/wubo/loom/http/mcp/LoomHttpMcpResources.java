package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.util.JsonMappers;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpResource;

import java.util.Map;

/**
 * 4 个只读 {@link McpResource} —— LLM 用 {@code resources/read} 主动拉取的 schema 快照。
 *
 * <p>全用单 URI + {@link McpArg} 形式（URI 固定,无模板变量）。
 *
 * <p><b>Spring AI 2.0.1 框架约束</b>：{@link McpResource} 方法在 URI 无变量时
 * 最多只能有 <strong>一个</strong>{@code String} 或 {@code ReadResourceRequest} 参数
 * （{@code AbstractMcpResourceMethodCallback#validateParametersWithoutUriVariables}）。故：
 * <ul>
 *   <li>{@code system-endpoints} 的可选过滤（{@code tag} / {@code source}）合并进
 *       {@code system} 参数：{@code "<systemName>"} 或 {@code "<systemName>|tag=foo,source=manual"}。
 *       单 {@code system} 参数足够覆盖 90% 用例；如需复杂过滤,可走 {@code LoomHttpMcpService.list_endpoints} 工具。</li>
 *   <li>{@code system-endpoint} 原本 3 个参数（{@code system} / {@code method} / {@code path}）
 *       合并为 1 个 JSON 字符串参数 {@code "<system>"} 或
 *       {@code {"system":..., "method":GET, "path":/users/{id}}};非 JSON 单字符串视为纯 system 名,
 *       method/path 强制必填时返回 422。</li>
 * </ul>
 *
 * <p>URI 列表：
 * <ul>
 *   <li>{@code system://list} — 所有已注册系统的索引</li>
 *   <li>{@code system://by-name} — 取单个 system 完整 JSON</li>
 *   <li>{@code system://endpoints} — 系统的合并端点清单（manual + OpenAPI）</li>
 *   <li>{@code system://endpoint} — 单个端点的完整 OpenAPI schema</li>
 * </ul>
 */
public class LoomHttpMcpResources {

    private final HttpEngine engine;

    public LoomHttpMcpResources(HttpEngine engine) {
        this.engine = engine;
    }

    @McpResource(name = "system-list", uri = "system://list",
            description = "所有已注册系统的索引(名字 + 描述 + baseUrl + authProfile)。",
            mimeType = "application/json")
    public String listSystems() {
        return String.join("\n", engine.listSystems());
    }

    @McpResource(name = "system", uri = "system://by-name",
            description = "取一个已注册系统的完整 JSON 配置(name / baseUrl / authProfile / openapi / endpoints)。",
            mimeType = "application/json")
    public String readSystem(
            @McpArg(name = "system", description = "系统名", required = true) String system) {
        return JsonMappers.toJson(engine.getSystem(system));
    }

    @McpResource(name = "system-endpoints", uri = "system://endpoints",
            description = "一个系统合并后的端点列表(manual + OpenAPI)。"
                    + "system 单值时全量;带过滤时用 systemName|tag=foo,source=manual 形式;复杂过滤走 list_endpoints 工具。",
            mimeType = "application/json")
    public String listEndpoints(
            @McpArg(name = "system",
                    description = "系统名,或 systemName|tag=foo,source=manual 复合形式",
                    required = true) String system) {
        String tag = null;
        String source = null;
        String systemName = system;
        int pipe = system.indexOf('|');
        if (pipe >= 0) {
            systemName = system.substring(0, pipe);
            String filterStr = system.substring(pipe + 1);
            if (filterStr != null && !filterStr.isBlank()) {
                for (String kv : filterStr.split(",")) {
                    int eq = kv.indexOf('=');
                    if (eq < 0) continue;
                    String k = kv.substring(0, eq).trim();
                    String v = kv.substring(eq + 1).trim();
                    if ("tag".equals(k)) tag = v;
                    else if ("source".equals(k)) source = v;
                }
            }
        }
        return engine.listEndpoints(systemName, tag, source);
    }

    @McpResource(name = "system-endpoint", uri = "system://endpoint",
            description = "单个端点的完整 OpenAPI schema。"
                    + "key 是 JSON {\"system\":..., \"method\":GET, \"path\":/users/{id}} 三个键必填。",
            mimeType = "application/json")
    public String getEndpoint(
            @McpArg(name = "key",
                    description = "JSON {\"system\":..., \"method\":..., \"path\":...}",
                    required = true) String key) {
        String system;
        String method;
        String path;
        try {
            Map<String, String> args = JsonMappers.parse(key, Map.class);
            if (args == null) {
                return JsonMappers.toJson(Map.of("error", "InvalidArg",
                        "message", "key must be a JSON object {system,method,path}"));
            }
            system = args.get("system");
            method = args.get("method");
            path = args.get("path");
        } catch (Exception ex) {
            return JsonMappers.toJson(Map.of("error", "InvalidArg",
                    "message", "key must be a JSON object {system,method,path}: " + ex.getMessage()));
        }
        if (system == null || method == null || path == null) {
            return JsonMappers.toJson(Map.of("error", "MissingArg",
                    "message", "key must include system, method, path"));
        }
        return engine.getEndpoint(system, method, path);
    }
}
