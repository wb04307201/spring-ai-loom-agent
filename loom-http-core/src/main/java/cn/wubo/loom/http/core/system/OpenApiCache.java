package cn.wubo.loom.http.core.system;

import cn.wubo.loom.http.core.HttpStorage;
import cn.wubo.loom.http.core.endpoint.Endpoint;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads an OpenAPI document for a system and caches it on disk.
 * <p>
 * Cache file lives at {@code <root>/systems/<name>/openapi.json}. For HTTP sources
 * the request carries an {@code If-None-Match} header so the server can short-circuit
 * with {@code 304 Not Modified}; for local file sources we compare mtimes to skip
 * the read when the source file has not changed.
 * <p>
 * Parsing is delegated to {@link OpenAPIParser} which auto-detects OpenAPI 3.0,
 * OpenAPI 3.1 and Swagger 2.0 documents and resolves Swagger 2.0 to the OpenAPI 3
 * model via the bundled {@code SwaggerConverter} SPI extension.
 */
public class OpenApiCache {

    private static final Logger log = LoggerFactory.getLogger(OpenApiCache.class);

    private final HttpStorage storage;
    private final RestClient restClient;

    public OpenApiCache(HttpStorage storage, RestClient restClient) {
        this.storage = storage;
        this.restClient = restClient;
    }

    /**
     * Result of loading an OpenAPI document: the parsed endpoints together
     * with metadata describing how the cache was used.
     *
     * <ul>
     *   <li>{@code refreshed=true} when the document was freshly fetched
     *       (HTTP 200 with a new etag, or local source re-read).</li>
     *   <li>{@code refreshed=false} when a cached copy was reused — either
     *       an HTTP {@code 304 Not Modified} (reason {@code "notModified"})
     *       or a local source whose mtime is older than the cache file
     *       (reason {@code "localCacheFresh"}).</li>
     *   <li>{@code reason="noSource"} when the system has no OpenAPI
     *       source configured; the endpoints list is empty and no work
     *       was performed.</li>
     * </ul>
     */
    public record LoadResult(List<Endpoint> endpoints, boolean refreshed, String reason) {}

