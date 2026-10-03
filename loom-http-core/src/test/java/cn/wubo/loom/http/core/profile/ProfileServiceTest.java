package cn.wubo.loom.http.core.profile;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class ProfileServiceTest {
    @TempDir Path tmp;
    ProfileService svc;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("profiles"));
        HttpStorage sc = new HttpStorage(tmp);
        svc = new ProfileService(sc, new HttpConfig());
    }

    @Test
    void savesAndLoads() throws Exception {
        Profile p = new Profile();
        p.setName("test");
        p.setBaseUrl("https://x.com");
        svc.save(p);

        Profile loaded = svc.get("test");
        assertThat(loaded.getBaseUrl()).isEqualTo("https://x.com");
    }

    @Test
    void listAllReturnsSaved() throws Exception {
        Profile p = new Profile();
        p.setName("a"); svc.save(p);
        Profile q = new Profile();
        q.setName("b"); svc.save(q);

        assertThat(svc.listAll()).extracting(Profile::getName).contains("a", "b");
    }

    @Test
    void getMissingThrows() {
        assertThatThrownBy(() -> svc.get("nope"))
            .isInstanceOf(ProfileNotFoundException.class);
    }

    @Test
    void deleteRemoves() throws Exception {
        Profile p = new Profile(); p.setName("x"); svc.save(p);
        svc.delete("x");
        assertThatThrownBy(() -> svc.get("x")).isInstanceOf(ProfileNotFoundException.class);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void savedProfileHasOwnerReadWriteOnly() throws Exception {
        Profile p = new Profile();
        p.setName("test");
        p.setBaseUrl("https://example.com");
        svc.save(p);

        Path file = tmp.resolve("profiles/test.json");
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        );
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void saveIgnoresPosixFailuresOnWindows() throws Exception {
        Profile p = new Profile();
        p.setName("test");
        p.setBaseUrl("https://example.com");
        assertThatCode(() -> svc.save(p)).doesNotThrowAnyException();

        Path file = tmp.resolve("profiles/test.json");
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.size(file)).isGreaterThan(0);
    }
}
