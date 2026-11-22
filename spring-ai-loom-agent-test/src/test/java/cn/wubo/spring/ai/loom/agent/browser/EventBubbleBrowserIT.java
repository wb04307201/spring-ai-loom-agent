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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 12(spec 2026-11-21 agentic-loop-steering)事件驱动气泡拆分 + 迭代分段 BrowserIT(G4)。
 *
 * <p><b>裁定(G4)</b>:只有用户插话(steerAck INJECTED)才拆新 AI 气泡;纯迭代续跑
 * (iteration 帧 index&gt;0)在<b>同一气泡</b>内画轻量分隔线 + 开新回答/思考分段,不开新气泡。
 *
 * <p><b>测试策略</b>:与 SteeringInputBrowserIT 同 —— route 拦截 POST /stream 扣住不 fulfill
 * (前端稳定停在 RUNNING),断言窗口内由测试线程手动 fulfill 预编排 SSE 帧。帧 JSON 形态与后端
 * {@code ChatResponseRecord} 序列化一致:{@code {"iteration":{"index":1}}}(IterationEvent 便捷
 * 构造器,其余分量 null;iteration 0 后端根本不发 —— beginIteration idx&gt;0 门控)、
 * {@code {"steerAck":{"messageId":"m-1","status":"INJECTED"}}}(1-arg legacy 缺 status 同 INJECTED)。
 * 真实 LLM 多迭代时序不可控,分段契约用 mock 帧锁死;真实链路冒烟由 ChatSmokeBrowserIT 兜底。
 *
 * <p><b>awaitRoute(R26)</b>:route 事件投递依赖测试线程回到 Playwright 调用,必须用
 * waitForTimeout 切片泵送,不能裸 queue.poll(否则假超时)。
 *
 * <p>覆盖契约(task-12-brief Step 1 四条 + A1 不变式):
 * ① steering_splits_new_ai_bubble —— ack INJECTED 后旧气泡封口(actions 落地)+ 灰态转正用户气泡
 * 夹在中间(时序 = 旧 AI → 插话 → 新 AI),新气泡只含插话后文本;NOT_INJECTED 不拆。
 * ② pure_iteration_same_bubble_segment —— 同气泡 1 个分隔线 + 独立回答分段,气泡数不变,
 * origin(bubble 级复制载荷)= 两段全文;
 * ③ thinking_segment_per_iteration —— 2 迭代 2 个思考折叠分段,内容互不合并;
 * ④ tool_chips_stay_in_correct_bubble —— 插话前子任务 chip 留旧气泡,插话后 chip 进新气泡;
 * ⑤ a1_no_steering_single_iteration_unchanged —— 无插话单迭代:1 气泡 / 0 分隔线 /
 * 0 迭代分段 / 思考容器仍是 renderBotMessage 那一个(与 Task 12 前逐字节同构)。
 */
@DisplayName("Task 12 事件驱动气泡:steerAck 拆气泡 + iteration 同气泡分段 + 思考按迭代分段")
class EventBubbleBrowserIT extends BrowserTestBase {

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

    /** 扣住聊天流 POST /stream:请求入队、不 fulfill ⇒ 前端保持 RUNNING,由测试手动收尾。 */
    private static BlockingQueue<Route> holdStreams(Page page) {
        BlockingQueue<Route> q = new LinkedBlockingQueue<>();
        page.route("**/spring/ai/loom/stream", q::add);
        return q;
    }

