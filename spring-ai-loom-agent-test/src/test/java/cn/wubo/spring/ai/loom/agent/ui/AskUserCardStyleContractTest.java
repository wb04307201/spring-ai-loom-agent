package cn.wubo.spring.ai.loom.agent.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 样式契约测试(#1 AskUser 全面测试 Phase B/B1):
 * askUser 卡片 CSS 必须消费项目真实设计 token(:root 里定义的 --primary-color 等),
 * 不得使用未定义变量名靠 fallback 字面值掩盖 —— 主题化/换肤时 fallback 不会跟随 token。
 *
 * <p>背景:2026-09-08 全面测试发现卡片块 3 处写了 {@code var(--primary, #6366f1)},
 * 而 style.css :root 定义的是 {@code --primary-color}。当前渲染恰好同色(fallback
 * 与 token 值一致)纯属侥幸。
 */
class AskUserCardStyleContractTest {

    private String readStyleCss() throws IOException {
        try (var in = getClass().getResourceAsStream(
                "/META-INF/resources/spring/ai/loom/style.css")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void noUndefinedPrimaryTokenAnywhere() throws IOException {
        // "var(--primary," = 引用了不存在的 --primary 变量(真实 token 是 --primary-color)。
        // 注意与合法引用 "var(--primary-color," 区分:后者在 --primary 后还有 "-color"。
        assertThat(readStyleCss()).doesNotContain("var(--primary,");
        assertThat(readStyleCss()).doesNotContain("var(--primary)");
    }

    @Test
    void askUserCardBlockUsesRealDesignTokens() throws IOException {
        String css = readStyleCss();
        int idx = css.indexOf("/* ===== #1 AskUser 提问卡片 ===== */");
        assertThat(idx).as("askUser card CSS block must exist").isGreaterThan(0);
        String block = css.substring(idx);
        assertThat(block).contains("var(--primary-color, #6366f1)");
        assertThat(block).contains("var(--border-color");
        assertThat(block).contains("var(--text-secondary");
        assertThat(block).contains("var(--success-color");
    }
}
