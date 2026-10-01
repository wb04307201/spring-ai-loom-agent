package cn.wubo.spring.ai.loom.agent.mcp;

import cn.wubo.spring.ai.loom.agent.model.McpRecord;
import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * ：单客户端 listTools() 内部走 Reactor 20s 超时；任一客户端慢/挂会卡死整个
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

    private static String safeClientName(McpSyncClient c) {
        try {
            return c.getClientInfo().name();
        } catch (Exception e) {
            return "<unknown>";
        }
    }

    public List<McpRecord> mcps() {
        long now = System.currentTimeMillis();
        if (now - cachedAt.get() < CACHE_TTL_MS && !cachedList.isEmpty()) {
            return snapshot();
        }
        List<McpRecord> fresh = new ArrayList<>();
        int failed = 0;
        for (McpSyncClient c : mcpSyncClients) {
            try {
                if (!c.isInitialized()) continue;
                McpRecord rec = convertToMcpRecord(
                        c.getClientInfo(),
                        c.listTools().tools());
                fresh.add(rec);
            } catch (Exception e) {
                // 单客户端失败不阻塞整体；常见原因：Reactor 20s 超时、stdio 进程已退出
                failed++;
                log.warn("MCP client {} listTools 失败，跳过本次: {}",
                        safeClientName(c), e.getMessage());
            }
        }
        // 2026-10-01(E2E 2026-10-01-2 复盘):「部分失败」的结果是**残缺**的 ——
        // 它既不能进缓存(会把上一份完整快照截断,导致已恢复的 MCP 在整个 TTL 内不可见),
        // 也不能作为权威列表喂给前端 picker(前端首次加载会把 listCapabilities 的返回值
        // 原样 _savePersisted 落盘 → 该 MCP 从此再也不会默认勾选,用户永久丢失它)。
        // 因此:有 client 失败时,只要还有上一份缓存就沿用它,等 TTL 过期后重新拉完整快照。
        boolean partial = failed > 0 && fresh.size() < mcpSyncClients.size();
        if (partial) {
            if (cachedAt.get() != 0) {
                log.warn("本次 SDK 拉取不完整({}/{} 成功)，沿用 {}s 前的完整缓存；恢复后下次刷新可见",
                        fresh.size(), mcpSyncClients.size(), (now - cachedAt.get()) / 1000);
                return snapshot();
            }
            // 还没有过完整缓存(冷启动首拉):不写缓存,让下一次调用重新拉全量。
            // 首次仍返回本次拿到的部分结果,避免整个 MCP 列表在冷启动窗口内空白。
            log.warn("本次 SDK 拉取不完整({}/{} 成功)且无历史缓存，不写入缓存；下次调用将重新拉取",
                    fresh.size(), mcpSyncClients.size());
            return fresh;
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
        // 载入 mcp_tool 表维护的工具描述:DB 优先于 SDK 原文,让 LLM 也读到人写的描述
        // (E2E 2026-10-01-2 Issue #1)。任一服务查询失败只降级为「用 SDK 原文」,
        // 不影响工具可用性。
        java.util.Map<String, java.util.Map<String, String>> descByMcpName = new java.util.LinkedHashMap<>();
        for (McpSyncClient c : picked) {
            try {
                descByMcpName.put(c.getClientInfo().name(), dbToolDescriptions(c.getClientInfo().name()));
            } catch (Exception e) {
                log.warn("读取 mcp_tool 描述失败,mcp={} 将使用 SDK 原始描述: {}",
                        safeClientName(c), e.getMessage());
            }
        }
        return DbDescriptionAwareProvider.of(picked, descByMcpName);
    }
}
