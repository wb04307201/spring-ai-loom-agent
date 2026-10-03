package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.invoke.InvokeRequest;
import cn.wubo.loom.http.core.system.System;
import cn.wubo.loom.http.core.util.JsonMappers;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 4 个通用 HTTP @McpTool + 内部 _ad_hoc 合成系统。
 * <p>每个 http_* 把传入 URL 拆成 baseUrl + path,合成 InvokeRequest 走 invokeEndpoint 同一 16 步流程。
 * 详见 Task 12 头部约定。
 */
public class LoomHttpService {

    /** 合成系统名(spec §6.4) —— 内部 baseUrl / authProfile 每调用覆盖。 */
    static final String AD_HOC_SYSTEM_NAME = "_ad_hoc";

    private final HttpEngine engine;
    /** 持久化的合成 system 记录:InvokeService.systemService.get(\"_ad_hoc\") 必须能解析。 */
    private final System adHocSystem;

    public LoomHttpService(HttpEngine engine) {
        this.engine = engine;
        this.adHocSystem = new System();
        adHocSystem.setName(AD_HOC_SYSTEM_NAME);
        adHocSystem.setDescription("Synthetic system for unbound ad-hoc httpXxx calls");
        engine.registerSystem(adHocSystem);
    }

    @McpTool(name = "http_get", description =
            "发送 HTTP GET。兼容旧(只传 url+headers JSON)+ 新增可选 profile/assertions/extract/responseMode。"
            + "返回 InvokeResponse JSON(statusCode/headers/body/extracted/assertionResult/error)。")
    public String httpGet(
            @ToolParam(description = "完整 URL https://api.example.com/data") String url,
            @ToolParam(description = "请求头 JSON 字符串,可省略", required = false) String headers,
            @ToolParam(description = "profile 名,自动注入 auth + customHeaders", required = false) String profile,
            @ToolParam(description = "断言列表 {type,value}", required = false) List<Map<String, Object>> assertions,
            @ToolParam(description = "JSONPath 提取 {alias:\"$.x\"}", required = false) Map<String, String> extract,
            @ToolParam(description = "summary / full / file", required = false) String responseMode) {
        return invokeAdHoc("GET", url, null, headers, profile, assertions, extract, responseMode);
    }

    @McpTool(name = "http_post", description = "发送 HTTP POST。body 为 JSON 字符串。")
    public String httpPost(
            @ToolParam(description = "完整 URL") String url,
            @ToolParam(description = "请求体 JSON 字符串 {\"key\":val}") String body,
            @ToolParam(description = "请求头 JSON 字符串", required = false) String headers,
            @ToolParam(description = "profile 名", required = false) String profile,
            @ToolParam(description = "断言列表", required = false) List<Map<String, Object>> assertions,
            @ToolParam(description = "JSONPath 提取", required = false) Map<String, String> extract,
            @ToolParam(description = "summary / full / file", required = false) String responseMode) {
        return invokeAdHoc("POST", url, body, headers, profile, assertions, extract, responseMode);
    }

    @McpTool(name = "http_put", description = "发送 HTTP PUT。body 为 JSON 字符串。")
    public String httpPut(
            @ToolParam(description = "完整 URL") String url,
            @ToolParam(description = "请求体 JSON 字符串") String body,
            @ToolParam(description = "请求头 JSON 字符串", required = false) String headers,
            @ToolParam(description = "profile 名", required = false) String profile,
            @ToolParam(description = "断言列表", required = false) List<Map<String, Object>> assertions,
            @ToolParam(description = "JSONPath 提取", required = false) Map<String, String> extract,
            @ToolParam(description = "summary / full / file", required = false) String responseMode) {
        return invokeAdHoc("PUT", url, body, headers, profile, assertions, extract, responseMode);
    }

    @McpTool(name = "http_delete", description = "发送 HTTP DELETE。")
    public String httpDelete(
            @ToolParam(description = "完整 URL") String url,
            @ToolParam(description = "请求头 JSON 字符串", required = false) String headers,
            @ToolParam(description = "profile 名", required = false) String profile,
            @ToolParam(description = "断言列表", required = false) List<Map<String, Object>> assertions,
            @ToolParam(description = "JSONPath 提取", required = false) Map<String, String> extract,
            @ToolParam(description = "summary / full / file", required = false) String responseMode) {
        return invokeAdHoc("DELETE", url, null, headers, profile, assertions, extract, responseMode);
    }

    // ==================== 内部:_ad_hoc 合成系统调用 ====================

    private String invokeAdHoc(String method, String url, String body, String headersJson,
                               String profile, List<Map<String, Object>> assertions,
                               Map<String, String> extract, String responseMode) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            return errorJson("InvalidUrl", ex.getMessage());
        }
        String scheme = uri.getScheme();
        String authority = uri.getAuthority();
        if (scheme == null || authority == null) {
            return errorJson("InvalidUrl", "URL must include scheme and authority: " + url);
        }
        String origin = scheme + "://" + authority;
        String pathOnly = (uri.getPath() == null || uri.getPath().isEmpty()) ? "/" : uri.getPath();
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null && !rawQuery.isEmpty()) pathOnly = pathOnly + "?" + rawQuery;

        Map<String, String> headers = parseHeaders(headersJson);
        Map<String, Object> queryParams = parseQuery(uri.getRawQuery());

        // 同步更新合成系统:baseUrl = origin,authProfile = profile
        synchronized (adHocSystem) {
            adHocSystem.setBaseUrl(origin);
            adHocSystem.setAuthProfile(profile);
        }

        InvokeRequest req = new InvokeRequest();
        req.setSystem(AD_HOC_SYSTEM_NAME);
        req.setMethod(method);
        req.setPath(pathOnly);
        req.setParams(queryParams);
        req.setBody(body);   // String 直传:InvokeService.serializeBody() 保留原始字符串
        req.setHeaders(headers);
        req.setAssertions(assertions);
        req.setExtract(extract);
        req.setResponseMode(responseMode);
        return engine.invokeEndpoint(req);
    }

    private static Map<String, String> parseHeaders(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JsonMappers.mapper().convertValue(JsonMappers.parse(json, Object.class), Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static Map<String, Object> parseQuery(String raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, Object> out = new HashMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) out.put(pair, "");
            else out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                         URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String errorJson(String code, String msg) {
        return "{\"error\":\"" + code + "\",\"message\":\""
                + msg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }
}
