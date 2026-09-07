package cn.wubo.spring.ai.loom.agent.askuser;

import cn.wubo.spring.ai.loom.agent.model.AskUserEvent;
import cn.wubo.spring.ai.loom.agent.model.ChatResponseRecord;
import cn.wubo.spring.ai.loom.agent.stream.SseEmitterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DefaultAskUserToolTest {

    private static final String OPTIONS_JSON =
            "[{\"label\":\"Docker\",\"description\":\"容器部署\"},{\"label\":\"java -jar\",\"description\":null}]";

    private AskUserRegistry askRegistry;
    private SseEmitterRegistry sseRegistry;
    private SseEmitter emitter;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        askRegistry = new AskUserRegistry();
        sseRegistry = new SseEmitterRegistry();
        emitter = mock(SseEmitter.class);
        sseRegistry.register("alice", "conv-1", emitter, null, null);
        ctx = new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-1"));
    }

    /** send 时截获 questionId,另起线程提交答案 —— 模拟用户点卡片。 */
    private void answerOnSend(String answerText) throws Exception {
        doAnswer(inv -> {
            ChatResponseRecord rec = inv.getArgument(0);
            String qid = rec.askUser().questionId();
            Thread t = new Thread(() -> askRegistry.answer(qid, "alice", answerText));
            t.setDaemon(true);
            t.start();
            return null;
        }).when(emitter).send(any(Object.class), any(MediaType.class));
    }

    @Test
    void returnsAnswerAndPushesCompleteCardEvent() throws Exception {
        answerOnSend("java -jar");
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);

        String r = tool.askUser("选择部署方式?", "部署", "需要确认部署形态",
                OPTIONS_JSON, false, true, ctx);

        assertThat(r).isEqualTo("[用户已回答] java -jar");
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(emitter).send(sent.capture(), eq(MediaType.APPLICATION_JSON));
        AskUserEvent ev = ((ChatResponseRecord) sent.getValue()).askUser();
        assertThat(ev.question()).isEqualTo("选择部署方式?");
        assertThat(ev.header()).isEqualTo("部署");
        assertThat(ev.background()).isEqualTo("需要确认部署形态");
        assertThat(ev.options()).hasSize(2);
        assertThat(ev.options().get(0).label()).isEqualTo("Docker");
        assertThat(ev.options().get(0).description()).isEqualTo("容器部署");
        assertThat(ev.multiSelect()).isFalse();
        assertThat(ev.allowCustomInput()).isTrue();
        assertThat(ev.timeoutSeconds()).isEqualTo(5L);
        assertThat(((ChatResponseRecord) sent.getValue()).content()).isNull();
        // 提问结束后注册表已清空(finally remove)
        assertThat(askRegistry.get(ev.questionId())).isNull();
    }

    @Test
    void timesOutAndReturnsNotAnsweredText() {
        // timeoutSeconds=0 → future.get(0, SECONDS) 立即 TimeoutException
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 0);
        String r = tool.askUser("选哪个?", null, null, OPTIONS_JSON, false, false, ctx);
        assertThat(r).startsWith("[用户未作答]").contains("未在").contains("分钟内作答");
    }

    @Test
    void cancelSentinelMapsToStoppedText() throws Exception {
        doAnswer(inv -> {
            Thread t = new Thread(() -> askRegistry.cancelAll("alice", "conv-1"));
            t.setDaemon(true);
            t.start();
            return null;
        }).when(emitter).send(any(Object.class), any(MediaType.class));
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);

        String r = tool.askUser("选哪个?", null, null, OPTIONS_JSON, false, false, ctx);
        assertThat(r).isEqualTo("[用户未作答] 用户已停止本次对话,未作答。");
    }

    @Test
    void rejectsBlankQuestion() {
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);
        assertThat(tool.askUser("  ", null, null, OPTIONS_JSON, false, false, ctx))
                .startsWith("[提问失败]").contains("question");
        verifyNoInteractions(emitter);
    }

    @Test
    void rejectsOneOption() {
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);
        assertThat(tool.askUser("选哪个?", null, null, "[{\"label\":\"A\"}]", false, false, ctx))
                .startsWith("[提问失败]").contains("2-4");
    }

    @Test
    void rejectsFiveOptions() {
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);
        String five = "[{\"label\":\"A\"},{\"label\":\"B\"},{\"label\":\"C\"},{\"label\":\"D\"},{\"label\":\"E\"}]";
        assertThat(tool.askUser("选哪个?", null, null, five, false, false, ctx))
                .startsWith("[提问失败]").contains("2-4");
    }

    @Test
    void rejectsMalformedOptionsJson() {
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);
        assertThat(tool.askUser("选哪个?", null, null, "not-json", false, false, ctx))
                .startsWith("[提问失败]").contains("optionsJson");
    }

    @Test
    void rejectsBlankOptionLabel() {
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);
        assertThat(tool.askUser("选哪个?", null, null, "[{\"label\":\"A\"},{\"label\":\" \"}]", false, false, ctx))
                .startsWith("[提问失败]").contains("label");
    }

    @Test
    void returnsUnavailableWhenNoActiveStream() throws Exception {
        SseEmitterRegistry emptyRegistry = new SseEmitterRegistry(); // 未 register
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, emptyRegistry, 5);
        assertThat(tool.askUser("选哪个?", null, null, OPTIONS_JSON, false, false, ctx))
                .isEqualTo("[提问失败] 无法向用户提问(会话流不可用)。");
    }

    @Test
    void rejectsMissingToolContextIdentity() {
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);
        assertThat(tool.askUser("选哪个?", null, null, OPTIONS_JSON, false, false, new ToolContext(Map.of())))
                .startsWith("[提问失败]").contains("会话上下文");
    }
}
