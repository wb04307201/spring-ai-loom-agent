package cn.wubo.loom.http.core.invoke;

import cn.wubo.loom.http.core.endpoint.Endpoint;
import java.time.Instant;

/**
 * Result of merging an OpenAPI-sourced endpoint with a manual endpoint.
 * Carries the winning {@link Endpoint}, its source tag ("openapi" or "manual"),
 * and the {@code lastModified} timestamp used for the merge decision.
 */
public record MergedEndpoint(Endpoint endpoint, String source, Instant lastModified) {
}
