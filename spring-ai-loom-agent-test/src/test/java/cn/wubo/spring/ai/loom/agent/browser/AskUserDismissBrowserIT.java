package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 13(spec 2026-11-21 agentic-loop-steering)askUser「聊聊这个」即时 dismiss BrowserIT(D-2/G3)。
 *
 * <p><b>测试策略</b>:与 Task 11/12 同 —— route 拦截 POST /stream <b>扣住不 fulfill</b>
 * (前端稳定停在 RUNNING,给卡片交互留确定窗口)。SSE 帧必须由 {@code route.fulfill} 一次性
 * 交付且交付即结束流 —— 而 onComplete 的 {@code cancelAllActive("已结束")} 会立刻冻结所有
 * pending 卡片,"卡片 pending 期间交互"用 fulfillSse 无法构造(真实运行时 askUser 阻塞期间
 * 流是活的)。故卡片事件帧经测试缝 {@code window._loomAgent.askUserCards.render(ev)} 注入
 * —— 这正是生产 onFrame 处理器对 {@code data.askUser} 帧所做的**同一调用**,ev 载荷形状与
 * 后端 AskUserEvent 序列化逐字段一致(questionId/question/header/background/options
 * [{label,description}]/multiSelect/allowCustomInput/timeoutSeconds)。answer 端点
 * (Task 8 DISMISS 契约)、steer 端点、历史 GET 全部 route mock,状态/契约逐条验证。
 *
 * <p><b>覆盖契约</b>(task-13-brief Step 1 六条):
 * ① dismiss_button_freezes_and_returns_running —— ASK_PENDING 锁输入(textarea/send disabled,
 * isStreaming 仍 true,提示条可见含倒计时与定位);点「💬 聊聊这个」→ POST {mode:"DISMISS"}
 * 无 answer 字段 → 200 → 第五终态"💬 转入对话"(卡片隐藏/摘要折叠/提交按钮隐藏)→ 输入经收敛回
 * RUNNING(R27),提示条隐藏;流结束不回退终态。
 * ② dismiss_then_steering_continues —— dismiss 回 RUNNING 后回车 = 普通 steering(G3 衔接:
 * /steer 恰一条、无第二条 /stream、灰态出现并被 ack 转正)。
 * ③ hint_bar_scrolls_to_card —— 提示条点击滚动到最早 pending 卡片(mainContent 溢出场景)。
 * ④ multi_card_stays_pending_until_all_resolved —— 2 卡提示条"2 个问题待回答";dismiss 最早一张
 * 仍 ASK_PENDING(计数 1);另一张走 ANSWER 路径(选项点击)落定后才 RUNNING(B8 + A1 混合)。
 * ⑤ dismiss_double_click_no_double_freeze —— 快速双击 → 恰一次 POST(在飞防抖),一次终态,
 * 无 toast 刷屏;404(CAS 抢先:超时/停止/选项)→ 静默转"已失效",不重复 freeze/不 toast,
 * 输入照常回 RUNNING(Review Focus 4)。
 * ⑥ history_readonly_card_shows_dismiss_badge —— 历史 GET 返回 ToolCallHistory 注入形态的
 * TOOL_CALL 条目,result="[用户想先聊聊] …"(Task 8 spec §5.3 原文)→ 只读卡片显示
 * "💬 转入对话"徽章,无交互按钮(B5);同批"已答 ✓"条目逐字节不变(A1)。
 */
@DisplayName("Task 13 askUser 聊聊这个:即时 dismiss + 第五终态 + 提示条 + ASK_PENDING")
class AskUserDismissBrowserIT extends BrowserTestBase {

    private static final Page.WaitForFunctionOptions WF_5S =
            new Page.WaitForFunctionOptions().setTimeout(5_000).setPollingInterval(100);
    private static final Page.WaitForFunctionOptions WF_10S =
            new Page.WaitForFunctionOptions().setTimeout(10_000).setPollingInterval(100);