    private static void steerBodies(Page page, SteerScript script) {
        page.route("**/steer", route -> {
            int n = script.seq.incrementAndGet();
            String body = script.body.replace("{{n}}", String.valueOf(n));
            route.fulfill(new Route.FulfillOptions()
                    .setStatus(script.status)
                    .setContentType("application/json; charset=UTF-8")
                    .setBody(body));
        });
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
     * 等一个被扣住的 Route(Playwright Java 的 route 事件投递依赖测试线程回到 Playwright
     * 调用,裸 poll 会假超时 —— 用 waitForTimeout 切片泵送,R26 裁定)。
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

    private void waitIdle(Page page, Page.WaitForFunctionOptions opts) {
        page.waitForFunction(
                "() => !window._loomAgent.state.isStreaming"
                        + " && window._loomAgent.state.inputMode === 'IDLE'", null, opts);
    }

    /** 启动一条被扣住的流,返回被扣的 Route(第一条消息已由测试填好). */
    private Route startHeldRun(Page page, BlockingQueue<Route> streams, String firstText) {
        page.fill("#textarea", firstText);
        page.click("#send-btn");
        Route run = awaitRoute(streams, page, 5_000);
        assertThat(run).as("聊天流 POST /stream 已发出并被扣住(= RUNNING)").isNotNull();
        return run;
    }

    // ==================== ① steerAck INJECTED → 事件驱动气泡拆分 ====================

    @Test
    @DisplayName("steerAck INJECTED:旧气泡封口 + 转正用户气泡夹中间 + 新气泡只含插话后文本;NOT_INJECTED 不拆")
    void steering_splits_new_ai_bubble() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            steerBodies(page, new SteerScript()); // m-1 / m-2
            newConversation(page);

            Route run1 = startHeldRun(page, streams, "第一条消息");
            page.fill("#textarea", "插话转正");
            page.press("#textarea", "Enter");
            page.waitForSelector(".chat-item-queued[data-message-id=\"m-1\"]",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));

            // 插话前内容 → 旧气泡;ack INJECTED → 封口 + 拆分;之后内容 → 新气泡
            fulfillSse(run1,
                    "{\"content\":\"插话前的回答\"}",
                    "{\"steerAck\":{\"messageId\":\"m-1\",\"status\":\"INJECTED\"}}",
                    "{\"content\":\"插话后的回答\"}");
            waitIdle(page, WF_10S);

            // 两个 AI 气泡 + 时序:旧 AI → 转正用户气泡 → 新 AI(新气泡 append 恰在转正气泡之后)
            assertThat(page.locator(".chat-item-left").count())
                    .as("插话拆分出第二个 AI 气泡").isEqualTo(2);
            Boolean timeline = (Boolean) page.evaluate(
                    "() => { const items=[...document.getElementById('mainContent').children];"
                            + " const u=items.findIndex(i=>i.getAttribute('data-message-id')==='m-1');"
                            + " const ais=items.filter(i=>i.classList.contains('chat-item-left'));"
                            + " return u > items.indexOf(ais[0]) && u < items.indexOf(ais[1]); }");
            assertThat(timeline).as("时间线 = 旧 AI → 用户插话(转正)→ 新 AI").isTrue();
            assertThat(page.evaluate(
                    "() => { const el=document.querySelector('[data-message-id=\"m-1\"]');"
                            + " return el.dataset.steerState === 'injected'; }"))
                    .as("灰态气泡已转正(Task 11 机制保持)").isEqualTo(true);

            // 文本按气泡隔离:旧气泡只有插话前文本,新气泡只有插话后文本(分段累加器随拆分复位)
            Boolean isolated = (Boolean) page.evaluate(
                    "() => { const ais=[...document.querySelectorAll('.chat-item-left')];"
                            + " return ais[0].innerText.includes('插话前的回答')"
                            + " && !ais[0].innerText.includes('插话后的回答')"
                            + " && ais[1].innerText.includes('插话后的回答')"
                            + " && !ais[1].innerText.includes('插话前的回答'); }");
            assertThat(isolated).as("插话前后文本分属两个气泡,互不混排").isTrue();

