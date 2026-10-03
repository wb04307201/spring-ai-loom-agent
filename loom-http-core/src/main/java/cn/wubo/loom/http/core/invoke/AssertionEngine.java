package cn.wubo.loom.http.core.invoke;

import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import org.springframework.http.HttpHeaders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Evaluates a list of assertions against the result of a single HTTP call.
 *
 * Each assertion is evaluated independently — a failure in one does not stop evaluation of
 * the remaining assertions. Failures are collected into the returned {@link AssertionResult}.
 *
 * Supported assertion types (see spec §6.1 step 12):
 * <ul>
 *   <li>{@code statusEquals} — exact HTTP status match</li>
 *   <li>{@code statusIn} — status is one of a list</li>
 *   <li>{@code bodyContains} — response body contains substring</li>
 *   <li>{@code bodyJsonPathEquals} — JsonPath value equals expected</li>
 *   <li>{@code bodyJsonPathExists} — JsonPath resolves</li>
 *   <li>{@code responseTimeMsLt} — latency strictly less than value</li>
 *   <li>{@code headerEquals} — response header value matches (case-insensitive header name)</li>
 * </ul>
 */
public class AssertionEngine {

    /**
     * Evaluate all assertions independently.
     *
     * @param assertions list of assertion maps (each must contain a {@code type} entry);
     *                   may be {@code null} or empty
     * @param statusCode HTTP status from the response
     * @param body       raw response body (may be {@code null})
     * @param headers    response headers (may be {@code null})
     * @param latencyMs  measured round-trip latency in milliseconds
     * @return aggregated result; {@code passed} is {@code true} only when {@code failures} is empty
     */
    public AssertionResult evaluate(List<Map<String, Object>> assertions,
                                    int statusCode,
                                    String body,
                                    HttpHeaders headers,
                                    long latencyMs) {
        if (assertions == null || assertions.isEmpty()) {
            return new AssertionResult(true, Collections.emptyList());
        }
        List<AssertionResult.Failure> failures = new ArrayList<>();
        for (Map<String, Object> a : assertions) {
            if (a == null) continue;
            Object typeObj = a.get("type");
            String type = typeObj == null ? null : typeObj.toString();
            try {
                evaluateOne(type, a, statusCode, body, headers, latencyMs, failures);
            } catch (RuntimeException ex) {
                failures.add(new AssertionResult.Failure(
                        type,
                        null,
                        null,
                        "Assertion threw: " + ex.getClass().getSimpleName() + ": " + ex.getMessage()));
            }
        }
        return new AssertionResult(failures.isEmpty(), failures);
    }

    private void evaluateOne(String type,
                              Map<String, Object> a,
                              int statusCode,
                              String body,
                              HttpHeaders headers,
                              long latencyMs,
                              List<AssertionResult.Failure> failures) {
        if (type == null || type.isBlank()) {
            failures.add(new AssertionResult.Failure(
                    "<missing>", null, null, "Assertion has no 'type' field"));
            return;
        }
        switch (type) {
            case "statusEquals" -> evalStatusEquals(a, statusCode, failures);
            case "statusIn" -> evalStatusIn(a, statusCode, failures);
            case "bodyContains" -> evalBodyContains(a, body, failures);
            case "bodyJsonPathEquals" -> evalBodyJsonPathEquals(a, body, failures);
            case "bodyJsonPathExists" -> evalBodyJsonPathExists(a, body, failures);
            case "responseTimeMsLt" -> evalResponseTimeMsLt(a, latencyMs, failures);
            case "headerEquals" -> evalHeaderEquals(a, headers, failures);
            default -> failures.add(new AssertionResult.Failure(
                    type, null, null, "Unknown assertion type: " + type));
        }
    }

    private void evalStatusEquals(Map<String, Object> a, int statusCode, List<AssertionResult.Failure> failures) {
        int expected = toInt(a.get("value"));
        if (statusCode != expected) {
            failures.add(new AssertionResult.Failure(
                    "statusEquals",
                    String.valueOf(expected),
                    String.valueOf(statusCode),
                    "Expected HTTP status " + expected + " but was " + statusCode));
        }
    }

    private void evalStatusIn(Map<String, Object> a, int statusCode, List<AssertionResult.Failure> failures) {
        Object v = a.get("value");
        List<Integer> allowed = toIntList(v);
        if (!allowed.contains(statusCode)) {
            failures.add(new AssertionResult.Failure(
                    "statusIn",
                    String.valueOf(v),
                    String.valueOf(statusCode),
                    "Expected HTTP status in " + v + " but was " + statusCode));
        }
    }

