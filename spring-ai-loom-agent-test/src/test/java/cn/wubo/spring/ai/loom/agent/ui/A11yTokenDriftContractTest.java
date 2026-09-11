package cn.wubo.spring.ai.loom.agent.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * a11y 色板 token 漂移守卫(2026-09-12 第五轮全面测试发现):
 * 2026-09-11 F-2(commit 91799f3)将全站文本/按钮主色为达 WCAG AA 4.5:1 做了批量替换
 * (#6366f1→#4f46e5、#94a3b8→#64748b、rgba(99,102,241→rgba(79,70,229 等),
 * 但 perl 映射漏掉 style.css L3971 一处 {@code rgba(99,102,241,.04)} —— 且同轮 F-2
 * 还漏改了单元契约 AskUserCardStyleContractTest(钉死旧 fallback hex),导致回归门 1 红。
 *
 * <p>本测试锁死"F-2 已废弃的旧色板 token 不得在任何前端资源中复现",防止未来再次漂移。
 * 纯文本扫描(无浏览器、无 Spring context),事实源 = core lib classpath 静态资源。
 * 旧色板值(对比度不达标,已全量退役):
 * <ul>
 *   <li>{@code #6366f1} — 旧主色(3.94:1,不达标)→ 新 #4f46e5(6.0:1)</li>
 *   <li>{@code #94a3b8} — 旧弱化灰(2.45:1)→ 新 #64748b(4.76:1)</li>
 *   <li>{@code rgba(99,102,241} — 旧主色 alpha 形式 → 新 rgba(79,70,229</li>
 *   <li>{@code rgba(148,163,184} — 旧弱化灰 alpha 形式 → 新 rgba(100,116,139</li>
 * </ul>
 */
@DisplayName("a11y:旧色板 token 不得在任何前端资源中复现(F-2 退役值漂移守卫)")
class A11yTokenDriftContractTest {

    private static final String ROOT = "/META-INF/resources/spring/ai/loom/";

    private static final String[] DEPRECATED_TOKENS = {
            "#6366f1",
            "#94a3b8",
            "rgba(99,102,241",
            "rgba(148,163,184",
    };

    private String read(String relPath) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(ROOT + relPath)) {
            assertThat(in).as(ROOT + relPath + " 在 classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @ParameterizedTest(name = "{0} 无废弃 token")
    @ValueSource(strings = {
            "style.css",
            "login.css",
            "login.html",
            "admin/console.css",
            "admin/stats.js",
    })
    void frontendResourceHasNoDeprecatedTokens(String relPath) throws IOException {
        String content = read(relPath).toLowerCase();
        for (String token : DEPRECATED_TOKENS) {
            assertThat(content)
                    .as("%s 不得含 F-2 已废弃 token %s(对比度不达 WCAG AA;主色现 #4f46e5 / 弱化灰 #64748b)",
                            relPath, token)
                    .doesNotContain(token.toLowerCase());
        }
    }

    @Test
    @DisplayName("主色新 token #4f46e5 在 style.css 中存在(F-2 替换确实生效)")
    void newPrimaryTokenPresent() throws IOException {
        assertThat(read("style.css").toLowerCase())
                .as("style.css 应含 F-2 新主色 #4f46e5")
                .contains("#4f46e5");
    }
}
