package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: the loomAgentProperties bean binds via Binder then manually copies
 * fields. This test guards that askuser.timeoutSeconds (and the default) survive
 * the copy — a missing setAskuser() silently dropped the yml/cmdline override
 * (revalidation defect #2).
 */
class LoomAgentPropertiesBindingTest {

    @Test
    void askuserTimeoutSecondsFromEnvironmentIsBound() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.ai.loom.agent.askuser.timeout-seconds", "42");
        LoomAgentProperties props =
                new LoomAgentConfiguration.InfrastructureConfiguration().loomAgentProperties(env);
        assertThat(props.getAskuser().getTimeoutSeconds()).isEqualTo(42L);
    }

    @Test
    void askuserTimeoutSecondsDefaultsTo300WhenUnset() {
        MockEnvironment env = new MockEnvironment();
        LoomAgentProperties props =
                new LoomAgentConfiguration.InfrastructureConfiguration().loomAgentProperties(env);
        assertThat(props.getAskuser().getTimeoutSeconds()).isEqualTo(300L);
    }
}
