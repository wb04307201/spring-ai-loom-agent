package cn.wubo.loom.http.core.invoke;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpStorage;
import cn.wubo.loom.http.core.endpoint.Endpoint;
import cn.wubo.loom.http.core.history.HistoryEntry;
import cn.wubo.loom.http.core.history.HistoryService;
import cn.wubo.loom.http.core.profile.AuthProvider;
import cn.wubo.loom.http.core.profile.Profile;
import cn.wubo.loom.http.core.profile.ProfileService;
import cn.wubo.loom.http.core.security.DomainWhitelist;
import cn.wubo.loom.http.core.security.SensitiveFieldMasker;
import cn.wubo.loom.http.core.system.OpenApiCache;
import cn.wubo.loom.http.core.system.System;
import cn.wubo.loom.http.core.system.SystemService;
import cn.wubo.loom.http.core.util.JsonMappers;
import cn.wubo.loom.http.core.util.PathTemplater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Core orchestrator for the {@code invokeEndpoint} flow (spec §6.1 — 16 steps).
 *
 * Wires together:
 * <ul>
 *   <li>System & profile lookup</li>
 *   <li>Endpoint merging (OpenAPI + manual)</li>
 *   <li>Domain whitelist enforcement</li>
 *   <li>Path template substitution, URL assembly</li>
 *   <li>Header resolution (placeholders + auth)</li>
 *   <li>HTTP execution via {@link RestClient} with retry / backoff</li>
 *   <li>Large-response file spill</li>
 *   <li>JSONPath extraction, assertion evaluation</li>
 *   <li>Sensitive-field masking on response headers</li>
 *   <li>Unknown-endpoint suggestion builder</li>
 * </ul>
 *
 * <p>Two collaborators are stubbed in this revision and will be wired by later
 * tasks: history writing (Task 19, step 15) and contract validation (Task 18,
 * step 12 part 2).
 *
 * <p>Spring imports retained (Task 3 brief allowance): {@code RestClient},
 * {@code HttpHeaders}, {@code HttpMethod}, {@code MediaType},
 * {@code ResponseEntity}, {@code JdkClientHttpRequestFactory} from
 * {@code org.springframework.web} / {@code org.springframework.http} —
 * core depends on spring-web; only spring-context annotations
 * ({@code @Service}, {@code @Autowired}) are stripped.
 */
public class InvokeService {

    private static final Logger log = LoggerFactory.getLogger(InvokeService.class);

    private final SystemService systemService;
    private final ProfileService profileService;
    private final HttpConfig globalConfig;
    private final HttpStorage storageConfig;
    private final HistoryService historyService;
    private final OpenApiCache openApiCache;
    private final EndpointMerger endpointMerger = new EndpointMerger();
    private final JsonPathExtractor jsonPathExtractor = new JsonPathExtractor();
    private final AssertionEngine assertionEngine = new AssertionEngine();
    private final ContractValidator contractValidator = new ContractValidator();
    private final SensitiveFieldMasker defaultMasker;

    private HeaderResolver headerResolverFor(Profile profile) {
        return new HeaderResolver(globalConfig, profile, effectiveAllowedFilePaths(profile));
    }

    public InvokeService(SystemService systemService,
                         ProfileService profileService,
                         HttpConfig globalConfig,
                         HttpStorage storageConfig,
                         HistoryService historyService,
                         OpenApiCache openApiCache) {
        this.systemService = systemService;
        this.profileService = profileService;
        this.globalConfig = globalConfig;
        this.storageConfig = storageConfig;
        this.historyService = historyService;
        this.openApiCache = openApiCache;
        this.defaultMasker = new SensitiveFieldMasker(mergeSensitiveHeaders(null));
    }

    // ------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------

