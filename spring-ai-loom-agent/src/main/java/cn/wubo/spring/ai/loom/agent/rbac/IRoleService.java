package cn.wubo.spring.ai.loom.agent.rbac;

import cn.wubo.spring.ai.loom.agent.model.McpSystemView;
import cn.wubo.spring.ai.loom.agent.model.RoleInfo;

import java.util.List;

public interface IRoleService {

    List<RoleInfo> list();

    RoleInfo create(String code, String name, String description, List<String> mcpNames);

    void delete(String code);

    void deleteOrThrow(String code);

    List<String> getUserRoles(String username);

    void setUserRoles(String username, List<String> roleCodes);

    void setUserRolesOrSkipAdmin(String username, List<String> roleCodes);

    /**
     * 角色授权 mcp 列表（按 sort_order 升序）
     */
    List<String> getRoleMcps(String roleCode);

    /**
     * 角色授权 mcp 列表（带 defaultEnabled）
     */
    List<RoleMcpItem> getRoleMcpsWithDefault(String roleCode);

    /**
     * 覆盖角色授权的 mcp 列表（含顺序 + defaultEnabled）。
     * items 顺序即 sort_order。
     */
    void setRoleMcps(String roleCode, List<RoleMcpItem> items);

    /**
     * 当前用户可见的 mcp。<b>M3 起 admin 不再 bypass</b>，所有用户走 RBAC：
     * 普通用户按角色合并；admin 也必须被授权至少一个角色。
     * 顺序按 role_mcp.sort_order 升序；每条带 defaultSelected 标识聊天界面默认勾选。
     */
    List<McpSystemView> getVisibleMcpsForUser(String username);

    // ==================== 本地工具 RBAC（M3 新增）====================

    /**
     * 角色授权的本地工具组列表（按 sort_order 升序），group_name 是
     * {@code "tool_" + @ToolGroup value} 形式（如 {@code "tool_file"}）。
     */
    List<String> getRoleTools(String roleCode);

    /**
     * 角色授权的本地工具组列表（带 defaultEnabled）。
     */
    List<RoleToolItem> getRoleToolsWithDefault(String roleCode);

    /**
     * 覆盖角色授权的本地工具组列表（含顺序 + defaultEnabled）。
     * items 顺序即 sort_order。
     */
    void setRoleTools(String roleCode, List<RoleToolItem> items);

    /**
     * 当前用户可见的本地工具组（与 {@link #getVisibleMcpsForUser} 同口径：所有用户
     * 严格 RBAC,admin 不再 bypass）。用于 {@code CapabilityService.toolGroupsFor}。
     */
    List<String> getVisibleToolsForUser(String username);

    record RoleMcpItem(String name, Boolean defaultEnabled) {
    }

    record RoleToolItem(String groupName, Boolean defaultEnabled) {
    }
}
