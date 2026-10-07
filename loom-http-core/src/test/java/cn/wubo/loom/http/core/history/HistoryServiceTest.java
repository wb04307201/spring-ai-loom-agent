package cn.wubo.loom.http.core.history;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryServiceTest {

    @TempDir
    Path tmp;
    HistoryService svc;
    HttpConfig global;

    @BeforeEach
    void setUp() throws Exception {
        HttpStorage sc = new HttpStorage(tmp);
        global = new HttpConfig();
        // small N for rolling-trim test
        global.setHistoryMaxEntriesPerSystem(3);
        svc = new HistoryService(sc, global);
    }

    private static HistoryEntry entry(String system, String profile, int statusCode,
                                      boolean passed, String error, Instant ts) {
        HistoryEntry e = new HistoryEntry();
        e.setTimestamp(ts);
        e.setSystem(system);
        e.setProfile(profile);
        e.setMethod("GET");
        e.setPath("/users/{id}");
        e.getRequest().getParams().put("id", "1");
        e.getResponse().setStatusCode(statusCode);
        e.getResponse().setLatencyMs(50);
        e.getResponse().setBodySize(10);
        e.getAssertion().setPassed(passed);
        if (!passed) {
            e.getAssertion().getFailures().add("expected 200 but got " + statusCode);
        }
        e.setError(error);
        return e;
    }

    private static HistoryEntry entry(String system, String profile, int statusCode,
                                      boolean passed, Instant ts) {
        return entry(system, profile, statusCode, passed, null, ts);
    }

    @Test
    void appendAndQueryRoundTrip() {
        Instant t = Instant.parse("2026-08-22T10:00:00Z");
        svc.append("user-service", entry("user-service", "staging", 200, true, t));

        List<HistoryEntry> result = svc.query("user-service", 50, null, null);
        assertThat(result).hasSize(1);
        HistoryEntry e = result.get(0);
        assertThat(e.getSystem()).isEqualTo("user-service");
        assertThat(e.getProfile()).isEqualTo("staging");
        assertThat(e.getTool()).isEqualTo("invokeEndpoint");
        assertThat(e.getMethod()).isEqualTo("GET");
        assertThat(e.getPath()).isEqualTo("/users/{id}");
        assertThat(e.getResponse().getStatusCode()).isEqualTo(200);
        assertThat(e.getResponse().getLatencyMs()).isEqualTo(50L);
        assertThat(e.getAssertion().isPassed()).isTrue();
        assertThat(e.getAssertion().getFailures()).isEmpty();
        assertThat(e.getRetryCount()).isEqualTo(0);
        assertThat(e.getError()).isNull();
        assertThat(e.getTimestamp()).isEqualTo(t);
        assertThat(e.getRequest().getParams()).containsEntry("id", "1");
    }

    @Test
    void rollingTrimKeepsOnlyNMostRecent() {
        // max=3, write 5
        Instant base = Instant.parse("2026-08-22T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            svc.append("user-service",
                    entry("user-service", "staging", 200, true, base.plusSeconds(i)));
        }

        List<HistoryEntry> result = svc.query("user-service", 50, null, null);
        assertThat(result).hasSize(3);
        // Most-recent first; should be the last 3 written
        assertThat(result.get(0).getTimestamp()).isEqualTo(base.plusSeconds(4));
        assertThat(result.get(1).getTimestamp()).isEqualTo(base.plusSeconds(3));
        assertThat(result.get(2).getTimestamp()).isEqualTo(base.plusSeconds(2));
    }

    @Test
    void rollingTrimDropsOldestFromBeginning() {
        Instant base = Instant.parse("2026-08-22T10:00:00Z");
        // Write 3 (fits within max)
        for (int i = 0; i < 3; i++) {
            svc.append("svc",
                    entry("svc", "p", 200, true, base.plusSeconds(i)));
        }
        // 4th should trim the first
        svc.append("svc", entry("svc", "p", 201, true, base.plusSeconds(99)));

        List<HistoryEntry> result = svc.query("svc", 50, null, null);
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getTimestamp()).isEqualTo(base.plusSeconds(99));
        assertThat(result.get(1).getTimestamp()).isEqualTo(base.plusSeconds(2));
        assertThat(result.get(2).getTimestamp()).isEqualTo(base.plusSeconds(1));
        // entry at +0s should be gone
        assertThat(result).noneMatch(e -> e.getTimestamp().equals(base));
    }

    @Test
    void queryFiltersByStatusSucceeded() {
        Instant base = Instant.parse("2026-08-22T10:00:00Z");
        svc.append("svc", entry("svc", "p", 200, true, base));
        svc.append("svc", entry("svc", "p", 500, false, base.plusSeconds(1)));
        svc.append("svc", entry("svc", "p", 200, true, base.plusSeconds(2)));

        List<HistoryEntry> result = svc.query("svc", 50, "succeeded", null);
        assertThat(result).hasSize(2);
        assertThat(result).allMatch(e -> e.getResponse().getStatusCode() == 200);
    }

    @Test
    void queryFiltersByStatusFailedForAssertionFailure() {
        Instant base = Instant.parse("2026-08-22T10:00:00Z");
        svc.append("svc", entry("svc", "p", 200, true, base));
        svc.append("svc", entry("svc", "p", 200, false, base.plusSeconds(1))); // assertion fail

        List<HistoryEntry> result = svc.query("svc", 50, "failed", null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAssertion().isPassed()).isFalse();
    }

    @Test
    void queryFiltersByStatusFailedForError() {
        Instant base = Instant.parse("2026-08-22T10:00:00Z");
        svc.append("svc", entry("svc", "p", 200, true, base));
        svc.append("svc", entry("svc", "p", 0, true, "NetworkError", base.plusSeconds(1)));

        List<HistoryEntry> result = svc.query("svc", 50, "failed", null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getError()).isEqualTo("NetworkError");
    }

    @Test
    void queryNullStatusFilterReturnsAll() {
        Instant base = Instant.parse("2026-08-22T10:00:00Z");
        svc.append("svc", entry("svc", "p", 200, true, base));
        svc.append("svc", entry("svc", "p", 500, false, base.plusSeconds(1)));

        List<HistoryEntry> result = svc.query("svc", 50, null, null);
        assertThat(result).hasSize(2);
    }

    @Test
    void queryFiltersBySinceTimestamp() {
        Instant cutoff = Instant.parse("2026-08-22T10:00:00Z");
        svc.append("svc", entry("svc", "p", 200, true, cutoff.minusSeconds(60)));
        svc.append("svc", entry("svc", "p", 200, true, cutoff));
        svc.append("svc", entry("svc", "p", 200, true, cutoff.plusSeconds(60)));

        // since is INCLUSIVE per "timestamp > since" — actually spec says strict "> since"
        // Use since slightly before cutoff to confirm filter is by after-since
        List<HistoryEntry> result = svc.query("svc", 50, null, cutoff.minusSeconds(1));
        // Should include cutoff and cutoff+60 but NOT cutoff-60
        assertThat(result).hasSize(2);
        assertThat(result).noneMatch(e -> e.getTimestamp().equals(cutoff.minusSeconds(60)));
    }

    @Test
    void queryForAdHocReadsAdHocJsonl() {
        Instant t = Instant.parse("2026-08-22T10:00:00Z");
        svc.append("_ad_hoc", entry("_ad_hoc", null, 200, true, t));

        List<HistoryEntry> result = svc.query("_ad_hoc", 50, null, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSystem()).isEqualTo("_ad_hoc");
    }

    @Test
    void queryForNullSystemTreatedAsAdHoc() {
        Instant t = Instant.parse("2026-08-22T10:00:00Z");
        svc.append("_ad_hoc", entry("_ad_hoc", null, 200, true, t));

        // null → _ad_hoc per spec
        List<HistoryEntry> result = svc.query(null, 50, null, null);
        assertThat(result).hasSize(1);
    }

    @Test
    void queryReturnsDescendingByTimestamp() {
        Instant base = Instant.parse("2026-08-22T10:00:00Z");
        svc.append("svc", entry("svc", "p", 200, true, base.plusSeconds(2)));
        svc.append("svc", entry("svc", "p", 200, true, base.plusSeconds(0)));
        svc.append("svc", entry("svc", "p", 200, true, base.plusSeconds(1)));

        List<HistoryEntry> result = svc.query("svc", 50, null, null);
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getTimestamp()).isEqualTo(base.plusSeconds(2));
        assertThat(result.get(1).getTimestamp()).isEqualTo(base.plusSeconds(1));
        assertThat(result.get(2).getTimestamp()).isEqualTo(base.plusSeconds(0));
    }

    @Test
    void queryAppliesLimitAfterFilters() {
        Instant base = Instant.parse("2026-08-22T10:00:00Z");
        for (int i = 0; i < 3; i++) {
            svc.append("svc", entry("svc", "p", 200, true, base.plusSeconds(i)));
        }

        List<HistoryEntry> result = svc.query("svc", 2, null, null);
        assertThat(result).hasSize(2);
        // Most-recent first
        assertThat(result.get(0).getTimestamp()).isEqualTo(base.plusSeconds(2));
        assertThat(result.get(1).getTimestamp()).isEqualTo(base.plusSeconds(1));
    }

    @Test
    void appendCreatesDirectoryIfMissing() {
        // tmp/history does not exist initially
        Instant t = Instant.parse("2026-08-22T10:00:00Z");
        svc.append("fresh", entry("fresh", "p", 200, true, t));
        assertThat(tmp.resolve("history")).exists();
        assertThat(tmp.resolve("history").resolve("fresh.jsonl")).exists();
    }

    @Test
    void appendCreatesOneJsonPerLineJsonlFormat() throws Exception {
        Instant base = Instant.parse("2026-08-22T10:00:00Z");
        svc.append("svc", entry("svc", "p", 200, true, base));
        svc.append("svc", entry("svc", "p", 201, true, base.plusSeconds(1)));

        String content = java.nio.file.Files.readString(tmp.resolve("history").resolve("svc.jsonl"));
        // Two lines (system line.separator trailing newline)
        String[] lines = content.split("\\R");
        assertThat(lines.length).isGreaterThanOrEqualTo(2);
        // Each line should be valid JSON
        for (String line : lines) {
            if (line.isBlank()) continue;
            assertThat(line).startsWith("{").endsWith("}");
        }
    }

    @Test
    void systemsAreIsolated() {
        Instant t = Instant.parse("2026-08-22T10:00:00Z");
        svc.append("alpha", entry("alpha", "p", 200, true, t));
        svc.append("beta", entry("beta", "p", 201, true, t.plusSeconds(1)));

        assertThat(svc.query("alpha", 50, null, null)).hasSize(1);
        assertThat(svc.query("beta", 50, null, null)).hasSize(1);
        assertThat(svc.query("alpha", 50, null, null).get(0).getResponse().getStatusCode()).isEqualTo(200);
        assertThat(svc.query("beta", 50, null, null).get(0).getResponse().getStatusCode()).isEqualTo(201);
    }

    @Test
    void queryEmptyFileReturnsEmptyList() {
        assertThat(svc.query("nonexistent", 50, null, null)).isEmpty();
    }

    @Test
    void requestBodyAndHeadersPreserved() {
        Instant t = Instant.parse("2026-08-22T10:00:00Z");
        HistoryEntry e = entry("svc", "p", 200, true, t);
        e.getRequest().setBody(Map.of("name", "Alice"));
        e.getRequest().getHeaders().put("X-Tenant", "staging");
        svc.append("svc", e);

        HistoryEntry loaded = svc.query("svc", 50, null, null).get(0);
        assertThat(loaded.getRequest().getBody()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) loaded.getRequest().getBody()).get("name")).isEqualTo("Alice");
        assertThat(loaded.getRequest().getHeaders()).containsEntry("X-Tenant", "staging");
    }

    @Test
    void responseFileFieldPreserved() {
        Instant t = Instant.parse("2026-08-22T10:00:00Z");
        HistoryEntry e = entry("svc", "p", 200, true, t);
        e.getResponse().setResponseFile("/path/to/file.json");
        svc.append("svc", e);

        HistoryEntry loaded = svc.query("svc", 50, null, null).get(0);
        assertThat(loaded.getResponse().getResponseFile()).isEqualTo("/path/to/file.json");
    }

    @Test
    void contractWarningsPreserved() {
        Instant t = Instant.parse("2026-08-22T10:00:00Z");
        HistoryEntry e = entry("svc", "p", 200, true, t);
        e.getContract().getWarnings().add("unexpectedField:foo");
        e.getContract().getWarnings().add("typeMismatch:bar");
        svc.append("svc", e);

        HistoryEntry loaded = svc.query("svc", 50, null, null).get(0);
        assertThat(loaded.getContract().getWarnings())
                .containsExactly("unexpectedField:foo", "typeMismatch:bar");
    }
}
