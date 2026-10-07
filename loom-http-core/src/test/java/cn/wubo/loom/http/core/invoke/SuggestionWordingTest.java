package cn.wubo.loom.http.core.invoke;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpStorage;
import cn.wubo.loom.http.core.history.HistoryService;
import cn.wubo.loom.http.core.profile.ProfileService;
import cn.wubo.loom.http.core.endpoint.Endpoint;
import cn.wubo.loom.http.core.system.OpenApiCache;
import cn.wubo.loom.http.core.system.System;
import cn.wubo.loom.http.core.system.SystemService;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺陷回归锁:两条 suggestion 措辞对 LLM 的可操作性。
 *
 * <p><b>缺陷来源</b>(2026-10-03 Chrome 端到端实测 embedded HTTP 工具时撞到):
 * <ol>
 *   <li><b>DomainNotAllowed 的 suggestion 指向死配置</b> ——
 *       {@code InvokeService:193} 说 "Add 'host' to config.json or profile",
 *       但 {@code HttpStorage.configFile()} 全仓<b>只有测试断言过路径,
 *       生产代码零调用</b> ⇒ {@code config.json} 根本不被读取。
 *       实测 LLM 拿到这条提示后,回复"建议由平台侧在 config.json 中加入域名",
 *       然后卡在那里 —— 它能做的只有让用户去改一个读不到的文件。
 *       profile 那一半是<b>真的</b>(只能收紧,不能打开闸),但把两半并列会误导。</li>
 *   <li><b>unknownEndpoint 的 suggestion 读起来像失败</b> ——
 *       {@code InvokeService:660} 说 "GET /posts/1 is not registered in system 'x'.
 *       Add it via addEndpoint?"。实测该 suggestion 是在<b>请求已成功执行之后</b>
 *       才附加的(见 :338-341 与 :374,当时 statusCode 已设、out 无 error),
 *       措辞却像在说"你没注册所以失败了",LLM 因此在成功回复里加了一句
 *       "提示:… 当前没在 jsonplaceholder 系统里注册",把成功说成了将就。</li>
 * </ol>
 *
 * <p><b>这两条 suggestion 的受众是 LLM,不是人</b>。它们唯一的用途是让模型知道
 * "下一步能做什么",因此措辞必须区分"失败原因"与"可选的后续动作"。
 */
