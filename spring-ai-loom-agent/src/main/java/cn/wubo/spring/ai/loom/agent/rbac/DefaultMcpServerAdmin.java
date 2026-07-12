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
                    // 描述优先级：DB 维护 > SDK 默认；没维护就用 SDK 原文
                    String dbDesc = toolDbMap.get(t.name());
                    String desc = dbDesc != null ? dbDesc : t.description();
                    tools.add(new McpToolSystemView(t.name(), desc, dbDesc != null));
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
        // 先查 DB 已有记录（id + 描述）
        Map<String, McpToolInfo> dbByName = new HashMap<>();
        try {
            jdbcTemplate.query(
                    "SELECT id, mcp_name, name, description " +
                            "FROM mcp_tool WHERE mcp_name = ?",
                    (rs, n) -> dbByName.put(rs.getString(3),
                            new McpToolInfo(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))),
                    mcpName);
        } catch (Exception ignore) {}
        // 拿 SDK 实时工具列表（保证 mcp_tool 表里没有的也展示出来）
        List<McpToolInfo> out = new ArrayList<>();
        for (McpRecord rec : mcp().mcps()) {
            if (!rec.name().equals(mcpName)) continue;
            if (rec.tools() == null) continue;
            for (ToolRecord t : rec.tools()) {
                McpToolInfo db = dbByName.get(t.name());
                if (db != null) {
                    // DB 有 → 描述优先 DB，没有就用 SDK 默认
                    String desc = db.description() != null ? db.description() : t.description();
                    out.add(new McpToolInfo(db.id(), db.mcpName(), db.name(), desc));
                } else {
                    // DB 没记录 → id=0（前端识别为"未维护"），描述用 SDK 默认
                    out.add(new McpToolInfo(0L, rec.name(), t.name(), t.description()));
                }
            }
        }
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return out;
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

    @Override
    public McpToolInfo upsertTool(Long toolId, String mcpName, String name, String description) {
        Object param = (description == null || description.isBlank()) ? null : description;
        if (toolId == null || toolId == 0L) {
            // 同一 (mcp_name, name) 已存在 → 覆盖；否则 INSERT
            List<Long> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM mcp_tool WHERE mcp_name = ? AND name = ?",
                    Long.class, mcpName, name);
            if (!existing.isEmpty()) {
                Long id = existing.get(0);
                jdbcTemplate.update("UPDATE mcp_tool SET description = ? WHERE id = ?", param, id);
                toolId = id;
            } else {
                jdbcTemplate.update(
                        "INSERT INTO mcp_tool (mcp_name, name, description, sort_order) VALUES (?, ?, ?, 0)",
                        mcpName, name, param);
                List<Long> ids = jdbcTemplate.queryForList(
                        "SELECT id FROM mcp_tool WHERE mcp_name = ? AND name = ?",
                        Long.class, mcpName, name);
                if (ids.isEmpty()) {
                    throw new LoomAgentRuntimeException("插入后未找到记录: mcpName=" + mcpName + " name=" + name);
                }
                toolId = ids.get(0);
            }
        } else {
            Integer n = jdbcTemplate.update(
                    "UPDATE mcp_tool SET description = ? WHERE id = ?", param, toolId);
            if (n == 0) throw new LoomAgentRuntimeException("工具不存在: id=" + toolId);
        }
        return jdbcTemplate.queryForObject(
                "SELECT id, mcp_name, name, description FROM mcp_tool WHERE id = ?",
                (rs, i) -> new McpToolInfo(
                        rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4)),
                toolId);
    }
}
