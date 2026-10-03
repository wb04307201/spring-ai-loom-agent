package cn.wubo.loom.http.core.system;

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

class SystemServiceTest {
    @TempDir Path tmp;
    SystemService svc;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("systems"));
        HttpStorage sc = new HttpStorage(tmp);
        svc = new SystemService(sc, new HttpConfig());
    }

    @Test
    void savesAndLoads() throws Exception {
        System s = new System();
        s.setName("test");
        s.setBaseUrl("https://x.com");
        svc.save(s);

        System loaded = svc.get("test");
        assertThat(loaded.getBaseUrl()).isEqualTo("https://x.com");
    }

    @Test
    void listAllReturnsSaved() throws Exception {
        System s = new System();
        s.setName("a"); svc.save(s);
        System t = new System();
        t.setName("b"); svc.save(t);

        assertThat(svc.listAll()).extracting(System::getName).contains("a", "b");
    }

    @Test
    void getMissingThrows() {
        assertThatThrownBy(() -> svc.get("nope"))
            .isInstanceOf(SystemNotFoundException.class);
    }

    @Test
    void deleteRemoves() throws Exception {
        System s = new System(); s.setName("x"); svc.save(s);
        svc.delete("x");
        assertThatThrownBy(() -> svc.get("x")).isInstanceOf(SystemNotFoundException.class);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void savedSystemHasOwnerReadWriteOnly() throws Exception {
        System s = new System();
        s.setName("test");
        s.setBaseUrl("https://example.com");
        svc.save(s);

        Path file = tmp.resolve("systems/test.json");
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        );
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void saveIgnoresPosixFailuresOnWindows() throws Exception {
        System s = new System();
        s.setName("test");
        s.setBaseUrl("https://example.com");
        assertThatCode(() -> svc.save(s)).doesNotThrowAnyException();

        Path file = tmp.resolve("systems/test.json");
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.size(file)).isGreaterThan(0);
    }
}
