package cn.wubo.loom.git.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration.ServerMcpAnnotatedBeans;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定 &#64;McpTool 参数的 required 数组 —— 缺陷回归锁。
 *
 * <p><b>缺陷来源</b>(Task 14 收尾,先在 loom-http-mcp 上发现,loom-git-mcp 同源):
 * LoomGitMcpService 原本 import 的是
 * {@code org.springframework.ai.tool.annotation.ToolParam}(spring-ai-<b>model</b> 的注解),
 * 而 MCP 侧的 schema 生成器({@code McpJsonSchemaGenerator},在 spring-ai-mcp-annotations 内)
 * <b>只认 {@code @McpToolParam}</b>。反编译其 {@code isMethodParameterRequired} 常量池,
 * 读取点只有 {@code McpToolParam.required()} / {@code JsonProperty.required()} /
 * {@code Schema.requiredMode()},<b>没有任何一处读 {@code @ToolParam}</b>
 * &#8658; {@code @ToolParam(required = false)} 对 MCP schema 生成器完全不可见。
 *
 * <p>未命中任何判定分支的参数落到硬编码的
 * {@code PROPERTY_REQUIRED_BY_DEFAULT = true}(private static final,<b>无开关可改</b>)
 * &#8658; 全部可选参数被标成必填 &#8658; 客户端省略任一个就报"未找到所需属性"。
 *
 * <p><b>为什么必须用测试锁</b>:MCP 客户端看到 required 后会强制填满所有字段,
 * 所以"工具能调通"往往只是因为客户端恰好填满了 —— 那不能证明 schema 是对的。
 *
 * <p><b>schema 的实际形态</b>:{@code McpSchema.Tool.inputSchema()} 是
 * {@code Map<String,Object>},{@code toString()} 出来是 <b>Java Map 形态</b>
 * ({@code required=[a, b]})而非 JSON 形态 —— 断言必须按实际形态写。
 */
@SpringBootTest(classes = LoomGitMcpApplication.class)
@TestPropertySource(properties = {
        "spring.main.web-application-type=none",
        "spring.ai.mcp.server.stdio=false",
        // 覆盖默认的 ~/.loom/mcp,测试不得污染用户真实目录
        "loom.git.mcp.basePath=${java.io.tmpdir}/loom-git-mcp-schema-test"
})
@DisplayName("loom-git-mcp schema required 契约")
class LoomGitMcpSchemaRequiredTest {

    @Autowired
    private ServerMcpAnnotatedBeans beans;

    @Test
    @DisplayName("标了 required=false 的参数不得进 schema 的 required 数组")
    void optionalParamsAreNotRequiredInSchema() {
        List<McpServerFeatures.SyncToolSpecification> specs = SyncMcpAnnotationProviders
                .toolSpecifications(beans.getBeansByAnnotation(McpTool.class));
        assertThat(specs).isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (McpServerFeatures.SyncToolSpecification spec : specs) {
            String tool = spec.tool().name();
            for (String field : requiredFields(spec.tool().inputSchema().toString())) {
                for (java.lang.reflect.Method method : toolMethods()) {
                    if (!method.getName().equals(toCamel(tool))) continue;
                    var param = Arrays.stream(method.getParameters())
                            .filter(p -> p.getName().equals(field)).findFirst();
                    if (param.isEmpty()) continue;
                    var ann = param.get().getAnnotation(
                            org.springframework.ai.mcp.annotation.McpToolParam.class);
                    if (ann != null && !ann.required()) {
                        violations.add(tool + "." + field);
                    }
                }
            }
        }
        assertThat(violations)
                .as("以下参数在 Java 侧标了 required=false,却出现在 schema 的 required 数组里:%n%s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("git_status 的 required 只剩真正必填的字段")
    void git_statusRequiredFields() {
        String schema = schemaOf("git_status");
        assertThat(requiredFields(schema))
                .as("git_status 的 required,实际=%s", requiredFields(schema))
                .containsExactlyInAnyOrder("workingDir");
    }

    private String schemaOf(String toolName) {
        List<McpServerFeatures.SyncToolSpecification> specs = SyncMcpAnnotationProviders
                .toolSpecifications(beans.getBeansByAnnotation(McpTool.class));
        return specs.stream()
                .filter(s -> toolName.equals(s.tool().name()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "找不到工具 " + toolName + ",现有:" + specs.stream()
                                .map(s -> s.tool().name()).toList()))
                .tool().inputSchema().toString();
    }

    private List<java.lang.reflect.Method> toolMethods() {
        List<java.lang.reflect.Method> out = new ArrayList<>();
        for (java.lang.reflect.Method m : LoomGitMcpService.class.getDeclaredMethods()) {
            if (m.getAnnotation(McpTool.class) != null) out.add(m);
        }
        return out;
    }

    /** 从 schema 的 toString 里抽出 required=[a, b, c] 的字段列表。 */
    static List<String> requiredFields(String schema) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("required=\\[([^\\]]*)\\]").matcher(schema);
        if (!m.find()) return List.of();
        String body = m.group(1).trim();
        if (body.isEmpty()) return List.of();
        return Arrays.stream(body.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** snake_case 工具名转驼峰方法名;已是驼峰的返回原值。 */
    static String toCamel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char ch : snake.toCharArray()) {
            if (ch == '_') { up = true; continue; }
            sb.append(up ? Character.toUpperCase(ch) : ch);
            up = false;
        }
        return sb.toString();
    }
}