            // 封口:旧气泡操作栏落地(拆分时刻),新气泡操作栏流结束落地
            Boolean sealed = (Boolean) page.evaluate(
                    "() => { const ais=[...document.querySelectorAll('.chat-item-left')];"
                            + " const act=(b)=>{const a=b.querySelector('.bubble-actions');return a && a.style.display!=='none';};"
                            + " return act(ais[0]) && act(ais[1]); }");
            assertThat(sealed).as("旧气泡封口(操作栏落地)+ 新气泡流结束也显示操作栏").isTrue();

            // 复制载荷按气泡隔离:各自 origin 只装本气泡文本
            Boolean originSplit = (Boolean) page.evaluate(
                    "() => { const ais=[...document.querySelectorAll('.chat-item-left')];"
                            + " const o0=ais[0].querySelector('[id^=\"origin-\"]').textContent;"
                            + " const o1=ais[1].querySelector('[id^=\"origin-\"]').textContent;"
                            + " return o0.includes('插话前的回答') && !o0.includes('插话后的回答')"
                            + " && o1.includes('插话后的回答') && !o1.includes('插话前的回答'); }");
            assertThat(originSplit).as("bubble 级复制载荷 = 各自气泡全文(不跨气泡)").isTrue();

            // 负断言:NOT_INJECTED(未注入终态)绝不拆气泡
            Route run2 = startHeldRun(page, streams, "第二条消息");
            page.fill("#textarea", "插话不注入");
            page.press("#textarea", "Enter");
            page.waitForSelector(".chat-item-queued[data-message-id=\"m-2\"]",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
            fulfillSse(run2,
                    "{\"content\":\"正常回答\"}",
                    "{\"steerAck\":{\"messageId\":\"m-2\",\"status\":\"NOT_INJECTED\"}}",
                    "{\"content\":\"继续正常回答\"}");
            waitIdle(page, WF_10S);
            assertThat(page.locator(".chat-item-left").count())
                    .as("NOT_INJECTED 不拆气泡:仍只有 run1 的 2 个 + run2 的 1 个").isEqualTo(3);
            Boolean notSplit = (Boolean) page.evaluate(
                    "() => { const ais=[...document.querySelectorAll('.chat-item-left')];"
                            + " const last=ais[ais.length-1];"
                            + " return last.innerText.includes('正常回答') && last.innerText.includes('继续正常回答'); }");
            assertThat(notSplit).as("NOT_INJECTED 后内容仍进同一气泡").isTrue();

            assertThat(consoleErrorsOf(page)).as("全程无 JS console error").isEmpty();
        }
    }

    // ==================== ② 纯迭代 → 同气泡分段,不开新气泡 ====================

    @Test
    @DisplayName("iteration 帧(无插话):同一气泡内 1 条分隔线 + 独立回答分段,气泡数不变;index=0 不分段")
    void pure_iteration_same_bubble_segment() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            newConversation(page);

            Route run1 = startHeldRun(page, streams, "跑个多轮任务");
            // index 0 = 首轮,后端不发(Task 6 beginIteration idx>0 门控),前端防御:发了也不分段
            fulfillSse(run1,
                    "{\"iteration\":{\"index\":0}}",
                    "{\"content\":\"第一段回答\"}",
                    "{\"iteration\":{\"index\":1}}",
                    "{\"content\":\"第二段回答\"}");
            waitIdle(page, WF_10S);

            assertThat(page.locator(".chat-item-left").count())
                    .as("G4 裁定:纯迭代只分段,绝不拆新气泡").isEqualTo(1);
            assertThat(page.locator(".iteration-divider").count())
                    .as("恰 1 条迭代分隔线(index 0 帧不分段)").isEqualTo(1);
            assertThat(page.locator(".iteration-segment").count())
                    .as("第 2 迭代开独立回答分段").isEqualTo(1);

            Boolean segments = (Boolean) page.evaluate(
                    "() => { const b=document.querySelector('.chat-item-left .bubble');"
                            + " const base=b.querySelector('.bot-content:not(.iteration-segment)');"
                            + " const seg=b.querySelector('.iteration-segment');"
                            + " const order = b.compareDocumentPosition(seg) & Node.DOCUMENT_POSITION_FOLLOWING;"
                            + " return base.innerText.includes('第一段回答') && !base.innerText.includes('第二段回答')"
                            + " && seg.innerText.includes('第二段回答') && !!order; }");
            assertThat(segments).as("两段文本分属两个分段元素,分段在基准内容之后").isTrue();

            // bubble 级操作栏复制整气泡文本(origin = 两段全文拼接)
            String origin = (String) page.evaluate(
                    "() => document.querySelector('.chat-item-left [id^=\"origin-\"]').textContent");
            assertThat(origin).as("复制载荷 = bubble 全文").contains("第一段回答").contains("第二段回答");

            assertThat(page.locator(".chat-item-queued").count()).isZero();
            assertThat(consoleErrorsOf(page)).as("全程无 JS console error").isEmpty();
        }
    }

    // ==================== ③ 思考分段按迭代(A5 不合并) ====================

    @Test
    @DisplayName("2 迭代各有思考 → 同气泡 2 个思考折叠分段,内容互不合并")
    void thinking_segment_per_iteration() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            newConversation(page);

            Route run1 = startHeldRun(page, streams, "先想一想再继续");
            fulfillSse(run1,
                    "{\"reasoningContent\":\"思考一的内容\"}",
                    "{\"content\":\"回答一\"}",
                    "{\"iteration\":{\"index\":1}}",
                    "{\"reasoningContent\":\"思考二的内容\"}",
                    "{\"content\":\"回答二\"}");
            waitIdle(page, WF_10S);

            Boolean twoThinking = (Boolean) page.evaluate(
                    "() => { const b=document.querySelector('.chat-item-left .bubble');"
                            + " const tcs=b.querySelectorAll('.thinking-container');"
                            + " return tcs.length === 2; }");
            assertThat(twoThinking).as("每迭代一个思考折叠分段").isTrue();

            Boolean notMerged = (Boolean) page.evaluate(
                    "() => { const b=document.querySelector('.chat-item-left .bubble');"
                            + " const bodies=b.querySelectorAll('.thinking-body');"
                            + " return bodies[0].innerText.includes('思考一')"
                            + " && !bodies[0].innerText.includes('思考二')"
                            + " && bodies[1].innerText.includes('思考二')"
                            + " && !bodies[1].innerText.includes('思考一'); }");
            assertThat(notMerged).as("思考分段内容互不合并(reasonText 随迭代复位)").isTrue();

            Boolean bothVisible = (Boolean) page.evaluate(
                    "() => { const b=document.querySelector('.chat-item-left .bubble');"
                            + " const tcs=b.querySelectorAll('.thinking-container');"
                            + " return [...tcs].every(t=>t.style.display!=='none'); }");
            assertThat(bothVisible).as("两个思考容器都因收到 reasoning 而可见").isTrue();

            // 分段可交互:toggleThinking 对新分段同样生效(id 唯一)
            Boolean togglable = (Boolean) page.evaluate(
                    "() => { const b=document.querySelector('.chat-item-left .bubble');"
                            + " const tcs=b.querySelectorAll('.thinking-container');"
                            + " const second=tcs[1]; const id=second.id.replace('thinking-','');"
                            + " window.ui.toggleThinking(id);"
                            + " return document.getElementById('thinking-content-'+id).classList.contains('expanded'); }");
            assertThat(togglable).as("第 2 迭代思考折叠区可展开").isTrue();

            assertThat(consoleErrorsOf(page)).as("全程无 JS console error").isEmpty();
        }
    }

    // ==================== ④ 工具 chip 归属正确气泡 ====================

    @Test
    @DisplayName("插话前子任务 chip 留旧气泡,插话后 chip 进新气泡(拆分跟随 _lastBotBubble/重定向清理)")
    void tool_chips_stay_in_correct_bubble() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            steerBodies(page, new SteerScript());
            newConversation(page);

            Route run1 = startHeldRun(page, streams, "带子任务的消息");
            page.fill("#textarea", "插话看 chip");
            page.press("#textarea", "Enter");
            page.waitForSelector(".chat-item-queued[data-message-id=\"m-1\"]",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));

            String chipBefore = "{\"subTaskEvent\":{\"subTaskId\":\"aaaa1111-2222-3333\",\"status\":\"RUNNING\","
                    + "\"prompt\":\"旧气泡子任务\",\"startedAt\":1,\"elapsedMs\":0,\"errorMessage\":null}}";
            String chipAfter = "{\"subTaskEvent\":{\"subTaskId\":\"bbbb4444-5555-6666\",\"status\":\"RUNNING\","
                    + "\"prompt\":\"新气泡子任务\",\"startedAt\":1,\"elapsedMs\":0,\"errorMessage\":null}}";
            fulfillSse(run1,
                    chipBefore,
                    "{\"content\":\"插话前文本\"}",
                    "{\"steerAck\":{\"messageId\":\"m-1\",\"status\":\"INJECTED\"}}",
                    "{\"content\":\"插话后文本\"}",
                    chipAfter);
            waitIdle(page, WF_10S);

            Boolean chipsSeparated = (Boolean) page.evaluate(
                    "() => { const ais=[...document.querySelectorAll('.chat-item-left')];"
                            + " return ais.length === 2"
                            + " && ais[0].querySelector('.subtask-chip[data-sub-task-id=\"aaaa1111-2222-3333\"]') !== null"
                            + " && ais[0].querySelector('.subtask-chip[data-sub-task-id=\"bbbb4444-5555-6666\"]') === null"
                            + " && ais[1].querySelector('.subtask-chip[data-sub-task-id=\"bbbb4444-5555-6666\"]') !== null"
                            + " && ais[1].querySelector('.subtask-chip[data-sub-task-id=\"aaaa1111-2222-3333\"]') === null; }");
            assertThat(chipsSeparated).as("插话前 chip 留旧气泡,插话后 chip 进新气泡,互不串门").isTrue();

            assertThat(consoleErrorsOf(page)).as("全程无 JS console error").isEmpty();
        }
    }

    // ==================== ⑤ A1 不变式:无插话单迭代与 Task 12 前同构 ====================

    @Test
    @DisplayName("A1:无插话单迭代 → 1 气泡 / 0 分隔线 / 0 迭代分段 / 思考容器仍是模板那一个")
    void a1_no_steering_single_iteration_unchanged() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            newConversation(page);

            Route run1 = startHeldRun(page, streams, "普通问题");
            fulfillSse(run1,
                    "{\"reasoningContent\":\"普通思考\"}",
                    "{\"content\":\"普通回答\"}");
            waitIdle(page, WF_10S);

            Boolean unchanged = (Boolean) page.evaluate(
                    "() => document.querySelectorAll('.chat-item-left').length === 1"
                            + " && document.querySelectorAll('.iteration-divider').length === 0"
                            + " && document.querySelectorAll('.iteration-segment').length === 0"
                            + " && (() => { const b=document.querySelector('.chat-item-left .bubble');"
                            + " return b.querySelectorAll('.bot-content').length === 1"
                            + " && b.querySelectorAll('.thinking-container').length === 1; })()");
            assertThat(unchanged).as("单迭代无插话:1 气泡 / 0 分隔线 / 0 分段 / 模板思考容器唯一,与 Task 12 前一致").isTrue();

            // 思考内容正常落入模板容器,回答文本可见
            String thinking = (String) page.evaluate(
                    "() => document.querySelector('.chat-item-left .thinking-body').innerText");
            assertThat(thinking).contains("普通思考");
            assertThat(page.locator(".chat-item-left").first().innerText()).contains("普通回答");

            assertThat(consoleErrorsOf(page)).as("全程无 JS console error").isEmpty();
        }
    }
}
