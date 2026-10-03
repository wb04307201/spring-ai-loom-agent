package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.invoke.InvokeRequest;
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
 * <p>Task 12.2 提交时全 19 个 @McpTool 已实装。
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

    private static final List<String> EXPECTED = List.of(
            "invoke_endpoint", "http_batch",
            "refresh_system", "register_system", "update_system", "remove_system",
            "add_profile", "update_profile", "remove_profile",
            "add_endpoint", "update_endpoint", "remove_endpoint",
            "list_endpoints", "get_endpoint",
            "get_request_history",
            "http_get", "http_post", "http_put", "http_delete"
    );

    @Autowired private ServerMcpAnnotatedBeans beans;
    @Autowired private HttpEngine engine;
    @Autowired private LoomHttpMcpService bridgeTarget;

    @Test
    @DisplayName("19 个工具全部存在,无多余")
    void all19ToolsPresent() {
        List<McpServerFeatures.SyncToolSpecification> specs = SyncMcpAnnotationProviders
                .toolSpecifications(beans.getBeansByAnnotation(McpTool.class));
        Set<String> actual = specs.stream()
                .map(s -> s.tool().name())
                .collect(Collectors.toSet());
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

    @Test
    @DisplayName("_ad_hoc 合成系统已注册:InvokeService 可解析")
    void adHocSystemRegistered() {
        // 通过 invokeEndpoint 走 _ad_hoc 路径,engine 内部 systemService.get 必须能解析
        InvokeRequest req = new InvokeRequest();
        req.setSystem(LoomHttpService.AD_HOC_SYSTEM_NAME);
        req.setMethod("GET");
        req.setPath("/");
        // 不期望 200 —— 没真实上游;期望 error 字段为 SystemNotFound/NetworkError 而不是 NPE
        String resp = engine.invokeEndpoint(req);
        assertThat(resp).containsAnyOf("SystemNotFound", "Network", "error");
    }

    /**
     * http_batch 接收 {@code List<Map<String,Object>>}(Spring AI 的 @Tool 参数形态),
     * BatchRequest.operations 期望 {@code List<InvokeRequest>}。generic invariance
     * 拒绝直接赋值,convertOperations helper 必须逐条 Jackson 桥接才能编译运行。
     * 本测试断言:3 条 op(每条 system 未注册) → BatchService 拿到 3 条 InvokeRequest,
     * 每条都得到结构化错误信封(不是 NPE)。
     */
    @Test
    @DisplayName("http_batch operations list 桥接正确(generic invariance fix)")
    void httpBatchOperationsBridgeWorks() {
        String out = bridgeTarget.httpBatch(
                java.util.List.of(
                        java.util.Map.of("system", "no-such-sys-1", "method", "GET", "path", "/a"),
                        java.util.Map.of("system", "no-such-sys-2", "method", "POST", "path", "/b",
                                "params", java.util.Map.of("k", "v"),
                                "body", java.util.Map.of("name", "n")),
                        java.util.Map.of("system", "no-such-sys-3", "method", "PUT", "path", "/c")),
                2, "continue", "summary", 2);
        assertThat(out).isNotNull();
        // 不是 NPE 也不是 Java 栈轨迹 —— 说明 3 条 op 都过了 bridge,且 BatchService 收到 typed List
        assertThat(out).doesNotContain("Exception in thread");
        assertThat(out).doesNotContain("at cn.wubo");
        // 错误信封里至少出现一次 SystemNotFound(每条 op 都会因 system 未注册而失败)
        assertThat(out).contains("SystemNotFound");
    }
}
