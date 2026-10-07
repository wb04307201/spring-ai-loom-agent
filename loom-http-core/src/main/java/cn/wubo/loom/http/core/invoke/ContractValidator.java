package cn.wubo.loom.http.core.invoke;

import cn.wubo.loom.http.core.endpoint.Endpoint;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal contract check for response bodies (spec §6.1 step 12 paragraph 2 — 契约校验).
 *
 * For the response status that has an entry in {@code endpoint.responses} with a
 * {@code content[mediaType].schema}, the validator walks the schema's
 * {@code properties} (shallow — no recursion into {@code $ref} / {@code oneOf} /
 * complex nested types) and reports:
 *
 * <ul>
 *   <li>{@code unexpectedField:<name>} — response body has a property the schema does not</li>
 *   <li>{@code typeMismatch:<name>:expected=<t>:actual=<t>} — a property exists in both but the JSON type doesn't match</li>
 *   <li>{@code nonJsonResponse} — response body is not parseable as JSON</li>
 * </ul>
 *
 * <p><b>Warnings never throw and never block.</b> The caller receives a list and decides
 * what to do with it.
 */
public class ContractValidator {

    private final ObjectMapper mapper;

    public ContractValidator() {
        this(new ObjectMapper());
    }

    public ContractValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param status         HTTP status code of the response
     * @param body           raw response body (may be {@code null} or empty)
     * @param mergedEndpoint the merged endpoint definition; {@code null} means no contract
     *                       is applicable (returns empty list)
     * @return list of warning strings; never {@code null}; never throws
     */
    public List<String> validate(int status, String body, MergedEndpoint mergedEndpoint) {
        try {
            return doValidate(status, body, mergedEndpoint);
        } catch (RuntimeException ex) {
            // Defensive: validate() must never block. Surface as a nonJsonResponse-ish warning.
            List<String> w = new ArrayList<>();
            w.add("nonJsonResponse");
            return w;
        }
    }

    private List<String> doValidate(int status, String body, MergedEndpoint mergedEndpoint) {
        if (mergedEndpoint == null || mergedEndpoint.endpoint() == null) {
            return List.of();
        }
        Endpoint endpoint = mergedEndpoint.endpoint();
        Map<String, Endpoint.Response> responses = endpoint.getResponses();
        if (responses == null || responses.isEmpty()) {
            return List.of();
        }
        Endpoint.Response response = responses.get(String.valueOf(status));
        if (response == null) {
            return List.of();
        }
        Map<String, Endpoint.MediaType> content = response.getContent();
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        Endpoint.MediaType mediaType = pickMediaType(content);
        if (mediaType == null) {
            return List.of();
        }
        Map<String, Object> schema = mediaType.getSchema();
        if (schema == null) {
            return List.of();
        }
        Object propsObj = schema.get("properties");
        if (!(propsObj instanceof Map<?, ?> propsMap) || propsMap.isEmpty()) {
            // Either no properties declared, or it's not a property map we can iterate.
            // Per minimal version: nothing to check → empty list.
            return List.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) propsMap;

        // Parse the body. If it's not JSON → nonJsonResponse.
        Object parsed;
        try {
            parsed = mapper.readValue(body == null ? "" : body, Object.class);
        } catch (IOException | RuntimeException ex) {
            List<String> w = new ArrayList<>();
            w.add("nonJsonResponse");
            return w;
        }
        if (!(parsed instanceof Map<?, ?> respMap)) {
            // JSON but not an object — we cannot match object properties. Treat as nonJsonResponse
            // (the spec uses this label for "we can't apply the contract"; the structured warning
            // list above is still the signal an LLM can show to the user).
            List<String> w = new ArrayList<>();
            w.add("nonJsonResponse");
            return w;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> responseObject = (Map<String, Object>) respMap;

        List<String> warnings = new ArrayList<>();

        // 1) Unexpected fields — response has, schema doesn't.
        for (String fieldName : responseObject.keySet()) {
            if (!properties.containsKey(fieldName)) {
                warnings.add("unexpectedField:" + fieldName);
            }
        }

        // 2) Type mismatches — present in both, but the JSON type doesn't match schema.
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            String fieldName = e.getKey();
            if (!responseObject.containsKey(fieldName)) {
                // Missing from response: not flagged here (no spec mandate for "required" semantics
                // in this minimal version, and "not present" is structurally distinguishable).
                continue;
            }
            Object actual = responseObject.get(fieldName);
            if (actual == null) {
                continue; // null is a valid value for any type in JSON.
            }
            Object propertySchema = e.getValue();
            String expectedType = typeOfSchema(propertySchema);
            if (expectedType == null) {
                continue; // No type declared (e.g. $ref / oneOf / untyped) — skip per minimal spec.
            }
            if (!jsonTypeMatches(expectedType, actual)) {
                warnings.add("typeMismatch:" + fieldName
                    + ":expected=" + expectedType
                    + ":actual=" + jsonTypeName(actual));
            }
        }

        return warnings;
    }

