package cn.wubo.spring.ai.loom.agent.model;

/**
 * 工具的合并视图：name 是 SDK 工具名（系统内唯一），description 优先取 DB 中文描述。
 */
public record McpToolSystemView(
        String name,
        String description, // DB description，未维护时为 null
        boolean maintained // DB 有记录
) {
}
