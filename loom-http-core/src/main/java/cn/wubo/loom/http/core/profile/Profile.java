package cn.wubo.loom.http.core.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Profile {
    private String name;
    private String description;
    private String baseUrl;
    private Auth auth = new Auth();
    private Map<String, String> customHeaders = new LinkedHashMap<>();
    private Map<String, String> defaultHeaders = new LinkedHashMap<>();
    private Set<String> allowedDomains;
    private Set<String> sensitiveHeaders;
    /**
     * Field names whose VALUES are masked in {@code request.body} when
     * writing history JSONL. Extends the global default list (does NOT
     * replace it) — set this to add app-specific credential field names
     * like {@code "pin"} or {@code "oldPassword"} that your API requires.
     */
    private Set<String> sensitiveRequestBodyFields;
    private List<String> allowedFilePaths;
    private long timeoutMs = 10000;
    private Retry retry = new Retry();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Auth {
        private String type;          // bearer | basic | apiKey-header | apiKey-query
        private String token;         // bearer
        private String username;      // basic
        private String password;      // basic
        private String keyName;       // apiKey-*
        private String value;         // apiKey-*

        public String getType() { return type; }
        public void setType(String t) { this.type = t; }
        public String getToken() { return token; }
        public void setToken(String t) { this.token = t; }
        public String getUsername() { return username; }
        public void setUsername(String u) { this.username = u; }
        public String getPassword() { return password; }
        public void setPassword(String p) { this.password = p; }
        public String getKeyName() { return keyName; }
        public void setKeyName(String k) { this.keyName = k; }
        public String getValue() { return value; }
        public void setValue(String v) { this.value = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Retry {
        private int maxAttempts = 3;
        private long backoffMs = 500;
        private List<Integer> retryOn = List.of(502, 503, 504, 429);

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int v) { this.maxAttempts = v; }
        public long getBackoffMs() { return backoffMs; }
        public void setBackoffMs(long v) { this.backoffMs = v; }
        public List<Integer> getRetryOn() { return retryOn; }
        public void setRetryOn(List<Integer> r) { this.retryOn = r; }
    }

    // getters/setters...
    public String getName() { return name; } public void setName(String n) { this.name = n; }
    public String getDescription() { return description; } public void setDescription(String d) { this.description = d; }
    public String getBaseUrl() { return baseUrl; } public void setBaseUrl(String b) { this.baseUrl = b; }
    public Auth getAuth() { return auth; } public void setAuth(Auth a) { this.auth = a; }
    public Map<String, String> getCustomHeaders() { return customHeaders; } public void setCustomHeaders(Map<String, String> m) { this.customHeaders = m; }
    public Map<String, String> getDefaultHeaders() { return defaultHeaders; } public void setDefaultHeaders(Map<String, String> m) { this.defaultHeaders = m; }
    public Set<String> getAllowedDomains() { return allowedDomains; } public void setAllowedDomains(Set<String> s) { this.allowedDomains = s; }
    public Set<String> getSensitiveHeaders() { return sensitiveHeaders; } public void setSensitiveHeaders(Set<String> s) { this.sensitiveHeaders = s; }
    public Set<String> getSensitiveRequestBodyFields() { return sensitiveRequestBodyFields; } public void setSensitiveRequestBodyFields(Set<String> s) { this.sensitiveRequestBodyFields = s; }
    public List<String> getAllowedFilePaths() { return allowedFilePaths; } public void setAllowedFilePaths(List<String> l) { this.allowedFilePaths = l; }
    public long getTimeoutMs() { return timeoutMs; } public void setTimeoutMs(long t) { this.timeoutMs = t; }
    public Retry getRetry() { return retry; } public void setRetry(Retry r) { this.retry = r; }
}
