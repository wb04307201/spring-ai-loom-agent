package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3/4(spec 2026-11-24 steering-retract)前端撤回 BrowserIT。
 *
 * <p>镜像 {@link SteeringInputBrowserIT} 脚手架(route 拦截 + awaitRoute 切片泵送 R26):
 * POST /stream 扣住不 fulfill ⇒ 状态机稳定 RUNNING;POST /steer 按脚本 fulfill;
 * <b>DELETE /steer/{messageId} 按脚本 fulfill</b>(retracted:true / false+reason)。
 *
 * <p>覆盖契约(task-3-brief 5 例):
 * ① queued ✕ → DELETE retracted:true → 气泡 DOM 移除 + toast「已撤回」;
 * ② 撤回失败(not_in_queue)→ 气泡保留不预判,INJECTED ack 到达 → 转正(按钮随 note 移除,G3);
 * ③ 同②但 NOT_INJECTED ack → 标「未发送」可见终态(A2);
 * ④ R20 晚响应:气泡渲染后 live 会话已切走 → DELETE 朝气泡 dataset.convId 快照发(Review Focus 4);
 * ⑤ 撤回文本含 HTML/script → 气泡/按钮/toast 全程 inert(Review Focus 3)。
 * Task 4 增补 ⑥⑦⑧⑨(fallback/unsent 本地清理 + 幽灵补发双腿)。
 */
@DisplayName("Task 3/4 steering 撤回:✕ 按钮 + queued 端点裁决 + convId 快照 + XSS inert")
class RetractSteeringBrowserIT extends BrowserTestBase {

    private static final Page.WaitForFunctionOptions WF_5S =
            new Page.WaitForFunctionOptions().setTimeout(5_000).setPollingInterval(100);
    private static final Page.WaitForFunctionOptions WF_10S =
            new Page.WaitForFunctionOptions().setTimeout(10_000).setPollingInterval(100);

    /** 打开聊天页并等 app.js 模块就绪 + 会话列表落地(镜像 SteeringInputBrowserIT)。 */
    private Page openChatReady(BrowserContext ctx) {
        Page page = newPage(ctx);
        page.navigate(baseUrl + UI + "index.html");
        page.waitForSelector("#textarea");
        page.waitForFunction("() => !!window._loomAgent", null, WF_10S);
        page.waitForFunction(
                "() => { const el=document.getElementById('sidebarList');"
                        + " return !!el && !el.querySelector('.sidebar-loading'); }", null, WF_10S);
        return page;
    }

    private void newConversation(Page page) {
        String before = (String) page.evaluate("() => window._loomAgent.state.conversationId || ''");
        page.click("#new-chat-btn");
        page.waitForFunction(
                "(b) => window._loomAgent.state.conversationId && window._loomAgent.state.conversationId !== b",
                before, WF_10S);
    }

    private String convId(Page page) {
        return (String) page.evaluate("() => window._loomAgent.state.conversationId");
    }

    /** 扣住聊天流 POST /stream:请求入队、不 fulfill ⇒ 前端保持 RUNNING。 */
    private static BlockingQueue<Route> holdStreams(Page page) {
        BlockingQueue<Route> q = new LinkedBlockingQueue<>();
        page.route("**/spring/ai/loom/stream", q::add);
        return q;
    }

    /** steer 脚本:queued:true,messageId=m-{{n}}(按请求序号展开)。 */
    private static void steerQueued(Page page) {
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        page.route("**/steer", route -> route.fulfill(new Route.FulfillOptions()
                .setStatus(200).setContentType("application/json; charset=UTF-8")
                .setBody("{\"queued\":true,\"messageId\":\"m-" + seq.incrementAndGet() + "\"}")));
    }

    /** DELETE 撤回脚本:固定 status+body;urlSink 收集请求 URL(快照断言用)。 */
    private static List<String> retractScript(Page page, int status, String body) {
        List<String> urls = Collections.synchronizedList(new ArrayList<>());
        page.route("**/steer/*", route -> {
            if (!"DELETE".equals(route.request().method())) {
                route.fallback();
                return;
            }
            urls.add(route.request().url());
            route.fulfill(new Route.FulfillOptions()
                    .setStatus(status).setContentType("application/json; charset=UTF-8")
                    .setBody(body));
        });
        return urls;
    }

    private static void fulfillSse(Route route, String... frameJsons) {
        StringBuilder sb = new StringBuilder();
        for (String f : frameJsons) sb.append("data: ").append(f).append("\n\n");
        try {
            route.fulfill(new Route.FulfillOptions()
                    .setStatus(200).setContentType("text/event-stream").setBody(sb.toString()));
        } catch (Exception ignored) {
            // 测试失败/上下文已关:请求可能已被 abort,忽略收尾异常,不掩盖真实断言结果
        }
    }