    /** Task 8 spec §5.3 逐字原文(dismiss 的 tool result 前缀 = 历史徽章判定标记)。 */
    private static final String DISMISS_RESULT =
            "[用户想先聊聊] 用户选择暂不回答以上问题,想先自由讨论。"
                    + "请等待用户接下来的补充消息(将在下一轮送达),不要自行猜测答案或强行推进原问题。";

    // ==================== 通用 harness(R26:awaitRoute 切片泵送) ====================

    /** answer 端点脚本:按 qid 定制 HTTP status,可选扣住首个请求模拟 CAS 在飞窗口。 */
    private static final class AnswerMock {
        final AtomicInteger total = new AtomicInteger();
        final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        volatile Map<String, Integer> statusByQid = new HashMap<>();
        volatile int defaultStatus = 200;
        volatile boolean hold = false;
        final BlockingQueue<Route> held = new LinkedBlockingQueue<>();
    }

    private static AnswerMock answerMock(Page page) {
        AnswerMock m = new AnswerMock();
        page.route("**/spring/ai/loom/ask/*/answer", route -> {
            m.total.incrementAndGet();
            m.bodies.add(route.request().postData());
            String qid = route.request().url()
                    .replaceAll(".*spring/ai/loom/ask/", "")
                    .replaceAll("/answer.*", "");
            if (m.hold) {
                m.held.add(route);
                return;
            }
            int status = m.statusByQid.getOrDefault(qid, m.defaultStatus);
            route.fulfill(new Route.FulfillOptions()
                    .setStatus(status)
                    .setContentType("application/json; charset=UTF-8")
                    .setBody(status == 200 ? "{\"ok\":true}" : "{\"error\":\"not found\"}"));
        });
        return m;
    }

