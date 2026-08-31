package cn.wubo.spring.ai.loom.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 统一 capability 描述。本地 tool group 与 MCP server 共用此 record。
 *
 * <p><b>id 命名空间：</b>
 * <ul>
 *   <li>本地 tool group：{@code "tool_" + @ToolGroup value}，例如 {@code "tool_file"}。
 *       注解里写纯名（{@code "file"}），由 {@link cn.wubo.spring.ai.loom.agent.capability.CapabilityService}
 *       在映射到 DB / API id 时统一加 {@code "tool_"} 前缀。</li>
 *   <li>MCP server：{@link io.modelcontextprotocol.client.McpSyncClient#getClientInfo()#name()}
 *       返回的真实 client name（当前是 {@code "spring-ai-mcp-client - <server>"} 格式）。
 *       <b>不做改名 / REPLACE</b>：DB 的 {@code role_mcp.mcp_name} 必须跟 live client name 一致，
 *       否则 {@code SyncMcp.getVisibleToolCallbackProvider} 的过滤会失配。</li>
 * </ul>
 *
 * <p>{@code effectiveEnabled} 是当前请求上下文计算出的"是否实际可调用"——综合：
 * 角色授权（role_*.default_enabled ∪ 默认行为）+ 用户会话勾选（{@code ChatRequestRecord.mcps()}
 * / {@code enabledToolGroups[]}）+ 服务端 is_active 等。{@code capabilityService.list(username)}
 * 把这个最终结果给前端；{@code DefaultChat} 在组装 tool callback 时也用同一份。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CapabilityInfo(
        String id,
        Type type,
        String name,
        String title,
        String description,
        List<ToolInfo> tools,
        Boolean effectiveEnabled
) {
    public enum Type { LOCAL, MCP }

    /**
     * 子工具。local 和 MCP 共用,前端统一展示（{@code id} 是工具方法名 / MCP tool name）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolInfo(String name, String description) {}
}