package cn.wubo.loom.http.core;

import org.junit.jupiter.api.Test;
import cn.wubo.loom.http.core.util.JsonMappers;
import static org.assertj.core.api.Assertions.*;

class HttpConfigTest {
    @Test
    void parsesMinimalJson() {
        String json = "{\"version\":1,\"allowedDomains\":[\".example.com\"]}";
        HttpConfig cfg = JsonMappers.parse(json, HttpConfig.class);
        assertThat(cfg.getVersion()).isEqualTo(1);
        assertThat(cfg.getAllowedDomains()).containsExactly(".example.com");
    }

    @Test
    void hasDefaults() {
        HttpConfig cfg = new HttpConfig();
        assertThat(cfg.getMaxResponseSizeBytes()).isEqualTo(1048576L);
        assertThat(cfg.getMaxRequestBodyBytes()).isEqualTo(10485760L);
        assertThat(cfg.getMaxBatchConcurrency()).isEqualTo(10);
        assertThat(cfg.getHistoryMaxEntriesPerSystem()).isEqualTo(1000);
        assertThat(cfg.getSensitiveHeaders()).contains("Authorization", "Cookie");
    }
}