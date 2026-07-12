package cn.wubo.spring.ai.loom.agent.mcp;

import cn.wubo.spring.ai.loom.agent.model.McpRecord;
import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class SyncMcp extends AbstractMcp {

    private final List<McpSyncClient> mcpSyncClients;
    private final IRoleService roleService;

    public SyncMcp(JdbcTemplate jdbcTemplate, List<McpSyncClient> mcpSyncClients, IRoleService roleService) {
        super(jdbcTemplate);
        this.mcpSyncClients = mcpSyncClients;
        this.roleService = roleService;
    }

    public List<McpRecord> mcps() {
        return mcpSyncClients.stream()
                .filter(McpSyncClient::isInitialized)
                .map(mcpSyncClient -> convertToMcpRecord(
                        mcpSyncClient.getClientInfo(),
                        mcpSyncClient.listTools().tools()
                ))
                .toList();
    }

    /**
     * 按用户角色过滤：合并用户所有角色的 mcp 列表，与 requestedMcps 求交集。
     * 用户选了不在自己角色内的 mcp：忽略 + warn。
     */
    public ToolCallbackProvider getVisibleToolCallbackProvider(String username, List<String> requestedMcps) {
        if (mcpSyncClients.isEmpty() || requestedMcps == null || requestedMcps.isEmpty()) {
            return null;
        }
        Set<String> allowed = new HashSet<>();
        for (var s : roleService.getVisibleMcpsForUser(username)) {
            allowed.add(s.name());
        }
        List<String> allowedToCall = requestedMcps.stream()
                .filter(m -> {
                    boolean ok = allowed.contains(m);
                    if (!ok) log.warn("用户 {} 请求了无权限的 mcp: {}", username, m);
                    return ok;
                })
                .toList();
        if (allowedToCall.isEmpty()) return null;

        List<McpSyncClient> picked = new ArrayList<>();
        for (McpSyncClient c : mcpSyncClients) {
            if (allowedToCall.contains(c.getClientInfo().name())) {
                if (c.isInitialized()) picked.add(c);
                else log.warn("McpSyncClient {} 未初始化", c.getClientInfo().name());
            }
        }
        if (picked.isEmpty()) return null;
        log.debug("McpSyncClient {} 初始化完成", picked.stream().map(McpSyncClient::getClientInfo).map(McpSchema.Implementation::name).collect(Collectors.joining(",")));
        return SyncMcpToolCallbackProvider.builder().mcpClients(picked).build();
    }
}
