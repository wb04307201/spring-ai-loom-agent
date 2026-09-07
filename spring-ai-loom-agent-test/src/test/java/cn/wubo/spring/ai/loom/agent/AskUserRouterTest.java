package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.askuser.AskUserRegistry;
import cn.wubo.spring.ai.loom.agent.model.AskUserEvent;
import cn.wubo.spring.ai.loom.agent.model.AskUserOption;
import cn.wubo.spring.ai.loom.agent.testutil.LoomAgentTestUtil;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1 AskUser answer 端点路由测试(真 router + 真 Registry,无 Spring 上下文 ——
 * AdminRouterSpotTest 先例;spec §6 的"接线 IT"由本类等价覆盖,不依赖 DB)。
 */
@DisplayName("askUser answer 路由")
class AskUserRouterTest {

    private AskUserRegistry registry;
    private RouterFunction<ServerResponse> router;

    @BeforeEach
    void setUp() {
        registry = new AskUserRegistry();
        router = new LoomAgentConfiguration.WebConfiguration().loomAgentAskRouter(registry);
        UserContextHolder.setCurrentUser("alice");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    private CompletableFuture<String> registerPending(String qid, String user, String conv) {
        AskUserEvent ev = new AskUserEvent(qid, "问题", null, null,
                List.of(new AskUserOption("A", null), new AskUserOption("B", null)),
                false, false, 300L);
        CompletableFuture<String> future = new CompletableFuture<>();
        registry.register(new AskUserRegistry.PendingQuestion(ev, user, conv, future,
                System.currentTimeMillis()));
        return future;
    }

    @Test
    void answerHappyPath200AndCompletesFuture() throws Exception {
        CompletableFuture<String> future = registerPending("q-1", "alice", "conv-1");
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "POST",
                "/spring/ai/loom/ask/q-1/answer", "{\"answer\":\"B\"}");
        assertThat(resp).isNotNull();
        assertThat(resp.statusCode().value()).isEqualTo(200);
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo("B");
    }

    @Test
    void multiSelectArrayAnswerJoinedWithSemicolon() throws Exception {
        CompletableFuture<String> future = registerPending("q-2", "alice", "conv-1");
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "POST",
                "/spring/ai/loom/ask/q-2/answer", "{\"answer\":[\"A\",\"B\"]}");
        assertThat(resp.statusCode().value()).isEqualTo(200);
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo("A; B");
    }

    @Test
    void unknownQuestionId404() throws Exception {
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "POST",
                "/spring/ai/loom/ask/nope/answer", "{\"answer\":\"A\"}");
        assertThat(resp.statusCode().value()).isEqualTo(404);
        assertThat(((EntityResponse<?>) resp).entity()).isEqualTo(Map.of("error", "not found"));
    }

    @Test
    void crossUserAnswer404SameBodyAsUnknown() throws Exception {
        registerPending("q-3", "bob", "conv-9");
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "POST",
                "/spring/ai/loom/ask/q-3/answer", "{\"answer\":\"A\"}");
        assertThat(resp.statusCode().value()).isEqualTo(404);
        assertThat(((EntityResponse<?>) resp).entity()).isEqualTo(Map.of("error", "not found"));
        assertThat(registry.get("q-3").answer().isDone()).isFalse();
    }

    @Test
    void blankAnswer400() throws Exception {
        registerPending("q-4", "alice", "conv-1");
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "POST",
                "/spring/ai/loom/ask/q-4/answer", "{\"answer\":\"  \"}");
        assertThat(resp.statusCode().value()).isEqualTo(400);
        assertThat(registry.get("q-4").answer().isDone()).isFalse();
    }

    @Test
    void malformedJson400() throws Exception {
        registerPending("q-5", "alice", "conv-1");
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "POST",
                "/spring/ai/loom/ask/q-5/answer", "not-json");
        assertThat(resp.statusCode().value()).isEqualTo(400);
    }

    @Test
    void missingAnswerField400() throws Exception {
        registerPending("q-6", "alice", "conv-1");
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "POST",
                "/spring/ai/loom/ask/q-6/answer", "{}");
        assertThat(resp.statusCode().value()).isEqualTo(400);
    }

    @Test
    void raceWithCancelMaps404() throws Exception {
        CompletableFuture<String> future = registerPending("q-7", "alice", "conv-1");
        registry.cancelAll("alice", "conv-1"); // 模拟 stop 抢先
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "POST",
                "/spring/ai/loom/ask/q-7/answer", "{\"answer\":\"A\"}");
        assertThat(resp.statusCode().value()).isEqualTo(404);
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo(AskUserRegistry.CANCELLED_SENTINEL);
    }
}
