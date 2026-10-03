package cn.wubo.loom.http.core.profile;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class AuthProviderTest {
    @Test
    void bearerAddsAuthorization() {
        Profile.Auth a = new Profile.Auth();
        a.setType("bearer");
        a.setToken("abc");
        Map<String, String> h = new HashMap<>();
        Map<String, String> q = new HashMap<>();
        AuthProvider.apply(a, h, q);
        assertThat(h.get("Authorization")).isEqualTo("Bearer abc");
    }

    @Test
    void basicAddsAuthorization() {
        Profile.Auth a = new Profile.Auth();
        a.setType("basic");
        a.setUsername("u"); a.setPassword("p");
        Map<String, String> h = new HashMap<>();
        AuthProvider.apply(a, h, new HashMap<>());
        assertThat(h.get("Authorization")).isEqualTo("Basic " + java.util.Base64.getEncoder().encodeToString("u:p".getBytes()));
    }

    @Test
    void apiKeyHeaderAddsHeader() {
        Profile.Auth a = new Profile.Auth();
        a.setType("apiKey-header");
        a.setKeyName("X-API-Key");
        a.setValue("secret");
        Map<String, String> h = new HashMap<>();
        AuthProvider.apply(a, h, new HashMap<>());
        assertThat(h.get("X-API-Key")).isEqualTo("secret");
    }

    @Test
    void apiKeyQueryAddsQuery() {
        Profile.Auth a = new Profile.Auth();
        a.setType("apiKey-query");
        a.setKeyName("api_key");
        a.setValue("k1");
        Map<String, String> q = new HashMap<>();
        AuthProvider.apply(a, new HashMap<>(), q);
        assertThat(q.get("api_key")).isEqualTo("k1");
    }

    @Test
    void unknownTypeIgnored() {
        Profile.Auth a = new Profile.Auth();
        a.setType("oauth");
        Map<String, String> h = new HashMap<>();
        AuthProvider.apply(a, h, new HashMap<>());
        assertThat(h).isEmpty();
    }
}