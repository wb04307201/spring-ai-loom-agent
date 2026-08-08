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
        // V5.4：Spring AI 1.1.7 的 onStop/onError 有时不触发（实测 onStart 多次
        // FIRED 但 onStop 0 次）所以 onStart + onStop 都写库，靠 conversation_id
        // + tool_call_id + created_at 去重；onStop 的 result 会刷新同一行。
        // （fallback：如果两次写入，DB 有重复行——下次 migration 加 unique index 解决）
        saveLog(context, false);
    }

    @Override
    public void onError(ToolCallingObservationContext context) {
        log.info("V5.3 onError: tool={} err={}", context.getToolDefinition() != null ? context.getToolDefinition().name() : "?", context.getError());
    }

    @Override
    public void onStop(ToolCallingObservationContext context) {
        log.debug("V5.3 onStop FIRED: tool={}", context.getToolDefinition() != null ? context.getToolDefinition().name() : "?");
        saveLog(context, true);
    }

    private void saveLog(ToolCallingObservationContext context, boolean withResult) {
        try {
            String toolName = context.getToolDefinition() != null ? context.getToolDefinition().name() : "unknown";
            String args = context.getToolCallArguments() == null ? "" : context.getToolCallArguments();
            String result = "";
            if (withResult && context.getToolCallResult() != null) {
                result = context.getToolCallResult();
            }

            ToolCallContextHolder.ToolCallContext holder = ToolCallContextHolder.get();
            String conversationId = holder != null ? holder.conversationId() : "";
            String username = holder != null ? holder.username() : "";

            String callId = "obs-" + UUID.randomUUID();

            boolean isError = withResult && result != null && (result.toLowerCase().contains("error")
                    || result.toLowerCase().contains("exception"));

            repository.save(new ToolCallLog(
                    null, conversationId, username, callId, toolName, args, result, isError, null, Instant.now()));

            log.debug("V5.4 tool_call log saved: conv={} user={} tool={} callId={} argsLen={} resultLen={} withResult={}",
                    conversationId, username, toolName, callId, args.length(), result.length(), withResult);
        } catch (Exception e) {
            log.warn("V5.4 tool_call log save failed: {}", e.getMessage());
        }
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ToolCallingObservationContext;
    }
}
