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
    @DisplayName("弹窗不含「当前用户」行（用户已确认不需要）")
    void noCurrentUserLine() throws IOException {
        String js = source("/META-INF/resources/spring/ai/loom/app.js");
        int start = js.indexOf("showAboutModal() {");
        int end = js.indexOf("async showUsageModal()", start);
        String body = js.substring(start, end);
        assertThat(body)
                .as("按用户要求移除「当前用户」行 —— 保留会在弹窗里显得冗余")
                .doesNotContain("当前用户");
    }

    @Test
    @DisplayName("含 Gitee 与 GitHub 两个仓库链接")
    void hasRepositoryLinks() throws IOException {
        String js = source("/META-INF/resources/spring/ai/loom/app.js");
        assertThat(js)
                .as("Gitee 仓库链接必须存在")
                .contains("https://gitee.com/wb04307201/spring-ai-loom-agent");
        assertThat(js)
                .as("GitHub 仓库链接必须存在")
                .contains("https://github.com/wb04307201/spring-ai-loom-agent");
        assertThat(js)
                .as("两个链接都要有可读 label")
                .contains("label: \"Gitee\"")
                .contains("label: \"GitHub\"");
    }

    @Test
    @DisplayName("产品介绍已写入（不再是占位符）")
    void descriptionIsRealContent() throws IOException {
        String js = source("/META-INF/resources/spring/ai/loom/app.js");
        assertThat(js)
                .as("占位文案必须已被真实产品介绍替换")
                .doesNotContain("在这里写产品介绍")
                .doesNotContain("请替换为你自己的信息");
        assertThat(js)
                .as("产品介绍应说明这是什么（Spring Boot 自动配置库）")
                .contains("Spring Boot 自动配置库");
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
                .contains("escapeHtml(ABOUT_INFO.stack)")
                .contains("escapeHtml(ABOUT_INFO.description)");
        assertThat(body)
                .as("版本（来自后端或常量）也必须转义 —— 后端值同样不可信")
                .contains("escapeHtml(version)");
        assertThat(body)
                .as("链接的 url 与 label 都必须转义")
                .contains("escapeHtml(l.url)")
                .contains("escapeHtml(l.label)");
        assertThat(body)
                .as("外链必须带 rel=noopener（防 tabnabbing）")
                .contains("rel=\"noopener noreferrer\"");
    }

    @Test
    @DisplayName("版本从后端读（不再硬编码），且有降级链")
    void versionComesFromBackend() throws IOException {
        String js = source("/META-INF/resources/spring/ai/loom/app.js");

        // ABOUT_INFO.version 必须为空（空 = 用后端值）
        Matcher m = Pattern.compile("version:\\s*\"([^\"]*)\"").matcher(js);
        assertThat(m.find()).as("应能提取 ABOUT_INFO.version").isTrue();
        assertThat(m.group(1))
                .as("ABOUT_INFO.version 必须留空 —— 填了就会覆盖后端读到的构建版本，"
                        + "版本号又变回硬编码")
                .isEmpty();

        // 降级链：手动覆盖 > 后端 features.version > 未知
        assertThat(js)
                .as("必须优先用后端 features.version")
                .contains("state.features && state.features.version");
        assertThat(js)
                .as("读不到时应有「未知」占位，而非空白")
                .contains("\"未知\"");
        assertThat(js)
                .as("渲染处必须用解析后的 version 变量，而非 ABOUT_INFO.version")
                .contains("escapeHtml(version)");
    }

    @Test
    @DisplayName("测试不得依赖 docs/API.md 的版本标注（版本真源已改为 pom）")
    void noLongerDependsOnApiDocVersion() throws IOException {
        String js = source("/META-INF/resources/spring/ai/loom/app.js");
        assertThat(js)
                .as("版本真源已改为构建产物（pom），前端不应再与 docs 的人工标注绑定")
                .doesNotContain("与 docs/API.md 保持一致");
    }
}
