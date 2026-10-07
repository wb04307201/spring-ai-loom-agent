package cn.wubo.loom.http.core.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.wubo.loom.http.core.util.JsonMappers;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SensitiveFieldMasker {
    private static final String MASK = "***";
    private final Set<String> sensitiveLower;

    public SensitiveFieldMasker(Set<String> fields) {
        this.sensitiveLower = fields.stream()
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
    }

    public Map<String, String> mask(Map<String, String> headers) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (sensitiveLower.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                e.setValue(MASK);
            }
        }
        return headers;
    }

    public boolean isSensitive(String key) {
        return sensitiveLower.contains(key.toLowerCase(Locale.ROOT));
    }

    /**
     * Mask sensitive fields in a JSON document body (request body, response
     * body, history payload, etc.). Walks the tree recursively — sensitive
     * keys nested inside arrays or sub-objects are masked too.
     *
     * <p>Matching is exact and case-insensitive on the field name (so
     * {@code "Authorization"} and {@code "authorization"} both mask, but
     * {@code "X-Authorization-Status"} is untouched).
     *
     * <p>If the input is not valid JSON, the original string is returned
     * unchanged — masking is best-effort and must not corrupt the payload
     * the caller is about to write to history.
     */
    public String maskJsonBody(String json) {
        if (json == null || json.isBlank()) return json;
        try {
            ObjectMapper mapper = JsonMappers.mapper();
            JsonNode root = mapper.readTree(json);
            JsonNode masked = maskNode(root);
            return mapper.writeValueAsString(masked);
        } catch (Exception e) {
            // Not valid JSON (or write failed for some structural reason).
            // Return the original string so history keeps a faithful copy.
            return json;
        }
    }

    /**
     * Mask sensitive fields in a structured body (Map / List / String /
     * primitive / null). Used for {@code request.body} in history entries
     * — InvokeService calls this on the history-entry body before writing
     * the JSONL line so credentials never end up on disk.
     *
     * <p>Strategy: serialise the object to JSON, run {@link #maskJsonBody},
     * deserialise back. Round-trips through Jackson but is safe for the
     * structured shapes callers actually pass (Maps, Lists, primitives,
     * Strings). For non-JSON String bodies (e.g. raw CSV sent via
     * {@code bodyRaw}), the inner {@code maskJsonBody} gracefully returns
     * the original string when it cannot parse — masking is best-effort
     * and must never corrupt the persisted history.
     *
     * <p>The returned object is a fresh value; the input is not mutated.
     */
    public Object maskBody(Object body) {
        if (body == null) return null;
        // For String bodies, skip the toJson round-trip — the String IS
        // already the JSON representation (callers pass bodyRaw verbatim).
        // Re-serialising a String would wrap it in extra quotes and the
        // inner structure would be hidden from maskJsonBody.
        if (body instanceof String s) {
            return maskJsonBody(s);
        }
        try {
            String json = JsonMappers.toJson(body);
            String masked = maskJsonBody(json);
            if (masked == null) return null;
            return JsonMappers.parse(masked, Object.class);
        } catch (Exception e) {
            // Should not happen — toJson + parse are mirror operations on
            // the same mapper. If it does (e.g. custom serialiser throws),
            // return the original body so the caller still gets SOMETHING
            // written, even unmasked.
            return body;
        }
    }

    private JsonNode maskNode(JsonNode node) {
        if (node == null) return node;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            var it = obj.fields();
            while (it.hasNext()) {
                var entry = it.next();
                String key = entry.getKey();
                if (isSensitive(key)) {
                    obj.put(key, MASK);
                } else {
                    obj.set(key, maskNode(entry.getValue()));
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, maskNode(arr.get(i)));
            }
        }
        return node;
    }
}
