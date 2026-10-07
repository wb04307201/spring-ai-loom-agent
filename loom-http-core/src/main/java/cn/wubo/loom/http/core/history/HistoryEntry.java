package cn.wubo.loom.http.core.history;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One row in {@code history/<system>.jsonl}. Spec §3.7.
 *
 * <p>Serialised as compact (single-line) JSON by
 * {@link HistoryService} so each line in the JSONL file is a self-contained entry.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HistoryEntry {

    private Instant timestamp;
    private String system;
    private String profile;
    private String tool = "invokeEndpoint";
    private String method;
    private String path;
    private Request request = new Request();
    private Response response = new Response();
    private Assertion assertion = new Assertion();
    private Contract contract = new Contract();
    private int retryCount;
    private Object error;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Request {
        private Map<String, Object> params = new LinkedHashMap<>();
        private Object body;
        private Map<String, String> headers = new LinkedHashMap<>();

        public Map<String, Object> getParams() { return params; }
        public void setParams(Map<String, Object> p) { this.params = p == null ? new LinkedHashMap<>() : p; }

        public Object getBody() { return body; }
        public void setBody(Object b) { this.body = b; }

        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> h) { this.headers = h == null ? new LinkedHashMap<>() : h; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Response {
        private Integer statusCode;
        private long latencyMs;
        private long bodySize;
        private String responseFile;

        public Integer getStatusCode() { return statusCode; }
        public void setStatusCode(Integer s) { this.statusCode = s; }

        public long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(long l) { this.latencyMs = l; }

        public long getBodySize() { return bodySize; }
        public void setBodySize(long b) { this.bodySize = b; }

        public String getResponseFile() { return responseFile; }
        public void setResponseFile(String r) { this.responseFile = r; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Assertion {
        private boolean passed = true;
        private List<String> failures = new ArrayList<>();

        public boolean isPassed() { return passed; }
        public void setPassed(boolean p) { this.passed = p; }

        public List<String> getFailures() { return failures; }
        public void setFailures(List<String> f) { this.failures = f == null ? new ArrayList<>() : f; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Contract {
        private List<String> warnings = new ArrayList<>();

        public List<String> getWarnings() { return warnings; }
        public void setWarnings(List<String> w) { this.warnings = w == null ? new ArrayList<>() : w; }
    }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant t) { this.timestamp = t; }

    public String getSystem() { return system; }
    public void setSystem(String s) { this.system = s; }

    public String getProfile() { return profile; }
    public void setProfile(String p) { this.profile = p; }

    public String getTool() { return tool; }
    public void setTool(String t) { this.tool = t; }

    public String getMethod() { return method; }
    public void setMethod(String m) { this.method = m; }

    public String getPath() { return path; }
    public void setPath(String p) { this.path = p; }

    public Request getRequest() { return request; }
    public void setRequest(Request r) { this.request = r == null ? new Request() : r; }

    public Response getResponse() { return response; }
    public void setResponse(Response r) { this.response = r == null ? new Response() : r; }

    public Assertion getAssertion() { return assertion; }
    public void setAssertion(Assertion a) { this.assertion = a == null ? new Assertion() : a; }

    public Contract getContract() { return contract; }
    public void setContract(Contract c) { this.contract = c == null ? new Contract() : c; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int r) { this.retryCount = r; }

    public Object getError() { return error; }
    public void setError(Object e) { this.error = e; }
}
