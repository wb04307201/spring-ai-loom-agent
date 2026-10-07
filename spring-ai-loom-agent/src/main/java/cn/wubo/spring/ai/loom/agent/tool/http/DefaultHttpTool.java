package cn.wubo.spring.ai.loom.agent.tool.http;

import cn.wubo.loom.file.core.LoomPaths;
import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.invoke.BatchRequest;
import cn.wubo.loom.http.core.invoke.InvokeRequest;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTTP 工具默认实现 —— 每个工具方法都是「取 username → 派生存储根 → 建引擎 → 委托 → 返回 String」。
 *
 * <p><b>为什么不缓存 HttpEngine</b>:引擎持有各存储服务的内存缓存与 OpenAPI 缓存,
 * 且绑死存储根。缓存它等于把 A 用户的凭据暴露给 B 用户。对照
 * {@code DefaultFileTool} 可以复用单例 {@code FileOperations},是因为它无状态。
 * 每次 new 的开销是几个 POJO + 读一次 {@code config.json},可忽略。
 *
 * <p><b>为什么 body 参数是 Map&lt;String,Object&gt; 而非 Object</b>:FIX-5 ——
 * 严格 JSON 对象契约与 {@code bodyRaw}(裸串)互斥,LLM 一边只可能传一个;{@code Object}
 * 会在 JSON 反序列化时丢类型信息(JVM Object 与 Jackson Map 不可区分),走 Object
 * 会让 {@code InvokeService} 的 16 步流水线失去 schema 校验能力。
 */
public class DefaultHttpTool implements IHttpTool {

    private final String usersBasePath;
    private final LoomAgentProperties.HttpProperty property;

    public DefaultHttpTool(String usersBasePath, LoomAgentProperties.HttpProperty property) {
        this.usersBasePath = LoomPaths.orDefaultUsersBase(usersBasePath);
        this.property = property;
    }

    private HttpEngine engineFor(String username) {
        HttpConfig cfg = new HttpConfig();
        cfg.setFailClosed(true);   // 内部工具模式恒 fail-closed(spec §4.4)
        cfg.setAllowedDomains(new java.util.LinkedHashSet<>(property.getAllowedDomains()));
        cfg.setMaxResponseSizeBytes(property.getMaxResponseSizeBytes());
        cfg.setMaxBatchConcurrency(property.getMaxBatchConcurrency());
        cfg.setMaxRequestBodyBytes(property.getMaxRequestBodyBytes());
        cfg.setHistoryMaxEntriesPerSystem(property.getHistoryMaxEntriesPerSystem());
        return new HttpEngine(LoomPaths.userHttpDir(usersBasePath, username), cfg);
    }

    private static String tryGetUsername(ToolContext toolContext) {
        Map<String, Object> ctx = toolContext == null ? null : toolContext.getContext();
        Object u = ctx == null ? null : ctx.get("username");
        if (u == null || u.toString().isBlank()) return null;
        return u.toString();
    }

    // ==================== 调用 ====================

    @Tool(description = "调用已注册 HTTP 系统上的某个端点。system 为已注册的 API 系统名(先用 listEndpoints 查看可用端点),"
            + "method 为 GET/POST/PUT/DELETE 等,path 可含 {placeholder},params 填 path/query 参数。"
            + "可选 assertions 做响应断言、extract 用 JSONPath 提取字段、responseMode 控制返回详细程度。"
            + "body 与 bodyRaw 互斥:body 是 JSON 对象,bodyRaw 是裸字符串(非 JSON 对象时用)。")
    @Override
    public String invokeEndpoint(
            @ToolParam(description = "已注册的系统名称") String system,
            @ToolParam(description = "HTTP 方法,例如 GET / POST / PUT / DELETE") String method,
            @ToolParam(description = "端点路径,可包含 {placeholder}") String path,
            @ToolParam(description = "path / query 参数映射", required = false) Map<String, Object> params,
            @ToolParam(description = "请求体(JSON 对象)", required = false) Map<String, Object> body,
            @ToolParam(description = "请求体原始字符串(非 JSON 对象时用,与 body 互斥)", required = false) String bodyRaw,
            @ToolParam(description = "额外请求头映射", required = false) Map<String, String> headers,
            @ToolParam(description = "断言规则列表,形如 {type:statusEquals, value:200}", required = false) List<Map<String, Object>> assertions,
            @ToolParam(description = "JSONPath 提取映射,形如 {\"userId\":\"$.id\"}", required = false) Map<String, String> extract,
            @ToolParam(description = "响应模式:summary / full / file", required = false) String responseMode,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误:缺少 username 上下文";
        InvokeRequest req = new InvokeRequest();
        req.setSystem(system);
        req.setMethod(method);
        req.setPath(path);
        req.setParams(params);
        req.setBody(body);
        req.setBodyRaw(bodyRaw);
        req.setHeaders(headers);
        req.setAssertions(assertions);
        req.setExtract(extract);
        req.setResponseMode(responseMode);
        req.setTimeoutMs(property.getTimeoutMs());
        return engineFor(username).invokeEndpoint(req);
    }

