package cn.wubo.spring.ai.loom.agent.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「关于」弹窗的前端契约锁。
 *
 * <p>锁住三件事：
 * <ol>
 *   <li>菜单项存在且绑定到 {@code showAboutModal}</li>
 *   <li>自定义文案集中在 {@code ABOUT_INFO} 常量 —— 改内容不必动渲染逻辑</li>
 *   <li>{@code ABOUT_INFO.version} 与 {@code docs/API.md} 顶部的版本标注一致
 *       （版本号没有后端来源，是人工维护的；两处漂移过一次就难发现）</li>
 * </ol>
 */
@DisplayName("「关于」弹窗前端契约")
class AboutModalContractTest {

    private String source(String path) throws IOException {
        try (var in = getClass().getResourceAsStream(path)) {
            assertThat(in).as("%s 必须存在", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("用户菜单有「关于」项并绑定到 showAboutModal")
    void menuItemExistsAndIsBound() throws IOException {
        String js = source("/META-INF/resources/spring/ai/loom/app.js");
        assertThat(js)
                .as("菜单项必须存在")
                .contains("id=\"user-menu-about\"")
                .contains(">关于<");
        assertThat(js)
                .as("必须绑定点击到 showAboutModal")
                .contains("getElementById(\"user-menu-about\")")
                .contains("this.showAboutModal()");
        assertThat(js)
                .as("渲染方法必须存在")
                .contains("showAboutModal() {");
    }

    @Test
    @DisplayName("自定义文案集中在 ABOUT_INFO 常量（改内容不动渲染逻辑）")
    void contentIsCentralizedInAboutInfo() throws IOException {
        String js = source("/META-INF/resources/spring/ai/loom/app.js");
        assertThat(js)
                .as("必须有 ABOUT_INFO 常量")
                .contains("const ABOUT_INFO = {");
        for (String field : new String[]{"name:", "tagline:", "version:", "stack:", "description:", "links:"}) {
            assertThat(js)
                    .as("ABOUT_INFO 必须含 %s 字段", field)
                    .contains(field);
        }
    }

    @Test
    @DisplayName("当前用户渲染用 state.userType 而非不存在的 state.isAdmin")
    void usesExistingStateField() throws IOException {
        String js = source("/META-INF/resources/spring/ai/loom/app.js");
        assertThat(js)
                .as("state 里是 userType，没有 isAdmin —— 用错会静默显示为空")
                .contains("state.userType === \"ADMIN\"");
        // state 定义里不得出现 isAdmin 字段
        int stateStart = js.indexOf("const state = {");
        assertThat(stateStart).isGreaterThan(-1);
        String stateBlock = js.substring(stateStart, Math.min(stateStart + 900, js.length()));
        assertThat(stateBlock)
                .as("state 定义中不应有 isAdmin 字段（实际是 userType）")
                .doesNotContain("isAdmin:");
    }

    @Test
    @DisplayName("所有用户可控字段经 escapeHtml 转义（XSS 防护）")
    void contentIsEscaped() throws IOException {
        String js = source("/META-INF/resources/spring/ai/loom/app.js");
        // 取 showAboutModal 方法体做局部断言
        int start = js.indexOf("showAboutModal() {");
        assertThat(start).isGreaterThan(-1);
        int end = js.indexOf("async showUsageModal()", start);
        assertThat(end).as("应能定位方法体结束").isGreaterThan(start);
        String body = js.substring(start, end);

        assertThat(body)
                .as("ABOUT_INFO 的文案字段必须转义")
                .contains("escapeHtml(ABOUT_INFO.name)")
                .contains("escapeHtml(ABOUT_INFO.tagline)")
                .contains("escapeHtml(ABOUT_INFO.version)")
                .contains("escapeHtml(ABOUT_INFO.stack)")
                .contains("escapeHtml(ABOUT_INFO.description)");
        assertThat(body)
                .as("链接的 url 与 label 都必须转义")
                .contains("escapeHtml(l.url)")
                .contains("escapeHtml(l.label)");
        assertThat(body)
                .as("外链必须带 rel=noopener（防 tabnabbing）")
                .contains("rel=\"noopener noreferrer\"");
    }

    @Test
    @DisplayName("ABOUT_INFO.version 与 docs/API.md 的版本标注一致")
    void versionMatchesApiDoc() throws IOException {
        String js = source("/META-INF/resources/spring/ai/loom/app.js");
        Matcher m = Pattern.compile("version:\\s*\"([^\"]+)\"").matcher(js);
        assertThat(m.find()).as("应能从 ABOUT_INFO 提取 version").isTrue();
        String jsVersion = m.group(1);

        // docs/ 不在测试 classpath 上，走文件系统。测试 cwd = spring-ai-loom-agent-test
        java.nio.file.Path apiDocPath = java.nio.file.Paths.get("..", "docs", "API.md");
        assertThat(apiDocPath)
                .as("docs/API.md 必须存在（相对 test 模块的 cwd）")
                .exists();
        String apiDoc = java.nio.file.Files.readString(apiDocPath, StandardCharsets.UTF_8);

        Matcher m2 = Pattern.compile("^> \\*\\*Version\\*\\*: (.+)$", Pattern.MULTILINE).matcher(apiDoc);
        assertThat(m2.find()).as("docs/API.md 顶部应有版本标注").isTrue();
        String docVersion = m2.group(1).trim();

        assertThat(jsVersion)
                .as("版本号没有后端来源，是人工维护的两处 —— 漂移了很难发现，故用测试锁住")
                .isEqualTo(docVersion);
    }
}
