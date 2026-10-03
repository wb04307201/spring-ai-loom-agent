package cn.wubo.loom.file.mcp;

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
 * 锁定 {@code @McpTool} 参数的 {@code required} 数组 —— 缺陷回归锁。
 *
 * <p><b>缺陷来源</b>(Task 14 收尾,在 {@code loom-http-mcp} 上先发现,本模块同源):
 * {@code LoomFileMcpService} 原本 import 的是
 * {@code org.springframework.ai.tool.annotation.ToolParam}(spring-ai-<b>model</b> 的注解),
 * 而 MCP 侧的 schema 生成器({@code McpJsonSchemaGenerator},在 spring-ai-mcp-annotations 内)
 * <b>只认 {@code @McpToolParam}</b>。反编译其 {@code isMethodParameterRequired} 常量池,
 * 读取点只有 {@code McpToolParam.required()} / {@code JsonProperty.required()} /
 * {@code Schema.requiredMode()},<b>没有任何一处读 {@code @ToolParam}</b>
 * ⇒ {@code @ToolParam(required = false)} 对 MCP schema 生成器完全不可见。
 *
 * <p>未命中任何判定分支的参数落到硬编码的
 * {@code PROPERTY_REQUIRED_BY_DEFAULT = true}(private static final,<b>无开关可改</b>)
 * ⇒ 全部可选参数被标成必填 ⇒ 客户端省略任一个就报"未找到所需属性"。
 *
 * <p><b>为什么必须用测试锁</b>:MCP 客户端看到 {@code required} 后会强制填满所有字段,
 * 所以"工具能调通"往往只是因为客户端恰好填满了 —— 那不能证明 schema 是对的。
 * 本测试是这条缺陷<b>唯一的探测手段</b>。
 *
 * <p><b>schema 的实际形态</b>:{@code McpSchema.Tool.inputSchema()} 是
 * {@code Map<String,Object>},{@code toString()} 出来是 <b>Java Map 形态</b>
 * ({@code required=[a, b]})而非 JSON 形态({@code "required":["a"]})——
 * 断言必须按实际形态写,否则会写出永远为真的假断言。
 */
@SpringBootTest(classes = LoomFileMcpApplication.class)
@TestPropertySource(properties = {
        "spring.main.web-application-type=none",
        "spring.ai.mcp.server.stdio=false",
        // 覆盖默认的 ~/.loom/mcp,测试不得污染用户真实目录
        "loom.file.mcp.basePath=${java.io.tmpdir}/loom-file-mcp-schema-test"
})
@DisplayName("loom-file-mcp schema required 契约")
class LoomFileMcpSchemaRequiredTest {

    @Autowired
    private ServerMcpAnnotatedBeans beans;

    /**
     * 全量遍历:每个工具的每个参数,只要 Java 侧标了 {@code required=false},
     * 就不得出现在 schema 的 required 数组里。
     *
     * <p><b>按位置匹配,不按参数名</b>。本仓编译未开 {@code -parameters}
     * (javap 确认:签名里只有裸类型,无 MethodParameters 属性),
     * 反射拿到的是 {@code arg0/arg1/...} 而非 {@code head/tail}。
     * 早先版本用 {@code p.getName().equals(field)} 匹配 ⇒ 永远匹配不上 ⇒
     * 循环空转放行,变异测试(把注解回退成 @ToolParam)时本用例<b>假绿</b>。
     * schema 的 properties 键序与方法参数序一致,故按下标对齐。
     */
    @Test
    @DisplayName("标了 required=false 的参数不得进 schema 的 required 数组(全量遍历)")
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
                            "工具 " + tool + " 在 schema 里有,但在 " + LoomFileMcpService.class
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
                .as("反射应当至少看到 N 个 @McpToolParam 参数,实际=%d —— 若为 0 说明反射匹配空转了", annotated)
                .isGreaterThan(0);
        assertThat(violations)
                .as("以下参数在 Java 侧标了 required=false,却出现在 schema 的 required 数组里:%n%s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("read_text_file 只有 path 必填 —— 尖括号 head/tail 应当可选")
    void readTextFileOnlyPathIsRequired() {
        String schema = schemaOf("read_text_file");
        assertThat(requiredFields(schema))
                .as("read_text_file 的 required 应只剩 path,实际=%s", requiredFields(schema))
                .containsExactly("path");
    }

    @Test
    @DisplayName("write_file 的 content 必填,path 必填,其余可选")
    void writeFileRequiredFields() {
        String schema = schemaOf("write_file");
        assertThat(requiredFields(schema))
                .as("write_file 的 required,实际=%s", requiredFields(schema))
                .contains("path", "content");
    }

    private String schemaOf(String toolName) {
        return SyncMcpAnnotationProviders
                .toolSpecifications(beans.getBeansByAnnotation(McpTool.class)).stream()
                .filter(s -> toolName.equals(s.tool().name()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "找不到工具 " + toolName + ",现有:" + SyncMcpAnnotationProviders
                                .toolSpecifications(beans.getBeansByAnnotation(McpTool.class)).stream()
                                .map(s -> s.tool().name()).toList()))
                .tool().inputSchema().toString();
    }

    private List<java.lang.reflect.Method> toolMethods() {
        List<java.lang.reflect.Method> out = new ArrayList<>();
        for (java.lang.reflect.Method m : LoomFileMcpService.class.getDeclaredMethods()) {
            if (m.getAnnotation(McpTool.class) != null) out.add(m);
        }
        return out;
    }

    /** 从 schema 的 toString 里抽出 {@code required=[a, b, c]} 的字段列表。 */
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
     * {@code {properties={path={...}, head={...}, tail={...}}, required=[...]}}。
     * 这里只取 properties 块内、位于 {@code =} 之前且处于块顶层的键
     * (嵌套的 type/description 等子键靠 {@code {}} 深度排除)。
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
                // 回溯取键名
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

    /** {@code read_text_file} → {@code readTextFile};已是驼峰的返回原值。 */
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