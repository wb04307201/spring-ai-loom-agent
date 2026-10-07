package cn.wubo.loom.http.core.invoke;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.util.JsonMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concurrent batch executor for {@link InvokeService} calls (spec §4.1.2 /
 * §6.3).
 *
 * <p>Each operation in {@link BatchRequest#getOperations()} is dispatched to a
 * fixed-size {@link ThreadPoolExecutor}; results are collected in submission
 * order. The {@code failPolicy} controls whether the rest of the batch is
 * cancelled after the first hard failure (a result with {@code error != null}).
 * Soft assertion failures never trigger {@code stopOnFirst} and are reported
 * in the summary instead.
 *
 * <p>The {@code responseMode} setting shapes the returned {@link BatchResult}
 * (default: {@code summary}, chosen as the LLM-friendly view — callers that
 * want per-op bodies must opt into {@code firstN} / {@code full} / {@code file}
 * explicitly):
 * <ul>
 *   <li>{@code summary} (default) — summary + failures, no per-op bodies.</li>
 *   <li>{@code firstN} — summary + failures + the first {@code firstN} results.</li>
 *   <li>{@code full} — everything inline; if the aggregated payload exceeds
 *       {@link #BATCH_FILE_THRESHOLD_BYTES} (5 MB), the result is auto-spilled
 *       to a JSON file under the static fallback {@code ~/.http-mcp/responses} and a
 *       {@code largeResponse} warning is emitted.</li>
 *   <li>{@code file} — always spill everything to disk; return the path
 *       together with a summary.</li>
 * </ul>
 *
 * <p><b>Signature note (loom port)</b>: original {@code BatchService} took a
 * {@code StorageConfig} as its second parameter to source the
 * {@code responses/} directory for the file-mode fallback. The loom port
 * changes that to {@link HttpConfig} (per Task 3 brief) — a config object
 * without any path information. As a result, file-mode writes always use
 * the static fallback {@code ~/.http-mcp/responses} regardless of where the
 * caller has set up its storage root. This is intentional: HttpConfig is
 * shared with the jar-mode MCP server, which is single-tenant and uses that
 * exact fallback path.
 */
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);

    /** Default per-batch concurrency cap when none is supplied. */
    public static final int DEFAULT_CONCURRENCY = 5;

    /** Aggregated-response size threshold (in bytes) for auto file mode. */
    public static final long BATCH_FILE_THRESHOLD_BYTES = 5L * 1024 * 1024;

    /** Fallback directory used when no storage root is supplied. */
    private static final Path DEFAULT_RESPONSES_DIR =
        Path.of(System.getProperty("user.home"), ".http-mcp", "responses");

    private final InvokeService invokeService;
    private final HttpConfig globalConfig;
    private final long fileThresholdBytes;

    public BatchService(InvokeService invokeService, HttpConfig globalConfig) {
        this(invokeService, globalConfig, BATCH_FILE_THRESHOLD_BYTES);
    }

    /**
     * Test-friendly constructor that exposes the file-mode threshold so tests
     * can exercise the auto file-mode branch without serving a 5 MB body.
     */
    BatchService(InvokeService invokeService, HttpConfig globalConfig, long fileThresholdBytes) {
        this.invokeService = invokeService;
        this.globalConfig = globalConfig;
        this.fileThresholdBytes = fileThresholdBytes;
    }

    /**
     * Run a batch of operations and return the aggregated result. Always
     * returns a non-null {@link BatchResult}; never throws on per-op failures
     * (those surface as entries in {@link BatchResult#getFailures()}).
     *
     * @param req batch description (operations + batch-level options)
     * @return aggregated batch result
     */
    public BatchResult execute(BatchRequest req) {
        if (req == null || req.getOperations() == null || req.getOperations().isEmpty()) {
            BatchResult empty = new BatchResult();
            empty.setResponseMode(normalizeResponseMode(req == null ? null : req.getResponseMode()));
            return empty;
        }

        int concurrency = resolveConcurrency(req.getConcurrency(), req.getOperations().size());
        String failPolicy = normalizeFailPolicy(req.getFailPolicy());
        String responseMode = normalizeResponseMode(req.getResponseMode());

        ThreadPoolExecutor exec = buildExecutor(concurrency);
        AtomicBoolean stopFlag = new AtomicBoolean(false);

        // Submit one task per operation. Tasks consult the shared stop flag
        // before invoking so stopOnFirst propagates to not-yet-started ops.
        Long batchTimeoutMs = req.getPerRequestTimeoutMs();
        List<Future<InvokeResponse>> futures = new ArrayList<>(req.getOperations().size());
        for (int i = 0; i < req.getOperations().size(); i++) {
            final int idx = i;
            final InvokeRequest op = req.getOperations().get(i);
            // Per-op timeoutMs takes precedence; falls back to batch-level
            // perRequestTimeoutMs. Applied to a local copy so we don't mutate
            // the caller-supplied InvokeRequest.
            final InvokeRequest effective = copyWithTimeout(op, batchTimeoutMs);
            Callable<InvokeResponse> task = () -> {
                if (stopFlag.get()) {
                    return skipped(idx, effective);
                }
                InvokeResponse out;
                try {
                    out = invokeService.invoke(effective);
                } catch (RuntimeException ex) {
                    out = errorFromException(idx, effective, ex);
                }
                // Hard failure + stopOnFirst → set the flag so the rest of
                // the in-flight / queued tasks return the "skipped" sentinel.
                if (out.getError() != null && "stoponfirst".equals(failPolicy)) {
                    stopFlag.set(true);
                }
                return out;
            };
            futures.add(exec.submit(task));
        }

        // Drain. Preserve submission order so callers can correlate
        // results[] / failures[] back to the original operations[].
        List<InvokeResponse> results = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            Future<InvokeResponse> f = futures.get(i);
            try {
                results.add(f.get());
            } catch (Exception ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                results.add(errorFromException(i, req.getOperations().get(i), cause));
            }
        }
        exec.shutdown();
        try {
            exec.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        return assembleResponse(req, results, responseMode);
    }

    // ------------------------------------------------------------------
    // Aggregation
    // ------------------------------------------------------------------

    private BatchResult assembleResponse(BatchRequest req, List<InvokeResponse> results, String responseMode) {
        BatchResult out = new BatchResult();
        out.setResponseMode(displayMode(responseMode));
        BatchResult.BatchSummary summary = buildSummary(results);
        out.setSummary(summary);
        out.setFailures(collectFailures(results));

        switch (responseMode) {
            case "summary":
                // No per-op bodies.
                return out;
            case "firstn": {
                int n = req.getFirstN() == null || req.getFirstN() <= 0
                    ? 1 : req.getFirstN();
                List<InvokeResponse> head = new ArrayList<>(Math.min(n, results.size()));
                for (int i = 0; i < results.size() && head.size() < n; i++) {
                    head.add(results.get(i));
                }
                out.setResults(head);
                return out;
            }
            case "file": {
                String file = writeResultsToFile(results, summary);
                out.setResultFile(file);
                return out;
            }
            case "full":
            default: {
                long bytes = estimateSize(results);
                if (bytes > fileThresholdBytes) {
                    String file = writeResultsToFile(results, summary);
                    out.setResultFile(file);
                    List<String> warnings = new ArrayList<>();
                    warnings.add("largeResponse: aggregated batch payload "
                        + bytes + " bytes exceeded threshold "
                        + fileThresholdBytes + "; auto-switched to file mode ("
                        + file + ")");
                    out.setWarnings(warnings);
                    return out;
                }
                out.setResults(results);
                return out;
            }
        }
    }

    private static String displayMode(String normalized) {
        if (normalized == null) return "full";
        switch (normalized) {
            case "firstn": return "firstN";
            case "summary": return "summary";
            case "file": return "file";
            default: return "full";
        }
    }

    private BatchResult.BatchSummary buildSummary(List<InvokeResponse> results) {
        BatchResult.BatchSummary s = new BatchResult.BatchSummary();
        s.setTotal(results.size());
        long totalLatency = 0L;
        for (InvokeResponse r : results) {
            if (r.getLatencyMs() > 0) totalLatency += r.getLatencyMs();
            if (r.getError() != null) {
                if ("notRun".equals(r.getError().get("code"))) {
                    s.setSkipped(s.getSkipped() + 1);
                } else {
                    s.setFailed(s.getFailed() + 1);
                }
            } else {
                if (r.getAssertionResult() != null && !r.getAssertionResult().passed()) {
                    s.setAssertionFailed(s.getAssertionFailed() + 1);
                }
                s.setSucceeded(s.getSucceeded() + 1);
            }
        }
        s.setTotalMs(totalLatency);
        s.setSuccessRate(s.getTotal() == 0 ? 0.0 : (double) s.getSucceeded() / (double) s.getTotal());
        return s;
    }

    private List<BatchResult.BatchFailure> collectFailures(List<InvokeResponse> results) {
        List<BatchResult.BatchFailure> failures = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            InvokeResponse r = results.get(i);
            if (r.getError() == null) continue;
            // "notRun" sentinels are recorded in the summary.skipped counter,
            // not as a failure — they reflect policy, not an error.
            if ("notRun".equals(r.getError().get("code"))) continue;
            BatchResult.BatchFailure f = new BatchResult.BatchFailure();
            f.setIndex(i);
            f.setMethod(r.getMethod());
            f.setPath(r.getPath());
            f.setStatusCode(r.getStatusCode());
            f.setError((String) r.getError().get("error"));
            Object msg = r.getError().get("message");
            f.setMessage(msg == null ? "" : msg.toString());
            failures.add(f);
        }
        return failures;
    }

    private long estimateSize(List<InvokeResponse> results) {
        long total = 0L;
        for (InvokeResponse r : results) {
            try {
                total += JsonMappers.toJson(r).length();
            } catch (RuntimeException ex) {
                // Best-effort estimate; if serialization fails assume 1 KB.
                total += 1024L;
            }
        }
        return total;
    }

    private String writeResultsToFile(List<InvokeResponse> results) {
        return writeResultsToFile(results, null);
    }

    private String writeResultsToFile(List<InvokeResponse> results, BatchResult.BatchSummary summary) {
        try {
            Path dir = DEFAULT_RESPONSES_DIR;
            Files.createDirectories(dir);
            String fname = "batch-" + Instant.now().toEpochMilli() + "-"
                + UUID.randomUUID().toString().substring(0, 8) + ".json";
            Path file = dir.resolve(fname);
            // Write a thin envelope rather than the bare array so the file is
            // self-describing when opened manually.
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("kind", "batchResults");
            envelope.put("count", results.size());
            if (summary != null) envelope.put("summary", summary);
            envelope.put("results", results);
            Files.writeString(file, JsonMappers.toJson(envelope));
            return file.toString();
        } catch (IOException ex) {
            log.warn("Failed to write batch results file: {}", ex.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Sentinels
    // ------------------------------------------------------------------

    /**
     * Build a copy of {@code op} with the effective per-request timeout set:
     * per-op {@code timeoutMs} wins over the batch-level
     * {@code perRequestTimeoutMs}; either can be null when the caller did not
     * specify a timeout. The copy avoids mutating caller-supplied state.
     */
    private static InvokeRequest copyWithTimeout(InvokeRequest op, Long batchTimeoutMs) {
        Long effective = op.getTimeoutMs() != null ? op.getTimeoutMs() : batchTimeoutMs;
        if (effective == null) return op;
        InvokeRequest copy = new InvokeRequest();
        copy.setSystem(op.getSystem());
        copy.setMethod(op.getMethod());
        copy.setPath(op.getPath());
        copy.setParams(op.getParams());
        copy.setBody(op.getBody());
        copy.setHeaders(op.getHeaders());
        copy.setAssertions(op.getAssertions());
        copy.setExtract(op.getExtract());
        copy.setResponseMode(op.getResponseMode());
        copy.setTimeoutMs(effective);
        // 路由 override 必须一起复制 —— 漏掉会让批量里的 ad-hoc 调用退回共享 system 的
        // baseUrl(已恒为 null)→ NoBaseUrl,而单条路径正常,表现为"批量调用整批失败"。
        // 两字段不可从 JSON 绑定(见 InvokeRequest 的 @JsonIgnore),此处是程序内复制,不受影响。
        copy.setBaseUrlOverride(op.getBaseUrlOverride());
        copy.setAuthProfileOverride(op.getAuthProfileOverride());
        return copy;
    }

    private InvokeResponse skipped(int idx, InvokeRequest op) {
        InvokeResponse out = new InvokeResponse();
        out.setSystem(op.getSystem());
        out.setMethod(op.getMethod());
        out.setPath(op.getPath());
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", "notRun");
        err.put("error", "NotRun");
        err.put("message", "Operation skipped due to failPolicy=stopOnFirst");
        err.put("index", idx);
        out.setError(err);
        return out;
    }

    private InvokeResponse errorFromException(int idx, InvokeRequest op, Throwable ex) {
        InvokeResponse out = new InvokeResponse();
        out.setSystem(op.getSystem());
        out.setMethod(op.getMethod());
        out.setPath(op.getPath());
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", "InvocationError");
        err.put("error", "InvocationError");
        err.put("message", ex.getMessage() == null ? ex.toString() : ex.getMessage());
        err.put("index", idx);
        out.setError(err);
        return out;
    }

    // ------------------------------------------------------------------
    // Normalisation
    // ------------------------------------------------------------------

    private static int resolveConcurrency(Integer raw, int ops) {
        if (raw == null || raw <= 0) return Math.min(DEFAULT_CONCURRENCY, ops);
        return Math.min(raw, ops);
    }

    private static String normalizeFailPolicy(String raw) {
        if (raw == null || raw.isBlank()) return "continue";
        String n = raw.trim().toLowerCase();
        if ("stoponfirst".equals(n)) return "stoponfirst";
        return "continue";
    }

    private static String normalizeResponseMode(String raw) {
        if (raw == null || raw.isBlank()) return "summary";
        String n = raw.trim().toLowerCase();
        switch (n) {
            case "summary": return "summary";
            case "firstn":  return "firstn";
            case "file":    return "file";
            case "full":    return "full";
            default:        return "summary";
        }
    }

    private static ThreadPoolExecutor buildExecutor(int size) {
        if (size <= 0) size = 1;
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "batch-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return (ThreadPoolExecutor) Executors.newFixedThreadPool(size, tf);
    }
}
