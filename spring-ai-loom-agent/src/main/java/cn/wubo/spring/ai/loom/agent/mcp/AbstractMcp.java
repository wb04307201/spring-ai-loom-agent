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
                    String desc = (StringUtils.hasText(dbDesc)) ? dbDesc : t.description();
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

    /**
     * 一次性载入某个 MCP 服务在 {@code mcp_tool} 表维护的全部工具描述。
     *
     * <p>与 {@link #convertToMcpRecord} 内那段查询同源（同一张表、同样的「DB 优先于
     * SDK」语义），单独抽出来是因为 LLM 调用路径不复用 {@code convertToMcpRecord}：
     * 那个方法只服务 admin UI / picker 面板。E2E 2026-10-01-2 复盘发现 admin 维护的
     * 中文工具描述只进了 UI，模型永远读不到上游英文原文 —— 于是有图表 MCP 却不用，
     * 改用 renderHtmlFile 画 SVG。
     *
     * @return toolName → DB 描述；无维护记录（或描述为 null）的 tool 不在表中
     */
    protected Map<String, String> dbToolDescriptions(String mcpName) {
        List<Map<String, Object>> dbTools = jdbcTemplate.queryForList(
                "SELECT name, description FROM mcp_tool WHERE mcp_name = ?", mcpName);
        Map<String, String> descByName = new HashMap<>();
        for (Map<String, Object> r : dbTools) {
            Object n = r.get("name");
            Object d = r.get("description");
            if (n != null) descByName.put(n.toString(), d == null ? null : d.toString());
        }
        return descByName;
    }
}
