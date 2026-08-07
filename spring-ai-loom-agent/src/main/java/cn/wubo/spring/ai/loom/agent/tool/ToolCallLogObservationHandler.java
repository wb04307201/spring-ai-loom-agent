package cn.wubo.spring.ai.loom.agent.tool;

import cn.wubo.spring.ai.loom.agent.model.ToolCallLog;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * V5.3：通过 Spring AI 1.1+ 的 {@link ObservationHandler} 拦截所有
 * {@code ToolCallingObservationContext} 事件，写到 {@code loom_tool_call_log}。
 *
 * <p>Spring AI 在 {@code DefaultToolCallingManager.executeToolCall()} 内部
 * （即使是 private）用 Micrometer Observation 包装了 tool call。本 handler
 * 监听 stop 事件即可拿到：tool 名（{@code ToolDefinition.name()}）、调用参数
 * （{@code getToolCallArguments()}）、执行结果（{@code getToolCallResult()}）。
 *
 * <p>覆盖范围（零侵入）：
 * <ul>
 *   <li>内置 {@code IEmbedTool}（@Tool 注解方法）</li>
 *   <li>stdio 引入的 MCP 工具（{@code SyncMcpToolCallbackProvider}）</li>
 *   <li>未来任何注册到 Spring AI 的 {@code ToolCallback}</li>
 * </ul>
 */
@Slf4j
@Component
public class ToolCallLogObservationHandler implements ObservationHandler<ToolCallingObservationContext> {

    private final IToolCallLogRepository repository;

    public ToolCallLogObservationHandler(IToolCallLogRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onStart(ToolCallingObservationContext context) {
        // onStop 一次性写库（拿 args + result 完整配对）
    }

    @Override
    public void onStop(ToolCallingObservationContext context) {
        try {
            String toolName = context.getToolDefinition() != null ? context.getToolDefinition().name() : "unknown";
            String args = context.getToolCallArguments() == null ? "" : context.getToolCallArguments();
            String result = context.getToolCallResult() == null ? "" : context.getToolCallResult();

            // 从 ThreadLocal 拿 conversationId / username（SseController 在异步任务入口 set）
            ToolCallContextHolder.ToolCallContext holder = ToolCallContextHolder.get();
            String conversationId = holder != null ? holder.conversationId() : "";
            String username = holder != null ? holder.username() : "";

            // observation 没暴露 toolCallId，生成 UUID 关联
            String callId = "obs-" + UUID.randomUUID();

            // 检测是否错误（result 含 exception 信息时）
            boolean isError = result != null && (result.toLowerCase().contains("error")
                    || result.toLowerCase().contains("exception"));

            repository.save(new ToolCallLog(
                    null, conversationId, username, callId, toolName, args, result, isError, null, Instant.now()));

            log.debug("V5.3 tool_call log saved: conv={} user={} tool={} callId={} argsLen={} resultLen={}",
                    conversationId, username, toolName, callId, args.length(), result.length());
        } catch (Exception e) {
            log.warn("V5.3 tool_call log save failed: {}", e.getMessage());
        }
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ToolCallingObservationContext;
    }
}
