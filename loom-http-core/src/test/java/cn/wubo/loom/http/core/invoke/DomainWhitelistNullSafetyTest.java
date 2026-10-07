package cn.wubo.loom.http.core.invoke;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpStorage;
import cn.wubo.loom.http.core.history.HistoryService;
import cn.wubo.loom.http.core.profile.ProfileService;
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
import java.util.Set;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 锁定 {@code InvokeService.effectiveDomainWhitelist} 的<b>全部六个分支</b>都不抛 NPE,
 * 特别是 fail-open 路径上那个曾经漏保护的一支。
 *
 * <p><b>缺陷来源</b>(Task 14 收尾,用真实 MCP 客户端逐个调用时暴露):
 * {@code loom-http-mcp} 的 {@code http_get} 等四个工具全部返回
 * {@code {"error":"NullPointerException","message":"Cannot invoke \"java.util.Collection.size()\" because \"c\" is null"}}。
 *
 * <p><b>根因</b>:{@code effectiveDomainWhitelist} 六个分支里<b>只有一个</b>漏了 null 保护 ——
 * {@code :453} 把 {@code profileAllowed} 显式赋成 {@code null}(profile 为 null 时),
 * 而 {@code :459} 在 {@code gEmpty} 分支无保护地把它传给 {@code new DomainWhitelist(...)}
 * → {@code new LinkedHashSet<>(patterns)},JDK 形参名恰好是 {@code c}。
 * 其余五支都被 {@code gEmpty} / {@code pEmpty} 挡住。
 *
 * <p><b>为什么此前 20+ 个测试全绿</b>:<b>测试盲区</b>,不是测试通过。
 * {@code LoomHttpServiceTest.java:73} 与 {@code InvokeFailureTest.java:76} 都给全局白名单
 * 塞了 {@code "localhost"} → {@code gEmpty == false} → <b>永远走不到出问题的那一支</b>。
 * 生产配置相反:{@code loom-http-mcp/src/main/resources/application.yml} 是
 * {@code failClosed: false} 且 {@code HttpConfig.allowedDomains} 默认为空集
 * → {@code gEmpty == true} → 必炸。本类刻意<b>不设</b>全局白名单来复现真实配置。
 *
 * <p>断言<b>行为</b>而非实现细节:要么正常返回,要么返回结构化错误信封,
 * 绝不允许抛异常 —— 因为引擎侧 {@code HttpEngine.toJson} 会把异常折叠成 JSON,
 * 而 LLM 侧看到的是"工具调用失败"而非任何可诊断信息。
 */
