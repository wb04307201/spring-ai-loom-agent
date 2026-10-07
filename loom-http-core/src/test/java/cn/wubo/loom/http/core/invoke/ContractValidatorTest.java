package cn.wubo.loom.http.core.invoke;

import cn.wubo.loom.http.core.endpoint.Endpoint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ContractValidator} — covers the minimal contract check
 * described in spec §6.1 step 12 paragraph 2 (契约校验).
 *
 * Pure unit tests; no Spring, no HTTP. Each test builds a hand-rolled
 * {@link MergedEndpoint} with a response schema and verifies the warning list
 * returned by {@link ContractValidator#validate}.
 */
class ContractValidatorTest {

    private final ContractValidator validator = new ContractValidator();

    // ---------- helpers ----------

    /**
     * Build a MergedEndpoint whose response schema for the given status declares
     * the supplied properties map under {@code properties}.
     */
    private static MergedEndpoint mergedEndpointWithProperties(int status,
                                                               Map<String, Object> properties) {
        Endpoint endpoint = new Endpoint();
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        if (properties != null) {
            schema.put("properties", properties);
        }
        Endpoint.MediaType mt = new Endpoint.MediaType();
        mt.setSchema(schema);
        Endpoint.Response response = new Endpoint.Response();
        Map<String, Endpoint.MediaType> content = new LinkedHashMap<>();
        content.put("application/json", mt);
        response.setContent(content);
        Map<String, Endpoint.Response> responses = new LinkedHashMap<>();
        responses.put(String.valueOf(status), response);
        endpoint.setResponses(responses);
        return new MergedEndpoint(endpoint, "manual", Instant.now());
    }

    /**
     * Build a schema fragment for a single property — used to populate
     * {@code properties} when only one field needs a typed declaration.
     */
    private static Map<String, Object> property(String type) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        return p;
    }

    // ---------- 1. null mergedEndpoint ----------

    @Test
    void nullMergedEndpointReturnsEmpty() {
        List<String> warnings = validator.validate(200, "{\"id\":1}", null);
        assertThat(warnings).isEmpty();
    }

    // ---------- 2. no schema for given status ----------

    @Test
    void noSchemaForStatusReturnsEmpty() {
        MergedEndpoint me = mergedEndpointWithProperties(200, Map.of("id", property("number")));
        // Status 404 has no entry in responses.
        List<String> warnings = validator.validate(404, "{\"id\":1}", me);
        assertThat(warnings).isEmpty();
    }

    @Test
    void responsesMapEmptyReturnsEmpty() {
        Endpoint endpoint = new Endpoint();
        endpoint.setResponses(new LinkedHashMap<>());
        MergedEndpoint me = new MergedEndpoint(endpoint, "manual", Instant.now());
        List<String> warnings = validator.validate(200, "{}", me);
        assertThat(warnings).isEmpty();
    }

    @Test
    void nullResponsesMapReturnsEmpty() {
        Endpoint endpoint = new Endpoint();
        endpoint.setResponses(null);
        MergedEndpoint me = new MergedEndpoint(endpoint, "manual", Instant.now());
        List<String> warnings = validator.validate(200, "{}", me);
        assertThat(warnings).isEmpty();
    }

    // ---------- 3. non-JSON body ----------

    @Test
    void nonJsonBodyEmitsNonJsonResponse() {
        MergedEndpoint me = mergedEndpointWithProperties(200, Map.of("id", property("number")));
        List<String> warnings = validator.validate(200, "not even close to JSON", me);
        assertThat(warnings).contains("nonJsonResponse");
    }

    @Test
    void emptyBodyEmitsNonJsonResponse() {
        MergedEndpoint me = mergedEndpointWithProperties(200, Map.of("id", property("number")));
        List<String> warnings = validator.validate(200, "", me);
        assertThat(warnings).contains("nonJsonResponse");
    }

    // ---------- 4. all fields present, types match ----------

    @Test
    void allFieldsPresentAndTypeMatchReturnsEmpty() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", property("integer"));
        props.put("name", property("string"));
        props.put("active", property("boolean"));
        MergedEndpoint me = mergedEndpointWithProperties(200, props);

        String body = "{\"id\":1,\"name\":\"Alice\",\"active\":true}";
        List<String> warnings = validator.validate(200, body, me);
        assertThat(warnings).isEmpty();
    }

    @Test
    void extraFieldsNotDeclaredInSchemaMeansNoWarningWhenAllDeclaredAreValid() {
        // Response has MORE than schema declares → that's expectedField. But this test
        // is for the case where response has matching + extra fields, schema only declares
        // a subset. Per spec, extra fields in RESPONSE → unexpectedField.
        // Here we keep schema's "id" declared AND require ALL response fields declared;
        // response includes both "id" and "name", but schema only declares "id".
        // That would still emit unexpectedField:name — covered in test 5.
        // So instead this asserts the negative: a RESPONSE that is an exact subset.
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", property("integer"));
        MergedEndpoint me = mergedEndpointWithProperties(200, props);
        List<String> warnings = validator.validate(200, "{\"id\":42}", me);
        assertThat(warnings).isEmpty();
    }

    // ---------- 5. unexpected field ----------

    @Test
    void unexpectedFieldEmitsWarning() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", property("integer"));
        MergedEndpoint me = mergedEndpointWithProperties(200, props);

        String body = "{\"id\":1,\"extra\":\"surprise\"}";
        List<String> warnings = validator.validate(200, body, me);
        assertThat(warnings).anyMatch(w -> w.startsWith("unexpectedField:") && w.contains("extra"));
    }

    @Test
    void multipleUnexpectedFieldsAllReported() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", property("integer"));
        MergedEndpoint me = mergedEndpointWithProperties(200, props);

        String body = "{\"id\":1,\"a\":1,\"b\":2}";
        List<String> warnings = validator.validate(200, body, me);
        assertThat(warnings).anyMatch(w -> w.startsWith("unexpectedField:") && w.contains(":a"));
        assertThat(warnings).anyMatch(w -> w.startsWith("unexpectedField:") && w.contains(":b"));
    }

    // ---------- 6. type mismatch ----------

    @Test
    void typeMismatchEmitsWarning() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", property("string"));
        MergedEndpoint me = mergedEndpointWithProperties(200, props);

        String body = "{\"name\":12345}";
        List<String> warnings = validator.validate(200, body, me);
        assertThat(warnings).anyMatch(w -> w.startsWith("typeMismatch:") && w.contains("name"));
    }

    @Test
    void typeMismatchBooleanExpectedButGotString() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("flag", property("boolean"));
        MergedEndpoint me = mergedEndpointWithProperties(200, props);
        String body = "{\"flag\":\"yes\"}";
        List<String> warnings = validator.validate(200, body, me);
        assertThat(warnings).anyMatch(w -> w.startsWith("typeMismatch:") && w.contains("flag"));
    }

    @Test
    void typeMismatchNumberExpectedButGotObject() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("count", property("number"));
        MergedEndpoint me = mergedEndpointWithProperties(200, props);
        String body = "{\"count\":{\"nested\":1}}";
        List<String> warnings = validator.validate(200, body, me);
        assertThat(warnings).anyMatch(w -> w.startsWith("typeMismatch:") && w.contains("count"));
    }

    @Test
    void integerExpectedAndNumericValuePasses() {
        // integer accepts any Number type — JSON "1" parsed as Integer, "1.5" as Double.
        // For type=integer we accept any Number; if someone passes a String "one",
        // that's a typeMismatch. This test confirms a correct integer value passes.
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("count", property("integer"));
        MergedEndpoint me = mergedEndpointWithProperties(200, props);
        String body = "{\"count\":7}";
        List<String> warnings = validator.validate(200, body, me);
        assertThat(warnings).isEmpty();
    }

    @Test
    void nullFieldValueDoesNotTriggerTypeMismatch() {
        // A missing/null field is fine — only mismatched concrete types warn.
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", property("string"));
        MergedEndpoint me = mergedEndpointWithProperties(200, props);
        String body = "{\"name\":null}";
        List<String> warnings = validator.validate(200, body, me);
        assertThat(warnings).isEmpty();
    }

    @Test
    void warningsNeverThrowEvenOnGarbageBody() {
        MergedEndpoint me = mergedEndpointWithProperties(200, Map.of("id", property("integer")));
        // Should not throw — returns at minimum a nonJsonResponse warning.
        List<String> warnings = validator.validate(200, "<html>oops</html>", me);
        assertThat(warnings).isNotNull();
        assertThat(warnings).contains("nonJsonResponse");
    }
}
