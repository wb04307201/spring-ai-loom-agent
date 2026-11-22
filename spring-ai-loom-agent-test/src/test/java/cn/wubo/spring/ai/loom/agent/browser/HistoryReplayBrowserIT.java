package cn.wubo.spring.ai.loom.agent.browser;

import cn.wubo.spring.ai.loom.agent.model.ToolCallLog;
import cn.wubo.spring.ai.loom.agent.tool.IToolCallLogRepository;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 14(spec 2026-11-21 agentic-loop-steering)历史回放气泡分组 BrowserIT(A6/G5,R25 合并门禁)。
 *
 * <p><b>契约背景</b>:Task 10 起 {@code GET /spring/ai/loom/conversation/{id}} 由
 * ChatHistoryAssembler 按 Instant 把 TOOL_CALL 条目 interleave 进消息时间轴(同刻消息在前)。
 * 真实 run 里工具完成时刻恒早于其迭代 ASSISTANT 文本行的落库时刻,交错序典型为
 * {@code [USER(轮初), TOOL_CALL(迭代1), ASSISTANT(迭代1), USER(steering 注入),
 * TOOL_CALL(迭代2), ASSISTANT(迭代2)]}。旧前端 renderMessages 把 TOOL_CALL 挂
 * {@code _lastBotBubble || mainContent} —— 工具先于本轮气泡到达时悬浮在 mainContent 顶部
 * (R25 过渡态),或错挂进上一轮气泡。本 IT 用<b>真实历史端点</b>(JDBC 灌库,零 mock 形状漂移)
 * 驱动 switchTo 回放,锁定收敛。
 *
 * <p>覆盖(spec §5.6/§5.10,task-14-brief Step 1 五条逐一对应 + R30):
 * ① replay_bubble_grouping_matches_live —— 2 迭代 + 中段插话 run 回放侧序 =
 * 用户/AI/用户(插话)/AI(steering USER 行天然拆气泡,与 live 事件驱动分组同构),文本按气泡隔离;
 * ② tool_call_in_correct_bubble_after_replay —— <b>R25 硬验收</b>:迭代1 的 askUser 只读卡片
 * 落在迭代1 的气泡内(非末尾气泡、非 mainContent 悬浮),mavenBuild 行落进气泡2,
 * {@code #mainContent > .tool-call-inline} 恒 0(过渡态终结);顺带锁历史 askUser 条目
 * 经 tool-call-renderer 渲染为只读冻结卡(已答 ✓ 徽章、无交互按钮);
 * ③ thinking_per_iteration_after_replay —— 同轮多条 ASSISTANT 行(纯迭代续跑,无 steering)=
 * 同一气泡内分段,每段独立思考折叠(±10s 配对);分隔线按 spec §5.6 裁定<b>不进历史</b>;
 * ④ steering_html_inert_after_replay —— Review Focus 1:历史 USER/ASSISTANT 行文本携带
 * {@code <img onerror>}/{@code <script>} → 回放全程不执行、DOM 无 [onerror]/script 残留
 * (旧代码 origin.innerHTML=raw 正是本任务的 XSS 面,改 textContent);
 * ⑤ legacy_single_turn_replay_unchanged —— A1:无 steering、工具 ts 天然最晚的旧会话回放
 * 与改造前一致(1 用户气泡 + 1 AI 气泡 + 工具挂气泡尾正文后/操作栏前 + 思考容器唯一 +
 * 复制载荷逐字节一致);多轮旧会话分组不变;
 * ⑥ askuser_frame_dispatch_route —— R30 补咬:askUser 事件帧走<b>生产 SSE 分派路由</b>
 * (app.js {@code if (data.askUser) askUserCards.render(...)}),Task 13 测试缝绕开了该行。
 *
 * <p>harness 沿用 EventBubbleBrowserIT 惯例(R26 awaitRoute 切片泵送)。浏览器 IT 与灌库共用
 * 同一 Spring 上下文/数据源(./target/test-ds),ADMIN_USER 即数据归属用户。
 */
@DisplayName("Task 14 历史回放:USER 行气泡分组 + TOOL_CALL 归位(R25)+ XSS inert")
class HistoryReplayBrowserIT extends BrowserTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IToolCallLogRepository toolCallLogRepository;

    private static final Page.WaitForFunctionOptions WF_10S =
            new Page.WaitForFunctionOptions().setTimeout(10_000).setPollingInterval(100);

    /** 固定基准时刻(微秒级整值,规避 TIMESTAMP 精度往返,同 HistoryInterleaveTest 惯例)。 */
    private static final Instant T0 = Instant.parse("2026-11-21T10:00:00Z");

    // ==================== 通用 harness ====================

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

    /** 建会话归属(否则 GET /conversation/{id} 按跨用户读 403)。 */
    private String newConv() {
        String conv = "hist-replay-" + java.util.UUID.randomUUID();
        jdbcTemplate.update(
                "MERGE INTO user_conversation (username, conversation_id) KEY (username, conversation_id) VALUES (?, ?)",
                ADMIN_USER, conv);
        return conv;
    }

    /** content 存纯文本(与 JdbcChatMemoryRepository 2.0.2 实际存储一致)。 */
    private void seedMemory(String conv, String type, String text, Instant ts, int seq) {
        jdbcTemplate.update(
                "INSERT INTO spring_ai_chat_memory (conversation_id, content, type, timestamp, sequence_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                conv, text, type, Timestamp.from(ts), seq);
    }

    private void seedTool(String conv, String callId, String name,
                          String argsJson, String result, Instant createdAt) {
        toolCallLogRepository.save(new ToolCallLog(null, conv, ADMIN_USER, callId, name,
                argsJson, result, false, 1500L, createdAt));
    }

    /** reasoning 行 created_at 贴近其 ASSISTANT 行(±10s 就近配对,Task 4/6)。 */
    private void seedReasoning(String conv, int seq, String text, Instant ts) {
        jdbcTemplate.update(
                "INSERT INTO loom_chat_reasoning (conversation_id, seq, reasoning_text, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                conv, seq, text, Timestamp.from(ts), Timestamp.from(ts));
    }

    /**
     * 走真实历史端点回放:conversation.switchTo → GET /conversation/{id} → renderMessages。
     * expectText 必须是该会话独有的行文本 —— 防「conversationId 已切但旧 DOM 未清」竞态
     * (switchTo 先改 state 再 await fetch,只等 .chat-item 会读到上一个会话的残留视图)。
     */
    private void replay(Page page, String conv, String expectText) {
        page.evaluate("(id) => window._loomAgent.conversation.switchTo(id)", conv);
        page.waitForFunction(
                "(a) => window._loomAgent.state.conversationId === a[0]"
                        + " && document.querySelector('#mainContent .chat-item')"
                        + " && document.getElementById('mainContent').innerText.includes(a[1])",
                List.of(conv, expectText), WF_10S);
    }

    /**
     * 交错序 run(迭代1 含 askUser,迭代2 含 mavenBuild):
     * USER@0 → tc-ask1@2s → ASST1@5s(思考一)→ steering USER@10s → tc-build@12s → ASST2@15s(思考二)。
     * interleave 后端响应序 = 该时间轴序(Task 10 HistoryInterleaveTest 已锁)。
     */
    private void seedInterleavedRun(String conv) {
        seedMemory(conv, "USER", "帮我完成双迭代任务", T0, 1);
        seedTool(conv, "tc-ask1", "askUser",
                "{\"question\":\"先做哪个?\",\"header\":\"顺序\",\"multiSelect\":false}",
                "[用户已回答] 先做A", T0.plusSeconds(2));
        seedMemory(conv, "ASSISTANT", "迭代一的回答", T0.plusSeconds(5), 2);
        seedReasoning(conv, 1, "思考一:先确认顺序", T0.plusSeconds(5).plusMillis(200));
        seedMemory(conv, "USER", "插话:换个角度", T0.plusSeconds(10), 3);
        seedTool(conv, "tc-build", "mavenBuild", "{\"goals\":\"package\"}",
                "BUILD SUCCESS", T0.plusSeconds(12));
        seedMemory(conv, "ASSISTANT", "迭代二的回答", T0.plusSeconds(15), 4);
        seedReasoning(conv, 2, "思考二:打包验证", T0.plusSeconds(15).plusMillis(200));
    }

    private static BlockingQueue<Route> holdStreams(Page page) {
        BlockingQueue<Route> q = new LinkedBlockingQueue<>();
        page.route("**/spring/ai/loom/stream", q::add);
        return q;
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

    /** R26:route 事件投递依赖测试线程回到 Playwright 调用,切片泵送,不能裸 poll。 */
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

    private void newConversation(Page page) {
        String before = (String) page.evaluate("() => window._loomAgent.state.conversationId || ''");
        page.click("#new-chat-btn");
        page.waitForFunction(
                "(b) => window._loomAgent.state.conversationId && window._loomAgent.state.conversationId !== b",
                before, WF_10S);
    }

    // ==================== ① 回放分组与 live 一致(A6/G5) ====================

    @Test
    @DisplayName("① 2迭代+中段插话:回放侧序=用户/AI/用户(插话)/AI,文本按气泡隔离,零悬浮工具条")
    void replay_bubble_grouping_matches_live() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            String conv = newConv();
            seedInterleavedRun(conv);
            replay(page, conv, "迭代二的回答");

            // 侧序与 live 事件驱动分组同构:用户 → AI(迭代1) → 用户(插话) → AI(迭代2)
            String seq = (String) page.evaluate(
                    "() => [...document.getElementById('mainContent').children]"
                            + ".filter(el => el.classList.contains('chat-item'))"
                            + ".map(el => el.classList.contains('chat-item-right') ? 'U'"
                            + " : el.classList.contains('chat-item-left') ? 'A' : '?').join(',')");
            assertThat(seq).as("回放气泡侧序与 live 一致(插话拆气泡)").isEqualTo("U,A,U,A");

            Boolean isolated = (Boolean) page.evaluate(
                    "() => { const ais=[...document.querySelectorAll('#mainContent .chat-item-left')];"
                            + " return ais.length === 2"
                            + " && ais[0].innerText.includes('迭代一的回答') && !ais[0].innerText.includes('迭代二的回答')"
                            + " && ais[1].innerText.includes('迭代二的回答') && !ais[1].innerText.includes('迭代一的回答'); }");
            assertThat(isolated).as("插话前后文本分属两个 AI 气泡,互不混排").isTrue();

            // 插话用户气泡 = 普通右侧气泡(与 live 转正后的形态一致,不是灰态排队气泡)
            Boolean steerBubble = (Boolean) page.evaluate(
                    "() => { const us=[...document.querySelectorAll('#mainContent .chat-item-right')];"
                            + " return us.length === 2 && us[1].innerText.includes('插话:换个角度')"
                            + " && !us[1].classList.contains('chat-item-queued'); }");
            assertThat(steerBubble).as("steering USER 行回放为普通用户气泡(非灰态)").isTrue();

            assertThat(page.locator("#mainContent > .tool-call-inline").count())
                    .as("R25:零工具条悬浮在气泡外").isZero();
            assertThat(consoleErrorsOf(page)).as("全程无 JS console error").isEmpty();
        }
    }

    // ==================== ② TOOL_CALL 归位(R25 合并门禁) ====================

    @Test
    @DisplayName("② R25 硬验收:迭代1 askUser 只在气泡1、mavenBuild 只在气泡2,mainContent 顶部零悬浮")
    void tool_call_in_correct_bubble_after_replay() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            String conv = newConv();
            seedInterleavedRun(conv);
            replay(page, conv, "迭代二的回答");

            // 旧过渡态:tc-ask1 先于本轮 ASSISTANT 到达 → _lastBotBubble 为 null → 落 mainContent
            // (悬浮)或错挂上一轮气泡。收敛后必须挂进本轮(气泡1)。
            Boolean askInFirst = (Boolean) page.evaluate(
                    "() => { const ais=[...document.querySelectorAll('#mainContent .chat-item-left')];"
                            + " const card=document.querySelector('[data-tool-call-id=\"tc-ask1\"]');"
                            + " return !!card && ais.length === 2"
                            + " && ais[0].contains(card) && !ais[1].contains(card); }");
            assertThat(askInFirst)
                    .as("迭代1 的 askUser 卡片回放在迭代1 的气泡内,非末尾气泡(#3 回归)")
                    .isTrue();

            Boolean buildInSecond = (Boolean) page.evaluate(
                    "() => { const ais=[...document.querySelectorAll('#mainContent .chat-item-left')];"
                            + " const row=document.querySelector('.tool-call-row[data-tool-call-id=\"tc-build\"]');"
                            + " return !!row && ais[1].contains(row) && !ais[0].contains(row); }");
            assertThat(buildInSecond).as("迭代2 的 mavenBuild 工具行只挂进气泡2").isTrue();

            // R30 侧证:历史 askUser 条目经 tool-call-renderer 渲染为只读冻结卡片
            Boolean cardReadOnly = (Boolean) page.evaluate(
                    "() => { const card=document.querySelector('.askuser-card[data-tool-call-id=\"tc-ask1\"]');"
                            + " return !!card && card.classList.contains('askuser-frozen')"
                            + " && card.querySelector('.askuser-state-ok').textContent.includes('已答')"
                            + " && card.querySelector('.askuser-question').textContent.includes('先做哪个?')"
                            + " && !card.querySelector('.ask-chat-btn'); }");
            assertThat(cardReadOnly).as("历史 askUser → 只读冻结卡片(已答 ✓,无交互按钮)").isTrue();

            // 工具内联挂在气泡内部、非独立 chat-item(既有契约延续)
            Boolean inlineInsideBubble = (Boolean) page.evaluate(
                    "() => { const el=document.querySelector('[data-tool-call-id=\"tc-ask1\"]');"
                            + " const node=el.closest('.tool-call-inline');"
                            + " return !!node && !!node.closest('.chat-item-left .bubble')"
                            + " && !document.querySelector('.tool-call-item'); }");
            assertThat(inlineInsideBubble).as("工具卡片内联挂在 AI 气泡内部").isTrue();

            assertThat(page.locator("#mainContent > .tool-call-inline").count())
                    .as("R25 合并门禁:回放后 mainContent 顶部不允许有任何悬浮工具条").isZero();
            assertThat(consoleErrorsOf(page)).as("全程无 JS console error").isEmpty();
        }
    }

    // ==================== ③ 思考按迭代分段回放 ====================

    @Test
    @DisplayName("③ 同轮纯迭代多条 ASSISTANT:同气泡内分段,思考逐段独立,分隔线不进历史")
    void thinking_per_iteration_after_replay() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            String conv = newConv();
            // 同一用户轮内两条 ASSISTANT(无 steering 夹在中间)= 纯迭代续跑。
            // 真实 run 时间轴:每条工具先于其迭代的 ASSISTANT 文本行。
            seedMemory(conv, "USER", "纯迭代任务", T0, 1);
            seedTool(conv, "tc-i1", "readTextFile", "{\"path\":\"a.txt\"}", "file-a", T0.plusSeconds(1));
            seedMemory(conv, "ASSISTANT", "第一段回答", T0.plusSeconds(2), 2);
            seedReasoning(conv, 1, "迭代1思考:读文件", T0.plusSeconds(2).plusMillis(200));
            seedTool(conv, "tc-i2", "searchFiles", "{\"glob\":\"*.java\"}", "found-3", T0.plusSeconds(3));
            seedMemory(conv, "ASSISTANT", "第二段回答", T0.plusSeconds(4), 3);
            seedReasoning(conv, 2, "迭代2思考:再搜索", T0.plusSeconds(4).plusMillis(200));
            replay(page, conv, "第二段回答");

            // 单轮两条 ASSISTANT = 一个 AI 气泡内两个分段(与 live G4 迭代分段同构),
            // 每段各带独立思考折叠,内容互不合并(±10s 配对按 ASSISTANT 行独立注入)。
            Boolean oneBubbleTwoSegments = (Boolean) page.evaluate(
                    "() => { const ais=[...document.querySelectorAll('#mainContent .chat-item-left')];"
                            + " if (ais.length !== 1) return false;"
                            + " const bubble=ais[0].querySelector('.bubble');"
                            + " const tcs=[...bubble.querySelectorAll('.thinking-container')];"
                            + " if (tcs.length !== 2) return false;"
                            + " const b0=tcs[0].querySelector('.thinking-body');"
                            + " const b1=tcs[1].querySelector('.thinking-body');"
                            + " const base=ais[0].querySelector('.bot-content:not(.iteration-segment)');"
                            + " const seg=ais[0].querySelector('.bot-content.iteration-segment');"
                            + " return !!seg"
                            + " && b0.innerText.includes('迭代1思考') && !b0.innerText.includes('迭代2思考')"
                            + " && b1.innerText.includes('迭代2思考') && !b1.innerText.includes('迭代1思考')"
                            + " && tcs.every(t => t.style.display !== 'none')"
                            + " && base.innerText.includes('第一段回答')"
                            + " && seg.innerText.includes('第二段回答'); }");
            assertThat(oneBubbleTwoSegments)
                    .as("纯迭代 = 同一 AI 气泡内分段,每段独立思考折叠")
                    .isTrue();

            // spec §5.6 裁定:迭代分隔线是纯视觉装饰,不进历史(回放不还原)
            assertThat(page.locator("#mainContent .iteration-divider").count())
                    .as("分隔线不进历史(spec §5.6)").isZero();

            // 工具归位到所属迭代:气泡直接子序 = 思考1 → 回答1 → 工具1 → 思考2 → 回答2 → 工具2
            // (工具挂在其迭代正文之后、操作栏之前 —— 与 live 时间轴「先干活后出文,气泡内按序落底」一致)
            @SuppressWarnings("unchecked")
            List<String> order = (List<String>) (List<?>) page.evaluate(
                    "() => { const bubble=document.querySelector('#mainContent .chat-item-left .bubble');"
                            + " return [...bubble.children]"
                            + ".filter(c => c.classList.contains('thinking-container')"
                            + " || c.classList.contains('tool-call-inline')"
                            + " || c.classList.contains('bot-content'))"
                            + ".map(c => { if (c.classList.contains('tool-call-inline'))"
                            + " return 'C:'+c.querySelector('[data-tool-call-id]').getAttribute('data-tool-call-id');"
                            + " if (c.classList.contains('thinking-container')) return 'T';"
                            + " return 'A:'+c.innerText.trim(); }); }");
            assertThat(order).as("气泡内直接子元素时间轴序")
                    .containsExactly("T", "A:第一段回答", "C:tc-i1", "T", "A:第二段回答", "C:tc-i2");

            assertThat(consoleErrorsOf(page)).as("全程无 JS console error").isEmpty();
        }
    }

    // ==================== ④ steering HTML inert(Review Focus 1) ====================

    @Test
    @DisplayName("④ 历史 USER 行含 <img onerror> 载荷 → 回放不执行、DOM 无 [onerror] 残留")
    void steering_html_inert_after_replay() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            String conv = newConv();
            // steering 注入文本 = 用户可控;历史 ASSISTANT 文本经 LLM —— 两者都必须 inert。
            // 旧代码 origin.innerHTML=raw 是真实 XSS 面(display:none 不阻止 img 加载触发 onerror)。
            seedMemory(conv, "USER", "问题<img src=x onerror=\"window.__xssU=1\">尾巴", T0, 1);
            seedMemory(conv, "ASSISTANT", "回答<img src=y onerror=\"window.__xssA=1\">正文", T0.plusSeconds(2), 2);
            seedMemory(conv, "USER", "插话<img src=z onerror=\"window.__xssS=1\">", T0.plusSeconds(5), 3);
            seedMemory(conv, "ASSISTANT", "尾答<script>window.__xssX=1</script>", T0.plusSeconds(7), 4);
            replay(page, conv, "尾答");
            // onerror 若被解析,图片加载失败会立即触发;留出触发窗口
            page.waitForTimeout(500);

            Boolean inert = (Boolean) page.evaluate(
                    "() => !window.__xssU && !window.__xssA && !window.__xssS && !window.__xssX"
                            + " && document.querySelectorAll('#mainContent [onerror]').length === 0"
                            + " && document.querySelectorAll('#mainContent script').length === 0");
            assertThat(inert).as("历史行文本载荷(USER 插话/ASSISTANT)经净化/textContent 写入,不可执行").isTrue();

            // 载荷文本仍然可见(转义为纯文本,不是被吞掉)
            Boolean visible = (Boolean) page.evaluate(
                    "() => { const us=[...document.querySelectorAll('#mainContent .chat-item-right')];"
                            + " return us[0].innerText.includes('问题') && us[0].innerText.includes('尾巴')"
                            + " && us[1].innerText.includes('插话'); }");
            assertThat(visible).as("净化后用户文本可见").isTrue();

            // 隐藏复制载荷 origin 必须是纯文本容器(无子元素,原文含 onerror 字样仅作载荷)
            Boolean originSafe = (Boolean) page.evaluate(
                    "() => { const os=[...document.querySelectorAll('#mainContent [id^=\"origin-\"]')];"
                            + " return os.length === 2 && os.every(o => o.children.length === 0)"
                            + " && os[0].textContent.includes('onerror'); }");
            assertThat(originSafe).as("origin 复制载荷 = 未解析的纯原文(textContent 写入)").isTrue();

            assertThat(consoleErrorsOf(page)).as("全程无 JS console error").isEmpty();
        }
    }

    // ==================== ⑤ A1:旧形态回放在新分组下逐位不变 ====================

    @Test
    @DisplayName("⑤ A1:无交错旧会话(工具 ts 最晚)回放与改造前一致")
    void legacy_single_turn_replay_unchanged() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            String conv = newConv();
            // 旧形态:工具完成时刻天然最晚(单迭代无交错),[USER, ASSISTANT, TOOL]
            seedMemory(conv, "USER", "旧形态提问", T0, 1);
            seedMemory(conv, "ASSISTANT", "旧形态的回答", T0.plusSeconds(2), 2);
            seedReasoning(conv, 1, "旧形态思考", T0.plusSeconds(2).plusMillis(200));
            seedTool(conv, "tc-late", "getSkill", "{\"skillName\":\"s\"}", "SKILL", T0.plusSeconds(5));
            replay(page, conv, "旧形态的回答");

            String seq = (String) page.evaluate(
                    "() => [...document.querySelectorAll('#mainContent .chat-item')]"
                            + ".map(el => el.classList.contains('chat-item-right') ? 'U'"
                            + " : el.classList.contains('chat-item-left') ? 'A' : '?').join(',')");
            assertThat(seq).as("旧单轮:1 用户气泡 + 1 AI 气泡").isEqualTo("U,A");

            Boolean unchangedShape = (Boolean) page.evaluate(
                    "() => { const b=document.querySelector('#mainContent .chat-item-left .bubble');"
                            + " const kids=[...b.children];"
                            + " const contentIdx=kids.findIndex(c => c.classList.contains('bot-content'));"
                            + " const toolIdx=kids.findIndex(c => c.classList.contains('tool-call-inline'));"
                            + " const actIdx=kids.findIndex(c => c.classList.contains('bubble-actions'));"
                            + " const tc=b.querySelector('.thinking-container');"
                            + " return contentIdx >= 0 && toolIdx > contentIdx && toolIdx < actIdx"
                            + " && kids.filter(c=>c.classList.contains('tool-call-inline')).length === 1"
                            + " && b.querySelectorAll('.iteration-segment').length === 0"
                            + " && b.querySelectorAll('.iteration-divider').length === 0"
                            + " && b.querySelectorAll('.thinking-container').length === 1"
                            + " && tc.style.display !== 'none'"
                            + " && tc.querySelector('.thinking-body').innerText.includes('旧形态思考')"
                            + " && b.querySelector('[id^=\"origin-\"]').textContent === '旧形态的回答'"
                            + " && b.querySelector('.bubble-actions').style.display !== 'none'"
                            + " && b.querySelector('.bot-content').innerText.includes('旧形态的回答'); }");
            assertThat(unchangedShape)
                    .as("工具挂气泡尾(正文之后、操作栏之前)、思考容器唯一、无分段、复制载荷逐字节一致")
                    .isTrue();

            // 多轮旧会话:两轮各自 用户+AI 气泡(与改造前分组一致)
            String conv2 = newConv();
            seedMemory(conv2, "USER", "第一轮问", T0, 1);
            seedMemory(conv2, "ASSISTANT", "第一轮答", T0.plusSeconds(1), 2);
            seedMemory(conv2, "USER", "第二轮问", T0.plusSeconds(2), 3);
            seedMemory(conv2, "ASSISTANT", "第二轮答", T0.plusSeconds(3), 4);
            replay(page, conv2, "第二轮答");
            Boolean twoTurns = (Boolean) page.evaluate(
                    "() => [...document.querySelectorAll('#mainContent .chat-item')]"
                            + ".map(el => el.classList.contains('chat-item-right') ? 'U'"
                            + " : el.classList.contains('chat-item-left') ? 'A' : '?').join(',')"
                            + " === 'U,A,U,A'");
            assertThat(twoTurns).as("多轮旧会话分组不变").isTrue();

            // 未回答的轮次(USER 行后无 ASSISTANT/工具)→ 脚手架必须剪除,A1:旧形态不产出空气泡
            String conv3 = newConv();
            seedMemory(conv3, "USER", "永远不会被回答", T0, 1);
            replay(page, conv3, "永远不会被回答");
            Boolean noEmptyBubble = (Boolean) page.evaluate(
                    "() => document.querySelectorAll('#mainContent .chat-item-right').length === 1"
                            + " && document.querySelectorAll('#mainContent .chat-item-left').length === 0");
            assertThat(noEmptyBubble).as("未回答轮次:只有用户气泡,无空 AI 脚手架气泡(与改造前一致)")
                    .isTrue();

            // 纯工具无文本(异常降级形态:工具先于一切消息)→ 也必须落进气泡,绝不悬浮 mainContent
            String conv4 = newConv();
            seedTool(conv4, "tc-orphan", "searchFiles", "{\"glob\":\"*.md\"}", "hit", T0);
            replay(page, conv4, "🔧");
            assertThat(page.locator("#mainContent > .tool-call-inline").count())
                    .as("无消息只有工具的形态:工具气泡化,不悬浮").isZero();
            assertThat(page.locator("#mainContent .chat-item-left").count()).isEqualTo(1);

            assertThat(consoleErrorsOf(page)).as("全程无 JS console error").isEmpty();
        }
    }

    // ==================== ⑥ R30:askUser 帧真实分派路由 ====================

    @Test
    @DisplayName("⑥ R30 补咬:生产 SSE 分派 if(data.askUser)→askUserCards.render 渲染卡片(Task 13 缝未覆盖)")
    void askuser_frame_dispatch_route() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openChatReady(ctx);
            BlockingQueue<Route> streams = holdStreams(page);
            newConversation(page);

            page.fill("#textarea", "路由测试问题");
            page.click("#send-btn");
            Route run = awaitRoute(streams, page, 5_000);
            assertThat(run).as("聊天流已发出并被扣住").isNotNull();

            // 真实 AskUserEvent 序列化形状(ChatResponseRecord.askUser 分量,同 Task 13 harness)
            fulfillSse(run,
                    "{\"content\":\"我需要先问你一个问题\"}",
                    "{\"askUser\":{\"questionId\":\"q-disp-1\",\"question\":\"分派路由测试?\",\"header\":\"路由\","
                            + "\"background\":\"走 onFrame 生产分派\",\"options\":[{\"label\":\"选项A\",\"description\":null},"
                            + "{\"label\":\"选项B\",\"description\":\"更快\"}],\"multiSelect\":false,"
                            + "\"allowCustomInput\":true,\"timeoutSeconds\":300}}");
            waitIdle(page); // 流结束 → onComplete cancelAllActive("已结束") 冻结卡片

            Boolean rendered = (Boolean) page.evaluate(
                    "() => { const card=document.getElementById('askuser-q-disp-1');"
                            + " if (!card) return false;"
                            + " const wrap=card.closest('.askuser-wrap');"
                            + " return card.classList.contains('askuser-frozen')"
                            + " && card.querySelector('.askuser-question').textContent.includes('分派路由测试?')"
                            + " && card.querySelectorAll('.askuser-opt-input').length === 3" // 2 选项 + 自定义输入 radio
                            + " && !!wrap && wrap.querySelector('.askuser-summary').style.display !== 'none'"
                            + " && wrap.querySelector('.askuser-summary-text').textContent.includes('已结束'); }");
            assertThat(rendered)
                    .as("askUser 卡片由 app.js 生产分派路由渲染(非测试缝注入),流结束按序冻结成摘要")
                    .isTrue();
            assertThat(((Number) page.evaluate("() => window._loomAgent.askUserCards.pendingCount()")).intValue())
                    .as("冻结后无 pending").isZero();

            assertThat(consoleErrorsOf(page)).as("全程无 JS console error").isEmpty();
        }
    }
}