    private void evalBodyContains(Map<String, Object> a, String body, List<AssertionResult.Failure> failures) {
        String expected = stringOf(a.get("value"));
        if (body == null || !body.contains(expected)) {
            failures.add(new AssertionResult.Failure(
                    "bodyContains",
                    expected,
                    body,
                    "Expected body to contain '" + expected + "'"));
        }
    }

    private void evalBodyJsonPathEquals(Map<String, Object> a, String body, List<AssertionResult.Failure> failures) {
        String path = stringOf(a.get("path"));
        Object expected = a.get("value");
        if (body == null) {
            failures.add(new AssertionResult.Failure(
                    "bodyJsonPathEquals",
                    String.valueOf(expected),
                    null,
                    "Body is null; cannot evaluate JsonPath '" + path + "'"));
            return;
        }
        try {
            Object actual = JsonPath.read(body, path);
            if (!jsonEquals(expected, actual)) {
                failures.add(new AssertionResult.Failure(
                        "bodyJsonPathEquals",
                        String.valueOf(expected),
                        String.valueOf(actual),
                        "Expected JsonPath '" + path + "' to equal " + expected));
            }
        } catch (PathNotFoundException ex) {
            failures.add(new AssertionResult.Failure(
                    "bodyJsonPathEquals",
                    String.valueOf(expected),
                    null,
                    "JsonPath '" + path + "' did not resolve"));
        } catch (InvalidPathException ex) {
            failures.add(new AssertionResult.Failure(
                    "bodyJsonPathEquals",
                    String.valueOf(expected),
                    null,
                    "Invalid JsonPath '" + path + "': " + ex.getMessage()));
        }
    }

    private void evalBodyJsonPathExists(Map<String, Object> a, String body, List<AssertionResult.Failure> failures) {
        String path = stringOf(a.get("path"));
        if (body == null) {
            failures.add(new AssertionResult.Failure(
                    "bodyJsonPathExists",
                    path,
                    null,
                    "Body is null; cannot evaluate JsonPath '" + path + "'"));
            return;
        }
        try {
            JsonPath.read(body, path);
        } catch (PathNotFoundException ex) {
            failures.add(new AssertionResult.Failure(
                    "bodyJsonPathExists",
                    path,
                    null,
                    "JsonPath '" + path + "' not found in body"));
        } catch (InvalidPathException ex) {
            failures.add(new AssertionResult.Failure(
                    "bodyJsonPathExists",
                    path,
                    null,
                    "Invalid JsonPath '" + path + "': " + ex.getMessage()));
        }
    }

    private void evalResponseTimeMsLt(Map<String, Object> a, long latencyMs, List<AssertionResult.Failure> failures) {
        int expected = toInt(a.get("value"));
        if (!(latencyMs < expected)) {
            failures.add(new AssertionResult.Failure(
                    "responseTimeMsLt",
                    "<" + expected,
                    String.valueOf(latencyMs),
                    "Expected latency < " + expected + "ms but was " + latencyMs + "ms"));
        }
    }

    private void evalHeaderEquals(Map<String, Object> a, HttpHeaders headers, List<AssertionResult.Failure> failures) {
        String name = stringOf(a.get("header"));
        String expected = stringOf(a.get("value"));
        if (headers == null) {
            failures.add(new AssertionResult.Failure(
                    "headerEquals",
                    expected,
                    null,
                    "Headers are null; cannot check '" + name + "'"));
            return;
        }
        // HttpHeaders.getFirst is case-insensitive on header name
        String actual = headers.getFirst(name);
        if (!Objects.equals(actual, expected)) {
            failures.add(new AssertionResult.Failure(
                    "headerEquals",
                    expected,
                    actual,
                    "Expected header '" + name + "' to equal '" + expected + "'"));
        }
    }

    // ----- helpers -----

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static List<Integer> toIntList(Object o) {
        if (o == null) return Collections.emptyList();
        if (o instanceof List<?> list) {
            List<Integer> out = new ArrayList<>(list.size());
            for (Object e : list) {
                out.add(toInt(e));
            }
            return out;
        }
        return Collections.emptyList();
    }

    private static String stringOf(Object o) {
        return o == null ? null : o.toString();
    }

    /**
     * Equality that treats Jackson-decoded numbers (Integer, Long, Double, BigDecimal) as equal
     * when their numeric values match — so {@code expected=30} matches {@code actual=30} even if
     * one side is a Long and the other an Integer.
     */
    private static boolean jsonEquals(Object expected, Object actual) {
        if (expected == null && actual == null) return true;
        if (expected == null || actual == null) return false;
        if (expected instanceof Number en && actual instanceof Number an) {
            // compare via double for fractional values, fallback to string for BigDecimal exactness
            try {
                return Double.compare(en.doubleValue(), an.doubleValue()) == 0;
            } catch (NumberFormatException ex) {
                return en.toString().equals(an.toString());
            }
        }
        return expected.equals(actual);
    }
}