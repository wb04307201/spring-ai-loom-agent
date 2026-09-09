package cn.wubo.spring.ai.loom.agent.tool.render;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IHtmlRenderTool RBAC + 注解落位契约(spec 2026-09-09 D3 + Global Constraints)。
 * 元数据单一真源 = 接口上的 @ToolGroup;@Tool/@ToolParam 必须在实现类
 * (Spring AI 反射不继承接口注解 —— askUser 复验教训)。
 */
class HtmlRenderToolContractTest {

    @Test
    @DisplayName("@ToolGroup(render) 在接口上,defaultGranted=false(RBAC 工具,重副作用必须显式授权)")
    void toolGroupIsRenderAndNotDefaultGranted() {
        ToolGroup ann = IHtmlRenderTool.class.getAnnotation(ToolGroup.class);
        assertThat(ann).as("IHtmlRenderTool 必须声明 @ToolGroup").isNotNull();
        assertThat(ann.value()).isEqualTo("render");
        assertThat(ann.defaultGranted()).isFalse();
        assertThat(ann.description()).contains("renderHtmlFile");
    }

    @Test
    @DisplayName("接口 extends IEmbedTool(DefaultChat List<IEmbedTool> 自动收集)")
    void extendsIEmbedTool() {
        assertThat(IEmbedTool.class.isAssignableFrom(IHtmlRenderTool.class)).isTrue();
    }

    @Test
    @DisplayName("实现类方法带 @Tool + 4 个 @ToolParam(htmlFilePath 必填,其余 optional),ToolContext 无注解")
    void implCarriesToolAnnotations() throws Exception {
        Method m = DefaultHtmlRenderTool.class.getMethod("renderHtmlFile",
                String.class, String.class, String.class, Boolean.class, ToolContext.class);
        assertThat(m.isAnnotationPresent(Tool.class)).isTrue();
        assertThat(m.getAnnotation(Tool.class).description()).contains("PNG");

        java.lang.annotation.Annotation[][] pa = m.getParameterAnnotations();
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            ToolParam tp = (ToolParam) Arrays.stream(pa[i])
                    .filter(a -> a instanceof ToolParam)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("参数 " + idx + " 缺 @ToolParam"));
            assertThat(tp.required()).as("参数 %d 的 required", i).isEqualTo(i == 0);
            assertThat(tp.description()).isNotBlank();
        }
        assertThat(Arrays.stream(pa[4]).anyMatch(a -> a instanceof ToolParam)).isFalse();
    }

    @Test
    @DisplayName("接口方法不带 @Tool(注解归实现类)")
    void interfaceHasNoToolAnnotation() throws Exception {
        Method m = IHtmlRenderTool.class.getMethod("renderHtmlFile",
                String.class, String.class, String.class, Boolean.class, ToolContext.class);
        assertThat(m.isAnnotationPresent(Tool.class)).isFalse();
    }
}
