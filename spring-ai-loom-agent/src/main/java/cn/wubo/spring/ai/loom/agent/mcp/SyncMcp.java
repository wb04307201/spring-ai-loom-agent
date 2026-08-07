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
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * V5.1：单客户端 listTools() 内部走 Reactor 20s 超时；任一客户端慢/挂会卡死整个
 * /mcps 接口。"listSystem()" 调链：SyncMcp.mcps() → McpServerAdmin.listSystem() →
 * roleService.getVisibleMcpsForUser() → /mcps。客户端逐个 try/catch + 30s 缓存。
 */
@Slf4j
public class SyncMcp extends AbstractMcp {

    private static final long CACHE_TTL_MS = 30_000L;

    private final List<McpSyncClient> mcpSyncClients;
    private final IRoleService roleService;

    private final List<McpRecord> cachedList = new ArrayList<>();
    private final AtomicLong cachedAt = new AtomicLong(0);

    public SyncMcp(JdbcTemplate jdbcTemplate, List<McpSyncClient> mcpSyncClients, IRoleService roleService) {
        super(jdbcTemplate);
        this.mcpSyncClients = mcpSyncClients;
        this.roleService = roleService;
    }

    public List<McpRecord> mcps() {
        long now = System.currentTimeMillis();
        if (now - cachedAt.get() < CACHE_TTL_MS && !cachedList.isEmpty()) {
            return snapshot();
        }
        List<McpRecord> fresh = new ArrayList<>();
        for (McpSyncClient c : mcpSyncClients) {
            try {
                if (!c.isInitialized()) continue;
                McpRecord rec = convertToMcpRecord(
                        c.getClientInfo(),
                        c.listTools().tools());
                fresh.add(rec);
            } catch (Exception e) {
                // 单客户端失败不阻塞整体；常见原因：Reactor 20s 超时、stdio 进程已退出
                log.warn("MCP client {} listTools 失败，跳过本次: {}",
                        safeClientName(c), e.getMessage());
            }
        }
        if (!fresh.isEmpty()) {
            synchronized (this) {
                cachedList.clear();
                cachedList.addAll(fresh);
                cachedAt.set(now);
            }
            return snapshot();
        }
        if (cachedAt.get() != 0) {
            // SDK 全挂但有老缓存 → 继续用，避免 MCP 服务暂时卡顿后不可见
            log.warn("本次 SDK live 拉取为空，沿用 {}s 前的缓存", (now - cachedAt.get()) / 1000);
            return snapshot();
        }
        return fresh;
    }

    private List<McpRecord> snapshot() {
        synchronized (this) {
            return new ArrayList<>(cachedList);
        }
    }

    private static String safeClientName(McpSyncClient c) {
        try {
            return c.getClientInfo().name();
        } catch (Exception e) {
            return "<unknown>";
        }
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