@DisplayName("suggestion 措辞对 LLM 的可操作性")
class SuggestionWordingTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @TempDir
    Path tmp;

    private HttpConfig global;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("profiles"));
        Files.createDirectories(tmp.resolve("systems"));
        Files.createDirectories(tmp.resolve("responses"));
        global = new HttpConfig();
        global.setMaxResponseSizeBytes(1024 * 1024L);
    }

    private InvokeService serviceWith(String systemName, List<String> allowedDomains) {
        global.setAllowedDomains(new java.util.LinkedHashSet<>(allowedDomains));
        SystemService sys = new SystemService(new HttpStorage(tmp), global);
        System s = new System();
        s.setName(systemName);
        s.setBaseUrl(wm.baseUrl());
        sys.save(s);

        OpenApiCache cache = mock(OpenApiCache.class);
        when(cache.loadResult(any(System.class)))
            .thenReturn(new OpenApiCache.LoadResult(List.of(), false, "noSource"));
        return new InvokeService(sys, new ProfileService(new HttpStorage(tmp), global), global,
            new HttpStorage(tmp), new HistoryService(new HttpStorage(tmp), global), cache);
    }

    private InvokeRequest request(String systemName, String path) {
        InvokeRequest req = new InvokeRequest();
        req.setSystem(systemName);
        req.setMethod("GET");
        req.setPath(path);
        req.setHeaders(Map.of());
        req.setResponseMode("summary");
        return req;
    }

    // ==================== 缺陷 1: DomainNotAllowed 的 suggestion ====================

    @Test
    @DisplayName("DomainNotAllowed 的 suggestion 不得指向 config.json(生产代码从不读它)")
    void domainNotAllowedMustNotPointAtDeadConfigFile() {
        // 白名单只放行别的 host ⇒ 本次请求被拒
        InvokeService svc = serviceWith("svc", List.of("example.invalid"));
        InvokeResponse out = svc.invoke(request("svc", "/probe"));

        assertThat(out.getError()).isNotNull();
        assertThat(out.getError().get("error")).isEqualTo("DomainNotAllowed");
        String suggestion = String.valueOf(out.getError().get("suggestion"));
        assertThat(suggestion)
            .as("config.json 在 embedded 与独立两条路径下都没有任何读取方"
                + "(HttpStorage.configFile() 全仓只有测试断言过路径)——"
                + "把它写进 suggestion 会让 LLM 建议用户去改一个读不到的文件,"
                + "实测 LLM 就是这么卡住的")
            .doesNotContain("config.json");
    }

    @Test
    @DisplayName("DomainNotAllowed 的 suggestion 必须指向真正生效的开关(yml 属性名)")
    void domainNotAllowedPointsAtRealSwitch() {
        InvokeService svc = serviceWith("svc", List.of("example.invalid"));
        InvokeResponse out = svc.invoke(request("svc", "/probe"));

        String suggestion = String.valueOf(out.getError().get("suggestion"));
        // 部署级真开关:internal 模式硬编码 fail-closed,只有 yml 属性能打开
        assertThat(suggestion).contains("spring.ai.loom.agent.http.allowed-domains");
        // profile 半边保留(profile 确实读、且只能收紧)但必须说明它是收紧语义
        assertThat(suggestion).contains("profile").contains("cannot widen");
    }

    @Test
    @DisplayName("端口提示仍在:host:PORT 形式对具体端口有用")
    void domainNotAllowedKeepsPortHint() {
        InvokeService svc = serviceWith("svc", List.of("example.invalid"));
        InvokeResponse out = svc.invoke(request("svc", "/probe"));

        String suggestion = String.valueOf(out.getError().get("suggestion"));
        assertThat(suggestion).contains(":" + wm.getPort())
            .as("host:PORT 的 pin 提示是有用的 —— 纯 host pattern 是 port-agnostic 的");
    }

    // ==================== 缺陷 2: unknownEndpoint 的 suggestion ====================

    @Test
    @DisplayName("unknownEndpoint suggestion 不得用 'is not registered' 这类失败措辞")
    void unknownEndpointMustNotSoundLikeFailure() {
        wm.stubFor(get(urlEqualTo("/posts/1")).willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"title\":\"t\"}")));

        InvokeService svc = serviceWith("svc", List.of("localhost"));
        InvokeResponse out = svc.invoke(request("svc", "/posts/1"));

        // 前置:调用本身必须成功 —— 这是本缺陷的前提,若失败则本用例无意义
        assertThat(out.getError()).as("本用例的前提是请求成功").isNull();
        assertThat(out.getStatusCode()).isEqualTo(200);

        assertThat(out.getSuggestion()).isNotNull();
        String message = String.valueOf(out.getSuggestion().get("message"));
        assertThat(message)
            .as("suggestion 是在请求成功之后附加的(InvokeService:338-341 → :374,"
                + "此时 statusCode 已设、out 无 error),措辞却像在说调用失败了,"
                + "实测 LLM 因此在成功回复里加了一句'当前没注册',把成功说成将就")
            .doesNotContain("is not registered");
        assertThat(message).contains("executed successfully");
    }

    @Test
    @DisplayName("unknownEndpoint suggestion 保留 proposedDefinition(可操作的部分不动)")
    void unknownEndpointKeepsProposedDefinition() {
        wm.stubFor(get(urlEqualTo("/posts/1")).willReturn(aResponse()
            .withStatus(200).withHeader("Content-Type", "application/json")
            .withBody("{\"title\":\"t\"}")));

        InvokeService svc = serviceWith("svc", List.of("localhost"));
        InvokeResponse out = svc.invoke(request("svc", "/posts/1"));

        var suggestion = out.getSuggestion();
        assertThat(suggestion.get("kind")).isEqualTo("unknownEndpoint");
        assertThat(suggestion.get("proposedDefinition"))
            .as("proposedDefinition 是这条 suggestion 真正有用的部分 —— LLM 可以照它 addEndpoint")
            .isNotNull();
    }

    @Test
    @DisplayName("已注册端点:不产生 unknownEndpoint suggestion(反证上面的措辞改动不影响正常路径)")
    void registeredEndpointHasNoSuggestion() {
        wm.stubFor(get(urlEqualTo("/posts/1")).willReturn(aResponse()
            .withStatus(200).withHeader("Content-Type", "application/json")
            .withBody("{\"title\":\"t\"}")));

        SystemService sys = new SystemService(new HttpStorage(tmp), global);
        System s = new System();
        s.setName("full");
        s.setBaseUrl(wm.baseUrl());
        var ep = new Endpoint();
        ep.setMethod("GET");
        ep.setPath("/posts/1");
        s.setEndpoints(new java.util.ArrayList<>(List.of(ep)));
        sys.save(s);

        OpenApiCache cache = mock(OpenApiCache.class);
        when(cache.loadResult(any(System.class)))
            .thenReturn(new OpenApiCache.LoadResult(List.of(), false, "noSource"));
        global.setAllowedDomains(new java.util.LinkedHashSet<>(List.of("localhost")));
        InvokeService svc = new InvokeService(sys,
            new ProfileService(new HttpStorage(tmp), global), global,
            new HttpStorage(tmp), new HistoryService(new HttpStorage(tmp), global), cache);

        InvokeResponse out = svc.invoke(request("full", "/posts/1"));
        assertThat(out.getStatusCode()).isEqualTo(200);
        assertThat(out.getSuggestion())
            .as("端点已注册 ⇒ 不该有任何 unknownEndpoint 提示")
            .isNull();
    }
}
