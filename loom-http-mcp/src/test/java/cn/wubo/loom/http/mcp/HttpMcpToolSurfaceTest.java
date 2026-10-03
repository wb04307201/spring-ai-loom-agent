package cn.wubo.loom.http.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration.ServerMcpAnnotatedBeans;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定 19 个 @McpTool 的 snake_case 全名单,任何变更是历史无法回顾。
 * <p>Task 12.1 提交时 4 个 http_* 注释掉(LoomHttpService stub-only);
 * Task 12.2 提交时取消注释,断言 19。
 * <p><b>API 偏差记录</b>:Spring AI 2.0.1 的 MCP server 端不暴露
 * {@code ToolCallbackProvider} —— {@code @McpTool} 方法经
 * {@link McpServerAnnotationScannerAutoConfiguration} 扫入
 * {@link ServerMcpAnnotatedBeans} 容器,然后由
 * {@code McpServerSpecificationFactoryAutoConfiguration} 桥接到
 * {@code McpServerFeatures.SyncToolSpecification} 列表注册到 McpSyncServer。
 * 测试用 {@link SyncMcpAnnotationProviders#toolSpecifications} 复用同一桥接
 * 取到完整 schema,直接读 {@code .tool().name()} / {@code .tool().description()}
 * —— 与生产 MCP 客户端对 tools/list 收到的形状一致。
 */
@SpringBootTest(classes = LoomHttpMcpApplication.class)
@TestPropertySource(properties = {
        "spring.main.web-application-type=none",
        "spring.ai.mcp.server.stdio=false",
        "loom.http.mcp.basePath=${java.io.tmpdir}/loom-http-mcp-tool-surface-test"
})
@DisplayName("loom-http-mcp 工具面契约(19 个 snake_case)")
class HttpMcpToolSurfaceTest {

    // Task 12.2 时取消注释 4 个 http_*
    private static final List<String> EXPECTED = List.of(
            "invoke_endpoint", "http_batch",
            "refresh_system", "register_system", "update_system", "remove_system",
            "add_profile", "update_profile", "remove_profile",
            "add_endpoint", "update_endpoint", "remove_endpoint",
            "list_endpoints", "get_endpoint",
            "get_request_history"
            //, "http_get", "http_post", "http_put", "http_delete"
    );

    @Autowired private ServerMcpAnnotatedBeans beans;

    @Test
    @DisplayName("15 个工具全部存在,无多余(LoomHttpMcpService 已实装)")
    void all15ToolsPresent() {
        List<McpServerFeatures.SyncToolSpecification> specs = SyncMcpAnnotationProviders
                .toolSpecifications(beans.getBeansByAnnotation(McpTool.class));
        Set<String> actual = specs.stream()
                .map(s -> s.tool().name())
                .collect(Collectors.toSet());
        // 防御性:从源码读不到的 @McpTool 不该进入契约 —— 重复断言确保 0 多余
        Set<String> expected = new HashSet<>(EXPECTED);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("invoke_endpoint 描述非空,含 spec 关键术语")
    void invokeEndpointHasMeaningfulDescription() {
        List<McpServerFeatures.SyncToolSpecification> specs = SyncMcpAnnotationProviders
                .toolSpecifications(beans.getBeansByAnnotation(McpTool.class));
        String desc = specs.stream()
                .filter(s -> "invoke_endpoint".equals(s.tool().name()))
                .findFirst().orElseThrow()
                .tool().description();
        assertThat(desc).contains("16 步");
    }
}
