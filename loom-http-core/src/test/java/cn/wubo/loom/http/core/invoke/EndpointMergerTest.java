package cn.wubo.loom.http.core.invoke;

import cn.wubo.loom.http.core.endpoint.Endpoint;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

class EndpointMergerTest {
    private final EndpointMerger merger = new EndpointMerger();

    private static Endpoint ep(String method, String path, Instant lastModified) {
        Endpoint e = new Endpoint();
        e.setMethod(method);
        e.setPath(path);
        e.setLastModified(lastModified);
        return e;
    }

    @Test
    void onlyOpenApiAllSourceOpenApi() {
        Endpoint e1 = ep("GET", "/a", Instant.parse("2026-01-01T00:00:00Z"));
        Endpoint e2 = ep("POST", "/b", Instant.parse("2026-01-02T00:00:00Z"));

        List<MergedEndpoint> merged = merger.merge(List.of(e1, e2), List.of());

        assertThat(merged).hasSize(2);
        assertThat(merged).allSatisfy(m -> assertThat(m.source()).isEqualTo("openapi"));
        assertThat(merged).extracting(m -> m.endpoint().getPath()).containsExactly("/a", "/b");
    }

    @Test
    void onlyManualAllSourceManual() {
        Endpoint e1 = ep("GET", "/a", Instant.parse("2026-01-01T00:00:00Z"));
        Endpoint e2 = ep("POST", "/b", Instant.parse("2026-01-02T00:00:00Z"));

        List<MergedEndpoint> merged = merger.merge(List.of(), List.of(e1, e2));

        assertThat(merged).hasSize(2);
        assertThat(merged).allSatisfy(m -> assertThat(m.source()).isEqualTo("manual"));
        assertThat(merged).extracting(m -> m.endpoint().getPath()).containsExactly("/a", "/b");
    }

    @Test
    void openApiNewerWinsOnConflict() {
        Endpoint openApi = ep("GET", "/users/{id}", Instant.parse("2026-08-22T12:00:00Z"));
        Endpoint manual = ep("GET", "/users/{id}", Instant.parse("2026-08-22T10:00:00Z"));

        List<MergedEndpoint> merged = merger.merge(List.of(openApi), List.of(manual));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).source()).isEqualTo("openapi");
        assertThat(merged.get(0).endpoint().getLastModified()).isEqualTo(Instant.parse("2026-08-22T12:00:00Z"));
    }

    @Test
    void manualNewerWinsOnConflict() {
        Endpoint openApi = ep("GET", "/users/{id}", Instant.parse("2026-08-22T10:00:00Z"));
        Endpoint manual = ep("GET", "/users/{id}", Instant.parse("2026-08-22T12:00:00Z"));

        List<MergedEndpoint> merged = merger.merge(List.of(openApi), List.of(manual));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).source()).isEqualTo("manual");
        assertThat(merged.get(0).endpoint().getLastModified()).isEqualTo(Instant.parse("2026-08-22T12:00:00Z"));
    }

    @Test
    void manualWinsOnTie() {
        Instant same = Instant.parse("2026-08-22T10:00:00Z");
        Endpoint openApi = ep("POST", "/internal/notify", same);
        Endpoint manual = ep("POST", "/internal/notify", same);

        List<MergedEndpoint> merged = merger.merge(List.of(openApi), List.of(manual));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).source()).isEqualTo("manual");
    }

    @Test
    void disjointEndpointsBothKept() {
        Endpoint openApi = ep("GET", "/a", Instant.parse("2026-01-01T00:00:00Z"));
        Endpoint manual = ep("POST", "/b", Instant.parse("2026-01-02T00:00:00Z"));

        List<MergedEndpoint> merged = merger.merge(List.of(openApi), List.of(manual));

        assertThat(merged).hasSize(2);
        assertThat(merged).extracting(m -> m.endpoint().getPath()).containsExactlyInAnyOrder("/a", "/b");
        assertThat(merged).extracting(MergedEndpoint::source).containsExactlyInAnyOrder("openapi", "manual");
    }

    @Test
    void resultIsSortedByMethodThenPath() {
        Endpoint a = ep("POST", "/z", Instant.parse("2026-01-01T00:00:00Z"));
        Endpoint b = ep("GET", "/b", Instant.parse("2026-01-02T00:00:00Z"));
        Endpoint c = ep("GET", "/a", Instant.parse("2026-01-03T00:00:00Z"));
        Endpoint d = ep("DELETE", "/m", Instant.parse("2026-01-04T00:00:00Z"));

        List<MergedEndpoint> merged = merger.merge(List.of(a, b, c, d), List.of());

        assertThat(merged).extracting(m -> m.endpoint().getMethod()).containsExactly("DELETE", "GET", "GET", "POST");
        assertThat(merged).extracting(m -> m.endpoint().getPath()).containsExactly("/m", "/a", "/b", "/z");
    }

    @Test
    void emptyInputsReturnsEmpty() {
        assertThat(merger.merge(List.of(), List.of())).isEmpty();
    }

    @Test
    void mergedEndpointExposesAllFields() {
        Endpoint e = ep("GET", "/x", Instant.parse("2026-08-22T10:00:00Z"));
        MergedEndpoint merged = merger.merge(List.of(e), List.of()).get(0);

        assertThat(merged.endpoint()).isSameAs(e);
        assertThat(merged.source()).isEqualTo("openapi");
        assertThat(merged.lastModified()).isEqualTo(Instant.parse("2026-08-22T10:00:00Z"));
    }

    @Test
    void findCaseInsensitiveMethod() {
        Endpoint e = ep("GET", "/users", Instant.parse("2026-01-01T00:00:00Z"));
        List<MergedEndpoint> merged = merger.merge(List.of(e), List.of());

        Optional<MergedEndpoint> found = merger.find(merged, "get", "/users");
        assertThat(found).isPresent();
        assertThat(found.get().endpoint().getPath()).isEqualTo("/users");
    }

    @Test
    void findUppercaseMethod() {
        Endpoint e = ep("GET", "/users", Instant.parse("2026-01-01T00:00:00Z"));
        List<MergedEndpoint> merged = merger.merge(List.of(e), List.of());

        Optional<MergedEndpoint> found = merger.find(merged, "GET", "/users");
        assertThat(found).isPresent();
    }

    @Test
    void findByPathExactMatch() {
        Endpoint e1 = ep("GET", "/users", Instant.parse("2026-01-01T00:00:00Z"));
        Endpoint e2 = ep("GET", "/users/{id}", Instant.parse("2026-01-02T00:00:00Z"));
        List<MergedEndpoint> merged = merger.merge(List.of(e1, e2), List.of());

        Optional<MergedEndpoint> found = merger.find(merged, "GET", "/users/{id}");
        assertThat(found).isPresent();
        assertThat(found.get().endpoint().getPath()).isEqualTo("/users/{id}");
    }

    @Test
    void findReturnsEmptyWhenNotFound() {
        Endpoint e = ep("GET", "/users", Instant.parse("2026-01-01T00:00:00Z"));
        List<MergedEndpoint> merged = merger.merge(List.of(e), List.of());

        assertThat(merger.find(merged, "POST", "/users")).isEmpty();
        assertThat(merger.find(merged, "GET", "/missing")).isEmpty();
        assertThat(merger.find(merged, "DELETE", "/anything")).isEmpty();
    }

    @Test
    void findReturnsEmptyOnEmptyList() {
        assertThat(merger.find(List.of(), "GET", "/users")).isEmpty();
    }
}
