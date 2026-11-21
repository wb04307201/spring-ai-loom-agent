package cn.wubo.spring.ai.loom.agent.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loom agentic loop advisor —— 子类化 Spring AI {@link ToolCallingAdvisor},接管主聊天
 * 工具调用循环的迭代边界(spec 2026-11-21 agentic-loop-steering,Task 5 骨架)。
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
 * <p><b>A1 退化契约(Task 6 接线完成)</b>:四个循环钩子先从 advisor context 读取
 * {@link StreamRunContext}({@link StreamRunContext#CONTEXT_KEY}):
 * <ul>
 *   <li>context 无该 key(子任务 / 自定义 IChat / 非 SSE 路径)→ 钩子<b>纯 super</b>;</li>
 *   <li>context 有该 key(主对话,SSE lambda 已经 request attribute 接线)→
 *       {@link #doFinalizeLoop} / {@link #doFinalizeLoopStream} 在外层迭代结束做
 *       <b>迭代收口写</b>:逐迭代 ASSISTANT 行({@link StreamRunContext#memory()}
 *       的 {@link MemoryWriter})+ 思考落库({@link StreamRunContext#flushReasoning()},
 *       每外层迭代一行 → spec S3)+ {@link StreamRunContext#markPerIterationMode()}
 *       (接管记忆写权,{@link LoomMemoryAdvisor#after} 据此跳过聚合单写,防双写)。</li>
 * </ul>
 * 无 steering 单迭代时:1 ASSISTANT + 1 reasoning = 与改造前逐行等价(A1)。
 * 流式路径收口文本来源:{@link #doAfterStream} 每轮缓存该轮聚合响应,
 * finalize hook 在最后一轮 doAfterStream 之后被调用(concatWith tail 的
 * {@code handleToolCallRecursion} 时序),取缓存即为当前外层迭代的聚合响应 ——
 * 其 context 携带同一 {@link StreamRunContext} 引用(R14:advisor param 经
 * 终端 ChatModel*Advisor 的 {@code Map.copyOf(request.context())} 透传到响应 context,
 * 已按 2.0.2 sources 核实,回归锁 {@code ReasoningPerIterationTest})。
 * Task 7 在本类接线 steering 消费(drain inbox → 注入 USER 行 → 续跑下一外层迭代,
 * 续跑开始处调 {@code ctx.emitIteration(idx)} + {@code ctx.resetReasoningDelta()})。
 *
 * <p>Issue1 不变式延续:builder 默认 {@code conversationHistoryEnabled=true}(与
 * {@code DefaultChatClient} 在本链形态下对 auto-register advisor 的计算结果一致 ——
 * {@link LoomMemoryAdvisor} order=+200 严格上游于本 advisor 的 +300,不算下游),
 * 迭代间多轮 tool_use history 全量保留,回归锁见 NoOp 测试
 * {@code next_instructions_*_delegates_super_full_history}。
 */
public class LoomAgenticLoopAdvisor extends ToolCallingAdvisor {

  private static final Logger log = LoggerFactory.getLogger(LoomAgenticLoopAdvisor.class);

  /**
   * 最近一轮的聚合响应快照 —— {@link #doAfterStream} 每轮写入,
   * {@link #doFinalizeLoopStream} 在迭代收口时读取(当前外层迭代的
   * ASSISTANT 文本 + advisor context 双重载体)。
   *
   * <p>advisor 实例每请求 new(DefaultChat.stream 逐请求 builder().build()),
   * SSE 单次订阅,per-round 顺序更新无并发问题;getAndSet(null) 消费即清,
   * 防御性避免跨 run 悬挂。
   */
  private final AtomicReference<ChatClientResponse> lastRoundAggregated = new AtomicReference<>();

  protected LoomAgenticLoopAdvisor(ToolCallingManager toolCallingManager,
                                   ToolExecutionEligibilityChecker toolExecutionEligibilityChecker,
                                   int advisorOrder,
                                   boolean conversationHistoryEnabled) {
    super(toolCallingManager, toolExecutionEligibilityChecker, advisorOrder, conversationHistoryEnabled);
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
   * 迭代收口文本:聚合响应所有 generation output 文本按序拼接(单生成时即最终回答)。
   * chatResponse 缺失 → null(跳过 ASSISTANT 写,reasoning 仍 flush,与空回答日的
   * legacy 行为差异仅限"无内容行",A1 常规轮不受影响)。
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
      if (assistantText != null && !assistantText.isEmpty()) {
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

  // ==================== A1 骨架 + Task 6 迭代收口写 ====================

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
    // Task 6 在消费点接管:ctx != null 时先 drain inbox 注入 steering USER 行再续下一迭代。
    // 骨架期仅读取验证接线通路(为 null 时即 A1/子任务路径),行为保持纯 super。
    if (log.isTraceEnabled() && runContextOf(chatClientRequest) == null) {
      log.trace("agentic loop: no StreamRunContext → pure super (A1 / subtask path)");
    }
    return super.doGetNextInstructionsForToolCall(chatClientRequest, chatClientResponse, toolExecutionResult);
  }

  @Override
  protected List<Message> doGetNextInstructionsForToolCallStream(ChatClientRequest chatClientRequest,
                                                                 ChatClientResponse chatClientResponse,
                                                                 ToolExecutionResult toolExecutionResult) {
    // Task 6 流式版消费点,同上门控
    if (log.isTraceEnabled() && runContextOf(chatClientRequest) == null) {
      log.trace("agentic loop (stream): no StreamRunContext → pure super (A1 / subtask path)");
    }
    return super.doGetNextInstructionsForToolCallStream(chatClientRequest, chatClientResponse, toolExecutionResult);
  }

  @Override
  protected ChatClientResponse doFinalizeLoop(ChatClientResponse chatClientResponse,
                                              CallAdvisorChain callAdvisorChain) {
    // Task 6 迭代收口写(主聊天/自定义 call 路径):ctx 在场 → ASSISTANT 逐迭代行 +
    // reasoning 落库 + perIterationMode 标记(LoomMemoryAdvisor.after 据此跳过聚合单写);
    // ctx 缺席(子任务/legacy)→ 钩子纯 super,after() 维持聚合单写(A1)。
    // Task 7 在此判定"收口前 inbox 是否仍有残留 → 起下一外层迭代(借 callAdvisorChain.copy(...).nextCall 重入,
    // 重入起点须覆盖 order=100 的 ChatUsageRecordingAdvisor 以保逐轮 usage 行)"。
    // 骨架期(Task 6):无重入 —— 写完直接委托 super。
    StreamRunContext ctx = runContextOf(chatClientResponse);
    if (ctx != null) {
      finalizeIteration(ctx, chatClientResponse);
    }
    return super.doFinalizeLoop(chatClientResponse, callAdvisorChain);
  }

  @Override
  protected Flux<ChatClientResponse> doFinalizeLoopStream(Flux<ChatClientResponse> chatClientResponseFlux,
                                                          StreamAdvisorChain streamAdvisorChain) {
    // 流式路径收口时入参 finalEmissions flux 常为空(末轮自带 usage 不补发),文本从
    // doAfterStream 缓存的末轮聚合响应读取;其 context 带同一 StreamRunContext 引用
    // (R14:终端 ChatModel*Advisor 以 Map.copyOf(request.context()) 透传到响应 context)。
    // Task 7 流式版收口点(重入逻辑),本期无重入 → 写完透传 super。
    ChatClientResponse lastRound = lastRoundAggregated.getAndSet(null);
    StreamRunContext ctx = runContextOf(lastRound);
    if (ctx != null) {
      finalizeIteration(ctx, lastRound);
    }
    return super.doFinalizeLoopStream(chatClientResponseFlux, streamAdvisorChain);
  }

  // ==================== builder(仿 ToolCallingAdvisor.builder(),默认值一致) ====================

  public static Builder builder() {
    return new Builder();
  }

  /**
   * 仿 {@link ToolCallingAdvisor.Builder}:默认 {@code ToolCallingManager.builder().build()}、
   * 默认 eligibility checker、{@code advisorOrder = DEFAULT_ORDER(+300)}、
   * {@code conversationHistoryEnabled = true} —— 逐项与 2.0.2 auto-register 对主链的计算结果对齐,
   * 保证"替换"零行为漂移。
   */
  public static class Builder extends ToolCallingAdvisor.Builder<Builder> {

    protected Builder() {
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
      return copy;
    }

    @Override
    public LoomAgenticLoopAdvisor build() {
      return new LoomAgenticLoopAdvisor(getToolCallingManager(), getToolExecutionEligibilityChecker(),
          getAdvisorOrder(), isConversationHistoryEnabled());
    }
  }
}
