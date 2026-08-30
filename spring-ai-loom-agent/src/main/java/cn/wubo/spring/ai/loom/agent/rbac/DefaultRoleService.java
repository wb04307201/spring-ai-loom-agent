package cn.wubo.spring.ai.loom.agent.rbac;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.McpSystemView;
import cn.wubo.spring.ai.loom.agent.model.RoleInfo;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

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
        if (findUserType(username) == null) {
            throw new LoomAgentRuntimeException("用户不存在: " + username);
        }
        jdbcTemplate.update("DELETE FROM user_role WHERE username = ?", username);
        if (roleCodes != null && !roleCodes.isEmpty()) {
            for (String r : roleCodes) {
                jdbcTemplate.update(
                        "INSERT INTO user_role (username, role_code) VALUES (?, ?)", username, r);
            }
        }
    }

    /**
     * M5 起去掉 admin bypass：admin 也必须通过角色授权访问 MCP / tool。
     * <p>
     * 历史原因：admin 类型历史上自动 bypass RBAC,但 M5 决定所有用户严格 RBAC,
     * 这里不再跳过 admin。如果 admin 之前没分配任何角色,登录后看不到任何
     * MCP / tool —— 需要 admin 控制台手动授权。
     */
    @Override
    public void setUserRolesOrSkipAdmin(String username, List<String> roleCodes) {
        String type = findUserType(username);
        if (type == null) {
            throw new LoomAgentRuntimeException("用户不存在: " + username);
        }
        setUserRoles(username, roleCodes);
    }

    /**
     * 查用户 type，用户不存在时返回 null（而不是抛 EmptyResultDataAccessException）。
     * 供 chat 热路径 {@link #getVisibleMcpsForUser} 及角色写入使用，避免 500。
     */
    private String findUserType(String username) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT type FROM user_info WHERE username = ?", String.class, username);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
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
                boolean def = it.defaultEnabled() == null || it.defaultEnabled();
                jdbcTemplate.update(
                        "INSERT INTO role_mcp (role_code, mcp_name, sort_order, default_enabled) VALUES (?, ?, ?, ?)",
                        roleCode, it.name(), sortOrder++, def);
            }
        }
    }

    /**
     * 所有用户（含 admin）严格走 RBAC：按用户所有角色的 sort_order / default_enabled
     * 合并后过滤 system 视图。<b>M3 起去掉 admin bypass</b>，admin 账号必须被分配角色
     * 才能看到 MCP（之前是 {@code if ("ADMIN".equals(type))} 全集,被去掉以保证 admin / 普通
     * 用户走同一套过滤逻辑,避免 admin 超能）。
     */
    @Override
    public List<McpSystemView> getVisibleMcpsForUser(String username) {
        List<McpSystemView> system = mcpServerAdmin.listSystem();
        if (system == null) system = List.of();
        Set<String> allowed = new HashSet<>();
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
                        if (rs.getBoolean(3)) {
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

    // ==================== 本地工具 RBAC（M3 新增）====================

    @Override
    public List<String> getRoleTools(String roleCode) {
        return jdbcTemplate.queryForList(
                "SELECT group_name FROM role_tool WHERE role_code = ? ORDER BY sort_order, group_name",
                String.class, roleCode);
    }

    @Override
    public List<IRoleService.RoleToolItem> getRoleToolsWithDefault(String roleCode) {
        return jdbcTemplate.query(
                "SELECT group_name, default_enabled FROM role_tool WHERE role_code = ? ORDER BY sort_order, group_name",
                (rs, n) -> new IRoleService.RoleToolItem(rs.getString(1), rs.getBoolean(2)),
                roleCode);
    }

    @Override
    public void setRoleTools(String roleCode, List<IRoleService.RoleToolItem> items) {
        jdbcTemplate.update("DELETE FROM role_tool WHERE role_code = ?", roleCode);
        if (items != null && !items.isEmpty()) {
            int sortOrder = 0;
            for (IRoleService.RoleToolItem it : items) {
                if (it == null || it.groupName() == null || it.groupName().isBlank()) continue;
                boolean def = it.defaultEnabled() == null || it.defaultEnabled();
                jdbcTemplate.update(
                        "INSERT INTO role_tool (role_code, group_name, sort_order, default_enabled) VALUES (?, ?, ?, ?)",
                        roleCode, it.groupName(), sortOrder++, def);
            }
        }
    }

    @Override
    public List<String> getVisibleToolsForUser(String username) {
        // 与 getVisibleMcpsForUser 同口径：合并所有角色，按 sort_order 升序
        Set<String> allowed = new LinkedHashSet<>();
        for (String role : getUserRoles(username)) {
            allowed.addAll(jdbcTemplate.queryForList(
                    "SELECT group_name FROM role_tool WHERE role_code = ? ORDER BY sort_order, group_name",
                    String.class, role));
        }
        return new ArrayList<>(allowed);
    }
}
