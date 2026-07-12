package cn.wubo.spring.ai.loom.agent.mcp;

import cn.wubo.spring.ai.loom.agent.model.McpRecord;
import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class ASyncMcp extends AbstractMcp {

    private final List<McpAsyncClient> mcpAsyncClients;
    private final IRoleService roleService;

    public ASyncMcp(JdbcTemplate jdbcTemplate, List<McpAsyncClient> mcpAsyncClients, IRoleService roleService) {
        super(jdbcTemplate);
        this.mcpAsyncClients = mcpAsyncClients;
        this.roleService = roleService;
    }

    public List<McpRecord> mcps() {
        return mcpAsyncClients.stream()
                .map(mcpAsyncClient -> {
                    McpSchema.Implementation clientInfo = mcpAsyncClient.getClientInfo();
                    McpSchema.ListToolsResult listToolsResult = mcpAsyncClient.listTools().block();
                    return convertToMcpRecord(clientInfo, listToolsResult != null ? listToolsResult.tools() : List.of());
                })
                .toList();
    }

    public ToolCallbackProvider getVisibleToolCallbackProvider(String username, List<String> requestedMcps) {
        if (mcpAsyncClients.isEmpty() || requestedMcps == null || requestedMcps.isEmpty()) {
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

        List<McpAsyncClient> picked = new ArrayList<>();
        for (McpAsyncClient c : mcpAsyncClients) {
            if (allowedToCall.contains(c.getClientInfo().name())) {
                if (c.isInitialized()) picked.add(c);
                else log.warn("McpAsyncClient {} 未初始化", c.getClientInfo().name());
            }
        }
        if (picked.isEmpty()) return null;
        log.debug("McpAsyncClient {} 初始化完成", picked.stream().map(McpAsyncClient::getClientInfo).map(McpSchema.Implementation::name).collect(Collectors.joining(",")));
        return AsyncMcpToolCallbackProvider.builder().mcpClients(picked).build();
    }
}
