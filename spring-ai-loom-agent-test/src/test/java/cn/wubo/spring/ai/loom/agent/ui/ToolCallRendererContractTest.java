package cn.wubo.spring.ai.loom.agent.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具调用渲染器的契约锁。
 *
 * <p>前端渲染逻辑无法在 Java 单测里真跑，故用静态断言锁住**关键契约点** ——
 * 这些都是"改坏了会导致功能静默失效"的地方：
 * <ul>
 *   <li>三种 toolName 的分派（askUser / startSubTask / 其他）</li>
 *   <li>XSS 转义必须覆盖用户可控字段</li>
 *   <li>历史卡片必须只读（disabled）—— 防止历史里误提交</li>
 *   <li>截断提示必须出现 —— 否则用户以为工具只返回了那么多</li>
 * </ul>
 */
@DisplayName("工具调用渲染器契约")
class ToolCallRendererContractTest {

    private String rendererSource() throws IOException {
        try (var in = getClass().getResourceAsStream(
                "/META-INF/resources/spring/ai/loom/tool-call-renderer.js")) {
            assertThat(in).as("tool-call-renderer.js 必须存在于静态资源").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("按 toolName 分派到三种渲染器")
    void dispatchesByToolName() throws IOException {
        String src = rendererSource();
        // 断言**分派语句本身**，而不只是函数存在 —— 只断言 renderAskUserCard 出现，
        // 把 return renderAskUserCard(entry) 改成 return renderGenericToolCall(entry)
        // 时用例不会变红（本用例第一版就踩了这个坑，变异测试抓出来的）。
        assertThat(src)
                .as("askUser 必须分派到卡片渲染器")
                .contains("if (name === \"askUser\") return renderAskUserCard(entry);");
        assertThat(src)
                .as("startSubTask 必须分派到 chip 渲染器")
                .contains("return renderSubtaskChip(entry, subtaskEvent);");
        assertThat(src)
                .as("其余工具必须分派到折叠行渲染器")
                .contains("return renderGenericToolCall(entry);");
        assertThat(src)
                .as("分派逻辑必须同时认 startSubTask 与 start_sub_task 两种写法")
                .contains("name === \"startSubTask\" || name === \"start_sub_task\"");
    }

    @Test
    @DisplayName("历史卡片只读 —— 选项 disabled，不可提交")
    void historyCardIsReadOnly() throws IOException {
        String src = rendererSource();
        assertThat(src)
                .as("历史 askUser 卡片的选项必须 disabled —— 否则用户能在历史里重新提交")
                .contains("${chosen ? \"checked\" : \"\"} disabled/>");
        assertThat(src)
                .as("必须带 askuser-frozen 类（复用实时卡片的只读修饰符）")
                .contains("askuser-card askuser-frozen");
    }

    @Test
    @DisplayName("用户可控字段必须转义（XSS 防护）")
    void userControlledFieldsAreEscaped() throws IOException {
        String src = rendererSource();
        // 这些字段来自 LLM 输出或用户输入，直接拼进 innerHTML 会造成 XSS。
        // 用字段名（不带 esc() 包裹）断言 —— 实现里可能写成 esc(x || "") 或 esc(String(x))，
        // 断言具体调用形式会很脆；断言"该字段出现在 esc(...) 调用内"更稳。
        for (String field : new String[]{
                "args.question", "args.header", "args.background",
                "opt.label", "opt.description", "error"}) {
            assertThat(src)
                    .as("字段 %s 必须经 esc() 转义", field)
                    .contains("esc(" + field);
        }
        // result / prompt 在实现里先转成 String 再转义，单独断言
        assertThat(src)
                .as("result 必须经 esc() 转义")
                .contains("esc(result)");
        assertThat(src)
                .as("prompt 必须经 esc() 转义")
                .contains("esc(prompt)");

        assertThat(src)
                .as("esc 必须转义尖括号与引号")
                .contains(".replace(/</g, \"&lt;\")")
                .contains(".replace(/>/g, \"&gt;\")")
                .contains(".replace(/\"/g, \"&quot;\")");
    }

    @Test
    @DisplayName("截断必须提示 —— 否则用户以为工具只返回了这么多")
    void truncationIsSurfaced() throws IOException {
        assertThat(rendererSource())
                .contains("tool-call-truncated")
                .contains("已截断");
    }

    @Test
    @DisplayName("optionsJson 解析失败时降级为空数组，不抛异常")
    void optionsParseFailureDegradesGracefully() throws IOException {
        String src = rendererSource();
        assertThat(src)
                .as("parseOptions 必须有 try/catch，解析失败返回空数组")
                .contains("catch (_) {\n      return [];");
    }

    @Test
    @DisplayName("两处渲染入口都引用同一个渲染器（避免重复实现）")
    void bothEntryPointsUseSharedRenderer() throws IOException {
        for (String path : new String[]{
                "/META-INF/resources/spring/ai/loom/app.js",
                "/META-INF/resources/spring/ai/loom/admin/conversation.js"}) {
            try (var in = getClass().getResourceAsStream(path)) {
                assertThat(in).as("%s 必须存在", path).isNotNull();
                String src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(src)
                        .as("%s 必须调用共享渲染器 ToolCallRenderer.render", path)
                        .contains("ToolCallRenderer.render");
            }
        }
    }

    @Test
    @DisplayName("两个页面都必须引入渲染器脚本")
    void bothPagesLoadRendererScript() throws IOException {
        for (String path : new String[]{
                "/META-INF/resources/spring/ai/loom/index.html",
                "/META-INF/resources/spring/ai/loom/admin/conversation.html"}) {
            try (var in = getClass().getResourceAsStream(path)) {
                assertThat(in).as("%s 必须存在", path).isNotNull();
                String src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(src)
                        .as("%s 必须 <script> 引入 tool-call-renderer.js", path)
                        .contains("tool-call-renderer.js");
            }
        }
    }
}
