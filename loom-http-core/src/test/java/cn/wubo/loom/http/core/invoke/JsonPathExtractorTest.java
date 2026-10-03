package cn.wubo.loom.http.core.invoke;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class JsonPathExtractorTest {

    private final JsonPathExtractor extractor = new JsonPathExtractor();

    @Test
    void extractsSingleField() {
        String body = "{\"name\":\"Alice\",\"age\":30}";
        Map<String, String> paths = Map.of("userName", "$.name");
        Map<String, Object> out = extractor.extract(body, paths);
        assertThat(out).containsEntry("userName", "Alice");
    }

    @Test
    void extractsMultipleFields() {
        String body = "{\"name\":\"Bob\",\"age\":42,\"city\":\"Paris\"}";
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("userName", "$.name");
        paths.put("userAge", "$.age");
        paths.put("userCity", "$.city");
        Map<String, Object> out = extractor.extract(body, paths);
        assertThat(out)
            .containsEntry("userName", "Bob")
            .containsEntry("userAge", 42)
            .containsEntry("userCity", "Paris")
            .hasSize(3);
    }

    @Test
    void extractsNestedField() {
        String body = "{\"data\":{\"id\":123,\"token\":\"abc\"}}";
        Map<String, String> paths = Map.of("token", "$.data.token");
        Map<String, Object> out = extractor.extract(body, paths);
        assertThat(out).containsEntry("token", "abc");
    }

    @Test
    void missingPathReturnsNullWithoutThrowing() {
        String body = "{\"name\":\"Carol\"}";
        Map<String, String> paths = Map.of("missing", "$.does.not.exist");
        Map<String, Object> out = extractor.extract(body, paths);
        assertThat(out).containsEntry("missing", null);
    }

    @Test
    void invalidJsonPathSyntaxThrowsExtractionException() {
        String body = "{\"a\":1}";
        Map<String, String> paths = Map.of("bad", "$.[invalid");
        assertThatThrownBy(() -> extractor.extract(body, paths))
            .isInstanceOf(ExtractionException.class);
    }

    @Test
    void emptyPathMapReturnsEmptyMap() {
        String body = "{\"a\":1}";
        Map<String, Object> out = extractor.extract(body, Map.of());
        assertThat(out).isEmpty();
    }
}