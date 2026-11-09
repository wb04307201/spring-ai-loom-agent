package cn.wubo.spring.ai.loom.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JsonLenient} 单元测试。
 *
 * <p><b>为什么需要</b>：Spring AI 2.x 用 Jackson 3，共享 {@code JsonMapper} 是<b>不可变</b>的，
 * 旧的"反射 enable ALLOW_COMMENTS"配置在 2.x 上从未生效（getObjectMapper 已移除 + Jackson3
 * 无 in-place enable + 枚举改名）。qwen 系模型工具调用 JSON 带 JS 注释会让
 * MethodToolCallback 解析直接抛 JsonParseException、整条工具链断。本类是替代方案：
 * 在<b>自有层</b>（LoggingToolCallback / askUser）解析前剥离注释，不依赖任何反射。
 *
 * <p>契约：只剥字符串字面量<b>之外</b>的 {@code //} 与 <code>/* *&#47;</code> 注释；
 * 字符串内的 {@code //}（如 URL）、转义引号、块注释里的引号都必须原样保留。
 */
@DisplayName("JsonLenient 工具调用 JSON 容错")
class JsonLenientTest {

    @Test
    @DisplayName("无注释 JSON 原样返回（no-op 不变性）")
    void cleanJsonUnchanged() {
        String json = "{\"path\":\"a/b.txt\",\"head\":10}";
        assertThat(JsonLenient.sanitize(json)).isEqualTo(json);
    }

    @Test
    @DisplayName("行注释 // 被剥离")
    void lineCommentStripped() {
        String in = "{\n  \"a\": 1, // trailing note\n  \"b\": 2\n}";
        String out = JsonLenient.sanitize(in);
        assertThat(out).doesNotContain("trailing note").contains("\"a\": 1,").contains("\"b\": 2");
    }

    @Test
    @DisplayName("块注释 /* */ 被剥离")
    void blockCommentStripped() {
        String in = "{ /* lead */ \"a\": 1, \"b\": /* mid */ 2 }";
        String out = JsonLenient.sanitize(in);
        assertThat(out).doesNotContain("lead").doesNotContain("mid").contains("\"a\": 1").contains("\"b\":  2");
    }

    @Test
    @DisplayName("字符串内的 // (URL) 不得被剥离")
    void urlInsideStringPreserved() {
        String in = "{\"url\":\"http://example.com/a?b=1\"} // real comment";
        String out = JsonLenient.sanitize(in);
        assertThat(out).contains("http://example.com/a?b=1").doesNotContain("real comment");
    }

    @Test
    @DisplayName("字符串内转义引号不破坏状态机")
    void escapedQuoteInString() {
        String in = "{\"s\":\"he said \\\"hi // not comment\\\"\"} // tail";
        String out = JsonLenient.sanitize(in);
        assertThat(out).contains("hi // not comment").doesNotContain("tail");
    }

    @Test
    @DisplayName("块注释内含引号不影响状态机")
    void quoteInsideBlockComment() {
        String in = "{ \"a\":1, /* \"not a string\" */ \"b\":2 }";
        String out = JsonLenient.sanitize(in);
        assertThat(out).doesNotContain("not a string").contains("\"b\":2");
    }

    @Test
    @DisplayName("null / 空串安全")
    void nullAndEmpty() {
        assertThat(JsonLenient.sanitize(null)).isNull();
        assertThat(JsonLenient.sanitize("")).isEqualTo("");
        assertThat(JsonLenient.sanitize("   ")).isEqualTo("   ");
    }
}
