package cn.wubo.spring.ai.loom.agent.tool;

/**
 * V5.3：工具调用上下文传递 — SseController 在异步任务入口设置，
 * ToolCallLogObservationHandler 在 onStop 时读取。
 *
 * <p>用 ThreadLocal。Reactor 的 {@code Hooks.enableAutomaticContextPropagation()}
 * 在 {@link ToolCallLogObservationConfig} 启动时开启，让 ThreadLocal
 * 跨 Reactor 异步边界（{@code onErrorResume}/{@code subscribeOn}）传播。
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
