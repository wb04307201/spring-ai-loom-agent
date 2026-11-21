package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.model.SteeringAuditRow;
import cn.wubo.spring.ai.loom.agent.tool.ILoomChatSteeringRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Task 9(spec 2026-11-21 agentic-loop-steering S4)—— steering 注入审计接线回归锁。
 *
 * <p>锁定语义:
 * <ul>
 *   <li><b>每条注入必写审计行</b>:内层 drain 与外层续跑残留两条路径共用
 *       {@code injectSteeringMessages} 单一入口,record(convId, username, text,
 *       injectedAt, seq) 逐条落 {@code loom_chat_steering};</li>
 *   <li><b>seq 本 run 内单调</b>(跨内层 drain + 续跑持续递增;advisor 逐请求实例,
 *       天然 per-run 重启);</li>
 *   <li><b>未注入零审计</b>(A1 + R11):空 inbox / 护栏终态 NOT_INJECTED 的残留
 *       不写行 —— 审计表只记录真实注入,B2 豁免判定才是确定性的;</li>
 *   <li><b>降级</b>:record 抛异常绝不打断注入与聊天流(log warn 吞掉,ack 照发);</li>
 *   <li>仓储未接线(builder 缺省 null)零副作用 —— 存量 NoOp / Injection / Guardrail
 *       测试全部不改即可过,同时保护子任务/自定义链路径。</li>
 * </ul>
 * 变异:去掉 injectSteeringMessages 的 record 调用 ⇒ inject 用例红;去掉 try/catch
 * ⇒ degradation 用例红;在 emitTerminalAcks 里也写行 ⇒ terminal 用例红。
 */
@DisplayName("Task 9:steering 注入 → loom_chat_steering 审计接线(双路径 + 零注入零行 + 降级)")
class SteeringAuditWiringTest {

    private static final String CONV = "conv-audit";
    private static final String USER = "alice";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    // ==================== fixtures ====================

    /** 捕获型仓储:record 追加到内存列表(接口面最小实现,不触 DB)。 */
    private static final class CapturingSteeringRepo implements ILoomChatSteeringRepository {
        final List<SteeringAuditRow> rows = new CopyOnWriteArrayList<>();
        final AtomicInteger writes = new AtomicInteger();

        @Override
        public void record(String convId, String username, String text,
                           java.time.Instant injectedAt, int seq) {
            writes.incrementAndGet();
            rows.add(new SteeringAuditRow(rows.size() + 1L, convId, username, text, injectedAt, seq));
        }

        @Override
        public List<SteeringAuditRow> findByConversationId(String convId) {
            return rows.stream().filter(r -> r.conversationId().equals(convId)).toList();
        }

        @Override
        public int deleteByConversation(String convId) {
            // Task 10 R23 接口扩展(会话删除级联):本测试只验 record 接线,删除 no-op
            return 0;
        }
    }

    /** 必抛仓储:验证审计失败绝不掐断注入/聊天流。 */
    private static final class ThrowingSteeringRepo implements ILoomChatSteeringRepository {
        @Override
        public void record(String convId, String username, String text,
                           java.time.Instant injectedAt, int seq) {
            throw new IllegalStateException("db down");
        }

        @Override
        public List<SteeringAuditRow> findByConversationId(String convId) {
            return List.of();
        }

        @Override
        public int deleteByConversation(String convId) {
            // Task 10 R23 接口扩展:本测试只验 record 降级,删除路径不驱动
            return 0;
        }
    }

    private static StreamRunContext ctx(SteeringInbox inbox, List<String> acks) {
        return new StreamRunContext(inbox, new MemoryWriter() {
            @Override
            public void writeUser(Message m) {
            }

            @Override
            public void writeAssistant(Message m) {
            }
        }, s -> {
        }, acks::add, i -> {
        }, () -> false, id -> {
        });
    }

