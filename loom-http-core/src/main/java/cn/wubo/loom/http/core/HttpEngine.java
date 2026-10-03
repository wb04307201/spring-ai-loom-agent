package cn.wubo.loom.http.core;

import cn.wubo.loom.http.core.endpoint.Endpoint;
import cn.wubo.loom.http.core.endpoint.EndpointService;
import cn.wubo.loom.http.core.history.HistoryService;
import cn.wubo.loom.http.core.invoke.BatchRequest;
import cn.wubo.loom.http.core.invoke.BatchResult;
import cn.wubo.loom.http.core.invoke.BatchService;
import cn.wubo.loom.http.core.invoke.InvokeRequest;
import cn.wubo.loom.http.core.invoke.InvokeService;
import cn.wubo.loom.http.core.profile.Profile;
import cn.wubo.loom.http.core.profile.ProfileService;
import cn.wubo.loom.http.core.profile.ProfileValidator;
import cn.wubo.loom.http.core.system.OpenApiCache;
import cn.wubo.loom.http.core.system.System;
import cn.wubo.loom.http.core.system.SystemNotFoundException;
import cn.wubo.loom.http.core.system.SystemService;
import cn.wubo.loom.http.core.util.JsonMappers;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 能力唯一门面 —— 内部工具壳与独立 jar 壳的共同入口。
 *
 * <p><b>设计要点</b>:
 * <ul>
 *   <li><b>存储根是数据不是配置</b>:构造参数 {@code storageRoot} 决定了 profile /
 *       system / history 落在哪。内部工具模式传
 *       {@code LoomPaths.userHttpDir(usersBasePath, username)}(per-user),
 *       jar 模式传 {@code loom.http.mcp.basePath}(扁平)。这是双模的全部差异。</li>
 *   <li><b>全线返回 String</b>:MCP 与 LLM 工具都直接序列化返回值;返回领域对象
 *       会触发二次序列化破坏响应形状(http-mcp 规则 #3)。</li>
 *   <li><b>不抛异常给调用方</b>:业务错误以 JSON 的 {@code error} 字段返回,
 *       与 http-mcp 的 {@code InvokeTools} 行为一致。</li>
 *   <li><b>每次调用 new 一个实例是正常的</b>:引擎持有各存储服务的内存缓存与
 *       OpenAPI 缓存,绑死存储根,不能跨用户复用。</li>
 * </ul>
 */
public final class HttpEngine {

    private final HttpStorage storage;
    private final HttpConfig config;
    private final ProfileService profileService;
    private final SystemService systemService;
    private final EndpointService endpointService;
    private final HistoryService historyService;
    private final OpenApiCache openApiCache;
    private final InvokeService invokeService;
    private final BatchService batchService;
    private final ProfileValidator profileValidator = new ProfileValidator();

    public HttpEngine(Path storageRoot, HttpConfig config) {
        this.storage = new HttpStorage(storageRoot);
        this.config = config != null ? config : new HttpConfig();
        RestClient restClient = RestClient.builder().build();
        this.profileService = new ProfileService(this.storage, this.config);
        this.systemService = new SystemService(this.storage, this.config);
        this.endpointService = new EndpointService(this.systemService);
        this.historyService = new HistoryService(this.storage, this.config);
        this.openApiCache = new OpenApiCache(this.storage, restClient);
        this.invokeService = new InvokeService(this.systemService, this.profileService,
                this.config, this.storage, this.historyService, this.openApiCache);
        this.batchService = new BatchService(this.invokeService, this.config);
    }

    public static HttpEngine of(String storageRoot, HttpConfig config) {
        return new HttpEngine(Path.of(storageRoot), config);
    }

    public HttpStorage storage() { return storage; }
    public HttpConfig config()   { return config; }

    // ==================== 调用面 ====================

    public String invokeEndpoint(InvokeRequest req) {
        return toJson(() -> invokeService.invoke(req));
    }

    public String httpBatch(BatchRequest req) {
        return toJson(() -> batchService.execute(req));
    }

    /** 端点清单:合并 manual + openapi 后按 tag / source 过滤(EndpointTools#listEndpoints 的无注解版)。 */
    public String listEndpoints(String system, String tag, String source) {
        return toJson(() -> {
            System s = systemService.get(system);
            return endpointService.listManual(s);   // openapi 段在 Step 5 用 EndpointMerger 补齐
        });
    }

    public String getEndpoint(String system, String method, String path) {
        return toJson(() -> systemService.get(system));
    }

    /** since 为 null 时表示不过滤;limit 传 0 表示用配置默认值。 */
    public String getRequestHistory(String system, int limit, String statusFilter, java.time.Instant since) {
        return toJson(() -> historyService.query(system, limit, statusFilter, since));
    }

    // ==================== 注册面 ====================

    /**
     * 新建 profile。<b>先校验后落盘</b>:ERROR 级问题(如 auth.type 拼错、bearer 缺
     * token、header key 空白)直接拒绝,WARNING(如 ${} 占位符不闭合)随信封返回。
     *
     * <p>这一步是 http-mcp {@code ProfileTools.addProfile} 的校验逻辑 —— 它住在带
     * {@code @Tool} 的壳里,不搬则失去校验。缺了它,{@code auth.type="beareer"} 这类
     * 笔误要等到首次调用才暴露,且错误信息远不如注册时清晰。
     */
    public String addProfile(Profile p) {
        return toJson(() -> {
            List<ProfileValidator.ValidationError> findings = profileValidator.validate(p);
            if (profileValidator.hasErrors(findings)) {
                throw new IllegalArgumentException("Profile validation failed: " + joinErrors(findings));
            }
            profileService.save(p);
            return profileEnvelope(p, "created", findings);
        });
    }

    /** 更新 profile:先合并再校验,规则与 {@link #addProfile} 完全一致。 */
    public String updateProfile(String name, Profile patch) {
        return toJson(() -> {
            Profile p = mergeProfile(profileService.get(name), patch);
            List<ProfileValidator.ValidationError> findings = profileValidator.validate(p);
            if (profileValidator.hasErrors(findings)) {
                throw new IllegalArgumentException("Profile validation failed: " + joinErrors(findings));
            }
            profileService.save(p);
            return profileEnvelope(p, "updated", findings);
        });
    }

    /**
     * 合并两个 profile:patch 字段非 null 时覆盖原值,null 时保留。
     * 整体替换 auth / retry 块(无字段级合并,与 http-mcp ProfileTools 一致)。
     */
    private static Profile mergeProfile(Profile existing, Profile patch) {
        if (patch == null) return existing;
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getBaseUrl() != null) existing.setBaseUrl(patch.getBaseUrl());
        if (patch.getAuth() != null && patch.getAuth().getType() != null) existing.setAuth(patch.getAuth());
        if (patch.getCustomHeaders() != null && !patch.getCustomHeaders().isEmpty()) existing.setCustomHeaders(patch.getCustomHeaders());
        if (patch.getDefaultHeaders() != null && !patch.getDefaultHeaders().isEmpty()) existing.setDefaultHeaders(patch.getDefaultHeaders());
        if (patch.getAllowedDomains() != null && !patch.getAllowedDomains().isEmpty()) existing.setAllowedDomains(patch.getAllowedDomains());
        if (patch.getSensitiveHeaders() != null && !patch.getSensitiveHeaders().isEmpty()) existing.setSensitiveHeaders(patch.getSensitiveHeaders());
        if (patch.getSensitiveRequestBodyFields() != null && !patch.getSensitiveRequestBodyFields().isEmpty()) existing.setSensitiveRequestBodyFields(patch.getSensitiveRequestBodyFields());
        if (patch.getAllowedFilePaths() != null && !patch.getAllowedFilePaths().isEmpty()) existing.setAllowedFilePaths(patch.getAllowedFilePaths());
        if (patch.getTimeoutMs() > 0) existing.setTimeoutMs(patch.getTimeoutMs());
        if (patch.getRetry() != null && patch.getRetry().getMaxAttempts() > 0) existing.setRetry(patch.getRetry());
        return existing;
    }

    /** 只取 ERROR 级拼成一句话;WARNING 不阻断,交给调用方决定。 */
    private static String joinErrors(List<ProfileValidator.ValidationError> findings) {
        return findings.stream()
                .filter(f -> f.severity() == ProfileValidator.Severity.ERROR)
                .map(ProfileValidator.ValidationError::toString)
                .reduce((a, b) -> a + "; " + b)
                .orElse("invalid profile");
    }

    /** 注册成功信封:{name, <verb>: true, file, warnings?} —— 与 http-mcp 的形状一致。 */
    private Map<String, Object> profileEnvelope(Profile p, String verb,
                                                List<ProfileValidator.ValidationError> findings) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("name", p.getName());
        envelope.put(verb, true);
        envelope.put("file", storage.profilesDir().resolve(p.getName() + ".json").toString());
        if (!findings.isEmpty()) {
            envelope.put("warnings", findings.stream()
                    .filter(f -> f.severity() == ProfileValidator.Severity.WARNING)
                    .map(ProfileValidator.ValidationError::toString)
                    .toList());
        }
        return envelope;
    }

    public String removeProfile(String name) {
        return toJson(() -> { profileService.delete(name); return name; });
    }

    /**
     * 注册 system。拒绝重名({@code SystemService.get} 抛异常即"不存在",借此做
     * exists? 检查而不碰 cache 内部),再跑 {@code ProfileValidator#validateSystem}
     * —— baseUrl 必须是合法 http(s) URL,漏写 scheme 这类笔误在注册时拦住,
     * 而不是等到首次调用变成 DNS 错误。
     */
    public String registerSystem(System s) {
        return toJson(() -> {
            try {
                systemService.get(s.getName());
                throw new IllegalArgumentException("System already exists: " + s.getName());
            } catch (SystemNotFoundException ignored) {
                // 名字没被占用 —— 正常路径
            }
            List<ProfileValidator.ValidationError> findings = profileValidator.validateSystem(s);
            if (profileValidator.hasErrors(findings)) {
                throw new IllegalArgumentException("System validation failed: " + joinErrors(findings));
            }
            systemService.save(s);
            return systemEnvelope(s, "created");
        });
    }

    /** 逐字段合并后同样校验,规则与 {@link #registerSystem} 一致。 */
    public String updateSystem(String name, System patch) {
        return toJson(() -> {
            System s = mergeSystem(systemService.get(name), patch);
            List<ProfileValidator.ValidationError> findings = profileValidator.validateSystem(s);
            if (profileValidator.hasErrors(findings)) {
                throw new IllegalArgumentException("System validation failed: " + joinErrors(findings));
            }
            systemService.save(s);
            return systemEnvelope(s, "updated");
        });
    }

    /** 合并 system 字段 —— patch 字段非 null 时覆盖,整体替换 openapi / endpoints 块。 */
    private static System mergeSystem(System existing, System patch) {
        if (patch == null) return existing;
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getBaseUrl() != null) existing.setBaseUrl(patch.getBaseUrl());
        if (patch.getAuthProfile() != null) existing.setAuthProfile(patch.getAuthProfile());
        if (patch.getOpenapi() != null && patch.getOpenapi().getSource() != null) existing.setOpenapi(patch.getOpenapi());
        if (patch.getEndpoints() != null && !patch.getEndpoints().isEmpty()) existing.setEndpoints(patch.getEndpoints());
        return existing;
    }

    /** 注册/更新成功信封:{name, <verb>: true, file}。 */
    private Map<String, Object> systemEnvelope(System s, String verb) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("name", s.getName());
        envelope.put(verb, true);
        envelope.put("file", storage.systemsDir().resolve(s.getName() + ".json").toString());
        return envelope;
    }

    public String removeSystem(String name, boolean deleteOpenApiCache) {
        return toJson(() -> {
            systemService.delete(name);
            if (deleteOpenApiCache) {
                openApiCache.invalidate(name);
            }
            return name;
        });
    }

    /** 强制刷新 OpenAPI 元数据;name 为 null 表示全部刷新。 */
    public String refreshSystem(String name) {
        return toJson(() -> {
            if (name == null || name.isBlank()) {
                List<System> all = systemService.listAll();
                List<String> refreshed = new ArrayList<>();
                for (System s : all) {
                    openApiCache.loadResult(s);
                    refreshed.add(s.getName());
                }
                return refreshed;
            }
            System s = systemService.get(name);
            OpenApiCache.LoadResult r = openApiCache.loadResult(s);
            return r;
        });
    }

    /** 列出 profile 名(不含凭据),供 REST 读接口用。 */
    public List<String> listProfiles() {
        List<String> out = new ArrayList<>();
        profileService.listAll().forEach(p -> out.add(p.getName()));
        return out;
    }

    /** 列出 system 名,供 REST 读接口用。 */
    public List<String> listSystems() {
        List<String> out = new ArrayList<>();
        systemService.listAll().forEach(s -> out.add(s.getName()));
        return out;
    }

    // ==================== endpoint 写面 ====================

    /**
     * 添加一个手动 endpoint 到指定 system。字段构建委托给
     * {@link EndpointService#add(System, Endpoint)}。
     */
    public String addEndpoint(String system, String method, String path,
                              List<Map<String, Object>> parameters, Map<String, Object> requestBody,
                              Map<String, Object> responses, String summary,
                              String description, List<String> tags) {
        return toJson(() -> {
            System s = systemService.get(system);
            Endpoint endpoint = new Endpoint();
            endpoint.setMethod(method);
            endpoint.setPath(path);
            if (summary != null) endpoint.setSummary(summary);
            if (description != null) endpoint.setDescription(description);
            if (tags != null) endpoint.setTags(new ArrayList<>(tags));
            if (parameters != null && !parameters.isEmpty()) {
                List<Endpoint.Parameter> params = new ArrayList<>();
                for (Map<String, Object> p : parameters) {
                    params.add(JsonMappers.mapper().convertValue(p, Endpoint.Parameter.class));
                }
                endpoint.setParameters(params);
            }
            if (requestBody != null && !requestBody.isEmpty()) {
                endpoint.setRequestBody(JsonMappers.mapper().convertValue(requestBody, Endpoint.RequestBody.class));
            }
            if (responses != null && !responses.isEmpty()) {
                endpoint.setResponses(JsonMappers.mapper().convertValue(responses,
                    JsonMappers.mapper().getTypeFactory()
                        .constructMapType(LinkedHashMap.class, String.class, Endpoint.Response.class)));
            }
            Endpoint persisted = endpointService.add(s, endpoint);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("system", s.getName());
            envelope.put("method", persisted.getMethod());
            envelope.put("path", persisted.getPath());
            envelope.put("lastModified", persisted.getLastModified() == null ? null : persisted.getLastModified().toString());
            envelope.put("mergedFrom", "manual");
            return envelope;
        });
    }

    /**
     * 部分更新一个手动 endpoint。{@code patch} 是字段名到新值的映射,
     * 只覆盖 patch 中显式出现的字段,其他字段保持不变。
     */
    public String updateEndpoint(String system, String method, String path, Map<String, Object> patch) {
        return toJson(() -> {
            System s = systemService.get(system);
            Endpoint persisted = endpointService.update(s, method, path, patch);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("system", s.getName());
            envelope.put("method", persisted.getMethod());
            envelope.put("path", persisted.getPath());
            envelope.put("lastModified", persisted.getLastModified() == null ? null : persisted.getLastModified().toString());
            envelope.put("mergedFrom", "manual");
            return envelope;
        });
    }

    /** 删除一个手动 endpoint(不影响 OpenAPI 缓存)。 */
    public String removeEndpoint(String system, String method, String path) {
        return toJson(() -> {
            System s = systemService.get(system);
            endpointService.remove(s, method, path);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("system", s.getName());
            envelope.put("method", method);
            envelope.put("path", path);
            envelope.put("removed", true);
            return envelope;
        });
    }

    // ==================== 错误折叠 ====================

    /**
     * 统一错误折叠:任何异常都变成 {@code {"error":..., "message":...}}。
     * 绝不把异常抛给 LLM —— 它无法处理栈轨迹,只会重试或胡言。
     */
    private static String toJson(java.util.function.Supplier<Object> action) {
        try {
            Object result = action.get();
            return result instanceof String s ? s : JsonMappers.toJson(result);
        } catch (RuntimeException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getClass().getSimpleName());
            err.put("message", e.getMessage() == null ? "" : e.getMessage());
            return JsonMappers.toJson(err);
        }
    }
}
