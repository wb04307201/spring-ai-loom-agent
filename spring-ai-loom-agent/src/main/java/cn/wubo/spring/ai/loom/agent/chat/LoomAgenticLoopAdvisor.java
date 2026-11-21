package cn.wubo.spring.ai.loom.agent.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.util.List;

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
 * <p><b>A1 退化契约(Task 5 骨架实现)</b>:四个循环钩子先从 advisor context 读取
 * {@link StreamRunContext}({@link StreamRunContext#CONTEXT_KEY}):
 * <ul>
 *   <li>context 无该 key(子任务 / 自定义 IChat / 非 SSE 路径)→ 钩子<b>纯 super</b>;</li>
 *   <li>context 有该 key 但 inbox 为空(主对话 Task 6 接线前)→ 骨架期同样委托 super。</li>
 * </ul>
 * 两条路径的循环行为都与默认 ToolCallingAdvisor 完全一致:无续跑、无额外模型调用。
 * Task 6/7 在本类接线 steering 消费(drain inbox → 注入 USER 行 → 续跑下一外层迭代)
 * 与 iteration/ack 帧发射;context 读取已就位,届时只增行为不改签名。
 *
 * <p>Issue1 不变式延续:builder 默认 {@code conversationHistoryEnabled=true}(与
 * {@code DefaultChatClient} 在本链形态下对 auto-register advisor 的计算结果一致 ——
 * {@link LoomMemoryAdvisor} order=+200 严格上游于本 advisor 的 +300,不算下游),
 * 迭代间多轮 tool_use history 全量保留,回归锁见 NoOp 测试
 * {@code next_instructions_*_delegates_super_full_history}。
 */
public class LoomAgenticLoopAdvisor extends ToolCallingAdvisor {

  private static final Logger log = LoggerFactory.getLogger(LoomAgenticLoopAdvisor.class);

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
    Object value = chatClientRequest.context().get(StreamRunContext.CONTEXT_KEY);
    return value instanceof StreamRunContext ctx ? ctx : null;
  }

  // ==================== A1 骨架:四个循环钩子全委托 super ====================

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
    // Task 7 在此判定"收口前 inbox 是否仍有残留 → 起下一外层迭代(借 callAdvisorChain.copy(...).nextCall 重入)"。
    // 骨架:纯 super —— 恒等透传、对 chain 零交互(= 无续跑重入)。
    return super.doFinalizeLoop(chatClientResponse, callAdvisorChain);
  }

  @Override
  protected Flux<ChatClientResponse> doFinalizeLoopStream(Flux<ChatClientResponse> chatClientResponseFlux,
                                                          StreamAdvisorChain streamAdvisorChain) {
    // Task 7 流式版收口点,同上门控
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
