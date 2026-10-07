package cn.wubo.loom.http.core.invoke;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Output of {@link BatchService#execute(BatchRequest)}.
 *
 * Combines a per-batch summary, an optional list of {@link InvokeResponse}s
 * (populated according to the request's {@code responseMode}) and a list of
 * hard failures (ops that returned an error envelope). Soft assertion failures
 * are reported in {@link BatchSummary#getAssertionFailed()} — they never appear
 * in {@link #failures} and never block the batch in {@code continue} mode.
 *
 * <p>When {@code responseMode=full} triggers the auto file-mode fallback (the
 * aggregated response exceeds {@link BatchService#BATCH_FILE_THRESHOLD_BYTES}),
 * the raw results are also written to {@link #resultFile} and a warning
 * entry is added to {@link #warnings}.
 *
 * <p>Field naming follows spec §4.1.2: {@code summary} carries
 * {@code total / succeeded / failed / totalMs / successRate} (plus optional
 * {@code skipped / assertionFailed} for richer reporting); the top-level file
 * path is {@code resultFile}; {@code failures[]} entries are structured
 * ({@code index / method / path / statusCode / error / message}) rather than
 * concatenated strings.
 */
public class BatchResult {

    private BatchSummary summary = new BatchSummary();
    private List<BatchFailure> failures = new ArrayList<>();
    private List<InvokeResponse> results = new ArrayList<>();
    private String responseMode;
    private String resultFile;
    private List<String> warnings = new ArrayList<>();
    private Map<String, Object> meta = new LinkedHashMap<>();

    public BatchSummary getSummary() { return summary; }
    public void setSummary(BatchSummary s) { this.summary = s; }

    public List<BatchFailure> getFailures() { return failures; }
    public void setFailures(List<BatchFailure> f) { this.failures = f == null ? new ArrayList<>() : f; }

    public List<InvokeResponse> getResults() { return results; }
    public void setResults(List<InvokeResponse> r) { this.results = r == null ? new ArrayList<>() : r; }

    public String getResponseMode() { return responseMode; }
    public void setResponseMode(String r) { this.responseMode = r; }

    public String getResultFile() { return resultFile; }
    public void setResultFile(String f) { this.resultFile = f; }

    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> w) { this.warnings = w == null ? new ArrayList<>() : w; }

    public Map<String, Object> getMeta() { return meta; }
    public void setMeta(Map<String, Object> m) { this.meta = m == null ? new LinkedHashMap<>() : m; }

    /**
     * Aggregated counters for the batch.
     *
     * <p>Spec-required fields (per §4.1.2):
     * <ul>
     *   <li>{@code total} — number of operations submitted</li>
     *   <li>{@code succeeded} — operations that returned a response with no error envelope</li>
     *   <li>{@code failed} — operations that returned a hard error envelope</li>
     *   <li>{@code totalMs} — summed per-op {@code latencyMs}</li>
     *   <li>{@code successRate} — {@code succeeded / total}, or 0.0 when {@code total=0}</li>
     * </ul>
     *
     * <p>Additional fields (preserved for richer reporting):
     * <ul>
     *   <li>{@code skipped} — ops short-circuited by {@code failPolicy=stopOnFirst}</li>
     *   <li>{@code assertionFailed} — ops whose assertion evaluation did not pass</li>
     * </ul>
     */
    public static class BatchSummary {
        private int total;
        private int succeeded;
        private int failed;
        private int skipped;
        private int assertionFailed;
        private long totalMs;
        private double successRate;

        public int getTotal() { return total; }
        public void setTotal(int t) { this.total = t; }

        public int getSucceeded() { return succeeded; }
        public void setSucceeded(int s) { this.succeeded = s; }

        public int getFailed() { return failed; }
        public void setFailed(int f) { this.failed = f; }

        public int getSkipped() { return skipped; }
        public void setSkipped(int s) { this.skipped = s; }

        public int getAssertionFailed() { return assertionFailed; }
        public void setAssertionFailed(int af) { this.assertionFailed = af; }

        public long getTotalMs() { return totalMs; }
        public void setTotalMs(long t) { this.totalMs = t; }

        public double getSuccessRate() { return successRate; }
        public void setSuccessRate(double r) { this.successRate = r; }
    }

    /**
     * Hard-failure record: an operation that returned an error envelope
     * (network error, system not found, domain not allowed, etc.). Structured
     * per spec §4.1.2 so the LLM can read each field without parsing strings.
     */
    public static class BatchFailure {
        private int index;
        private String method;
        private String path;
        private Integer statusCode;
        private String error;
        private String message;

        public int getIndex() { return index; }
        public void setIndex(int i) { this.index = i; }

        public String getMethod() { return method; }
        public void setMethod(String m) { this.method = m; }

        public String getPath() { return path; }
        public void setPath(String p) { this.path = p; }

        public Integer getStatusCode() { return statusCode; }
        public void setStatusCode(Integer sc) { this.statusCode = sc; }

        public String getError() { return error; }
        public void setError(String e) { this.error = e; }

        public String getMessage() { return message; }
        public void setMessage(String m) { this.message = m; }
    }
}
