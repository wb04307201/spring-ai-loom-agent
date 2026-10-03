package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.invoke.InvokeRequest;
import cn.wubo.loom.http.core.invoke.BatchRequest;
import cn.wubo.loom.http.core.profile.Profile;
import cn.wubo.loom.http.core.profile.Profile.Auth;
import cn.wubo.loom.http.core.profile.Profile.Retry;
import cn.wubo.loom.http.core.system.System;
import cn.wubo.loom.http.core.util.JsonMappers;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * loom-http-mcp 暴露给 LLM 的注册 / 管理 / 读用面工具(15 个 @McpTool, snake_case)。
 * <p>通用 HTTP(4 个 http_*)见 {@link LoomHttpService},那里有 _ad_hoc 合成系统代码。
 */
public class LoomHttpMcpService {

    private final HttpEngine engine;

    public LoomHttpMcpService(HttpEngine engine) {
        this.engine = engine;
    }

    // ==================== 调用面(Task 8 契约:invokeEndpoint / httpBatch) ====================

    @McpTool(name = "invoke_endpoint", description =
            "调用一个已注册系统上的 HTTP 端点。system / method / path 必填,其余经 16 步流程 "
            + "(白名单→模板→占位 header→请求→大响应落盘→断言→JSONPath 提取→脱敏→历史)。"
            + "responseMode: summary / full / file。")
    public String invokeEndpoint(
            @ToolParam(description = "已注册系统名") String system,
            @ToolParam(description = "HTTP 方法,例 GET") String method,
            @ToolParam(description = "端点路径,支持 {placeholder}") String path,
            @ToolParam(description = "path/query 参数表", required = false) Map<String, Object> params,
            @ToolParam(description = "JSON 请求体", required = false) Map<String, Object> body,
            @ToolParam(description = "额外请求头", required = false) Map<String, String> headers,
            @ToolParam(description = "断言列表 {type,value,...}", required = false) List<Map<String, Object>> assertions,
            @ToolParam(description = "JSONPath 提取 {alias:\"$.x\"}", required = false) Map<String, String> extract,
            @ToolParam(description = "summary / full / file", required = false) String responseMode) {
        InvokeRequest req = new InvokeRequest();
        req.setSystem(system);
        req.setMethod(method);
        req.setPath(path);
        if (params != null) req.setParams(params);
        if (body != null) req.setBody(body);
        if (headers != null) req.setHeaders(headers);
        if (assertions != null) req.setAssertions(assertions);
        if (extract != null) req.setExtract(extract);
        if (responseMode != null) req.setResponseMode(responseMode);
        return engine.invokeEndpoint(req);
    }

    @McpTool(name = "http_batch", description =
            "并发执行一组 HTTP 操作,每项是 invokeEndpoint 入参形状。"
            + "concurrency 默认 5。failPolicy: continue(默认) / stopOnFirst。")
    public String httpBatch(
            @ToolParam(description = "操作列表,每项形如 {system,method,path,...}") List<Map<String, Object>> operations,
            @ToolParam(description = "最大并发 op 数", required = false) Integer concurrency,
            @ToolParam(description = "continue / stopOnFirst", required = false) String failPolicy,
            @ToolParam(description = "响应模式 summary/firstN/full/file", required = false) String responseMode,
            @ToolParam(description = "firstN 时返回条数", required = false) Integer firstN) {
        BatchRequest req = new BatchRequest();
        if (operations != null) req.setOperations(convertOperations(operations));
        if (concurrency != null) req.setConcurrency(concurrency);
        if (failPolicy != null) req.setFailPolicy(failPolicy);
        if (responseMode != null) req.setResponseMode(responseMode);
        if (firstN != null) req.setFirstN(firstN);
        return engine.httpBatch(req);
    }

