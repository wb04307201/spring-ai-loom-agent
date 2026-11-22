package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.LoomAgentConfiguration;
import cn.wubo.spring.ai.loom.agent.askuser.AskUserRegistry;
import cn.wubo.spring.ai.loom.agent.capability.CapabilityService;
import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge;
import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.model.ChatRequestRecord;
import cn.wubo.spring.ai.loom.agent.model.ChatResponseRecord;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import cn.wubo.spring.ai.loom.agent.stream.SseEmitterRegistry;
import cn.wubo.spring.ai.loom.agent.token.ChatUsageService;
import cn.wubo.spring.ai.loom.agent.tool.IToolCallLogRepository;
import cn.wubo.spring.ai.loom.agent.user.IUserConversation;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 15(spec 2026-11-21 A1):无 steering 运行 SSE 帧类型序列契约锁。
 *
 * <p><b>A1 退化契约</b>:agentic loop 改造(Task 5-7)后,一次"无插话"运行产出的帧序列必须与
 * 改造前逐类型等价 —— 只有内容帧(content/reasoningContent),<b>零 steerAck、零 iteration 帧</b>
 * (迭代 0 不发帧;空 inbox → 内层 drain 空转、外层收口零重入)。这是"新帧只随新行为出现"的
 * 向后兼容底线:旧前端忽略未知分量,A1 保证旧前端行为逐字节不变。
 *
 * <p><b>harness(端到端,非近似)</b>:真 {@link LoomAgentConfiguration.ChatConfiguration.SseController}
 * (Task 6 生产接线:StreamRunContext 的 ack/iteration/terminalAck 发射器 → emitter.send)+
 * 真 {@link DefaultChat}(真 ChatClient + 桩模型,per-request 挂真 {@link LoomAgenticLoopAdvisor})+
 * 真 {@link SseEmitter} —— 未 initialize 的 emitter 把全部 send 缓冲进
 * {@code earlySendAttempts}(spring-webmvc 7.0.9 私有字段,反射读取;同
 * {@code DisconnectDisposeIT} 反射 errorCallback 的先例),据此捕获<b>真实帧序列与顺序</b>。
 *
 * <p><b>防空转哨兵(正向对照)</b>:{@code steering_run_emits_ack_and_iteration_frames}
 * 走同一 harness 注入一条插话,断言 ack + iteration 帧<b>确实出现</b> —— 若 emitter/ctx 接线断裂
 * 导致帧根本发不出来,负向 A1 用例会"假绿"(没帧 ≠ 不该有帧)。哨兵红而 A1 用例绿 = 帧丢失,
 * 必须视为回归。
 *
 * <p>content/reasoning/askUser/subTask 帧的<b>形状</b>不变性由 Task 2 的
 * {@code ChatResponseRecordFrameTest} 锁定;本类锁定<b>序列</b>:无插话 = 纯内容帧。
 */
@DisplayName("Task 15/A1:无 steering 帧序列与改造前一致(零 steerAck/iteration)+ 正向对照防空转")
class NoSteeringFrameSequenceContractTest {

    private static final String USER = "a1-user";
    private static final String CONV = "conv-a1-frame";

    private SseEmitterRegistry registry;
    private RecordingMemory memory;
    private ChatUsageService usage;

    @BeforeEach
    void setup() {
        registry = new SseEmitterRegistry();
        memory = new RecordingMemory();
        usage = mock(ChatUsageService.class);
        UserContextHolder.setCurrentUser(USER);
    }

    @AfterEach
    void teardown() {
        UserContextHolder.clear();
    }

    // ==================== harness ====================

