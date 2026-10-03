package cn.wubo.loom.http.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration.ServerMcpAnnotatedBeans;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4 个 {@link McpResource} URI 名单契约。
 *
 * <p><b>API 偏差记录</b>：Spring AI 2.0.1 资源与工具同走
 * {@link SyncMcpAnnotationProviders},资源方法经 {@link McpResource} 注解扫入
 * {@link ServerMcpAnnotatedBeans} 容器,然后由
 * {@code McpServerSpecificationFactoryAutoConfiguration} 桥接到
 * {@link McpServerFeatures.SyncResourceSpecification} 列表注册到
 * {@code McpSyncServer}。本类用 {@link SyncMcpAnnotationProviders#resourceSpecifications}
 * 复用同一桥接取到完整 schema,直接读 {@code .resource().uri()} / {@code .resource().name()} —— 与
 * 生产 MCP 客户端对 {@code resources/list} 收到的形状一致。
 */
@SpringBootTest(classes = LoomHttpMcpApplication.class)
@TestPropertySource(properties = {
        "spring.main.web-application-type=none",
        "spring.ai.mcp.server.stdio=false",
        "loom.http.mcp.basePath=${java.io.tmpdir}/loom-http-mcp-resource-surface-test"
})
@DisplayName("loom-http-mcp 资源面契约(4 个 URI)")
class HttpMcpResourceSurfaceTest {

    private static final List<String> EXPECTED_URIS = List.of(
            "system://list",
            "system://by-name",
            "system://endpoints",
            "system://endpoint"
    );

    private static final List<String> EXPECTED_NAMES = List.of(
            "system-list",
            "system",
            "system-endpoints",
            "system-endpoint"
    );

    @Autowired private ServerMcpAnnotatedBeans beans;

    @Test
    @DisplayName("4 个资源 URI 全部存在,无多余")
    void all4ResourcesPresent() {
        List<McpServerFeatures.SyncResourceSpecification> specs = SyncMcpAnnotationProviders
                .resourceSpecifications(beans.getBeansByAnnotation(McpResource.class));
        Set<String> actualUris = specs.stream()
                .map(s -> s.resource().uri())
                .collect(Collectors.toSet());
        Set<String> expected = new HashSet<>(EXPECTED_URIS);
        assertThat(actualUris).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("4 个资源 name 全部存在,无多余")
    void all4ResourceNamesPresent() {
        List<McpServerFeatures.SyncResourceSpecification> specs = SyncMcpAnnotationProviders
                .resourceSpecifications(beans.getBeansByAnnotation(McpResource.class));
        Set<String> actualNames = specs.stream()
                .map(s -> s.resource().name())
                .collect(Collectors.toSet());
        Set<String> expected = new HashSet<>(EXPECTED_NAMES);
        assertThat(actualNames).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("URI 名单与设计一致(4 个,全用单 URI + @McpArg)")
    void uriListMatchesDesign() {
        // 静态校验:不允许源码外的资源 URI 注册
        Method[] methods = LoomHttpMcpResources.class.getDeclaredMethods();
        long withMcpResource = Arrays.stream(methods)
                .filter(m -> m.isAnnotationPresent(McpResource.class))
                .count();
        assertThat(withMcpResource).isEqualTo(EXPECTED_URIS.size());
    }
}