    /**
     * operations 是 list<Map<String,Object>>(JSON 形态),BatchRequest.operations 期望
     * List<InvokeRequest>。逐条用 Jackson convertValue 桥接,不引入内部字段耦合。
     */
    private static List<InvokeRequest> convertOperations(List<Map<String, Object>> ops) {
        List<InvokeRequest> out = new java.util.ArrayList<>(ops.size());
        for (Map<String, Object> op : ops) {
            out.add(JsonMappers.mapper().convertValue(op, InvokeRequest.class));
        }
        return out;
    }

    // ==================== 注册面:system 4 个 ====================

    @McpTool(name = "refresh_system", description =
            "刷新一个或所有已注册系统的 OpenAPI 元数据。name 为 null 时刷新全部。")
    public String refreshSystem(
            @ToolParam(description = "系统名,省略则刷新全部", required = false) String name) {
        return engine.refreshSystem(name);
    }

    @McpTool(name = "register_system", description =
            "注册一个新系统(system JSON 落盘)。baseUrl 必填,authProfile / openapi / endpoints 可选。")
    public String registerSystem(
            @ToolParam(description = "系统名,必须唯一") String name,
            @ToolParam(description = "文字描述", required = false) String description,
            @ToolParam(description = "上游 API base URL") String baseUrl,
            @ToolParam(description = "绑定的 profile 名", required = false) String authProfile,
            @ToolParam(description = "OpenAPI 源 {source,format,refresh}", required = false) Map<String, Object> openapi,
            @ToolParam(description = "手动端点列表", required = false) List<Map<String, Object>> endpoints) {
        System sys = new System();
        sys.setName(name);
        if (description != null) sys.setDescription(description);
        sys.setBaseUrl(baseUrl);
        if (authProfile != null) sys.setAuthProfile(authProfile);
        // openapi / endpoints 由 Engine.registerSystem 在保存时按各自模型反序列化
        if (openapi != null && !openapi.isEmpty()) {
            sys.setOpenapi(JsonMappers.mapper().convertValue(openapi, System.OpenApi.class));
        }
        if (endpoints != null && !endpoints.isEmpty()) {
            sys.setEndpoints(new java.util.ArrayList<>(
                    JsonMappers.mapper().convertValue(endpoints,
                            JsonMappers.mapper().getTypeFactory().constructCollectionType(
                                    java.util.List.class,
                                    cn.wubo.loom.http.core.endpoint.Endpoint.class))));
        }
        return engine.registerSystem(sys);
    }

    @McpTool(name = "update_system", description =
            "部分更新一个已注册系统。null 字段保留原值;openapi 非 null 则整体替换。")
    public String updateSystem(
            @ToolParam(description = "要更新的系统名") String name,
            @ToolParam(description = "新描述,null 保留原值", required = false) String description,
            @ToolParam(description = "新 baseUrl", required = false) String baseUrl,
            @ToolParam(description = "新 authProfile", required = false) String authProfile,
            @ToolParam(description = "新 openapi 块,整体替换", required = false) Map<String, Object> openapi) {
        // HttpEngine.updateSystem 实际签名是 (String, System patch) — 跟随 Task 1 产物,不做内部字段名耦合。
        System patch = new System();
        if (description != null) patch.setDescription(description);
        if (baseUrl != null) patch.setBaseUrl(baseUrl);
        if (authProfile != null) patch.setAuthProfile(authProfile);
        if (openapi != null && !openapi.isEmpty()) {
            patch.setOpenapi(JsonMappers.mapper().convertValue(openapi, System.OpenApi.class));
        }
        return engine.updateSystem(name, patch);
    }

    @McpTool(name = "remove_system", description =
            "删除系统 JSON;deleteOpenApiCache=true 时同时清 systems/<name>/openapi.json。")
    public String removeSystem(
            @ToolParam(description = "系统名") String name,
            @ToolParam(description = "是否同时删 OpenAPI 缓存", required = false) Boolean deleteOpenApiCache) {
        return engine.removeSystem(name, Boolean.TRUE.equals(deleteOpenApiCache));
    }

