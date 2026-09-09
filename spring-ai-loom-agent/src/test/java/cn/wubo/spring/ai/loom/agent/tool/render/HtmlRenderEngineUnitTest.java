package cn.wubo.spring.ai.loom.agent.tool.render;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HtmlRenderEngine 纯函数 + 并发闸门的单元测试(不需要真浏览器)。
 * 真 Chromium 渲染在 HtmlRenderEngineIT(Task 5)。
 */
class HtmlRenderEngineUnitTest {

    @Test
    @DisplayName("injectCsp:有 <head> 时 meta 紧随其后插入,原内容保留")
    void injectCspAfterExistingHead() {
        String out = HtmlRenderEngine.injectCsp("<html><head><title>t</title></head><body>b</body></html>");
        assertThat(out).contains("<head><meta http-equiv=\"Content-Security-Policy\"");
        assertThat(out).contains("default-src 'none'");
        assertThat(out).contains("<title>t</title>").contains("<body>b</body>");
    }

    @Test
    @DisplayName("injectCsp:<head 带属性> 支持;<header> 不误匹配")
    void injectCspHeadWithAttributesAndNoHeaderFalsePositive() {
        String withAttrs = HtmlRenderEngine.injectCsp("<html><head lang=\"zh\"><title>t</title></head></html>");
        assertThat(withAttrs).contains("<head lang=\"zh\"><meta http-equiv=\"Content-Security-Policy\"");

        String headerOnly = HtmlRenderEngine.injectCsp("<div><header>h</header><p>x</p></div>");
        // 没有真 <head> → 走最小骨架包裹分支,<header> 后不被插 meta
        assertThat(headerOnly).startsWith("<!doctype html>");
        assertThat(headerOnly).doesNotContain("<header><meta");
        assertThat(headerOnly).contains("<header>h</header>");
    }

    @Test
    @DisplayName("injectCsp:无 head 时包裹最小骨架")
    void injectCspWrapsWhenNoHead() {
        String out = HtmlRenderEngine.injectCsp("<p>hello</p>");
        assertThat(out).startsWith("<!doctype html><html><head>");
        assertThat(out).contains("default-src 'none'").contains("<p>hello</p>");
    }

    @Test
    @DisplayName("injectCsp:<head 出现在 <script 字符串之后 → 走包裹分支(CSP 不可被诱骗,最终评审 finding 3)")
    void injectCspNotFooledByHeadInsideScriptString() {
        String attack = "<script>var s = \"<head>\";</script><html><head><title>t</title></head><body>b</body></html>";
        String out = HtmlRenderEngine.injectCsp(attack);
        assertThat(out).startsWith("<!doctype html><html><head>");
        assertThat(out).contains("default-src 'none'");
        // meta 必须在真正的文档 head 里(包裹层),而不是 script 字符串里
        assertThat(out.indexOf("Content-Security-Policy"))
                .isLessThan(out.indexOf("<script"));
    }

    @Test
    @DisplayName("pngSize:从 IHDR(offset 16/20,big-endian)解析宽高;坏输入 {0,0}")
    void pngSizeReadsIhdr() {
        byte[] fake = new byte[32];
        fake[18] = 0x05; fake[19] = (byte) 0xA0;  // 0x05A0 = 1440
        fake[22] = 0x03; fake[23] = (byte) 0x84;  // 0x0384 = 900
        assertThat(HtmlRenderEngine.pngSize(fake)).containsExactly(1440, 900);
        assertThat(HtmlRenderEngine.pngSize(new byte[10])).containsExactly(0, 0);
        assertThat(HtmlRenderEngine.pngSize(null)).containsExactly(0, 0);
    }

    @Test
    @DisplayName("busy 路径:Semaphore 许可被占,timeoutSeconds 后抛 RenderBusyException(D9)")
    void busyWhenSemaphoreOccupied() throws Exception {
        LoomAgentProperties.RenderProperty cfg = new LoomAgentProperties.RenderProperty();
        cfg.setTimeoutSeconds(1);
        HtmlRenderEngine engine = new HtmlRenderEngine(cfg);
        engine.renderSemaphore().acquire();  // 模拟另一渲染进行中
        try {
            AtomicReference<Throwable> err = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    engine.render("<p>x</p>", new HtmlRenderEngine.Viewport(100, 100), false, 1);
                } catch (Throwable ex) {
                    err.set(ex);
                }
            });
            t.start();
            t.join(10_000);
            assertThat(err.get()).isInstanceOf(HtmlRenderEngine.RenderBusyException.class);
        } finally {
            engine.renderSemaphore().release();
        }
    }
}