    /**
     * 真 SseController + 真 DefaultChat + 真 ChatClient(桩模型)+ 真 LoomMemoryAdvisor,
     * 与生产链同构(chatClient bean 的 defaultAdvisors 即含 memory advisor)。
     */
    private SseEmitter runStreaming(ChatModel model) {
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(new LoomMemoryAdvisor(memory))
                .build();

        IMcp mcp = mock(IMcp.class);
        when(mcp.getVisibleToolCallbackProvider(anyString(), any())).thenReturn(null);
        CapabilityService capabilityService = mock(CapabilityService.class);
        when(capabilityService.visibleToolGroupsFor(anyString())).thenReturn(java.util.Set.of());
        when(capabilityService.allowedCapabilityIdsFor(anyString(), any())).thenReturn(java.util.Set.of());
        when(capabilityService.filterEmbedToolsByCapabilityIds(anyList(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        ISkillStorage skillStorage = mock(ISkillStorage.class);
        when(skillStorage.list(anyString())).thenReturn(List.of());
        IKnowledge knowledge = mock(IKnowledge.class);
        when(knowledge.list(anyString())).thenReturn(List.of());
        IUserConversation userConversation = mock(IUserConversation.class);
        when(userConversation.exists(any())).thenReturn(true);

        DefaultChat chat = new DefaultChat(chatClient, mcp, List.of(), userConversation,
                mock(IFile.class), skillStorage, knowledge, new LoomAgentProperties(),
                mock(IToolCallLogRepository.class), capabilityService, usage);

        LoomAgentConfiguration.ChatConfiguration.SseController controller =
                new LoomAgentConfiguration.ChatConfiguration.SseController(
                        chat, registry, mock(AskUserRegistry.class), usage, memory,
                        mock(IUserConversation.class));
        return controller.stream(new ChatRequestRecord("原任务", CONV, null, null, null),
                new MockHttpServletRequest());
    }

    /** 反射读未初始化 SseEmitter 的 earlySendAttempts(帧按 send 顺序缓冲;LinkedHashSet)。 */
    @SuppressWarnings("unchecked")
    private static List<ChatResponseRecord> frames(SseEmitter emitter) throws Exception {
        Field f = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
        f.setAccessible(true);
        java.util.Set<ResponseBodyEmitter.DataWithMediaType> buf =
                (java.util.Set<ResponseBodyEmitter.DataWithMediaType>) f.get(emitter);
        List<ChatResponseRecord> out = new ArrayList<>();
        for (ResponseBodyEmitter.DataWithMediaType d : buf) {
            // runAsync 线程可能仍在写;只收 ChatResponseRecord(本链唯一的 send 载荷类型)
            if (d.getData() instanceof ChatResponseRecord rec) {
                out.add(rec);
            }
        }
        return out;
    }

    /** 轮询直到流结束(registry 清理 = subscribe onComplete 已跑)或超时,再取帧快照。 */
    private static List<ChatResponseRecord> awaitFrames(SseEmitter emitter,
                                                        SseEmitterRegistry registry,
                                                        int expectedFrames) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (registry.get(USER, CONV) == null && frames(emitter).size() >= expectedFrames) {
                break;
            }
            Thread.sleep(25);
        }
        // 终帧可能晚于 registry 清理几十 ms(同一 lambda 顺序内实际不会)—— 再取一次快照
        return frames(emitter);
    }

    /** 帧类型序列描述符:C=内容帧 / K=steerAck / I=iteration / ?=其他事件帧。 */
    private static List<String> describe(List<ChatResponseRecord> frames) {
        return frames.stream().map(f -> {
            if (f.iteration() != null) {
                return "I:" + f.iteration().index();
            }
            if (f.steerAck() != null) {
                return "K:" + f.steerAck().status().charAt(0) + ":" + f.steerAck().messageId();
            }
            if (f.askUser() != null || f.subTaskEvent() != null) {
                return "?";
            }
            return "C:" + f.content() + ":" + f.reasoningContent();
        }).toList();
    }

    /**
     * 单轮/多轮桩模型:第 n 次 stream 返回文本 "r{n}"(reasoning 仅第 1 轮携带,
     * cumulative 单帧 = delta 全量);round1 可在 latch 上阻塞供测试线程插话注入。
     */
    private static final class RoundStubModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        volatile CountDownLatch blockOnFirstCall;

        @Override
        public ChatResponse call(Prompt prompt) {
            return next();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            int n = calls.incrementAndGet();
            if (n == 1 && blockOnFirstCall != null) {
                try {
                    if (!blockOnFirstCall.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("stub model release timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return Flux.just(next());
        }

        private ChatResponse next() {
            int n = calls.get();
            AssistantMessage output = n == 1
                    ? AssistantMessage.builder().content("r1")
                            .properties(Map.of("reasoningContent", "think-1")).build()
                    : new AssistantMessage("r2");
            return new ChatResponse(List.of(new Generation(output)));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    // ==================== A1:无 steering → 纯内容帧序列 ====================

    @Test
    @DisplayName("A1:空 inbox 单迭代运行 → 帧序列 = [内容帧],零 steerAck / 零 iteration;记忆/reasoning 行与改造前等量")
    void empty_inbox_frame_sequence_identical_to_baseline() throws Exception {
        SseEmitter emitter = runStreaming(new RoundStubModel());
        List<ChatResponseRecord> fs = awaitFrames(emitter, registry, 1);

        // 序列逐位锁定:改造前形态 = 每 chunk 一帧 (text, reasoningDelta);本桩 1 chunk 1 帧
        assertThat(describe(fs)).containsExactly("C:r1:think-1");
        // 分量级负锁:任何帧都不得携带新事件分量(A1:旧前端逐字节不变)
        assertThat(fs).allSatisfy(f -> {
            assertThat(f.steerAck()).as("无 steering 不得出现 steerAck 帧").isNull();
            assertThat(f.iteration()).as("迭代 0 不得出现 iteration 帧").isNull();
            assertThat(f.askUser()).isNull();
            assertThat(f.subTaskEvent()).isNull();
        });
        // 帧形状:2-arg 内容帧形态(content/reasoningContent 有值,其余分量 null)
        ChatResponseRecord only = fs.get(0);
        assertThat(only.content()).isEqualTo("r1");
        assertThat(only.reasoningContent()).isEqualTo("think-1");
        // 记忆行 = turn-initial USER + 迭代收口 ASSISTANT(与改造前聚合单写等量,无双写)
        assertThat(memory.singleWrites).extracting(Message::getMessageType)
                .containsExactly(MessageType.USER, MessageType.ASSISTANT);
        assertThat(memory.mergedWrites).as("perIterationMode 已置位 → after() 聚合单写跳过").isEmpty();
        // reasoning 恰 1 行(迭代收口 flush;SseController onComplete 二次 flush 空缓冲零写)
        org.mockito.Mockito.verify(usage, org.mockito.Mockito.times(1)).saveReasoning(CONV, "think-1");
    }

    // ==================== 正向对照:防空转哨兵 ====================

    @Test
    @DisplayName("哨兵:注入一条插话 → 同一 harness 必须观测到 steerAck(INJECTED) + iteration(1) 帧")
    void steering_run_emits_ack_and_iteration_frames() throws Exception {
        RoundStubModel model = new RoundStubModel();
        model.blockOnFirstCall = new CountDownLatch(1);
        SseEmitter emitter = runStreaming(model);

        // 首轮模型调用被 latch 阻塞,此时 steer 必落在 iteration 0 的收口窗口内
        SseEmitterRegistry.Entry entry = registry.get(USER, CONV);
        assertThat(entry).as("run 已注册").isNotNull();
        assertThat(entry.inbox().offer("m-steer", "补充一句")).as("入队成功").isTrue();
        model.blockOnFirstCall.countDown();

        List<ChatResponseRecord> fs = awaitFrames(emitter, registry, 4);

        // 序列:内容帧(r1) → steerAck 转正 → iteration(1) → 续跑内容帧(r2)
        assertThat(describe(fs))
                .as("harness 能观测 ack/iteration 帧 —— A1 负断言非空转")
                .containsExactly("C:r1:think-1", "K:I:m-steer", "I:1", "C:r2:null");
        assertThat(fs).anySatisfy(f -> {
            assertThat(f.steerAck()).isNotNull();
            assertThat(f.steerAck().status()).isEqualTo(cn.wubo.spring.ai.loom.agent.model.SteerAck.STATUS_INJECTED);
        });
        assertThat(fs).anySatisfy(f -> {
            assertThat(f.iteration()).isNotNull();
            assertThat(f.iteration().index()).isEqualTo(1);
        });
    }

    // ==================== helpers ====================

    /** 记录型 ChatMemory fake(同 ReasoningPerIterationTest 惯例:两个 add 重载分开记)。 */
    private static final class RecordingMemory implements ChatMemory {
        final List<Message> singleWrites = new CopyOnWriteArrayList<>();
        final List<List<Message>> mergedWrites = new CopyOnWriteArrayList<>();

        @Override
        public void add(String conversationId, Message message) {
            singleWrites.add(message);
        }

        @Override
        public void add(String conversationId, List<Message> messages) {
            mergedWrites.add(List.copyOf(messages));
        }

        @Override
        public List<Message> get(String conversationId) {
            return List.of();
        }

        @Override
        public void clear(String conversationId) {
        }
    }
}
