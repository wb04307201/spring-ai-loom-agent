package cn.wubo.loom.http.core.profile;

import org.junit.jupiter.api.Test;
import cn.wubo.loom.http.core.util.JsonMappers;
import static org.assertj.core.api.Assertions.*;

class ProfileTest {
    @Test
    void parsesBearerProfile() {
        String json = """
            {"name":"staging","baseUrl":"https://s.example.com",
             "auth":{"type":"bearer","token":"${env:TOK}"}}
            """;
        Profile p = JsonMappers.parse(json, Profile.class);
        assertThat(p.getName()).isEqualTo("staging");
        assertThat(p.getBaseUrl()).isEqualTo("https://s.example.com");
        assertThat(p.getAuth().getType()).isEqualTo("bearer");
        assertThat(p.getAuth().getToken()).isEqualTo("${env:TOK}");
    }

    @Test
    void hasTimeoutDefault() {
        Profile p = new Profile();
        assertThat(p.getTimeoutMs()).isEqualTo(10000);
    }

    @Test
    void hasRetryDefault() {
        Profile p = new Profile();
        assertThat(p.getRetry().getMaxAttempts()).isEqualTo(3);
        assertThat(p.getRetry().getRetryOn()).contains(502, 503, 504, 429);
    }
}