    /**
     * Load and parse the OpenAPI document associated with {@code system},
     * returning metadata that distinguishes a fresh fetch from a cache hit.
     *
     * <p>If {@code system.openapi.source} is null or blank, returns a
     * {@code LoadResult} with empty endpoints, {@code refreshed=false} and
     * {@code reason="noSource"} (no fetch was attempted). For HTTP sources
     * the request is sent with an {@code If-None-Match} header carrying the
     * previously stored etag; a {@code 304} response is treated as
     * "cache still fresh" and the existing on-disk cache is parsed
     * ({@code refreshed=false, reason="notModified"}). For local file
     * sources the source file's mtime is compared against the cache file's
     * mtime to avoid redundant work ({@code refreshed=false,
     * reason="localCacheFresh"} when the cache is fresh).
     *
     * @param system the system whose OpenAPI source should be loaded
     * @return a {@link LoadResult} describing what was loaded and how
     * @throws RuntimeException if the source cannot be read or parsed
     */
    public LoadResult loadResult(System system) {
        String source = system.getOpenapi() == null ? null : system.getOpenapi().getSource();
        if (source == null || source.isBlank()) {
            return new LoadResult(List.of(), false, "noSource");
        }

        Path cacheFile = ensureCacheDir(system.getName());
        String content;
        boolean refreshed;
        String reason = null;
        Instant lastLoadedAt = Instant.now();
        // Remember the previous successful load so a stale-cache fallback can
        // preserve the original timestamp instead of silently bumping it to
        // "now" (which would mask the fact that the upstream is unreachable).
        String previousLastLoadedAt = system.getOpenapi() == null ? null : system.getOpenapi().getLastLoadedAt();

        if (isHttp(source)) {
            try {
                content = fetchHttp(system, source, cacheFile);
                if (content == null) {
                    // 304 — server says our cached copy is still fresh
                    refreshed = false;
                    reason = "notModified";
                    content = readOrThrow(cacheFile);
                } else {
                    refreshed = true;
                    writeCache(cacheFile, content);
                }
            } catch (RuntimeException fetchFailure) {
                // Source unreachable / timed out / mid-fetch IO error. Fall back
                // to the previously cached document so listEndpoints and
                // invokeEndpoint don't silently return empty when an upstream
                // blip happens. The fallback is flagged reason="staleCache" so
                // callers (and audit reviewers) can tell it isn't a fresh read.
                // If no cached copy exists at all, rethrow so the caller sees
                // the original failure rather than an empty endpoint set.
                if (Files.exists(cacheFile)) {
                    log.warn("OpenAPI fetch failed for system '{}', falling back to stale cache at {}: {}",
                        system.getName(), cacheFile, fetchFailure.getMessage());
                    content = readOrThrow(cacheFile);
                    refreshed = false;
                    reason = "staleCache";
                    if (previousLastLoadedAt != null) {
                        try {
                            lastLoadedAt = Instant.parse(previousLastLoadedAt);
                        } catch (DateTimeParseException ignored) {
                            // Malformed previous value — leave lastLoadedAt as
                            // Instant.now() so the envelope still has a usable
                            // timestamp.
                        }
                    }
                } else {
                    throw fetchFailure;
                }
            }
        } else {
            Path src = Path.of(source);
            if (Files.exists(cacheFile) && isCacheFresh(src, cacheFile)) {
                content = readOrThrow(cacheFile);
                refreshed = false;
                reason = "localCacheFresh";
            } else {
                content = readOrThrow(src);
                writeCache(cacheFile, content);
                refreshed = true;
            }
        }

        try {
            List<Endpoint> endpoints = parse(content, lastLoadedAt);
            system.getOpenapi().setLastLoadedAt(lastLoadedAt.toString());
            return new LoadResult(endpoints, refreshed, reason);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse OpenAPI document for system '" + system.getName() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Backward-compatible wrapper around {@link #loadResult(System)} that
     * returns only the endpoint list. Existing callers (resources,
     * invoke flow) keep working without modification.
     */
    public List<Endpoint> load(System system) {
        return loadResult(system).endpoints();
    }

    // ---------------------------------------------------------------------
    // HTTP fetch (304-aware)
    // ---------------------------------------------------------------------

    private String fetchHttp(System system, String source, Path cacheFile) {
        String etag = system.getOpenapi().getEtag();
        try {
            RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(source);
            if (etag != null && !etag.isBlank()) {
                spec = spec.header("If-None-Match", etag);
            }
            ResponseEntity<String> resp = ((RestClient.RequestHeadersSpec<?>) spec)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> { /* don't throw on 4xx/5xx — we inspect status manually */ })
                .toEntity(String.class);

            int status = resp.getStatusCode().value();
            if (status == 304) {
                return null; // signal "use cache"
            }
            // Capture any new etag for next call.
            List<String> etagHeaders = resp.getHeaders().get("ETag");
            if (etagHeaders != null && !etagHeaders.isEmpty()) {
                system.getOpenapi().setEtag(etagHeaders.get(0));
            }
            String body = resp.getBody();
            if (body == null || body.isEmpty()) {
                throw new RuntimeException("Empty response body from " + source);
            }
            return body;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch OpenAPI from " + source + ": " + e.getMessage(), e);
        }
    }

    private static boolean isHttp(String source) {
        String lower = source.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    // ---------------------------------------------------------------------
    // File helpers
    // ---------------------------------------------------------------------

    private Path ensureCacheDir(String systemName) {
        try {
            Path dir = storage.systemsDir().resolve(systemName);
            Files.createDirectories(dir);
            return dir.resolve("openapi.json");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create cache dir for system '" + systemName + "'", e);
        }
    }

    private static boolean isCacheFresh(Path source, Path cache) {
        try {
            Instant srcMtime = Files.getLastModifiedTime(source).toInstant();
            Instant cacheMtime = Files.getLastModifiedTime(cache).toInstant();
            return !cacheMtime.isBefore(srcMtime);
        } catch (IOException e) {
            return false;
        }
    }

    private static String readOrThrow(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + file, e);
        }
    }

