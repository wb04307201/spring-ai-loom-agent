package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.LoomAgentConfiguration;
import cn.wubo.spring.ai.loom.agent.askuser.AskUserRegistry;
import cn.wubo.spring.ai.loom.agent.stream.SseEmitterRegistry;
import cn.wubo.spring.ai.loom.agent.token.ChatUsageService;
import cn.wubo.spring.ai.loom.agent.user.IUserConversation;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 7(spec 2026-11-21 §5.2)steer 端点回归锁。直接构造 SseController(真 registry +
 * mock 归属/依赖),锁定:活跃 run 入队 {queued:true,messageId} / 无 run {queued:false} /
 * 8KB 超限 400 / inbox 满 429(Review Focus 5)/ 跨用户 403 / emoji 文本完整 round-trip。
 */
@DisplayName("Task 7:steer 端点(归属/8KB/429/queued/emoji)")
class SteerEndpointTest {

    private static final String USER = "alice";
    private static final String CONV = "conv-steer";

    private SseEmitterRegistry registry;
    private IUserConversation userConversation;
    private LoomAgentConfiguration.ChatConfiguration.SseController controller;

    @BeforeEach
    void setup() {
        registry = new SseEmitterRegistry();
        userConversation = mock(IUserConversation.class);
        when(userConversation.exists(any())).thenReturn(true);
        controller = new LoomAgentConfiguration.ChatConfiguration.SseController(
                mock(IChat.class), registry, mock(AskUserRegistry.class),
                mock(ChatUsageService.class), mock(ChatMemory.class), userConversation);
        UserContextHolder.setCurrentUser(USER);
    }

    @AfterEach
    void teardown() {
        UserContextHolder.clear();
    }

    private SseEmitterRegistry.Entry registerRun() {
        return registry.register(USER, CONV, mock(SseEmitter.class), mock(Disposable.class), null);
    }

    private ResponseEntity<Map<String, Object>> steer(String message) {
        return controller.steer(CONV, Map.of("message", message));
    }

    @Test
    @DisplayName("活跃 run → {queued:true, messageId},消息入 inbox")
    void steer_queued_when_active() {
        SseEmitterRegistry.Entry entry = registerRun();
        ResponseEntity<Map<String, Object>> resp = steer("补充一下");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("queued")).isEqualTo(true);
        assertThat(resp.getBody().get("messageId")).asString().isNotBlank();
        assertThat(entry.inbox().depth()).isEqualTo(1);
        assertThat(entry.inbox().drain())
                .extracting(SteeringInbox.SteeringMessage::text).containsExactly("补充一下");
    }

    @Test
    @DisplayName("无活跃 run → {queued:false}(前端回退普通发送)")
    void steer_false_when_no_run() {
        ResponseEntity<Map<String, Object>> resp = steer("补充");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("queued")).isEqualTo(false);
    }

    @Test
    @DisplayName("超 8KB(8193 字符)→ 400")
    void steer_400_over_8kb() {
        registerRun();
        ResponseEntity<Map<String, Object>> resp = steer("x".repeat(8193));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("inbox 深度满 20 → 429(Review Focus 5)")
    void steer_429_inbox_full() {
        SseEmitterRegistry.Entry entry = registerRun();
        for (int i = 0; i < SteeringInbox.MAX_DEPTH; i++) {
            assertThat(entry.inbox().offer("f" + i, "x")).isTrue();
        }
        ResponseEntity<Map<String, Object>> resp = steer("第21条");
        assertThat(resp.getStatusCode().value()).isEqualTo(429);
    }

    @Test
    @DisplayName("跨用户(会话不属于 caller)→ 403")
    void steer_403_cross_user() {
        when(userConversation.exists(any())).thenReturn(false);
        registerRun();
        ResponseEntity<Map<String, Object>> resp = steer("越权");
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("emoji 文本经 steer → inbox 完整 round-trip(Review Focus 5,GBK 前科)")
    void steer_emoji_roundtrip() {
        SseEmitterRegistry.Entry entry = registerRun();
        String text = "🚀部署到生产环境🎉";
        ResponseEntity<Map<String, Object>> resp = steer(text);
        assertThat(resp.getBody().get("queued")).isEqualTo(true);
        List<SteeringInbox.SteeringMessage> drained = entry.inbox().drain();
        assertThat(drained).hasSize(1);
        assertThat(drained.get(0).text()).isEqualTo(text);
    }

    @Test
    @DisplayName("空消息 → 400;正在收口(closing)的 run → {queued:false}")
    void steer_blank_400_and_closing_false() {
        SseEmitterRegistry.Entry entry = registerRun();
        assertThat(steer("  ").getStatusCode().value()).isEqualTo(400);
        entry.inbox().close();
        ResponseEntity<Map<String, Object>> resp = steer("收口中");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("queued")).isEqualTo(false);
    }
}