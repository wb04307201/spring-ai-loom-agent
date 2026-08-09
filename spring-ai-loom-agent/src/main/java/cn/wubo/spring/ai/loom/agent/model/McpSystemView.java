package cn.wubo.spring.ai.loom.agent.model;

import java.util.List;

/**
 * 合并视图：SDK 实时 mcp + DB 元数据。
 * 用于 /admin/mcp-system 端点（mcps.html 和 roles.html 都用这个）。
 * isActive 已删除（V7）；defaultSelected 表示普通用户进聊天界面时是否默认勾选。
 */
public record McpSystemView(
 String name,
 String title,
 String description,
 boolean maintained,
 boolean defaultSelected, // 普通用户默认勾选；admin 始终 true
 List<McpToolSystemView> tools
) {
}