    /** steer 端点:固定 queued:true,messageId 由调用方指定。 */
    private static List<String> steerMock(Page page, String messageId) {
        List<String> captured = Collections.synchronizedList(new ArrayList<>());
        page.route("**/steer", route -> {
            captured.add(route.request().postData());
            route.fulfill(new Route.FulfillOptions()
                    .setStatus(200).setContentType("application/json; charset=UTF-8")
                    .setBody("{\"queued\":true,\"messageId\":\"" + messageId + "\"}"));
        });
        return captured;
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

    private static BlockingQueue<Route> holdStreams(Page page) {
        BlockingQueue<Route> q = new LinkedBlockingQueue<>();
        page.route("**/spring/ai/loom/stream", q::add);
        return q;
    }

    private Route startHeldRun(Page page, BlockingQueue<Route> streams, String firstText) {
        page.fill("#textarea", firstText);
        page.click("#send-btn");
        Route run = awaitRoute(streams, page, 5_000);
        assertThat(run).as("聊天流 POST /stream 已发出并被扣住(= RUNNING)").isNotNull();
        return run;
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

    /** R26:route 事件投递依赖测试线程回到 Playwright 调用,必须切片泵送,不能裸 poll。 */
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

    /**
     * 注入一张 askUser 卡片 —— 与生产 onFrame 完全同一入口
     * (onFrame: {@code if (data.askUser) askUserCards.render(data.askUser)})。
     * 载荷逐字段匹配后端 AskUserEvent/AskUserOption 序列化形状。
     */
    private static void pushAskCard(Page page, String qid, String question) {
        Map<String, Object> optionA = new LinkedHashMap<>();
        optionA.put("label", "方案A");
        optionA.put("description", null);
        Map<String, Object> optionB = new LinkedHashMap<>();
        optionB.put("label", "方案B");
        optionB.put("description", "更快落地");
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("questionId", qid);
        ev.put("question", question);
        ev.put("header", "方案选择");
        ev.put("background", "需要你拍板");
        ev.put("options", List.of(optionA, optionB));
        ev.put("multiSelect", false);
        ev.put("allowCustomInput", true);
        ev.put("timeoutSeconds", 300);
        page.evaluate("(f) => window._loomAgent.askUserCards.render(f)", ev);
    }

    private static String mode(Page page) {
        return (String) page.evaluate("() => window._loomAgent.state.inputMode");
    }

    private static void waitMode(Page page, String expected) {
        page.waitForFunction("(m) => window._loomAgent.state.inputMode === m", expected, WF_10S);
    }

    private static int pendingCount(Page page) {
        Object n = page.evaluate("() => window._loomAgent.askUserCards.pendingCount()");
        return ((Number) n).intValue();
    }

    private static String hintBarText(Page page) {
        return (String) page.evaluate(
                "() => { const b=document.getElementById('ask-hint-bar'); return b ? b.textContent : null; }");
    }

    private static String stateBadge(Page page, String qid) {
        return (String) page.evaluate(
                "(id) => { const b=document.getElementById('askuser-' + id)"
                        + "?.querySelector('.askuser-state'); return b ? b.textContent : null; }", qid);
    }

    /** 无 toast 刷屏断言:toast 区从未写过失败类文案。 */
    private static void assertNoFailureToast(Page page) {
        String toast = (String) page.evaluate(
                "() => document.getElementById('toast-notification').textContent");
        assertThat(toast == null ? "" : toast)
                .as("dismiss 成功/404 路径都不得弹失败 toast").doesNotContain("失败");
    }

    // ==================== ① 卡片终态 + 输入回 RUNNING ====================

    @Test
    @DisplayName("「聊聊这个」→ POST mode=DISMISS → 卡片冻结「💬 转入对话」→ 输入回 RUNNING,流语义不变")
    void dismiss_button_freezes_and_returns_running() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            AnswerMock answers = answerMock(page);
            newConversation(page);

            Route run1 = startHeldRun(page, streams, "帮我定午餐");

            pushAskCard(page, "q-1", "午餐吃面条还是米饭?");
            waitMode(page, "ASK_PENDING");

            // ASK_PENDING:输入区锁定,但 isStreaming/工具栏/stop 语义不变
            assertThat(mode(page)).isEqualTo("ASK_PENDING");
            assertThat((Boolean) page.evaluate("() => document.getElementById('textarea').disabled"))
                    .as("ASK_PENDING 锁 textarea").isTrue();
            assertThat((Boolean) page.evaluate("() => document.getElementById('send-btn').disabled"))
                    .as("ASK_PENDING 锁 send").isTrue();
            assertThat((String) page.evaluate("() => document.getElementById('textarea').placeholder"))
                    .as("占位提示指向卡片/聊聊这个").contains("回答上方卡片");
            assertThat((Boolean) page.evaluate("() => window._loomAgent.state.isStreaming"))
                    .as("isStreaming 语义保持(有活跃流)").isTrue();
            assertThat(page.isVisible("#stop-btn")).as("stop 按钮保持可见").isTrue();
            assertThat(pendingCount(page)).isEqualTo(1);

            // 提示条:文案 + 倒计时 m:ss + 定位
            assertThat(page.isVisible("#ask-hint-bar")).as("ASK_PENDING 提示条可见").isTrue();
            String hint = hintBarText(page);
            assertThat(hint).contains("AI 正在等你回答").contains("定位");
            assertThat(hint).containsPattern("剩余 \\d+:\\d{2}");

            // dismiss 按钮存在(卡片恒有)
            assertThat(page.isVisible("#askuser-q-1 .ask-chat-btn"))
                    .as("卡片恒有「聊聊这个」按钮").isTrue();

            page.click("#askuser-q-1 .ask-chat-btn");
            waitMode(page, "RUNNING");

            // 契约:POST answer 端点,body 仅 mode=DISMISS,无 answer 字段(Task 8:文本不参与)
            assertThat(answers.total.get()).as("恰一次 DISMISS POST").isEqualTo(1);
            assertThat(answers.bodies.get(0))
                    .as("body {mode:\"DISMISS\"} 不带 answer")
                    .contains("\"mode\":\"DISMISS\"").doesNotContain("answer");

            // 第五终态:徽章 + 摘要折叠 + 卡片隐藏(不可逆,无返回选项)
            assertThat(stateBadge(page, "q-1")).isEqualTo("💬 转入对话");
            assertThat((Boolean) page.evaluate(
                    "() => { const c=document.getElementById('askuser-q-1');"
                            + " return c.classList.contains('askuser-frozen') && c.style.display==='none'; }"))
                    .as("卡片定格并折叠").isTrue();
            String summary = (String) page.evaluate(
                    "() => { const s=document.querySelector('.askuser-summary');"
                            + " return s ? s.textContent : ''; }");
            assertThat(summary).as("摘要行显示第五终态").contains("转入自由讨论");
            assertThat((Boolean) page.evaluate(
                    "() => document.getElementById('askuser-q-1')"
                            + ".querySelectorAll('input:enabled, button:enabled').length === 0"))
                    .as("终态输入全禁").isTrue();

            // 收敛回 RUNNING(非 IDLE:流仍活跃;R27 全走 _setComposerMode 单入口)
            assertThat(mode(page)).isEqualTo("RUNNING");
            assertThat((Boolean) page.evaluate("() => !document.getElementById('textarea').disabled"))
                    .as("dismiss 后 textarea 解锁").isTrue();
            assertThat(page.isVisible("#ask-hint-bar")).as("离开 ASK_PENDING 提示条隐藏").isFalse();
            assertThat(pendingCount(page)).isZero();

            // 收尾:流照常结束
            fulfillSse(run1, "{\"content\":\"好的,已记下\"}");
            waitIdle(page, WF_10S);
            assertThat(mode(page)).isEqualTo("IDLE");
            assertThat(stateBadge(page, "q-1"))
                    .as("流结束 cancelAll sentinel 对已定格卡片幂等 no-op").isEqualTo("💬 转入对话");

            assertNoFailureToast(page);
            assertThat(consoleErrorsOf(page)).as("全程无 JS console error").isEmpty();
        }
    }

