package cn.wubo.spring.ai.loom.agent.tool;

/**
 * V5.4 P9：薄封装 Spring AI 1.1.7 的 {@code org.springframework.ai.support.ToolCallbacks.from}，
 * 方便在业务代码中调用而不需要直接引用上游包（上游类可能被 spring-ai-commons
 * 多次搬家 —— 不同版本路径不同）。
 *
 * <p>功能：把 IEmbedTool 数组（{@code @Tool} 注解的方法）转为 {@code ToolCallback[]}
 * 数组，便于包 {@code LoggingToolCallback} 后用
 * {@code requestSpec.toolCallbacks(wrapped)} 统一注册 —— 替代
 * {@code requestSpec.tools(embedTools.toArray())}。
 *
 * <p>原因：{@code tools()} 注册的 MethodToolCallback 走 Spring AI 默认 ObservationHandler
 * 路径，Spring AI 1.1.7 DashScope 流式 chunk 让 onStart+onStop 各写一次 → 1 次实际工具
 * 调用产生 2 行 DB。统一用 {@code LoggingToolCallback} 后 DB 行数 = 真实调用次数。
 */
public final class ToolCallbacks {

    private ToolCallbacks() {}

    public static org.springframework.ai.tool.ToolCallback[] from(Object... toolObjects) {
        return org.springframework.ai.support.ToolCallbacks.from(toolObjects);
    }
}
