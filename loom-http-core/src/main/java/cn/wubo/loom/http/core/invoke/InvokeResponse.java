package cn.wubo.loom.http.core.invoke;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Output of {@link InvokeService#invoke(InvokeRequest)}.
 *
 * Combines the 16-step pipeline result (spec §6.1) into a single, JSON-friendly
 * structure. Fields that did not apply for a given invocation (e.g. {@code body}
 * in {@code summary} / {@code file} mode) are returned as {@code null}.
 */
public class InvokeResponse {

    private String system;
    private String method;
    private String path;
    private Integer statusCode;
    private Object body;
    private Map<String, String> headers = new LinkedHashMap<>();
    private Map<String, Object> extracted = new LinkedHashMap<>();
    private AssertionResult assertionResult;
    private List<String> contractWarnings = List.of();
    private int retryCount;
    private String responseFile;
    private long latencyMs;
    private Map<String, Object> suggestion;
    private Map<String, Object> error;

    public String getSystem() { return system; }
    public void setSystem(String s) { this.system = s; }

    public String getMethod() { return method; }
    public void setMethod(String m) { this.method = m; }

    public String getPath() { return path; }
    public void setPath(String p) { this.path = p; }

    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer sc) { this.statusCode = sc; }

    public Object getBody() { return body; }
    public void setBody(Object b) { this.body = b; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> h) { this.headers = h == null ? new LinkedHashMap<>() : h; }

    public Map<String, Object> getExtracted() { return extracted; }
    public void setExtracted(Map<String, Object> e) { this.extracted = e == null ? new LinkedHashMap<>() : e; }

    public AssertionResult getAssertionResult() { return assertionResult; }
    public void setAssertionResult(AssertionResult ar) { this.assertionResult = ar; }

    public List<String> getContractWarnings() { return contractWarnings; }
    public void setContractWarnings(List<String> c) { this.contractWarnings = c == null ? List.of() : c; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int rc) { this.retryCount = rc; }

    public String getResponseFile() { return responseFile; }
    public void setResponseFile(String rf) { this.responseFile = rf; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long l) { this.latencyMs = l; }

    public Map<String, Object> getSuggestion() { return suggestion; }
    public void setSuggestion(Map<String, Object> sg) { this.suggestion = sg; }

    public Map<String, Object> getError() { return error; }
    public void setError(Map<String, Object> e) { this.error = e; }
}
