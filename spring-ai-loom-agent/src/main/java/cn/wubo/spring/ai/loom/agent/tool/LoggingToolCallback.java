package cn.wubo.spring.ai.loom.agent.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * ：Plan A 思路 — 直接包 ToolCallback 写 loom_tool_call_log。
 *
 * <p>Plan D（ObservationHandler）在 Spring AI 1.1.7 上 onStart 触发但 onStop 不
 * 触发（已验证：DB 0 行 vs handler 多次 FIRED onStart），所以退化到包 callback。
 *
 * <p>闭包捕获 conversationId / username，execute() 前后计时调 repository.save。
 * 覆盖范围：MCP（{@code SyncMcpToolCallbackProvider}）和 IEmbedTool
 * （{@code MethodToolCallback} 由 Spring AI 从 @Tool 方法生成）两种类型。
 */
@Slf4j
@RequiredArgsConstructor
public class LoggingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final String conversationId;
    private final String username;
    private final IToolCallLogRepository repository;

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String arguments) {
        // abstract 方法（没 ToolContext 上下文），委托给 default 实现走 call(String, null)
        return call(arguments, null);
    }

    @Override
    public String call(String arguments, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        Instant start = Instant.now();
        boolean isError = false;
        String result = "";
        // 2026-11-09: qwen tool_use arguments 常带 JS 注释,而 Spring AI 2.x 共享
        // JsonMapper 不可变(反射开 ALLOW_COMMENTS 的路子在 2.x 从未生效) ⇒ 在本
        // 包装点(embed + MCP 唯一入口)剥净注释再委托,MethodToolCallback 永远
        // 看不到注释。审计日志仍记**原始** arguments(审计保真)。
        String sanitized = JsonLenient.sanitize(arguments);
        try {
            result = delegate.call(sanitized, toolContext);
            return result;
        } catch (Exception e) {
            isError = true;
            result = "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn(" tool_call error: tool={} err={}", toolName, e.getMessage());
            throw e;
        } finally {
            try {
                long durationMs = Duration.between(start, Instant.now()).toMillis();
                String callId = "wrap-" + UUID.randomUUID();
                repository.save(new cn.wubo.spring.ai.loom.agent.model.ToolCallLog(
                        null, conversationId, username, callId, toolName,
                        arguments == null ? "" : arguments,
                        result, isError, durationMs, Instant.now()));
                log.debug(" tool_call log saved: conv={} tool={} callId={} dur={}ms err={}",
                        conversationId, toolName, callId, durationMs, isError);
            } catch (Exception ex) {
                log.warn(" tool_call log save failed: {}", ex.getMessage());
            }
        }
    }
}
