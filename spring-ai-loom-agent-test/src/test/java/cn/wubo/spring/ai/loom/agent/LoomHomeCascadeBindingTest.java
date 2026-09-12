package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the loom-home cascade in the {@code loomAgentProperties} binder bean:
 * when yml overrides {@code spring.ai.loom.agent.loom-home}, sub-paths still at
 * their built-in defaults are re-derived under the new root; explicitly
 * configured sub-paths always win.
 */
class LoomHomeCascadeBindingTest {

    private final LoomAgentConfiguration.InfrastructureConfiguration config =
            new LoomAgentConfiguration.InfrastructureConfiguration();

    private LoomAgentProperties bind(MockEnvironment env) {
        return config.loomAgentProperties(env);
    }

    @Test
    void noOverride_keepsBuiltInDefaults() {
        LoomAgentProperties p = bind(new MockEnvironment());
        assertThat(p.getFileBasePath()).startsWith(System.getProperty("user.home"));
        assertThat(p.getFileBasePath()).endsWith(".loom/file");
    }

    @Test
    void loomHomeOverride_rederivesAllDefaultSubPaths() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.ai.loom.agent.loom-home", "/data/loom-home");
        LoomAgentProperties p = bind(env);
        assertThat(p.getFileBasePath()).isEqualTo("/data/loom-home/file");
        assertThat(p.getDatasourceDir()).isEqualTo("/data/loom-home/datasource");
    }

    @Test
    void loomHomeOverride_explicitSubPathWins() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.ai.loom.agent.loom-home", "/data/loom-home")
                .withProperty("spring.ai.loom.agent.file-base-path", "/mnt/nas/user-files");
        LoomAgentProperties p = bind(env);
        assertThat(p.getFileBasePath()).isEqualTo("/mnt/nas/user-files");
        // siblings still cascade
        assertThat(p.getDatasourceDir()).isEqualTo("/data/loom-home/datasource");
    }
}
