package cn.wubo.spring.ai.loom.agent.tool.http;

import cn.wubo.loom.file.core.LoomPaths;
import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.profile.Profile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户隔离:Alice 的 profile(含 API 凭据)对 Bob 不可见。
 *
 * <p>这是 per-user 存储的核心保证。若失效,Bob 可用 Alice 的凭据对外发请求。
 */
@DisplayName("HTTP 配置用户隔离")
class HttpPerUserIsolationTest {

    @Test
    @DisplayName("Alice 的 profile 对 Bob 不可见,反之亦然")
    void profilesAreNotShared(@TempDir Path tmp) {
        HttpEngine alice = new HttpEngine(LoomPaths.userHttpDir(tmp.toString(), "alice"), new HttpConfig());
        Profile p = new Profile();
        p.setName("prod");
        p.setBaseUrl("https://api.example.com");
        p.getAuth().setType("bearer");
        p.getAuth().setToken("super-secret-token");
        alice.addProfile(p);

        HttpEngine bob = new HttpEngine(LoomPaths.userHttpDir(tmp.toString(), "bob"), new HttpConfig());
        assertThat(bob.listProfiles()).doesNotContain("prod");
        assertThat(bob.listProfiles()).isEmpty();
    }

    @Test
    @DisplayName("凭据文件写在 Alice 自己的目录下")
    void credentialStaysInOwnerDir(@TempDir Path tmp) throws Exception {
        HttpEngine alice = new HttpEngine(LoomPaths.userHttpDir(tmp.toString(), "alice"), new HttpConfig());
        Profile p = new Profile();
        p.setName("prod");
        p.getAuth().setType("bearer");   // addProfile 会跑 ProfileValidator,type 必填
        p.getAuth().setToken("super-secret-token");
        alice.addProfile(p);

        Path aliceFile = LoomPaths.userHttpDir(tmp.toString(), "alice")
                .resolve("profiles").resolve("prod.json");
        assertThat(Files.exists(aliceFile)).isTrue();
        assertThat(Files.readString(aliceFile)).contains("super-secret-token");

        Path bobFile = LoomPaths.userHttpDir(tmp.toString(), "bob")
                .resolve("profiles").resolve("prod.json");
        assertThat(Files.exists(bobFile)).isFalse();
    }
}