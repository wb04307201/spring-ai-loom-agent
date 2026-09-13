package cn.wubo.spring.ai.loom.agent.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the path-default contract: every filesystem-backed property should
 * land under {@code ${user.home}/.loom/}, never a cwd-relative
 * {@code .local/...}. A single {@code rm -rf ~/.loom/} should reach
 * everything; if a future change reintroduces {@code .local} defaults, this
 * test fails loudly.
 */
class LoomAgentPropertiesDefaultsTest {

    @Test
    void usersBasePath_defaultsUnderUserHome_dot_loom() {
        LoomAgentProperties p = new LoomAgentProperties();
        assertThat(p.getUsersBasePath()).startsWith(System.getProperty("user.home"));
        assertThat(p.getUsersBasePath()).contains(".loom").contains("users");
        assertThat(p.getUsersBasePath()).doesNotContain(".local");
    }

    @Test
    void datasourceDir_defaultsUnderUserHome_dot_loom() {
        LoomAgentProperties p = new LoomAgentProperties();
        assertThat(p.getDatasourceDir())
                .startsWith(System.getProperty("user.home"))
                .contains(".loom").contains("datasource")
                .doesNotContain(".local");
    }

    @Test
    void loomHome_isExposedAndAbsolute() {
        LoomAgentProperties p = new LoomAgentProperties();
        // Compare via Path so OS-specific separators don't matter (Windows
        // emits backslashes for the user.home part and our string concat emits
        // forward slashes — both resolve to the same Path).
        assertThat(Paths.get(p.getLoomHome()))
                .isEqualTo(Paths.get(System.getProperty("user.home"), ".loom"));
    }

    @Test
    void singleRm_rfTargets_allUserState() {
        LoomAgentProperties p = new LoomAgentProperties();
        String loomHome = p.getLoomHome();
        // String compare (not Path.startsWith) so the test doesn't try to
        // resolve on disk — the knowledge-dir may not exist yet on a fresh
        // user setup, and the contract we want to pin is purely about the
        // configured default, not the filesystem state.
        for (String path : new String[]{
                p.getUsersBasePath(),
                p.getDatasourceDir()
        }) {
            assertThat(path)
                    .as("path %s must live under loomHome %s", path, loomHome)
                    .startsWith(loomHome + "/");
        }
    }

    @Test
    void askuserTimeoutSeconds_defaultsTo300() {
        LoomAgentProperties props = new LoomAgentProperties();
        assertThat(props.getAskuser().getTimeoutSeconds()).isEqualTo(300L);
    }

    @Test
    void renderProperty_defaultsMatchSpec() {
        LoomAgentProperties props = new LoomAgentProperties();
        assertThat(props.getRender().getDeviceScaleFactor()).isEqualTo(2);
        assertThat(props.getRender().getTimeoutSeconds()).isEqualTo(30);
        assertThat(props.getRender().getRenderWaitMs()).isEqualTo(1500);
        assertThat(props.getRender().isNetworkBlocked()).isTrue();
        assertThat(props.getRender().getMaxHtmlBytes()).isEqualTo(2L * 1024 * 1024);
    }
}
