package cn.wubo.spring.ai.loom.agent.tool.render;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无头 Chromium HTML→PNG 渲染引擎(spec 2026-09-09-html-render-tool-design D5/D6/D9)。
 * <p>
 * 移植 sql-forge {@code PlaywrightRenderer} 的浏览器生命周期骨架:懒启动单例 BrowserHolder +
 * FutureTask 启动超时 + {@link #close()} 释放(bean destroyMethod 目标)。差异点:
 * <ul>
 *   <li>三级 Chromium 探测(spec D5):chromium-path 配置 → Playwright 默认缓存 → setChannel("chromium") 系统包</li>
 *   <li>网络屏蔽双保险(spec D6):route("**") abort(仅 networkBlocked=true)+ CSP meta 注入(任何情况都注入)</li>
 *   <li>Semaphore(1) 串行渲染(spec D9),排队最多 timeoutSeconds,超时抛 {@link RenderBusyException}</li>
 *   <li>输出 PNG 字节 + IHDR 实际尺寸(不再是 amis PreviewResult)</li>
 * </ul>
 * <p>
 * <b>裸机 Linux 已知坑</b>(详见 docs/provision-chromium.sh):缺系统 so 库(libnss3 等)launch 直接
 * crash;缺 CJK 字体截图中文全豆腐块(□□□);root 跑 Chromium 需要 --no-sandbox —— 建议非 root
 * 运行服务,本引擎不自动加该参数(安全默认)。
 */
public class HtmlRenderEngine {

    private static final Logger log = LoggerFactory.getLogger(HtmlRenderEngine.class);

    /**
     * Chromium 启动超时(毫秒)。sql-forge 原值 10s;这里放宽到 60s:dev 机首次
     * Playwright.create() 可能触发浏览器在线下载(数十秒),10s 会把下载拦腰截断。
     * Linux provisioned 机器 launch < 3s,60s 只影响"真坏了"的场景。
     */
    public static final int LAUNCH_TIMEOUT_MS = 60_000;

    /** CSP:允许 inline CSS/JS(自包含页面必需),封死一切外部源(spec D6/§4.1)。route abort 是双保险。 */
    static final String CSP =
            "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data: blob:; font-src data:";

    /** 匹配 <head> 开标签(可带属性),排除 <header> 等误匹配。 */
    private static final Pattern HEAD_OPEN = Pattern.compile("<head(?=[\\s>])", Pattern.CASE_INSENSITIVE);

    /** 设备视口预设(spec §1.2 device 白名单)。 */
    public record Viewport(int width, int height) {}

    /** 渲染产物:PNG 字节 + 图片实际像素尺寸(IHDR)。 */
    public record RenderResult(byte[] png, int widthPx, int heightPx) {}

    /** Chromium 不可用(三级探测全失败 / 启动超时)—— 工具层转 [渲染不可用] 文本(D8)。 */
    public static class RenderUnavailableException extends RuntimeException {
        private final String reason;
        private final String installHint;

        public RenderUnavailableException(String reason, String installHint) {
            super(reason);
            this.reason = reason;
            this.installHint = installHint;
        }

        public String reason() { return reason; }
        public String installHint() { return installHint; }
    }

    /** Semaphore 排队超时(D9)—— 工具层转 "[渲染失败] 渲染器忙,请稍后重试"。 */
    public static class RenderBusyException extends RuntimeException {
        public RenderBusyException(String message) { super(message); }
    }

    /** Chromium 不可用时的安装指引(工具层拼进 [渲染不可用] 文本)。 */
    public static final String INSTALL_HINT =
            "Linux 裸机部署请执行 provision-chromium.sh(见 docs/);Windows/macOS 开发机首次使用需联网由 Playwright 自动下载。";

    private final LoomAgentProperties.RenderProperty cfg;
    private final Semaphore semaphore = new Semaphore(1);
    private volatile BrowserHolder browserHolder;

    public HtmlRenderEngine(LoomAgentProperties.RenderProperty cfg) {
        this.cfg = cfg;
    }

    /** 测试缝:busy 路径单测需要预占许可(package-private,生产代码勿用)。 */
    Semaphore renderSemaphore() {
        return semaphore;
    }

    /**
     * 渲染自包含 HTML → PNG。串行(Semaphore(1)),排队 + 启动 + 渲染全程受配置约束。
     *
     * @param html     HTML 内容(调用方已做大小上限校验)
     * @param vp       视口(CSS 像素)
     * @param fullPage true=截整页;false=只截视口
     * @param scale    deviceScaleFactor(2=Retina)
     * @throws RenderUnavailableException Chromium 不可用
     * @throws RenderBusyException        排队超过 timeoutSeconds
     * @throws RuntimeException           其余渲染失败(message 为裸原因,工具层加 [渲染失败] 前缀)
     */
    public RenderResult render(String html, Viewport vp, boolean fullPage, int scale) {
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(cfg.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RenderBusyException("渲染排队被中断");
        }
        if (!acquired) {
            throw new RenderBusyException("渲染器忙(等待超过 " + cfg.getTimeoutSeconds() + "s 未获得渲染许可)");
        }
        try {
            BrowserHolder holder;
            try {
                holder = acquireBrowser();
            } catch (RenderUnavailableException e) {
                throw e;
            } catch (Exception e) {
                throw new RenderUnavailableException(describe(e), INSTALL_HINT);
            }
            try (BrowserContext ctx = holder.browser().newContext(new Browser.NewContextOptions()
                    .setViewportSize(vp.width(), vp.height())
                    .setDeviceScaleFactor(scale));
                 Page page = ctx.newPage()) {
                if (cfg.isNetworkBlocked()) {
                    // SSRF/外联双保险之一:一切网络请求 abort(setContent 注入本身不是网络请求,不受影响)
                    ctx.route("**", route -> route.abort());
                }
                page.setContent(injectCsp(html),
                        new Page.SetContentOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                // 无网络请求可等,固定小等待让内联 JS 渲染完成(spec §2)
                page.waitForTimeout(cfg.getRenderWaitMs());
                byte[] png = page.screenshot(new Page.ScreenshotOptions()
                        .setFullPage(fullPage)
                        .setType(ScreenshotType.PNG));
                int[] size = pngSize(png);
                return new RenderResult(png, size[0], size[1]);
            } catch (Exception e) {
                // message 保持裸原因,工具层负责加 "[渲染失败] " 前缀(避免双重前缀)
                throw new RuntimeException(describe(e), e);
            }
        } finally {
            semaphore.release();
        }
    }

    /** 释放浏览器 + Playwright(bean destroyMethod="close" 目标;移植 sql-forge shutdown/BrowserHolder.close)。 */
    public void close() {
        BrowserHolder holder = this.browserHolder;
        if (holder != null) {
            try {
                holder.close();
            } catch (Exception e) {
                log.warn("HtmlRenderEngine 浏览器关闭失败: {}", e.getMessage());
            }
            this.browserHolder = null;
        }
    }

    /** 懒启动 + 连接复用(移植 sql-forge acquireBrowser 的 FutureTask 超时骨架)。 */
    private BrowserHolder acquireBrowser() throws Exception {
        BrowserHolder holder = this.browserHolder;
        if (holder != null && holder.browser().isConnected()) {
            return holder;
        }
        synchronized (this) {
            if (this.browserHolder != null && this.browserHolder.browser().isConnected()) {
                return this.browserHolder;
            }
            // fix(最终评审 finding 1): 浏览器崩溃后恢复时,旧 holder 的 Playwright
            // node-driver 子进程必须先关闭再覆盖引用 —— 否则每次 crash-recovery 泄漏一个进程,
            // 破坏 spec §4.4 "单例浏览器 destroyMethod 释放" 的资源承诺。
            // BrowserHolder.close() 内部吞异常,对已死浏览器安全。
            BrowserHolder stale = this.browserHolder;
            if (stale != null) {
                stale.close();
                this.browserHolder = null;
            }
            FutureTask<BrowserHolder> task = new FutureTask<>(() -> {
                BrowserHolder h = launchWithProbe();
                if (Thread.currentThread().isInterrupted()) {
                    // 等待侧已超时 cancel(true),但 launch 恰好完成 —— 就地关闭,
                    // 不留孤儿 Chromium/node-driver 进程(最终评审 M1)。
                    h.close();
                    throw new IllegalStateException("Chromium launch 在超时取消后完成,已就地关闭");
                }
                return h;
            });
            ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "loom-html-render-launch");
                t.setDaemon(true);
                return t;
            });
            try {
                executor.submit(task);
                this.browserHolder = task.get(LAUNCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                return this.browserHolder;
            } catch (TimeoutException te) {
                task.cancel(true);
                throw new RenderUnavailableException(
                        "Chromium 启动超时(> " + LAUNCH_TIMEOUT_MS + "ms);dev 机首次使用可能正在在线下载浏览器,请稍后重试",
                        INSTALL_HINT);
            } catch (ExecutionException ee) {
                Throwable c = ee.getCause() == null ? ee : ee.getCause();
                if (c instanceof RenderUnavailableException rue) {
                    throw rue;
                }
                throw new RenderUnavailableException(describe(c), INSTALL_HINT);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * 三级探测(spec D5):① chromium-path 配置 → ② Playwright 默认缓存(~/.cache/ms-playwright /
     * %LOCALAPPDATA%\ms-playwright)→ ③ setChannel("chromium") 系统包。全部失败时抛
     * IllegalStateException,reason 汇总三级各自的失败原因。
     */
    private BrowserHolder launchWithProbe() {
        Playwright pw = Playwright.create();
        boolean handedOff = false;
        try {
            List<String> reasons = new ArrayList<>();
            String configured = cfg.getChromiumPath();
            if (configured != null && !configured.isBlank()) {
                try {
                    Browser b = pw.chromium().launch(baseLaunch().setExecutablePath(Paths.get(configured.trim())));
                    log.info("HtmlRenderEngine Chromium 启动成功(chromium-path={})", configured);
                    handedOff = true;
                    return new BrowserHolder(pw, b, "chromium-path");
                } catch (Exception e) {
                    reasons.add("chromium-path(" + configured + "): " + e.getMessage());
                }
            }
            try {
                Browser b = pw.chromium().launch(baseLaunch());
                log.info("HtmlRenderEngine Chromium 启动成功(Playwright 默认缓存)");
                handedOff = true;
                return new BrowserHolder(pw, b, "playwright-default");
            } catch (Exception e) {
                reasons.add("Playwright 默认缓存: " + e.getMessage());
            }
            try {
                Browser b = pw.chromium().launch(baseLaunch().setChannel("chromium"));
                log.info("HtmlRenderEngine Chromium 启动成功(系统 channel=chromium)");
                handedOff = true;
                return new BrowserHolder(pw, b, "channel:chromium");
            } catch (Exception e) {
                reasons.add("channel=chromium: " + e.getMessage());
            }
            throw new IllegalStateException("Chromium 三级探测全部失败 — " + String.join(" | ", reasons));
        } finally {
            // fix(最终评审 M2): 任何非成功路径(含 Error,如驱动解压 OOM)都关闭 pw,
            // 原实现只在"全失败"分支 close,Exception/Error 从探测缝隙抛出时泄漏。
            if (!handedOff) {
                try { pw.close(); } catch (Exception ignore) { }
            }
        }
    }

    /** 异常描述兜底:message 为 null 时用 toString(),避免工具层文本出现 "[渲染失败] null"(最终评审 M3)。 */
    private static String describe(Throwable t) {
        String m = t.getMessage();
        return (m != null && !m.isBlank()) ? m : t.toString();
    }

    private BrowserType.LaunchOptions baseLaunch() {
        return new BrowserType.LaunchOptions()
                .setHeadless(true)
                // 防御纵深:Node driver 的 deprecation 警告不回流 stdout(移植 sql-forge)
                .setEnv(Map.of("NODE_NO_WARNINGS", "1"));
    }

    /**
     * CSP 注入(spec §2):有 {@code <head>}(可带属性)则在开标签后插 meta;无 head 则整页包裹
     * 最小骨架。任何情况都注入 —— networkBlocked=false 只去掉 route abort,CSP 仍在(spec §4.1)。
     */
    static String injectCsp(String html) {
        String meta = "<meta http-equiv=\"Content-Security-Policy\" content=\"" + CSP + "\">";
        String src = html == null ? "" : html;
        String lower = src.toLowerCase(Locale.ROOT);
        Matcher m = HEAD_OPEN.matcher(src);
        if (m.find()) {
            int headIdx = m.start();
            int scriptIdx = lower.indexOf("<script");
            int bodyIdx = lower.indexOf("<body");
            // fix(最终评审 finding 3): 首个 <head 若出现在 <script>/<body 之后,
            // 它可能位于 JS 字符串内 —— 插进去的 meta 不会被解析,CSP 失效。
            // 此时改走整页包裹分支,保证 CSP 从第 0 字节生效。
            boolean headIsReal = (scriptIdx < 0 || headIdx < scriptIdx)
                    && (bodyIdx < 0 || headIdx < bodyIdx);
            if (headIsReal) {
                int gt = src.indexOf('>', m.end() - 1);
                if (gt >= 0) {
                    return src.substring(0, gt + 1) + meta + src.substring(gt + 1);
                }
            }
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\">" + meta + "</head><body>" + src + "</body></html>";
    }

    /**
     * 读 PNG IHDR 宽高(big-endian)。PNG 布局:8B 签名 + 4B 长度 + "IHDR" + 4B 宽(offset 16)
     * + 4B 高(offset 20)。坏输入返回 {0, 0}(工具层文本仍可用,只是尺寸显示 0x0)。
     */
    static int[] pngSize(byte[] png) {
        if (png == null || png.length < 24) {
            return new int[]{0, 0};
        }
        int w = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
        int h = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16) | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
        return new int[]{w, h};
    }

    /** 浏览器持有器(移植 sql-forge BrowserHolder;source 记录三级探测命中的是哪一级,仅日志用)。 */
    record BrowserHolder(Playwright pw, Browser browser, String source) {
        void close() {
            try { browser.close(); } catch (Exception ignore) { }
            try { pw.close(); } catch (Exception ignore) { }
        }
    }
}
