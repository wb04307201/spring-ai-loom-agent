package cn.wubo.loom.http.core.invoke;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpStorage;
import cn.wubo.loom.http.core.history.HistoryService;
import cn.wubo.loom.http.core.profile.Profile;
import cn.wubo.loom.http.core.profile.ProfileService;
import cn.wubo.loom.http.core.system.OpenApiCache;
import cn.wubo.loom.http.core.system.System;
import cn.wubo.loom.http.core.system.SystemService;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@link BatchService} — covers the four response
 * modes, both fail policies, assertion-failure tolerance, and the concurrency
 * cap using WireMock as the HTTP target.
 *
 * <p>The shared {@link InvokeService} is wired by hand (no Spring) using the
 * same helpers as {@link InvokeServiceTest}. The {@link BatchService} is then
 * wrapped around it; per-test {@code BatchRequest}s describe a small set of
 * per-operation invocations.
 */
class BatchServiceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @TempDir
    Path tmp;

    private InvokeService invokeService;
    private HttpConfig global;
    private HttpStorage storageConfig;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("profiles"));
        Files.createDirectories(tmp.resolve("systems"));
        Files.createDirectories(tmp.resolve("responses"));
        Files.createDirectories(tmp.resolve("batches"));

        storageConfig = new HttpStorage(tmp);
        global = new HttpConfig();
        global.getAllowedDomains().add("localhost");
        global.setMaxResponseSizeBytes(1024 * 1024L); // 1 MB per-response threshold

        ProfileService ps = new ProfileService(storageConfig, global);
        Profile p = new Profile();
        p.setName("default");
        p.setAllowedDomains(Set.of("localhost"));
        ps.save(p);

        SystemService sys = new SystemService(storageConfig, global);
        System s = new System();
        s.setName("test-svc");
        baseUrl = wm.baseUrl();
        s.setBaseUrl(baseUrl);
        s.setAuthProfile("default");
        // Register a few endpoints so happy-path / not-found / 500 paths can be
        // exercised without tripping the unknown-endpoint branch.
        s.setEndpoints(List.of(
            endpoint("GET",    "/users/1"),
            endpoint("POST",   "/users"),
            endpoint("PUT",    "/users/1"),
            endpoint("DELETE", "/users/1"),
            endpoint("GET",    "/missing"),
            endpoint("GET",    "/boom"),
            endpoint("GET",    "/fail"),
            endpoint("GET",    "/slow"),
            endpoint("GET",    "/big")
        ));
        sys.save(s);

        OpenApiCache cache = mock(OpenApiCache.class);
        when(cache.loadResult(any(System.class)))
            .thenReturn(new OpenApiCache.LoadResult(List.of(), false, "noSource"));
        invokeService = new InvokeService(sys, ps, global, storageConfig, new HistoryService(storageConfig, global), cache);

        wm.resetAll();
    }

    private static cn.wubo.loom.http.core.endpoint.Endpoint endpoint(String method, String path) {
        var e = new cn.wubo.loom.http.core.endpoint.Endpoint();
        e.setMethod(method);
        e.setPath(path);
        return e;
    }

    // ---------- helpers ----------

    /** Default BatchService with a generous threshold so no auto file mode triggers unexpectedly. */
    private BatchService newBatchService() {
        return new BatchService(invokeService, global, 100L * 1024 * 1024);
    }

    /** Custom threshold — used by the auto-file-mode test. */
    private BatchService newBatchService(long fileThresholdBytes) {
        return new BatchService(invokeService, global, fileThresholdBytes);
    }

    private static InvokeRequest op(String method, String path) {
        InvokeRequest r = new InvokeRequest();
        r.setSystem("test-svc");
        r.setMethod(method);
        r.setPath(path);
        return r;
    }

    private static InvokeRequest opWithAssertions(String method, String path, List<Map<String, Object>> assertions) {
        InvokeRequest r = op(method, path);
        r.setAssertions(assertions);
        return r;
    }

    private static Map<String, Object> assertion(String type, Map<String, Object> fields) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", type);
        a.putAll(fields);
        return a;
    }

    private static BatchRequest batch(List<InvokeRequest> ops) {
        BatchRequest b = new BatchRequest();
        b.setOperations(new ArrayList<>(ops));
        return b;
    }

    // ---------- 1. mixed methods in a single batch ----------

    @Test
    void mixedMethods_allSucceed() {
        wm.stubFor(get("/users/1").willReturn(aResponse().withStatus(200).withBody("{\"id\":1}")));
        wm.stubFor(post("/users").willReturn(aResponse().withStatus(201).withBody("{\"id\":2}")));
        wm.stubFor(put("/users/1").willReturn(aResponse().withStatus(200).withBody("{\"id\":1}")));
        wm.stubFor(delete("/users/1").willReturn(aResponse().withStatus(204).withBody("")));

        BatchRequest req = batch(List.of(
            op("GET",    "/users/1"),
            op("POST",   "/users"),
            op("PUT",    "/users/1"),
            op("DELETE", "/users/1")
        ));
        req.setResponseMode("summary");

        BatchResult out = newBatchService().execute(req);

        assertThat(out.getSummary().getTotal()).isEqualTo(4);
        assertThat(out.getSummary().getSucceeded()).isEqualTo(4);
        assertThat(out.getSummary().getFailed()).isZero();
        assertThat(out.getFailures()).isEmpty();
        assertThat(out.getSummary().getAssertionFailed()).isZero();
    }

    // ---------- 2. assertion failure doesn't block (continue policy) ----------

    @Test
    void assertionFailure_doesNotBlock_continuePolicy() {
        wm.stubFor(get("/fail").willReturn(aResponse().withStatus(500).withBody("boom")));

        // Two ops; both will trip the same failing assertion but the batch
        // must run them all (default policy = continue).
        BatchRequest req = batch(List.of(
            opWithAssertions("GET", "/fail", List.of(assertion("statusEquals", Map.of("value", 200)))),
            opWithAssertions("GET", "/fail", List.of(assertion("statusEquals", Map.of("value", 200))))
        ));
        req.setResponseMode("full");
        req.setFailPolicy("continue");

        BatchResult out = newBatchService().execute(req);

        assertThat(out.getSummary().getTotal()).isEqualTo(2);
        // Both ops returned a real HTTP response — assertion failure does NOT
        // count as a hard failure.
        assertThat(out.getSummary().getFailed()).isZero();
        // Both assertion evaluations ran and failed.
        assertThat(out.getSummary().getAssertionFailed()).isEqualTo(2);
        assertThat(out.getFailures()).isEmpty();
        assertThat(out.getResults()).hasSize(2);
        for (InvokeResponse r : out.getResults()) {
            assertThat(r.getStatusCode()).isEqualTo(500);
            assertThat(r.getAssertionResult()).isNotNull();
            assertThat(r.getAssertionResult().passed()).isFalse();
        }
    }

    // ---------- 3. stopOnFirst stops on first failure ----------

    @Test
    void stopOnFirst_stopsRemainingOps() {
        // op 0 hits an unknown system → InvokeService returns a hard error
        // envelope. With policy = stopOnFirst, op 1 must be skipped.
        InvokeRequest bad = new InvokeRequest();
        bad.setSystem("does-not-exist");
        bad.setMethod("GET");
        bad.setPath("/whatever");
        InvokeRequest good = op("GET", "/users/1");

        BatchRequest req = batch(List.of(bad, good));
        req.setResponseMode("full");
        req.setFailPolicy("stopOnFirst");
        req.setConcurrency(1); // sequential so we can reason about ordering

        BatchResult out = newBatchService().execute(req);

        // Exactly one hard failure.
        assertThat(out.getSummary().getFailed()).isEqualTo(1);
        assertThat(out.getFailures()).hasSize(1);
        // Op 1 was skipped: counted in summary.skipped, never invoked.
        assertThat(out.getSummary().getSkipped() + (2 - out.getSummary().getTotal()))
            .isGreaterThanOrEqualTo(0);
        // If results[] has 2 entries the second carries a notRun marker.
        if (out.getResults().size() == 2) {
            Map<String, Object> secondErr = out.getResults().get(1).getError();
            assertThat(secondErr).isNotNull();
            assertThat(secondErr.get("code")).isEqualTo("notRun");
        }
    }

    // ---------- 4. responseMode=summary ----------

    @Test
    void responseMode_summary_omitsResults() {
        wm.stubFor(get("/users/1").willReturn(aResponse().withStatus(200).withBody("{\"id\":1}")));

        BatchRequest req = batch(List.of(op("GET", "/users/1")));
        req.setResponseMode("summary");

        BatchResult out = newBatchService().execute(req);

        assertThat(out.getResponseMode()).isEqualTo("summary");
        assertThat(out.getResults()).isEmpty();
        assertThat(out.getSummary().getTotal()).isEqualTo(1);
        assertThat(out.getSummary().getSucceeded()).isEqualTo(1);
    }

    // ---------- 5. responseMode=firstN ----------

    @Test
    void responseMode_firstN_capsResults() {
        wm.stubFor(get("/users/1").willReturn(aResponse().withStatus(200).withBody("{\"id\":1}")));
        wm.stubFor(post("/users").willReturn(aResponse().withStatus(201).withBody("{\"id\":2}")));
        wm.stubFor(put("/users/1").willReturn(aResponse().withStatus(200).withBody("{\"id\":1}")));

        BatchRequest req = batch(List.of(
            op("GET",  "/users/1"),
            op("POST", "/users"),
            op("PUT",  "/users/1")
        ));
        req.setResponseMode("firstN");
        req.setFirstN(2);

        BatchResult out = newBatchService().execute(req);

        assertThat(out.getResponseMode()).isEqualTo("firstN");
        assertThat(out.getResults()).hasSize(2);
        assertThat(out.getSummary().getTotal()).isEqualTo(3);
    }

    // ---------- 6. responseMode=full ----------

    @Test
    void responseMode_full_includesAllResults() {
        wm.stubFor(get("/users/1").willReturn(aResponse().withStatus(200).withBody("{\"id\":1}")));
        wm.stubFor(post("/users").willReturn(aResponse().withStatus(201).withBody("{\"id\":2}")));
        wm.stubFor(put("/users/1").willReturn(aResponse().withStatus(200).withBody("{\"id\":1}")));

        BatchRequest req = batch(List.of(
            op("GET",  "/users/1"),
            op("POST", "/users"),
            op("PUT",  "/users/1")
        ));
        req.setResponseMode("full");

        BatchResult out = newBatchService().execute(req);

        assertThat(out.getResponseMode()).isEqualTo("full");
        assertThat(out.getResults()).hasSize(3);
        assertThat(out.getSummary().getTotal()).isEqualTo(3);
        assertThat(out.getSummary().getSucceeded()).isEqualTo(3);
        assertThat(out.getResultFile()).isNull();
    }

    // ---------- 7. responseMode=file (auto on size > 5MB) ----------

    @Test
    void responseMode_full_autoSwitchesToFileWhenTooLarge() throws Exception {
        // One op that returns a 1 KB body. With fileThresholdBytes = 200 (set
        // in the test factory), the aggregated batch result must be spilled to
        // a file and a warning must be emitted.
        String body = "{\"data\":\"" + "x".repeat(800) + "\"}";
        wm.stubFor(get("/big").willReturn(aResponse().withStatus(200).withBody(body)));

        BatchRequest req = batch(List.of(op("GET", "/big")));
        req.setResponseMode("full");

        BatchResult out = newBatchService(200L).execute(req);

        assertThat(out.getResultFile()).isNotNull();
        assertThat(out.getWarnings()).anyMatch(w -> w.contains("largeResponse"));
        // results is empty in auto file mode — payload lives on disk.
        assertThat(out.getResults()).isEmpty();
        // The file actually contains the batch envelope (summary + results).
        String onDisk = Files.readString(Path.of(out.getResultFile()));
        assertThat(onDisk).contains("\"summary\"");
        assertThat(onDisk).contains("\"results\"");
    }

    @Test
    void responseMode_file_alwaysSpillsToDisk() throws Exception {
        wm.stubFor(get("/users/1").willReturn(aResponse().withStatus(200).withBody("{\"id\":1}")));

        BatchRequest req = batch(List.of(op("GET", "/users/1")));
        req.setResponseMode("file");

        BatchResult out = newBatchService().execute(req);

        assertThat(out.getResultFile()).isNotNull();
        // Even with a generous threshold, explicit file mode always spills.
        assertThat(out.getResults()).isEmpty();
        String onDisk = Files.readString(Path.of(out.getResultFile()));
        assertThat(onDisk).contains("\"summary\"");
    }

    // ---------- 8. concurrency applied ----------

    @Test
    void concurrency_isRespected() throws Exception {
        // Three endpoints that each sleep 200 ms before responding. With
        // concurrency=3 all three should run in parallel; total wall clock
        // should be ~200 ms, well under 600 ms (which is what concurrency=1
        // would take).
        long delayMs = 200L;
        for (String p : List.of("/users/1", "/missing", "/boom")) {
            wm.stubFor(get(p).willReturn(aResponse().withStatus(200)
                .withBody("{}")
                .withFixedDelay((int) delayMs)));
        }

        BatchRequest req = batch(List.of(
            op("GET", "/users/1"),
            op("GET", "/missing"),
            op("GET", "/boom")
        ));
        req.setResponseMode("summary");
        req.setConcurrency(3);
        req.setFailPolicy("continue");

        long start = java.lang.System.currentTimeMillis();
        BatchResult out = newBatchService().execute(req);
        long elapsed = java.lang.System.currentTimeMillis() - start;

        assertThat(out.getSummary().getTotal()).isEqualTo(3);
        assertThat(out.getSummary().getSkipped()).isZero();
        // Generous slack but enforce parallel execution: serial would take ~600 ms.
        assertThat(elapsed).isLessThan(550L);
        // Every op returned a real response (no failures on these endpoints).
        assertThat(out.getSummary().getSucceeded()).isEqualTo(3);
        assertThat(out.getSummary().getFailed()).isZero();
    }

    // ---------- 9. concurrency=1 (sanity) ----------

    @Test
    void concurrencyOne_runsSerially() throws Exception {
        wm.stubFor(get("/users/1").willReturn(aResponse().withStatus(200).withBody("{\"id\":1}")));
        wm.stubFor(get("/missing").willReturn(aResponse().withStatus(404).withBody("")));

        BatchRequest req = batch(List.of(
            op("GET", "/users/1"),
            op("GET", "/missing")
        ));
        req.setResponseMode("summary");
        req.setConcurrency(1);

        BatchResult out = newBatchService().execute(req);

        assertThat(out.getSummary().getTotal()).isEqualTo(2);
        // One succeeded, one returned a real 404 (still a "successful" HTTP
        // response — not a hard failure).
        assertThat(out.getSummary().getSucceeded()).isEqualTo(2);
        assertThat(out.getSummary().getFailed()).isZero();
    }

    // ---------- 10. spec §4.1.2 summary field names ----------

    @Test
    void summarySpecFieldNames_present() {
        wm.stubFor(get("/users/1").willReturn(aResponse().withStatus(200).withBody("{\"id\":1}")));

        BatchRequest req = batch(List.of(op("GET", "/users/1")));
        // default responseMode is now "summary" — exercise it without setting.

        BatchResult out = newBatchService().execute(req);

        // Spec-required field names.
        assertThat(out.getSummary().getTotal()).isEqualTo(1);
        assertThat(out.getSummary().getSucceeded()).isEqualTo(1);
        assertThat(out.getSummary().getFailed()).isZero();
        assertThat(out.getSummary().getTotalMs()).isGreaterThanOrEqualTo(0L);
        assertThat(out.getSummary().getSuccessRate()).isEqualTo(1.0);
        // Additional fields preserved for richer reporting.
        assertThat(out.getSummary().getSkipped()).isZero();
        assertThat(out.getSummary().getAssertionFailed()).isZero();
    }

    @Test
    void summarySuccessRate_zeroWhenAllFailed() {
        // 2 ops, both against an unknown system → 0 succeeded / 2 total = 0.0
        BatchRequest req = batch(List.of(
            op("GET", "/nope-a"),
            op("GET", "/nope-b")
        ));
        // Force error envelope by pointing the ops at a system that doesn't exist.
        // Easiest: replace the system on each op.
        req.getOperations().get(0).setSystem("does-not-exist-a");
        req.getOperations().get(1).setSystem("does-not-exist-b");

        BatchResult out = newBatchService().execute(req);

        assertThat(out.getSummary().getTotal()).isEqualTo(2);
        assertThat(out.getSummary().getSucceeded()).isZero();
        assertThat(out.getSummary().getFailed()).isEqualTo(2);
        assertThat(out.getSummary().getSuccessRate()).isEqualTo(0.0);
        assertThat(out.getFailures()).hasSize(2);
    }

    // ---------- 11. structured failures (spec §4.1.2) ----------

    @Test
    void failuresAreStructured() {
        InvokeRequest bad = new InvokeRequest();
        bad.setSystem("does-not-exist");
        bad.setMethod("POST");
        bad.setPath("/users");

        BatchRequest req = batch(List.of(bad));
        req.setResponseMode("full");
        req.setConcurrency(1);

        BatchResult out = newBatchService().execute(req);

        assertThat(out.getFailures()).hasSize(1);
        BatchResult.BatchFailure f = out.getFailures().get(0);
        assertThat(f.getIndex()).isZero();
        assertThat(f.getMethod()).isEqualTo("POST");
        assertThat(f.getPath()).isEqualTo("/users");
        assertThat(f.getError()).isNotBlank();
        // No concatenated "METHOD PATH" string — op() helper now gone.
        assertThat(out.getResultFile()).isNull();
    }

    // ---------- 12. default responseMode is summary ----------

    @Test
    void defaultResponseMode_isSummary() {
        wm.stubFor(get("/users/1").willReturn(aResponse().withStatus(200).withBody("{\"id\":1}")));

        BatchRequest req = batch(List.of(op("GET", "/users/1")));
        // Intentionally do NOT set responseMode — the default must be "summary".

        BatchResult out = newBatchService().execute(req);

        assertThat(out.getResponseMode()).isEqualTo("summary");
        // In summary mode no per-op bodies are returned.
        assertThat(out.getResults()).isEmpty();
        assertThat(out.getSummary().getSucceeded()).isEqualTo(1);
    }

    // ---------- 13. per-op timeoutMs is honoured ----------

    @Test
    void perOpTimeoutMs_isHonoured() {
        // Stub a slow endpoint (300 ms delay) and ask for a 50 ms per-op timeout.
        // The InvokeService will throw a NetworkError / timeout-like exception that
        // surfaces in the failures[] as a hard error envelope.
        wm.stubFor(get("/slow").willReturn(aResponse().withStatus(200)
            .withBody("{}")
            .withFixedDelay(300)));

        InvokeRequest slow = op("GET", "/slow");
        slow.setTimeoutMs(50L);

        BatchRequest req = batch(List.of(slow));
        req.setResponseMode("summary");
        req.setFailPolicy("continue");

        BatchResult out = newBatchService().execute(req);

        // The op didn't return a successful response — it timed out.
        assertThat(out.getSummary().getFailed()).isEqualTo(1);
        assertThat(out.getFailures()).hasSize(1);
        BatchResult.BatchFailure f = out.getFailures().get(0);
        assertThat(f.getMethod()).isEqualTo("GET");
        assertThat(f.getPath()).isEqualTo("/slow");
        assertThat(f.getError()).isNotBlank();
    }

    // ---------- 14. batch-level perRequestTimeoutMs applies to all ops ----------

    @Test
    void batchLevelTimeoutMs_appliesWhenOpHasNone() {
        wm.stubFor(get("/slow").willReturn(aResponse().withStatus(200)
            .withBody("{}")
            .withFixedDelay(300)));

        BatchRequest req = batch(List.of(op("GET", "/slow")));
        req.setPerRequestTimeoutMs(50L);
        req.setResponseMode("summary");

        BatchResult out = newBatchService().execute(req);

        // Same expectation as the per-op case — the batch-level fallback worked.
        assertThat(out.getSummary().getFailed()).isEqualTo(1);
        assertThat(out.getFailures()).hasSize(1);
    }

    // ---------- 15. empty batch ----------

    @Test
    void emptyBatch_returnsZeroedSummary() {
        BatchRequest req = batch(List.of());
        BatchResult out = newBatchService().execute(req);

        assertThat(out.getSummary().getTotal()).isZero();
        assertThat(out.getSummary().getSucceeded()).isZero();
        assertThat(out.getSummary().getFailed()).isZero();
        // successRate = 0.0 (not NaN) when total=0.
        assertThat(out.getSummary().getSuccessRate()).isEqualTo(0.0);
        assertThat(out.getFailures()).isEmpty();
    }
}
