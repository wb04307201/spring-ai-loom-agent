package cn.wubo.loom.http.core.invoke;

import java.util.ArrayList;
import java.util.List;

/**
 * Input to {@link BatchService#execute(BatchRequest)}.
 *
 * Carries the list of per-operation {@link InvokeRequest}s together with
 * batch-level options (concurrency, fail policy, response mode).
 *
 * <p>Defaults applied in {@link BatchService}:
 * <ul>
 *   <li>{@code concurrency}: 5 (clamped to {@code operations.size()})</li>
 *   <li>{@code failPolicy}: {@code "continue"}</li>
 *   <li>{@code responseMode}: {@code "full"}</li>
 * </ul>
 */
public class BatchRequest {

    private String profile;
    private List<InvokeRequest> operations = new ArrayList<>();
    private Integer concurrency;
    private String failPolicy;
    private Long perRequestTimeoutMs;
    private String responseMode;
    private Integer firstN;

    public String getProfile() { return profile; }
    public void setProfile(String p) { this.profile = p; }

    public List<InvokeRequest> getOperations() { return operations; }
    public void setOperations(List<InvokeRequest> ops) { this.operations = ops == null ? new ArrayList<>() : ops; }

    public Integer getConcurrency() { return concurrency; }
    public void setConcurrency(Integer c) { this.concurrency = c; }

    public String getFailPolicy() { return failPolicy; }
    public void setFailPolicy(String p) { this.failPolicy = p; }

    public Long getPerRequestTimeoutMs() { return perRequestTimeoutMs; }
    public void setPerRequestTimeoutMs(Long t) { this.perRequestTimeoutMs = t; }

    public String getResponseMode() { return responseMode; }
    public void setResponseMode(String m) { this.responseMode = m; }

    public Integer getFirstN() { return firstN; }
    public void setFirstN(Integer n) { this.firstN = n; }
}
