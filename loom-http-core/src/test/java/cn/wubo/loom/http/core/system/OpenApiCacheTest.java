package cn.wubo.loom.http.core.system;

import cn.wubo.loom.http.core.HttpStorage;
import cn.wubo.loom.http.core.endpoint.Endpoint;
import cn.wubo.loom.http.core.invoke.ContractValidator;
import cn.wubo.loom.http.core.invoke.MergedEndpoint;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link OpenApiCache}.
 * <p>
 * Covers:
 * - file source: parse fixture and verify 3 endpoints + parameters preserved
 * - file source: mtime check skips parse when unchanged
 * - URL source: download, write to cache, return parsed endpoints
 * - URL source: etag-based 304 returns the previously cached endpoints
 * - empty source: returns empty list
 * - malformed spec: throws a clear RuntimeException
 */
class OpenApiCacheTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort().gzipDisabled(true))
        .build();

    @TempDir
    Path tmp;

    private HttpStorage storage;
    private OpenApiCache cache;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("systems"));
        storage = new HttpStorage(tmp);
        RestClient restClient = RestClient.builder().build();
        cache = new OpenApiCache(storage, restClient);
        wm.resetAll();
    }

    private static System system(String name, String source) {
        System s = new System();
        s.setName(name);
        s.setOpenapi(new System.OpenApi());
        s.getOpenapi().setSource(source);
        return s;
    }

    private static String loadFixture(String name) throws Exception {
        return new String(new ClassPathResource("openapi/" + name).getInputStream().readAllBytes());
    }

    // ---------- 1. file source: parse + verify endpoint count ----------

    @Test
    void fileSourceParsesThreeEndpoints() throws Exception {
        Path fixture = tmp.resolve("petstore.json");
        Files.writeString(fixture, loadFixture("petstore.json"));

        System s = system("petstore", fixture.toString());

        List<Endpoint> endpoints = cache.load(s);

        assertThat(endpoints).hasSize(3);
        assertThat(endpoints).extracting(Endpoint::getMethod).containsExactly("GET", "POST", "GET");
        assertThat(endpoints).extracting(Endpoint::getPath).containsExactly("/pets", "/pets", "/pets/{id}");
        assertThat(endpoints).extracting(Endpoint::getSummary)
            .containsExactly("List pets", "Create pet", "Get pet");
    }

    // ---------- 2. parameters preserved on GET /pets/{id} ----------

    @Test
    void parametersArePreserved() throws Exception {
        Path fixture = tmp.resolve("petstore.json");
        Files.writeString(fixture, loadFixture("petstore.json"));

        System s = system("petstore", fixture.toString());

        List<Endpoint> endpoints = cache.load(s);

        Endpoint getById = endpoints.stream()
            .filter(e -> "GET".equals(e.getMethod()) && "/pets/{id}".equals(e.getPath()))
            .findFirst().orElseThrow();

        assertThat(getById.getParameters()).hasSize(1);
        Endpoint.Parameter id = getById.getParameters().get(0);
        assertThat(id.getName()).isEqualTo("id");
        assertThat(id.getIn()).isEqualTo("path");
        assertThat(id.isRequired()).isTrue();
        assertThat(id.getSchema()).containsEntry("type", "string");
    }

    // ---------- 3. URL source: download + write cache + parse ----------

    @Test
    void urlSourceDownloadsAndCaches() throws Exception {
        String body = loadFixture("petstore.json");
        wm.stubFor(get(urlEqualTo("/openapi.json"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("ETag", "\"v1\"")
                .withBody(body)));

        System s = system("petstore", wm.baseUrl() + "/openapi.json");

        List<Endpoint> endpoints = cache.load(s);

        assertThat(endpoints).hasSize(3);
        // Cache file written under systems/<name>/openapi.json
        Path cacheFile = storage.systemsDir().resolve("petstore").resolve("openapi.json");
        assertThat(cacheFile).exists();
        assertThat(Files.readString(cacheFile)).isEqualTo(body);
        // etag captured on system
        assertThat(s.getOpenapi().getEtag()).isEqualTo("\"v1\"");
    }

    // ---------- 4. URL source: 304 keeps existing cache ----------

    @Test
    void urlSource304ReturnsCachedEndpoints() throws Exception {
        String body = loadFixture("petstore.json");
        // First request (no If-None-Match) → 200 + ETag + body.
        // Subsequent requests (with If-None-Match="v1") → 304.
        wm.stubFor(get(urlEqualTo("/openapi.json"))
            .withHeader("If-None-Match", absent())
            .willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"v1\"")
                .withBody(body)));
        wm.stubFor(get(urlEqualTo("/openapi.json"))
            .withHeader("If-None-Match", equalTo("\"v1\""))
            .willReturn(aResponse().withStatus(304)));

        System s = system("petstore", wm.baseUrl() + "/openapi.json");
        List<Endpoint> first = cache.load(s);
        assertThat(first).hasSize(3);

        // Cache file written by first load.
        Path cacheFile = storage.systemsDir().resolve("petstore").resolve("openapi.json");
        assertThat(cacheFile).exists();
        long sizeAfterFirst = Files.size(cacheFile);

        // Second load: server returns 304 → cache must be reused, not overwritten.
        List<Endpoint> second = cache.load(s);
        assertThat(second).hasSize(3);
        assertThat(second).extracting(Endpoint::getPath).containsExactly("/pets", "/pets", "/pets/{id}");
        assertThat(Files.size(cacheFile)).isEqualTo(sizeAfterFirst);

        wm.verify(getRequestedFor(urlEqualTo("/openapi.json"))
            .withHeader("If-None-Match", equalTo("\"v1\"")));
    }

    // ---------- 5. no source: returns empty list ----------

    @Test
    void noSourceReturnsEmpty() {
        System s = system("petstore", null);
        assertThat(cache.load(s)).isEmpty();
    }

    // ---------- 6. malformed spec throws ----------

    @Test
    void malformedSpecThrows() throws Exception {
        Path bad = tmp.resolve("bad.json");
        Files.writeString(bad, "{ this is not json");
        System s = system("bad", bad.toString());

        assertThatThrownBy(() -> cache.load(s))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("bad");
    }

    // ---------- 7. Swagger 2.0 fixture: auto-converted via OpenAPIParser facade ----------

    /**
     * Verifies that a Swagger 2.0 document is auto-detected and converted to the
     * OpenAPI 3 model by the {@link io.swagger.parser.OpenAPIParser} facade
     * (which uses the bundled {@code swagger-parser-v2-converter} SPI extension).
     * <p>
     * Prior to switching the parser to the facade, the OpenAPIV3Parser used by
     * this cache was unable to ingest Swagger 2.0 documents, so the test name
     * keeps that history visible.
     */
    @Test
    @DisplayName("parsesSwagger2WithOpenAPIv3Parser: facade auto-converts Swagger 2.0")
    void parsesSwagger2WithOpenAPIv3Parser() throws Exception {
        Path fixture = tmp.resolve("swagger2.json");
        Files.writeString(fixture, loadFixture("swagger2.json"));

        System s = system("legacy", fixture.toString());

        List<Endpoint> endpoints = cache.load(s);

        // Swagger 2.0 has 3 operations (GET /users, POST /users, GET /users/{id}).
        assertThat(endpoints).hasSize(3);
        assertThat(endpoints).extracting(Endpoint::getMethod)
            .containsExactly("GET", "POST", "GET");
        assertThat(endpoints).extracting(Endpoint::getPath)
            .containsExactly("/users", "/users", "/users/{id}");
        assertThat(endpoints).extracting(Endpoint::getSummary)
            .containsExactly("List users", "Create user", "Get user");

        // Path parameter on GET /users/{id} is preserved.
        Endpoint getById = endpoints.stream()
            .filter(e -> "GET".equals(e.getMethod()) && "/users/{id}".equals(e.getPath()))
            .findFirst().orElseThrow();
        assertThat(getById.getParameters()).hasSize(1);
        Endpoint.Parameter id = getById.getParameters().get(0);
        assertThat(id.getName()).isEqualTo("id");
        assertThat(id.getIn()).isEqualTo("path");
        assertThat(id.isRequired()).isTrue();
        assertThat(id.getSchema()).containsEntry("type", "string");
    }

    // ---------- 8. Deep $ref chain fixture: all refs resolved inline ----------

    /**
     * Verifies that a 5-level deep {@code $ref} chain ({@code Level1 → Level2 →
     * Level3 → Level4 → Level5}) is fully resolved by the parser. The cache
     * keeps its {@link OpenApiCache#load(System)} contract — callers see
     * flattened, inlined schemas — and the {@link ContractValidator} (which
     * walks {@code properties} shallowly) must not crash on this structure.
     */
    @Test
    @DisplayName("parsesDeepRefChain: 5-level $ref chain resolves inline")
    void parsesDeepRefChain() throws Exception {
        Path fixture = tmp.resolve("deep-ref.json");
        Files.writeString(fixture, loadFixture("deep-ref.json"));

        System s = system("deepref", fixture.toString());

        List<Endpoint> endpoints = cache.load(s);

        // One operation, one response, one media type — schema present.
        assertThat(endpoints).hasSize(1);
        Endpoint ep = endpoints.get(0);
        Endpoint.Response resp = ep.getResponses().get("200");
        assertThat(resp).isNotNull();
        Endpoint.MediaType mt = resp.getContent().get("application/json");
        assertThat(mt).isNotNull();

        Map<String, Object> schema = mt.getSchema();
        assertThat(schema).isNotNull();

        // After resolution the top-level $ref pointer should be gone — either
        // the schema is inlined directly or the parser has stamped it with the
        // resolved concrete shape. Walk 5 levels deep via the "child" key and
        // confirm each level carries the expected property name with a primitive
        // type (or further nested object).
        assertThat(schema).containsKey("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> l1Props = (Map<String, Object>) schema.get("properties");
        assertThat(l1Props).containsOnlyKeys("id", "child");

        // Level 1 → child is an inlined object (Level2 resolved), not a $ref.
        @SuppressWarnings("unchecked")
        Map<String, Object> l2 = (Map<String, Object>) l1Props.get("child");
        assertThat(l2).doesNotContainKey("$ref");
        assertThat(l2).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        Map<String, Object> l2Props = (Map<String, Object>) l2.get("properties");
        assertThat(l2Props).containsOnlyKeys("label", "child");

        // Level 2 → child resolves to Level3.
        @SuppressWarnings("unchecked")
        Map<String, Object> l3 = (Map<String, Object>) l2Props.get("child");
        assertThat(l3).doesNotContainKey("$ref");
        assertThat(l3).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        Map<String, Object> l3Props = (Map<String, Object>) l3.get("properties");
        assertThat(l3Props).containsOnlyKeys("value", "child");

        // Level 3 → child resolves to Level4.
        @SuppressWarnings("unchecked")
        Map<String, Object> l4 = (Map<String, Object>) l3Props.get("child");
        assertThat(l4).doesNotContainKey("$ref");
        assertThat(l4).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        Map<String, Object> l4Props = (Map<String, Object>) l4.get("properties");
        assertThat(l4Props).containsOnlyKeys("name", "child");

        // Level 4 → child resolves to Level5 (the deepest level — no further $ref).
        @SuppressWarnings("unchecked")
        Map<String, Object> l5 = (Map<String, Object>) l4Props.get("child");
        assertThat(l5).doesNotContainKey("$ref");
        assertThat(l5).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        Map<String, Object> l5Props = (Map<String, Object>) l5.get("properties");
        assertThat(l5Props).containsOnlyKeys("deepest");
    }

    // ---------- 9. oneOf / anyOf fixture: parser + ContractValidator don't crash ----------

    /**
     * Verifies that a response schema using {@code oneOf} (discriminated union)
     * or {@code anyOf} (intersection) is parsed without errors and that the
     * minimal {@link ContractValidator} gracefully treats union-typed property
     * schemas as "no declared type" — producing no warnings, never throwing,
     * even when the runtime payload does not match any union branch.
     * <p>
     * The contract validator deliberately does not implement union matching in
     * this revision (see ContractValidator {@code typeOfSchema} — returns null
     * when no {@code type} is declared, e.g. for {@code oneOf}/{@code anyOf}).
     * This test pins that contract: a spec with composed schemas must not
     * break the validator.
     */
    @Test
    @DisplayName("parsesOneOfAnyOf: composed schemas parse and validate gracefully")
    void parsesOneOfAnyOf() throws Exception {
        Path fixture = tmp.resolve("union.json");
        Files.writeString(fixture, loadFixture("union.json"));

        System s = system("union", fixture.toString());

        List<Endpoint> endpoints = cache.load(s);

        // Both operations are exposed.
        assertThat(endpoints).hasSize(2);
        assertThat(endpoints).extracting(Endpoint::getPath)
            .containsExactly("/events", "/alerts");

        // GET /events response schema exposes the oneOf-typed "event" property.
        Endpoint events = endpoints.stream()
            .filter(e -> "/events".equals(e.getPath()))
            .findFirst().orElseThrow();
        Map<String, Object> eventSchema = events.getResponses().get("200")
            .getContent().get("application/json").getSchema();
        assertThat(eventSchema).containsKey("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> eventProps = (Map<String, Object>) eventSchema.get("properties");
        assertThat(eventProps).containsKey("event");
        @SuppressWarnings("unchecked")
        Map<String, Object> eventField = (Map<String, Object>) eventProps.get("event");
        assertThat(eventField).containsKey("oneOf");
        assertThat(eventField.get("oneOf")).isInstanceOf(List.class);
        // two union branches (UserEvent + SystemEvent).
        assertThat((List<?>) eventField.get("oneOf")).hasSize(2);
        // discriminator preserved.
        @SuppressWarnings("unchecked")
        Map<String, Object> discriminator = (Map<String, Object>) eventField.get("discriminator");
        assertThat(discriminator).containsEntry("propertyName", "kind");

        // GET /alerts response schema exposes the anyOf-typed "payload" property.
        Endpoint alerts = endpoints.stream()
            .filter(e -> "/alerts".equals(e.getPath()))
            .findFirst().orElseThrow();
        Map<String, Object> alertSchema = alerts.getResponses().get("200")
            .getContent().get("application/json").getSchema();
        assertThat(alertSchema).containsKey("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> alertProps = (Map<String, Object>) alertSchema.get("properties");
        assertThat(alertProps).containsKey("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> payloadField = (Map<String, Object>) alertProps.get("payload");
        assertThat(payloadField).containsKey("anyOf");
        assertThat((List<?>) payloadField.get("anyOf")).hasSize(2);

        // ContractValidator must not crash on a union-typed response.
        ContractValidator validator = new ContractValidator();
        MergedEndpoint merged = new MergedEndpoint(events, "manual", Instant.now());

        // (1) A body whose only top-level field is "event" (oneOf-typed) — the
        //     validator skips the union field (no "type" declared) and reports
        //     no warnings for the envelope's own typed "id" field.
        List<String> warnings = validator.validate(200, "{\"id\":\"abc\",\"event\":{\"kind\":\"user\"}}", merged);
        assertThat(warnings).isEmpty();

        // (2) A body with an unexpected top-level field — the validator flags
        //     it (the union field itself is still skipped because it has no
        //     declared type).
        warnings = validator.validate(200, "{\"id\":\"abc\",\"sneaky\":\"value\"}", merged);
        assertThat(warnings).contains("unexpectedField:sneaky");

        // (3) Non-JSON body returns the canonical nonJsonResponse warning —
        //     never throws.
        warnings = validator.validate(200, "not even json", merged);
        assertThat(warnings).containsExactly("nonJsonResponse");
    }

    // ---------- 10. HTTP fetch failure with cache present → falls back to staleCache ----------
    //
    // Regression: Finding #N — the original loadResult threw when fetchHttp
    // failed (IO error, timeout, connection refused) and propagated up to
    // listEndpoints, where the try/catch in EndpointTools.collectMerged
    // swallowed it into an empty endpoint list. invokeEndpoint also broke
    // because step 3 of the pipeline needs the endpoint schema before it can
    // build the request. The fix catches the fetch failure and falls back
    // to the on-disk cache, tagged reason="staleCache", so callers can
    // distinguish "fresh" from "served from stale copy".

    @Test
    void httpSourceFailureFallsBackToStaleCache() throws Exception {
        String body = loadFixture("petstore.json");
        // First load: server returns 200 + body + ETag → cache populated.
        wm.stubFor(get(urlEqualTo("/openapi.json"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("ETag", "\"v1\"")
                .withBody(body)));

        System s = system("petstore", wm.baseUrl() + "/openapi.json");
        OpenApiCache.LoadResult first = cache.loadResult(s);
        assertThat(first.endpoints()).hasSize(3);
        assertThat(first.refreshed()).isTrue();
        assertThat(first.reason()).isNull();

        // Second load: server now fails at the network layer (connection
        // reset) — cache file must still be there from the first load.
        wm.resetAll();
        wm.stubFor(get(urlEqualTo("/openapi.json"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        OpenApiCache.LoadResult second = cache.loadResult(s);
        // Endpoints unchanged — we read the cached copy.
        assertThat(second.endpoints()).hasSize(3);
        assertThat(second.endpoints().get(0).getPath()).isEqualTo("/pets");
        // Reason flags this as a stale read, not a fresh fetch.
        assertThat(second.refreshed()).isFalse();
        assertThat(second.reason()).isEqualTo("staleCache");
    }

    // ---------- 11. HTTP fetch failure with NO cache → rethrows original error ----------

    @Test
    void httpSourceFailureWithNoCacheRethrows() {
        // No prior load → no cache file on disk. WireMock returns a network
        // fault. The cache must NOT swallow the failure — callers need the
        // original error so they can surface "source unreachable" rather
        // than getting an empty endpoint list and assuming the API is empty.
        wm.stubFor(get(urlEqualTo("/openapi.json"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        System s = system("orphan", wm.baseUrl() + "/openapi.json");

        assertThatThrownBy(() -> cache.loadResult(s))
            .isInstanceOf(RuntimeException.class);
    }
}