    // ==================== 注册面:profile 3 个 ====================

    @McpTool(name = "add_profile", description =
            "新建认证 profile。auth.type 必填(bearer/basic/apiKey-header/apiKey-query);"
            + "allowedDomains 留空表示不施加约束(spec §4.4 第二态)。")
    public String addProfile(
            @ToolParam(description = "profile 名,必须唯一") String name,
            @ToolParam(description = "文字描述", required = false) String description,
            @ToolParam(description = "上游 API base URL", required = false) String baseUrl,
            @ToolParam(description = "认证配置 {type,token|username|password|keyName|value}", required = false) Map<String, Object> auth,
            @ToolParam(description = "每次调用覆盖的 header", required = false) Map<String, String> customHeaders,
            @ToolParam(description = "默认附加 header", required = false) Map<String, String> defaultHeaders,
            @ToolParam(description = "域名白名单,支持通配符", required = false) List<String> allowedDomains,
            @ToolParam(description = "超时 ms,默认 10000", required = false) Long timeoutMs,
            @ToolParam(description = "重试策略 {maxAttempts,backoffMs,retryOn}", required = false) Map<String, Object> retry) {
        Profile p = new Profile();
        p.setName(name);
        if (description != null) p.setDescription(description);
        if (baseUrl != null) p.setBaseUrl(baseUrl);
        // Map -> Auth/Retry 用 JsonMappers.mapper().convertValue(已在 HttpEngine:332 验证可用)。
        if (auth != null && !auth.isEmpty()) {
            p.setAuth(JsonMappers.mapper().convertValue(auth, Auth.class));
        }
        if (customHeaders != null) p.setCustomHeaders(customHeaders);
        if (defaultHeaders != null) p.setDefaultHeaders(defaultHeaders);
        if (allowedDomains != null) p.setAllowedDomains(new LinkedHashSet<>(allowedDomains));
        if (timeoutMs != null) p.setTimeoutMs(timeoutMs);
        if (retry != null && !retry.isEmpty()) {
            p.setRetry(JsonMappers.mapper().convertValue(retry, Retry.class));
        }
        return engine.addProfile(p);
    }

    @McpTool(name = "update_profile", description =
            "部分更新一个已注册 profile。null 字段保留;auth 非 null 则整体替换凭据块。")
    public String updateProfile(
            @ToolParam(description = "profile 名") String name,
            @ToolParam(description = "新描述", required = false) String description,
            @ToolParam(description = "新 baseUrl", required = false) String baseUrl,
            @ToolParam(description = "新 auth 块,整体替换", required = false) Map<String, Object> auth,
            @ToolParam(description = "新 customHeaders", required = false) Map<String, String> customHeaders,
            @ToolParam(description = "新 defaultHeaders", required = false) Map<String, String> defaultHeaders,
            @ToolParam(description = "新 allowedDomains,整体替换", required = false) List<String> allowedDomains,
            @ToolParam(description = "新 timeoutMs", required = false) Long timeoutMs,
            @ToolParam(description = "新 retry", required = false) Map<String, Object> retry) {
        Profile patch = new Profile();
        if (description != null) patch.setDescription(description);
        if (baseUrl != null) patch.setBaseUrl(baseUrl);
        if (auth != null && !auth.isEmpty()) {
            patch.setAuth(JsonMappers.mapper().convertValue(auth, Auth.class));
        }
        if (customHeaders != null) patch.setCustomHeaders(customHeaders);
        if (defaultHeaders != null) patch.setDefaultHeaders(defaultHeaders);
        if (allowedDomains != null) patch.setAllowedDomains(new LinkedHashSet<>(allowedDomains));
        if (timeoutMs != null) patch.setTimeoutMs(timeoutMs);
        if (retry != null && !retry.isEmpty()) {
            patch.setRetry(JsonMappers.mapper().convertValue(retry, Retry.class));
        }
        return engine.updateProfile(name, patch);
    }

