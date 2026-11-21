package cn.wubo.spring.ai.loom.agent.askuser;

import cn.wubo.spring.ai.loom.agent.chat.MemoryWriter;
import cn.wubo.spring.ai.loom.agent.chat.SteeringInbox;
import cn.wubo.spring.ai.loom.agent.chat.StreamRunContext;
import cn.wubo.spring.ai.loom.agent.model.AskUserEvent;
import cn.wubo.spring.ai.loom.agent.model.AskUserOption;
import cn.wubo.spring.ai.loom.agent.model.ChatResponseRecord;
import cn.wubo.spring.ai.loom.agent.stream.SseEmitterRegistry;
import cn.wubo.spring.ai.loom.agent.testutil.LoomAgentTestUtil;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Task 8(spec 2026-11-21 §5.3, D-2/D-3/A3):askUser "聊聊这个" DISMISS 哨兵。
 * <p>
 * 语义:点「聊聊这个」即时 complete 被阻塞的工具,返回<b>非错误中性文本</b>(区别于
 * 超时/stop 的"[用户未作答]"与 is_error 路径),卡片冻结、输入框回归自由聊天;
 * <b>无保活状态、无 deadline 重置</b> —— dismiss 即 complete+remove。后续用户消息
 * 走普通 steering,不再是本卡片的"答案"。
 * <p>
 * CAS 竞态(Review Focus 4):dismiss 与超时/选项点击之间由 CompletableFuture.complete
 * 先到先得,后到方 answer() 返回 false(路由 404),绝无双重处理。
 * <p>
 * R21/D-3:askUser 阻塞等待期间经 ToolContext 里的 {@link StreamRunContext}
 * pauseClock,resumeClock 放 finally 覆盖 回答/超时/取消/dismiss 全部路径 ——
 * runTimeout 只计活跃时长,人工等待不计。
 */
@DisplayName("Task 8:askUser DISMISS 哨兵 + answer 端点 mode + D-3 时钟")
class AskUserDismissTest {

    /** spec §5.3 原文(契约锁,任何措辞漂移即红)。 */
    private static final String DISMISS_TEXT =
            "[用户想先聊聊] 用户选择暂不回答以上问题,想先自由讨论。"
            + "请等待用户接下来的补充消息(将在下一轮送达),不要自行猜测答案或强行推进原问题。";

    private static final String OPTIONS_JSON =
            "[{\"label\":\"Docker(推荐)\",\"description\":\"容器部署\"},{\"label\":\"java -jar\",\"description\":null}]";

    private AskUserRegistry askRegistry;
    private SseEmitterRegistry sseRegistry;
    private SseEmitter emitter;

    @BeforeEach
    void setUp() {
        askRegistry = new AskUserRegistry();
        sseRegistry = new SseEmitterRegistry();
        emitter = mock(SseEmitter.class);
        sseRegistry.register("alice", "conv-1", emitter, null, null);
        UserContextHolder.setCurrentUser("alice");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    private static ToolContext toolCtx() {
        return new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-1"));
    }

    /** send 时截获 questionId 到 ref 并释放 latch —— 测试线程据此在工具阻塞期间提交答案。 */
    private void captureQuestionId(AtomicReference<String> qidRef, CountDownLatch sent) throws Exception {
        doAnswer(inv -> {
            ChatResponseRecord rec = inv.getArgument(0);
            qidRef.set(rec.askUser().questionId());
            sent.countDown();
            return null;
        }).when(emitter).send(any(Object.class), any(MediaType.class));
    }

    /** 在卡片推送后由后台线程提交 DISMISS 哨兵(经 registry.answer —— 与路由同入口)。 */
    private void answerDismissOnSend() throws Exception {
        doAnswer(inv -> {
            ChatResponseRecord rec = inv.getArgument(0);
            String qid = rec.askUser().questionId();
            Thread t = new Thread(() ->
                    askRegistry.answer(qid, "alice", AskUserRegistry.DISMISS_SENTINEL));
            t.setDaemon(true);
            t.start();
            return null;
        }).when(emitter).send(any(Object.class), any(MediaType.class));
    }

    private CompletableFuture<String> registerPending(String qid, String user, String conv) {
        AskUserEvent ev = new AskUserEvent(qid, "问题", null, null,
                List.of(new AskUserOption("A", null), new AskUserOption("B", null)),
                false, false, 300L);
        CompletableFuture<String> future = new CompletableFuture<>();
        askRegistry.register(new AskUserRegistry.PendingQuestion(ev, user, conv, future,
                System.currentTimeMillis()));
        return future;
    }

    // ==================== 工具侧中性返回 ====================

    @Test
    @DisplayName("dismiss → spec §5.3 原文中性文本,非错误路径(A3)")
    void dismiss_returns_neutral_non_error_text() throws Exception {
        answerDismissOnSend();
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);

        // 非异常返回(is_error 变异在此直接抛红);文本逐字符锁定
        String r = tool.askUser("选哪种部署?", "部署", "需要确认", OPTIONS_JSON, false, false, toolCtx());

        assertThat(r).isEqualTo(DISMISS_TEXT);
        // 中性语义护栏:不得伪装成"未作答"(超时/stop 文案),也不得指示"继续"推进
        assertThat(r).doesNotContain("用户未作答");
        assertThat(r).doesNotContain("继续");
        assertThat(r).doesNotContain("自行合理决策");
        assertThat(r).doesNotStartWith("[提问失败]");
    }

