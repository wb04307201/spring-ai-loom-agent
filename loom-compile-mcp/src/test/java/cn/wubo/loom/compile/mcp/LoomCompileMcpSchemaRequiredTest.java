package cn.wubo.loom.compile.mcp;

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
 * <p><b>缺陷来源</b>(Task 14 收尾,先在 loom-http-mcp 上发现,loom-compile-mcp 同源):
 * LoomCompileMcpService 原本 import 的是
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
@SpringBootTest(classes = LoomCompileMcpApplication.class)
@TestPropertySource(properties = {
        "spring.main.web-application-type=none",
        "spring.ai.mcp.server.stdio=false",
        // 覆盖默认的 ~/.loom/mcp,测试不得污染用户真实目录
        "loom.compile.mcp.basePath=${java.io.tmpdir}/loom-compile-mcp-schema-test"
})
@DisplayName("loom-compile-mcp schema required 契约")
class LoomCompileMcpSchemaRequiredTest {

    @Autowired
    private ServerMcpAnnotatedBeans beans;

    @Test
    @DisplayName("标了 required=false 的参数不得进 schema 的 required 数组")
    void optionalParamsAreNotRequiredInSchema() {
        List<McpServerFeatures.SyncToolSpecification> specs = SyncMcpAnnotationProviders
                .toolSpecifications(beans.getBeansByAnnotation(McpTool.class));
        assertThat(specs).isNotEmpty();

        List<String> violations = new ArrayList<>();
        int checked = 0;
        int annotated = 0;
        for (McpServerFeatures.SyncToolSpecification spec : specs) {
            String tool = spec.tool().name();
            java.lang.reflect.Method method = toolMethods().stream()
                    .filter(m -> m.getName().equals(toCamel(tool))).findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "工具 " + tool + " 在 schema 里有,但在 " + LoomCompileMcpService.class
                                    .getSimpleName() + " 上找不到同名方法"));
            var params = method.getParameters();
            List<String> required = requiredFields(spec.tool().inputSchema().toString());
            // schema 的 properties 键序 = 参数声明序
            List<String> fieldNames = propertyNames(spec.tool().inputSchema().toString());

            for (int i = 0; i < params.length; i++) {
                var ann = params[i].getAnnotation(
                        org.springframework.ai.mcp.annotation.McpToolParam.class);
                if (ann == null) continue;
                annotated++;
                if (ann.required()) continue;   // 只校验显式可选的参数
                checked++;
                String field = i < fieldNames.size() ? fieldNames.get(i) : "<第" + i + "个参数>";
                if (required.contains(field)) violations.add(tool + "." + field);
            }
        }
        assertThat(annotated)
                .as("反射应当至少看到 N 个 @McpToolParam 参数,实际=%d —— 若为 0 说明反射匹配空转了。"
                        + "注:本模块 compile_and_deploy 唯一参数 params 未标 required=false,"
                        + "故可选参数数(checked)合法地为 0,不能拿它当防空转的判据", annotated)
                .isGreaterThan(0);
        assertThat(violations)
                .as("以下参数在 Java 侧标了 required=false,却出现在 schema 的 required 数组里:%n%s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("compile_and_deploy 的 required 只剩真正必填的字段")
    void compileAndDeployRequiredFields() {
        String schema = schemaOf("compile_and_deploy");
        assertThat(requiredFields(schema))
                .as("compile_and_deploy 的 required,实际=%s", requiredFields(schema))
                .containsExactlyInAnyOrder("params");
    }

    /**
     * 本模块只有一个工具、且唯一参数必填,所以上面的"可选参数不得进 required"用例
     * <b>天然空转</b>(实测:把注解回退成 &#64;ToolParam,该用例仍绿)。
     * 这不是测试有漏洞,而是<b>这个工具本来就没有可选参数</b> —— 注解错配对它无影响。
     *
     * <p>但注解替换在这里<b>仍有实质意义</b>:换注解之前,&#64;ToolParam 上的
     * {@code description} 对 MCP 客户端<b>完全不可见</b>(schema 生成器不读它),
     * 换成 &#64;McpToolParam 之后才第一次发布。本用例锁住 description 确实出现在 schema 里 ——
     * 这是本模块唯一能被该缺陷影响的 observable。
     */
    @Test
    @DisplayName("params 的 description 确实发布到了 schema(注解替换后才可见)")
    void paramsDescriptionIsPublishedToSchema() {
        String schema = schemaOf("compile_and_deploy");
        assertThat(schema)
                .as("compile_and_deploy 的 schema 应含 params 的 description(它列出 gitUrl/port/buildTool 等键名)")
                .contains("description=")
                .contains("gitUrl");
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
        for (java.lang.reflect.Method m : LoomCompileMcpService.class.getDeclaredMethods()) {
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

    /**
     * 从 schema 的 toString 里抽出 properties 的键名,<b>按出现顺序</b>
     * —— schema 生成器按方法参数声明序写入 properties,故该顺序即参数序。
     *
     * <p>schema 是 {@code Map<String,Object>},toString 形态为
     * {@code {properties={path={...}, head={...}}, required=[...]}}。
     * 只取 properties 块内、处于块顶层且位于 {@code =} 之前的键
     * (嵌套的 type/description 等子键靠 {@code {}} 深度排除)。
     *
     * <p><b>为什么要按位置而非按名字对齐</b>:本仓编译未开 {@code -parameters}
     * (javap 确认:签名里只有裸类型,无 MethodParameters 属性),反射拿到的是
     * {@code arg0/arg1/...} 而非真实参数名。早先版本用
     * {@code p.getName().equals(field)} 匹配 ⇒ 永远匹配不上 ⇒ 循环空转放行,
     * 变异测试(注解回退成 @ToolParam)时本用例<b>假绿</b>。
     */
    static List<String> propertyNames(String schema) {
        int start = schema.indexOf("properties={");
        if (start < 0) return List.of();
        int i = start + "properties=".length();
        List<String> names = new ArrayList<>();
        int depth = 0;
        boolean expectKey = true;
        for (; i < schema.length(); i++) {
            char c = schema.charAt(i);
            if (c == '{' || c == '[') { depth++; continue; }
            if (c == '}' || c == ']') {
                if (depth == 0) break;      // properties 块结束
                depth--; continue;
            }
            if (depth == 0 && expectKey && c == '=') {
                int end = i;
                while (end > 0 && Character.isWhitespace(schema.charAt(end - 1))) end--;
                int s = end;
                while (s > 0 && (Character.isLetterOrDigit(schema.charAt(s - 1))
                        || schema.charAt(s - 1) == '_')) s--;
                if (s < end) names.add(schema.substring(s, end));
                expectKey = false;
                continue;
            }
            if (depth == 0 && c == ',') { expectKey = true; continue; }
        }
        return names;
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
