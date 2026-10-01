package cn.wubo.spring.ai.loom.agent.mcp;

import cn.wubo.spring.ai.loom.agent.model.McpRecord;
import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test: {@link SyncMcp#mcps()} must not let a <b>partial</b> SDK snapshot
 * overwrite a good cache.
 *
 * <p>Found while investigating E2E 2026-10-01-2: {@code listTools()} failures are caught
 * per-client and skipped, so any single flaky MCP produced a truncated list which was then
 * written into the 30s cache. For the whole TTL the recovered MCP stayed invisible, and —
 * worse — {@code GET /api/capabilities} serves that same truncated list, which the chat
 * picker persists verbatim on a user's first load
 * ({@code app.js loadList() → selectedMcps = mcpIds.slice() → _savePersisted()}),
 * permanently dropping that MCP from the user's picker.
 *
 * <p>{@code mcp-server-chart} is the natural victim: it initializes last (~1.2s before
 * startup completes), so a cold {@code npx} fetch of the package races the 20s
 * {@code listTools()} timeout.
 */
class SyncMcpCacheIntegrityTest {

    private JdbcTemplate jdbcTemplate;
    private IRoleService roleService;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:cache-integrity-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        jdbcTemplate.execute("CREATE TABLE mcp_server (name VARCHAR(128) PRIMARY KEY, title VARCHAR(255), description CLOB)");
        jdbcTemplate.execute("CREATE TABLE mcp_tool (mcp_name VARCHAR(128), name VARCHAR(128), description CLOB)");
        roleService = mock(IRoleService.class);
    }

    /** MCP client whose {@code listTools()} can be flipped to fail, simulating a flaky server. */
    private static final class FlakyClient {
        final AtomicBoolean fail = new AtomicBoolean(false);
        final McpSyncClient client;
        private final String name;

        FlakyClient(String name) {
            this.name = name;
            this.client = mock(McpSyncClient.class);
            when(client.getClientInfo()).thenReturn(McpSchema.Implementation.builder(name, "1.0").build());
            when(client.isInitialized()).thenReturn(true);
            when(client.getClientCapabilities()).thenReturn(McpSchema.ClientCapabilities.builder().build());
            when(client.getCurrentInitializationResult()).thenReturn(null);
            McpSchema.Tool tool = McpSchema.Tool.builder("some_tool").description("d")
                    .inputSchema(Map.of("type", "object")).build();
            when(client.listTools()).thenAnswer(inv -> {
                if (fail.get()) throw new RuntimeException("Reactor 20s timeout (simulated)");
                return McpSchema.ListToolsResult.builder(List.of(tool)).build();
            });
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static List<String> namesOf(List<McpRecord> recs) {
        return recs.stream().map(McpRecord::name).toList();
    }

    /**
     * Force the TTL to look expired so the next {@code mcps()} re-fetches from the SDK,
     * <b>without</b> discarding the cache contents. {@code cachedAt} doubles as the
     * "have we ever cached anything?" sentinel ({@code != 0}), so expiring it must set a
     * timestamp that is merely old — never 0, which production code reads as "no prior cache".
     */
    private static void expireCache(SyncMcp syncMcp) {
        try {
            var field = SyncMcp.class.getDeclaredField("cachedAt");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.atomic.AtomicLong cachedAt =
                    (java.util.concurrent.atomic.AtomicLong) field.get(syncMcp);
            cachedAt.set(System.currentTimeMillis() - java.time.Duration.ofHours(1).toMillis());
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new AssertionError("无法让缓存过期,测试前提不成立", e);
        }
    }

    @Test
    void recovered_client_becomes_visible_when_no_prior_cache_exists() {
        FlakyClient good = new FlakyClient("good-mcp");
        FlakyClient chart = new FlakyClient("chart-mcp");
        chart.fail.set(true);
        SyncMcp syncMcp = new SyncMcp(jdbcTemplate, List.of(good.client, chart.client), roleService);

        // 1st pull: chart times out → only good-mcp comes back.
        assertThat(namesOf(syncMcp.mcps()))
                .as("chart 超时,本次缺席")
                .containsExactly("good-mcp");

        // chart's stdio process finishes coming up.
        chart.fail.set(false);

        // 2nd pull: the truncated result must NOT have been cached, so chart is back now.
        assertThat(namesOf(syncMcp.mcps()))
                .as("covers R1: 恢复后无需等待 TTL 即重新可见")
                .containsExactly("good-mcp", "chart-mcp");
    }

    @Test
    void partial_failure_does_not_truncate_an_existing_good_cache() {
        FlakyClient good = new FlakyClient("good-mcp");
        FlakyClient chart = new FlakyClient("chart-mcp");
        SyncMcp syncMcp = new SyncMcp(jdbcTemplate, List.of(good.client, chart.client), roleService);

        // A complete snapshot first — this is the cache we must not lose.
        assertThat(namesOf(syncMcp.mcps())).containsExactly("good-mcp", "chart-mcp");

        chart.fail.set(true);
        expireCache(syncMcp);

        // chart now fails: we keep serving the last-known-good list rather than a truncated one.
        assertThat(namesOf(syncMcp.mcps()))
                .as("covers R2: 残缺结果不得覆盖已有完整缓存")
                .containsExactly("good-mcp", "chart-mcp");

        // And once it recovers, the next pull is complete again.
        chart.fail.set(false);
        expireCache(syncMcp);
        assertThat(namesOf(syncMcp.mcps()))
                .as("covers R2: 恢复后快照重新完整")
                .containsExactly("good-mcp", "chart-mcp");
    }

    @Test
    void all_clients_failing_still_serves_last_known_good() {
        FlakyClient good = new FlakyClient("good-mcp");
        FlakyClient chart = new FlakyClient("chart-mcp");
        SyncMcp syncMcp = new SyncMcp(jdbcTemplate, List.of(good.client, chart.client), roleService);
        assertThat(namesOf(syncMcp.mcps())).containsExactly("good-mcp", "chart-mcp");

        good.fail.set(true);
        chart.fail.set(true);
        expireCache(syncMcp);

        assertThat(namesOf(syncMcp.mcps()))
                .as("covers R3: 全挂时沿用旧缓存,不返回空列表")
                .containsExactly("good-mcp", "chart-mcp");
    }
}