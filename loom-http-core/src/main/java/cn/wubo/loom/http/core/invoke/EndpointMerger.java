package cn.wubo.loom.http.core.invoke;

import cn.wubo.loom.http.core.endpoint.Endpoint;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Merges OpenAPI-sourced endpoints with manual endpoints.
 *
 * Merge rule (see spec section 6.1 step 3):
 * - For same {@code (method, path)}, compare {@code lastModified}.
 * - Most recent wins.
 * - Tie (same {@code lastModified}) → manual wins.
 *
 * Result is sorted by {@code method} then {@code path}.
 */
public class EndpointMerger {

    public static final String SOURCE_OPENAPI = "openapi";
    public static final String SOURCE_MANUAL = "manual";

    /**
     * Merge two endpoint lists. Returns a new list sorted by method, path.
     */
    public List<MergedEndpoint> merge(List<Endpoint> openApiEndpoints, List<Endpoint> manualEndpoints) {
        Map<String, MergedEndpoint> byKey = new HashMap<>();

        for (Endpoint e : openApiEndpoints) {
            String key = key(e.getMethod(), e.getPath());
            Instant lm = nullSafe(e.getLastModified());
            byKey.put(key, new MergedEndpoint(e, SOURCE_OPENAPI, lm));
        }

        for (Endpoint e : manualEndpoints) {
            String key = key(e.getMethod(), e.getPath());
            Instant manualLm = nullSafe(e.getLastModified());
            MergedEndpoint existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, new MergedEndpoint(e, SOURCE_MANUAL, manualLm));
            } else if (manualLm.isAfter(existing.lastModified())) {
                byKey.put(key, new MergedEndpoint(e, SOURCE_MANUAL, manualLm));
            } else if (manualLm.equals(existing.lastModified())) {
                // tie-breaker: manual wins
                byKey.put(key, new MergedEndpoint(e, SOURCE_MANUAL, manualLm));
            }
            // else: existing (openapi) is newer — keep it
        }

        return byKey.values().stream()
            .sorted(Comparator
                .comparing((MergedEndpoint m) -> m.endpoint().getMethod(), Comparator.nullsLast(String::compareTo))
                .thenComparing(m -> m.endpoint().getPath(), Comparator.nullsLast(String::compareTo)))
            .toList();
    }

    /**
     * Find a merged endpoint by case-insensitive method match and exact path match.
     */
    public Optional<MergedEndpoint> find(List<MergedEndpoint> merged, String method, String path) {
        String normalizedMethod = method == null ? null : method.toUpperCase(Locale.ROOT);
        for (MergedEndpoint m : merged) {
            String mMethod = m.endpoint().getMethod();
            if (mMethod != null && mMethod.equalsIgnoreCase(normalizedMethod)
                && path != null
                && path.equals(m.endpoint().getPath())) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    private static String key(String method, String path) {
        return (method == null ? "" : method.toUpperCase(Locale.ROOT)) + " " + (path == null ? "" : path);
    }

    private static Instant nullSafe(Instant instant) {
        return instant == null ? Instant.EPOCH : instant;
    }
}
