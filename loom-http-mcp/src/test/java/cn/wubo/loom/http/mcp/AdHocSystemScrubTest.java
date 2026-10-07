package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.system.System;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定"合成 system {@code _ad_hoc} 不得携带任何路由信息"这条边界。
 *
 * <p><b>缺陷来源</b>(deep audit {@code aud_6fZ4zETVSf},openai f1):D1 修复前,ad-hoc 调用把每次的
 * baseUrl / authProfile <b>写进共享的 {@code _ad_hoc} system 并落盘</b>。修复后代码不再写它,
 * 但**磁盘上可能已经存着旧版本留下的记录**,而
 * {@code firstNonBlank(req.getAuthProfileOverride(), sys.getAuthProfile())} 在 override 为空时
 * <b>回退到 sys 的值</b> ⇒ 一个"不带 profile 的 ad-hoc 调用"会继承上一个调用者的凭据并把它发出去。
 *
 * <p>更糟的是重名注册<b>不会报错</b>:{@code HttpEngine} 的所有写面都走
 * {@code toJson} 错误折叠,把任何异常变成 {@code {"error":...,"message":...}} 字符串返回 ——
 * {@code registerSystem} <b>从不抛异常</b>。所以任何"捕获重名异常再重试"的写法都是死代码
 * (首版修复正是这样写的,{@code AdHocSystemScrubTest} 首跑 2/4 红把它证伪),
 * 合成 system 会静默缺席、旧凭据会继续生效。修复因此改为<b>无条件</b> remove + register。
 *
 * <p>本测试模拟"升级安装":先写一份带路由字段的 {@code _ad_hoc.json}(等价旧版本留下的),
 * 再构造 {@link LoomHttpService},断言 (1) 构造不抛,(2) 落盘记录里的路由字段已被洗掉,
 * (3) 随后一个不带 profile 的调用不携带任何 Authorization 头。
 *
 * <p><b>已知边界</b>:升级模拟走的是 {@code engine.registerSystem(legacy)} 而非手写文件。
 * 落盘形态由 {@code SystemService} 用同一个 mapper 序列化同一个 {@code System} 对象,
 * 与旧版本写的逐字同构(旧版本就是同一个对象多设了两个字段,而这两个字段仍在 {@code System} 上)。
 * 断言也从"解析文件"改成"reload 后读引擎看到的 {@code System}" —— 锁的是最终危害面,不是文件内容。
 */
class AdHocSystemScrubTest {

    @TempDir
    Path tmp;

    private WireMockServer wm;
    private HttpEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("profiles"));
        Files.createDirectories(tmp.resolve("systems"));
        Files.createDirectories(tmp.resolve("responses"));

        HttpConfig config = new HttpConfig();
        config.getAllowedDomains().add("localhost");

        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        wm.stubFor(get(urlEqualTo("/probe")).willReturn(aResponse().withStatus(200).withBody("ok")));

        engine = HttpEngine.of(tmp.toString(), config);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    /** 旧版本留下的记录:带着上一次调用的主机与凭据。 */
    private void writeLegacyAdHocRecord() {
        System legacy = new System();
        legacy.setName(LoomHttpService.AD_HOC_SYSTEM_NAME);
        legacy.setDescription("Synthetic system for unbound ad-hoc httpXxx calls");
        legacy.setBaseUrl(wm.baseUrl());          // 上一次调用留下的目标主机
        legacy.setAuthProfile("ghost-profile");   // 上一次调用留下的凭据引用
        engine.registerSystem(legacy);
    }

    @Test
    @DisplayName("磁盘上已有 _ad_hoc 时构造不抛 —— 否则 MCP server 在重启后起不来")
    void constructionSucceedsWhenAdHocRecordAlreadyExists() {
        writeLegacyAdHocRecord();

        assertDoesNotThrow(() -> new LoomHttpService(engine),
                "存在旧版遗留的 _ad_hoc.json 时必须能启动(remove + 重新注册)");
    }

    @Test
    @DisplayName("构造后落盘的 _ad_hoc 不再携带任何路由字段")
    void adHocRecordIsScrubbedOfRoutingFields() {
        writeLegacyAdHocRecord();
        new LoomHttpService(engine);
        engine.reload();

        System onDisk = engine.getSystem(LoomHttpService.AD_HOC_SYSTEM_NAME);
        assertNull(onDisk.getBaseUrl(),
                "旧版落盘的目标主机必须被洗掉,否则无 profile 的调用会回退到它");
        assertNull(onDisk.getAuthProfile(),
                "旧版落盘的凭据引用必须被洗掉,否则无 profile 的调用会把别人的凭据发出去");
    }

    @Test
    @DisplayName("不带 profile 的 ad-hoc 调用不携带任何 Authorization 头")
    void callWithoutProfileCarriesNoAuthorizationHeader() {
        writeLegacyAdHocRecord();
        LoomHttpService svc = new LoomHttpService(engine);

        String out = svc.httpGet(wm.baseUrl() + "/probe", null, null, null, null, "summary");

        assertTrue(out.contains("\"statusCode\":200"), "调用本身应成功,实际=" + out);
        wm.verify(0, getRequestedFor(urlEqualTo("/probe"))
                .withHeader("Authorization", matching(".+")));
    }

    @Test
    @DisplayName("全新安装路径不受清洗逻辑影响 —— 正常 ad-hoc 调用照常工作")
    void freshInstallStillWorks() {
        LoomHttpService svc = new LoomHttpService(engine);

        String out = svc.httpGet(wm.baseUrl() + "/probe", null, null, null, null, "summary");

        assertTrue(out.contains("\"statusCode\":200"), out);
        wm.verify(1, getRequestedFor(urlEqualTo("/probe")));
    }
}