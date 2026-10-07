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

    /** 读任意 classpath 资源（供跨文件断言用）。 */
    private String source(String path) throws IOException {
        try (var in = getClass().getResourceAsStream(path)) {
            assertThat(in).as("%s 必须存在", path).isNotNull();
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
    @DisplayName("result 外层引号必须剥离 —— 否则选中项不高亮")
    void outerQuotesAreStrippedForAnswerMatching() throws IOException {
        String src = rendererSource();
        assertThat(src)
                .as("必须有 stripOuterQuotes 处理 —— loom_tool_call_log.result_text 可能带外层引号")
                .contains("stripOuterQuotes");
        assertThat(src)
                .as("answer 抽取后必须再剥一次残留引号")
                .contains(".replace(/^[\"']|[\"']$/g, \"\")");
        assertThat(src)
                .as("选中项必须加 askuser-opt-chosen 类才能高亮")
                .contains("askuser-opt-chosen");
    }

    @Test
    @DisplayName("spinner 旋转只在运行中 —— 结束后不得继续转")
    void spinnerAnimationOnlyWhileRunning() throws IOException {
        String css = source("/META-INF/resources/spring/ai/loom/style.css");

        // 动画必须限定在 .subtask-chip-running 后代上。
        // 此前是无条件的 —— renderEnd 把 ⏳ 换成 ✓ 后动画仍在跑，
        // 表现是「执行完了还一直转」；历史 chip 的静态 ✅ 同样在转。
        assertThat(css)
                .as("旋转动画必须限定在 running 状态")
                .contains(".subtask-chip-running .subtask-chip-spinner { animation: spin");
        // 不得有**行首**就是 .subtask-chip-spinner 的动画规则（无条件的）
        // —— 注意不能用 doesNotContain(".subtask-chip-spinner { animation")，
        // 那会误伤上面那条带 .subtask-chip-running 前缀的正确规则。
        boolean hasUnconditional = css.lines()
                .map(String::trim)
                .anyMatch(l -> l.startsWith(".subtask-chip-spinner {") && l.contains("animation"));
        assertThat(hasUnconditional)
                .as("不得存在无条件的 spinner 动画规则（那会让结束态也一直转）")
                .isFalse();

        // 实时渲染结束时必须移除 running 类，动画才会停
        String appJs = source("/META-INF/resources/spring/ai/loom/app.js");
        int end = appJs.indexOf("function renderEnd(ev)");
        assertThat(end).as("应能定位 renderEnd").isGreaterThan(-1);
        String body = appJs.substring(end, Math.min(end + 500, appJs.length()));
        assertThat(body)
                .as("renderEnd 必须移除 subtask-chip-running —— 否则 CSS 动画不停")
                .contains("classList.remove(\"subtask-chip-running\")");

        // 历史 chip 不带 running 类（静态图标）
        assertThat(rendererSource())
                .as("历史 chip 不应带 running 类")
                .doesNotContain("subtask-chip-running");
    }

    @Test
    @DisplayName("renderMessages 必须重置 _lastBotBubble —— 否则工具调用挂到游离节点")
    void resetsLastBotBubbleOnRender() throws IOException {
        String appJs = source("/META-INF/resources/spring/ai/loom/app.js");
        int start = appJs.indexOf("renderMessages(messages) {");
        assertThat(start).as("应能定位 renderMessages").isGreaterThan(-1);
        String body = appJs.substring(start, Math.min(start + 900, appJs.length()));

        assertThat(body)
                .as("clearChat() 后必须重置 _lastBotBubble —— 它仍指向已被移除的旧气泡，"
                        + "后续 TOOL_CALL 会 append 到看不见的游离节点上（静默丢失，无 console 报错）")
                .contains("this._lastBotBubble = null");
    }

    @Test
    @DisplayName("截断必须提示 —— 否则用户以为工具只返回了这么多")
    void truncationIsSurfaced() throws IOException {
        assertThat(rendererSource())
                .contains("tool-call-truncated")
                .contains("已截断");
    }

    @Test
    @DisplayName("历史工具调用挂在 AI 气泡内部，不是独立气泡")
    void toolCallsRenderInsideBotBubble() throws IOException {
        String appJs = source("/META-INF/resources/spring/ai/loom/app.js");
        int start = appJs.indexOf("} else if (role === \"TOOL_CALL\") {");
        assertThat(start).as("应能定位 TOOL_CALL 分支").isGreaterThan(-1);
        String branch = appJs.substring(start, Math.min(start + 1200, appJs.length()));

        assertThat(branch)
                .as("必须挂进上一个 AI 气泡（_lastBotBubble），而非新建 chat-item —— "
                        + "独立气泡会把一次 AI 回复拆成两段")
                .contains("this._lastBotBubble");
        assertThat(branch)
                .as("应插在 .bubble-actions 之前（即正文之后、操作按钮之前）")
                .contains("insertBefore(node, actions)");
        assertThat(branch)
                .as("不应再新建 chat-item（旧实现）")
                .doesNotContain("chat-item chat-item-left tool-call-item");
        assertThat(branch)
                .as("不应再 append 到 mainContent（那会变成独立气泡）")
                .doesNotContain("this.mainContent.appendChild(item)");

        assertThat(appJs)
                .as("renderBotMessage 必须记录最近的气泡供 TOOL_CALL 挂载")
                .contains("this._lastBotBubble = item.querySelector(\".bubble\")");

        String css = source("/META-INF/resources/spring/ai/loom/style.css");
        assertThat(css)
                .as("内联容器样式必须存在")
                .contains(".tool-call-inline");
    }

    @Test
    @DisplayName("子任务状态在主界面无 SUBTASK 事件时从 result 前缀推断")
    void subtaskStatusInferredWhenNoEvent() throws IOException {
        String js = source("/META-INF/resources/spring/ai/loom/tool-call-renderer.js");
        assertThat(js)
                .as("必须有状态推断函数 —— 主聊天界面的历史接口不返回 SUBTASK 事件，"
                        + "只认 st.status 会把已完成的子任务标成 ⚠️ 失败")
                .contains("function resolveSubtaskStatus");
        // 前缀由 DefaultSubTaskTool 定义，逐字核对
        assertThat(js)
                .as("必须识别 [子任务已完成")
                .contains("/\\[子任务已完成/");
        assertThat(js)
                .as("必须识别失败与取消")
                .contains("[子任务失败")
                .contains("[子任务已取消");
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
