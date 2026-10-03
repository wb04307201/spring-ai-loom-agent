package cn.wubo.loom.http.core.invoke;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssertionEngineTest {

    private final AssertionEngine engine = new AssertionEngine();

    private static Map<String, Object> assertion(String type, Map<String, Object> fields) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", type);
        a.putAll(fields);
        return a;
    }

    private static HttpHeaders headers(String... pairs) {
        HttpHeaders h = new HttpHeaders();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            h.add(pairs[i], pairs[i + 1]);
        }
        return h;
    }

    // ---------- statusEquals ----------

    @Test
    void statusEqualsPasses() {
        AssertionResult r = engine.evaluate(
                List.of(assertion("statusEquals", Map.of("value", 200))),
                200, "{}", headers(), 10);
        assertThat(r.passed()).isTrue();
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void statusEqualsFailsNoBlock() {
        AssertionResult r = engine.evaluate(
                List.of(assertion("statusEquals", Map.of("value", 200))),
                500, "err", headers(), 10);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).hasSize(1);
        assertThat(r.failures().get(0).assertion()).isEqualTo("statusEquals");
        assertThat(r.failures().get(0).expected()).isEqualTo("200");
        assertThat(r.failures().get(0).actual()).isEqualTo("500");
    }

    // ---------- statusIn ----------

    @Test
    void statusInPasses() {
        AssertionResult r = engine.evaluate(
                List.of(assertion("statusIn", Map.of("value", List.of(200, 201, 204)))),
                201, "{}", headers(), 10);
        assertThat(r.passed()).isTrue();
    }

    @Test
    void statusInFails() {
        AssertionResult r = engine.evaluate(
                List.of(assertion("statusIn", Map.of("value", List.of(200, 201)))),
                404, "{}", headers(), 10);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).hasSize(1);
        assertThat(r.failures().get(0).assertion()).isEqualTo("statusIn");
        assertThat(r.failures().get(0).actual()).isEqualTo("404");
    }

    // ---------- bodyContains ----------

    @Test
    void bodyContainsPasses() {
        AssertionResult r = engine.evaluate(
                List.of(assertion("bodyContains", Map.of("value", "hello"))),
                200, "say hello world", headers(), 10);
        assertThat(r.passed()).isTrue();
    }

    @Test
    void bodyContainsFails() {
        AssertionResult r = engine.evaluate(
                List.of(assertion("bodyContains", Map.of("value", "missing-text"))),
                200, "hello world", headers(), 10);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures().get(0).assertion()).isEqualTo("bodyContains");
        assertThat(r.failures().get(0).expected()).isEqualTo("missing-text");
    }

    // ---------- bodyJsonPathEquals ----------

    @Test
    void bodyJsonPathEqualsPasses() {
        String body = "{\"user\":{\"name\":\"Alice\",\"age\":30}}";
        AssertionResult r = engine.evaluate(
                List.of(assertion("bodyJsonPathEquals",
                        Map.of("path", "$.user.name", "value", "Alice"))),
                200, body, headers(), 10);
        assertThat(r.passed()).isTrue();
    }

    @Test
    void bodyJsonPathEqualsFailsOnMismatch() {
        String body = "{\"user\":{\"name\":\"Alice\"}}";
        AssertionResult r = engine.evaluate(
                List.of(assertion("bodyJsonPathEquals",
                        Map.of("path", "$.user.name", "value", "Bob"))),
                200, body, headers(), 10);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).hasSize(1);
        assertThat(r.failures().get(0).assertion()).isEqualTo("bodyJsonPathEquals");
        assertThat(r.failures().get(0).expected()).contains("Bob");
        assertThat(r.failures().get(0).actual()).contains("Alice");
    }

    @Test
    void bodyJsonPathEqualsFailsWhenPathMissing() {
        String body = "{\"user\":{\"name\":\"Alice\"}}";
        AssertionResult r = engine.evaluate(
                List.of(assertion("bodyJsonPathEquals",
                        Map.of("path", "$.user.email", "value", "alice@x.com"))),
                200, body, headers(), 10);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).hasSize(1);
        assertThat(r.failures().get(0).assertion()).isEqualTo("bodyJsonPathEquals");
    }

    // ---------- bodyJsonPathExists ----------

    @Test
    void bodyJsonPathExistsPasses() {
        String body = "{\"user\":{\"name\":\"Alice\"}}";
        AssertionResult r = engine.evaluate(
                List.of(assertion("bodyJsonPathExists", Map.of("path", "$.user.name"))),
                200, body, headers(), 10);
        assertThat(r.passed()).isTrue();
    }

    @Test
    void bodyJsonPathExistsFailsWhenPathMissing() {
        String body = "{\"user\":{\"name\":\"Alice\"}}";
        AssertionResult r = engine.evaluate(
                List.of(assertion("bodyJsonPathExists", Map.of("path", "$.user.email"))),
                200, body, headers(), 10);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).hasSize(1);
        assertThat(r.failures().get(0).assertion()).isEqualTo("bodyJsonPathExists");
    }

    // ---------- responseTimeMsLt ----------

    @Test
    void responseTimeMsLtPasses() {
        AssertionResult r = engine.evaluate(
                List.of(assertion("responseTimeMsLt", Map.of("value", 500))),
                200, "{}", headers(), 100);
        assertThat(r.passed()).isTrue();
    }

    @Test
    void responseTimeMsLtFails() {
        AssertionResult r = engine.evaluate(
                List.of(assertion("responseTimeMsLt", Map.of("value", 500))),
                200, "{}", headers(), 700);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).hasSize(1);
        assertThat(r.failures().get(0).assertion()).isEqualTo("responseTimeMsLt");
        assertThat(r.failures().get(0).expected()).isEqualTo("<500");
        assertThat(r.failures().get(0).actual()).isEqualTo("700");
    }

    // ---------- headerEquals ----------

    @Test
    void headerEqualsPasses() {
        AssertionResult r = engine.evaluate(
                List.of(assertion("headerEquals",
                        Map.of("header", "Content-Type", "value", "application/json"))),
                200, "{}", headers("Content-Type", "application/json"), 10);
        assertThat(r.passed()).isTrue();
    }

    @Test
    void headerEqualsCaseInsensitive() {
        AssertionResult r = engine.evaluate(
                List.of(assertion("headerEquals",
                        Map.of("header", "content-type", "value", "application/json"))),
                200, "{}", headers("Content-Type", "application/json"), 10);
        assertThat(r.passed()).isTrue();
    }

    @Test
    void headerEqualsFailsOnMismatch() {
        AssertionResult r = engine.evaluate(
                List.of(assertion("headerEquals",
                        Map.of("header", "Content-Type", "value", "text/plain"))),
                200, "{}", headers("Content-Type", "application/json"), 10);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).hasSize(1);
        assertThat(r.failures().get(0).assertion()).isEqualTo("headerEquals");
        assertThat(r.failures().get(0).expected()).isEqualTo("text/plain");
        assertThat(r.failures().get(0).actual()).isEqualTo("application/json");
    }

    @Test
    void headerEqualsFailsWhenHeaderMissing() {
        AssertionResult r = engine.evaluate(
                List.of(assertion("headerEquals",
                        Map.of("header", "X-Missing", "value", "anything"))),
                200, "{}", headers("Content-Type", "application/json"), 10);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).hasSize(1);
        assertThat(r.failures().get(0).actual()).isNull();
    }

    // ---------- Combination + edge cases ----------

    @Test
    void emptyListPasses() {
        AssertionResult r = engine.evaluate(List.of(), 200, "{}", headers(), 10);
        assertThat(r.passed()).isTrue();
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void nullListPasses() {
        AssertionResult r = engine.evaluate(null, 200, "{}", headers(), 10);
        assertThat(r.passed()).isTrue();
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void mixedPassAndFail() {
        List<Map<String, Object>> assertions = List.of(
                assertion("statusEquals", Map.of("value", 200)),
                assertion("bodyContains", Map.of("value", "hello")),
                assertion("responseTimeMsLt", Map.of("value", 500)),
                assertion("statusEquals", Map.of("value", 999))
        );
        AssertionResult r = engine.evaluate(assertions, 200, "say hello", headers(), 100);
        // 3 pass + 1 fail
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).hasSize(1);
        assertThat(r.failures().get(0).assertion()).isEqualTo("statusEquals");
        assertThat(r.failures().get(0).expected()).isEqualTo("999");
    }

    @Test
    void multipleFailuresAllCollected() {
        List<Map<String, Object>> assertions = List.of(
                assertion("statusEquals", Map.of("value", 200)),
                assertion("bodyContains", Map.of("value", "nope")),
                assertion("responseTimeMsLt", Map.of("value", 50))
        );
        AssertionResult r = engine.evaluate(assertions, 500, "hello", headers(), 100);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).hasSize(3);
    }

    @Test
    void allPassReturnsPassedTrueWithNoFailures() {
        List<Map<String, Object>> assertions = List.of(
                assertion("statusEquals", Map.of("value", 200)),
                assertion("bodyContains", Map.of("value", "ok")),
                assertion("responseTimeMsLt", Map.of("value", 500))
        );
        AssertionResult r = engine.evaluate(assertions, 200, "ok-response", headers(), 50);
        assertThat(r.passed()).isTrue();
        assertThat(r.failures()).isEmpty();
    }

    // ---------- Unknown / malformed assertions ----------

    @Test
    void unknownTypeProducesFailure() {
        AssertionResult r = engine.evaluate(
                List.of(assertion("bogus", Map.of("value", "x"))),
                200, "{}", headers(), 10);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).hasSize(1);
        assertThat(r.failures().get(0).assertion()).isEqualTo("bogus");
    }

    @Test
    void missingTypeProducesFailure() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("value", "x");
        AssertionResult r = engine.evaluate(List.of(a), 200, "{}", headers(), 10);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).hasSize(1);
    }
}