    @McpTool(name = "remove_profile", description =
            "删除 profile JSON。被任何 system 的 authProfile 引用时拒绝。")
    public String removeProfile(
            @ToolParam(description = "profile 名") String name) {
        return engine.removeProfile(name);
    }

    // ==================== 注册面:endpoint 5 个 ====================

    @McpTool(name = "add_endpoint", description =
            "向已注册系统添加一个手动端点定义(OpenAPI 文档之外的接口)。")
    public String addEndpoint(
            @ToolParam(description = "目标系统名") String system,
            @ToolParam(description = "HTTP 方法") String method,
            @ToolParam(description = "端点路径 /users/{id}") String path,
            @ToolParam(description = "OpenAPI parameters 数组", required = false) List<Map<String, Object>> parameters,
            @ToolParam(description = "OpenAPI requestBody 对象", required = false) Map<String, Object> requestBody,
            @ToolParam(description = "OpenAPI responses 对象", required = false) Map<String, Object> responses,
            @ToolParam(description = "简短描述", required = false) String summary,
            @ToolParam(description = "长描述", required = false) String description,
            @ToolParam(description = "OpenAPI tags", required = false) List<String> tags) {
        return engine.addEndpoint(system, method, path, parameters,
                requestBody, responses, summary, description, tags);
    }

    @McpTool(name = "update_endpoint", description =
            "对已存在的 (system, method, path) 手动端点做部分更新。"
            + "patch 是 {field: value} map,未指定字段保留原值。")
    public String updateEndpoint(
            @ToolParam(description = "目标系统名") String system,
            @ToolParam(description = "HTTP 方法") String method,
            @ToolParam(description = "端点路径") String path,
            @ToolParam(description = "{field:value} patch map") Map<String, Object> patch) {
        return engine.updateEndpoint(system, method, path, patch);
    }

    @McpTool(name = "remove_endpoint", description =
            "删除一个手动端点定义。不影响 OpenAPI 缓存。")
    public String removeEndpoint(
            @ToolParam(description = "目标系统名") String system,
            @ToolParam(description = "HTTP 方法") String method,
            @ToolParam(description = "端点路径") String path) {
        return engine.removeEndpoint(system, method, path);
    }

    @McpTool(name = "list_endpoints", description =
            "列出一个系统的合并端点(manual + OpenAPI),可按 tag / source 过滤。")
    public String listEndpoints(
            @ToolParam(description = "系统名") String system,
            @ToolParam(description = "按 OpenAPI tag 过滤", required = false) String tag,
            @ToolParam(description = "按 source 过滤 manual / openapi", required = false) String source) {
        return engine.listEndpoints(system, tag, source);
    }

    @McpTool(name = "get_endpoint", description =
            "取一个端点的完整 schema(system/method/path),返回 JSON。")
    public String getEndpoint(
            @ToolParam(description = "系统名") String system,
            @ToolParam(description = "HTTP 方法") String method,
            @ToolParam(description = "端点路径") String path) {
        return engine.getEndpoint(system, method, path);
    }

    // ==================== 历史查询 1 个 ====================

    @McpTool(name = "get_request_history", description =
            "查询请求历史。system 为 null/blank 表示全系统。limit 默认 50,最大 500。"
            + "statusFilter: succeeded / failed / all。since: ISO-8601,严格 > since。")
    public String getRequestHistory(
            @ToolParam(description = "系统名,省略表示全系统", required = false) String system,
            @ToolParam(description = "返回条数上限", required = false) Integer limit,
            @ToolParam(description = "succeeded / failed / all", required = false) String statusFilter,
            @ToolParam(description = "ISO-8601 时间戳", required = false) String since) {
        Instant sinceInstant = (since == null || since.isBlank())
                ? Instant.EPOCH
                : Instant.parse(since);
        int lim = (limit == null) ? 50 : Math.min(limit, 500);
        return engine.getRequestHistory(system, lim, statusFilter, sinceInstant);
    }
}
