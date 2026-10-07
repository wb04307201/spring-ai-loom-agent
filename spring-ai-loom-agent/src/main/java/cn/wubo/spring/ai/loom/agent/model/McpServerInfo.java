package cn.wubo.spring.ai.loom.agent.model;

/**
 * mcp_server 表查询结果。
 * V7 起 is_active 字段已删除（mcp 是否可用完全由"角色授权"决定）。
 */
public record McpServerInfo(
        String name,
        String title,
        String description
) {
}
