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
    /**
     * 合成 system 的**注册样板**:只在构造时注册一次,<b>此后永不再被读取或修改</b>。
     *
     * <p><b>历史教训 —— 不要为了路由去改这个字段</b>:它注册后会落盘,而
     * {@link FileWatcher} 在 {@code profiles/} 或 {@code systems/} 变更时调
     * {@link HttpEngine#reload()},reload 清缓存并从磁盘反序列化出<b>新对象</b>;
     * 改本字段不会影响引擎读到的副本。更严重的是:它曾是<b>进程级共享可变状态</b> ——
     * {@code InvokeService} 按 name 重新解析 {@code _ad_hoc} 并读它的 baseUrl/authProfile,
     * 那次读取发生在写锁之外,导致两个并发 ad-hoc 调用互相覆盖目标主机与凭据。
     * 现在路由参数随 {@link InvokeRequest} 传递(见 {@code invokeAdHoc}),
     * 共享对象保持不可变,并发调用之间无共享状态。
     */
    private final System adHocSystemTemplate;

    public LoomHttpService(HttpEngine engine) {
        this.engine = engine;
        this.adHocSystemTemplate = new System();
        adHocSystemTemplate.setName(AD_HOC_SYSTEM_NAME);
        adHocSystemTemplate.setDescription("Synthetic system for unbound ad-hoc httpXxx calls");
        // 无条件「删掉再注册」,不是「注册失败才重试」。
        // 两个原因:
        //  1) registerSystem 走 HttpEngine.toJson 折叠,**从不抛异常** —— 重名只体现为
        //     返回信封里的 {"error":"...already exists..."}。所以任何"捕获异常再重试"的写法
        //     都是死代码,合成 system 会静默缺席。
        //  2) 旧版本把**每次调用的 baseUrl/authProfile 写进这份记录并落盘**。不洗掉的话,
        //     "不带 profile 的 ad-hoc 调用"会经 firstNonBlank 回退到上一位调用者的凭据,
        //     并把它发到上一位调用者的主机上(凭据外泄)。
        // updateSystem 的 merge 跳过 null 字段(清不掉),故走 remove + register。
        // 只在构造期发生一次,不进入调用热路径。
        engine.removeSystem(AD_HOC_SYSTEM_NAME, false);
        engine.registerSystem(adHocSystemTemplate);
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

        // 路由参数随请求传递,不写共享的 _ad_hoc system ——
        // 见 adHocSystemTemplate 的 javadoc(共享可变状态会导致并发调用互相覆盖
        // 目标主机与凭据)。引擎侧由 InvokeService 的 per-request override 优先取值。
        InvokeRequest req = new InvokeRequest();
        req.setSystem(AD_HOC_SYSTEM_NAME);
        req.setBaseUrlOverride(origin);
        req.setAuthProfileOverride(profile);
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
