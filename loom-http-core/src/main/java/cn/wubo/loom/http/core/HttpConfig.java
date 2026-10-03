package cn.wubo.loom.http.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 全局 HTTP 能力配置（无 Spring，原 http-mcp 的 {@code GlobalConfig}）。
 *
 * <p><b>关于 {@link #allowedDomains} 的双模语义</b>：内部工具模式（嵌在
 * spring-ai-loom-agent 里的 HTTP tool）和 jar 模式（独立 {@code loom-http-mcp}
 * 服务）对"空集合"含义的解释不同——这由上层 Mode 适配层负责，
 * 本 POJO 不做区分：
 * <ul>
 *   <li><b>内部模式</b>：空 = <b>拒绝一切外网</b>（fail-closed）。
 *       默认安全；用户必须显式授权每台外部服务器。</li>
 *   <li><b>jar 模式</b>：空 = <b>不限制</b>（fail-open）。
 *       假设 MCP server 部署者完全控制环境。</li>
 * </ul>
 *
 * <p>Task 1 仅搬运字段，不做 fail-closed 默认值判定——那是 Task 4
 * （{@code ProfileValidator} 接线 + 全局校验）的职责。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HttpConfig {
    private int version = 1;
    private Set<String> allowedDomains = new LinkedHashSet<>();
    private Map<String, String> defaultHeaders = new LinkedHashMap<>();
    private long maxResponseSizeBytes = 1024 * 1024L;
    private int maxBatchConcurrency = 10;
    private long maxRequestBodyBytes = 10 * 1024 * 1024L;
    private int historyMaxEntriesPerSystem = 1000;
    private Set<String> sensitiveHeaders = new LinkedHashSet<>(Set.of(
        "Authorization", "Cookie", "Set-Cookie",
        "X-API-Key", "X-Auth-Token", "Proxy-Authorization"));
    /**
     * Field names whose VALUES are masked in {@code request.body} when
     * writing history JSONL. Default list is intentionally conservative:
     * the most common credential shapes users accidentally put in JSON
     * bodies. Profile-level {@code sensitiveRequestBodyFields} extends
     * (not replaces) this list. Matching is exact and case-insensitive
     * — same semantics as header masking, so {@code userPassword} is
     * NOT matched by the default {@code password} (extend the list
     * explicitly if you need that).
     */
    private Set<String> sensitiveRequestBodyFields = new LinkedHashSet<>(Set.of(
        "password", "pwd", "pass",
        "token", "accessToken", "refreshToken", "idToken",
        "secret", "clientSecret", "apiKey", "api_key",
        "privateKey", "private_key"));
    private AuditLog auditLog = new AuditLog();

    public static class AuditLog { private boolean enabled = true; public boolean isEnabled() { return enabled; } public void setEnabled(boolean e) { this.enabled = e; } }

    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
    public Set<String> getAllowedDomains() { return allowedDomains; }
    public void setAllowedDomains(Set<String> s) { this.allowedDomains = s; }
    public Map<String, String> getDefaultHeaders() { return defaultHeaders; }
    public void setDefaultHeaders(Map<String, String> m) { this.defaultHeaders = m; }
    public long getMaxResponseSizeBytes() { return maxResponseSizeBytes; }
    public void setMaxResponseSizeBytes(long v) { this.maxResponseSizeBytes = v; }
    public int getMaxBatchConcurrency() { return maxBatchConcurrency; }
    public void setMaxBatchConcurrency(int v) { this.maxBatchConcurrency = v; }
    public long getMaxRequestBodyBytes() { return maxRequestBodyBytes; }
    public void setMaxRequestBodyBytes(long v) { this.maxRequestBodyBytes = v; }
    public int getHistoryMaxEntriesPerSystem() { return historyMaxEntriesPerSystem; }
    public void setHistoryMaxEntriesPerSystem(int v) { this.historyMaxEntriesPerSystem = v; }
    public Set<String> getSensitiveHeaders() { return sensitiveHeaders; }
    public void setSensitiveHeaders(Set<String> s) { this.sensitiveHeaders = s; }
    public Set<String> getSensitiveRequestBodyFields() { return sensitiveRequestBodyFields; }
    public void setSensitiveRequestBodyFields(Set<String> s) { this.sensitiveRequestBodyFields = s; }
    public AuditLog getAuditLog() { return auditLog; }
    public void setAuditLog(AuditLog a) { this.auditLog = a; }
}