package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotAnimations;
import com.microsoft.playwright.options.ScreenshotCaret;
import com.microsoft.playwright.options.ScreenshotType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 截图基线视觉回归:10 页(index + login + 8 admin)固定 viewport(基类 1280×800 /
 * deviceScaleFactor=1 / ReducedMotion.REDUCE)全页截图,与
 * {@code src/test/resources/browser-baselines/*.png}(提交入 git)逐像素比对。
 *
 * <p><b>判定阈值(控制器裁定):</b>单像素 RGB 归一化距离 &gt; 0.1 记差异像素;
 * 差异比例 &gt; 0.5% 判 FAIL;diff 热力图输出 {@code target/visual-diffs/}。
 * {@code -DupdateBaselines=true} 时只写基线跳过比较。
 *
 * <p><b>基线生成仪式:</b>必须干净库 + 只跑本类:
 * <pre>
 * rm -rf spring-ai-loom-agent-test/target/test-ds
 * mvn test -pl spring-ai-loom-agent-test -Dtest='VisualBaselineBrowserIT' \
 *   -Dsurefire.failIfNoSpecifiedTests=false -DupdateBaselines=true
 * </pre>
 *
 * <p><b>探针参数(镜像 PageHealthBrowserIT 校准):</b>user.html 缺 {@code username}、
 * conversation.html 缺 {@code id} 时页面 JS 会 location.replace 到 console.html,
 * 基线对象会漂移 —— 分别固定 {@code ?username=wb04307201} 与 {@code ?id=visual-probe}
 * (不存在的会话 id,走后端空数据兜底,渲染确定性空态)。
 *
 * <p><b>动态内容覆写清单(截图前 page.evaluate,控制器裁定 #4):</b>
 * <ul>
 *   <li><b>stats.html</b>:stats.js 用 {@code new Date()} 填 {@code #year-input} /
 *       {@code #month-input},load() 又据此渲染 {@code #month-label}("YYYY-MM 月用量")
 *       与 #stats-table 空态("YYYY-M 无用量记录")—— 同月内确定、跨月漂移,
 *       统一覆写为 2026 / 1 / "2026-01 月用量" / "2026-1 无用量记录"。</li>
 *   <li><b>admin-user.html</b>:user.js loadBarChart 渲染最近 6 个月标签
 *       (bar-label "YYYY-MM",跨月漂移)—— 覆写为固定 2026-01..2026-06;
 *       #month-total("本月用量：N tokens")一并固定(干净库 N=0)。</li>
 *   <li><b>admin-market-skills.html</b>:V1.0 种子 2 条官方技能的 reviewed_at =
 *       CURRENT_TIMESTAMP(建库时刻),wipe 重建后 approval-meta / 提交时间列的
 *       "YYYY-MM-DD HH:mm" 会变 —— 表格内所有时间戳正则覆写为 "2026-01-01 00:00"。</li>
 *   <li><b>admin-conversation.html</b>:?id=visual-probe 不存在的会话走后端空数据兜底,
 *       但 flow 仍合成一条 SYSTEM 事件,其 ts = 请求时刻 → conversation.js
 *       {@code toLocaleString("zh-CN")} 渲染 "YYYY/M/D H:mm:ss"(斜杠+秒级),
 *       每次请求都变(二跑实测 diff=0.006%,红像素 bbox 精确落在时间戳秒段)——
 *       同款文本节点 scrubber 覆写。</li>
 *   <li><b>index.html 侧边栏</b>:干净库下无会话行、无时间戳文案(app.js formatDate /
 *       generateDefaultConversationTitle 只在有会话/新建时渲染)—— 首跑 actual 与
 *       二跑 diff 为 0,无需覆写(结论已实证,见 task-13-report.md)。</li>
 * </ul>
 * 其余 6 页(index/login/console/roles/mcps/knowledge-market)空态/种子数据
 * 均为确定性文案,无时间渲染路径(源码逐页核验 + 三跑 diff=0 实证)。
 *
 * <p><b>截图确定性:</b>waitForLoadState(NETWORKIDLE) + waitForTimeout(1500) 停稳
 * 异步渲染/字体后,fullPage PNG + {@code setAnimations(DISABLED)}(冻结 CSS 无限动画,
 * style.css 存在 spin/pulse/shimmer 等 infinite keyframes)+ {@code setCaret(HIDE)}。
 */
@DisplayName("L2 截图基线:10 页固定 viewport 全页截图 vs browser-baselines(-DupdateBaselines=true 重建)")
class VisualBaselineBrowserIT extends BrowserTestBase {

    private static final double PIXEL_THRESHOLD = 0.1;   // 单像素 RGB 距离容忍(抗锯齿)
    private static final double RATIO_THRESHOLD = 0.005; // 0.5% 差异像素比例

    private final Path baselineDir = Path.of("src/test/resources/browser-baselines");
    private final Path actualDir = Path.of("target/visual-actual");
    private final Path diffDir = Path.of("target/visual-diffs");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "index.html", "login.html",
            "admin-console.html", "admin-stats.html", "admin-user.html",
            "admin-roles.html", "admin-mcps.html", "admin-conversation.html",
            "admin-market-skills.html", "admin-knowledge-market.html"
    })
    void pageMatchesBaseline(String name) throws Exception {
        boolean isLogin = "login.html".equals(name);
        String urlPath = toUrlPath(name);

        // login.html 必须未登录 context(已登录会被守卫跳到 index);其余走 adminContext
        try (BrowserContext ctx = isLogin ? newContext() : adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + urlPath);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(1500); // 停稳异步渲染/字体
            freezeDynamicContent(page, name);

            actualDir.toFile().mkdirs();
            File actual = actualDir.resolve(name.replace(".html", "") + ".png").toFile();
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(actual.toPath())
                    .setFullPage(true)
                    .setType(ScreenshotType.PNG)
                    .setAnimations(ScreenshotAnimations.DISABLED)
                    .setCaret(ScreenshotCaret.HIDE));

            File baseline = baselineDir.resolve(name.replace(".html", "") + ".png").toFile();
            boolean update = Boolean.getBoolean("updateBaselines");
            if (update || !baseline.exists()) {
                baselineDir.toFile().mkdirs();
                Files.copy(actual.toPath(), baseline.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[VisualBaseline] 基线已写入: " + baseline.getAbsolutePath()
                        + " (" + baseline.length() + " bytes)");
                return; // 首建/update 模式不判 diff
            }
            PngDiff.Result r = PngDiff.compare(baseline, actual,
                    diffDir.resolve(name.replace(".html", "") + "-diff.png").toFile(),
                    PIXEL_THRESHOLD, RATIO_THRESHOLD);
            System.out.printf("[VisualBaseline] %s diff=%.4f%% (%dx%d, sizeMismatch=%b)%n",
                    name, r.diffRatio() * 100, r.width(), r.height(), r.sizeMismatch());
            assertThat(r.pass())
                    .as("%s 视觉回归 diff=%.4f%% (阈值 %.2f%%),diff 图见 %s",
                            name, r.diffRatio() * 100, RATIO_THRESHOLD * 100,
                            diffDir.toAbsolutePath())
                    .isTrue();
        }
    }

    /** 基线文件名 → 实际 URL 路径(含 user/conversation 探针参数,见类 javadoc)。 */
    private String toUrlPath(String name) {
        if ("login.html".equals(name)) {
            return "login.html";
        }
        if (!name.startsWith("admin-")) {
            return name; // index.html
        }
        String page = name.substring("admin-".length()); // e.g. console.html
        return switch (page) {
            case "user.html" -> "admin/user.html?username=" + ADMIN_USER;
            case "conversation.html" -> "admin/conversation.html?id=visual-probe";
            default -> "admin/" + page;
        };
    }

    /**
     * 截图前把随时间/建库时刻变化的文案覆写为固定值(清单与理由见类 javadoc)。
     * 全部操作幂等且只改文本/value,不改布局结构。
     */
    private void freezeDynamicContent(Page page, String name) {
        switch (name) {
            case "admin-stats.html" -> page.evaluate("""
                    () => {
                      const y = document.getElementById('year-input');
                      if (y) y.value = '2026';
                      const m = document.getElementById('month-input');
                      if (m) m.value = '1';
                      const l = document.getElementById('month-label');
                      if (l) l.textContent = '2026-01 月用量';
                      document.querySelectorAll('#stats-table .empty-state')
                        .forEach(e => e.textContent = '2026-1 无用量记录');
                    }
                    """);
            case "admin-user.html" -> page.evaluate("""
                    () => {
                      const labels = ['2026-01','2026-02','2026-03','2026-04','2026-05','2026-06'];
                      document.querySelectorAll('#bar-chart .bar-label')
                        .forEach((e, i) => e.textContent = labels[i] || '2026-01');
                      const mt = document.getElementById('month-total');
                      if (mt) mt.textContent = '本月用量：0 tokens';
                    }
                    """);
            case "admin-market-skills.html" -> scrubTimestamps(page, "#skill-table-container");
            case "admin-conversation.html" -> scrubTimestamps(page, "body");
            default -> {
                // 其余 6 页无动态时间文案(源码核验 + 首跑/二跑 diff=0 实证)
            }
        }
    }

    /**
     * 文本节点级时间戳 scrubber:只改写匹配文本节点的 nodeValue(不碰 HTML 结构,
     * badge/按钮等子元素保留),兼容 ISO("2026-09-11 16:57")与 zh-CN toLocaleString
     * ("2026/9/11 16:57:50")两种渲染格式,统一覆写为固定 "2026-01-01 00:00"。
     *
     * @param rootCss scrub 根选择器(限定影响面,避免误伤其它区域)
     */
    private void scrubTimestamps(Page page, String rootCss) {
        page.evaluate("""
                (rootCss) => {
                  const root = document.querySelector(rootCss);
                  if (!root) return;
                  const re = /\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}[ T]\\d{1,2}:\\d{2}(:\\d{2})?/g;
                  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
                  let n;
                  while ((n = walker.nextNode())) {
                    if (re.test(n.nodeValue)) {
                      n.nodeValue = n.nodeValue.replace(re, '2026-01-01 00:00');
                    }
                    re.lastIndex = 0;
                  }
                }
                """, rootCss);
    }
}
