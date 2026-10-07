package cn.wubo.loom.http.core.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;

public final class JsonMappers {
    /**
     * Shared Jackson mapper. Notable choices:
     * <ul>
     *   <li>{@code WRITE_DATES_AS_TIMESTAMPS} is disabled so {@code java.time}
     *       values round-trip as ISO-8601 strings (e.g. "2026-08-23T...").</li>
     *   <li>{@code INDENT_OUTPUT} is intentionally DISABLED. Pretty-printing
     *       would add newlines and {@code ": "} spacing to every serialised
     *       body — fine for log dumps, but surprising in HTTP request bodies
     *       (the upstream sees whitespace the caller never wrote, and strict
     *       wire-level stub matchers fail). Regression: prior to this fix,
     *       JsonMappers.toJson(Map) emitted multi-line bodies that broke
     *       body-equality assertions and surprised upstream parsers that
     *       strip whitespace differently.</li>
     *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES} is disabled so deserialisation
     *       of forward-compatible response payloads does not blow up when an
     *       upstream adds a new field.</li>
     * </ul>
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonMappers() {}

    public static ObjectMapper mapper() { return MAPPER; }

    public static <T> T parse(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse JSON", e);
        }
    }

    public static <T> T parse(InputStream is, Class<T> type) {
        try {
            return MAPPER.readValue(is, type);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse JSON", e);
        }
    }

    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize JSON", e);
        }
    }
}
