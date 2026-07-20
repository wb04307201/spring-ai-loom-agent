package cn.wubo.spring.ai.loom.agent.rbac;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.McpSystemView;
import cn.wubo.spring.ai.loom.agent.model.RoleInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class DefaultRoleService implements IRoleService {

    private final JdbcTemplate jdbcTemplate;
    private final IMcpServerAdmin mcpServerAdmin;

    public DefaultRoleService(JdbcTemplate jdbcTemplate, IMcpServerAdmin mcpServerAdmin) {
        this.jdbcTemplate = jdbcTemplate;
        this.mcpServerAdmin = mcpServerAdmin;
    }

    @Override
    public List<RoleInfo> list() {
        return jdbcTemplate.query(
                "SELECT code, name, is_system, description FROM role ORDER BY is_system DESC, code",
                (rs, n) -> new RoleInfo(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4)));
    }

    @Override
    public RoleInfo create(String code, String name, String description, List<String> mcpNames) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new LoomAgentRuntimeException(400, "角色 code 和 name 必填");
        }
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM role WHERE code = ?", Integer.class, code);
        if (exists != null && exists > 0) {
            throw new LoomAgentRuntimeException(409, "角色 code 已存在: " + code);
        }
        jdbcTemplate.update(
                "INSERT INTO role (code, name, is_system, description) VALUES (?, ?, FALSE, ?)",
                code, name, description);
        if (mcpNames != null && !mcpNames.isEmpty()) {
            List<IRoleService.RoleMcpItem> items = mcpNames.stream()
                    .map(n -> new IRoleService.RoleMcpItem(n, null))
                    .toList();
            setRoleMcps(code, items);
        }
        return new RoleInfo(code, name, false, description);
    }

    @Override
    public void delete(String code) {
        jdbcTemplate.update("DELETE FROM role WHERE code = ? AND is_system = FALSE", code);
    }

    @Override
    public void deleteOrThrow(String code) {
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT is_system FROM role WHERE code = ?", Integer.class, code);
        if (rows.isEmpty()) throw new LoomAgentRuntimeException(404, "角色不存在: " + code);
        Integer isSystem = rows.get(0);
        if (isSystem != null && isSystem != 0) throw new LoomAgentRuntimeException(400, "系统角色不可删除: " + code);
        delete(code);
    }

    @Override
    public List<String> getUserRoles(String username) {
        return jdbcTemplate.queryForList(
                "SELECT role_code FROM user_role WHERE username = ? ORDER BY role_code", String.class, username);
    }

    @Override
    public void setUserRoles(String username, List<String> roleCodes) {
        jdbcTemplate.update("DELETE FROM user_role WHERE username = ?", username);
        if (roleCodes != null && !roleCodes.isEmpty()) {
            for (String r : roleCodes) {
                jdbcTemplate.update(
                        "INSERT INTO user_role (username, role_code) VALUES (?, ?)", username, r);
            }
        }
    }

    @Override
    public void setUserRolesOrSkipAdmin(String username, List<String> roleCodes) {
        String type = jdbcTemplate.queryForObject(
                "SELECT type FROM user_info WHERE username = ?", String.class, username);
        if ("ADMIN".equals(type)) {
            return;
        }
        setUserRoles(username, roleCodes);
    }

    @Override
    public List<String> getRoleMcps(String roleCode) {
        return jdbcTemplate.queryForList(
                "SELECT mcp_name FROM role_mcp WHERE role_code = ? ORDER BY sort_order, mcp_name",
                String.class, roleCode);
    }

    @Override
    public List<IRoleService.RoleMcpItem> getRoleMcpsWithDefault(String roleCode) {
        return jdbcTemplate.query(
                "SELECT mcp_name, default_enabled FROM role_mcp WHERE role_code = ? ORDER BY sort_order, mcp_name",
                (rs, n) -> new IRoleService.RoleMcpItem(rs.getString(1), rs.getBoolean(2)),
                roleCode);
    }

    @Override
    public void setRoleMcps(String roleCode, List<IRoleService.RoleMcpItem> items) {
        jdbcTemplate.update("DELETE FROM role_mcp WHERE role_code = ?", roleCode);
        if (items != null && !items.isEmpty()) {
            int sortOrder = 0;
            for (IRoleService.RoleMcpItem it : items) {
                if (it == null || it.name() == null || it.name().isBlank()) continue;
                boolean def = it.defaultEnabled() == null ? true : it.defaultEnabled();
                jdbcTemplate.update(
                        "INSERT INTO role_mcp (role_code, mcp_name, sort_order, default_enabled) VALUES (?, ?, ?, ?)",
                        roleCode, it.name(), sortOrder++, def);
            }
        }
    }

    /**
     * admin 全部 mcp（defaultSelected=true）；
     * 普通用户：按角色 sort_order 升序，按角色 default_enabled OR（任一角色 default=true）算 defaultSelected。
     */
    @Override
    public List<McpSystemView> getVisibleMcpsForUser(String username) {
        String type = jdbcTemplate.queryForObject(
                "SELECT type FROM user_info WHERE username = ?", String.class, username);
        List<McpSystemView> system = mcpServerAdmin.listSystem();
        if (system == null) system = List.of();
        if ("ADMIN".equals(type)) {
            return system.stream()
                    .sorted((a, b) -> a.name().compareTo(b.name()))
                    .toList();
        }
        // 普通用户：合并所有角色允许的 mcp
        Set<String> allowed = new HashSet<>();
        // per-mcp info：最小 sort_order + 是否任一角色 default_enabled
        Map<String, Integer> orderByName = new HashMap<>();
        Map<String, Boolean> defaultByName = new HashMap<>();
        for (String role : getUserRoles(username)) {
            jdbcTemplate.query(
                    "SELECT mcp_name, sort_order, default_enabled FROM role_mcp WHERE role_code = ? ORDER BY sort_order, mcp_name",
                    (rs, rowNum) -> {
                        String n = rs.getString(1);
                        allowed.add(n);
                        int so = rs.getInt(2);
                        orderByName.merge(n, so, Math::min);
                        if (Boolean.TRUE.equals(rs.getBoolean(3))) {
                            defaultByName.put(n, true);
                        }
                        return null;
                    },
                    role);
        }
        return system.stream()
                .filter(m -> allowed.contains(m.name()))
                .map(m -> new McpSystemView(
                        m.name(), m.title(), m.description(), m.maintained(),
                        defaultByName.getOrDefault(m.name(), false),
                        m.tools()))
                .sorted((a, b) -> {
                    int oa = orderByName.getOrDefault(a.name(), Integer.MAX_VALUE);
                    int ob = orderByName.getOrDefault(b.name(), Integer.MAX_VALUE);
                    if (oa != ob) return Integer.compare(oa, ob);
                    return a.name().compareTo(b.name());
                })
                .toList();
    }
}