    // ==================== ② dismiss 后自由文本走 steering(G3) ====================

    @Test
    @DisplayName("dismiss 回 RUNNING 后回车 = 普通 steering:/steer 恰一条、无第二条 /stream、ack 转正")
    void dismiss_then_steering_continues() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            AnswerMock answers = answerMock(page);
            List<String> steers = steerMock(page, "m-1");
            newConversation(page);

            Route run1 = startHeldRun(page, streams, "先讲架构");
            pushAskCard(page, "q-1", "先讲存储还是计算?");
            waitMode(page, "ASK_PENDING");
            page.click("#askuser-q-1 .ask-chat-btn");
            waitMode(page, "RUNNING");
            assertThat(answers.total.get()).isEqualTo(1);

            // dismiss 后的自由文本 = 普通 steering(下一轮注入),不是第二条 /stream
            page.fill("#textarea", "换个思路:先讲缓存分层");
            page.press("#textarea", "Enter");
            page.waitForSelector(".chat-item-queued[data-message-id=\"m-1\"]",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
            assertThat(steers).as("恰一条 steer").hasSize(1);
            assertThat(steers.get(0)).contains("换个思路:先讲缓存分层");
            assertThat(streams.size())
                    .as("steering 不触发第二条 /stream").isZero();

            // ack INJECTED 转正 → 流正常结束
            fulfillSse(run1,
                    "{\"steerAck\":{\"messageId\":\"m-1\",\"status\":\"INJECTED\"}}",
                    "{\"content\":\"收到,改讲缓存分层\"}");
            waitIdle(page, WF_10S);
            assertThat(page.locator(".chat-item-queued").count())
                    .as("结束后无残留灰态").isZero();
            assertThat((String) page.evaluate(
                    "() => document.getElementById('mainContent').innerText"))
                    .contains("换个思路:先讲缓存分层");
            assertThat(stateBadge(page, "q-1"))
                    .as("steering 续跑不影响已定格卡片").isEqualTo("💬 转入对话");

            assertNoFailureToast(page);
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    // ==================== ③ 提示条点击滚动到卡片 ====================

    @Test
    @DisplayName("ASK_PENDING 提示条点击「↓定位」→ 最早 pending 卡片滚入可视区")
    void hint_bar_scrolls_to_card() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            answerMock(page);
            newConversation(page);

            Route run1 = startHeldRun(page, streams, "长文之后提问");
            pushAskCard(page, "q-1", "方案定了走哪个?");
            waitMode(page, "ASK_PENDING");
            assertThat(page.isVisible("#ask-hint-bar")).isTrue();

            // 制造溢出:卡片之上塞 1600px 占位,滚到顶(卡片滚出视野)
            page.evaluate("() => {"
                    + " const mc=document.getElementById('mainContent');"
                    + " const s=document.createElement('div'); s.style.height='1600px';"
                    + " mc.prepend(s); mc.scrollTop=0; }");
            page.waitForTimeout(200); // 泵送 scroll 事件
            Number before = (Number) page.evaluate(
                    "() => document.getElementById('mainContent').scrollTop");
            assertThat(before.intValue()).as("定位前在顶部").isZero();

            page.click("#ask-hint-bar");
            page.waitForFunction(
                    "() => document.getElementById('mainContent').scrollTop > 100", null, WF_5S);
            Boolean inView = (Boolean) page.evaluate(
                    "() => { const mc=document.getElementById('mainContent');"
                            + " const el=document.getElementById('askuser-q-1');"
                            + " const r=el.getBoundingClientRect(), m=mc.getBoundingClientRect();"
                            + " return r.top >= m.top - 4 && r.bottom <= m.bottom + 4; }");
            assertThat(inView).as("点击提示条后卡片滚入 mainContent 视野").isTrue();

            // 卡片仍 pending:定位不消费问题
            assertThat(pendingCount(page)).isEqualTo(1);
            assertThat(mode(page)).isEqualTo("ASK_PENDING");

            fulfillSse(run1, "{\"content\":\"好\"}");
            waitIdle(page, WF_10S);
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    // ==================== ④ 多卡:全部落定才解锁(B8) ====================

    @Test
    @DisplayName("2 卡 pending:dismiss 最早一张仍 ASK_PENDING(提示条计数);另一张 ANSWER 落定后才 RUNNING")
    void multi_card_stays_pending_until_all_resolved() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            AnswerMock answers = answerMock(page);
            newConversation(page);

            Route run1 = startHeldRun(page, streams, "两件事都要拍板");
            pushAskCard(page, "q-1", "午餐主食?");
            pushAskCard(page, "q-2", "喝什么?");
            waitMode(page, "ASK_PENDING");
            assertThat(pendingCount(page)).isEqualTo(2);

            String hintTwo = hintBarText(page);
            assertThat(hintTwo).as("多卡提示条显示计数").contains("2 个问题待回答");

            // dismiss 最早一张 → 另一张仍 pending → 输入仍锁
            page.click("#askuser-q-1 .ask-chat-btn");
            page.waitForFunction("() => window._loomAgent.askUserCards.pendingCount() === 1", null, WF_5S);
            assertThat(mode(page)).as("多卡:全落定前保持 ASK_PENDING").isEqualTo("ASK_PENDING");
            assertThat((Boolean) page.evaluate("() => document.getElementById('textarea').disabled"))
                    .isTrue();
            assertThat(stateBadge(page, "q-1")).isEqualTo("💬 转入对话");
            String hintOne = hintBarText(page);
            assertThat(hintOne).as("剩余 1 张:计数撤下(spec §5.3:多卡才计数),仍在等回答")
                    .contains("AI 正在等你回答").doesNotContain("2 个问题待回答");

            // 第二张走 ANSWER 路径(单选点选项 = 自动提交)—— A1:ANSWER 流程不受 dismiss 接线影响
            page.click("#askuser-q-2 .askuser-opt-input");
            waitMode(page, "RUNNING");
            assertThat(pendingCount(page)).isZero();
            assertThat(stateBadge(page, "q-2")).isEqualTo("已答 ✓");
            assertThat(answers.total.get()).isEqualTo(2);
            String dismissBody = answers.bodies.get(0);
            String answerBody = answers.bodies.get(1);
            assertThat(dismissBody).contains("\"mode\":\"DISMISS\"").doesNotContain("answer");
            assertThat(answerBody).as("ANSWER 路径 payload 不变").doesNotContain("DISMISS");
            assertThat(page.isVisible("#ask-hint-bar")).isFalse();

            fulfillSse(run1, "{\"content\":\"收到两条答案\"}");
            waitIdle(page, WF_10S);
            assertThat(stateBadge(page, "q-1")).isEqualTo("💬 转入对话");
            assertThat(stateBadge(page, "q-2")).isEqualTo("已答 ✓");

            assertNoFailureToast(page);
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    // ==================== ⑤ 双击防重 + 404 竞态静默 ====================

    @Test
    @DisplayName("快速双击「聊聊这个」:恰一次 POST、一次终态、零 toast;迟到 404(CAS 抢先)静默转已失效")
    void dismiss_double_click_no_double_freeze() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            AnswerMock answers = answerMock(page);
            newConversation(page);

            Route run1 = startHeldRun(page, streams, "第一问");
            pushAskCard(page, "q-1", "问题一?");
            waitMode(page, "ASK_PENDING");

            // 双击:第一个 click 发起 POST(被扣住模拟在飞),第二个 click 被在飞防抖吞掉
            answers.hold = true;
            page.click("#askuser-q-1 .ask-chat-btn");
            Route inFlight = awaitRoute(answers.held, page, 5_000);
            assertThat(inFlight).as("首个 click 的 DISMISS POST 在飞").isNotNull();
            page.evaluate("() => { const b=document.querySelector('#askuser-q-1 .ask-chat-btn'); b.click(); }");
            page.waitForTimeout(300); // 泵送:第二请求若发出必已入 held
            assertThat(answers.total.get())
                    .as("快速双击只产生一次 POST(在飞防抖)").isEqualTo(1);

            inFlight.fulfill(new Route.FulfillOptions()
                    .setStatus(200).setContentType("application/json; charset=UTF-8")
                    .setBody("{\"ok\":true}"));
            waitMode(page, "RUNNING");
            assertThat(stateBadge(page, "q-1")).isEqualTo("💬 转入对话");
            assertThat((Number) page.evaluate(
                    "() => document.querySelectorAll('.askuser-summary').length"))
                    .as("恰好一次终态折叠").isEqualTo(1);
            assertNoFailureToast(page);

            // 404 竞态:服务端已先行落定(超时/停止/跨页选项点击)→ 迟到 DISMISS 收 404
            answers.hold = false;
            Map<String, Integer> fortyFour = new HashMap<>();
            fortyFour.put("q-2", 404);
            answers.statusByQid = fortyFour;
            pushAskCard(page, "q-2", "问题二?");
            waitMode(page, "ASK_PENDING");
            page.click("#askuser-q-2 .ask-chat-btn");
            waitMode(page, "RUNNING");
            assertThat(answers.total.get()).isEqualTo(2);
            assertThat(stateBadge(page, "q-2"))
                    .as("404 = 已失效终态,不重复 freeze").isEqualTo("已失效(超时或已取消)");
            assertThat(stateBadge(page, "q-1"))
                    .as("第一张不被二次 touch(无双 freeze)").isEqualTo("💬 转入对话");
            assertNoFailureToast(page);
            assertThat((Boolean) page.evaluate(
                    "() => document.getElementById('textarea').disabled")).isFalse();

            fulfillSse(run1, "{\"content\":\"好\"}");
            waitIdle(page, WF_10S);

            assertThat(consoleErrorsOf(page))
                    .as("除 404 网络状态日志外无 JS console error")
                    .noneMatch(e -> !e.startsWith("Failed to load resource"));
        }
    }

    // ==================== ⑥ 历史只读卡片「转入对话」徽章(B5) ====================

    @Test
    @DisplayName("历史 TOOL_CALL 条目 result=[用户想先聊聊] → 只读卡片显示「💬 转入对话」徽章;已答条目逐字节不变")
    void history_readonly_card_shows_dismiss_badge() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            newConversation(page);
            String conv = (String) page.evaluate("() => window._loomAgent.state.conversationId");
            assertThat(conv).isNotBlank();

            // 历史 GET mock:条目形状 = ToolCallHistory.inject 的真实产出
            String history = "[\n"
                    + " {\"messageType\":\"USER\",\"text\":\"帮我订午餐\"},\n"
                    + " {\"messageType\":\"ASSISTANT\",\"text\":\"先确认你的口味\"},\n"
                    + " {\"messageType\":\"TOOL_CALL\",\"toolName\":\"askUser\",\"toolCallId\":\"tc-dismiss\","
                    + "  \"isError\":false,\"durationMs\":45000,\"truncated\":false,\n"
                    + "  \"arguments\":{\"question\":\"吃面条还是米饭?\",\"header\":\"主食\","
                    + "   \"optionsJson\":\"[{\\\"label\\\":\\\"面条\\\"},{\\\"label\\\":\\\"米饭\\\"}]\","
                    + "   \"multiSelect\":false,\"allowCustomInput\":true},\n"
                    + "  \"result\":\"" + DISMISS_RESULT + "\"},\n"
                    + " {\"messageType\":\"TOOL_CALL\",\"toolName\":\"askUser\",\"toolCallId\":\"tc-answer\","
                    + "  \"isError\":false,\"durationMs\":12000,\"truncated\":false,\n"
                    + "  \"arguments\":{\"question\":\"喝什么?\",\"header\":\"饮品\","
                    + "   \"optionsJson\":\"[{\\\"label\\\":\\\"美式\\\"},{\\\"label\\\":\\\"拿铁\\\"}]\","
                    + "   \"multiSelect\":false},\n"
                    + "  \"result\":\"[用户已回答] 拿铁\"}\n"
                    + "]";
            page.route(baseUrl + "/spring/ai/loom/conversation/" + conv, route ->
                    route.fulfill(new Route.FulfillOptions()
                            .setStatus(200).setContentType("application/json; charset=UTF-8")
                            .setBody(history)));

            page.evaluate("(id) => window._loomAgent.conversation.switchTo(id)", conv);
            page.waitForFunction(
                    "() => document.querySelectorAll('.tool-call-inline .askuser-card').length === 2",
                    null, WF_10S);

            Boolean dismissBadge = (Boolean) page.evaluate(
                    "() => { const cards=[...document.querySelectorAll('.tool-call-inline .askuser-card')];"
                            + " const c=cards.find(x=>x.querySelector('.askuser-state-chat'));"
                            + " return !!c && c.querySelector('.askuser-state-chat').textContent.includes('转入对话')"
                            + " && !c.textContent.includes('未作答'); }");
            assertThat(dismissBadge).as("B5:dismiss 历史卡片显示转入对话徽章且不误标未作答").isTrue();

            // 只读:历史卡片绝无交互按钮(聊这个/提交)
            assertThat(page.locator(".tool-call-inline .ask-chat-btn").count())
                    .as("历史只读卡片不含「聊聊这个」按钮").isZero();

            // A1:已答条目徽章/选中高亮逐字节不变
            Boolean answeredOk = (Boolean) page.evaluate(
                    "() => { const cards=[...document.querySelectorAll('.tool-call-inline .askuser-card')];"
                            + " const c=cards.find(x=>!x.querySelector('.askuser-state-chat'));"
                            + " return c.querySelector('.askuser-state-ok').textContent.includes('已答 ✓')"
                            + " && c.querySelector('.askuser-opt-chosen') !== null; }");
            assertThat(answeredOk).as("ANSWER 历史卡片不受第五终态影响").isTrue();

            // 历史 pending 态不锁输入
            assertThat(mode(page)).as("历史回看不触发 ASK_PENDING").isNotEqualTo("ASK_PENDING");

            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }
}
