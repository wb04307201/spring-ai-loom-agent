package cn.wubo.spring.ai.loom.agent.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §1 卡片折叠契约(spec 2026-09-08-askuser-followups-design.md):
 * askUser 卡片终态必须折叠成一行摘要(点击展开回看),而不是整卡常驻对话流。
 * 镜像 AskUserCardStyleContractTest 的静态读文件断言风格。
 */
class AskUserCardCollapseContractTest {

    private String read(String resource) throws IOException {
        try (var in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("resource must exist: " + resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String appJs() throws IOException {
        return read("/META-INF/resources/spring/ai/loom/app.js");
    }

    private String styleCss() throws IOException {
        return read("/META-INF/resources/spring/ai/loom/style.css");
    }

    @Test
    void freezeTakesAnswerTextParam() throws IOException {
        // 4 参签名:已答路径传用户答案进摘要行;其余终态传 null/省略
        assertThat(appJs()).contains("function freeze(qid, stateText, ok, answerText)");
    }

    @Test
    void summaryDomStructureExists() throws IOException {
        String js = appJs();
        assertThat(js).contains("askuser-wrap");
        assertThat(js).contains("askuser-summary");
        // 提交成功路径必须把答案传进 freeze(摘要行显示 问题 → 答案)
        assertThat(js).contains("freeze(qid, \"已答 ✓\", true,");
    }

    @Test
    void summaryCssExistsAndUsesRealTokens() throws IOException {
        String css = styleCss();
        int idx = css.indexOf("/* ===== #1 AskUser 提问卡片 ===== */");
        assertThat(idx).isGreaterThan(0);
        String block = css.substring(idx);
        assertThat(block).contains(".askuser-summary {");
        assertThat(block).contains("text-overflow: ellipsis");
        // 真实 token(不得再出现未定义 --primary;既有 AskUserCardStyleContractTest 全文锁死,
        // 这里锁摘要行用了 success/secondary/border token)
        assertThat(block).contains("var(--success-color");
        assertThat(block).doesNotContain("var(--primary,");
    }
}