    private static void writeCache(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write cache file " + file, e);
        }
    }

    // ---------------------------------------------------------------------
    // OpenAPI 3 → Endpoint[] conversion
    // ---------------------------------------------------------------------

    /**
     * Parse the given OpenAPI document (JSON or YAML) and convert it into our
     * internal {@link Endpoint} list. Each (method, path) pair produces one
     * endpoint whose parameters, request body and responses are copied across.
     */
    private List<Endpoint> parse(String content, Instant lastModified) {
        // Enable $ref resolution so multi-level #/components/schemas/* chains
        // are inlined into the returned Schema objects. Without this, callers
        // (ContractValidator in particular) would see only a "$ref" pointer and
        // no inline properties to validate against.
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        SwaggerParseResult result = new OpenAPIParser().readContents(content, new ArrayList<>(), options);
        OpenAPI openAPI = result == null ? null : result.getOpenAPI();
        if (openAPI == null) {
            String messages = result == null || result.getMessages() == null
                ? "no parse result"
                : String.join("; ", result.getMessages());
            throw new RuntimeException("OpenAPI document could not be parsed: " + messages);
        }
        if (openAPI.getPaths() == null) {
            return List.of();
        }

        List<Endpoint> out = new ArrayList<>();
        for (Map.Entry<String, PathItem> entry : openAPI.getPaths().entrySet()) {
            String path = entry.getKey();
            PathItem item = entry.getValue();
            if (item == null) continue;

            addIfPresent(out, path, "GET", item.getGet(), lastModified);
            addIfPresent(out, path, "POST", item.getPost(), lastModified);
            addIfPresent(out, path, "PUT", item.getPut(), lastModified);
            addIfPresent(out, path, "DELETE", item.getDelete(), lastModified);
            addIfPresent(out, path, "PATCH", item.getPatch(), lastModified);
            addIfPresent(out, path, "HEAD", item.getHead(), lastModified);
            addIfPresent(out, path, "OPTIONS", item.getOptions(), lastModified);
            addIfPresent(out, path, "TRACE", item.getTrace(), lastModified);
        }
        return out;
    }

    private void addIfPresent(List<Endpoint> out, String path, String method, Operation op, Instant lastModified) {
        if (op == null) return;

        Endpoint e = new Endpoint();
        e.setMethod(method);
        e.setPath(path);
        e.setSummary(op.getSummary());
        e.setDescription(op.getDescription());
        e.setTags(op.getTags() == null ? new ArrayList<>() : new ArrayList<>(op.getTags()));
        e.setLastModified(lastModified);

        if (op.getParameters() != null) {
            List<Endpoint.Parameter> params = new ArrayList<>();
            for (Parameter p : op.getParameters()) {
                Endpoint.Parameter ep = new Endpoint.Parameter();
                ep.setName(p.getName());
                ep.setIn(p.getIn());
                ep.setRequired(p.getRequired() != null && p.getRequired());
                ep.setDescription(p.getDescription());
                ep.setSchema(p.getSchema() == null ? null : toMap(p.getSchema()));
                params.add(ep);
            }
            e.setParameters(params);
        }

        RequestBody rb = op.getRequestBody();
        if (rb != null) {
            Endpoint.RequestBody erb = new Endpoint.RequestBody();
            erb.setRequired(rb.getRequired() != null && rb.getRequired());
            if (rb.getContent() != null) {
                Map<String, Endpoint.MediaType> content = new LinkedHashMap<>();
                rb.getContent().forEach((mediaType, mt) -> {
                    Endpoint.MediaType emt = new Endpoint.MediaType();
                    emt.setSchema(mt.getSchema() == null ? null : toMap(mt.getSchema()));
                    content.put(mediaType, emt);
                });
                erb.setContent(content);
            }
            e.setRequestBody(erb);
        }

        if (op.getResponses() != null) {
            Map<String, Endpoint.Response> responses = new LinkedHashMap<>();
            op.getResponses().forEach((code, resp) -> {
                Endpoint.Response er = new Endpoint.Response();
                er.setDescription(resp.getDescription());
                if (resp.getContent() != null) {
                    Map<String, Endpoint.MediaType> content = new LinkedHashMap<>();
                    resp.getContent().forEach((mediaType, mt) -> {
                        Endpoint.MediaType emt = new Endpoint.MediaType();
                        emt.setSchema(mt.getSchema() == null ? null : toMap(mt.getSchema()));
                        content.put(mediaType, emt);
                    });
                    er.setContent(content);
                }
                responses.put(code, er);
            });
            e.setResponses(responses);
        }

        out.add(e);
    }

    /**
     * Flatten a Swagger {@code Schema} into a plain {@link Map}. With
     * {@code resolveFully=true} in {@link #parse}, multi-level {@code $ref}
     * chains are already inlined into the Schema object, so a JSON round-trip
     * through Jackson produces a clean nested Map (with null fields omitted so
     * the resulting map mirrors the structure of the original OpenAPI doc).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object schema) {
        if (schema == null) return null;
        if (schema instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        // Fall back to reflection via a small JSON round-trip — covers Schema, composed schemas, etc.
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
            String json = mapper.writeValueAsString(schema);
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("_unparsable", schema.toString());
        }
    }
}