    @Tool(description = "并发执行一组 HTTP 操作,每个操作与 invokeEndpoint 走同一流程。适用于跨多个端点的批量读写。")
    @Override
    public String httpBatch(
            @ToolParam(description = "操作列表,每项形如 {system, method, path, params?, body?}", required = false) List<Map<String, Object>> operations,
            @ToolParam(description = "最大并发 op 数,默认 5", required = false) Integer concurrency,
            @ToolParam(description = "失败策略:continue(默认,全部跑完)/ stopOnFirst", required = false) String failPolicy,
            @ToolParam(description = "响应模式:summary / firstN / full / file", required = false) String responseMode,
            @ToolParam(description = "responseMode=firstN 时返回的条数", required = false) Integer firstN,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误:缺少 username 上下文";
        // LLM 传的是 JSON map 形态,需转成 BatchRequest 期望的 List<InvokeRequest>
        List<InvokeRequest> ops = new ArrayList<>();
        if (operations != null) {
            for (Map<String, Object> m : operations) {
                if (m != null) ops.add(toInvokeRequest(m));
            }
        }
        BatchRequest req = new BatchRequest();
        req.setOperations(ops);
        req.setConcurrency(concurrency);
        req.setFailPolicy(failPolicy);
        req.setResponseMode(responseMode);
        req.setFirstN(firstN);
        return engineFor(username).httpBatch(req);
    }

    // ==================== 发现 ====================

    @Tool(description = "列出已注册系统的所有端点(手动定义 + OpenAPI 已合并)。可用 tag / source 过滤。")
    @Override
    public String listEndpoints(
            @ToolParam(description = "系统名称") String system,
            @ToolParam(description = "按 tag 过滤", required = false) String tag,
            @ToolParam(description = "来源过滤:openapi / manual / both", required = false) String source,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误:缺少 username 上下文";
        return engineFor(username).listEndpoints(system, tag, source);
    }

    @Tool(description = "获取某个端点的完整 schema(参数、请求体、响应定义)。")
    @Override
    public String getEndpoint(
            @ToolParam(description = "系统名称") String system,
            @ToolParam(description = "HTTP 方法") String method,
            @ToolParam(description = "端点路径") String path,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误:缺少 username 上下文";
        return engineFor(username).getEndpoint(system, method, path);
    }

    @Tool(description = "查询该用户自己的 HTTP 请求历史(仅当前用户的调用记录)。")
    @Override
    public String getRequestHistory(
            @ToolParam(description = "系统名称;省略表示全部", required = false) String system,
            @ToolParam(description = "返回条数上限,默认 50", required = false) Integer limit,
            @ToolParam(description = "状态过滤:succeeded / failed / all", required = false) String statusFilter,
            @ToolParam(description = "ISO-8601 时间戳,仅返回该时间之后", required = false) String since,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误:缺少 username 上下文";
        Instant sinceAt = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceAt = Instant.parse(since);
            } catch (RuntimeException e) {
                return "错误:since 必须是 ISO-8601 时间戳,例如 2026-08-22T10:00:00Z";
            }
        }
        return engineFor(username).getRequestHistory(system,
                limit == null || limit <= 0 ? 50 : limit, statusFilter, sinceAt);
    }

    // ==================== helpers ====================

    /** LLM 传来的 map 形态 → InvokeRequest。仅拷贝已知字段,忽略未知键。 */
    private static InvokeRequest toInvokeRequest(Map<String, Object> m) {
        InvokeRequest r = new InvokeRequest();
        r.setSystem(asString(m.get("system")));
        r.setMethod(asString(m.get("method")));
        r.setPath(asString(m.get("path")));
        r.setParams(asMap(m.get("params")));
        r.setBody(m.get("body"));
        r.setBodyRaw(asString(m.get("bodyRaw")));
        r.setHeaders(asStringMap(m.get("headers")));
        return r;
    }

    private static String asString(Object o) { return o == null ? null : o.toString(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> mm ? (Map<String, Object>) mm : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> asStringMap(Object o) {
        return o instanceof Map<?, ?> mm ? (Map<String, String>) mm : null;
    }
}