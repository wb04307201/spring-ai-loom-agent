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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Task 11(spec 2026-11-21 agentic-loop-steering)前端输入三态机 BrowserIT。
 *
 * <p><b>测试策略:route 拦截为主 + 一个真实链路兜底</b>。
 * 确定性用例全部走 Playwright {@code page.route} 拦截:
 * <ul>
 *   <li>POST {@code /spring/ai/loom/stream}(聊天流)被拦截后<b>扣住不 fulfill</b> ——
 *       前端 fetch 挂起,{@code disableSend()} 已生效 ⇒ 状态机稳定停在 RUNNING,
 *       给插话/停止/回退断言留出确定窗口;断言完成后由测试线程手动 fulfill 一段
 *       预编排 SSE 帧(可含 {@code steerAck})收尾。</li>
 *   <li>POST {@code .../steer} 按脚本立即 fulfill(queued:true/false、403、429 等);
 *       body 里的 {@code {{n}}} 由请求计数展开,支持同一用例多次插话不同 messageId。</li>
 * </ul>
 * 真实 LLM 时序不可控(整轮可能几秒就收尾,插话窗口无法保证),故按 brief 授权以
 * mock 为主;末尾 {@code realRunSteeringKeepsUserTextVisible} 用真实后端 + 真实模型
 * 跑一条多迭代任务并在运行中真实 steer,验证灰态终态不变式(用户的话绝不静默蒸发)。
 *
 * <p><b>覆盖契约</b>(task-11-brief 6 条逐一对应):
 * ① running_textarea_enabled_send_steers(RUNNING 解锁输入、回车走 /steer 非 /stream、灰态出现、
 * 工具栏/侧栏仍锁、badge 可见);② steerAck_promotes_gray_bubble(INJECTED 转正 + legacy 无
 * status 默认转正 + NOT_INJECTED 终态);③ queued_false_waits_local_complete_then_sends
 * (#6 回退纪律:不抢 register,本地流 complete 后才普通发送);④ steering_html_escaped
 * (灰态气泡 textContent 转义,Review Focus 1);⑤ isStreaming_guards_still_block_switch
 * (A2:isStreaming 语义不变,8 消费点守卫仍生效);⑥ stop_marks_gray_unsent(A2 可见终态 +
 * stop 抑制回退);⑦ forbidden 403 → 按 queued:false 回退(R20 TOCTOU);429 深度上限 →
 * toast + 文本回输入框。
 */
@DisplayName("Task 11 前端输入三态机:steering 发送 + 灰态排队气泡 + 回退纪律")
class SteeringInputBrowserIT extends BrowserTestBase {

    private static final Page.WaitForFunctionOptions WF_5S =
            new Page.WaitForFunctionOptions().setTimeout(5_000).setPollingInterval(100);
    private static final Page.WaitForFunctionOptions WF_10S =
            new Page.WaitForFunctionOptions().setTimeout(10_000).setPollingInterval(100);

    /** steer 端点脚本:HTTP status + body 模板({{n}} 按请求序号展开)。 */
    private static final class SteerScript {
        volatile int status = 200;
        volatile String body = "{\"queued\":true,\"messageId\":\"m-{{n}}\"}";
        final AtomicInteger seq = new AtomicInteger(0);
    }

    /** 打开聊天页并等 app.js 模块就绪 + 会话列表落地(镜像 HttpToolBrowserIT.openChatReady)。 */
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

    /** 扣住聊天流 POST /stream:请求入队、不 fulfill ⇒ 前端保持 RUNNING,由测试手动收尾。 */
    private static BlockingQueue<Route> holdStreams(Page page) {
        BlockingQueue<Route> q = new LinkedBlockingQueue<>();
        page.route("**/spring/ai/loom/stream", q::add);
        return q;
    }

    /** 拦截 steer(不关心 URL 的用例用这个)。 */
    private static List<String> steerBodies(Page page, SteerScript script) {
        return steerBodies(page, script, Collections.synchronizedList(new ArrayList<>()));
    }

    /** 拦截 steer:fulfill 脚本响应并记录 body;urlSink 顺带收集请求 URL(归属断言用)。 */
    private static List<String> steerBodies(Page page, SteerScript script, List<String> urlSink) {
        List<String> captured = Collections.synchronizedList(new ArrayList<>());
        page.route("**/steer", route -> {
            urlSink.add(route.request().url());
            captured.add(route.request().postData());
            int n = script.seq.incrementAndGet();
            String body = script.body.replace("{{n}}", String.valueOf(n));
            route.fulfill(new Route.FulfillOptions()
                    .setStatus(script.status)
                    .setContentType("application/json; charset=UTF-8")
                    .setBody(body));
        });
        return captured;
    }

    private static void mockStopOk(Page page) {
        page.route("**/stop", route -> route.fulfill(new Route.FulfillOptions()
                .setStatus(200).setContentType("application/json; charset=UTF-8")
                .setBody("{\"stopped\":true}")));
    }

    /** 用预编排 SSE 帧收尾一个被扣住的流;帧 JSON 原样进 data: 行(与后端 ChatResponseRecord 形态一致)。 */
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

    /**
     * 等一个被扣住的 Route。不能用裸 {@code queue.poll(ms)}:Playwright Java 的
     * route 事件投递依赖测试线程回到 Playwright 调用(内部 dispatcher 泵);
     * 裸阻塞等待会让"浏览器早已发出的请求"在超时前一直不被 handler 接收
     * (③/⑦ 首版即栽在这:补发的第二条 /stream 实际 ~0ms 就发出去了,
     * 但 route 事件直到 poll 超时才入队)。用 waitForTimeout 切片轮询,
     * 每个切片都是一次 Playwright 调用 ⇒ 泵送事件循环。
     */
    private static Route awaitRoute(BlockingQueue<Route> q, Page page, long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            Route r = q.poll();
            if (r != null) return r;
            page.waitForTimeout(50);
        }
        return q.poll();
    }

    /** 等状态机进入 IDLE(流正常结束的通用收尾等待)。 */
    private void waitIdle(Page page, Page.WaitForFunctionOptions opts) {
        page.waitForFunction(
                "() => !window._loomAgent.state.isStreaming"
                        + " && window._loomAgent.state.inputMode === 'IDLE'", null, opts);
    }

    // ==================== ① RUNNING 解锁输入 + 回车走 steer ====================

    @Test
    @DisplayName("RUNNING 态 textarea/send-btn 解锁;回车 → POST /steer → 灰态气泡出现(/stream 不重发)")
    void running_textarea_enabled_send_steers() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            SteerScript script = new SteerScript();
            List<String> steerUrls = Collections.synchronizedList(new ArrayList<>());
            List<String> steerReqs = steerBodies(page, script, steerUrls);
            newConversation(page);
            String conv = convId(page);            page.fill("#textarea", "第一条消息");
            page.click("#send-btn");
            Route run1 = awaitRoute(streams, page, 5_000);
            assertThat(run1).as("聊天流 POST /stream 已发出并被扣住(= RUNNING)").isNotNull();

            // RUNNING:输入区解锁(G1),按钮文案不变(§5.5 裁定),badge 旁挂提示
            assertThat((Boolean) page.evaluate("() => !document.getElementById('textarea').disabled"))
                    .as("RUNNING 态 textarea 不锁定").isTrue();
            assertThat((Boolean) page.evaluate("() => !document.getElementById('send-btn').disabled"))
                    .as("RUNNING 态 send-btn 不锁定").isTrue();
            assertThat((String) page.evaluate("() => document.getElementById('send-btn').textContent.trim()"))
                    .as("按钮文案保持『发送消息』(结束信号兼容)").isEqualTo("发送消息");
            assertThat(page.evaluate("() => window._loomAgent.state.inputMode"))
                    .as("输入三态机进入 RUNNING").isEqualTo("RUNNING");
            assertThat((Boolean) page.evaluate("() => window._loomAgent.state.isStreaming"))
                    .as("isStreaming 语义不变:RUNNING 时仍为 true").isTrue();
            assertThat(page.isVisible("#steering-badge"))
                    .as("运行中 badge 可见").isTrue();
            // 工具栏/侧栏 P1 保留锁定(N3/N4)
            assertThat((Boolean) page.evaluate("() => document.getElementById('new-chat-btn').disabled"))
                    .as("RUNNING 态新对话按钮仍锁定").isTrue();
            assertThat((Boolean) page.evaluate(
                    "() => document.getElementById('sidebarList').classList.contains('sidebar-locked')"))
                    .as("RUNNING 态侧栏仍锁定").isTrue();

            // 回车插话 → /steer(不是第二条 /stream)
            page.fill("#textarea", "补充:先看重点");
            page.press("#textarea", "Enter");
            page.waitForFunction(
                    "() => document.querySelector('.chat-item-queued[data-message-id=\"m-1\"]') !== null",
                    null, WF_5S);

            assertThat(steerReqs).as("恰有一条 steer 请求").hasSize(1);
            assertThat(steerReqs.get(0)).as("steer body 携带纯文本 message").contains("补充:先看重点");
            assertThat(steerUrls.get(0))
                    .as("steer 归属当前会话(URL 含 /stream/{conv}/steer)")
                    .contains("/spring/ai/loom/stream/" + conv + "/steer");
            assertThat((String) page.evaluate("() => document.querySelector("
                    + "'.chat-item-queued[data-message-id=\"m-1\"] .queued-body').textContent"))
                    .as("灰态气泡文本 = 插话内容").isEqualTo("补充:先看重点");
            assertThat((String) page.inputValue("#textarea")).as("插话后 textarea 已清空").isEmpty();
            assertThat(streams.size())
                    .as("steering 不触发普通 chat.send(第二条 /stream 不得出现)").isZero();

            // 收尾:流结束且无 steerAck → 灰态必须转可见终态(R20 兜底),状态机回 IDLE
            fulfillSse(run1, "{\"content\":\"好的\"}");
            waitIdle(page, WF_10S);
            assertThat(page.evaluate(
                    "() => document.querySelector('[data-message-id=\"m-1\"]').dataset.steerState"))
                    .as("流结束时无 ack 的灰态 → 未发送终态(A2 可见,不静默蒸发)")
                    .isEqualTo("unsent");
            assertThat(page.isVisible("#steering-badge")).as("结束后 badge 隐藏").isFalse();

            assertThat(consoleErrorsOf(page)).as("全程无 JS console error").isEmpty();
        }
    }

    // ==================== ② steerAck 帧驱动灰态转正 / 未注入终态 ====================

    @Test
    @DisplayName("steerAck:INJECTED(含 legacy 无 status)转正;NOT_INJECTED → 未发送终态")
    void steerAck_promotes_gray_bubble() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            steerBodies(page, new SteerScript()); // queued:true,m-1 / m-2
            newConversation(page);

            page.fill("#textarea", "第一条");
            page.click("#send-btn");
            Route run1 = awaitRoute(streams, page, 5_000);
            assertThat(run1).isNotNull();

            page.fill("#textarea", "插话一(将转正)");
            page.press("#textarea", "Enter");
            page.waitForSelector(".chat-item-queued[data-message-id=\"m-1\"]",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
            page.fill("#textarea", "插话二(将标未发送)");
            page.press("#textarea", "Enter");
            page.waitForSelector(".chat-item-queued[data-message-id=\"m-2\"]",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));

            // m-1:legacy 帧形态(1-arg 构造器 → 序列化默认 status=INJECTED;此处直接省略 status 验证前端默认)
            // m-2:显式 NOT_INJECTED
            fulfillSse(run1,
                    "{\"content\":\"收到补充\"}",
                    "{\"steerAck\":{\"messageId\":\"m-1\"}}",
                    "{\"steerAck\":{\"messageId\":\"m-2\",\"status\":\"NOT_INJECTED\"}}");
            waitIdle(page, WF_10S);

            assertThat(page.evaluate("() => { const el=document.querySelector('[data-message-id=\"m-1\"]');"
                    + " return el && el.dataset.steerState === 'injected'"
                    + " && !el.classList.contains('chat-item-queued')"
                    + " && el.querySelector('.queued-note') === null; }"))
                    .as("INJECTED(含 legacy 缺省 status)→ 灰态转正(去灰 class、去排队标注)")
                    .isEqualTo(true);
            assertThat((String) page.evaluate("() => document.querySelector('[data-message-id=\"m-1\"] .queued-body').textContent"))
                    .as("转正后气泡文本保留").isEqualTo("插话一(将转正)");
            assertThat(page.evaluate("() => { const el=document.querySelector('[data-message-id=\"m-2\"]');"
                    + " return el && el.dataset.steerState === 'unsent'"
                    + " && el.querySelector('.queued-note').textContent.includes('未发送'); }"))
                    .as("NOT_INJECTED → 未发送可见终态")
                    .isEqualTo(true);

            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    // ==================== ③ queued:false → 等本地流 complete 再普通发送(#6) ====================

    @Test
    @DisplayName("steer 返回 queued:false:不立即抢发,等本地流 complete 后 chat.send 补发(#6)")
    void queued_false_waits_local_complete_then_sends() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            SteerScript script = new SteerScript();
            script.body = "{\"queued\":false}";
            List<String> steerReqs = steerBodies(page, script);
            newConversation(page);

            page.fill("#textarea", "第一条");
            page.click("#send-btn");
            Route run1 = awaitRoute(streams, page, 5_000);
            assertThat(run1).isNotNull();

            page.fill("#textarea", "收尾时补发我");
            page.press("#textarea", "Enter");
            // 等待 steer 响应已落地:灰态回退气泡出现(data-steer-state=fallback)= 前端已处理 queued:false
            page.waitForSelector(".chat-item-queued[data-steer-state=\"fallback\"]",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));

            // #6 纪律:此刻本地流未结束,绝不能抢发普通 /stream
            assertThat(streams.size())
                    .as("queued:false 不得立即触发普通 chat.send(本地流还在跑)").isZero();
            // 短暂等待后再确认(排除"其实很快会补发"的侥幸路径 —— 补发只能由 complete 事件驱动)
            page.waitForTimeout(500);
            assertThat(streams.size()).as("补发窗口未到仍无第二条 /stream").isZero();

            // 本地流 complete → enableSend 兜底补发(#6:补发只由 complete 事件驱动)
            fulfillSse(run1, "{\"content\":\"好的\"}");
            Route run2 = awaitRoute(streams, page, 5_000);
            assertThat(run2)
                    .as("本地流结束后补发普通 /stream(streamQueue=%d, console=%s)",
                            streams.size(), consoleErrorsOf(page))
                    .isNotNull();
            assertThat(run2.request().postData())
                    .as("补发的是插话文本").contains("收尾时补发我");
            assertThat(page.locator(".chat-item-queued[data-steer-state=\"fallback\"]").count())
                    .as("补发后灰态回退气泡被普通用户气泡取代").isZero();

            fulfillSse(run2, "{\"content\":\"收到补发\"}");
            waitIdle(page, WF_10S);
            assertThat(steerReqs).hasSize(1);
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    // ==================== ④ 灰态气泡 XSS 转义(Review Focus 1) ====================

    @Test
    @DisplayName("插话含 HTML → 灰态气泡 textContent 转义,无活动 img / 无 alert(Review Focus 1)")
    void steering_html_escaped() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            AtomicBoolean dialogFired = new AtomicBoolean(false);
            page.onDialog(d -> {
                dialogFired.set(true);
                try { d.dismiss(); } catch (Exception ignored) {}
            });
            BlockingQueue<Route> streams = holdStreams(page);
            steerBodies(page, new SteerScript());
            newConversation(page);

            page.fill("#textarea", "第一条");
            page.click("#send-btn");
            Route run1 = awaitRoute(streams, page, 5_000);
            assertThat(run1).isNotNull();

            String payload = "<img src=x onerror=alert(1)>";
            page.fill("#textarea", payload);
            page.press("#textarea", "Enter");
            page.waitForSelector(".chat-item-queued[data-message-id=\"m-1\"]",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));

            assertThat((Boolean) page.evaluate(
                    "() => { const b=document.querySelector('[data-message-id=\"m-1\"] .bubble');"
                            + " return b.querySelector('img') === null && !b.innerHTML.includes('<img'); }"))
                    .as("灰态气泡内不得出现活动 <img>(innerHTML 只应有 &lt;img 转义文本)")
                    .isTrue();
            assertThat((String) page.evaluate(
                    "() => document.querySelector('[data-message-id=\"m-1\"] .queued-body').textContent"))
                    .as("textContent 原样保留载荷文本(所见=所发)")
                    .isEqualTo(payload);

            page.waitForTimeout(300); // onerror 若被执行,dialog 早已弹出
            assertThat(dialogFired.get()).as("steering 载荷中的 onerror 未被执行").isFalse();

            fulfillSse(run1, "{\"content\":\"ok\"}");
            waitIdle(page, WF_10S);
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    // ==================== ⑤ isStreaming 守卫不变(RUNNING 仅解锁输入) ====================

    @Test
    @DisplayName("RUNNING 下切换/新建会话守卫仍生效(isStreaming 语义不变,A2);仅输入区解锁")
    void isStreaming_guards_still_block_switch() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            steerBodies(page, new SteerScript());
            newConversation(page);
            newConversation(page); // 两个会话:current=第二个,other=第一个

            page.fill("#textarea", "第一条");
            page.click("#send-btn");
            Route run1 = awaitRoute(streams, page, 5_000);
            assertThat(run1).isNotNull();
            String current = convId(page);
            String other = (String) page.evaluate(
                    "(c) => [...document.querySelectorAll('#sidebarList .sidebar-item')]"
                            + ".map(i => i.dataset.conversationId).find(id => id && id !== c)", current);
            assertThat(other).as("侧栏应存在另一会话供切换尝试").isNotNull();

            // 直调 .click()(绕开 sidebar-locked 的 pointer-events,验的是 JS 守卫本身)
            Object r = page.evaluate(
                    "(id) => window._loomAgent.conversation.switchTo(id).then(() =>"
                            + " window._loomAgent.state.conversationId)", other);
            assertThat((String) r).as("RUNNING 下 switchTo 仍被 isStreaming 守卫拦截").isEqualTo(current);
            assertThat((Boolean) page.evaluate("() => window._loomAgent.state.isStreaming"))
                    .as("守卫拦截不影响 isStreaming(语义仍=有活跃流)").isTrue();
            assertThat((Boolean) page.evaluate(
                    "() => document.getElementById('toast-notification').textContent.includes('请等待 AI 回复完成')"))
                    .as("拦截有 toast 提示").isTrue();

            page.evaluate("() => window._loomAgent.conversation.createNew()");
            assertThat(convId(page)).as("RUNNING 下 createNew 仍被守卫拦截").isEqualTo(current);

            // 输入区守卫分工:RUNNING 下 textarea 可用,但空文本 Enter = 静默 no-op(不发 steer 请求)
            assertThat((Boolean) page.evaluate("() => !document.getElementById('textarea').disabled"))
                    .as("RUNNING 仅解锁输入区").isTrue();
            assertThat(page.evaluate("() => window._loomAgent.state.inputMode"))
                    .isEqualTo("RUNNING");
            page.evaluate("() => { document.getElementById('textarea').value = ''; }");
            page.press("#textarea", "Enter");
            page.waitForTimeout(400);
            assertThat(streams.size()).as("空插话不得触发普通发送").isZero();
            assertThat(page.locator(".chat-item-queued").count())
                    .as("空插话静默 no-op:不产生灰态气泡").isZero();

            fulfillSse(run1, "{\"content\":\"ok\"}");
            waitIdle(page, WF_10S);
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    // ==================== ⑥ stop → 灰态标未发送 + 抑制回退(#6) ====================

    @Test
    @DisplayName("有灰态时点 stop → 全部标『未发送(已停止)』;queued:false 回退被抑制(stop 意图优先)")
    void stop_marks_gray_unsent() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            SteerScript script = new SteerScript();
            steerBodies(page, script);
            mockStopOk(page);
            newConversation(page);

            page.fill("#textarea", "第一条");
            page.click("#send-btn");
            Route run1 = awaitRoute(streams, page, 5_000);
            assertThat(run1).isNotNull();

            // 灰态 1:服务端已排队(等 ack,永不到来)
            script.body = "{\"queued\":true,\"messageId\":\"m-stop\"}";
            page.fill("#textarea", "插话进灰态");
            page.press("#textarea", "Enter");
            page.waitForSelector(".chat-item-queued[data-message-id=\"m-stop\"]",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));

            // 灰态 2:本地回退排队(queued:false → 本应等 complete 补发)
            script.body = "{\"queued\":false}";
            page.fill("#textarea", "回退将被抑制");
            page.press("#textarea", "Enter");
            page.waitForSelector(".chat-item-queued[data-steer-state=\"fallback\"]",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));

            page.click("#stop-btn");
            page.waitForFunction(
                    "() => document.querySelectorAll('.chat-item-queued').length === 0"
                            + " && document.querySelectorAll('.chat-item-unsent[data-steer-state=\"unsent\"]').length === 2",
                    null, WF_10S);
            assertThat(page.textContent(
                    ".chat-item-unsent[data-message-id=\"m-stop\"] .queued-note"))
                    .as("stop 终态标注可见").contains("未发送(已停止)");

            waitIdle(page, WF_10S);
            page.waitForTimeout(500);
            assertThat(streams.size())
                    .as("stop 后 queued:false 回退被抑制,绝不补发(#6 stop 意图优先)").isZero();
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    // ==================== ⑦ 403 TOCTOU → 按 queued:false 回退(R20) ====================

    @Test
    @DisplayName("steer 403(R20 TOCTOU)→ 按 queued:false 回退,本地 complete 后补发,不做硬错误死胡同")
    void forbidden403_fallsBackLikeQueuedFalse() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            SteerScript script = new SteerScript();
            script.status = 403;
            script.body = "{\"error\":\"forbidden\",\"code\":403}";
            steerBodies(page, script);
            newConversation(page);

            page.fill("#textarea", "第一条");
            page.click("#send-btn");
            Route run1 = awaitRoute(streams, page, 5_000);
            assertThat(run1).isNotNull();

            page.fill("#textarea", "新会话竞态插话");
            page.press("#textarea", "Enter");
            page.waitForSelector(".chat-item-queued[data-steer-state=\"fallback\"]",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
            assertThat(streams.size()).as("403 同样等 complete,不抢发").isZero();

            fulfillSse(run1, "{\"content\":\"好的\"}");
            Route run2 = awaitRoute(streams, page, 5_000);
            assertThat(run2).as("403 回退后照常补发").isNotNull();
            assertThat(run2.request().postData()).contains("新会话竞态插话");

            fulfillSse(run2, "{\"content\":\"收到\"}");
            waitIdle(page, WF_10S);
            // 过滤 "Failed to load resource … 403":浏览器对 mock fulfill 的非 2xx 状态码打的
            // 网络层日志(HTTP 状态噪音,非 JS 异常),按前缀过滤而非放宽断言(同 ⑧/ChatSmoke 约定)。
            assertThat(consoleErrorsOf(page))
                    .as("除 403 网络状态日志外无 JS console error")
                    .noneMatch(e -> !e.startsWith("Failed to load resource"));
        }
    }

    // ==================== ⑧ 429 深度上限 → toast + 文本回输入框 ====================

    @Test
    @DisplayName("steer 429(inbox 深度上限)→ toast 提示 + 文本恢复进 textarea(不蒸发)")
    void tooMany_429_restoresTextToTextarea() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            SteerScript script = new SteerScript();
            script.status = 429;
            script.body = "{\"error\":\"补充消息过多,请等待 AI 处理\",\"code\":429}";
            steerBodies(page, script);
            newConversation(page);

            page.fill("#textarea", "第一条");
            page.click("#send-btn");
            Route run1 = awaitRoute(streams, page, 5_000);
            assertThat(run1).isNotNull();

            page.fill("#textarea", "刷屏插话");
            page.press("#textarea", "Enter");
            page.waitForFunction(
                    "() => document.getElementById('toast-notification').textContent.includes('补充消息过多')",
                    null, WF_5S);
            assertThat((String) page.inputValue("#textarea"))
                    .as("429 后文本恢复进输入框,用户可稍后重试").isEqualTo("刷屏插话");
            assertThat(page.locator(".chat-item-queued").count())
                    .as("未入队的 429 不渲染灰态气泡").isZero();

            fulfillSse(run1, "{\"content\":\"好的\"}");
            waitIdle(page, WF_10S);
            assertThat(streams.size()).isZero();
            // 过滤 "Failed to load resource … 429":这是浏览器对 mock fulfill 的 429 状态码打的
            // 网络层日志(HTTP 状态噪音,非 JS 异常),按前缀过滤而非放宽断言(同 ChatSmoke 约定)。
            assertThat(consoleErrorsOf(page))
                    .as("除 429 网络状态日志外无 JS console error")
                    .noneMatch(e -> !e.startsWith("Failed to load resource"));
        }
    }

    // ==================== ⑨ 真实链路:真实 run 中真实 steer(无 route mock) ====================

    @Test
    @DisplayName("真实 LLM 多迭代 run 中回车插话:200 应答 + 灰态在流结束前必有可见终态(A2 不蒸发)")
    void realRunSteeringKeepsUserTextVisible() {
        assumeTrue(System.getenv("NEW_API_KEY") != null && !System.getenv("NEW_API_KEY").isBlank(),
                "跳过:NEW_API_KEY 未设置(真实模型中转站不可用)");
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx); // 无任何 route 拦截:全真实
            newConversation(page);

            // 多迭代任务(强制 ≥2 次工具调用,保证插话窗口)
            page.fill("#textarea",
                    "请依次调用 get_current_time 工具获取当前时间和东京时间(分两次调用),"
                            + "然后各写一句点评,最后用一句话总结。");
            page.click("#send-btn");
            // 开始信号(§5.5 裁定新契约):stop-btn 可见 + isStreaming
            page.waitForFunction(
                    "() => { const t=document.getElementById('stop-btn');"
                            + " return t && t.style.display !== 'none'"
                            + " && window._loomAgent && window._loomAgent.state.isStreaming === true; }",
                    null, WF_10S);

            String steerText = "插话:请最后只回复 收到插话 四个字";
            page.fill("#textarea", steerText);
            // 真实 steer 端点应答(RUNNING 必有活跃 run → 200;queued true/false 均可)。
            // Enter 放进 action Runnable:先挂响应监听再触发请求,杜绝响应早于监听到达的竞态。
            com.microsoft.playwright.Response steerResp = page.waitForResponse(
                    r -> r.url().contains("/steer"),
                    new Page.WaitForResponseOptions().setTimeout(30_000),
                    () -> page.press("#textarea", "Enter"));
            assertThat(steerResp.status()).as("真实 steer 端点应答").isEqualTo(200);

            // 等全部轮次(含可能的回退补发轮)结束:IDLE + 不再存在任何灰态等待气泡
            page.waitForFunction(
                    "() => !window._loomAgent.state.isStreaming"
                            + " && window._loomAgent.state.inputMode === 'IDLE'"
                            + " && document.querySelectorAll('.chat-item-queued').length === 0",
                    null, new Page.WaitForFunctionOptions().setTimeout(240_000).setPollingInterval(1_000));

            // A2 不变式:用户插话文本在 DOM 里可见(转正气泡 / 补发用户气泡 / unsent 终态三选一,都留在 DOM)
            assertThat((Boolean) page.evaluate(
                    "(t) => document.getElementById('mainContent').innerText.includes(t)", steerText))
                    .as("插话文本在流结束后仍在聊天 DOM(绝不静默蒸发)")
                    .isTrue();
            assertThat(consoleErrorsOf(page)).as("真实 steering 链路无 JS console error").isEmpty();
        }
    }
}
