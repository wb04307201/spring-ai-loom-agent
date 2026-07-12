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

    /** 角色授权 mcp 列表（按 sort_order 升序） */
    List<String> getRoleMcps(String roleCode);

    /** 角色授权 mcp 列表（带 defaultEnabled） */
    List<RoleMcpItem> getRoleMcpsWithDefault(String roleCode);

    /**
     * 覆盖角色授权的 mcp 列表（含顺序 + defaultEnabled）。
     * items 顺序即 sort_order。
     */
    void setRoleMcps(String roleCode, List<RoleMcpItem> items);

    /**
     * 当前用户可见的 mcp（admin 全；普通按角色合并）。
     * 顺序按 role_mcp.sort_order 升序；每条带 defaultSelected 标识聊天界面默认勾选。
     */
    List<McpSystemView> getVisibleMcpsForUser(String username);

    record RoleMcpItem(String name, Boolean defaultEnabled) {}
}
