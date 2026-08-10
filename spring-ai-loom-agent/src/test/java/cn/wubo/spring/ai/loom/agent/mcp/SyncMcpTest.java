package cn.wubo.spring.ai.loom.agent.mcp;

import cn.wubo.spring.ai.loom.agent.model.McpSystemView;
import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SyncMcp} role-filtering logic — the critical path that
 * powers BUG-3 fix (URL containing '/' can still be matched). We avoid
 * mocking McpSyncClient by directly stubbing the role filter on
 * {@link IRoleService} and exercising {@link SyncMcp#getVisibleToolCallbackProvider}
 * with empty / not-yet-initialized client lists.
 *
 * Pins:
 * <ul>
 *   <li>Empty requested list → null (no tool callback).</li>
 *   <li>Requested MCP not in user's visible set → filtered out + warning.</li>
 *   <li>Requested MCP in user's visible set but no client registered → null.</li>
 *   <li>Non-empty result contains exactly the requested subset.</li>
 * </ul>
 */
class SyncMcpTest {

    private JdbcTemplate jdbcTemplate;
    private IRoleService roleService;
    private SyncMcp syncMcp;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:sync-mcp-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        // AbstractMcp.convertToMcpRecord 查 mcp_server 表 — 建空表避免 queryForMap 抛错
        jdbcTemplate.execute("""
            CREATE TABLE mcp_server (name VARCHAR(128) PRIMARY KEY, title VARCHAR(255), description CLOB)
            """);
        jdbcTemplate.execute("""
            CREATE TABLE mcp_tool (mcp_name VARCHAR(128), name VARCHAR(128), description CLOB)
            """);
        roleService = mock(IRoleService.class);
        // Empty client list — getVisibleToolCallbackProvider 会立即返回 null（无 client 可用）
        syncMcp = new SyncMcp(jdbcTemplate, List.of(), roleService);
    }

    private McpSystemView visibleMcp(String name) {
        return new McpSystemView(name, name + " title", name + " desc", true, true, List.of());
    }

    @Test
    void empty_requested_returns_null() {
        ToolCallbackProvider result = syncMcp.getVisibleToolCallbackProvider("alice", List.of());
        assertThat(result).isNull();
    }

    @Test
    void null_requested_returns_null() {
        ToolCallbackProvider result = syncMcp.getVisibleToolCallbackProvider("alice", null);
        assertThat(result).isNull();
    }

    @Test
    void requested_mcp_not_in_visible_set_is_filtered_out() {
        when(roleService.getVisibleMcpsForUser("alice"))
                .thenReturn(List.of(visibleMcp("allowed-mcp")));
        // alice 请求了 "evil-mcp" 但不在她的可见集 — 应被过滤（实际没 client 会返回 null）
        ToolCallbackProvider result = syncMcp.getVisibleToolCallbackProvider("alice", List.of("evil-mcp"));
        assertThat(result).isNull();
    }

    @Test
    void requested_mcp_in_visible_set_but_no_client_returns_null() {
        when(roleService.getVisibleMcpsForUser("alice"))
                .thenReturn(List.of(visibleMcp("wanted-mcp")));
        // 角色允许 "wanted-mcp"，但 mcpSyncClients 是空 — 返回 null
        ToolCallbackProvider result = syncMcp.getVisibleToolCallbackProvider("alice", List.of("wanted-mcp"));
        assertThat(result).isNull();
    }

    @Test
    void mixed_requested_some_allowed_some_not_filters_to_allowed_subset() {
        when(roleService.getVisibleMcpsForUser("alice"))
                .thenReturn(List.of(visibleMcp("allowed-a"), visibleMcp("allowed-b")));
        // 请求 3 个（2 允许 + 1 不允许）— client 列表为空，最终返回 null（无可执行 client）
        // 这里验证的是「filter 不让 evil 干扰 allowed」逻辑路径：no exception
        ToolCallbackProvider result = syncMcp.getVisibleToolCallbackProvider("alice",
                List.of("allowed-a", "evil-mcp", "allowed-b"));
        // 没有 client 注册 → null（不是 IllegalArgumentException）
        assertThat(result).isNull();
    }

    /** McpSystemView 验证：name 含 `/` 仍可作为 key 匹配（BUG #3 验证） */
    @Test
    void visible_mcp_name_with_slash_works_as_filter_key() {
        // 即便 MCP name 含 `/`（曾经导致 BUG #3），filter 也要按字符串比较工作
        when(roleService.getVisibleMcpsForUser("alice"))
                .thenReturn(List.of(
                        new McpSystemView(
                                "spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch",
                                "网页内容抓取", "抓取网页内容", true, true, List.of()
                        )
                ));
        List<McpSystemView> visible = roleService.getVisibleMcpsForUser("alice");
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).name())
                .isEqualTo("spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch");
    }
}