package cn.wubo.loom.http.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HttpStorage 路径派生")
class HttpStorageTest {

    @Test
    @DisplayName("root 绝对化并规范化，子目录挂在其下")
    void derivesSubdirectories(@TempDir Path tmp) {
        HttpStorage s = new HttpStorage(tmp.resolve("a/../b"));
        assertThat(s.root()).isEqualTo(tmp.resolve("b").toAbsolutePath().normalize());
        assertThat(s.profilesDir()).isEqualTo(s.root().resolve("profiles"));
        assertThat(s.systemsDir()).isEqualTo(s.root().resolve("systems"));
        assertThat(s.historyDir()).isEqualTo(s.root().resolve("history"));
        assertThat(s.responsesDir()).isEqualTo(s.root().resolve("responses"));
        assertThat(s.configFile()).isEqualTo(s.root().resolve("config.json"));
        assertThat(s.logDir()).isEqualTo(s.root().resolve("log"));
        assertThat(s.auditLogFile()).isEqualTo(s.root().resolve("log").resolve("audit.log"));
    }

    @Test
    @DisplayName("两个不同 root 完全隔离 —— 双模存储隔离的基础")
    void separateRootsAreIndependent(@TempDir Path tmp) throws java.io.IOException {
        // AssertJ's Path#startsWith calls toRealPath() on BOTH the asserted
        // path AND the prefix, so we seed the leaf dir being asserted
        // (profiles) so toRealPath succeeds. HttpStorage itself never
        // creates directories — this is purely to satisfy the assertion.
        Files.createDirectories(tmp.resolve("users/alice/http/profiles"));
        Files.createDirectories(tmp.resolve("users/bob/http/profiles"));
        HttpStorage alice = new HttpStorage(tmp.resolve("users/alice/http"));
        HttpStorage bob = new HttpStorage(tmp.resolve("users/bob/http"));
        assertThat(alice.profilesDir()).isNotEqualTo(bob.profilesDir());
        assertThat(alice.profilesDir()).startsWith(tmp.resolve("users/alice"));
        assertThat(bob.profilesDir()).startsWith(tmp.resolve("users/bob"));
    }
}
