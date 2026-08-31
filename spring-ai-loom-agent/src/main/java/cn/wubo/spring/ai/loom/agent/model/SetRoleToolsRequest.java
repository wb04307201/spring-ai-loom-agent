package cn.wubo.spring.ai.loom.agent.model;

import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;

import java.util.List;

/**
 * 角色授权本地 tool group 时传 [{groupName, defaultEnabled}, ...]
 * （groupName 是 "tool_<@ToolGroup value>" 形式，如 "tool_file"）。
 * defaultEnabled 表示聊天界面默认勾选。
 */
public record SetRoleToolsRequest(List<IRoleService.RoleToolItem> items) {
}