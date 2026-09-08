package cn.wubo.spring.ai.loom.agent.askuser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §2 提问日志解析契约(spec 2026-09-08-askuser-followups-design.md):
 * status 从 result_text 前缀推导(前缀逐字对齐 DefaultAskUserTool 返回值),
 * question/header 从 arguments_json 解析,任何畸形输入不得抛异常。
 */
@DisplayName("askUser 日志解析")
class AskUserLogParsingTest {

    @Test
    void answeredPrefixMapsToAnswered() {
        assertThat(JdbcAskUserLogQuery.deriveStatus("[用户已回答] 选项A")).isEqualTo("ANSWERED");
    }

    @Test
    void timeoutPrefixMapsToTimeout() {
        assertThat(JdbcAskUserLogQuery.deriveStatus(
                "[用户未作答] 用户未在 5 分钟内作答。请基于现有信息自行合理决策并继续,或改用其他方式推进。"))
                .isEqualTo("TIMEOUT");
    }

    @Test
    void stopCancelledMapsToCancelled() {
        // 同为 [用户未作答] 前缀,靠正文"已停止"细分(spec §2.2 status 表)
        assertThat(JdbcAskUserLogQuery.deriveStatus("[用户未作答] 用户已停止本次对话,未作答。"))
                .isEqualTo("CANCELLED");
        assertThat(JdbcAskUserLogQuery.deriveStatus("[提问被中断] 等待用户作答时被中断,未获得答案。"))
                .isEqualTo("CANCELLED");
    }

    @Test
    void failedPrefixMapsToFailed() {
        assertThat(JdbcAskUserLogQuery.deriveStatus("[提问失败] question 不能为空,请提供要问用户的问题文本后重试。"))
                .isEqualTo("FAILED");
    }

    @Test
    void nullOrUnknownResultTextMapsToUnknown() {
        assertThat(JdbcAskUserLogQuery.deriveStatus(null)).isEqualTo("UNKNOWN");
        assertThat(JdbcAskUserLogQuery.deriveStatus("")).isEqualTo("UNKNOWN");
        assertThat(JdbcAskUserLogQuery.deriveStatus("随便什么")).isEqualTo("UNKNOWN");
    }

    @Test
    void answerTextExtractedAfterPrefix() {
        assertThat(JdbcAskUserLogQuery.extractAnswer("[用户已回答] 选项A；自定义答案B"))
                .isEqualTo("选项A；自定义答案B");
        assertThat(JdbcAskUserLogQuery.extractAnswer("[用户未作答] 用户已停止本次对话,未作答。"))
                .isNull();
        assertThat(JdbcAskUserLogQuery.extractAnswer(null)).isNull();
    }

    @Test
    void questionAndHeaderParsedFromArgumentsJson() {
        String json = "{\"question\":\"用哪种数据库?\",\"header\":\"数据库选型\","
                + "\"optionsJson\":\"[{\\\"label\\\":\\\"MySQL\\\"}]\",\"multiSelect\":false}";
        assertThat(JdbcAskUserLogQuery.parseQuestion(json)).isEqualTo("用哪种数据库?");
        assertThat(JdbcAskUserLogQuery.parseHeader(json)).isEqualTo("数据库选型");
    }

    @Test
    void malformedArgumentsJsonFallsBackWithoutThrowing() {
        assertThat(JdbcAskUserLogQuery.parseQuestion("not-json")).isEqualTo("(解析失败)");
        assertThat(JdbcAskUserLogQuery.parseQuestion(null)).isEqualTo("(解析失败)");
        assertThat(JdbcAskUserLogQuery.parseHeader("not-json")).isNull();
        // 合法 JSON 但缺字段 → 各自兜底
        assertThat(JdbcAskUserLogQuery.parseQuestion("{}")).isEqualTo("(解析失败)");
        assertThat(JdbcAskUserLogQuery.parseHeader("{\"question\":\"q\"}")).isNull();
    }
}