    /** R26:切片轮询等 Route(每切片一次 Playwright 调用泵送事件循环,不能裸阻塞)。 */
    private static Route awaitRoute(BlockingQueue<Route> q, Page page, long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            Route r = q.poll();
            if (r != null) return r;
            page.waitForTimeout(50);
        }
        return q.poll();
    }

    private void waitIdle(Page page) {
        page.waitForFunction(
                "() => !window._loomAgent.state.isStreaming"
                        + " && window._loomAgent.state.inputMode === 'IDLE'", null, WF_10S);
    }

    /** 起一个被扣住的 run + 一条 queued 灰态插话(m-1),返回流 Route。 */
    private Route startRunWithQueuedSteer(Page page, String steerText) {
        BlockingQueue<Route> streams = holdStreams(page);
        steerQueued(page);
        newConversation(page);
        page.fill("#textarea", "第一条消息");
        page.click("#send-btn");
        Route run1 = awaitRoute(streams, page, 5_000);
        assertThat(run1).as("聊天流已发出并被扣住(RUNNING)").isNotNull();
        page.fill("#textarea", steerText);
        page.press("#textarea", "Enter");
        page.waitForSelector(".chat-item-queued[data-message-id=\"m-1\"]",
                new Page.WaitForSelectorOptions().setTimeout(5_000));
        return run1;
    }

    // ==================== ① queued ✕ → retracted:true → 气泡移除 + toast ====================

    @Test
    @DisplayName("queued 灰态点 ✕ → DELETE retracted:true → 气泡 DOM 移除 + toast「已撤回」")
    void queued_retract_removes_bubble() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            List<String> deleteUrls = retractScript(page, 200, "{\"retracted\":true}");
            BlockingQueue<Route> streams = holdStreams(page);
            steerQueued(page);
            newConversation(page);
            String conv = convId(page);

            page.fill("#textarea", "第一条消息");
            page.click("#send-btn");
            Route run1 = awaitRoute(streams, page, 5_000);
            assertThat(run1).isNotNull();
            page.fill("#textarea", "要撤回的插话");
            page.press("#textarea", "Enter");
            page.waitForSelector(".chat-item-queued[data-message-id=\"m-1\"] .queued-retract-btn",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));

            page.click(".chat-item-queued[data-message-id=\"m-1\"] .queued-retract-btn");
            page.waitForFunction(
                    "() => document.querySelector('[data-message-id=\"m-1\"]') === null", null, WF_5S);
            assertThat(deleteUrls).as("恰发一次 DELETE").hasSize(1);
            assertThat(deleteUrls.get(0))
                    .as("DELETE 归属当前会话 + messageId")
                    .contains("/spring/ai/loom/stream/" + conv + "/steer/m-1");
            assertThat((String) page.evaluate(
                    "() => document.getElementById('toast-notification').textContent"))
                    .as("撤回成功 toast").contains("已撤回");

            fulfillSse(run1, "{\"content\":\"好的\"}");
            waitIdle(page);
            assertThat(consoleErrorsOf(page)).as("全程无 JS console error").isEmpty();
        }
    }

    // ==================== ② 撤回失败 → 保留气泡,INJECTED ack 转正 ====================

    @Test
    @DisplayName("DELETE retracted:false(not_in_queue)→ 气泡保留不预判;INJECTED ack → 转正且 ✕ 随 note 移除(G3)")
    void retract_failure_keeps_bubble_for_ack() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            retractScript(page, 200, "{\"retracted\":false,\"reason\":\"not_in_queue\"}");
            Route run1 = startRunWithQueuedSteer(page, "撤回竞态插话");

            page.click(".chat-item-queued[data-message-id=\"m-1\"] .queued-retract-btn");
            page.waitForFunction(
                    "() => document.getElementById('toast-notification').textContent.includes('未能撤回')",
                    null, WF_5S);
            assertThat(page.locator(".chat-item-queued[data-message-id=\"m-1\"]").count())
                    .as("撤回失败:气泡保留,不预判状态(ack/汇聚点裁决)").isEqualTo(1);
            assertThat((String) page.evaluate(
                    "() => document.querySelector('[data-message-id=\"m-1\"]').dataset.steerState"))
                    .as("失败不改 steerState(仍 queued 等 ack)").isEqualTo("queued");

            // INJECTED ack 迟到 → 转正;✕ 按钮必须随 note 一起移除(已注入不可撤回,G3)
            fulfillSse(run1,
                    "{\"content\":\"收到补充\"}",
                    "{\"steerAck\":{\"messageId\":\"m-1\",\"status\":\"INJECTED\"}}");
            waitIdle(page);
            assertThat(page.evaluate("() => { const el=document.querySelector('[data-message-id=\"m-1\"]');"
                    + " return el && el.dataset.steerState === 'injected'"
                    + " && !el.classList.contains('chat-item-queued')"
                    + " && el.querySelector('.queued-note') === null"
                    + " && el.querySelector('.queued-retract-btn') === null; }"))
                    .as("转正后 ✕ 按钮不存在(G3:injected 不可撤回)").isEqualTo(true);
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    // ==================== ③ 撤回失败 → NOT_INJECTED ack 标未发送 ====================

    @Test
    @DisplayName("DELETE retracted:false 后 NOT_INJECTED ack 到达 → 标「未发送」可见终态(A2)")
    void retract_failure_then_not_injected() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            retractScript(page, 200, "{\"retracted\":false,\"reason\":\"not_in_queue\"}");
            Route run1 = startRunWithQueuedSteer(page, "护栏收口插话");

            page.click(".chat-item-queued[data-message-id=\"m-1\"] .queued-retract-btn");
            page.waitForFunction(
                    "() => document.getElementById('toast-notification').textContent.includes('未能撤回')",
                    null, WF_5S);

            fulfillSse(run1,
                    "{\"content\":\"好的\"}",
                    "{\"steerAck\":{\"messageId\":\"m-1\",\"status\":\"NOT_INJECTED\"}}");
            waitIdle(page);
            assertThat(page.evaluate("() => { const el=document.querySelector('[data-message-id=\"m-1\"]');"
                    + " return el && el.dataset.steerState === 'unsent'"
                    + " && el.querySelector('.queued-note').textContent.includes('未发送'); }"))
                    .as("NOT_INJECTED → 未发送可见终态,不静默蒸发").isEqualTo(true);
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    // ==================== ④ convId 快照:DELETE 朝气泡快照发,不朝 live(Review Focus 4) ====================

    @Test
    @DisplayName("R20 晚响应:气泡渲染后 live 会话已切走 → DELETE 朝气泡 dataset.convId 快照发,不含 live 值")
    void retract_uses_conv_snapshot() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            List<String> deleteUrls = retractScript(page, 200, "{\"retracted\":true}");
            Route run1 = startRunWithQueuedSteer(page, "快照边界插话");
            String snapshot = convId(page);
            assertThat((String) page.evaluate(
                    "() => document.querySelector('[data-message-id=\"m-1\"]').dataset.convId"))
                    .as("气泡渲染时绑定 convId 快照").isEqualTo(snapshot);

            // 模拟 R20 晚响应形态:气泡已在视图,但 live state.conversationId 已是另一会话
            page.evaluate("() => { window._loomAgent.state.conversationId = 'conv-live-other'; }");
            page.click(".chat-item-queued[data-message-id=\"m-1\"] .queued-retract-btn");
            page.waitForFunction(
                    "() => document.querySelector('[data-message-id=\"m-1\"]') === null", null, WF_5S);

            assertThat(deleteUrls).hasSize(1);
            assertThat(deleteUrls.get(0))
                    .as("DELETE 朝气泡快照 conv 发(R28/R34 同哲学)").contains("/stream/" + snapshot + "/steer/m-1");
            assertThat(deleteUrls.get(0))
                    .as("绝不朝 live 会话发 DELETE").doesNotContain("conv-live-other");

            page.evaluate("(c) => { window._loomAgent.state.conversationId = c; }", snapshot);
            fulfillSse(run1, "{\"content\":\"好的\"}");
            waitIdle(page);
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    // ==================== ⑤ XSS:撤回全程 inert(Review Focus 3) ====================

    @Test
    @DisplayName("插话含 <img onerror> → 灰态/✕ 按钮/toast 全程 inert,无 alert 无活动 img")
    void retract_xss_inert() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            AtomicBoolean dialogFired = new AtomicBoolean(false);
            page.onDialog(d -> {
                dialogFired.set(true);
                try { d.dismiss(); } catch (Exception ignored) {}
            });
            retractScript(page, 200, "{\"retracted\":true}");
            String payload = "<img src=x onerror=alert(1)>";
            Route run1 = startRunWithQueuedSteer(page, payload);

            assertThat((Boolean) page.evaluate(
                    "() => { const b=document.querySelector('[data-message-id=\"m-1\"] .bubble');"
                            + " return b.querySelector('img') === null && !b.innerHTML.includes('<img'); }"))
                    .as("灰态气泡内无活动 <img>(textContent 纪律)").isTrue();
            assertThat((String) page.evaluate(
                    "() => document.querySelector('[data-message-id=\"m-1\"] .queued-retract-btn').textContent"))
                    .as("✕ 按钮自身也是 inert 文本").isEqualTo("✕");

            page.click(".chat-item-queued[data-message-id=\"m-1\"] .queued-retract-btn");
            page.waitForFunction(
                    "() => document.querySelector('[data-message-id=\"m-1\"]') === null", null, WF_5S);
            assertThat((String) page.evaluate(
                    "() => document.getElementById('toast-notification').textContent"))
                    .as("toast 走 textContent,inert").contains("已撤回");
            page.waitForTimeout(300); // onerror 若被执行,dialog 早已弹出
            assertThat(dialogFired.get()).as("撤回全程 onerror 未执行").isFalse();

            fulfillSse(run1, "{\"content\":\"ok\"}");
            waitIdle(page);
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }
}