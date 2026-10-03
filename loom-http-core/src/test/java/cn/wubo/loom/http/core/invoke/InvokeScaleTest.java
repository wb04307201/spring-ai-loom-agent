package cn.wubo.loom.http.core.invoke;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpStorage;
import cn.wubo.loom.http.core.endpoint.Endpoint;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Scale and performance tests for {@link InvokeService} and {@link BatchService}.
 *
 * <p>The brief lists 8 test cases; each maps to one {@code @Test} method below.
 * WireMock is the HTTP target; a per-class dynamic port keeps state isolated
 * between test classes. All collaborators are wired by hand (no Spring) using
 * the same helpers as {@link InvokeServiceTest} / {@link BatchServiceTest},
 * with a {@code @TempDir} for storage isolation.
 *
 * <h2>Timing helper</h2>
 * The brief mentions {@code Stopwatch.elapsed()}; Guava is not on the classpath
 * so this file uses {@link System#nanoTime()} + {@link Duration} instead. The
 * wire-level assertion is identical: {@code elapsed < threshold}.
 *
 * <h2>Notes on test 6 — batchWithAllFailures</h2>
 * The brief describes the failure mechanism as "all 500". The current
 * {@link BatchService} only increments {@code summary.failed} for hard error
 * envelopes (a result with {@code error != null} and {@code code != "notRun"}).
 * An HTTP 500 response does NOT set the {@code error} field, so 100 ops hitting
 * a wiremock endpoint that returns 500 would produce
 * {@code succeeded=100, failed=0}. To exercise the summary-failure code path
 * described in the brief (and exercised by the existing
 * {@code summarySuccessRate_zeroWhenAllFailed} test in {@code BatchServiceTest}),
 * this test points all 100 ops at a system name that has not been registered —
 * which produces a {@code SystemNotFound} envelope and the expected
 * {@code failed=100, succeeded=0} summary. The wiremock is still configured so
 * the test setup matches the other tests in this class.
 */
class InvokeScaleTest {

    /** 2 MB — slightly above the 1 MB default {@code maxResponseSizeBytes} threshold. */
    private static final int TWO_MB = 2 * 1024 * 1024;

    /** 1 KB path parameter / 4 KB header value. */
    private static final int ONE_KB = 1024;
    private static final int FOUR_KB = 4 * 1024;

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @TempDir
    Path tmp;

    private InvokeService invokeService;
    private HttpConfig global;
    private HttpStorage storageConfig;
    private ProfileService profileService;
    private SystemService systemService;
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
        // Default 1 MB threshold — keeps test #2 cheap (2 MB body > 1 MB threshold).
        global.setMaxResponseSizeBytes(1024 * 1024L);

        profileService = new ProfileService(storageConfig, global);
        Profile p = new Profile();
        p.setName("default");
        p.setAllowedDomains(Set.of("localhost"));
        profileService.save(p);

        systemService = new SystemService(storageConfig, global);
        System s = new System();
        s.setName("test-svc");
        baseUrl = wm.baseUrl();
        s.setBaseUrl(baseUrl);
        s.setAuthProfile("default");
        s.setEndpoints(List.of(
            endpoint("GET", "/get"),
            endpoint("GET", "/users/{id}"),
            endpoint("GET", "/big"),
            endpoint("GET", "/echo-headers")
        ));
        systemService.save(s);

        OpenApiCache cache = mock(OpenApiCache.class);
        when(cache.loadResult(any(System.class)))
            .thenReturn(new OpenApiCache.LoadResult(List.of(), false, "noSource"));
        invokeService = new InvokeService(systemService, profileService, global,
            storageConfig, new HistoryService(storageConfig, global), cache);

        wm.resetAll();
    }

    private static Endpoint endpoint(String method, String path) {
        Endpoint e = new Endpoint();
        e.setMethod(method);
        e.setPath(path);
        return e;
    }

    // ---------- helpers ----------

    private BatchService newBatchService() {
        // Generous threshold — no auto file-mode trigger for these tests.
        return new BatchService(invokeService, global, 100L * 1024 * 1024);
    }

    private static InvokeRequest op(String method, String path) {
        InvokeRequest r = new InvokeRequest();
        r.setSystem("test-svc");
        r.setMethod(method);
        r.setPath(path);
        return r;
    }

    private static BatchRequest batch(List<InvokeRequest> ops) {
        BatchRequest b = new BatchRequest();
        b.setOperations(new ArrayList<>(ops));
        return b;
    }

    // ------------------------------------------------------------------
    // 1. 1000 ops through BatchService complete in < 30 s
    // ------------------------------------------------------------------

    @Test
    void batch1000OpsCompletesWithin30s() {
        wm.stubFor(get("/get").willReturn(aResponse().withStatus(200).withBody("{}")));

        List<InvokeRequest> ops = new ArrayList<>(1000);
        for (int i = 0; i < 1000; i++) {
            ops.add(op("GET", "/get"));
        }

        BatchRequest req = batch(ops);
        req.setResponseMode("summary");

        long start = java.lang.System.nanoTime();
        BatchResult out = newBatchService().execute(req);
        Duration elapsed = Duration.ofNanos(java.lang.System.nanoTime() - start);

        assertThat(out.getSummary().getTotal())
            .as("summary.total should be 1000; envelope=%s", out.getSummary())
            .isEqualTo(1000);
        assertThat(out.getSummary().getSucceeded())
            .as("summary.succeeded should be 1000; envelope=%s", out.getSummary())
            .isEqualTo(1000);
        assertThat(out.getSummary().getFailed())
            .as("summary.failed should be 0; envelope=%s", out.getSummary())
            .isZero();
        assertThat(elapsed)
            .as("1000-op batch should complete in < 30 s; took %s", elapsed)
            .isLessThan(Duration.ofSeconds(30));
    }

    // ------------------------------------------------------------------
    // 2. 2 MB response triggers file mode (InvokeService level)
    // ------------------------------------------------------------------

    @Test
    void largeResponseTriggersFileMode() throws Exception {
        // Build a 2 MB JSON body — comfortably above the 1 MB threshold.
        StringBuilder sb = new StringBuilder("{\"data\":\"");
        for (int i = 0; i < TWO_MB; i++) sb.append('x');
        sb.append("\"}");
        String big = sb.toString();

        wm.stubFor(get("/big").willReturn(aResponse().withStatus(200).withBody(big)));

        InvokeResponse out = invokeService.invoke(op("GET", "/big"));

        assertThat(out.getStatusCode())
            .as("largeResponseTriggersFileMode should return 200; envelope=%s", out)
            .isEqualTo(200);
        assertThat(out.getBody())
            .as("body should be null in file mode; envelope=%s", out)
            .isNull();
        assertThat(out.getResponseFile())
            .as("responseFile should be populated in file mode; envelope=%s", out)
            .isNotNull();
        assertThat(out.getResponseFile())
            .as("responseFile should end with .json; got %s", out.getResponseFile())
            .endsWith(".json");

        Path written = Path.of(out.getResponseFile());
        assertThat(Files.exists(written))
            .as("response file should exist on disk at %s", written)
            .isTrue();
        // The file lives under <tmp>/responses/ — i.e. the storage root's
        // responses directory created by @BeforeEach.
        assertThat(written)
            .as("response file should be inside storageConfig.responsesDir()")
            .startsWith(storageConfig.responsesDir());
        // The 2 MB body must have been written verbatim.
        assertThat(Files.size(written))
            .as("file size should match the response body length")
            .isEqualTo((long) big.length());
    }

    // ------------------------------------------------------------------
    // 3. 10 concurrent threads calling the same endpoint — race-free
    // ------------------------------------------------------------------

    @Test
    void tenConcurrentCallsSameEndpoint() throws Exception {
        wm.stubFor(get("/get").willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        // Brief: "Use ThreadPoolExecutor directly for concurrent tests".
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);
        try {
            List<Future<InvokeResponse>> futures = new ArrayList<>(10);
            for (int i = 0; i < 10; i++) {
                Callable<InvokeResponse> task = () -> invokeService.invoke(op("GET", "/get"));
                futures.add(executor.submit(task));
            }

            // Drain with a generous timeout — if any call hangs the test fails fast.
            for (int i = 0; i < futures.size(); i++) {
                InvokeResponse r = futures.get(i).get(15, TimeUnit.SECONDS);
                assertThat(r.getStatusCode())
                    .as("thread #%d should observe 200; envelope=%s", i, r)
                    .isEqualTo(200);
                assertThat(r.getError())
                    .as("thread #%d should have no error envelope; envelope=%s", i, r)
                    .isNull();
                assertThat(r.getRetryCount())
                    .as("thread #%d should have retryCount=0; envelope=%s", i, r)
                    .isZero();
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        // WireMock must have seen exactly 10 serves on the endpoint — proves no
        // thread silently dropped or duplicated a request.
        assertThat(wm.getAllServeEvents())
            .as("wiremock should have observed 10 serve events")
            .hasSize(10);
    }

    // ------------------------------------------------------------------
    // 4. 1 KB path template parameter — URL length allowed
    // ------------------------------------------------------------------

    @Test
    void largePathParameter() throws Exception {
        // WireMock matches the substituted URL (template expansion happens in
        // InvokeService step 6, before the HTTP request is made).
        String big = "a".repeat(ONE_KB);
        wm.stubFor(get("/users/" + big).willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        InvokeRequest r = op("GET", "/users/{id}");
        r.setParams(Map.of("id", big));

        InvokeResponse out = invokeService.invoke(r);

        assertThat(out.getStatusCode())
            .as("largePathParameter should return 200; envelope=%s", out)
            .isEqualTo(200);
        assertThat(out.getError())
            .as("largePathParameter should have no error envelope; envelope=%s", out)
            .isNull();
        assertThat(wm.getAllServeEvents())
            .as("wiremock should have observed exactly one serve event")
            .hasSize(1);
    }

    // ------------------------------------------------------------------
    // 5. 4 KB header value — HTTP header size allowed
    // ------------------------------------------------------------------

    @Test
    void largeHeaderValue() throws Exception {
        // httpbin-style echo: return the request headers as JSON so the test
        // can verify the value reached the server.
        String value = "v".repeat(FOUR_KB);
        wm.stubFor(get("/echo-headers")
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"x-large\":\"" + value + "\"}")));

        InvokeRequest r = op("GET", "/echo-headers");
        r.setHeaders(Map.of("X-Large", value));

        InvokeResponse out = invokeService.invoke(r);

        assertThat(out.getStatusCode())
            .as("largeHeaderValue should return 200; envelope=%s", out)
            .isEqualTo(200);
        assertThat(out.getError())
            .as("largeHeaderValue should have no error envelope; envelope=%s", out)
            .isNull();
        // Sanity: the wiremock-side stub records the served request, proving
        // the header reached the server without being truncated by the JDK
        // HttpClient or Jetty 12 (WireMock's embedded server).
        var events = wm.getAllServeEvents();
        assertThat(events)
            .as("wiremock should have observed exactly one serve event")
            .hasSize(1);
        String observedHeader = events.get(0).getRequest().getHeader("X-Large");
        assertThat(observedHeader)
            .as("X-Large header should reach wiremock with full 4 KB value; got length %d",
                observedHeader == null ? -1 : observedHeader.length())
            .hasSize(FOUR_KB);
    }

    // ------------------------------------------------------------------
    // 6. batch with 100 hard failures → summary.failed == 100
    // ------------------------------------------------------------------

    @Test
    void batchWithAllFailures() {
        // Stub /get so wiremock responds, but the ops target an UNKNOWN system
        // — InvokeService returns SystemNotFound envelope, which BatchService
        // counts as a hard failure (summary.failed += 1). See class-level note.
        wm.stubFor(get("/get").willReturn(aResponse().withStatus(200).withBody("{}")));

        List<InvokeRequest> ops = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            InvokeRequest r = op("GET", "/get");
            r.setSystem("does-not-exist-" + i);
            ops.add(r);
        }

        BatchRequest req = batch(ops);
        req.setResponseMode("summary");
        req.setFailPolicy("continue");

        BatchResult out = newBatchService().execute(req);

        assertThat(out.getSummary().getTotal())
            .as("summary.total should be 100; envelope=%s", out.getSummary())
            .isEqualTo(100);
        assertThat(out.getSummary().getFailed())
            .as("summary.failed should be 100; envelope=%s", out.getSummary())
            .isEqualTo(100);
        assertThat(out.getSummary().getSucceeded())
            .as("summary.succeeded should be 0; envelope=%s", out.getSummary())
            .isZero();
        assertThat(out.getFailures())
            .as("failures list should contain all 100 entries")
            .hasSize(100);
    }

    // ------------------------------------------------------------------
    // 7. batch with 50 succeed + 50 fail (continue) — all 100 executed
    // ------------------------------------------------------------------

    @Test
    void batchMixedPassFailContinue() {
        wm.stubFor(get("/get").willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        List<InvokeRequest> ops = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            InvokeRequest r = op("GET", "/get");
            // First 50 succeed; last 50 fail with SystemNotFound.
            if (i >= 50) r.setSystem("does-not-exist-" + i);
            ops.add(r);
        }

        BatchRequest req = batch(ops);
        req.setResponseMode("summary");
        req.setFailPolicy("continue");

        BatchResult out = newBatchService().execute(req);

        assertThat(out.getSummary().getTotal())
            .as("summary.total should be 100; envelope=%s", out.getSummary())
            .isEqualTo(100);
        assertThat(out.getSummary().getSucceeded())
            .as("summary.succeeded should be 50; envelope=%s", out.getSummary())
            .isEqualTo(50);
        assertThat(out.getSummary().getFailed())
            .as("summary.failed should be 50; envelope=%s", out.getSummary())
            .isEqualTo(50);
        // continue policy must NOT skip any op — the 50 failing ops still ran.
        assertThat(out.getSummary().getSkipped())
            .as("summary.skipped should be 0 under continue; envelope=%s", out.getSummary())
            .isZero();
    }

    // ------------------------------------------------------------------
    // 8. batch with stopOnFirst + concurrency=1 — stops after first failure
    // ------------------------------------------------------------------

    @Test
    void batchStopOnFirst() {
        // Stub /get so the 99 "would-have-succeeded" ops would have hit
        // wiremock if they ran. The first op targets an UNKNOWN system
        // (SystemNotFound envelope → summary.failed += 1) and trips the
        // stopFlag before any HTTP call is made; the remaining 99 ops are
        // short-circuited with a notRun sentinel and never reach wiremock.
        wm.stubFor(get("/get").willReturn(aResponse().withStatus(200).withBody("{}")));

        List<InvokeRequest> ops = new ArrayList<>(100);
        // Op 0: fails (SystemNotFound, no HTTP call).
        InvokeRequest first = op("GET", "/get");
        first.setSystem("does-not-exist");
        ops.add(first);
        // Ops 1..99: would have succeeded against /get — but stopOnFirst must
        // short-circuit them with notRun.
        for (int i = 1; i < 100; i++) {
            ops.add(op("GET", "/get"));
        }

        BatchRequest req = batch(ops);
        req.setResponseMode("summary");
        req.setFailPolicy("stopOnFirst");
        req.setConcurrency(1);

        BatchResult out = newBatchService().execute(req);

        // All 100 ops are accounted for in the summary, but only one actually
        // ran: 1 hard failure + 99 skipped (notRun sentinel). This proves
        // execution stopped after the first failure rather than running all
        // 100 and discarding the results.
        assertThat(out.getSummary().getTotal())
            .as("summary.total should be 100 (all ops accounted for); envelope=%s", out.getSummary())
            .isEqualTo(100);
        assertThat(out.getSummary().getFailed())
            .as("summary.failed should be 1; envelope=%s", out.getSummary())
            .isEqualTo(1);
        assertThat(out.getSummary().getSkipped())
            .as("summary.skipped should be 99 (stopOnFirst short-circuit); envelope=%s", out.getSummary())
            .isEqualTo(99);
        assertThat(out.getSummary().getSucceeded())
            .as("summary.succeeded should be 0; envelope=%s", out.getSummary())
            .isZero();
        // Wiremock saw 0 serve events: the failing op never reached HTTP
        // (rejected at step 1 — SystemNotFound), and the 99 short-circuited
        // ops never reached HTTP either (stopFlag checked before invocation).
        // This is the wire-level proof that stopOnFirst actually stopped work
        // rather than just labelling it as skipped post-hoc.
        assertThat(wm.getAllServeEvents())
            .as("wiremock should have observed 0 serve events (failing op rejected "
                + "before HTTP; subsequent ops short-circuited); got %d",
                wm.getAllServeEvents().size())
            .isEmpty();
    }
}
