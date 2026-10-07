package cn.wubo.spring.ai.loom.agent.tool.http;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolCallbacks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 {@code IHttpManageTool} 的"零 {@code @Tool} 方法是安全的"这一契约。
 *
 * <p><b>为什么需要这个测试</b>:{@code IHttpManageTool extends IEmbedTool}(见接口
 * 第 41 行),而 {@code DefaultChat} 把整个 {@code List<IEmbedTool>} 交给
 * {@code ToolCallbacks.from(...)}(DefaultChat.java:168)。也就是说这个"纯授权标记"
 * bean <b>确实会进</b>反射扫描路径 —— 它不进 LLM 只是因为它一个 {@code @Tool} 方法都没有。
 * 这个安全性此前只写在 javadoc 里,没有任何测试兜底:如果哪天 Spring AI 的
 * {@code ToolCallbacks.from} 改成对"零方法对象"抛异常(或把它当成可调用工具注册),
 * 整个聊天链路会在启动/首帧时炸掉,而编译期与 RBAC 测试都发现不了。
 *
 * <p>用<b>内联 stub</b>而不是真实工具实现,是为了让本测试零 Spring 上下文依赖 ——
 * 它要验证的是纯反射行为,不需要容器。
 */
class HttpManageToolInertnessTest {

    /** 对照组:一个带 {@code @Tool} 方法的普通 embed tool,用来证明过滤器本身是通的。 */
    static class StubRealTool implements IEmbedTool {
        @Tool(description = "对照组工具")
        public String ping() {
            return "pong";
        }
    }

    @Test
    @DisplayName("零 @Tool 的 http_manage 标记 bean 与真实工具混装时,ToolCallbacks.from 不抛异常")
    void zeroToolMethodBeanDoesNotBreakCallbackAssembly() {
        Object[] tools = { new DefaultHttpManageTool(), new StubRealTool() };

        ToolCallback[] callbacks = assertDoesNotThrow(
                () -> ToolCallbacks.from(tools),
                "零 @Tool 方法的 IEmbedTool 混在工具数组里时,Spring AI 不得抛异常");

        assertTrue(callbacks != null, "回调数组不得为 null");
    }

    @Test
    @DisplayName("零 @Tool 的 http_manage 标记 bean 不向 LLM 贡献任何回调")
    void zeroToolMethodBeanContributesNoCallbacks() {
        long fromMarker = ToolCallbacks.from(new DefaultHttpManageTool()).length;
        long fromRealTool = ToolCallbacks.from(new StubRealTool()).length;

        assertEquals(0, fromMarker,
                "http_manage 是纯授权标记,不得产生任何可调用 tool callback");
        assertTrue(fromRealTool > 0,
                "对照组必须有回调 —— 否则上面那个 0 只是因为过滤器整体失效,测试无意义");
    }

    @Test
    @DisplayName("混装后回调只来自真实工具,标记 bean 不污染工具名集合")
    void markerBeanDoesNotPolluteToolNames() {
        ToolCallback[] mixed = ToolCallbacks.from(new DefaultHttpManageTool(), new StubRealTool());
        List<String> names = Arrays.stream(mixed).map(ToolCallback::getToolDefinition)
                .map(td -> td.name()).toList();

        assertTrue(names.contains("ping"), "真实工具的回调必须在场,实际=" + names);
        assertTrue(names.stream().noneMatch(n -> n.contains("http_manage") || n.contains("HttpManage")),
                "标记 bean 不得出现在 LLM 可见的工具名里,实际=" + names);
    }

    /**
     * 自定义元注解 —— 用 Spring 的方式"间接"标注 {@code @Tool}。
     *
     * <p>{@code @Tool} 本身是 {@code @Target(METHOD)} 且支持元注解用法,Spring AI 的
     * {@code MethodToolCallbackProvider} 通过 {@code AnnotationUtils.findAnnotation} 识别,
     * 因此这样的方法<b>上游认得、会正常注册</b>。
     */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
    @Tool(description = "间接标注的工具")
    @interface MetaTool {
    }

    static class StubMetaAnnotatedTool implements IEmbedTool {
        @MetaTool
        public String metaPong() {
            return "meta-pong";
        }
    }

    @Test
    @DisplayName("@Tool 经自定义元注解间接标注的工具不得被过滤器静默丢弃")
    void metaAnnotatedToolIsNotSilentlyDropped() {
        // 缺陷来源:deep audit aud_6fZ4zETVSf(primary f3 / google f2 / feat f1)。
        // 过滤谓词原本用 Method.getAnnotation(Tool.class) —— 只认"直接标注",
        // 而上游 MethodToolCallbackProvider 用 AnnotationUtils.findAnnotation,
        // **认元注解**。谓词比上游窄 ⇒ 这种工具上游会注册,却被本过滤器静默吞掉
        // (能力消失,且无任何异常信号)。本仓当前没有这种工具,所以是潜在缺陷,
        // 但过滤器唯一的职责就是"预测上游会不会抛",必须与上游判据同源。
        ToolCallback[] callbacks = ToolCallbacks.from(new StubMetaAnnotatedTool());

        List<String> names = Arrays.stream(callbacks).map(ToolCallback::getToolDefinition)
                .map(td -> td.name()).toList();
        assertTrue(names.contains("metaPong"),
                "元注解标注的 @Tool 方法上游会注册,过滤器不得比上游更窄 —— 实际=" + names);
    }

    @Test
    @DisplayName("元注解工具与零工具标记 bean 混装时同样不丢")
    void metaAnnotatedToolSurvivesMixedAssembly() {
        ToolCallback[] mixed = ToolCallbacks.from(
                new DefaultHttpManageTool(), new StubMetaAnnotatedTool());

        List<String> names = Arrays.stream(mixed).map(ToolCallback::getToolDefinition)
                .map(td -> td.name()).toList();
        assertTrue(names.contains("metaPong"), "混装不得改变元注解工具的可见性,实际=" + names);
    }
}