    private static ToolExecutionResult toolResultWithHistory() {
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("tc-1", "function", "noop", "{}")))
                .build();
        ToolResponseMessage toolResult = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("tc-1", "noop", "ok")))
                .build();
        return ToolExecutionResult.builder()
                .conversationHistory(List.of(
                        new UserMessage("原任务"),
                        toolCall,
                        toolResult))
                .build();
    }

    private static ChatClientRequest requestWith(StreamRunContext ctx) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage("原任务")), ToolCallingChatOptions.builder().build()))
                .context(Map.of(StreamRunContext.CONTEXT_KEY, ctx))
                .build();
    }

    private static ChatClientResponse responseWith(StreamRunContext ctx) {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("x")))))
                .context(Map.of(StreamRunContext.CONTEXT_KEY, ctx))
                .build();
    }

    private static LoomAgenticLoopAdvisor advisor(ILoomChatSteeringRepository repo) {
        return LoomAgenticLoopAdvisor.builder()
                .steeringAudit(repo, CONV, USER)
                .build();
    }

    /** 桩模型:每轮纯文本(无 tool_call),可预置/按轮注入 inbox。 */
    private static final class StubModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("a" + (calls.get() - 1)))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    private static List<ChatResponse> run(ChatModel model, StreamRunContext ctx,
                                          LoomAgenticLoopAdvisor advisor) {
        return ChatClient.builder(model).build().prompt().user("原任务")
                .advisors(advisor)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONV))
                .advisors(a -> a.param(StreamRunContext.CONTEXT_KEY, ctx))
                .stream().chatResponse().collectList().block(TIMEOUT);
    }

    // ==================== 内层 drain 路径 ====================

    @Test
    @DisplayName("内层 drain:每条注入写 1 行(convId/username/text 完整),多条 seq 从 1 递增")
    void inner_drain_injection_writes_audit_rows() {
        CapturingSteeringRepo repo = new CapturingSteeringRepo();
        SteeringInbox inbox = new SteeringInbox();
        List<String> acks = new ArrayList<>();
        StreamRunContext c = ctx(inbox, acks);
        inbox.offer("m-1", "🚀部署dev分支");
        inbox.offer("m-2", "改用蓝绿");

        advisor(repo).doGetNextInstructionsForToolCall(requestWith(c), responseWith(c), toolResultWithHistory());

        assertThat(repo.rows).hasSize(2);
        assertThat(repo.rows).allSatisfy(r -> {
            assertThat(r.conversationId()).isEqualTo(CONV);
            assertThat(r.username()).isEqualTo(USER);
        });
        assertThat(repo.rows).extracting(SteeringAuditRow::messageText)
                .containsExactly("🚀部署dev分支", "改用蓝绿");
        assertThat(repo.rows).extracting(SteeringAuditRow::seq).containsExactly(1, 2);
        assertThat(acks).as("审计接线不影响 ack 通道").containsExactly("m-1", "m-2");
    }

    // ==================== 外层续跑残留路径 ====================

    @Test
    @DisplayName("续跑残留注入同样写审计,seq 跨内层 drain + 续跑本 run 内持续递增")
    void continuation_residual_writes_audit() {
        CapturingSteeringRepo repo = new CapturingSteeringRepo();
        SteeringInbox inbox = new SteeringInbox();
        inbox.offer("m-0", "补充纠偏 🚀");
        List<String> acks = new ArrayList<>();
        StreamRunContext c = ctx(inbox, acks);
        StubModel model = new StubModel();

        run(model, c, advisor(repo));

        assertThat(model.calls.get()).as("残留触发续跑 = 2 迭代").isEqualTo(2);
        assertThat(repo.rows).hasSize(1);
        assertThat(repo.rows.get(0).messageText()).isEqualTo("补充纠偏 🚀");
        assertThat(repo.rows.get(0).conversationId()).isEqualTo(CONV);
        assertThat(repo.rows.get(0).username()).isEqualTo(USER);
        assertThat(repo.rows.get(0).seq()).as("续跑残留是本 run 首条注入 → seq=1").isEqualTo(1);
        assertThat(acks).containsExactly("m-0");
    }

    @Test
    @DisplayName("同 run 内层 drain 先注入 1 条、续跑残留再注入 1 条 → seq=1,2 单调不重复")
    void seq_monotonic_across_inner_and_continuation() {
        CapturingSteeringRepo repo = new CapturingSteeringRepo();
        SteeringInbox inbox = new SteeringInbox();
        List<String> acks = new ArrayList<>();
        StreamRunContext c = ctx(inbox, acks);
        // 模型桩:首轮返回 tool_call?纯文本模型无 tool call → 无内层 drain。
        // 改为手工先在内层钩子注入一条(同一 advisor 实例 = 同一 run),再走完整流触发续跑。
        LoomAgenticLoopAdvisor advisor = advisor(repo);
        inbox.offer("m-inner", "内层注入");
        advisor.doGetNextInstructionsForToolCall(requestWith(c), responseWith(c), toolResultWithHistory());
        assertThat(repo.rows).extracting(SteeringAuditRow::seq).containsExactly(1);

        inbox.offer("m-cont", "收口残留");
        StubModel model = new StubModel();
        ChatClient.builder(model).build().prompt().user("原任务")
                .advisors(advisor) // 同一实例 = 同一 run 的续跑
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONV))
                .advisors(a -> a.param(StreamRunContext.CONTEXT_KEY, c))
                .stream().chatResponse().collectList().block(TIMEOUT);

        assertThat(repo.rows).extracting(SteeringAuditRow::messageText)
                .containsExactly("内层注入", "收口残留");
        assertThat(repo.rows).extracting(SteeringAuditRow::seq).containsExactly(1, 2);
    }

    // ==================== 零注入 → 零审计(A1 + R11) ====================

    @Test
    @DisplayName("A1:无 steering 全程(inbox 恒空)→ 零审计行(续跑/收口不写)")
    void no_steering_writes_zero_audit_rows() {
        CapturingSteeringRepo repo = new CapturingSteeringRepo();
        SteeringInbox inbox = new SteeringInbox();
        List<String> acks = new ArrayList<>();
        StreamRunContext c = ctx(inbox, acks);
        StubModel model = new StubModel();

        run(model, c, advisor(repo));

        assertThat(model.calls.get()).isEqualTo(1);
        assertThat(repo.rows).as("A1:无 steering 的 run 审计表必须零行").isEmpty();
        assertThat(acks).isEmpty();
    }

    @Test
    @DisplayName("R11:护栏触顶 NOT_INJECTED 终态残留不写审计行(审计=真实注入)")
    void terminal_not_injected_writes_no_audit() {
        CapturingSteeringRepo repo = new CapturingSteeringRepo();
        SteeringInbox inbox = new SteeringInbox();
        inbox.offer("m-0", "太晚了");
        List<String> acks = new ArrayList<>();
        List<String> terminalAcks = new ArrayList<>();
        StreamRunContext c = new StreamRunContext(inbox, new MemoryWriter() {
            @Override
            public void writeUser(Message m) {
            }

            @Override
            public void writeAssistant(Message m) {
            }
        }, s -> {
        }, acks::add, i -> {
        }, () -> false, terminalAcks::add);
        StubModel model = new StubModel();

        // runTimeoutSeconds(0):收口护栏必触顶 → 残留走 NOT_INJECTED 终态,绝不注入
        run(model, c, LoomAgenticLoopAdvisor.builder()
                .steeringAudit(repo, CONV, USER)
                .runTimeoutSeconds(0)
                .build());

        assertThat(model.calls.get()).as("触顶不续跑").isEqualTo(1);
        assertThat(terminalAcks).containsExactly("m-0");
        assertThat(repo.rows).as("未注入的消息绝不能进审计表").isEmpty();
    }

    // ==================== 降级 + 未接线 ====================

    @Test
    @DisplayName("record 抛异常不打断注入:USER 行入 instructions、ack 照发、流不炸")
    void audit_failure_does_not_cut_injection() {
        SteeringInbox inbox = new SteeringInbox();
        List<String> acks = new ArrayList<>();
        StreamRunContext c = ctx(inbox, acks);
        inbox.offer("m-1", "照常注入");
        ToolExecutionResult ter = toolResultWithHistory();

        LoomAgenticLoopAdvisor advisor = advisor(new ThrowingSteeringRepo());
        List<Message> out = new ArrayList<>();
        assertThatCode(() -> {
            List<Message> r = advisor.doGetNextInstructionsForToolCall(requestWith(c), responseWith(c), ter);
            out.addAll(r);
        }).doesNotThrowAnyException();

        assertThat(out).hasSize(ter.conversationHistory().size() + 1);
        assertThat(out.get(out.size() - 1)).isInstanceOf(UserMessage.class);
        assertThat(acks).as("审计失败不得吞掉 ack 转正通道").containsExactly("m-1");
    }

    @Test
    @DisplayName("仓储未接线(builder 缺省)注入照常、零审计零异常 —— 子任务/存量链保护")
    void null_repository_is_noop() {
        SteeringInbox inbox = new SteeringInbox();
        List<String> acks = new ArrayList<>();
        StreamRunContext c = ctx(inbox, acks);
        inbox.offer("m-1", "无仓储注入");
        ToolExecutionResult ter = toolResultWithHistory();

        LoomAgenticLoopAdvisor advisor = LoomAgenticLoopAdvisor.builder().build();
        List<Message> out = advisor.doGetNextInstructionsForToolCall(requestWith(c), responseWith(c), ter);

        assertThat(out).hasSize(ter.conversationHistory().size() + 1);
        assertThat(acks).containsExactly("m-1");
    }
}