    /**
     * Execute the 16-step {@code invokeEndpoint} flow.
     *
     * Always returns a non-null {@link InvokeResponse}. On step-level failures
     * (system / profile not found, domain not allowed, missing base URL, etc.)
     * the response is still returned with the appropriate {@code error} field
     * set, mirroring the spec §6.2 error envelope shape.
     */
    public InvokeResponse invoke(InvokeRequest req) {
        long start = java.lang.System.currentTimeMillis();
        InvokeResponse out = new InvokeResponse();
        out.setSystem(req.getSystem());
        out.setMethod(req.getMethod());
        out.setPath(req.getPath());

        // Step 1 — Resolve system
        System sys;
        try {
            sys = systemService.get(req.getSystem());
        } catch (RuntimeException ex) {
            return error(out, "SystemNotFound", ex.getMessage(),
                "Use registerSystem or listResources(system://list)", start);
        }

        // Step 2 — Resolve profile.
        // Per-request override wins over the system's own authProfile: ad-hoc callers pass
        // the profile name on the request instead of writing it into the shared System.
        String authProfileName = firstNonBlank(req.getAuthProfileOverride(), sys.getAuthProfile());
        Profile profile = null;
        if (authProfileName != null) {
            try {
                profile = profileService.get(authProfileName);
            } catch (RuntimeException ex) {
                return error(out, "ProfileNotFound", ex.getMessage(),
                    "Use addProfile to create it", start);
            }
        }

        // Step 3 — Merge endpoint schema
        // Load OpenAPI endpoints from the cache (no-op when the system has no
        // openapi.source configured: loadResult returns an empty list) and merge
        // with the manually-registered endpoints. Either list may be empty.
        List<Endpoint> manualEndpoints = sys.getEndpoints() == null ? List.of() : sys.getEndpoints();
        List<Endpoint> openApiEndpoints = openApiCache.loadResult(sys).endpoints();
        List<MergedEndpoint> merged = endpointMerger.merge(openApiEndpoints, manualEndpoints);
        Optional<MergedEndpoint> foundEndpoint =
            endpointMerger.find(merged, req.getMethod(), req.getPath());

        // Step 4 — Domain whitelist
        DomainWhitelist effective = effectiveDomainWhitelist(profile);
        // We need a candidate URL to extract host. Build it first (steps 5-7 collapsed
        // here so we can compute host for the check).
        String resolvedPath;
        try {
            resolvedPath = replacePath(req.getPath(), req.getParams());
        } catch (IllegalArgumentException ex) {
            return error(out, "PathParamMissing", ex.getMessage(), null, start);
        }
        // Per-request override wins, then system.baseUrl, then profile.baseUrl.
        String baseUrl = firstNonBlank(req.getBaseUrlOverride(), sys.getBaseUrl());
        if (baseUrl == null) {
            baseUrl = profile != null ? profile.getBaseUrl() : null;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return error(out, "NoBaseUrl",
                "Neither system.baseUrl nor profile.baseUrl is set", null, start);
        }
        URI uri;
        try {
            uri = assembleUri(baseUrl, resolvedPath);
        } catch (RuntimeException ex) {
            return error(out, "NoBaseUrl", ex.getMessage(), null, start);
        }
        String host = uri.getHost();
        if (host == null || !effective.allows(host, uri.getPort())) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "DomainNotAllowed");
            err.put("message", "Host '" + host + "' is not in the allowed list");
            err.put("allowedDomains", effective.patterns());
            String portHint = (uri.getPort() > 0)
                ? " (or '" + host + ":" + uri.getPort() + "' to pin a specific port)"
                : "";
            err.put("suggestion", "Add '" + host + "'" + portHint + " to config.json or profile");
            out.setError(err);
            out.setLatencyMs(java.lang.System.currentTimeMillis() - start);
            return out;
        }

        // Step 5 — Parameter validation against schema (minimal: required path params).
        // If endpoint is found and a required path param is missing, return PathParamMissing.
        // Otherwise emit contractWarnings for any unknown params.
        List<String> contractWarnings = new ArrayList<>();
        if (foundEndpoint.isPresent()) {
            Set<String> declared = new HashSet<>();
            Set<String> requiredPath = new HashSet<>();
            Endpoint ep = foundEndpoint.get().endpoint();
            if (ep.getParameters() != null) {
                for (Endpoint.Parameter p : ep.getParameters()) {
                    if (p.getName() == null) continue;
                    declared.add(p.getName());
                    if (p.isRequired() && "path".equals(p.getIn())) {
                        requiredPath.add(p.getName());
                    }
                }
            }
            for (String r : requiredPath) {
                if (req.getParams() == null || req.getParams().get(r) == null) {
                    return error(out, "PathParamMissing",
                        "Required path parameter '" + r + "' is missing", null, start);
                }
            }
            if (req.getParams() != null) {
                for (String key : req.getParams().keySet()) {
                    if (!declared.contains(key) && !PathTemplater.extractPlaceholders(req.getPath()).contains(key)) {
                        contractWarnings.add("unexpectedParam:" + key);
                    }
                }
            }
        }

        // Step 6 — Path template substitution (already done above for host check).
        // resolvedPath computed above is used as-is.

        // Step 7 — URL assembly (already done above).

        // Step 8 — Header resolution (4-layer merge + placeholder + auth).
        Map<String, String> resolvedHeaders;
        String bodyJson = serializeBody(req.getBodyRaw(), req.getBody());
        try {
            HeaderResolver hr = headerResolverFor(profile);
            resolvedHeaders = hr.resolve(req.getHeaders() == null ? Map.of() : req.getHeaders(), bodyJson);
            // Resolve placeholders on auth fields BEFORE applying — auth.token,
            // auth.value, auth.password, auth.username may carry `${env:...}`,
            // `${file:...}`, `${hmac...}` etc. just like regular headers. Without
            // this step, the literal placeholder string lands in the outbound
            // Authorization header (regression: Finding #7).
            if (profile != null && profile.getAuth() != null) {
                Profile.Auth auth = profile.getAuth();
                Map<String, String> authCtx = new HashMap<>(resolvedHeaders);
                String token = hr.resolveValue(auth.getToken(), authCtx, bodyJson);
                String value = hr.resolveValue(auth.getValue(), authCtx, bodyJson);
                String password = hr.resolveValue(auth.getPassword(), authCtx, bodyJson);
                String username = hr.resolveValue(auth.getUsername(), authCtx, bodyJson);
                AuthProvider.applyResolved(auth.getType(), token, username, password,
                        auth.getKeyName(), value, resolvedHeaders, new HashMap<>());
            }
            if (req.getBody() != null && !resolvedHeaders.containsKey("Content-Type")
                && !resolvedHeaders.containsKey("content-type")) {
                resolvedHeaders.put("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            }
        } catch (HeaderResolutionException ex) {
            return error(out, "HeaderResolutionFailed", ex.getMessage(),
                "Check placeholder values and profile config", start);
        }

        // Step 9 — Execute with retry.
        long t0 = java.lang.System.currentTimeMillis();
        ExecutionResult exec;
        try {
            // bodyJson is already the wire-format string (bodyRaw verbatim
            // or Jackson-serialised structured body). Pass the resolved string
            // to executeWithRetry instead of re-serialising the raw object.
            exec = executeWithRetry(uri.toString(), req.getMethod(), resolvedHeaders, bodyJson, profile, req.getTimeoutMs());
        } catch (RuntimeException ex) {
            return error(out, "NetworkError", ex.getMessage(),
                "Check connectivity and target service", start);
        }
        long latencyMs = java.lang.System.currentTimeMillis() - t0;
        out.setRetryCount(exec.retryCount);

        int statusCode = exec.response.getStatusCode().value();
        String respBody = exec.response.getBody() == null ? "" : exec.response.getBody();
        byte[] respBytes = respBody.getBytes(StandardCharsets.UTF_8);
        Map<String, String> respHeaders = headersToMap(exec.response.getHeaders());

        // Step 10 — Response size handling.
        long maxBytes = globalConfig.getMaxResponseSizeBytes();
        String responseFile = null;
        String mode = req.getResponseMode() == null ? "full" : req.getResponseMode().toLowerCase();
        boolean forceFileMode = "file".equals(mode);
        boolean fileMode = forceFileMode || respBytes.length > maxBytes;
        if (fileMode) {
            responseFile = writeResponseToFile(respBody);
            respBody = null;
        }

        // Step 11 — JSONPath extraction.
        // Runs in every mode that has a body available: full + summary both
        // populate `extracted` so callers can read variables without re-parsing
        // the full body. Skipped in `file` mode (the body has been spilled to
        // disk; the caller reads it from `responseFile` and can run JsonPath
        // themselves).
        Map<String, Object> extracted = new LinkedHashMap<>();
        if (!fileMode && req.getExtract() != null && !req.getExtract().isEmpty()) {
            try {
                extracted = jsonPathExtractor.extract(respBody == null ? "" : respBody, req.getExtract());
            } catch (ExtractionException ex) {
                return error(out, "ExtractionFailed", ex.getMessage(), null, start);
            }
        }

        // Step 12 — Assertions + contract validation.
        AssertionResult ar = null;
        if (!mode.equals("file")) {
            // Even in summary mode the assertions run (per spec table in §6.1.16).
            HttpHeaders headerView = new HttpHeaders();
            respHeaders.forEach(headerView::add);
            String bodyForAssertions = respBody == null ? "" : respBody;
            ar = assertionEngine.evaluate(req.getAssertions(), statusCode, bodyForAssertions, headerView, latencyMs);
        } else {
            // For file mode spec says assertions still run, but we don't have the body.
            // In task 17 we evaluate against empty body — caller can re-read from file.
            ar = assertionEngine.evaluate(req.getAssertions(), statusCode, "", new HttpHeaders(), latencyMs);
        }
        // Contract validation (Task 18): shallow check of response body against the
        // merged endpoint's response schema. Warnings never block; populated only when
        // an endpoint was found and the schema actually declares properties.
        MergedEndpoint foundEp = foundEndpoint.orElse(null);
        String contractBody = respBody == null ? "" : respBody;
        List<String> contractResult = contractValidator.validate(statusCode, contractBody, foundEp);

        // Step 13 — Sensitive field masking on response headers.
        SensitiveFieldMasker shaker = profile != null
            ? new SensitiveFieldMasker(mergeSensitiveHeaders(profile))
            : defaultMasker;
        shaker.mask(respHeaders);

        // Step 14 — Unknown endpoint suggestion.
        Map<String, Object> suggestion = null;
        if (foundEndpoint.isEmpty()) {
            suggestion = buildUnknownEndpointSuggestion(sys, req, respBody);
        }

        // Step 15 — Write history (with already-masked request headers AND
        // body). Spec §6.1 step 15 requires headers to be masked before
        // persisting; we additionally mask request body fields per
        // sensitiveRequestBodyFields (global default + profile additions)
        // so credential-shaped fields never end up on disk in plaintext.
        try {
            HistoryEntry entry = buildHistoryEntry(req, sys, profile, statusCode,
                respBody, responseFile, ar, contractResult, exec.retryCount,
                out.getError(), start);
            shaker.mask(entry.getRequest().getHeaders());
            // Build a fresh masker for body fields. Same field set construction
            // as headers: global defaults merged with profile additions.
            // This is a separate call to keep header-vs-body field lists
            // independent — header fields (Authorization, Cookie, ...) are
            // not necessarily the same as body field names (password, token,
            // ...).
            SensitiveFieldMasker bodyMasker = new SensitiveFieldMasker(
                mergeSensitiveRequestBodyFields(profile));
            entry.getRequest().setBody(bodyMasker.maskBody(entry.getRequest().getBody()));
            historyService.append(sys.getName(), entry);
        } catch (RuntimeException ex) {
            log.warn("Failed to write history entry for system={}: {}",
                sys.getName(), ex.getMessage());
        }

        // Step 16 — Assemble response per responseMode.
        out.setStatusCode(statusCode);
        out.setLatencyMs(latencyMs);
        out.setAssertionResult(ar);
        out.setContractWarnings(contractResult);
        out.setSuggestion(suggestion);
        out.setResponseFile(responseFile);

        switch (mode) {
            case "summary" -> {
                // Summary keeps the compact view: hide body + headers, but
                // PRESERVE extracted (the whole point of summary mode is
                // callers don't have to re-parse the body themselves).
                out.setBody(null);
                out.setHeaders(new LinkedHashMap<>());
                out.setExtracted(extracted);
            }
            case "file" -> {
                // File mode spills body to disk for the caller to read. The
                // caller can run JsonPath over the file themselves, so
                // dropping extracted here is fine and avoids duplicate work.
                out.setBody(null);
                out.setHeaders(new LinkedHashMap<>());
                out.setExtracted(new LinkedHashMap<>());
            }
            default -> {
                out.setBody(respBody);
                out.setHeaders(respHeaders);
                out.setExtracted(extracted);
            }
        }
        // Always include contractWarnings (from step 12, even if stubbed).
        out.setContractWarnings(contractWarnings.isEmpty() ? contractResult : contractWarnings);

        return out;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private InvokeResponse error(InvokeResponse out, String code, String message,
                                 String suggestion, long startEpoch) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", code);
        err.put("message", message == null ? "" : message);
        if (suggestion != null) err.put("suggestion", suggestion);
        out.setError(err);
        out.setLatencyMs(java.lang.System.currentTimeMillis() - startEpoch);
        return out;
    }

    /**
     * 三态白名单策略(spec §4.4)。failClosed 是模式开关:
     * <ul>
     *   <li>内部模式(DefaultHttpTool)恒为 true —— 部署未配置 allowedDomains = 未授权对外访问。</li>
     *   <li>jar 模式(LoomHttpMcpService)默认 false —— 保持 http-mcp 单租户工具的既有行为,
     *       由 {@code loom.http.mcp.fail-closed} 属性开启。直接改死会静默改坏已发布 jar 的语义。</li>
     * </ul>
     *
     * <p>三种状态:
     * <ol>
     *   <li>{@code failClosed=true, profile.allowedDomains empty} → deny everything (RETURN EMPTY DomainWhitelist)</li>
     *   <li>{@code failClosed=true, profile.allowedDomains non-empty} → use {@code DomainWhitelist.effectiveAllowed(global)}
     *       (profile narrows global)</li>
     *   <li>{@code failClosed=false} → original fail-open behavior (source's "both empty → allow")</li>
     * </ol>
     */
    /**
     * First non-blank of the given values, or {@code null} when all are null/blank.
     * Used to layer per-request overrides over the system's stored values without
     * changing behaviour for callers that never set an override.
     */
    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private DomainWhitelist effectiveDomainWhitelist(Profile profile) {
        Set<String> global = globalConfig.getAllowedDomains() == null ? Set.of() : globalConfig.getAllowedDomains();
        Set<String> profileAllowed = profile == null ? null : profile.getAllowedDomains();
        boolean gEmpty = global.isEmpty();
        boolean pEmpty = profileAllowed == null || profileAllowed.isEmpty();

        if (!globalConfig.isFailClosed()) {
            // http-mcp 原行为(fail-open):empty global 退回 profile 白名单。
            //
            // profileAllowed 可能是 null(:453 在 profile 为 null 时就赋的 null),
            // 直接 new DomainWhitelist(null) 会在构造器里 new LinkedHashSet<>(patterns)
            // 抛 NPE —— 这是 loom-http-mcp 四个 http_* 工具全部不可用的根因
            // (其余五支都被 pEmpty / gEmpty 挡住,只有这一支漏了)。
            //
            // **双空时拒绝一切,不是放行 —— 这是恢复原语义,不是新决定。**
            // 证据:本方法在 c505ec16(从源项目搬运时)的原始实现里有一条被后续重构
            // 弄丢的显式分支 —
            //     // Both empty — return an empty whitelist (everything denied).
            //     if (gEmpty && pEmpty) return new DomainWhitelist(Set.of());
            // 2798efe4 引入 fail-closed 开关时,该分支被 if(!failClosed) / if(failClosed)
            // 两段结构吞掉,双空组合落进本支的 new DomainWhitelist(null) ⇒ NPE。
            // 所以这里补 pEmpty 判空,等于把 1.1.1 的语义原样接回来。
            if (gEmpty) return new DomainWhitelist(pEmpty ? java.util.Set.of() : profileAllowed);
            if (pEmpty) return new DomainWhitelist(global);
            return new DomainWhitelist(global).effectiveAllowed(new DomainWhitelist(profileAllowed));
        }
        // fail-closed 路径:global 空 = 部署未授权对外访问 = 拒绝一切。
        // profile 空 = 不施加额外约束(沿用 http-mcp 的 emptyProfileAllowedDomains
        // 回归测试所锁定的语义)。
        if (gEmpty) return new DomainWhitelist(java.util.Set.of());
        if (pEmpty) return new DomainWhitelist(global);
        return new DomainWhitelist(global).effectiveAllowed(new DomainWhitelist(profileAllowed));
    }

    private Set<String> mergeSensitiveHeaders(Profile profile) {
        Set<String> merged = new HashSet<>();
        if (globalConfig != null && globalConfig.getSensitiveHeaders() != null) {
            merged.addAll(globalConfig.getSensitiveHeaders());
        }
        if (profile != null && profile.getSensitiveHeaders() != null) {
            merged.addAll(profile.getSensitiveHeaders());
        }
        return merged;
    }

    /**
     * Merge the global default sensitive request body field names with any
     * profile-level additions. Profile extends (does NOT replace) the
     * global list — same semantics as {@link #mergeSensitiveHeaders(Profile)}
     * for headers.
     */
    private Set<String> mergeSensitiveRequestBodyFields(Profile profile) {
        Set<String> merged = new HashSet<>();
        if (globalConfig != null && globalConfig.getSensitiveRequestBodyFields() != null) {
            merged.addAll(globalConfig.getSensitiveRequestBodyFields());
        }
        if (profile != null && profile.getSensitiveRequestBodyFields() != null) {
            merged.addAll(profile.getSensitiveRequestBodyFields());
        }
        return merged;
    }

    private List<String> effectiveAllowedFilePaths(Profile profile) {
        List<String> paths = new ArrayList<>();
        paths.add(java.lang.System.getProperty("user.home"));
        paths.add(java.lang.System.getProperty("user.home") + java.io.File.separator + ".http-mcp");
        if (profile != null && profile.getAllowedFilePaths() != null) {
            paths.addAll(profile.getAllowedFilePaths());
        }
        return paths;
    }

    private String replacePath(String pathTemplate, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            // No params — template must have no placeholders.
            if (!PathTemplater.extractPlaceholders(pathTemplate).isEmpty()) {
                throw new IllegalArgumentException("Path template requires parameters but none provided: " + pathTemplate);
            }
            return pathTemplate;
        }
        Map<String, String> stringParams = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            stringParams.put(e.getKey(), e.getValue() == null ? null : e.getValue().toString());
        }
        return PathTemplater.replace(pathTemplate, stringParams);
    }

    private URI assembleUri(String baseUrl, String path) {
        String cleanedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String cleanedPath = path.startsWith("/") ? path : "/" + path;
        return URI.create(cleanedBase + cleanedPath);
    }

    /**
     * Resolve the request body to a wire-format string. {@code bodyRaw} wins
     * over {@code body} when both are present (the tool layer rejects that
     * combination at entry, so this is a safety net). For the structured
     * {@code body} path, {@code String} is returned as-is (preserved for
     * pre-v2 callers like {@link cn.wubo.loom.http.core.HttpService}), and anything
     * else is Jackson-serialised.
     */
    private String serializeBody(String bodyRaw, Object body) {
        if (bodyRaw != null) return bodyRaw;
        if (body == null) return null;
        if (body instanceof String s) return s;
        try {
            return JsonMappers.toJson(body);
        } catch (RuntimeException ex) {
            return body.toString();
        }
    }

    private ExecutionResult executeWithRetry(String url, String method, Map<String, String> headers,
                                              String body, Profile profile, Long timeoutMs) {
        int maxAttempts = profile != null && profile.getRetry() != null && profile.getRetry().getMaxAttempts() > 0
            ? profile.getRetry().getMaxAttempts() : 1;
        long backoffMs = profile != null && profile.getRetry() != null
            ? profile.getRetry().getBackoffMs() : 0L;
        Set<Integer> retryOn = profile != null && profile.getRetry() != null && profile.getRetry().getRetryOn() != null
            ? new HashSet<>(profile.getRetry().getRetryOn()) : Set.of();
        // Use the JDK HttpClient so there is no built-in retry layer — all retries
        // happen in this loop and are observable via wiremock request counts.
        // setReadTimeout(Duration) maps to HttpRequest.Builder.timeout() per call,
        // so a per-op timeoutMs flows all the way down to the JDK request.
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory();
        if (timeoutMs != null && timeoutMs > 0) {
            rf.setReadTimeout(Duration.ofMillis(timeoutMs));
        }
        RestClient client = RestClient.builder()
            .requestFactory(rf)
            .build();

        ResponseEntity<String> last = null;
        RuntimeException lastEx = null;
        int retries = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(method.toUpperCase())).uri(url);
                HttpHeaders hh = new HttpHeaders();
                headers.forEach(hh::add);
                hh.forEach((k, v) -> spec.header(k, v.toArray(new String[0])));
                if (body != null) {
                    spec.body(body);
                    if (!headers.containsKey("Content-Type") && !headers.containsKey("content-type")) {
                        spec.contentType(MediaType.APPLICATION_JSON);
                    }
                }
                ResponseEntity<String> resp = spec.retrieve()
                    .onStatus(s -> true, (req, res) -> { /* don't throw on any status */ })
                    .toEntity(String.class);
                int code = resp.getStatusCode().value();
                if (retryOn.contains(code) && attempt < maxAttempts) {
                    last = resp;
                    retries++;
                    sleepBackoff(backoffMs, attempt);
                    continue;
                }
                if (last == null) last = resp;
                return new ExecutionResult(last, retries);
            } catch (RuntimeException ex) {
                lastEx = ex;
                if (attempt >= maxAttempts) {
                    if (last != null) return new ExecutionResult(last, retries);
                    throw ex;
                }
                retries++;
                sleepBackoff(backoffMs, attempt);
            }
        }
        if (last != null) return new ExecutionResult(last, retries);
        if (lastEx != null) throw lastEx;
        throw new IllegalStateException("Retry loop exited without result");
    }

    private void sleepBackoff(long baseMs, int attempt) {
        if (baseMs <= 0) return;
        long ms = baseMs * (1L << (attempt - 1));
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, String> headersToMap(HttpHeaders headers) {
        Map<String, String> map = new LinkedHashMap<>();
        if (headers == null) return map;
        headers.forEach((k, v) -> {
            if (v != null && !v.isEmpty()) map.put(k, String.join(",", v));
        });
        return map;
    }

    private String writeResponseToFile(String body) {
        try {
            Path dir = storageConfig.responsesDir();
            Files.createDirectories(dir);
            String fname = Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".json";
            Path file = dir.resolve(fname);
            Files.writeString(file, body == null ? "" : body);
            return file.toString();
        } catch (IOException ex) {
            log.warn("Failed to write response file: {}", ex.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildUnknownEndpointSuggestion(System system, InvokeRequest req, String body) {
        Map<String, Object> suggestion = new LinkedHashMap<>();
        suggestion.put("kind", "unknownEndpoint");
        suggestion.put("message",
            req.getMethod() + " " + req.getPath() + " is not registered in system '" + system.getName()
                + "'. Add it via addEndpoint?");
        Map<String, Object> proposed = new LinkedHashMap<>();
        proposed.put("method", req.getMethod());
        proposed.put("path", req.getPath());
        if (req.getParams() != null && !req.getParams().isEmpty()) {
            List<Map<String, Object>> parameters = new ArrayList<>();
            for (String key : req.getParams().keySet()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("name", key);
                p.put("in", "query");
                p.put("required", false);
                p.put("schema", Map.of("type", "string"));
                p.put("source", "inferred");
                parameters.add(p);
            }
            proposed.put("parameters", parameters);
        } else {
            proposed.put("parameters", List.of());
        }
        proposed.put("requestBody", null);
        Map<String, Object> respExample = new LinkedHashMap<>();
        respExample.put("description", "OK");
        if (body != null) {
            String example = body.length() > 2048 ? body.substring(0, 2048) : body;
            respExample.put("example", example);
        }
        proposed.put("responses", Map.of("200", respExample));
        suggestion.put("proposedDefinition", proposed);
        return suggestion;
    }

    /**
     * Build the {@link HistoryEntry} for step 15. Captures the resolved
     * request and response state (statusCode, body size, response file,
     * assertion + contract outcome, retry count, error). Headers are NOT
     * masked here — the caller (step 15) applies {@link SensitiveFieldMasker}
     * to {@code entry.request.headers} just before persisting, so the
     * masker matches the one used by step 13 (profile-aware).
     */
    private HistoryEntry buildHistoryEntry(InvokeRequest req, System sys, Profile profile,
                                            int statusCode, String respBody, String responseFile,
                                            AssertionResult ar, List<String> contractResult,
                                            int retryCount, Object error, long startEpoch) {
        HistoryEntry e = new HistoryEntry();
        e.setTimestamp(Instant.ofEpochMilli(startEpoch));
        e.setSystem(sys.getName());
        e.setProfile(profile != null ? profile.getName() : null);
        e.setTool("invokeEndpoint");
        e.setMethod(req.getMethod());
        e.setPath(req.getPath());
        e.getRequest().setParams(req.getParams());
        e.getRequest().setBody(req.getBody());
        // Caller masks these after this method returns.
        if (req.getHeaders() != null) {
            e.getRequest().getHeaders().putAll(req.getHeaders());
        }
        e.getResponse().setStatusCode(statusCode);
        e.getResponse().setLatencyMs(java.lang.System.currentTimeMillis() - startEpoch);
        e.getResponse().setBodySize(respBody == null ? 0 : respBody.getBytes(StandardCharsets.UTF_8).length);
        e.getResponse().setResponseFile(responseFile);
        if (ar != null) {
            e.getAssertion().setPassed(ar.passed());
            if (ar.failures() != null) {
                for (AssertionResult.Failure f : ar.failures()) {
                    e.getAssertion().getFailures().add(f.assertion());
                }
            }
        }
        if (contractResult != null) {
            e.getContract().getWarnings().addAll(contractResult);
        }
        e.setRetryCount(retryCount);
        e.setError(error);
        return e;
    }

    private record ExecutionResult(ResponseEntity<String> response, int retryCount) { }
}