    /**
     * Pick the most relevant media type to validate. Prefer JSON variants, fall back
     * to the first entry.
     */
    private static Endpoint.MediaType pickMediaType(Map<String, Endpoint.MediaType> content) {
        Endpoint.MediaType chosen = content.get("application/json");
        if (chosen != null) return chosen;
        for (Map.Entry<String, Endpoint.MediaType> e : content.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().toLowerCase();
            if (key.contains("json")) return e.getValue();
        }
        // Fallback: first declared media type.
        return content.values().iterator().next();
    }

    /**
     * Returns the OpenAPI "type" string for a property schema, or {@code null} when
     * we cannot determine it (ref / oneOf / no type declared / not an object).
     */
    private static String typeOfSchema(Object propertySchema) {
        if (!(propertySchema instanceof Map<?, ?> m)) return null;
        Object type = m.get("type");
        if (type == null) return null;
        String s = type.toString();
        return s.isBlank() ? null : s;
    }

    /**
     * Best-effort comparison between an OpenAPI {@code type} declaration and the
     * runtime type of a parsed JSON value. {@code null} returns {@code false}
     * unless explicitly handled by caller.
     */
    private static boolean jsonTypeMatches(String expectedType, Object actual) {
        if (expectedType == null || actual == null) return true;
        String actualType = jsonTypeName(actual);
        switch (expectedType) {
            case "string":
                return "string".equals(actualType);
            case "number":
                return "number".equals(actualType);
            case "integer":
                // integer accepts any numeric value whose runtime type is integral-Number;
                // also accept any number-shaped value whose string rep has no '.'/'e' to
                // tolerate JSON generators that emit Long but the schema is "integer".
                return "integer".equals(actualType) || "string".equals(actualType) && isIntegerString(actual.toString());
            case "boolean":
                return "boolean".equals(actualType);
            case "object":
                return "object".equals(actualType);
            case "array":
                return "array".equals(actualType);
            default:
                return false;
        }
    }

    /**
     * JSON-runtime type name: "string", "number", "integer", "boolean", "object", "array", or "null".
     */
    private static String jsonTypeName(Object o) {
        if (o == null) return "null";
        if (o instanceof String) return "string";
        if (o instanceof Boolean) return "boolean";
        if (o instanceof java.util.Map<?, ?>) return "object";
        if (o instanceof List<?>) return "array";
        if (o instanceof Number n) {
            // Integer / Long / Short / Byte / BigInteger → "integer"; Float/Double/BigDecimal → "number"
            return isIntegral(n) ? "integer" : "number";
        }
        return "unknown";
    }

    private static boolean isIntegral(Number n) {
        if (n instanceof java.math.BigDecimal bd) {
            try {
                bd.toBigIntegerExact();
                return true;
            } catch (ArithmeticException ex) {
                return false;
            }
        }
        if (n instanceof java.math.BigInteger) return true;
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) return false;
        return d == Math.floor(d);
    }

    private static boolean isIntegerString(String s) {
        if (s == null) return false;
        try {
            Long.parseLong(s.trim());
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