class DomainWhitelistNullSafetyTest {

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
        // 刻意保持 allowedDomains 为空 —— 这是生产的真实默认(H2 部署未显式配白名单)。
        // 之前的测试全部在这里 add("localhost"),导致 gEmpty==false,掩盖了 :459 的崩溃。
        global.setMaxResponseSizeBytes(1024 * 1024L);
    }

    /** 用当前 global 配置装配一条 InvokeService,system 绑到 WireMock,不绑任何 profile。 */
    private InvokeService serviceWithoutProfile(String systemName) {
        SystemService sys = new SystemService(new HttpStorage(tmp), global);
        System s = new System();
        s.setName(systemName);
        s.setBaseUrl(wm.baseUrl());
        // 刻意不 setAuthProfile —— 对应 LLM 调 http_get(url) 不带 profile 的情形
        sys.save(s);

        var cache = mock(cn.wubo.loom.http.core.system.OpenApiCache.class);
        when(cache.loadResult(any(System.class)))
            .thenReturn(new cn.wubo.loom.http.core.system.OpenApiCache.LoadResult(List.of(), false, "noSource"));
        return new InvokeService(sys, new ProfileService(new HttpStorage(tmp), global), global,
                new HttpStorage(tmp), new HistoryService(new HttpStorage(tmp), global), cache);
    }

    private InvokeRequest request(String systemName) {
        InvokeRequest req = new InvokeRequest();
        req.setSystem(systemName);
        req.setMethod("GET");
        req.setPath("/probe");
        req.setHeaders(Map.of());
        req.setResponseMode("summary");
        return req;
    }

    @Test
    @DisplayName("fail-open + global 白名单空 + 无 profile —— 不得抛 NPE")
    void failOpenWithEmptyGlobalAndNoProfileDoesNotThrow() {
        InvokeService svc = serviceWithoutProfile("adhoc-noprofile");
        assertEquals(false, global.isFailClosed(), "本用例只针对 fail-open 分支");
        assertEquals(true, global.getAllowedDomains().isEmpty(), "前置:global 白名单必须为空");

        InvokeResponse resp = assertDoesNotThrow(
            () -> svc.invoke(request("adhoc-noprofile")),
            "global 白名单空 + profile 为 null 时,不得抛 NullPointerException");

        assertNotNull(resp);
        assertNull(resp.getStatusCode(), "白名单为空 = 没有 host 被授权,不应发出请求");
        assertNotNull(resp.getError(), "应返回结构化错误信封,而不是让异常逃逸");
        assertEquals("DomainNotAllowed", resp.getError().get("error"),
            "fail-open 下 global 空 = 退回 profile 白名单;profile 也没有 ⇒ 拒绝一切");
    }

    @Test
    @DisplayName("fail-closed + global 白名单空 + 无 profile —— 同样不得抛 NPE")
    void failClosedWithEmptyGlobalAndNoProfileDoesNotThrow() {
        global.setFailClosed(true);
        InvokeService svc = serviceWithoutProfile("adhoc-noprofile-fc");

        InvokeResponse resp = assertDoesNotThrow(
            () -> svc.invoke(request("adhoc-noprofile-fc")),
            "fail-closed 路径也必须对 null profile 安全");

        assertNotNull(resp.getError());
        assertEquals("DomainNotAllowed", resp.getError().get("error"));
    }

    @Test
    @DisplayName("fail-open + global 白名单空 + profile 存在但 allowedDomains 为 null")
    void profileWithNullAllowedDomainsDoesNotThrow() {
        // 另一条能到 :459 的路径:profile 非 null,但它的 allowedDomains 字段本身是 null
        // (JSON 里显式写了 "allowedDomains": null,或对象由代码 new 出来没 set)。
        var profileSvc = new ProfileService(new HttpStorage(tmp), global);
        cn.wubo.loom.http.core.profile.Profile p = new cn.wubo.loom.http.core.profile.Profile();
        p.setName("null-domains");
        p.setAllowedDomains(null);
        profileSvc.save(p);

        SystemService sys = new SystemService(new HttpStorage(tmp), global);
        System s = new System();
        s.setName("sys-null-domains");
        s.setBaseUrl(wm.baseUrl());
        s.setAuthProfile("null-domains");
        sys.save(s);

        var cache = mock(cn.wubo.loom.http.core.system.OpenApiCache.class);
        when(cache.loadResult(any(System.class)))
            .thenReturn(new cn.wubo.loom.http.core.system.OpenApiCache.LoadResult(List.of(), false, "noSource"));
        InvokeService svc = new InvokeService(sys, profileSvc, global, new HttpStorage(tmp),
                new HistoryService(new HttpStorage(tmp), global), cache);

        InvokeResponse resp = assertDoesNotThrow(
            () -> svc.invoke(request("sys-null-domains")),
            "profile.allowedDomains 为 null 时同样不得抛 NPE");

        assertNotNull(resp);
        assertEquals("DomainNotAllowed", resp.getError().get("error"));
    }

    @Test
    @DisplayName("六个分支全遍历:任何 global/profile 组合都不抛 NPE")
    void allBranchesAreNullSafe() {
        // 参数化穷举 —— effectiveDomainWhitelist 的分支由 (failClosed × gEmpty × pEmpty) 决定,
        // 这里把 8 种组合全跑一遍,任何一支抛异常都会让本用例红。
        boolean[] bools = {true, false};
        int caseNo = 0;
        for (boolean failClosed : bools) {
            for (boolean globalEmpty : bools) {
                for (int profileMode = 0; profileMode < 3; profileMode++) {
                    final boolean fc = failClosed, ge = globalEmpty;
                    final int pm = profileMode;
                    HttpConfig cfg = new HttpConfig();
                    cfg.setFailClosed(fc);
                    if (!ge) cfg.getAllowedDomains().add("localhost");
                    cfg.setMaxResponseSizeBytes(1024 * 1024L);

                    var profileSvc = new ProfileService(new HttpStorage(tmp.resolve("c" + caseNo)), cfg);
                    String sysAuth = null;
                    if (pm > 0) {
                        cn.wubo.loom.http.core.profile.Profile p =
                                new cn.wubo.loom.http.core.profile.Profile();
                        p.setName("p");
                        // pm==1 ⇒ allowedDomains 为 null;pm==2 ⇒ 空集合
                        p.setAllowedDomains(pm == 1 ? null : Set.of());
                        profileSvc.save(p);
                        sysAuth = "p";
                    }
                    var storage = new HttpStorage(tmp.resolve("c" + caseNo));
                    SystemService sys = new SystemService(storage, cfg);
                    System s = new System();
                    s.setName("s");
                    s.setBaseUrl(wm.baseUrl());
                    s.setAuthProfile(sysAuth);
                    sys.save(s);

                    var cache = mock(cn.wubo.loom.http.core.system.OpenApiCache.class);
                    when(cache.loadResult(any(System.class)))
                        .thenReturn(new cn.wubo.loom.http.core.system.OpenApiCache.LoadResult(
                                List.of(), false, "noSource"));
                    InvokeService svc = new InvokeService(sys, profileSvc, cfg, storage,
                            new HistoryService(storage, cfg), cache);

                    InvokeRequest req = new InvokeRequest();
                    req.setSystem("s");
                    req.setMethod("GET");
                    req.setPath("/probe");
                    req.setHeaders(Map.of());
                    req.setResponseMode("summary");

                    final String label = "failClosed=" + fc + " globalEmpty=" + ge + " profileMode=" + pm;
                    assertDoesNotThrow(() -> svc.invoke(req), "组合 " + label + " 抛异常了");
                    caseNo++;
                }
            }
        }
        assertEquals(12, caseNo, "failClosed(2) × globalEmpty(2) × profileMode(3) = 12 种组合");
    }
}