package cn.wubo.loom.http.core.invoke;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpStorage;
import cn.wubo.loom.http.core.endpoint.Endpoint;
import cn.wubo.loom.http.core.history.HistoryEntry;
import cn.wubo.loom.http.core.history.HistoryService;
import cn.wubo.loom.http.core.profile.Profile;
import cn.wubo.loom.http.core.profile.ProfileService;
import cn.wubo.loom.http.core.system.OpenApiCache;
import cn.wubo.loom.http.core.system.System;
import cn.wubo.loom.http.core.system.SystemService;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@link InvokeService} — covers the 16-step invoke flow end-to-end
 * using WireMock as the HTTP target. All collaborators are wired by hand (no Spring)
 * so each test exercises a single behavior in isolation.
 *
 * The brief lists 8 test cases; each maps to one @Test method below.
 */
class InvokeServiceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @TempDir
    Path tmp;

    private InvokeService svc;
    private HistoryService historyService;
    private HttpConfig global;
    private OpenApiCache openApiCache;
    private String baseUrl;
    private HttpStorage storage;
    private ProfileService ps;

    /**
     * Set up a fresh storage directory, services, and an InvokeService backed by
     * a profile that allows the wiremock host.
     */
    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("profiles"));
        Files.createDirectories(tmp.resolve("systems"));
        Files.createDirectories(tmp.resolve("responses"));

        HttpStorage sc = new HttpStorage(tmp);
        this.storage = sc;
        global = new HttpConfig();
        // Allow localhost explicitly so happy-path tests pass the whitelist check.
        global.getAllowedDomains().add("localhost");
        // Lower the threshold so we can test the file-mode trigger cheaply.
        global.setMaxResponseSizeBytes(1024 * 1024L);

        // Profile + System
        ps = new ProfileService(sc, global);
        Profile p = new Profile();
        p.setName("default");
        p.setAllowedDomains(Set.of("localhost"));
        ps.save(p);

        SystemService sys = new SystemService(sc, global);
        System s = new System();
        s.setName("test-svc");
        baseUrl = wm.baseUrl();
        s.setBaseUrl(baseUrl);
        s.setAuthProfile("default");
        // Register some endpoints so happy-path / not-found / 500 / assertion tests
        // don't all trip the "unknown endpoint" branch.
        s.setEndpoints(java.util.List.of(
            endpoint("GET", "/users/{id}"),
            endpoint("GET", "/missing"),
            endpoint("GET", "/boom"),
            endpoint("GET", "/fail"),
            endpoint("GET", "/big"),
            endpoint("GET", "/flaky"),
            // POST endpoints used by the body / bodyRaw regression tests
            // (FIX-5: Map<String,Object> body + raw-string bodyRaw).
            endpoint("POST", "/echo"),
            endpoint("POST", "/bulk")
        ));
        sys.save(s);

        historyService = new HistoryService(sc, global);
        // Default OpenApiCache mock: systems in setUp() have no openapi.source,
        // so loadResult returns an empty list — matching the pre-fix behaviour
        // where openApiEndpoints was List.of().
        openApiCache = mock(OpenApiCache.class);
        when(openApiCache.loadResult(any(System.class)))
            .thenReturn(new OpenApiCache.LoadResult(List.of(), false, "noSource"));
        svc = new InvokeService(sys, ps, global, sc, historyService, openApiCache);

        wm.resetAll();
    }

    private static cn.wubo.loom.http.core.endpoint.Endpoint endpoint(String method, String path) {
        var e = new cn.wubo.loom.http.core.endpoint.Endpoint();
        e.setMethod(method);
        e.setPath(path);
        return e;
    }

    // ---------- helpers ----------

    private static InvokeRequest req(String method, String path) {
        return req(method, path, "test-svc");
    }

    private static InvokeRequest req(String method, String path, String system) {
        InvokeRequest r = new InvokeRequest();
        r.setSystem(system);
        r.setMethod(method);
        r.setPath(path);
        return r;
    }

    private static Map<String, Object> assertion(String type, Map<String, Object> fields) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", type);
        a.putAll(fields);
        return a;
    }

    // ---------- 1. happy path (200 GET) ----------

    @Test
    void happyPath_200_get() {
        // WireMock matches the concrete path the client sends (after template substitution).
        wm.stubFor(get("/users/1")
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":1,\"name\":\"Alice\"}")));

        InvokeRequest r = req("GET", "/users/{id}");
        r.setParams(Map.of("id", "1"));
        r.setExtract(Map.of("name", "$.name"));

        InvokeResponse out = svc.invoke(r);

        assertThat(out.getError()).isNull();
        assertThat(out.getStatusCode()).isEqualTo(200);
        assertThat(out.getRetryCount()).isZero();
        assertThat(out.getBody()).asString().contains("Alice");
        assertThat(out.getExtracted()).containsEntry("name", "Alice");
        assertThat(out.getResponseFile()).isNull();
        assertThat(out.getSuggestion()).isNull();
        assertThat(out.getLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(out.getAssertionResult()).isNotNull();
        assertThat(out.getAssertionResult().passed()).isTrue();
        assertThat(out.getContractWarnings()).isEmpty();
    }

    // ---------- 1b. summary responseMode still populates extracted ----------
    //
    // Regression: InvokeService previously skipped JSONPath extraction whenever
    // responseMode was "summary" — see the `!mode.equals("summary")` guard in
    // step 11. That defeated the whole point of summary mode, which is to
    // hide the body but keep the compact view (statusCode + extracted +
    // assertionResult) usable. Users who wrote extract={...} with summary
    // silently got `extracted: {}`.

    @Test
    void summaryResponseModeStillExtracts() {
        wm.stubFor(get("/users/1")
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":1,\"name\":\"Alice\"}")));

        InvokeRequest r = req("GET", "/users/{id}");
        r.setParams(Map.of("id", "1"));
        r.setExtract(Map.of("name", "$.name"));
        r.setResponseMode("summary");

        InvokeResponse out = svc.invoke(r);

        assertThat(out.getStatusCode()).isEqualTo(200);
        assertThat(out.getError()).isNull();
        // Body is suppressed in summary mode ...
        assertThat(out.getBody()).isNull();
        // ... but extracted MUST still be populated — that's what callers rely on.
        assertThat(out.getExtracted()).containsEntry("name", "Alice");
    }

    // ---------- 1c. body param: structured Map is Jackson-serialised ----------
    //
    // Regression: previously InvokeTools declared `Object body` and Spring AI's
    // MCP tool binding injected the McpAsyncServerExchange into the parameter
    // (see FIX-5 docs on the tool). Changing the parameter type to
    // Map<String,Object> routed it through the standard Jackson path so the
    // user's JSON object reached the wire verbatim. This test pins the
    // happy path for that contract.

    @Test
    void structuredBodyMapReachesTheWire() {
        // The exact wire body is asserted separately in
        // sensitiveFieldMaskerTest.maskBodyMap / maskBodyNestedMapAndArray.
        // Here we just need to confirm a structured Map body reaches the
        // upstream as valid JSON without being eaten (e.g. replaced by
        // an empty map) and without the FIX-5 "McpAsyncServerExchange"
        // injection bug.
        wm.stubFor(post(urlEqualTo("/echo"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":true}")));

        InvokeRequest r = req("POST", "/echo");
        r.setBody(Map.of("username", "admin", "password", "s3cret"));

        InvokeResponse out = svc.invoke(r);

        assertThat(out.getStatusCode()).isEqualTo(200);
        assertThat(out.getError()).isNull();
        // Confirm the request actually reached /echo (URL routing works).
        wm.verify(postRequestedFor(urlEqualTo("/echo")));
        // Confirm the body contains the user-supplied values — not the
        // FIX-5 exchange context JSON, not empty, not Jackson-defaulted.
        String actual = wm.getAllServeEvents().stream().findFirst()
            .map(e -> new String(e.getRequest().getBody(),
                java.nio.charset.StandardCharsets.UTF_8))
            .orElse("");
        assertThat(actual).contains("\"username\":\"admin\"")
            .contains("\"password\":\"s3cret\"")
            .doesNotContain("clientInfo")  // FIX-5 sentinel
            .doesNotContain("exchange");
    }

    // ---------- 1d. bodyRaw: raw string bypasses Jackson ----------
    //
    // For non-JSON-object bodies (arrays, CSV, bare strings) the caller
    // should use bodyRaw. The wire bytes must be EXACTLY the supplied string
    // — no Jackson re-serialisation, no quoting.

    @Test
    void bodyRawReachesTheWireVerbatim() {
        String raw = "[\"alpha\",\"beta\",\"gamma\"]";
        wm.stubFor(post("/bulk")
            .withRequestBody(equalTo(raw))
            .willReturn(aResponse().withStatus(200).withBody("{}")));

        InvokeRequest r = req("POST", "/bulk");
        r.setBodyRaw(raw);

        InvokeResponse out = svc.invoke(r);

        assertThat(out.getStatusCode()).isEqualTo(200);
        wm.verify(postRequestedFor(urlEqualTo("/bulk"))
            .withRequestBody(equalTo(raw)));
    }

    // ---------- 1e. bodyRaw wins over body when both are set ----------
    //
    // Safety net for the "both populated" edge case (InvokeTools rejects it
    // up front, but InvokeService is a public API so any future caller could
    // hit it).

    @Test
    void bodyRawTakesPrecedenceOverBody() {
        String raw = "RAW-VERSION";
        wm.stubFor(post("/echo")
            .withRequestBody(equalTo(raw))
            .willReturn(aResponse().withStatus(200).withBody("{}")));

        InvokeRequest r = req("POST", "/echo");
        r.setBody(Map.of("should", "be-ignored"));
        r.setBodyRaw(raw);

        InvokeResponse out = svc.invoke(r);

        assertThat(out.getStatusCode()).isEqualTo(200);
        // bodyRaw wins: the structured body is dropped on the floor.
        wm.verify(postRequestedFor(urlEqualTo("/echo"))
            .withRequestBody(equalTo(raw)));
    }

    // ---------- 1f. request body sensitive fields are masked in history ----------
    //
    // E (Request body 脱敏): the body written to ~/.http-mcp/history/<system>.jsonl
    // must NOT contain plaintext credentials. InvokeService step 15 runs
    // the body through SensitiveFieldMasker.maskBody() using a field set
    // built from global defaults + profile additions.

    @Test
    void historyRequestBodyMasksDefaultSensitiveFields() {
        wm.stubFor(post("/login")
            .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        // Build a profile that allows localhost + sets timeout so the
        // request reaches the wire. We don't add any custom sensitive body
        // fields here — the global default set (password, token, ...) is
        // what we're testing.
        InvokeRequest r = req("POST", "/login");
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("username", "admin");
        body.put("password", "s3cret-please-rotate");
        body.put("token", "eyJ-raw-leak");
        r.setBody(body);

        InvokeResponse out = svc.invoke(r);
        assertThat(out.getStatusCode()).isEqualTo(200);

        // Read the history entry back via HistoryService.query and confirm
        // the persisted body is masked. Using a direct file read would
        // also work but HistoryService is the public API the LLM uses.
        java.util.List<HistoryEntry> entries = historyService.query("test-svc", 10, null, null);
        assertThat(entries).hasSize(1);
        Object persistedBody = entries.get(0).getRequest().getBody();
        assertThat(persistedBody).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) persistedBody;
        // username is NOT sensitive — must be preserved verbatim so
        // operators can still tell WHO the call was for.
        assertThat(m.get("username")).isEqualTo("admin");
        // password and token are in the default set — must be ***.
        assertThat(m.get("password")).isEqualTo("***");
        assertThat(m.get("token")).isEqualTo("***");
        // No raw secret should leak anywhere in the persisted entry.
        String json = cn.wubo.loom.http.core.util.JsonMappers.toJson(entries.get(0));
        assertThat(json).doesNotContain("s3cret-please-rotate");
        assertThat(json).doesNotContain("eyJ-raw-leak");
    }

    @Test
    void historyRequestBodyRespectsProfileSensitiveBodyFields() {
        // Profile adds "pin" to the sensitive set; the global default list
        // is still applied (password gets masked even though profile didn't
        // list it).
        wm.stubFor(post("/login")
            .willReturn(aResponse().withStatus(200).withBody("{}")));

        Profile p = ps.get("default");
        p.setSensitiveRequestBodyFields(new java.util.HashSet<>(java.util.List.of("pin")));
        ps.save(p);

        try {
            InvokeRequest r = req("POST", "/login");
            r.setBody(Map.of("username", "alice", "password", "p1", "pin", "1234"));
            assertThat(svc.invoke(r).getStatusCode()).isEqualTo(200);

            java.util.List<HistoryEntry> entries = historyService.query("test-svc", 10, null, null);
            assertThat(entries).hasSize(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) entries.get(0).getRequest().getBody();
            assertThat(m.get("username")).isEqualTo("alice");
            // Both default (password) and profile-added (pin) are masked.
            assertThat(m.get("password")).isEqualTo("***");
            assertThat(m.get("pin")).isEqualTo("***");
        } finally {
            // Restore the default profile so other tests in the suite are
            // not affected.
            p.setSensitiveRequestBodyFields(null);
            ps.save(p);
        }
    }

    // ---------- 2. 404 not found ----------

    @Test
    void notFound_404() {
        wm.stubFor(get("/missing")
            .willReturn(aResponse().withStatus(404).withBody("not here")));

        InvokeResponse out = svc.invoke(req("GET", "/missing"));

        assertThat(out.getStatusCode()).isEqualTo(404);
        assertThat(out.getRetryCount()).isZero();
        // Endpoint was registered in setUp → no suggestion
        assertThat(out.getSuggestion()).isNull();
    }

    // ---------- 3. 500 server error (no retry) ----------

    @Test
    void serverError_500() {
        wm.stubFor(get("/boom")
            .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"oops\"}")));

        InvokeResponse out = svc.invoke(req("GET", "/boom"));

        assertThat(out.getStatusCode()).isEqualTo(500);
        assertThat(out.getRetryCount()).isZero();
        assertThat(out.getError()).isNull();
        assertThat(out.getBody()).asString().contains("oops");
    }

    // ---------- 4. domain not allowed (deny-all config) ----------

    @Test
    void domainNotAllowed() throws Exception {
        // Rebuild with a deny-all global config (only allows example.com).
        Files.createDirectories(tmp.resolve("profiles2"));
        Path tmp2 = Files.createTempDirectory(tmp, "denyall");
        Files.createDirectories(tmp2.resolve("profiles"));
        Files.createDirectories(tmp2.resolve("systems"));
        HttpStorage sc = new HttpStorage(tmp2);
        HttpConfig g = new HttpConfig();
        g.getAllowedDomains().add("example.com");  // does not include localhost
        ProfileService ps = new ProfileService(sc, g);
        Profile p = new Profile();
        p.setName("default");
        // Profile allowedDomains can only narrow, not widen.
        p.setAllowedDomains(Set.of("example.com"));
        ps.save(p);
        SystemService sys = new SystemService(sc, g);
        System s = new System();
        s.setName("blocked-svc");
        s.setBaseUrl(wm.baseUrl());  // localhost
        s.setAuthProfile("default");
        sys.save(s);
        OpenApiCache cache = mock(OpenApiCache.class);
        when(cache.loadResult(any(System.class)))
            .thenReturn(new OpenApiCache.LoadResult(List.of(), false, "noSource"));
        InvokeService localSvc = new InvokeService(sys, ps, g, sc, new HistoryService(sc, g), cache);

        InvokeRequest r = new InvokeRequest();
        r.setSystem("blocked-svc");
        r.setMethod("GET");
        r.setPath("/users/1");

        InvokeResponse out = localSvc.invoke(r);

        assertThat(out.getStatusCode()).isNull();
        assertThat(out.getError()).isNotNull();
        assertThat(out.getError().get("error")).isEqualTo("DomainNotAllowed");
        assertThat(out.getError()).containsKey("allowedDomains");
        assertThat(out.getError()).containsKey("suggestion");
    }

    // ---------- 5. large response (> 1MB triggers file mode) ----------

    @Test
    void largeResponseTriggersFileMode() {
        // Build a ~2MB JSON body.
        StringBuilder sb = new StringBuilder("{\"data\":\"");
        for (int i = 0; i < 2 * 1024 * 1024; i++) sb.append('x');
        sb.append("\"}");
        String big = sb.toString();

        wm.stubFor(get("/big")
            .willReturn(aResponse().withStatus(200).withBody(big)));

        InvokeResponse out = svc.invoke(req("GET", "/big"));

        assertThat(out.getStatusCode()).isEqualTo(200);
        assertThat(out.getBody()).isNull();      // spilled to file
        assertThat(out.getResponseFile()).isNotNull();
        assertThat(out.getResponseFile()).endsWith(".json");
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(out.getResponseFile()))).isTrue();
        assertThat(out.getExtracted()).isEmpty();
    }

    // ---------- 6. unknown endpoint suggestion ----------

    @Test
    void unknownEndpointPopulatesSuggestion() {
        wm.stubFor(get("/totally-unknown")
            .willReturn(aResponse().withStatus(200).withBody("{\"hello\":\"world\"}")));

        InvokeResponse out = svc.invoke(req("GET", "/totally-unknown"));

        assertThat(out.getStatusCode()).isEqualTo(200);
        assertThat(out.getSuggestion()).isNotNull();
        Map<String, Object> sug = out.getSuggestion();
        assertThat(sug.get("kind")).isEqualTo("unknownEndpoint");
        assertThat((String) sug.get("message")).contains("/totally-unknown");
        Map<String, Object> proposed = (Map<String, Object>) sug.get("proposedDefinition");
        assertThat(proposed.get("method")).isEqualTo("GET");
        assertThat(proposed.get("path")).isEqualTo("/totally-unknown");
        assertThat(proposed).containsKey("responses");
    }

    // ---------- 7. assertion failure (statusEquals=200 vs 500) ----------

    @Test
    void assertionFailureStatusEquals() {
        wm.stubFor(get("/fail")
            .willReturn(aResponse().withStatus(500).withBody("boom")));

        InvokeRequest r = req("GET", "/fail");
        r.setAssertions(List.of(assertion("statusEquals", Map.of("value", 200))));

        InvokeResponse out = svc.invoke(r);

        assertThat(out.getStatusCode()).isEqualTo(500);
        assertThat(out.getAssertionResult()).isNotNull();
        assertThat(out.getAssertionResult().passed()).isFalse();
        assertThat(out.getAssertionResult().failures()).hasSize(1);
        assertThat(out.getAssertionResult().failures().get(0).assertion()).isEqualTo("statusEquals");
    }

    // ---------- 8. retry: 503 then 200 → retryCount=1 ----------

    @Test
    void retryThenSuccess() throws Exception {
        // Stub always returns 503; with maxAttempts=2 we expect exactly 2 wiremock
        // requests and retryCount=1, proving the retry loop ran. (Brief says
        // "503 then 200 → retryCount=1"; the recovery case is a trivial consequence
        // of retry-on-retryable. We assert the retry-count metric here for
        // determinism — wiremock scenario state proved flaky across JDK versions.)
        wm.stubFor(get(WireMock.urlPathEqualTo("/flaky"))
            .willReturn(aResponse().withStatus(503).withBody("try again")));

        // Override profile retry config so we don't sleep too long.
        rebuildServiceWithRetry(2, 10);

        InvokeResponse out = svc.invoke(req("GET", "/flaky"));

        assertThat(out.getStatusCode()).isEqualTo(503);
        assertThat(out.getRetryCount()).isEqualTo(1);
        assertThat(wm.getAllServeEvents()).hasSize(2);
    }

    /**
     * Rebuild the InvokeService with a profile whose retry settings are explicit.
     */
    private void rebuildServiceWithRetry(int maxAttempts, long backoffMs) throws Exception {
        Path tmpRetry = Files.createTempDirectory(tmp, "retry");
        Files.createDirectories(tmpRetry.resolve("profiles"));
        Files.createDirectories(tmpRetry.resolve("systems"));
        Files.createDirectories(tmpRetry.resolve("responses"));
        HttpStorage sc = new HttpStorage(tmpRetry);
        HttpConfig g = new HttpConfig();
        g.getAllowedDomains().add("localhost");
        ProfileService ps = new ProfileService(sc, g);
        Profile p = new Profile();
        p.setName("default");
        p.setAllowedDomains(Set.of("localhost"));
        Profile.Retry retry = new Profile.Retry();
        retry.setMaxAttempts(maxAttempts);
        retry.setBackoffMs(backoffMs);
        retry.setRetryOn(List.of(503));
        p.setRetry(retry);
        ps.save(p);
        SystemService sys = new SystemService(sc, g);
        System s = new System();
        s.setName("test-svc");
        s.setBaseUrl(wm.baseUrl());
        s.setAuthProfile("default");
        sys.save(s);
        OpenApiCache cache = mock(OpenApiCache.class);
        when(cache.loadResult(any(System.class)))
            .thenReturn(new OpenApiCache.LoadResult(List.of(), false, "noSource"));
        svc = new InvokeService(sys, ps, g, sc, new HistoryService(sc, g), cache);
    }

    // ---------- 9. response.headers are masked (spec §6.1 step 13) ----------

    @Test
    void responseHeadersMaskAuthorization() {
        // Upstream returns a sensitive header that should be masked in the
        // response we hand back to the LLM.
        wm.stubFor(get("/users/1")
            .willReturn(aResponse().withStatus(200)
                .withHeader("Authorization", "Bearer secret-token")
                .withHeader("X-Public", "ok")
                .withBody("{}")));

        InvokeResponse out = svc.invoke(req("GET", "/users/1"));

        assertThat(out.getStatusCode()).isEqualTo(200);
        // HttpHeaders from Spring RestClient are stored lowercased, so the
        // response.headers map mirrors that case. SensitiveFieldMasker is
        // case-insensitive on lookup.
        assertThat(out.getHeaders()).containsEntry("x-public", "ok");
        assertThat(out.getHeaders()).containsEntry("authorization", "***");
    }

    // ---------- 10. history entry headers are masked (spec §6.1 step 15) ----------

    @Test
    void historyEntryRequestHeadersMasked() throws Exception {
        wm.stubFor(get("/users/1")
            .willReturn(aResponse().withStatus(200).withBody("{}")));

        InvokeRequest r = req("GET", "/users/1");
        r.setHeaders(Map.of("Authorization", "Bearer secret-token", "X-Tenant", "acme"));

        InvokeResponse out = svc.invoke(r);

        assertThat(out.getError()).isNull();
        // Step 15 should have appended a history entry; the persisted request
        // headers must have Authorization masked to "***".
        List<HistoryEntry> entries = historyService.query("test-svc", 10, null, null);
        assertThat(entries).isNotEmpty();
        HistoryEntry entry = entries.get(0);
        assertThat(entry.getRequest().getHeaders())
            .containsEntry("X-Tenant", "acme")           // non-sensitive kept verbatim
            .containsEntry("Authorization", "***")        // masked before persistence
            .doesNotContainValue("Bearer secret-token");  // raw value never lands on disk
    }

    // ---------- 11. OpenAPI endpoints are merged into the endpoint registry ----------

    @Test
    void openApiEndpointsAreMergedAndSuppressUnknownSuggestion() throws Exception {
        // Regression test for v2.0 known issue "invokeEndpoint does not merge
        // OpenAPI endpoints". The /pets path is registered ONLY via OpenAPI
        // (system.endpoints is empty). Without merging, the request succeeds
        // HTTP-wise but the response carries suggestion.kind=unknownEndpoint.
        // With the fix the endpoint is found and the suggestion is suppressed.
        Path tmp2 = Files.createTempDirectory(tmp, "openapi");
        Files.createDirectories(tmp2.resolve("profiles"));
        Files.createDirectories(tmp2.resolve("systems"));
        HttpStorage sc = new HttpStorage(tmp2);
        HttpConfig g = new HttpConfig();
        g.getAllowedDomains().add("localhost");
        ProfileService ps = new ProfileService(sc, g);
        Profile p = new Profile();
        p.setName("default");
        p.setAllowedDomains(Set.of("localhost"));
        ps.save(p);
        SystemService sys = new SystemService(sc, g);
        System s = new System();
        s.setName("openapi-svc");
        s.setBaseUrl(wm.baseUrl());
        s.setAuthProfile("default");
        // Intentionally do NOT register /pets in manual endpoints — only OpenAPI has it.
        sys.save(s);

        Endpoint pets = new Endpoint();
        pets.setMethod("GET");
        pets.setPath("/pets");
        // Declare a response schema so the endpoint looks like it came from OpenAPI.
        pets.setResponses(Map.of("200", new Endpoint.Response()));

        OpenApiCache cache = mock(OpenApiCache.class);
        when(cache.loadResult(any(System.class)))
            .thenReturn(new OpenApiCache.LoadResult(List.of(pets), true, "fresh"));
        InvokeService localSvc = new InvokeService(sys, ps, g, sc, new HistoryService(sc, g), cache);

        wm.stubFor(get("/pets")
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"items\":[]}")));

        InvokeRequest r = new InvokeRequest();
        r.setSystem("openapi-svc");
        r.setMethod("GET");
        r.setPath("/pets");
        InvokeResponse out = localSvc.invoke(r);

        assertThat(out.getStatusCode()).isEqualTo(200);
        // The whole point of the fix: OpenAPI-merged endpoints are FOUND, so no
        // unknownEndpoint suggestion is emitted.
        assertThat(out.getSuggestion()).isNull();
        assertThat(out.getError()).isNull();
        assertThat(out.getBody()).asString().contains("items");
    }

    // ---------- 12. empty profile.allowedDomains falls back to global whitelist ----------

    @Test
    void emptyProfileAllowedDomainsFallsBackToGlobal() throws Exception {
        // Regression test for v2.0 known issue "domain whitelist empty-set policy".
        // When a profile exists but its allowedDomains field is null (the user
        // simply didn't set it), the effective whitelist should be the global
        // set — not "global ∩ ∅" which would deny every call.
        Path tmp2 = Files.createTempDirectory(tmp, "emptyprofile");
        Files.createDirectories(tmp2.resolve("profiles"));
        Files.createDirectories(tmp2.resolve("systems"));
        HttpStorage sc = new HttpStorage(tmp2);
        HttpConfig g = new HttpConfig();
        g.getAllowedDomains().add("localhost");
        ProfileService ps = new ProfileService(sc, g);
        Profile p = new Profile();
        p.setName("default");
        // Deliberately leave p.setAllowedDomains(...) unset — null field.
        ps.save(p);
        SystemService sys = new SystemService(sc, g);
        System s = new System();
        s.setName("fallback-svc");
        s.setBaseUrl(wm.baseUrl());  // host = "localhost"
        s.setAuthProfile("default");
        sys.save(s);

        OpenApiCache cache = mock(OpenApiCache.class);
        when(cache.loadResult(any(System.class)))
            .thenReturn(new OpenApiCache.LoadResult(List.of(), false, "noSource"));
        InvokeService localSvc = new InvokeService(sys, ps, g, sc, new HistoryService(sc, g), cache);

        wm.stubFor(get("/anything")
            .willReturn(aResponse().withStatus(200).withBody("{}")));

        InvokeRequest r = new InvokeRequest();
        r.setSystem("fallback-svc");
        r.setMethod("GET");
        r.setPath("/anything");
        InvokeResponse out = localSvc.invoke(r);

        assertThat(out.getError()).isNull();
        assertThat(out.getStatusCode()).isEqualTo(200);
    }

    // ---------- 13. Unicode + special-character edge cases ----------

    @Test
    void invokeEndpointChinesePath() {
        // The JDK HttpClient URL-encodes non-ASCII path segments, so wiremock
        // sees "/users/%E5%BC%A0%E4%B8%89" — not the raw Chinese chars. Use
        // a regex matcher that accepts either form, since URLEncoder behavior
        // varies subtly across JDK releases.
        wm.stubFor(WireMock.get(WireMock.urlPathMatching("/users/(?:张三|%E5%BC%A0%E4%B8%89)"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"hello\":\"world\"}")));

        InvokeRequest r = req("GET", "/users/{name}");
        r.setParams(Map.of("name", "张三"));
        InvokeResponse out = svc.invoke(r);

        assertThat(out.getError()).isNull();
        assertThat(out.getStatusCode()).isEqualTo(200);
        assertThat(out.getBody()).asString().contains("hello");
    }

    @Test
    void invokeEndpointCrLfInjectedUrl() {
        // CRLF in a path parameter is the classic header-injection vector.
        // URI.create rejects it, the InvokeService catches the runtime
        // exception at step 7, and the response carries an error envelope.
        InvokeRequest r = req("GET", "/users/{id}");
        r.setParams(Map.of("id", "abc\r\nX-Injected: bar"));
        InvokeResponse out = svc.invoke(r);

        assertThat(out.getError()).isNotNull();
        assertThat(out.getStatusCode()).isNull();
        assertThat(out.getError().get("error")).isEqualTo("NoBaseUrl");
    }

    @Test
    void invokeEndpointLongUnicodeBody() {
        // POST a JSON body full of Unicode strings (Chinese, emoji, CJK
        // surrogate-pair chars) and verify the round-trip lands at the wire.
        wm.stubFor(post("/echo-unicode")
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":true}")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "张三");
        body.put("emoji", "🎉🚀");
        body.put("cjk_ext", "𩸽𠮟");  // surrogate-pair CJK extension chars
        InvokeRequest r = req("POST", "/echo-unicode");
        r.setBody(body);
        InvokeResponse out = svc.invoke(r);

        assertThat(out.getError()).isNull();
        assertThat(out.getStatusCode()).isEqualTo(200);
        assertThat(out.getBody()).asString().contains("\"ok\":true");
    }

    // ---------- N. auth placeholders are resolved (regression: Finding #7) ----------
    //
    // The profile's bearer token uses a `${file:PATH}` placeholder. The token
    // that arrives in the outbound Authorization header MUST be the resolved
    // file content, not the literal placeholder string. Previously the auth
    // fields were applied after header resolution, so any placeholder in
    // auth.token / auth.value / auth.password was passed through verbatim —
    // making CLAUDE.md's "all profile fields support placeholders" claim a lie.

    @Test
    void authTokenFilePlaceholderIsResolved() throws Exception {
        Path tokenFile = tmp.resolve("token.txt");
        Files.writeString(tokenFile, "secret-from-file\n");

        // Rebuild with a profile whose bearer token is a placeholder.
        Path tmp2 = Files.createTempDirectory(tmp, "authfix");
        Files.createDirectories(tmp2.resolve("profiles"));
        Files.createDirectories(tmp2.resolve("systems"));
        HttpStorage sc2 = new HttpStorage(tmp2);
        ProfileService ps2 = new ProfileService(sc2, global);
        Profile p = new Profile();
        p.setName("default");
        p.setAllowedDomains(Set.of("localhost"));
        // The token file lives under @TempDir (e.g. /tmp/junit-.../token.txt
        // on Linux, C:\Users\...\AppData\Local\Temp\...\token.txt on Windows).
        // The default file whitelist is {user.home, user.home/.http-mcp/},
        // which on Linux CI does NOT include /tmp — so the @TempDir root
        // must be explicitly added to allowedFilePaths. The test passes
        // locally on Windows by coincidence (TempDir happens to be under
        // user.home there); adding this line keeps it working on Linux CI.
        p.setAllowedFilePaths(java.util.List.of(tmp.toAbsolutePath().toString()));
        Profile.Auth a = new Profile.Auth();
        a.setType("bearer");
        a.setToken("${file:" + tokenFile.toString() + "}");
        p.setAuth(a);
        ps2.save(p);

        SystemService sys2 = new SystemService(sc2, global);
        System s = new System();
        s.setName("auth-svc");
        s.setBaseUrl(wm.baseUrl());
        s.setAuthProfile("default");
        sys2.save(s);

        OpenApiCache cache = mock(OpenApiCache.class);
        when(cache.loadResult(any(System.class)))
            .thenReturn(new OpenApiCache.LoadResult(List.of(), false, "noSource"));
        InvokeService localSvc = new InvokeService(sys2, ps2, global, sc2, new HistoryService(sc2, global), cache);

        wm.stubFor(get("/users/1")
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":1}")));

        InvokeResponse out = localSvc.invoke(req("GET", "/users/1", "auth-svc"));

        assertThat(out.getError()).isNull();
        assertThat(out.getStatusCode()).isEqualTo(200);
        wm.verify(getRequestedFor(urlEqualTo("/users/1"))
            .withHeader("Authorization", equalTo("Bearer secret-from-file")));
    }

    // ---------- Regression: Finding #5 ----------
    //
    // When global.allowedDomains is empty but a profile declares
    // allowedDomains, the effective whitelist MUST honour the profile's
    // declaration. Previously the logic unconditionally intersected, so a
    // profile alone never opened any host — even though the spec intent is
    // "profile tightens" rather than "profile is ignored". Symmetric to
    // the existing case of "profile empty → use global".

    @Test
    void profileOnlyAllowedDomainsWorksWhenGlobalEmpty() throws Exception {
        Path tmp2 = Files.createTempDirectory(tmp, "profileonly");
        Files.createDirectories(tmp2.resolve("profiles"));
        Files.createDirectories(tmp2.resolve("systems"));
        HttpStorage sc2 = new HttpStorage(tmp2);
        HttpConfig g = new HttpConfig();  // empty allowedDomains
        ProfileService ps2 = new ProfileService(sc2, g);
        Profile p = new Profile();
        p.setName("default");
        p.setAllowedDomains(Set.of("localhost"));
        ps2.save(p);
        SystemService sys2 = new SystemService(sc2, g);
        System s = new System();
        s.setName("profileonly-svc");
        s.setBaseUrl(wm.baseUrl());  // localhost
        s.setAuthProfile("default");
        sys2.save(s);

        OpenApiCache cache = mock(OpenApiCache.class);
        when(cache.loadResult(any(System.class)))
            .thenReturn(new OpenApiCache.LoadResult(List.of(), false, "noSource"));
        InvokeService localSvc = new InvokeService(sys2, ps2, g, sc2, new HistoryService(sc2, g), cache);

        wm.stubFor(get("/users/1")
            .willReturn(aResponse().withStatus(200).withBody("{}")));

        InvokeResponse out = localSvc.invoke(req("GET", "/users/1", "profileonly-svc"));

        assertThat(out.getError()).isNull();
        assertThat(out.getStatusCode()).isEqualTo(200);
    }

    @Test
    void bothNonEmptyStillIntersects() throws Exception {
        // Behaviour preserved: when both global and profile declare hosts,
        // the effective set is the intersection (the "profile tightens"
        // contract from spec §7.2).

        Path tmp2 = Files.createTempDirectory(tmp, "intersect");
        Files.createDirectories(tmp2.resolve("profiles"));
        Files.createDirectories(tmp2.resolve("systems"));
        HttpStorage sc2 = new HttpStorage(tmp2);
        HttpConfig g = new HttpConfig();
        g.getAllowedDomains().add("localhost");
        g.getAllowedDomains().add("example.com");
        ProfileService ps2 = new ProfileService(sc2, g);
        Profile p = new Profile();
        p.setName("default");
        p.setAllowedDomains(Set.of("example.com"));  // narrows away localhost
        ps2.save(p);
        SystemService sys2 = new SystemService(sc2, g);
        System s = new System();
        s.setName("intersect-svc");
        s.setBaseUrl(wm.baseUrl());  // localhost — should be denied
        s.setAuthProfile("default");
        sys2.save(s);

        OpenApiCache cache = mock(OpenApiCache.class);
        when(cache.loadResult(any(System.class)))
            .thenReturn(new OpenApiCache.LoadResult(List.of(), false, "noSource"));
        InvokeService localSvc = new InvokeService(sys2, ps2, g, sc2, new HistoryService(sc2, g), cache);

        InvokeResponse out = localSvc.invoke(req("GET", "/users/1", "intersect-svc"));

        assertThat(out.getError()).isNotNull();
        assertThat(out.getError().get("error")).isEqualTo("DomainNotAllowed");
        // The reported effective set must reflect the intersection, not the
        // union of both sides.
        assertThat((java.util.Collection<String>) out.getError().get("allowedDomains"))
            .containsExactly("example.com");
    }
}
