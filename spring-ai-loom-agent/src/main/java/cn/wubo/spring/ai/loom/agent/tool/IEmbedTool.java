package cn.wubo.spring.ai.loom.agent.tool;

/**
 * 聚合标记接口，所有嵌入工具（ITimeTool、ISkillTool、IFileTool）均继承此接口。
 * <p>
 * DefaultChat 通过注入 {@code List<IEmbedTool>} 收集所有工具实例，
 * 统一注册到 Spring AI 的 {@code .tools()} 调用中。
 * <p>
 * 用户可以单独替换某个子工具（如 IFileTool），而不影响其他子工具的默认实现。
 */
public interface IEmbedTool {
}
