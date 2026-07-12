package cn.wubo.spring.ai.loom.agent.mcp;

import cn.wubo.spring.ai.loom.agent.model.McpRecord;
import cn.wubo.spring.ai.loom.agent.model.ToolRecord;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractMcp implements IMcp {

    protected final JdbcTemplate jdbcTemplate;

    protected AbstractMcp(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 把 McpClientInfo 转成 McpRecord，title/description 从 mcp_server 表读。
     * 工具的 description 从 mcp_tool 表读（DB 优先，没有则用 SDK 默认）。
     */
    protected McpRecord convertToMcpRecord(McpSchema.Implementation mcpSchemaImpl, List<McpSchema.Tool> mcpSchemaTools) {
        // mcp_server 元数据（title/desc，defaultSelected 已废弃）
        String title = mcpSchemaImpl.title();
        String description = null;
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT title, description FROM mcp_server WHERE name = ?",
                    mcpSchemaImpl.name());
            Object t = row.get("title");
            if (t != null && StringUtils.hasText(t.toString())) title = t.toString();
            Object d = row.get("description");
            if (d != null) description = d.toString();
        } catch (EmptyResultDataAccessException ignore) {
            // mcp_server 表里没记录（动态加的 MCP 或第一次启动还没 seed）→ 用 SDK 默认
        }

        // 工具元数据
        final String finalTitle = title;
        List<Map<String, Object>> dbTools = jdbcTemplate.queryForList(
                "SELECT name, description FROM mcp_tool WHERE mcp_name = ?", mcpSchemaImpl.name());
        Map<String, String> toolDescByName = new HashMap<>();
        for (Map<String, Object> r : dbTools) {
            Object n = r.get("name");
            Object d = r.get("description");
            if (n != null) toolDescByName.put(n.toString(), d == null ? null : d.toString());
        }
        final String finalDescription = description;
        List<ToolRecord> tools = mcpSchemaTools.stream()
                .map(t -> {
                    String dbDesc = toolDescByName.get(t.name());
                    String desc = (dbDesc != null && StringUtils.hasText(dbDesc)) ? dbDesc : t.description();
                    return new ToolRecord(t.name(), desc);
                })
                .toList();

        return new McpRecord(
                mcpSchemaImpl.name(),
                finalTitle,
                mcpSchemaImpl.version(),
                finalDescription,
                tools
        );
    }
}
