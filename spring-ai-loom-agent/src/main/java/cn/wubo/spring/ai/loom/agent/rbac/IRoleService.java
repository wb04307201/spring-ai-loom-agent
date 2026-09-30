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

    /**
     * 历史残留名字:M5 起 admin 不再被跳过,行为完全等同 {@link #setUserRoles(String, List)}。
     * §3(2026-09-08):admin 现可在控制台被分配角色(strict RBAC,admin 的 MCP/工具同样按角色过滤)。
     *
     * @deprecated 名字误导(并不 skip admin);请直接调用 {@link #setUserRoles(String, List)}。
     *             下一 minor 版本删除。
     */
    @Deprecated
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

    /**
     * 同 {@link #getVisibleToolsForUser},但额外返回每项的 {@code default_enabled} 值。
     * <p>
     * 用途：{@code CapabilityService.list()} 序列化 {@link cn.wubo.spring.ai.loom.agent.model.CapabilityInfo}
     * 时携带 {@code defaultEnabled} 字段,聊天面板前端用它做 localStorage 持久化缺失项的 fallback 勾选。
     * 与 {@link #getRoleToolsWithDefault(String)} 同 SQL 模式,只是按用户合并所有角色。
     * <p>
     * 角色未授权的工具(不在 role_tool)不返回;admin 走严格 RBAC,语义同 {@link #getVisibleToolsForUser}。
     * 空角色或未授权工具场景下返回空 list,不能返 null(见 {@code CapabilityServiceTest.defaultEnabledEmptyRoles})。
     */
    List<RoleToolItem> getVisibleToolsForUserWithDefault(String username);

    record RoleMcpItem(String name, Boolean defaultEnabled) {
    }

    record RoleToolItem(String groupName, Boolean defaultEnabled) {
    }
}
