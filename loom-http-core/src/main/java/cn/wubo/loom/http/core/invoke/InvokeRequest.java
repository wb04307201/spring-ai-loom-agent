package cn.wubo.loom.http.core.invoke;

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
}
