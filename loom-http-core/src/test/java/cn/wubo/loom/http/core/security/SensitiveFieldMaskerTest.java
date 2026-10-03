package cn.wubo.loom.http.core.security;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class SensitiveFieldMaskerTest {
    private final SensitiveFieldMasker masker =
        new SensitiveFieldMasker(Set.of("Authorization", "Cookie", "X-API-Key"));

    @Test
    void masksAuthorization() {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer xxx");
        Map<String, String> m = masker.mask(h);
        assertThat(m.get("Authorization")).isEqualTo("***");
    }

    @Test
    void caseInsensitiveKey() {
        Map<String, String> h = new HashMap<>();
        h.put("authorization", "Bearer xxx");
        masker.mask(h);
        assertThat(h.get("authorization")).isEqualTo("***");
    }

    @Test
    void nonSensitiveUnchanged() {
        Map<String, String> h = new HashMap<>();
        h.put("X-Tenant", "staging");
        masker.mask(h);
        assertThat(h.get("X-Tenant")).isEqualTo("staging");
    }

    @Test
    void isSensitiveChecks() {
        assertThat(masker.isSensitive("Authorization")).isTrue();
        assertThat(masker.isSensitive("authorization")).isTrue();
        assertThat(masker.isSensitive("X-Tenant")).isFalse();
    }

    @Test
    void emptyInput() {
        Map<String, String> result = masker.mask(new HashMap<>());
        assertThat(result).isEmpty();
    }

    // ---------- JSON body masking ----------

    @Test
    void masksNestedJsonFields() {
        // Sensitive keys must be masked wherever they appear in the JSON
        // tree — including nested inside arrays and sub-objects. The token
        // value here is also under a sibling key; the masker only touches
        // the key it was configured for.
        String body = "{\"Authorization\":\"Bearer xxx\",\"user\":{\"Authorization\":\"inner\",\"name\":\"alice\"},\"items\":[{\"Authorization\":\"list\"}]}";
        String masked = masker.maskJsonBody(body);
        // JsonMappers is configured with INDENT_OUTPUT, so the output has
        // padding whitespace — match flexibly rather than via exact substrings.
        assertThat(masked).containsPattern("\"Authorization\"\\s*:\\s*\"\\*\\*\\*\"");
        // No raw token should leak — every "Authorization" key maps to "***".
        assertThat(masked).doesNotContain("Bearer xxx");
        assertThat(masked).doesNotContain("\"inner\"");
        assertThat(masked).doesNotContain("\"list\"");
        // The sibling "name" key is preserved verbatim.
        assertThat(masked).contains("alice");
    }

    @Test
    void masksAcrossCaseInsensitiveDuplicates() {
        // "Authorization" and "authorization" are the same key for matching
        // purposes — both should be masked even though they differ in case.
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer upper");
        h.put("authorization", "Bearer lower");
        masker.mask(h);
        assertThat(h.get("Authorization")).isEqualTo("***");
        assertThat(h.get("authorization")).isEqualTo("***");
    }

    @Test
    void doesNotMaskNonSensitiveOverlappingKeys() {
        // "X-Authorization-Status" is a DIFFERENT key from "Authorization" —
        // it's just a status flag that happens to contain the word
        // "Authorization" in its name. The masker must only match the exact
        // configured key, not a substring or prefix.
        Map<String, String> h = new HashMap<>();
        h.put("X-Authorization-Status", "verified");
        h.put("Authorization-Foo", "bar");
        masker.mask(h);
        assertThat(h.get("X-Authorization-Status")).isEqualTo("verified");
        assertThat(h.get("Authorization-Foo")).isEqualTo("bar");
    }

    @Test
    void maskJsonBodyHandlesNonJsonInput() {
        // Malformed bodies are returned unchanged so history keeps a faithful
        // copy — masking must never corrupt a payload.
        String masked = masker.maskJsonBody("not really json");
        assertThat(masked).isEqualTo("not really json");
    }

    @Test
    void maskJsonBodyHandlesNullAndBlank() {
        assertThat(masker.maskJsonBody(null)).isNull();
        assertThat(masker.maskJsonBody("")).isEqualTo("");
        assertThat(masker.maskJsonBody("   ")).isEqualTo("   ");
    }

    // ---------- maskBody(Object) — structured input for history persistence ----------

    private final SensitiveFieldMasker bodyMasker =
        new SensitiveFieldMasker(Set.of("password", "token", "apiKey"));

    @Test
    void maskBodyNull() {
        assertThat(bodyMasker.maskBody(null)).isNull();
    }

    @Test
    void maskBodyMap() {
        Object body = java.util.Map.of(
            "username", "admin",
            "password", "s3cret",
            "token", "eyJ...");
        Object masked = bodyMasker.maskBody(body);
        // Round-trip through Jackson turns LinkedHashMap-likes into regular
        // Map; assert on the (key, value) pairs we care about.
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> m = (java.util.Map<String, Object>) masked;
        assertThat(m.get("username")).isEqualTo("admin");
        assertThat(m.get("password")).isEqualTo("***");
        assertThat(m.get("token")).isEqualTo("***");
    }

    @Test
    void maskBodyNestedMapAndArray() {
        Object body = java.util.Map.of(
            "user", java.util.Map.of("name", "alice", "password", "s3cret"),
            "items", java.util.List.of(
                java.util.Map.of("apiKey", "k1", "id", 1),
                java.util.Map.of("apiKey", "k2", "id", 2)));
        Object masked = bodyMasker.maskBody(body);
        String json = cn.wubo.loom.http.core.util.JsonMappers.toJson(masked);
        // JsonMappers uses compact JSON (no INDENT_OUTPUT, see FIX-6) so
        // we assert on compact form, not the old pretty-printed form.
        assertThat(json).contains("\"name\":\"alice\"");
        assertThat(json).contains("\"password\":\"***\"");
        assertThat(json).contains("\"apiKey\":\"***\"");
        assertThat(json).contains("\"id\":1");
        // No raw secret should leak anywhere.
        assertThat(json).doesNotContain("s3cret");
        assertThat(json).doesNotContain("\"k1\"");
        assertThat(json).doesNotContain("\"k2\"");
    }

    @Test
    void maskBodyDoesNotMatchSubstring() {
        // "userPassword" is NOT "password" by exact match. Operators must
        // add it to the sensitive list explicitly if they need that. Same
        // semantics as header masking.
        Object body = java.util.Map.of("userPassword", "s3cret", "Password", "p1");
        Object masked = bodyMasker.maskBody(body);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> m = (java.util.Map<String, Object>) masked;
        // Substring case: exact "Password" matches case-insensitively → masked.
        assertThat(m.get("Password")).isEqualTo("***");
        // Different key, NOT masked — operator must extend the list.
        assertThat(m.get("userPassword")).isEqualTo("s3cret");
    }

    @Test
    void maskBodyStringPassthrough() {
        // Strings are treated as JSON candidates; if unparseable, returned
        // unchanged (best-effort, must not corrupt history).
        assertThat(bodyMasker.maskBody("not really json")).isEqualTo("not really json");
        // Strings that DO parse as JSON get masked, and the result is
        // still a String (not round-tripped through Map).
        Object masked = bodyMasker.maskBody("{\"password\":\"s3cret\"}");
        assertThat(masked).isInstanceOf(String.class);
        assertThat((String) masked).contains("\"password\":\"***\"")
            .doesNotContain("s3cret");
    }

    @Test
    void maskBodyDoesNotMutateInput() {
        java.util.Map<String, Object> original = new java.util.HashMap<>();
        original.put("password", "s3cret");
        original.put("name", "alice");
        bodyMasker.maskBody(original);
        // The original map must be untouched.
        assertThat(original.get("password")).isEqualTo("s3cret");
        assertThat(original.get("name")).isEqualTo("alice");
    }
}
