package cn.wubo.spring.ai.loom.agent.rbac;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.McpRecord;
import cn.wubo.spring.ai.loom.agent.model.McpServerInfo;
import cn.wubo.spring.ai.loom.agent.model.McpSystemView;
import cn.wubo.spring.ai.loom.agent.model.McpToolInfo;
import cn.wubo.spring.ai.loom.agent.model.McpToolSystemView;
import cn.wubo.spring.ai.loom.agent.model.ToolRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class DefaultMcpServerAdmin implements IMcpServerAdmin {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<cn.wubo.spring.ai.loom.agent.mcp.IMcp> mcpProvider;

    public DefaultMcpServerAdmin(JdbcTemplate jdbcTemplate,
                                 ObjectProvider<cn.wubo.spring.ai.loom.agent.mcp.IMcp> mcpProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.mcpProvider = mcpProvider;
    }

    private cn.wubo.spring.ai.loom.agent.mcp.IMcp mcp() {
        // 懒查，打破 mcpServerAdmin <-> roleService <-> mcp 的循环依赖
        return mcpProvider.getObject();
    }

    @Override
    public List<McpServerInfo> listAll() {
        return jdbcTemplate.query(
                "SELECT name, title, description " +
                        "FROM mcp_server ORDER BY name",
                (rs, n) -> new McpServerInfo(
                        rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    /**
     * 合并视图：SDK 实时 mcp（name + tools） + DB 元数据（title/description + tool desc）。
     * V7 起没有 isActive 字段（mcp 是否可用由角色授权决定）。
     */
    @Override
    public List<McpSystemView> listSystem() {
        // 1. 拉 SDK 实时列表
        List<McpRecord> live = mcp().mcps();
        if (live == null || live.isEmpty()) return List.of();
        // 2. 批量查 DB 元数据
        List<String> names = live.stream().map(McpRecord::name).toList();
        String placeholders = String.join(",", names.stream().map(s -> "?").toList());
        Map<String, Map<String, Object>> dbServerRows = new HashMap<>();
        try {
            org.springframework.jdbc.core.RowMapper<Map<String, Object>> serverMapper = (rs, rowNum) -> {
                dbServerRows.put(rs.getString(1), Map.of(
                        "title", rs.getString(2),
                        "description", rs.getString(3)));
                return null;
            };
            jdbcTemplate.query(
                    "SELECT name, title, description FROM mcp_server WHERE name IN (" + placeholders + ")",
                    serverMapper,
                    (Object[]) names.toArray());
        } catch (Exception ignore) {}
        // 3. 批量查工具描述
        Map<String, Map<String, String>> dbToolByMcp = new HashMap<>();
        try {
            org.springframework.jdbc.core.RowMapper<Map<String, String>> toolMapper = (rs, rowNum) -> {
                dbToolByMcp.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
                        .put(rs.getString(2), rs.getString(3));
                return null;
            };
            jdbcTemplate.query(
                    "SELECT mcp_name, name, description FROM mcp_tool WHERE mcp_name IN (" + placeholders + ")",
                    toolMapper,
                    (Object[]) names.toArray());
        } catch (Exception ignore) {}

        // 4. 合并
        List<McpSystemView> out = new ArrayList<>();
        for (McpRecord r : live) {
            Map<String, Object> dbRow = dbServerRows.get(r.name());
            boolean maintained = dbRow != null;
            String title = maintained ? (String) dbRow.get("title") : null;
            String description = maintained ? (String) dbRow.get("description") : null;
            Map<String, String> toolDbMap = dbToolByMcp.getOrDefault(r.name(), Map.of());
            List<McpToolSystemView> tools = new ArrayList<>();
            if (r.tools() != null) {
                for (ToolRecord t : r.tools()) {
                    String dbDesc = toolDbMap.get(t.name());
                    tools.add(new McpToolSystemView(t.name(), dbDesc, dbDesc != null));
                }
            }
            out.add(new McpSystemView(r.name(), title, description, maintained, true, tools));
        }
        return out;
    }

    @Override
    public McpServerInfo update(String name, String title, String description) {
        Integer n = jdbcTemplate.update(
                "UPDATE mcp_server SET title = COALESCE(?, title), " +
                        "description = COALESCE(?, description), " +
                        "updated_at = CURRENT_TIMESTAMP " +
                        "WHERE name = ?",
                title, description, name);
        if (n == 0) {
            // 不存在就插入
            jdbcTemplate.update(
                    "INSERT INTO mcp_server (name, title, description) VALUES (?, ?, ?)",
                    name, title, description);
        }
        return jdbcTemplate.queryForObject(
                "SELECT name, title, description FROM mcp_server WHERE name = ?",
                (rs, i) -> new McpServerInfo(
                        rs.getString(1), rs.getString(2), rs.getString(3)),
                name);
    }

    @Override
    public List<McpToolInfo> listTools(String mcpName) {
        return jdbcTemplate.query(
                "SELECT id, mcp_name, name, description " +
                        "FROM mcp_tool WHERE mcp_name = ? ORDER BY name",
                (rs, n) -> new McpToolInfo(
                        rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4)),
                mcpName);
    }

    @Override
    public McpToolInfo updateTool(Long toolId, String description) {
        Object param = (description == null || description.isBlank()) ? null : description;
        Integer n = jdbcTemplate.update(
                "UPDATE mcp_tool SET description = ? WHERE id = ?",
                param, toolId);
        if (n == 0) throw new LoomAgentRuntimeException("工具不存在: id=" + toolId);
        return jdbcTemplate.queryForObject(
                "SELECT id, mcp_name, name, description FROM mcp_tool WHERE id = ?",
                (rs, i) -> new McpToolInfo(
                        rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4)),
                toolId);
    }
}