    @Test
    @DisplayName("dismiss 即 complete+remove,注册表零保活状态")
    void dismiss_removes_pending_no_keepalive() throws Exception {
        AtomicReference<String> qidRef = new AtomicReference<>();
        doAnswer(inv -> {
            ChatResponseRecord rec = inv.getArgument(0);
            String qid = rec.askUser().questionId();
            qidRef.set(qid);
            Thread t = new Thread(() ->
                    askRegistry.answer(qid, "alice", AskUserRegistry.DISMISS_SENTINEL));
            t.setDaemon(true);
            t.start();
            return null;
        }).when(emitter).send(any(Object.class), any(MediaType.class));
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);

        String r = tool.askUser("选哪种?", null, null, OPTIONS_JSON, false, false, toolCtx());

        assertThat(r).isEqualTo(DISMISS_TEXT);
        assertThat(qidRef.get()).as("卡片确已推送").isNotNull();
        // 无保活:dismiss 后 pending 已清空,后续任何 answer 都是 404 语义(false)
        assertThat(askRegistry.get(qidRef.get())).isNull();
        assertThat(askRegistry.answer(qidRef.get(), "alice", "补的答案")).isFalse();
    }

    @Test
    @DisplayName("dismiss 与超时竞态:complete 先到先得,超时后 answer 返回 false(不重复处理)")
    void dismiss_vs_timeout_cas_first_wins() throws Exception {
        AtomicReference<String> qidRef = new AtomicReference<>();
        CountDownLatch sent = new CountDownLatch(1);
        captureQuestionId(qidRef, sent);
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 30);

        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch returned = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            result.set(tool.askUser("选哪种?", null, null, OPTIONS_JSON, false, false, toolCtx()));
            returned.countDown();
        });
        worker.setDaemon(true);
        worker.start();

        assertThat(sent.await(3, TimeUnit.SECONDS)).as("卡片已推送").isTrue();
        String qid = qidRef.get();
        // dismiss 抢先 complete → true
        assertThat(askRegistry.answer(qid, "alice", AskUserRegistry.DISMISS_SENTINEL)).isTrue();
        // 迟到的"作答"(原超时窗口内任何后续提交)→ false(路由映射 404),绝无双重处理
        assertThat(askRegistry.answer(qid, "alice", "A")).isFalse();
        assertThat(returned.await(5, TimeUnit.SECONDS)).as("工具线程被 dismiss 即时释放").isTrue();
        assertThat(result.get()).isEqualTo(DISMISS_TEXT);

        // 反方向:超时先行(工具 timeoutSeconds=0)→ 返回超时文案,pending 已被 finally 清理,
        // 之后迟到的 dismiss 提交 false(幽灵不复活)
        DefaultAskUserTool expiredTool = new DefaultAskUserTool(askRegistry, sseRegistry, 0);
        String timedOut = expiredTool.askUser("选哪种?", null, null, OPTIONS_JSON, false, false, toolCtx());
        assertThat(timedOut).startsWith("[用户未作答]");
        // 超时用例的 qid 从新一次 send 截获
        AtomicReference<String> qid2 = new AtomicReference<>();
        CountDownLatch sent2 = new CountDownLatch(1);
        captureQuestionId(qid2, sent2);
        String ghost = expiredTool.askUser("另一个?", null, null, OPTIONS_JSON, false, false, toolCtx());
        assertThat(ghost).startsWith("[用户未作答]");
        assertThat(askRegistry.answer(qid2.get(), "alice", AskUserRegistry.DISMISS_SENTINEL)).isFalse();
    }

    @Test
    @DisplayName("dismiss 与选项点击竞态:complete CAS 一个成功一个 false")
    void dismiss_vs_option_click_cas() throws Exception {
        CompletableFuture<String> f1 = registerPending("q-cas-1", "alice", "conv-1");
        assertThat(askRegistry.answer("q-cas-1", "alice", AskUserRegistry.DISMISS_SENTINEL)).isTrue();
        assertThat(askRegistry.answer("q-cas-1", "alice", "Docker(推荐)")).isFalse();
        assertThat(f1.get(1, TimeUnit.SECONDS)).isEqualTo(AskUserRegistry.DISMISS_SENTINEL);

        // 反方向:选项点击先行,dismiss 后到 false
        CompletableFuture<String> f2 = registerPending("q-cas-2", "alice", "conv-1");
        assertThat(askRegistry.answer("q-cas-2", "alice", "Docker(推荐)")).isTrue();
        assertThat(askRegistry.answer("q-cas-2", "alice", AskUserRegistry.DISMISS_SENTINEL)).isFalse();
        assertThat(f2.get(1, TimeUnit.SECONDS)).isEqualTo("Docker(推荐)");
    }

    @Test
    @DisplayName("DISMISS_SENTINEL 与 CANCELLED_SENTINEL 是两个不同值")
    void dismiss_sentinel_distinct_from_cancelled() {
        assertThat(AskUserRegistry.DISMISS_SENTINEL).isEqualTo("__ASKUSER_DISMISS__");
        assertThat(AskUserRegistry.DISMISS_SENTINEL)
                .isNotEqualTo(AskUserRegistry.CANCELLED_SENTINEL);
    }

    // ==================== answer 端点 mode ====================

    /**
     * WebConfiguration 是包私有嵌套类(AskUserRouterTest 之所以放根包),本测试在 askuser
     * 包,故反射构造 router —— 真 handler + 真 registry,无 Spring 上下文(同 AskUserRouterTest)。
     */
    private RouterFunction<ServerResponse> router() throws Exception {
        Class<?> wc = Class.forName("cn.wubo.spring.ai.loom.agent.LoomAgentConfiguration$WebConfiguration");
        var ctor = wc.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object web = ctor.newInstance();
        var m = wc.getDeclaredMethod("loomAgentAskRouter", AskUserRegistry.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        RouterFunction<ServerResponse> r = (RouterFunction<ServerResponse>) m.invoke(web, askRegistry);
        return r;
    }

    @Test
    @DisplayName("端点 mode=DISMISS:无 answer 文本也 200,complete 哨兵")
    void answer_endpoint_dismiss_mode_no_text_required() throws Exception {
        CompletableFuture<String> future = registerPending("q-d-1", "alice", "conv-1");
        ServerResponse resp = LoomAgentTestUtil.route(router(), "POST",
                "/spring/ai/loom/ask/q-d-1/answer", "{\"mode\":\"DISMISS\"}");
        assertThat(resp.statusCode().value()).isEqualTo(200);
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo(AskUserRegistry.DISMISS_SENTINEL);
    }

    @Test
    @DisplayName("端点 mode=DISMISS:answer 空白也合法(文本不参与)")
    void answer_endpoint_dismiss_mode_blank_answer_still_valid() throws Exception {
        CompletableFuture<String> future = registerPending("q-d-2", "alice", "conv-1");
        ServerResponse resp = LoomAgentTestUtil.route(router(), "POST",
                "/spring/ai/loom/ask/q-d-2/answer", "{\"mode\":\"DISMISS\",\"answer\":\"  \"}");
        assertThat(resp.statusCode().value()).isEqualTo(200);
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo(AskUserRegistry.DISMISS_SENTINEL);
    }

    @Test
    @DisplayName("端点缺省 mode 行为逐字节不变(A1):文本校验/400/200 全部照旧")
    void answer_endpoint_default_mode_unchanged() throws Exception {
        CompletableFuture<String> future = registerPending("q-d-3", "alice", "conv-1");
        ServerResponse resp = LoomAgentTestUtil.route(router(), "POST",
                "/spring/ai/loom/ask/q-d-3/answer", "{\"answer\":\"B\"}");
        assertThat(resp.statusCode().value()).isEqualTo(200);
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo("B");

        // 显式 mode=ANSWER 与缺省等价
        CompletableFuture<String> f2 = registerPending("q-d-4", "alice", "conv-1");
        ServerResponse r2 = LoomAgentTestUtil.route(router(), "POST",
                "/spring/ai/loom/ask/q-d-4/answer", "{\"mode\":\"ANSWER\",\"answer\":\"A\"}");
        assertThat(r2.statusCode().value()).isEqualTo(200);
        assertThat(f2.get(1, TimeUnit.SECONDS)).isEqualTo("A");

        // ANSWER 模式下空白文本仍 400(旧规则不因新分支松动)
        registerPending("q-d-5", "alice", "conv-1");
        ServerResponse r3 = LoomAgentTestUtil.route(router(), "POST",
                "/spring/ai/loom/ask/q-d-5/answer", "{\"answer\":\"  \"}");
        assertThat(r3.statusCode().value()).isEqualTo(400);

        // ANSWER 模式缺 answer 字段仍 400(与 mode=DISMISS 缺 answer 形成对照)
        registerPending("q-d-6", "alice", "conv-1");
        ServerResponse r4 = LoomAgentTestUtil.route(router(), "POST",
                "/spring/ai/loom/ask/q-d-6/answer", "{}");
        assertThat(r4.statusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("端点未知 mode → 400,不碰 future")
    void answer_endpoint_unknown_mode_400() throws Exception {
        CompletableFuture<String> future = registerPending("q-d-7", "alice", "conv-1");
        ServerResponse resp = LoomAgentTestUtil.route(router(), "POST",
                "/spring/ai/loom/ask/q-d-7/answer", "{\"mode\":\"TELEPORT\",\"answer\":\"A\"}");
        assertThat(resp.statusCode().value()).isEqualTo(400);
        assertThat(future.isDone()).isFalse();
    }

    @Test
    @DisplayName("端点 mode=DISMISS 竞态:已被抢先 complete → 404")
    void answer_endpoint_dismiss_race_404() throws Exception {
        CompletableFuture<String> future = registerPending("q-d-8", "alice", "conv-1");
        askRegistry.answer("q-d-8", "alice", "A"); // 选项点击先行
        ServerResponse resp = LoomAgentTestUtil.route(router(), "POST",
                "/spring/ai/loom/ask/q-d-8/answer", "{\"mode\":\"DISMISS\"}");
        assertThat(resp.statusCode().value()).isEqualTo(404);
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo("A");
    }

    // ==================== R21 / D-3:时钟接线 ====================

    /** 6-arg 兼容构造全 no-op 的真 StreamRunContext(不 mock:时钟行为必须是真实实现)。 */
    private static StreamRunContext realCtx() {
        return new StreamRunContext(new SteeringInbox(), new MemoryWriter() {
            @Override
            public void writeUser(org.springframework.ai.chat.messages.Message m) {
            }

            @Override
            public void writeAssistant(org.springframework.ai.chat.messages.Message m) {
            }
        }, s -> {
        }, s -> {
        }, i -> {
        }, () -> false);
    }

    @Test
    @DisplayName("D-3:askUser 阻塞区间不计入 activeElapsedMs(pause→冻结→resume→恢复),dismiss/回答路径均 finally resume")
    void askUser_block_pauses_run_clock_excluding_human_wait() throws Exception {
        StreamRunContext ctx = realCtx();
        ToolContext toolContext = new ToolContext(Map.of(
                "username", "alice",
                "parentConversationId", "conv-1",
                StreamRunContext.CONTEXT_KEY, ctx));

        AtomicReference<String> qidRef = new AtomicReference<>();
        CountDownLatch sent = new CountDownLatch(1);
        captureQuestionId(qidRef, sent);
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 30);

        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch returned = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            result.set(tool.askUser("选哪种?", null, null, OPTIONS_JSON, false, false, toolContext));
            returned.countDown();
        });
        worker.setDaemon(true);
        worker.start();
        assertThat(sent.await(3, TimeUnit.SECONDS)).isTrue();
        // send 之后工具立刻 pauseClock(同一方法顺序),再稍等让 pause 落定
        Thread.sleep(50);

        // 人工等待窗口:时钟必须冻结(pauseClock 变异移除 ⇒ 此段增长 ⇒ 红)
        long a1 = ctx.activeElapsedMs();
        Thread.sleep(250);
        long a2 = ctx.activeElapsedMs();
        assertThat(a2).as("等待用户期间活跃时钟必须暂停(D-3)").isEqualTo(a1);

        // dismiss 作答释放;finally 的 resumeClock 变异移除 ⇒ 返回后时钟仍冻结 ⇒ 下方红
        assertThat(askRegistry.answer(qidRef.get(), "alice", AskUserRegistry.DISMISS_SENTINEL)).isTrue();
        assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get()).isEqualTo(DISMISS_TEXT);

        Thread.sleep(80);
        long a3 = ctx.activeElapsedMs();
        assertThat(a3).as("工具返回后时钟必须恢复计时(resumeClock 在 finally,覆盖 dismiss 路径)")
                .isGreaterThanOrEqualTo(a2 + 60);
    }

    @Test
    @DisplayName("D-3:超时路径同样 finally resumeClock(人工等待不计,返回后恢复)")
    void askUser_timeout_path_resumes_clock() throws Exception {
        StreamRunContext ctx = realCtx();
        ToolContext toolContext = new ToolContext(Map.of(
                "username", "alice",
                "parentConversationId", "conv-1",
                StreamRunContext.CONTEXT_KEY, ctx));
        // timeoutSeconds=0:future.get 立即 TimeoutException,走 catch-return + finally
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 0);

        String r = tool.askUser("选哪种?", null, null, OPTIONS_JSON, false, false, toolContext);
        assertThat(r).startsWith("[用户未作答]");

        Thread.sleep(80);
        long before = ctx.activeElapsedMs();
        Thread.sleep(80);
        assertThat(ctx.activeElapsedMs()).as("超时返回后时钟已恢复(未停在 paused)").isGreaterThan(before);
    }

    @Test
    @DisplayName("toolContext 无 StreamRunContext(子任务/直连路径)→ 不暂停,行为不变")
    void askUser_without_run_context_still_works() throws Exception {
        // 经典 2-key toolContext(与 DefaultAskUserToolTest 同款):无任何时钟联动
        answerOnSendPlain("Docker(推荐)");
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);
        String r = tool.askUser("选哪种?", null, null, OPTIONS_JSON, false, false, toolCtx());
        assertThat(r).isEqualTo("[用户已回答] Docker(推荐)");
    }

    private void answerOnSendPlain(String answerText) throws Exception {
        doAnswer(inv -> {
            ChatResponseRecord rec = inv.getArgument(0);
            String qid = rec.askUser().questionId();
            Thread t = new Thread(() -> askRegistry.answer(qid, "alice", answerText));
            t.setDaemon(true);
            t.start();
            return null;
        }).when(emitter).send(any(Object.class), any(MediaType.class));
    }
}
