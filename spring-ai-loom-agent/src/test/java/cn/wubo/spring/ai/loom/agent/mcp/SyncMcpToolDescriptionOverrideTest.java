package cn.wubo.spring.ai.loom.agent.mcp;

import cn.wubo.spring.ai.loom.agent.model.McpSystemView;
import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the E2E 2026-10-01-2 Issue #1 follow-up: the operator-maintained
 * {@code mcp_tool.description} never reached the LLM.
 *
 * <p>Before the fix, {@link SyncMcp#getVisibleToolCallbackProvider} handed the raw
 * {@code McpSyncClient} list to {@code SyncMcpToolCallbackProvider}, whose
 * {@code McpToolUtils.createToolDefinition} takes {@code tool.description()} straight
 * from the MCP server. The DB-over-SDK precedence documented in
 * {@code V1.1__mcp_data.sql} ("DB 描述优先于 SDK fallback") therefore only ever applied
 * to the admin UI / picker panel — the 34 hand-written Chinese descriptions were
 * invisible to the model, which is why it failed to recognise the chart-generation tools.
 *
 * <p>Pins the observable contract, not the implementation:
 * <ul>
 *   <li>DB row present with text → LLM sees the DB description (R1).</li>
 *   <li>No DB row → LLM sees the server's original description (R2, fail-open).</li>
 *   <li>DB row present but blank → LLM sees the server's original description (R2).</li>
 *   <li>Name + input schema are never altered by the override; {@code call} still delegates.</li>
 * </ul>
 */
class SyncMcpToolDescriptionOverrideTest {

    private static final String CHART_MCP = "spring-ai-mcp-client - mcp-server-chart";
    private static final String SDK_DESC = "Generate a column chart. (SDK original English text)";

    private JdbcTemplate jdbcTemplate;
    private IRoleService roleService;
    private McpSyncClient client;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:mcp-desc-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        jdbcTemplate.execute("""
            CREATE TABLE mcp_tool (mcp_name VARCHAR(128), name VARCHAR(128), description CLOB)
            """);
        roleService = mock(IRoleService.class);
        when(roleService.getVisibleMcpsForUser("alice")).thenReturn(List.of(
                new McpSystemView(CHART_MCP, "图表生成", "AntV 图表服务", true, true, List.of())));
        client = mockClient(CHART_MCP);
    }

    /** A minimal McpSyncClient: initialized, one tool, plain-text listTools result. */
    private McpSyncClient mockClient(String clientName) {
        McpSyncClient c = mock(McpSyncClient.class);
        when(c.getClientInfo()).thenReturn(McpSchema.Implementation.builder(clientName, "1.0").build());
        when(c.isInitialized()).thenReturn(true);
        when(c.getClientCapabilities()).thenReturn(McpSchema.ClientCapabilities.builder().build());
        when(c.getCurrentInitializationResult()).thenReturn(null);
        McpSchema.Tool tool = McpSchema.Tool.builder("generate_column_chart")
                .description(SDK_DESC)
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("title", Map.of("type", "string"))))
                .build();
        when(c.listTools()).thenReturn(McpSchema.ListToolsResult.builder(List.of(tool)).build());
        return c;
    }

    private ToolCallback[] callbacksFor(SyncMcp syncMcp) {
        var provider = syncMcp.getVisibleToolCallbackProvider("alice", List.of(CHART_MCP));
        assertThat(provider).as("provider must be built for an authorized MCP").isNotNull();
        ToolCallback[] cbs = provider.getToolCallbacks();
        assertThat(cbs).hasSize(1);
        return cbs;
    }

    @Test
    void db_maintained_description_is_what_the_llm_sees() {
        jdbcTemplate.update(
                "INSERT INTO mcp_tool (mcp_name, name, description) VALUES (?, ?, ?)",
                CHART_MCP, "generate_column_chart",
                "生成柱状图（纵向）：用于类别数据的比较；当各数值接近时，柱状图比面积图、角度图更易判读高度差异");

        SyncMcp syncMcp = new SyncMcp(jdbcTemplate, List.of(client), roleService);
        ToolCallback cb = callbacksFor(syncMcp)[0];

        assertThat(cb.getToolDefinition().description())
                .as("covers R1: DB 描述必须覆盖 SDK 原文到达 LLM")
                .isEqualTo("生成柱状图（纵向）：用于类别数据的比较；当各数值接近时，柱状图比面积图、角度图更易判读高度差异");
    }

    @Test
    void missing_db_row_falls_back_to_sdk_description() {
        SyncMcp syncMcp = new SyncMcp(jdbcTemplate, List.of(client), roleService);
        ToolCallback cb = callbacksFor(syncMcp)[0];

        assertThat(cb.getToolDefinition().description())
                .as("covers R2: 无 DB 行时逐字回退 SDK 原文")
                .isEqualTo(SDK_DESC);
    }

    @Test
    void blank_db_description_falls_back_to_sdk_description() {
        jdbcTemplate.update(
                "INSERT INTO mcp_tool (mcp_name, name, description) VALUES (?, ?, ?)",
                CHART_MCP, "generate_column_chart", "   ");

        SyncMcp syncMcp = new SyncMcp(jdbcTemplate, List.of(client), roleService);
        ToolCallback cb = callbacksFor(syncMcp)[0];

        assertThat(cb.getToolDefinition().description())
                .as("covers R2: DB 描述为空白时不得覆盖成空串")
                .isEqualTo(SDK_DESC);
    }

    @Test
    void override_preserves_name_schema_and_delegates_call() {
        jdbcTemplate.update(
                "INSERT INTO mcp_tool (mcp_name, name, description) VALUES (?, ?, ?)",
                CHART_MCP, "generate_column_chart", "生成柱状图（纵向）");

        SyncMcp syncMcp = new SyncMcp(jdbcTemplate, List.of(client), roleService);
        ToolCallback cb = callbacksFor(syncMcp)[0];

        // Name / schema untouched — only description may change.
        assertThat(cb.getToolDefinition().name()).isEqualTo("generate_column_chart");
        assertThat(cb.getToolDefinition().inputSchema()).contains("title");
        assertThat(cb.getToolDefinition().description()).isEqualTo("生成柱状图（纵向）");

        // call() must still reach the MCP server (delegate), not the decorator.
        when(client.callTool(any(io.modelcontextprotocol.spec.McpSchema.CallToolRequest.class)))
                .thenReturn(McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("chart-bytes")))
                        .build());
        assertThat(cb.call("{\"title\":\"2024\"}")).contains("chart-bytes");
    }
}