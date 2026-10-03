package cn.wubo.spring.ai.loom.agent.tool.http;

import cn.wubo.loom.file.core.LoomPaths;
import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.profile.Profile;
import cn.wubo.loom.http.core.system.System;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具 → 上游全链路的确定性覆盖（spec §6.2）。
 *
 * <p><b>为什么不打 LLM</b>：模型可能不调用工具、或调用方式与预期不同，那会让
 * 本该确定的安全断言变成 flaky。故直接调工具方法 + 手工构造 ToolContext。
 *
 * <p><b>fail-closed 的真正证明</b>在 {@link #blankWhitelistBlocksBeforeSendingRequest()}：
 * Task 6 的同名用例在 system 未注册时就返回了，根本没走到白名单判定。
 */
@DisplayName("HTTP 工具 → 上游全链路（WireMock 确定性，不经 LLM）")
class HttpToolInvokeIT {

    private static final String SECRET = "sk-live-DO-NOT-LEAK-12345";
    private static final List<String> ALLOWED = List.of("localhost", "127.0.0.1");

    private WireMockServer wm;
    private String usersBase;

    @BeforeEach
    void startWireMock() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        wm.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"pong\":true,\"id\":\"abc-123\"}")));
    }

    @AfterEach
    void stopWireMock() {
        if (wm != null) wm.stop();
    }

    private void initBase(Path tmp) {
        usersBase = tmp.toString();
    }

    private static ToolContext ctx(String username) {
        return new ToolContext(Map.of("username", username));
    }

    private static LoomAgentProperties.HttpProperty props(List<String> allowedDomains) {
        LoomAgentProperties.HttpProperty p = new LoomAgentProperties.HttpProperty();
        p.setAllowedDomains(allowedDomains);
        return p;
    }

    /** 与 DefaultHttpTool.engineFor 逐行同构 —— 白名单与 fail-closed 开关必须一致。 */
    private HttpEngine engineFor(String user, List<String> allowedDomains) {
        HttpConfig cfg = new HttpConfig();
        cfg.setFailClosed(true);
        cfg.setAllowedDomains(new LinkedHashSet<>(allowedDomains));
        return new HttpEngine(LoomPaths.userHttpDir(usersBase, user), cfg);
    }

    private static System buildSystem(String name, String baseUrl, String authProfile) {
        System s = new System();
        s.setName(name);
        s.setBaseUrl(baseUrl);
        s.setAuthProfile(authProfile);
        return s;
    }

    @Test
    @DisplayName("invokeEndpoint 真的打到上游并拿到响应")
    void reachesUpstream(@TempDir Path tmp) {
        initBase(tmp);
        engineFor("alice", ALLOWED).registerSystem(buildSystem("svc", wm.baseUrl(), null));
        DefaultHttpTool tool = new DefaultHttpTool(usersBase, props(ALLOWED));

        String out = tool.invokeEndpoint("svc", "GET", "/ping",
                null, null, null, null, null, null, null, ctx("alice"));

        wm.verify(1, getRequestedFor(urlEqualTo("/ping")));
        assertThat(out).contains("\"statusCode\":200").contains("pong");
    }

    @Test
    @DisplayName("profile 的 token 随请求头发往上游，且不回显给调用方")
    void authHeaderReachesUpstream(@TempDir Path tmp) {
        initBase(tmp);
        HttpEngine engine = engineFor("alice", ALLOWED);
        Profile p = new Profile();
        p.setName("prod");
        p.getAuth().setType("bearer");
        p.getAuth().setToken(SECRET);
        engine.addProfile(p);
        engine.registerSystem(buildSystem("authed", wm.baseUrl(), "prod"));

        DefaultHttpTool tool = new DefaultHttpTool(usersBase, props(ALLOWED));
        String out = tool.invokeEndpoint("authed", "GET", "/ping",
                null, null, null, null, null, null, null, ctx("alice"));

        wm.verify(1, getRequestedFor(urlEqualTo("/ping"))
                .withHeader("Authorization", equalTo("Bearer " + SECRET)));
        assertThat(out).contains("\"statusCode\":200");
        assertThat(out).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("fail-closed：白名单拒绝时 WireMock 请求数为 0")
    void blankWhitelistBlocksBeforeSendingRequest(@TempDir Path tmp) {
        initBase(tmp);
        // 先用有白名单的引擎把 system 注册进 alice 的目录（注册不需要联网）
        engineFor("alice", ALLOWED).registerSystem(buildSystem("svc", wm.baseUrl(), null));

        // 再用一个"空白名单"的工具去打 —— fail-closed 应在发请求前拦住
        DefaultHttpTool closedTool = new DefaultHttpTool(usersBase, props(List.of()));
        String out = closedTool.invokeEndpoint("svc", "GET", "/ping",
                null, null, null, null, null, null, null, ctx("alice"));

        wm.verify(0, getRequestedFor(urlEqualTo("/ping")));
        assertThat(out).contains("DomainNotAllowed");
    }

    @Test
    @DisplayName("白名单不含上游 host 时同样拒绝，且零请求")
    void hostNotInWhitelistIsRejected(@TempDir Path tmp) {
        initBase(tmp);
        engineFor("alice", List.of("example.com")).registerSystem(buildSystem("svc", wm.baseUrl(), null));
        DefaultHttpTool tool = new DefaultHttpTool(usersBase, props(List.of("example.com")));

        String out = tool.invokeEndpoint("svc", "GET", "/ping",
                null, null, null, null, null, null, null, ctx("alice"));

        wm.verify(0, getRequestedFor(urlEqualTo("/ping")));
        assertThat(out).contains("DomainNotAllowed");
    }
}
