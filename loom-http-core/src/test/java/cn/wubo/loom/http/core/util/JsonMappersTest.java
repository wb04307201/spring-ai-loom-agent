package cn.wubo.loom.http.core.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class JsonMappersTest {
    public record Sample(String name, int value) {}

    @Test
    void parsesJsonString() {
        Map<String, Object> result = JsonMappers.parse("{\"a\":1}", Map.class);
        assertThat(result.get("a")).isEqualTo(1);
    }

    @Test
    void serializesToCompactJson() {
        // Regression: JsonMappers previously had SerializationFeature.INDENT_OUTPUT
        // enabled, which made every serialised body multi-line with ": " spacing.
        // That's a body-corruption bug for HTTP request bodies — the upstream
        // sees whitespace the caller never wrote, and strict stub matchers fail.
        // Compact JSON is the right default for an HTTP client library; log
        // formatting belongs in the logging layer.
        String json = JsonMappers.toJson(Map.of("a", 1));
        assertThat(json).isEqualTo("{\"a\":1}");
    }

    @Test
    void parsesRecord() {
        Sample s = JsonMappers.parse("{\"name\":\"x\",\"value\":42}", Sample.class);
        assertThat(s.name()).isEqualTo("x");
        assertThat(s.value()).isEqualTo(42);
    }

    @Test
    void mapperReturnsSameInstance() {
        assertThat(JsonMappers.mapper()).isSameAs(JsonMappers.mapper());
    }
}
