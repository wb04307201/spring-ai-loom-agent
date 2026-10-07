package cn.wubo.spring.ai.loom.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.DefaultMcpToolNamePrefixGenerator;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在 {@code SyncMcpToolCallbackProvider} 之上叠加「DB 维护描述优先」的 provider。
 *
 * <p>{@code SyncMcpToolCallbackProvider} 生成的 {@code ToolDefinition} 直接取 MCP server
 * 原始 {@code description}；admin 在「MCP 描述维护」页写的中文描述只经
 * {@code AbstractMcp#convertToMcpRecord} 进入 UI，LLM 路径完全绕过。本 provider 复用
 * SDK 的全部构建逻辑（工具发现、重名前缀化、schema 规范化），只在最后一步把 description
 * 换成 DB 值 —— 查不到或空白时逐字回退原定义，绝不写入空串。
 *
 * <p>匹配 key 用 MCP <b>原始</b>工具名 {@code tool.name()}，而非可能被
 * {@link DefaultMcpToolNamePrefixGenerator} 改成 {@code alt_1_xxx} 的
 * {@code ToolDefinition.name()}，因此重名工具在改名前也能命中维护描述。
 */
@Slf4j
final class DbDescriptionAwareProvider implements ToolCallbackProvider {

    private final ToolCallback[] callbacks;

    private DbDescriptionAwareProvider(ToolCallback[] callbacks) {
        this.callbacks = callbacks;
    }

    /**
     * @param clients         已通过角色过滤、且已初始化的 MCP 客户端
     * @param descByMcpName   mcpName → (原始工具名 → DB 描述)
     */
    static ToolCallbackProvider of(List<McpSyncClient> clients, Map<String, Map<String, String>> descByMcpName) {
        McpToolNamePrefixGenerator prefixGen = new DefaultMcpToolNamePrefixGenerator();
        ToolContextToMcpMetaConverter converter = ToolContextToMcpMetaConverter.defaultConverter();
        List<ToolCallback> out = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();

        for (McpSyncClient client : clients) {
            String mcpName = safeClientName(client);
            Map<String, String> descByTool = descByMcpName.getOrDefault(mcpName, Map.of());
            List<McpSchema.Tool> tools;
            try {
                tools = client.listTools().tools();
            } catch (Exception e) {
                // 与 SyncMcpToolCallbackProvider 同策略：单个 MCP 拉取失败不拖垮其余工具。
                log.warn("MCP client {} listTools 失败，跳过其工具描述覆盖: {}", mcpName, e.getMessage());
                continue;
            }
            McpConnectionInfo info = McpConnectionInfo.builder()
                    .clientCapabilities(client.getClientCapabilities())
                    .clientInfo(client.getClientInfo())
                    .initializeResult(client.getCurrentInitializationResult())
                    .build();
            for (McpSchema.Tool tool : tools) {
                String name = prefixGen.prefixedToolName(info, tool);
                if (!usedNames.add(name)) {
                    // SDK 侧对重名工具会加 alt_N_ 前缀；此处保持同样的去重语义。
                    name = "alt_" + (usedNames.size() + 1) + "_" + name;
                    usedNames.add(name);
                }
                ToolCallback callback = SyncMcpToolCallback.builder()
                        .mcpClient(client)
                        .tool(tool)
                        .prefixedToolName(name)
                        .toolContextToMcpMetaConverter(converter)
                        .build();
                String dbDesc = descByTool.get(tool.name());
                out.add(StringUtils.hasText(dbDesc)
                        ? new DbDescriptionToolCallback(callback, dbDesc)
                        : callback);
            }
        }
        return new DbDescriptionAwareProvider(out.toArray(new ToolCallback[0]));
    }

    private static String safeClientName(McpSyncClient c) {
        try {
            return c.getClientInfo().name();
        } catch (Exception e) {
            return "<unknown>";
        }
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return callbacks;
    }
}