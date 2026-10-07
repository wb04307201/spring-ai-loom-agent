package cn.wubo.loom.http.core.invoke;

import java.util.List;

/**
 * Result of evaluating a list of assertions against a single HTTP response.
 *
 * @param passed   {@code true} when every assertion passed; {@code false} when at least one failed
 * @param failures collected failures (empty when all passed)
 */
public record AssertionResult(boolean passed, List<Failure> failures) {

    /**
     * Describes a single failed assertion. {@code expected} and {@code actual} are best-effort
     * string representations used in error messages; they may be {@code null} when not applicable.
     *
     * @param assertion the assertion type (e.g. {@code statusEquals})
     * @param expected  expected value as a string
     * @param actual    actual value as a string
     * @param message   human-readable description of the failure
     */
    public record Failure(String assertion, String expected, String actual, String message) {
    }
}