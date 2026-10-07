package cn.wubo.spring.ai.loom.agent.mcp;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 用 {@code mcp_tool} 表维护的描述覆盖 MCP server 原始描述的装饰器。
 *
 * <p><b>为什么需要它</b>：{@code SyncMcpToolCallbackProvider} 生成的
 * {@code ToolDefinition} 直接取 {@code McpSchema.Tool.description()}（见 Spring AI
 * {@code McpToolUtils#createToolDefinition}），那是 MCP server 自带的原文。admin 在
 * 「MCP 描述维护」页写的中文描述只经 {@code AbstractMcp#convertToMcpRecord} 进入 UI，
 * LLM 路径完全绕过 —— 于是模型拿到的是上游英文描述而非人写的强信号描述，
 * 导致「有图表 MCP 却不用，改用 renderHtmlFile 画 SVG」这类降级。
 *
 * <p><b>语义</b>：仅覆盖 description；name 与 inputSchema 原样透传（name 可能是
 * {@code DefaultMcpToolNamePrefixGenerator} 前缀化后的 {@code alt_1_xxx}，覆盖它会破坏
 * 工具调用路由）。DB 未维护或描述为空白时 <b>原样返回 delegate 的定义</b>，
 * 逐字回退到 server 原文，绝不写入空串。
 *
 * <p>描述以 MCP <b>原始</b>工具名为 key（而非可能被前缀化的 {@code ToolDefinition.name()}），
 * 因此重名工具被改名前也能匹配到正确的维护描述。
 */
final class DbDescriptionToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final String dbDescription;

    DbDescriptionToolCallback(ToolCallback delegate, String dbDescription) {
        this.delegate = delegate;
        this.dbDescription = dbDescription;
    }

    static DbDescriptionToolCallback of(ToolCallback delegate, String dbDescription) {
        return new DbDescriptionToolCallback(delegate, dbDescription);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        ToolDefinition base = delegate.getToolDefinition();
        return DefaultToolDefinition.builder()
                .name(base.name())
                .description(dbDescription)
                .inputSchema(base.inputSchema())
                .build();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }
}