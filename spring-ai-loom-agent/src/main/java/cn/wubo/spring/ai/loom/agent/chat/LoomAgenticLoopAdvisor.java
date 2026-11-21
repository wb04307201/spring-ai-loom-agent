package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.tool.ILoomChatSteeringRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loom agentic loop advisor —— 子类化 Spring AI {@link ToolCallingAdvisor},接管主聊天
 * 工具调用循环的迭代边界(spec 2026-11-21 agentic-loop-steering,Task 5 骨架 / Task 6
 * 迭代收口写 / Task 7 steering 注入 + inbox 驱动续跑 + 护栏 / Task 9 注入审计接线 S4)。
 *
 * <p><b>S1 抑制语义(已按 spring-ai-client-chat 2.0.2 源核实,回归锁
 * {@code LoomAgenticLoopAdvisorNoOpTest})</b>:
 * {@code DefaultChatClient.autoRegisterToolCallingAdvisor()} 检测
 * {@code advisors.anyMatch(a -> a instanceof ToolAdvisor)},命中即跳过默认
 * {@link ToolCallingAdvisor} 注册;而 {@code ToolCallingAdvisor implements ... ToolAdvisor},
 * 故本类(继承之)经 per-request {@code requestSpec.advisors(...)} 挂载后,主聊天链上
 * <b>恰好一个 ToolAdvisor</b>({@code validateSingleToolAdvisor()} 不抛)。
 *
 * <p><b>A5 子任务排除</b>:本类只在 {@code DefaultChat.stream()} 逐请求挂载。绝不可注册为
 * {@code chatClient} bean 的 defaultAdvisor 或共享 bean —— 子任务链
 * ({@code DefaultSubTaskExecutor} 复用同一 {@code ChatClient} bean)会把 defaultAdvisor
 * 继承进链,而子任务无 SSE、无 steering 语义;缺本 advisor 时 Spring AI 自动注册默认
 * ToolCallingAdvisor,子任务行为与改造前逐字节一致。回归锁 {@code SubTaskLoopExclusionTest}。
 *
 * <p><b>A1 退化契约</b>:四个循环钩子先从 advisor context 读取
 * {@link StreamRunContext}({@link StreamRunContext#CONTEXT_KEY}):
 * <ul>
 *   <li>context 无该 key(子任务 / 自定义 IChat / 非 SSE 路径)→ 钩子<b>纯 super</b>;</li>
 *   <li>context 有该 key 但 inbox 恒空(无 steering)→ 内层 drain 空转返回 super 原列表、
 *       外层收口残留为空不续跑、迭代 0 不发 iteration 帧 —— 帧序列/记忆行/usage 行
 *       与改造前逐行等价(A1,回归锁 NoOp/ReasoningPerIteration/UsageNoDoubleCount)。</li>
 * </ul>
 *
 * <p><b>Task 7 steering 消费(本类核心)</b>:
 * <ol>
 *   <li><b>内层注入</b>({@link #doGetNextInstructionsForToolCall} / {@code ...Stream}):
 *       工具结果就位后、下一轮模型调用前 drain inbox,每条 steering 经
 *       {@link MemoryWriter#writeUser} 落 USER 行 + append 进 instructions +
 *       {@link StreamRunContext#emitAck} 转正帧。<b>A4 注入位置</b>:super 返回的
 *       conversationHistory 以 ToolResponseMessage 收尾,USER 行 append 其后 ——
 *       消息序列 [...assistant(tool_call), tool_result, USER(steering)]。
 *       注入前查 stop/runTimeout(D-3 内层查超时),触发则不注入,残留交外层收口可见处置。</li>
 *   <li><b>外层续跑</b>({@link #doFinalizeLoop} / {@code ...Stream}):迭代收口写
 *       (finalizeIteration,Task 6)后 {@code inbox.closeAndRemaining()} 原子收口 ——
 *       残留非空且护栏未触 → {@link SteeringInbox#reopen()} 重开接受窗口 + 残留注入
 *       下一迭代 prompt + 重入完整循环;空 → super 收口(run 结束,inbox 保持收口,
 *       后续 steer 被拒 → 前端回退普通发送,G2 收尾竞态)。</li>
 *   <li><b>护栏</b>(G6/D-3):{@code currentIteration() >= maxContinuations} 或
 *       {@code activeElapsedMs() >= runTimeoutSeconds*1000}(活跃计时,暂停区间不计)
 *       或 {@code isStopped()} → 不续跑,残留经 {@link StreamRunContext#emitTerminalAck}
 *       发 steerAck(NOT_INJECTED) 可见终态(R11/A2,绝不静默蒸发)。</li>
 * </ol>
 *
 * <p><b>续跑重入机制(已按 2.0.2 sources 核实 —— 对 brief "chain.copy(this).nextCall"
 * 字面的必要订正)</b>:{@code DefaultAroundAdvisorChain.copy(after)} 返回的是
 * {@code originalAdvisors.subList(indexOf(after)+1, size)} —— <b>严格排除 after 自身</b>。
 * 若续跑直接 {@code chain.copy(this).nextCall(req)},续跑迭代只过下游 advisor
 * (usage/ChatModel)单轮模型调用:<b>没有工具循环、没有 doFinalizeLoop 重入</b> ——
 * 续跑的 ASSISTANT/reasoning 逐迭代行将永久丢失(LoomMemoryAdvisor.after 因
 * perIterationMode 跳过聚合单写),steering 也无法再纠偏,违反 spec §5.4/§5.10。
 * 故续跑重入 = {@code this.adviseCall/adviseStream(contReq, 原 chain)} 递归穿过本 advisor
 * 自身:adviseCall/adviseStream 内部每模型轮仍走 {@code chain.copy(this).nextCall/nextStream}
 * (ToolCallingAdvisor:167/293)—— R16(c) 的实质要求"重入覆盖 order=100 的
 * ChatUsageRecordingAdvisor、用 copy(this) 而非新造链"逐轮成立(S3 逐轮 usage 行),
 * 且续跑迭代获得完整工具循环 + 内层 drain + 收口写 + 递归续跑(护栏封顶)。
 * copy 读 originalAdvisors 快照(非已 pop 的 deque),原 chain 引用可安全递归复用。
 * 上游 LoomMemoryAdvisor(order +200)不在 copy(this) 段内 → 续跑不重跑 before()
 * (turn-initial USER 不双写);steering USER 行由本类注入点写、ASSISTANT 行由收口写(D-1)。
 *
 * <p>流式拼接(spec §5.1 点 3):{@code doFinalizeLoopStream} 返回
 * {@code Flux.concat(本轮 finalEmissions, Flux.defer(续跑 adviseStream))} —— SSE 单连接
 * 不变,SseController 无感知;续跑 flux 惰性订阅,本轮完成后才起下一迭代。
 *
 * <p>Issue1 不变式延续:builder 默认 {@code conversationHistoryEnabled=true}(与
 * {@code DefaultChatClient} 在本链形态下对 auto-register advisor 的计算结果一致 ——
 * {@link LoomMemoryAdvisor} order=+200 严格上游于本 advisor 的 +300,不算下游),
 * 迭代间多轮 tool_use history 全量保留,回归锁见 NoOp 测试
 * {@code next_instructions_*_delegates_super_full_history}。
 */
public class LoomAgenticLoopAdvisor extends ToolCallingAdvisor {

  private static final Logger log = LoggerFactory.getLogger(LoomAgenticLoopAdvisor.class);

  /** 护栏默认值单一真源 = {@link LoomAgentProperties.LoopProperty}(Task 2 冻结 10/1800)。 */
  private static final LoomAgentProperties.LoopProperty LOOP_DEFAULTS = new LoomAgentProperties.LoopProperty();

  /**
   * 最近一轮的聚合响应快照 —— {@link #doAfterStream} 每轮写入,
   * {@link #doFinalizeLoopStream} 在迭代收口时读取(当前外层迭代的
   * ASSISTANT 文本 + advisor context 双重载体)。
   *
   * <p>advisor 实例每请求 new(DefaultChat.stream 逐请求 builder().build()),
   * SSE 单次订阅,per-round 顺序更新无并发问题;getAndSet(null) 消费即清,
   * 防御性避免跨 run 悬挂。续跑迭代经 Flux.concat 串行订阅,缓存逐迭代消费不串扰。
   */
  private final AtomicReference<ChatClientResponse> lastRoundAggregated = new AtomicReference<>();

  /**
   * 最近一轮送模型的请求快照 —— {@link #doBeforeCall}/{@link #doBeforeStream} 每轮写入。
   * 续跑 prompt 构造载体:finalize 钩子只拿得到响应,续跑 instructions 必须从末轮请求
   * 的完整 history 派生(+ 本迭代 ASSISTANT 文本 + 残留 steering USER 行)。
   */
  private final AtomicReference<ChatClientRequest> lastProcessedRequest = new AtomicReference<>();

  /** 外层续跑次数上限(G6):currentIteration() >= 此值即收口,残留可见终态。 */
  private final int maxContinuations;

  /** run 活跃时长上限秒数(D-3:暂停区间不计;区别于 chat.timeout-seconds 单次调用超时)。 */
  private final int runTimeoutSeconds;

  /**
   * steering 注入审计仓储(Task 9 / S4,spec §5.9)。null = 未接线(子任务直连构造 /
   * 消费方替换 advisor 不带审计)→ 注入路径零副作用,行为与 S4 前逐字节一致。
   */
  @Nullable
  private final ILoomChatSteeringRepository steeringAuditRepo;

  /** 审计行归属(逐请求由 DefaultChat 经 builder 显式传入;任一为 null 视同未接线)。 */
  @Nullable
  private final String steeringConvId;
  @Nullable
  private final String steeringUsername;

  /**
   * 本 run 注入序号(Task 9 "seq = 本 run 内注入序号",从 1 单调递增)。advisor
   * 逐请求 new(DefaultChat.stream per-request builder().build())⇒ 计数器天然
   * per-run 重启;内层 drain 与续跑残留共用同一 advisor 实例 ⇒ 跨迭代持续递增不重号。
   */
  private final AtomicInteger steeringSeq = new AtomicInteger(0);

  protected LoomAgenticLoopAdvisor(ToolCallingManager toolCallingManager,
                                   ToolExecutionEligibilityChecker toolExecutionEligibilityChecker,
                                   int advisorOrder,
                                   boolean conversationHistoryEnabled) {
    this(toolCallingManager, toolExecutionEligibilityChecker, advisorOrder, conversationHistoryEnabled,
        LOOP_DEFAULTS.getMaxContinuations(), LOOP_DEFAULTS.getRunTimeoutSeconds(),
        null, null, null);
  }

  protected LoomAgenticLoopAdvisor(ToolCallingManager toolCallingManager,
                                   ToolExecutionEligibilityChecker toolExecutionEligibilityChecker,
                                   int advisorOrder,
                                   boolean conversationHistoryEnabled,
                                   int maxContinuations,
                                   int runTimeoutSeconds) {
    this(toolCallingManager, toolExecutionEligibilityChecker, advisorOrder, conversationHistoryEnabled,
        maxContinuations, runTimeoutSeconds, null, null, null);
  }

  protected LoomAgenticLoopAdvisor(ToolCallingManager toolCallingManager,
                                   ToolExecutionEligibilityChecker toolExecutionEligibilityChecker,
                                   int advisorOrder,
                                   boolean conversationHistoryEnabled,
                                   int maxContinuations,
                                   int runTimeoutSeconds,
                                   @Nullable ILoomChatSteeringRepository steeringAuditRepo,
                                   @Nullable String steeringConvId,
                                   @Nullable String steeringUsername) {
    super(toolCallingManager, toolExecutionEligibilityChecker, advisorOrder, conversationHistoryEnabled);
    this.maxContinuations = maxContinuations;
    this.runTimeoutSeconds = runTimeoutSeconds;
    this.steeringAuditRepo = steeringAuditRepo;
    this.steeringConvId = steeringConvId;
    this.steeringUsername = steeringUsername;
  }

  @Override
  public String getName() {
    // 与默认 ToolCallingAdvisor 区分:validateSingleToolAdvisor 报错、观测日志、
    // advisor 链调试输出中都按此名标识"loom 循环已接管"
    return "Loom Agentic Loop Advisor";
  }

  /**
   * 从 advisor 请求 context 读取本轮 {@link StreamRunContext};null = 子任务/非 SSE 路径,
   * 钩子必须纯 super(A1)。key 缺席或类型不符都归并为 null(宽容消费方自定义 context)。
   */
  @Nullable
  static StreamRunContext runContextOf(@Nullable ChatClientRequest chatClientRequest) {
    if (chatClientRequest == null) {
      return null;
    }
    return runContextOf(chatClientRequest.context());
  }

  /**
   * 响应侧读取(R14 接线点之一):终端 ChatModel*Advisor 把 request context
   * {@code Map.copyOf} 进 response context,故聚合响应的 context 必带同一
   * {@link StreamRunContext} 引用;{@link LoomMemoryAdvisor#after} 同源读取。
   */
  @Nullable
  static StreamRunContext runContextOf(@Nullable ChatClientResponse chatClientResponse) {
    if (chatClientResponse == null) {
      return null;
    }
    return runContextOf(chatClientResponse.context());
  }

  @Nullable
  private static StreamRunContext runContextOf(@Nullable java.util.Map<String, Object> context) {
    if (context == null) {
      return null;
    }
    Object value = context.get(StreamRunContext.CONTEXT_KEY);
    return value instanceof StreamRunContext ctx ? ctx : null;
  }

  /**
   * 迭代收口文本:聚合响应所有 generation output 文本按序拼接(多 generation 合并为一行 =
   * spec §5.10"该迭代文本"by-design,每外层迭代一行,与逐 generation 写行等量信息)。
   * chatResponse 缺失 / generations 为空 → null(跳过 ASSISTANT 写,reasoning 仍 flush,
   * 等价 legacy after() 写空列表 → 0 行的路径)。
   *
   * <p><b>R17(A1 绑定权威)</b>:generations 存在但文本为空 → 返回空串,调用方<b>仍写行</b> ——
   * legacy {@code MessageChatMemoryAdvisor}/{@link LoomMemoryAdvisor#after} 对每个 generation
   * 无条件写 ASSISTANT 行(JdbcChatMemoryRepository 只滤 ToolResponseMessage/带 tool-call 的
   * AssistantMessage,<b>无 empty-text 过滤</b>),此处若跳空文本行即违反 A1"memory ONE
   * ASSISTANT row 完全相同"。可达路径:Qwen empty finishReason=STOP self-talk 失败模式。
   */
  @Nullable
  private static String aggregatedAssistantText(@Nullable ChatClientResponse response) {
    if (response == null || response.chatResponse() == null) {
      return null;
    }
    List<Generation> results = response.chatResponse().getResults();
    if (results == null || results.isEmpty()) {
      return null;
    }
    StringBuilder text = new StringBuilder();
    for (Generation generation : results) {
      if (generation == null || generation.getOutput() == null) {
        continue;
      }
      String chunk = generation.getOutput().getText();
      if (chunk != null) {
        text.append(chunk);
      }
    }
    return text.toString();
  }

  /**
   * 外层迭代结束的收口写(spec S3 + D-1 接管):
   * ASSISTANT 逐迭代行 → reasoning 逐迭代行(drain+复位 delta 状态)→ 标记
   * perIterationMode 让 {@link LoomMemoryAdvisor#after} 跳过聚合单写(防双写)。
   * 调用点无并发(Reactor 串行),内部异常一律吞成日志 —— 收口写失败绝不允许
   * 掐断主聊天流(ChatUsageService/MemoryWriter 同策略)。
   */
  private static void finalizeIteration(StreamRunContext ctx, @Nullable ChatClientResponse aggregated) {
    String assistantText = aggregatedAssistantText(aggregated);
    try {
      // R17:generations 存在即写行(含空文本)—— legacy after() 每 generation 无条件写;
      // assistantText==null 仅对应 chatResponse/generations 缺失(legacy 写空列表 0 行)→ 跳。
      if (assistantText != null) {
        ctx.memory().writeAssistant(new AssistantMessage(assistantText));
      }
    } catch (Exception ex) {
      log.warn("agentic loop 迭代 ASSISTANT 落库失败: {}", ex.getMessage());
    }
    try {
      ctx.flushReasoning();
      ctx.resetReasoningDelta();
    } catch (Exception ex) {
      log.warn("agentic loop 迭代 reasoning 落库失败: {}", ex.getMessage());
    }
    ctx.markPerIterationMode();
  }

  // ==================== 护栏(G6/D-3) ====================

  /**
   * 外层续跑护栏:迭代触顶 / run 活跃超时 / stop 任一触发即收口。
   * 活跃时长用 {@link StreamRunContext#activeElapsedMs()}(D-3:askUser 人等待
   * pauseClock 区间不计,Task 8 接线暂停;本类只正确消费活跃值)。
   */
  private boolean continuationAllowed(StreamRunContext ctx) {
    return ctx.currentIteration() < maxContinuations
        && ctx.activeElapsedMs() < runTimeoutSeconds * 1000L
        && !ctx.isStopped();
  }

  /**
   * 内层注入点护栏(D-3 内层查超时 + spec §5.7 stop 每个内层注入点检查):
   * 触发则本轮不注入 steering(消息留 inbox),当前迭代照常跑完,外层收口时
   * 残留经终帧可见处置 —— 不硬掐正在进行的模型调用(spec §5.7 run 预算语义)。
   */
  private boolean innerInjectionAllowed(StreamRunContext ctx) {
    return !ctx.isStopped() && ctx.activeElapsedMs() < runTimeoutSeconds * 1000L;
  }

  // ==================== steering 注入(A4 + R11) ====================

  /**
   * 把 steering 消息注入 instructions 并落 USER 行 + 写 {@code loom_chat_steering}
   * 审计行(Task 9 / S4)+ 发转正 ack 帧。
   * 内层 drain 与外层续跑残留共用此点 —— "行存在 ⇔ 注入发生"的确定性契约正依赖
   * 这单一入口(未注入终态走 {@link #emitTerminalAcks},绝不经此)。
   * memory 写失败降级日志不打断注入(prompt 送达 ≠ 持久化,与 MemoryWriter 契约同策略);
   * 审计写失败同样只 log.warn —— 审计缺行绝不掐断主聊天流(ToolCallHistory/usage 同策略)。
   */
  private void injectSteeringMessages(StreamRunContext ctx,
                                      List<SteeringInbox.SteeringMessage> messages,
                                      List<Message> instructionsOut) {
    for (SteeringInbox.SteeringMessage m : messages) {
      UserMessage userMessage = new UserMessage(m.text());
      try {
        ctx.memory().writeUser(userMessage);
      } catch (Exception ex) {
        log.warn("steering USER 行落库失败(注入继续): msg={} err={}", m.messageId(), ex.getMessage());
      }
      instructionsOut.add(userMessage);
      // Task 9(S4)审计接线:注入即写 loom_chat_steering(spec §5.9)。seq = 本 run 注入
      // 序号(steeringSeq 计数器,advisor per-request ⇒ per-run 从 1 单调);repo/归属
      // 未接线(任一 null)跳过 —— 子任务/替换 advisor 路径零副作用。异常吞成日志:
      // 审计行缺失由 Task 10 B2 豁免按"无行=未注入"降级处理,绝不反噬聊天流。
      if (steeringAuditRepo != null && steeringConvId != null && steeringUsername != null) {
        int seq = steeringSeq.incrementAndGet();
        try {
          steeringAuditRepo.record(steeringConvId, steeringUsername, m.text(), Instant.now(), seq);
        } catch (Exception ex) {
          log.warn("steering 审计行落库失败(注入继续): msg={} seq={} err={}",
              m.messageId(), seq, ex.getMessage());
        }
      }
      ctx.emitAck(m.messageId());
    }
  }

  /**
   * 残留可见终态(R11/A2):护栏触顶/stop/已终止收口时,未注入的排队消息逐条发
   * steerAck(NOT_INJECTED) 终帧(前端灰态气泡标"未发送(已停止)")。绝不静默蒸发。
   */
  private static void emitTerminalAcks(StreamRunContext ctx, List<SteeringInbox.SteeringMessage> residual) {
    for (SteeringInbox.SteeringMessage m : residual) {
      try {
        ctx.emitTerminalAck(m.messageId());
      } catch (Exception ex) {
        log.warn("steering 终态帧发射失败: msg={} err={}", m.messageId(), ex.getMessage());
      }
    }
  }

  /**
   * 内层 drain 公共逻辑:super instructions(tool_result 就位)之后 append steering
   * USER 行(A4)。ctx 缺席 / 护栏触发 / inbox 空 → 原样返回 super 列表(A1 同实例)。
   */
  private List<Message> drainAndInject(StreamRunContext ctx, List<Message> superInstructions) {
    if (!innerInjectionAllowed(ctx)) {
      return superInstructions;
    }
    List<SteeringInbox.SteeringMessage> drained = ctx.inbox().drain();
    if (drained.isEmpty()) {
      return superInstructions;
    }
    List<Message> instructions = new ArrayList<>(superInstructions);
    injectSteeringMessages(ctx, drained, instructions);
    return instructions;
  }

  /**
   * 续跑请求构造:末轮请求完整 history + 本迭代 ASSISTANT 文本 + 残留 steering USER 行
   * (注入即落 memory + ack)。context 沿用末轮(同一 StreamRunContext/CONVERSATION_ID 引用),
   * options 沿用末轮 ToolCallingChatOptions(重入 adviseCall/adviseStream 的循环前提)。
   */
  private ChatClientRequest buildContinuationRequest(StreamRunContext ctx,
                                                     ChatClientRequest lastRequest,
                                                     @Nullable ChatClientResponse lastRound,
                                                     List<SteeringInbox.SteeringMessage> residual) {
    List<Message> instructions = new ArrayList<>(lastRequest.prompt().getInstructions());
    String assistantText = aggregatedAssistantText(lastRound);
    if (assistantText != null) {
      instructions.add(new AssistantMessage(assistantText));
    }
    injectSteeringMessages(ctx, residual, instructions);
    return ChatClientRequest.builder()
        .prompt(new Prompt(instructions, lastRequest.prompt().getOptions()))
        .context(lastRequest.context())
        .build();
  }

  // ==================== 迭代边界 + 内层注入 + 外层续跑 ====================

  @Override
  protected ChatClientRequest doInitializeLoop(ChatClientRequest chatClientRequest,
                                               CallAdvisorChain callAdvisorChain) {
    beginIteration(chatClientRequest);
    return super.doInitializeLoop(chatClientRequest, callAdvisorChain);
  }

  @Override
  protected ChatClientRequest doInitializeLoopStream(ChatClientRequest chatClientRequest,
                                                     StreamAdvisorChain streamAdvisorChain) {
    beginIteration(chatClientRequest);
    return super.doInitializeLoopStream(chatClientRequest, streamAdvisorChain);
  }

  /**
   * 每外层迭代开始(含续跑重入)取迭代序号。迭代 0 = 首轮,<b>不发 iteration 帧</b>
   * (A1:无 steering 单迭代帧序列与改造前一致);续跑(idx>0)发 iteration 帧 +
   * 复位 reasoning delta 状态机(spec §5.4,前端按迭代分段)。ctx 缺席(子任务)零副作用。
   */
  private void beginIteration(ChatClientRequest chatClientRequest) {
    StreamRunContext ctx = runContextOf(chatClientRequest);
    if (ctx == null) {
      return;
    }
    int idx = ctx.nextIteration();
    if (idx > 0) {
      ctx.emitIteration(idx);
      ctx.resetReasoningDelta();
    }
  }

  @Override
  protected ChatClientRequest doBeforeCall(ChatClientRequest chatClientRequest,
                                           CallAdvisorChain callAdvisorChain) {
    ChatClientRequest processed = super.doBeforeCall(chatClientRequest, callAdvisorChain);
    // 末轮请求快照 = 续跑 prompt 的 history 载体(finalize 钩子只拿得到响应)
    lastProcessedRequest.set(processed);
    return processed;
  }

  @Override
  protected ChatClientRequest doBeforeStream(ChatClientRequest chatClientRequest,
                                             StreamAdvisorChain streamAdvisorChain) {
    ChatClientRequest processed = super.doBeforeStream(chatClientRequest, streamAdvisorChain);
    lastProcessedRequest.set(processed);
    return processed;
  }

  @Override
  protected ChatClientResponse doAfterStream(ChatClientResponse chatClientResponse,
                                             StreamAdvisorChain streamAdvisorChain) {
    // 流式路径每轮聚合响应在此透传(handledToolCallRecursion 内、finalize 之前)。
    // 缓存最后一轮 = 当前外层迭代收口文本载体(finalize hook 读取)。
    if (chatClientResponse != null) {
      lastRoundAggregated.set(chatClientResponse);
    }
    return super.doAfterStream(chatClientResponse, streamAdvisorChain);
  }

  @Override
  protected List<Message> doGetNextInstructionsForToolCall(ChatClientRequest chatClientRequest,
                                                           ChatClientResponse chatClientResponse,
                                                           ToolExecutionResult toolExecutionResult) {
    List<Message> instructions =
        super.doGetNextInstructionsForToolCall(chatClientRequest, chatClientResponse, toolExecutionResult);
    StreamRunContext ctx = runContextOf(chatClientRequest);
    if (ctx == null) {
      // A1/子任务路径:纯 super
      return instructions;
    }
    // A4:super 的 conversationHistory 以 tool_result 收尾,steering USER append 其后、
    // 下一轮模型调用之前 —— 正在执行的工具不中断(spec §5.2 注入语义)。
    return drainAndInject(ctx, instructions);
  }

  @Override
  protected List<Message> doGetNextInstructionsForToolCallStream(ChatClientRequest chatClientRequest,
                                                                 ChatClientResponse chatClientResponse,
                                                                 ToolExecutionResult toolExecutionResult) {
    List<Message> instructions =
        super.doGetNextInstructionsForToolCallStream(chatClientRequest, chatClientResponse, toolExecutionResult);
    StreamRunContext ctx = runContextOf(chatClientRequest);
    if (ctx == null) {
      return instructions;
    }
    return drainAndInject(ctx, instructions);
  }

  @Override
  protected ChatClientResponse doFinalizeLoop(ChatClientResponse chatClientResponse,
                                              CallAdvisorChain callAdvisorChain) {
    StreamRunContext ctx = runContextOf(chatClientResponse);
    if (ctx == null) {
      // 子任务/legacy:纯 super,after() 维持聚合单写(A1)
      return super.doFinalizeLoop(chatClientResponse, callAdvisorChain);
    }
    // Task 6 迭代收口写:ASSISTANT 逐迭代行 + reasoning 落库 + perIterationMode 标记
    finalizeIteration(ctx, chatClientResponse);

    // Task 7 外层续跑:原子收口(CAS closing + drain,G2 收尾竞态)—— 收口后新 offer
    // 一律被拒(steer 端点 queued:false → 前端回退普通发送),残留二选一可见处置。
    List<SteeringInbox.SteeringMessage> residual = ctx.inbox().closeAndRemaining();
    if (residual.isEmpty()) {
      // A1/G2:无残留 → 正常收口,零重入
      return super.doFinalizeLoop(chatClientResponse, callAdvisorChain);
    }
    ChatClientRequest lastRequest = lastProcessedRequest.get();
    if (!continuationAllowed(ctx) || lastRequest == null || !ctx.inbox().reopen()) {
      // 护栏触顶 / stop / inbox 已永久终止(stop/断连竞态)→ 残留可见终态(R11/A2)
      emitTerminalAcks(ctx, residual);
      return super.doFinalizeLoop(chatClientResponse, callAdvisorChain);
    }
    // 续跑:残留注入下一迭代 prompt(writeUser + ack),重入完整循环(见类 javadoc
    // 重入机制订正:递归 adviseCall 内部每轮 chain.copy(this).nextCall 覆盖 usage advisor)。
    ChatClientRequest continuation =
        buildContinuationRequest(ctx, lastRequest, chatClientResponse, residual);
    return this.adviseCall(continuation, callAdvisorChain);
  }

  @Override
  protected Flux<ChatClientResponse> doFinalizeLoopStream(Flux<ChatClientResponse> chatClientResponseFlux,
                                                          StreamAdvisorChain streamAdvisorChain) {
    // 流式路径收口时入参 finalEmissions flux 常为空(末轮自带 usage 不补发),文本从
    // doAfterStream 缓存的末轮聚合响应读取;其 context 带同一 StreamRunContext 引用
    // (R14:终端 ChatModel*Advisor 以 Map.copyOf(request.context()) 透传到响应 context)。
    ChatClientResponse lastRound = lastRoundAggregated.getAndSet(null);
    StreamRunContext ctx = runContextOf(lastRound);
    if (ctx == null) {
      return super.doFinalizeLoopStream(chatClientResponseFlux, streamAdvisorChain);
    }
    finalizeIteration(ctx, lastRound);

    List<SteeringInbox.SteeringMessage> residual = ctx.inbox().closeAndRemaining();
    if (residual.isEmpty()) {
      return super.doFinalizeLoopStream(chatClientResponseFlux, streamAdvisorChain);
    }
    ChatClientRequest lastRequest = lastProcessedRequest.get();
    if (!continuationAllowed(ctx) || lastRequest == null || !ctx.inbox().reopen()) {
      emitTerminalAcks(ctx, residual);
      return super.doFinalizeLoopStream(chatClientResponseFlux, streamAdvisorChain);
    }
    ChatClientRequest continuation = buildContinuationRequest(ctx, lastRequest, lastRound, residual);
    // spec §5.1 点 3:Flux.concat(本轮流, 续跑流) —— SSE 单连接不变。续跑 flux 惰性
    // (Flux.defer),本轮完成后才订阅;递归 adviseStream 的收口钩子可再续跑(护栏封顶)。
    return super.doFinalizeLoopStream(chatClientResponseFlux, streamAdvisorChain)
        .concatWith(Flux.defer(() -> this.adviseStream(continuation, streamAdvisorChain)));
  }

  // ==================== builder(仿 ToolCallingAdvisor.builder(),默认值一致) ====================

  public static Builder builder() {
    return new Builder();
  }

  /**
   * 仿 {@link ToolCallingAdvisor.Builder}:默认 {@code ToolCallingManager.builder().build()}、
   * 默认 eligibility checker、{@code advisorOrder = DEFAULT_ORDER(+300)}、
   * {@code conversationHistoryEnabled = true} —— 逐项与 2.0.2 auto-register 对主链的计算结果对齐,
   * 保证"替换"零行为漂移。护栏默认值取 {@link LoomAgentProperties.LoopProperty}(10/1800),
   * DefaultChat 挂载时显式传绑定值(消费方 yml 可配)。
   */
  public static class Builder extends ToolCallingAdvisor.Builder<Builder> {

    private int maxContinuations = LOOP_DEFAULTS.getMaxContinuations();
    private int runTimeoutSeconds = LOOP_DEFAULTS.getRunTimeoutSeconds();
    @Nullable
    private ILoomChatSteeringRepository steeringAuditRepo;
    @Nullable
    private String steeringConvId;
    @Nullable
    private String steeringUsername;

    protected Builder() {
    }

    /** 外层续跑次数上限(G6),默认 10。 */
    public Builder maxContinuations(int maxContinuations) {
      this.maxContinuations = maxContinuations;
      return this;
    }

    /** run 活跃时长上限秒数(D-3),默认 1800。 */
    public Builder runTimeoutSeconds(int runTimeoutSeconds) {
      this.runTimeoutSeconds = runTimeoutSeconds;
      return this;
    }

    /**
     * steering 注入审计接线(Task 9 / S4,spec §5.9):仓储 + 行归属(convId/username)。
     * 三者任一为 null ⇒ 审计整组关闭(注入路径零副作用)—— 子任务/替换 advisor/未配
     * 仓储 bean 的部署天然落此形态。DefaultChat 逐请求挂载时显式传入。
     */
    public Builder steeringAudit(@Nullable ILoomChatSteeringRepository repo,
                                 @Nullable String convId,
                                 @Nullable String username) {
      this.steeringAuditRepo = repo;
      this.steeringConvId = convId;
      this.steeringUsername = username;
      return this;
    }

    @Override
    protected Builder self() {
      return this;
    }

    @Override
    protected Builder newCopy() {
      return new Builder();
    }

    @Override
    public Builder copy() {
      Builder copy = newCopy();
      copy.toolCallingManager(getToolCallingManager());
      copy.toolExecutionEligibilityChecker(getToolExecutionEligibilityChecker());
      copy.advisorOrder(getAdvisorOrder());
      copy.conversationHistoryEnabled(isConversationHistoryEnabled());
      copy.maxContinuations(this.maxContinuations);
      copy.runTimeoutSeconds(this.runTimeoutSeconds);
      copy.steeringAudit(this.steeringAuditRepo, this.steeringConvId, this.steeringUsername);
      return copy;
    }

    @Override
    public LoomAgenticLoopAdvisor build() {
      return new LoomAgenticLoopAdvisor(getToolCallingManager(), getToolExecutionEligibilityChecker(),
          getAdvisorOrder(), isConversationHistoryEnabled(), maxContinuations, runTimeoutSeconds,
          steeringAuditRepo, steeringConvId, steeringUsername);
    }
  }
}