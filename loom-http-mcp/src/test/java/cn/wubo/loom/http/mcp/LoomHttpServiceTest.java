package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.HttpStorage;
import cn.wubo.loom.http.core.profile.Profile;
import cn.wubo.loom.http.core.profile.ProfileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LoomHttpService} 的 4 个 {@code http_get/post/put/delete} 工具测试。
 *
 * <p>验两件事:旧签名({@code url, headers JSON})向后兼容,以及新增的可选参数
 * {@code profile / assertions / extract / responseMode}。用 WireMock 打真实假上游,
 * 经合成的 {@code _ad_hoc} system 走完整 16 步调用链 —— {@code LoomHttpService}
 * 自身零逻辑,它只是把参数装配成 {@code InvokeRequest} 交给 {@link HttpEngine}。
 *
 * <p>从源项目 {@code http-mcp} 的 {@code HttpServiceTest} 整体搬运(源侧
 * {@code GlobalConfig}/{@code StorageConfig} 在本仓对应 {@link HttpConfig}/{@link HttpStorage},
 * 装配改由 {@link HttpEngine#of} 一站式完成,故不再手工 new 各 Service)。
 */
class LoomHttpServiceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @TempDir
    Path tmp;

    private ProfileService profileService;
    private LoomHttpService httpService;
    private HttpEngine engine;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("profiles"));
        Files.createDirectories(tmp.resolve("systems"));
        Files.createDirectories(tmp.resolve("responses"));

        HttpConfig config = new HttpConfig();
        config.getAllowedDomains().add("localhost");

        // profileService 与 engine 共用同一个存储根 —— 文件是唯一真源,
        // 因此这里 save 之后 engine 侧的 InvokeService 读得到。
        profileService = new ProfileService(new HttpStorage(tmp), config);
        httpService = new LoomHttpService(engine = HttpEngine.of(tmp.toString(), config));

        wm.resetAll();
    }

    /**
     * 存 profile 后必须让 engine 侧重新加载:{@code ProfileService} 自带内存 cache
     * (构造时 {@code reload()} 一次),两个实例不共享 —— 只 save 不 reload 会
     * {@code ProfileNotFound}。这正是 jar 模式 FileWatcher 变更后调 reload 的原因。
     */
    private void saveProfile(Profile p) {
        profileService.save(p);
        engine.reload();
    }

    private static Map<String, Object> assertion(String type, Map<String, Object> fields) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", type);
        a.putAll(fields);
        return a;
    }

    private void assertNoError(JsonNode root) {
        if (root.has("error") && root.get("error") != null && !root.get("error").isNull()) {
            throw new AssertionError("Expected no error, got: " + root.get("error"));
        }
    }

    // ------------------------------------------------------------------
    // Backward-compatibility: old (url, headers) signature still works
    // ------------------------------------------------------------------

    @Test
    void httpGet_oldApiBackwardCompatible() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/users/1"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":1,\"name\":\"Alice\"}")));

        // Old call shape: url + headers JSON string + all new params = null
        String result = httpService.httpGet(
            wm.baseUrl() + "/users/1",
            "{\"X-Custom\":\"foo\"}",
            null, null, null, null);

        JsonNode root = mapper.readTree(result);
        assertNoError(root);
        assertThat(root.get("statusCode").asInt()).isEqualTo(200);
        assertThat(root.get("body").asText()).contains("Alice");
        // Custom header from old API was forwarded
        wm.verify(getRequestedFor(urlEqualTo("/users/1"))
            .withHeader("X-Custom", equalTo("foo")));
    }

    @Test
    void httpPost_oldApiBackwardCompatible() throws Exception {
        wm.stubFor(post(urlPathEqualTo("/todos"))
            .willReturn(aResponse().withStatus(201).withBody("{\"id\":42}")));

        String result = httpService.httpPost(
            wm.baseUrl() + "/todos",
            "{\"title\":\"foo\"}",
            "{\"Authorization\":\"Bearer oldtoken\"}",
            null, null, null, null);

        JsonNode root = mapper.readTree(result);
        assertNoError(root);
        assertThat(root.get("statusCode").asInt()).isEqualTo(201);
        wm.verify(postRequestedFor(urlEqualTo("/todos"))
            .withHeader("Authorization", equalTo("Bearer oldtoken"))
            .withRequestBody(equalTo("{\"title\":\"foo\"}")));
    }

    @Test
    void httpPut_oldApiBackwardCompatible() throws Exception {
        wm.stubFor(put(urlPathEqualTo("/users/1"))
            .willReturn(aResponse().withStatus(200).withBody("{\"updated\":true}")));

        String result = httpService.httpPut(
            wm.baseUrl() + "/users/1",
            "{\"name\":\"new\"}",
            null,
            null, null, null, null);

        JsonNode root = mapper.readTree(result);
        assertNoError(root);
        assertThat(root.get("statusCode").asInt()).isEqualTo(200);
    }

    @Test
    void httpDelete_oldApiBackwardCompatible() throws Exception {
        wm.stubFor(delete(urlPathEqualTo("/users/1"))
            .willReturn(aResponse().withStatus(204)));

        String result = httpService.httpDelete(
            wm.baseUrl() + "/users/1",
            null,
            null, null, null, null);

        JsonNode root = mapper.readTree(result);
        assertNoError(root);
        assertThat(root.get("statusCode").asInt()).isEqualTo(204);
    }

    @Test
    void httpGet_nullHeadersBackwardCompatible() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/ping"))
            .willReturn(aResponse().withStatus(200).withBody("pong")));

        String result = httpService.httpGet(wm.baseUrl() + "/ping", null,
            null, null, null, null);

        JsonNode root = mapper.readTree(result);
        assertNoError(root);
        assertThat(root.get("statusCode").asInt()).isEqualTo(200);
        assertThat(root.get("body").asText()).isEqualTo("pong");
    }

    // ------------------------------------------------------------------
    // New optional parameters
    // ------------------------------------------------------------------

    @Test
    void httpGet_withProfileInjectsAuthHeader() throws Exception {
        // WireMock asserts the bearer token issued by the profile is present.
        wm.stubFor(get(urlPathEqualTo("/secure"))
            .withHeader("Authorization", equalTo("Bearer test-token-123"))
            .willReturn(aResponse().withStatus(200).withBody("{}")));

        Profile p = new Profile();
        p.setName("staging");
        p.setAllowedDomains(Set.of("localhost"));
        Profile.Auth auth = new Profile.Auth();
        auth.setType("bearer");
        auth.setToken("test-token-123");
        p.setAuth(auth);
        saveProfile(p);

        String result = httpService.httpGet(
            wm.baseUrl() + "/secure",
            null,
            "staging",     // profile
            null, null, null);

        JsonNode root = mapper.readTree(result);
        assertNoError(root);
        assertThat(root.get("statusCode").asInt()).isEqualTo(200);
        wm.verify(getRequestedFor(urlEqualTo("/secure"))
            .withHeader("Authorization", equalTo("Bearer test-token-123")));
    }

    @Test
    void httpGet_assertionsPassAndFail() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/users/1"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":1,\"name\":\"Alice\"}")));

        List<Map<String, Object>> assertions = List.of(
            assertion("statusEquals", Map.of("value", 200)),
            assertion("bodyJsonPathEquals", Map.of("path", "$.name", "value", "Alice"))
        );

        String result = httpService.httpGet(
            wm.baseUrl() + "/users/1",
            null,
            null, assertions, null, null);

        JsonNode root = mapper.readTree(result);
        assertNoError(root);
        assertThat(root.has("assertionResult")).isTrue();
        assertThat(root.get("assertionResult").get("passed").asBoolean()).isTrue();
        assertThat(root.get("assertionResult").get("failures")).isEmpty();
    }

    @Test
    void httpGet_assertionFailureReported() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/missing"))
            .willReturn(aResponse().withStatus(404).withBody("not here")));

        List<Map<String, Object>> assertions = List.of(
            assertion("statusEquals", Map.of("value", 200))
        );

        String result = httpService.httpGet(
            wm.baseUrl() + "/missing",
            null,
            null, assertions, null, null);

        JsonNode root = mapper.readTree(result);
        assertThat(root.get("statusCode").asInt()).isEqualTo(404);
        assertThat(root.get("assertionResult").get("passed").asBoolean()).isFalse();
        assertThat(root.get("assertionResult").get("failures")).isNotEmpty();
    }

    @Test
    void httpGet_extractPullsJsonPath() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/users/1"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"id\":1,\"name\":\"Alice\",\"email\":\"a@example.com\"}")));

        Map<String, String> extract = Map.of("name", "$.name", "email", "$.email");

        String result = httpService.httpGet(
            wm.baseUrl() + "/users/1",
            null,
            null, null, extract, null);

        JsonNode root = mapper.readTree(result);
        assertNoError(root);
        JsonNode ex = root.get("extracted");
        assertThat(ex).isNotNull();
        assertThat(ex.get("name").asText()).isEqualTo("Alice");
        assertThat(ex.get("email").asText()).isEqualTo("a@example.com");
    }

    @Test
    void httpGet_responseModeSummaryOmitsBody() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/big"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"a\":1,\"b\":2,\"c\":3}")));

        String result = httpService.httpGet(
            wm.baseUrl() + "/big",
            null,
            null, null, null, "summary");

        JsonNode root = mapper.readTree(result);
        assertNoError(root);
        assertThat(root.get("statusCode").asInt()).isEqualTo(200);
        // Summary mode: body is null, headers are empty map
        assertThat(root.get("body").isNull()).isTrue();
        assertThat(root.get("headers").size()).isZero();
    }

    @Test
    void httpGet_queryStringParamsBecomeRequestParams() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/search"))
            .withQueryParam("q", equalTo("hello"))
            .willReturn(aResponse().withStatus(200).withBody("[]")));

        // URL has ?q=hello
        String result = httpService.httpGet(
            wm.baseUrl() + "/search?q=hello",
            null,
            null, null, null, null);

        JsonNode root = mapper.readTree(result);
        assertNoError(root);
        assertThat(root.get("statusCode").asInt()).isEqualTo(200);
        wm.verify(getRequestedFor(urlEqualTo("/search?q=hello")));
    }

    @Test
    void httpPost_newParamsCombinedWithBody() throws Exception {
        wm.stubFor(post(urlPathEqualTo("/echo"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"title\":\"foo\"}")));

        String result = httpService.httpPost(
            wm.baseUrl() + "/echo",
            "{\"title\":\"foo\"}",
            "{\"X-Trace\":\"abc\"}",
            null,
            null,
            null,
            null);

        JsonNode root = mapper.readTree(result);
        assertNoError(root);
        assertThat(root.get("statusCode").asInt()).isEqualTo(200);
        wm.verify(postRequestedFor(urlEqualTo("/echo"))
            .withHeader("X-Trace", equalTo("abc"))
            .withRequestBody(equalTo("{\"title\":\"foo\"}")));
    }

    @Test
    void httpGet_invalidHeadersJsonSurfacesError() throws Exception {
        // Malformed headers JSON should not throw — error goes into InvokeResponse.error
        String result = httpService.httpGet(
            wm.baseUrl() + "/x",
            "{not valid json",
            null, null, null, null);

        JsonNode root = mapper.readTree(result);
        // We don't make any wiremock request — error should be reported
        assertThat(root.has("error")).isTrue();
    }

    @Test
    void httpGet_newParamsWorkForSummary() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/ok"))
            .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        // All new params at once
        String result = httpService.httpGet(
            wm.baseUrl() + "/ok",
            null,
            null,
            List.of(assertion("statusEquals", Map.of("value", 200))),
            Map.of("ok", "$.ok"),
            "summary");

        JsonNode root = mapper.readTree(result);
        assertNoError(root);
        assertThat(root.get("statusCode").asInt()).isEqualTo(200);
        assertThat(root.get("body").isNull()).isTrue();
        assertThat(root.get("assertionResult").get("passed").asBoolean()).isTrue();
    }

    @Test
    void httpDelete_withProfileAndHeaders() throws Exception {
        wm.stubFor(delete(urlPathEqualTo("/x"))
            .willReturn(aResponse().withStatus(200)));

        String result = httpService.httpDelete(
            wm.baseUrl() + "/x",
            "{\"X-Tenant\":\"acme\"}",
            null,
            null, null, null);

        JsonNode root = mapper.readTree(result);
        assertNoError(root);
        assertThat(root.get("statusCode").asInt()).isEqualTo(200);
        wm.verify(deleteRequestedFor(urlEqualTo("/x"))
            .withHeader("X-Tenant", equalTo("acme")));
    }

    @Test
    void httpGet_profileAlsoMergesCustomHeaders() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/whoami"))
            .withHeader("Authorization", equalTo("Bearer t"))
            .withHeader("X-Tenant", equalTo("acme"))
            .willReturn(aResponse().withStatus(200).withBody("{}")));

        Profile p = new Profile();
        p.setName("p1");
        p.setAllowedDomains(Set.of("localhost"));
        Profile.Auth auth = new Profile.Auth();
        auth.setType("bearer");
        auth.setToken("t");
        p.setAuth(auth);
        p.setCustomHeaders(Map.of("X-Tenant", "acme"));
        saveProfile(p);

        String result = httpService.httpGet(
            wm.baseUrl() + "/whoami",
            null,
            "p1", null, null, null);

        JsonNode root = mapper.readTree(result);
        assertNoError(root);
        wm.verify(getRequestedFor(urlEqualTo("/whoami"))
            .withHeader("Authorization", equalTo("Bearer t"))
            .withHeader("X-Tenant", equalTo("acme")));
    }

    // ------------------------------------------------------------------
    // Concurrency regression: no cross-talk between concurrent ad-hoc calls
    // ------------------------------------------------------------------

    /**
     * 并发 ad-hoc 调用必须各自拿到<b>自己的</b>目标与凭据。
     *
     * <p><b>锁的是什么 bug</b>:实现早期把 baseUrl / authProfile 写进<b>共享的</b>
     * {@code _ad_hoc} system 对象,而 {@code InvokeService} 是按 name 重新解析该 system
     * 再读这两个字段的 —— 读发生在调用方的写锁之外。线程 A 设完释放锁后,线程 B 覆写,
     * A 的请求就会带着 B 的凭据发往 B 的主机。
     *
     * <p>现在的实现把路由参数随 {@code InvokeRequest} 传递,共享对象不再被修改,
     * 因此这个隔离是<b>结构性</b>成立的,不依赖时序。这里仍用 {@link CyclicBarrier}
     * 让两个线程在同一时刻进入 ad-hoc 分支,保证测试在旧实现下会稳定地抓到串台。
     */
    @Test
    void concurrentAdHocCallsDoNotCrossTalk() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/lane-a"))
            .willReturn(aResponse().withStatus(200).withBody("{\"lane\":\"a\"}")));
        wm.stubFor(get(urlPathEqualTo("/lane-b"))
            .willReturn(aResponse().withStatus(200).withBody("{\"lane\":\"b\"}")));

        Profile pa = new Profile();
        pa.setName("lane-a-profile");
        pa.setAllowedDomains(Set.of("localhost"));
        Profile.Auth authA = new Profile.Auth();
        authA.setType("bearer");
        authA.setToken("token-a");
        pa.setAuth(authA);
        saveProfile(pa);

        Profile pb = new Profile();
        pb.setName("lane-b-profile");
        pb.setAllowedDomains(Set.of("localhost"));
        Profile.Auth authB = new Profile.Auth();
        authB.setType("bearer");
        authB.setToken("token-b");
        pb.setAuth(authB);
        saveProfile(pb);

        int rounds = 20;
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<String> aBody = new AtomicReference<>();
        AtomicReference<String> bBody = new AtomicReference<>();
        AtomicReference<String> crossTalk = new AtomicReference<>();

        Runnable laneA = () -> {
            for (int i = 0; i < rounds; i++) {
                try {
                    barrier.await();
                    JsonNode root = mapper.readTree(httpService.httpGet(
                        wm.baseUrl() + "/lane-a", null, "lane-a-profile", null, null, null));
                    assertNoError(root);
                    String lane = root.get("body") == null ? null : root.get("body").asText();
                    aBody.set(lane);
                    if (lane != null && lane.contains("\"lane\":\"b\"")) {
                        crossTalk.compareAndSet(null, "lane-a 收到了 lane-b 的响应体:" + lane);
                    }
                } catch (Exception ex) {
                    crossTalk.compareAndSet(null, "lane-a 抛异常:" + ex);
                }
            }
        };
        Runnable laneB = () -> {
            for (int i = 0; i < rounds; i++) {
                try {
                    barrier.await();
                    JsonNode root = mapper.readTree(httpService.httpGet(
                        wm.baseUrl() + "/lane-b", null, "lane-b-profile", null, null, null));
                    assertNoError(root);
                    String lane = root.get("body") == null ? null : root.get("body").asText();
                    bBody.set(lane);
                    if (lane != null && lane.contains("\"lane\":\"a\"")) {
                        crossTalk.compareAndSet(null, "lane-b 收到了 lane-a 的响应体:" + lane);
                    }
                } catch (Exception ex) {
                    crossTalk.compareAndSet(null, "lane-b 抛异常:" + ex);
                }
            }
        };

        Thread ta = new Thread(laneA, "lane-a");
        Thread tb = new Thread(laneB, "lane-b");
        ta.start();
        tb.start();
        ta.join(60_000);
        tb.join(60_000);

        assertThat(crossTalk.get()).as("并发 ad-hoc 调用串台").isNull();
        assertThat(aBody.get()).contains("\"lane\":\"a\"");
        assertThat(bBody.get()).contains("\"lane\":\"b\"");

        // 凭据隔离:每个 lane 的请求只带自己的 token(出现对方的 token 即为串台)
        wm.verify(getRequestedFor(urlEqualTo("/lane-a"))
            .withHeader("Authorization", equalTo("Bearer token-a")));
        wm.verify(getRequestedFor(urlEqualTo("/lane-b"))
            .withHeader("Authorization", equalTo("Bearer token-b")));
        wm.verify(0, getRequestedFor(urlEqualTo("/lane-a"))
            .withHeader("Authorization", equalTo("Bearer token-b")));
        wm.verify(0, getRequestedFor(urlEqualTo("/lane-b"))
            .withHeader("Authorization", equalTo("Bearer token-a")));
    }

    /**
     * reload 后 ad-hoc 调用仍可用 —— 且不再依赖"活实例"这种易碎前提。
     *
     * <p>路由参数随请求传递后,引擎读的是请求自己的 baseUrl,所以
     * {@code _ad_hoc} 是否被 reload 换成了新对象都不影响路由结果。
     */
    @Test
    void adHocCallWorksAfterEngineReload() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/after-reload"))
            .willReturn(aResponse().withStatus(200).withBody("ok-after-reload")));

        String before = httpService.httpGet(
            wm.baseUrl() + "/after-reload", null, null, null, null, null);
        assertThat(mapper.readTree(before).get("body").asText()).isEqualTo("ok-after-reload");

        engine.reload();

        String after = httpService.httpGet(
            wm.baseUrl() + "/after-reload", null, null, null, null, null);
        JsonNode root = mapper.readTree(after);
        assertNoError(root);
        assertThat(root.get("body").asText()).isEqualTo("ok-after-reload");
    }
}