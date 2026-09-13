package cn.wubo.spring.ai.loom.agent.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * i18n key 对齐纯单测(无浏览器、无 Spring context)。
 *
 * <p>事实源:core lib classpath {@code /META-INF/resources/spring/ai/loom/i18n/}
 * 下 {@code zh-CN.json} / {@code en-US.json}(各 28 个 market.* 平铺 key + `_meta` 对象;
 * 文件内 note 明示"新增 key 必须两文件同步追加")。test 模块经 starter→core lib
 * 依赖可 getResourceAsStream 读取。
 *
 * <p>断言:两文件平铺 key 集合完全相等 + 含抽样 key + zh-CN 无非空对象值缺失(无空白)。
 * `_meta` 为合法 key(zh/en 均有,递归展开为 `_meta.locale` 等),不影响对齐断言。
 */
@DisplayName("i18n:zh-CN / en-US key 集合一致 + 无空值")
class I18nKeysParityTest {

    private static final String BASE = "/META-INF/resources/spring/ai/loom/i18n/";

    private Set<String> flatKeys(String file) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(BASE + file)) {
            assertThat(in).as(BASE + file + " 在 classpath").isNotNull();
            JsonNode root = new ObjectMapper().readTree(in);
            Set<String> keys = new HashSet<>();
            collect("", root, keys);
            return keys;
        }
    }

    private void collect(String prefix, JsonNode node, Set<String> out) {
        node.fields().forEachRemaining(e -> {
            String k = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue().isObject()) collect(k, e.getValue(), out);
            else out.add(k);
        });
    }

    @Test
    void keysMatchAndNoBlankValues() throws Exception {
        Set<String> zh = flatKeys("zh-CN.json");
        Set<String> en = flatKeys("en-US.json");
        assertThat(zh).isEqualTo(en);
        assertThat(zh).contains("market.admin.status.pending", "market.load.more");
        // 无空值
        try (InputStream in = getClass().getResourceAsStream(BASE + "zh-CN.json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            assertNoBlanks(root);
        }
    }

    private void assertNoBlanks(JsonNode node) {
        node.fields().forEachRemaining(e -> {
            if (e.getValue().isObject()) assertNoBlanks(e.getValue());
            else assertThat(e.getValue().asText()).as(e.getKey()).isNotBlank();
        });
    }
}
