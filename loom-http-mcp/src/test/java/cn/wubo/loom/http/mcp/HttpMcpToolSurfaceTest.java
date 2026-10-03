package cn.wubo.loom.http.mcp;

import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.invoke.InvokeRequest;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
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

    /**
     * 锁定"标了 {@code required = false} 的参数不得出现在 schema 的 {@code required} 数组里"。
     *
     * <p><b>缺陷来源</b>(Task 14 收尾,真实 MCP 客户端逐个调用时暴露):
     * {@code http_get} 等四个工具全部报
     * {@code 未找到所需属性"headers"} / {@code "profile"} / {@code "assertions"} / {@code "extract"}。
     *
     * <p><b>根因</b>:本项目的 {@code LoomHttpService} 与 {@code LoomHttpMcpService} import 的是
     * {@code org.springframework.ai.tool.annotation.ToolParam}(spring-ai-<b>model</b> 的注解),
     * 而 MCP 侧的 schema 生成器({@code McpJsonSchemaGenerator},在 spring-ai-mcp-annotations 内)
     * <b>只认 {@code @McpToolParam}</b>。反编译其 {@code isMethodParameterRequired} 常量池,
     * 读取点只有 {@code McpToolParam.required()} / {@code JsonProperty.required()} /
     * {@code Schema.requiredMode()},<b>没有任何一处读 {@code @ToolParam}</b>
     * ⇒ {@code @ToolParam(required = false)} 对 MCP schema 生成器完全不可见。
     *
     * <p>未命中任何判定分支的参数会落到硬编码的 {@code PROPERTY_REQUIRED_BY_DEFAULT = true}
     * (private static final,<b>无开关可改</b>)⇒ 全部可选参数被标成必填。
     *
     * <p><b>为什么必须用测试锁</b>:MCP 客户端看到 {@code required} 后会强制填满所有字段 ——
     * 所以 {@code list_endpoints} / {@code get_request_history} 实测"能用"是<b>假象</b>
     * (客户端恰好填满了),而不是它们本身是对的。本测试是这条缺陷<b>唯一的探测手段</b>。
     */
    @Test
    @DisplayName("标了 required=false 的参数不得进 schema 的 required 数组")
    void optionalParamsAreNotRequiredInSchema() {
        List<McpServerFeatures.SyncToolSpecification> specs = SyncMcpAnnotationProviders
                .toolSpecifications(beans.getBeansByAnnotation(McpTool.class));

        // http_get 的 headers/profile/assertions/extract/responseMode 全是可选
        String httpGetSchema = specs.stream()
                .filter(s -> "http_get".equals(s.tool().name()))
                .findFirst().orElseThrow()
                .tool().inputSchema().toString();
        for (String optional : List.of("headers", "profile", "assertions", "extract", "responseMode")) {
            assertThat(requiredFields(httpGetSchema))
                    .as("http_get 的可选参数 %s 不该出现在 required 里%n实际 required=%s", optional, requiredFields(httpGetSchema))
                    .doesNotContain(optional);
        }
        // url 是唯一必填项,必须仍在 required 里(否则会退化成"什么都不用传")
        assertThat(requiredFields(httpGetSchema)).containsExactly("url");

        // invoke_endpoint 的可选参数同理(body/headers/params/assertions/extract/responseMode/timeoutMs)
        String invokeSchema = specs.stream()
                .filter(s -> "invoke_endpoint".equals(s.tool().name()))
                .findFirst().orElseThrow()
                .tool().inputSchema().toString();
        for (String optional : List.of("body", "headers", "params", "assertions",
                "extract", "responseMode", "timeoutMs")) {
            assertThat(requiredFields(invokeSchema))
                    .as("invoke_endpoint 的可选参数 %s 不该出现在 required 里%n实际 required=%s", optional, requiredFields(invokeSchema))
                    .doesNotContain(optional);
        }
        assertThat(requiredFields(invokeSchema)).contains("system", "method", "path");
    }

    /**
     * 全量扫描:任何工具里,凡是 Java 签名标了 {@code required = false} 的参数,
     * 都不该出现在该工具 schema 的 required 数组中。
     *
     * <p>本用例是上面那条定点断言的<b>全量版</b> —— 防止将来新增工具时重犯
     * (LoomHttpMcpService 有 15 个工具,逐个手写断言既漏又难维护)。
     */
    @Test
    @DisplayName("全量扫描:19 个工具的可选参数一律不进 required")
    void noToolDeclaresOptionalParamAsRequired() {
        List<McpServerFeatures.SyncToolSpecification> specs = SyncMcpAnnotationProviders
                .toolSpecifications(beans.getBeansByAnnotation(McpTool.class));

        StringBuilder violations = new StringBuilder();
        for (McpServerFeatures.SyncToolSpecification spec : specs) {
            String name = spec.tool().name();
            String schema = spec.tool().inputSchema().toString();
            for (String field : requiredFields(schema)) {
                // 找到该工具对应的 Java 方法,看它是否标了 required = false
                for (java.lang.reflect.Method method : allToolMethods()) {
                    if (!method.getName().equals(toCamel(name))) continue;
                    var param = java.util.Arrays.stream(method.getParameters())
                            .filter(p -> p.getName().equals(field))
                            .findFirst();
                    if (param.isEmpty()) continue;
                    var ann = param.get().getAnnotation(McpToolParam.class);
                    if (ann != null && !ann.required()) {
                        violations.append(String.format("%s.%s%n", name, field));
                    }
                }
            }
        }
        assertThat(violations.toString())
                .as("以下参数在 Java 侧标了 required=false,却出现在 schema 的 required 数组里:%n%s", violations)
                .isEmpty();
    }

    /**
     * 锁定参数 description 不得再宣称"断言 = {type, value}"。
     *
     * <p><b>为什么这条很重要</b>(deep audit {@code aud_c89sXNDSgU} 的 primary f3 / feat f5):
     * 原文案写"断言列表 {type,value}",暗示 type 可以随便写(如 {@code equals})。
     * 但 {@code AssertionEngine} 只支持 7 个字面量:
     * {@code statusEquals / statusIn / bodyContains / bodyJsonPathEquals /
     * bodyJsonPathExists / responseTimeMsLt / headerEquals}。
     * LLM 照文案发 {@code type:"equals"} 会收到
     * {@code Unknown assertion type: equals}(实测),白费一轮重试。
     *
     * <p><b>归属的关键点</b>:这些 description 在本次修复之前对 MCP 客户端是
     * <b>不可见</b>的 —— 因为 MCP schema 生成器不读 {@code @ToolParam}。
     *也就是说"换注解"这个动作<em>恰恰是第一次把这段文案发布给 LLM</em>,
     *所以不能把它当作"与本次无关的继承问题"推迟。测试锁住它,防止再次漂移。
     */
    @Test
    @DisplayName("断言参数的 description 必须列出真实支持的 type,不含误导性的 {type,value}")
    void assertionDescriptionMatchesEngineSupportedTypes() {
        List<McpServerFeatures.SyncToolSpecification> specs = SyncMcpAnnotationProviders
                .toolSpecifications(beans.getBeansByAnnotation(McpTool.class));

        List<String> toolsWithAssertions = List.of("http_get", "http_post", "http_put", "http_delete",
                "invoke_endpoint");
        for (String toolName : toolsWithAssertions) {
            String schema = specs.stream()
                    .filter(s -> toolName.equals(s.tool().name()))
                    .findFirst().orElseThrow()
                    .tool().inputSchema().toString();

            // schema 的 toString 形态:assertions={type=array, items={...}, description=...}
            // description 一直延伸到下一个 "key=" 或串尾。用 reluctant 匹配,避免吃到后面字段。
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("description=断言列表.*?(?=, [a-zA-Z]+=|$)").matcher(schema);
            assertThat(m.find())
                    .as("%s 的 assertions 参数应有 description", toolName)
                    .isTrue();

            String desc = m.group();
            // 7 种支持的类型必须都写出来
            for (String type : List.of("statusEquals", "statusIn", "bodyContains",
                    "bodyJsonPathEquals", "bodyJsonPathExists", "responseTimeMsLt", "headerEquals")) {
                assertThat(desc)
                        .as("%s 的 assertions description 必须提到真实支持的 type %s%n实际=%s",
                                toolName, type, desc)
                        .contains(type);
            }
            // 不能再宣称可以用任意 type
            assertThat(desc)
                    .as("%s 的 description 不应再只写 {type,value} 而不列出合法 type%n实际=%s", toolName, desc)
                    .doesNotContain("断言列表 {type, value}。");
        }
    }

    /**
     * 从 schema 的 {@code toString()} 里抽出 {@code required=[a, b, c]} 的字段列表。
     *
     * <p>注意 {@code McpSchema.Tool.inputSchema()} 是 {@code Map<String,Object>},
     * {@code toString()} 出来是 <b>Java Map 形态</b>({@code required=[url, headers]})
     * 而非 JSON 形态({@code "required":["url"]})—— 断言必须按实际形态写,
     * 否则会写出永远为真的假断言。
     */
    private static List<String> requiredFields(String schema) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("required=\\[([^\\]]*)\\]").matcher(schema);
        if (!m.find()) return List.of();
        String body = m.group(1).trim();
        if (body.isEmpty()) return List.of();
        return java.util.Arrays.stream(body.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private List<java.lang.reflect.Method> allToolMethods() {
        List<java.lang.reflect.Method> out = new java.util.ArrayList<>();
        for (Class<?> c : List.of(LoomHttpMcpService.class, LoomHttpService.class)) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getAnnotation(McpTool.class) != null) out.add(m);
            }
        }
        return out;
    }

    /** {@code invoke_endpoint} → {@code invokeEndpoint};已是驼峰的返回原值。 */
    private static String toCamel(String snake) {
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
