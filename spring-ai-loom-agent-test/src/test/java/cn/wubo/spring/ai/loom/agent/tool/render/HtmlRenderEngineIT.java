package cn.wubo.spring.ai.loom.agent.tool.render;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * HtmlRenderEngine 真 Chromium 集成测试(spec §5 IT 行)。
 * <p>
 * 环境缺失条件跳过(镜像 DefaultMavenToolRealProjectIT 的 assumeTrue 先例):
 * Linux 裸机未 provision / 首次 Playwright 下载失败 → 3 用例全 skip,不算失败。
 * dev Windows 机首跑会在线下载 Chromium(需联网)。
 */
@DisplayName("HtmlRenderEngine 真 Chromium 集成测试")
class HtmlRenderEngineIT {

    private static HtmlRenderEngine engine;

    @BeforeAll
    static void probeAvailability() {
        engine = new HtmlRenderEngine(new LoomAgentProperties.RenderProperty());
        boolean available;
        try {
            engine.render("<html><body>ping</body></html>",
                    new HtmlRenderEngine.Viewport(100, 100), false, 1);
            available = true;
        } catch (Exception e) {
            available = false;
            System.out.println("[HtmlRenderEngineIT] Chromium 不可用,跳过: " + e.getMessage());
        }
        assumeTrue(available,
                "跳过:Chromium 不可用(Linux 裸机未 provision / 首次 Playwright 下载失败)");
    }

    @AfterAll
    static void closeBrowser() {
        if (engine != null) engine.close();
    }

    @Test
    @DisplayName("中文 + inline CSS → PNG(非空 + magic + 尺寸 = 视口 × deviceScaleFactor)")
    void rendersChineseHtmlToPngWithScaledDimensions() {
        String html = "<html><head><style>h1{color:#6366f1;font-family:sans-serif}</style></head>"
                + "<body><h1>登录页原型</h1><p>中文渲染无豆腐块需要系统 CJK 字体(provision 脚本装 fonts-noto-cjk)</p>"
                + "<input placeholder='用户名'><input type='password' placeholder='密码'></body></html>";
        HtmlRenderEngine.RenderResult r = engine.render(html,
                new HtmlRenderEngine.Viewport(1440, 900), false, 2);
        assertThat(r.png()).isNotNull();
        assertThat(r.png().length).isGreaterThan(1000);
        // PNG magic: 0x89 'P' 'N' 'G'
        assertThat(r.png()[0] & 0xFF).isEqualTo(0x89);
        assertThat(r.png()[1]).isEqualTo((byte) 'P');
        assertThat(r.png()[2]).isEqualTo((byte) 'N');
        assertThat(r.png()[3]).isEqualTo((byte) 'G');
        assertThat(r.widthPx()).isEqualTo(2880);   // 1440 * scale 2
        assertThat(r.heightPx()).isEqualTo(1800);  // fullPage=false → 视口 900 * 2
    }

    @Test
    @DisplayName("网络屏蔽:页面外链图片 → 探测服务器 0 命中(route abort + CSP 双保险,D6)")
    void blocksExternalNetworkRequests() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe.png", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            String html = "<html><body><img src='http://127.0.0.1:" + port + "/probe.png'>"
                    + "<p>x</p></body></html>";
            HtmlRenderEngine.RenderResult r = engine.render(html,
                    new HtmlRenderEngine.Viewport(800, 600), false, 1);
            assertThat(r.png()).isNotNull();
            assertThat(hits.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("fullPage=true:超高内容截整页(高度 ≥ 内容高,宽 = 视口宽)")
    void fullPageCapturesBeyondViewport() {
        StringBuilder sb = new StringBuilder("<html><body style='margin:0'>");
        for (int i = 0; i < 200; i++) {
            sb.append("<div style='height:40px'>row ").append(i).append("</div>");
        }
        sb.append("</body></html>");
        HtmlRenderEngine.RenderResult r = engine.render(sb.toString(),
                new HtmlRenderEngine.Viewport(800, 600), true, 1);
        assertThat(r.widthPx()).isEqualTo(800);
        assertThat(r.heightPx()).isGreaterThanOrEqualTo(7900); // 200*40=8000,容忍取整
    }
}
