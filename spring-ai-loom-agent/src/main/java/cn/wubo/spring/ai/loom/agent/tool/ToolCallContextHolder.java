package cn.wubo.spring.ai.loom.agent.tool;

/**
 * V5.3：工具调用上下文传递 — SseController 在异步任务入口设置，
 * ToolCallLogObservationHandler 在 onStop 时读取。避免给 ToolCallingObservationContext
 * 增字段（它没暴露 metadata.context），用 ThreadLocal + Reactor 上下文传播解耦。
 */
public final class ToolCallContextHolder {

    public record ToolCallContext(String conversationId, String username) {}

    private static final ThreadLocal<ToolCallContext> CURRENT = new ThreadLocal<>();

    private ToolCallContextHolder() {}

    public static void set(String conversationId, String username) {
        CURRENT.set(new ToolCallContext(conversationId, username));
    }

    public static ToolCallContext get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
