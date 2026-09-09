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

    @Test
    void renderChromiumPathAndTimeoutFromEnvironmentAreBound() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.ai.loom.agent.render.chromium-path", "/usr/bin/chromium-browser");
        env.setProperty("spring.ai.loom.agent.render.timeout-seconds", "77");
        LoomAgentProperties props =
                new LoomAgentConfiguration.InfrastructureConfiguration().loomAgentProperties(env);
        assertThat(props.getRender().getChromiumPath()).isEqualTo("/usr/bin/chromium-browser");
        assertThat(props.getRender().getTimeoutSeconds()).isEqualTo(77);
    }

    @Test
    void renderDefaultsSurviveManualCopy() {
        // 守卫 2bd0b5d 类缺陷:loomAgentProperties 手动拷贝块漏 setRender() 时,
        // bound 实例的 render 会被丢掉,返回默认实例 —— 默认值断言能过但覆盖值会丢,
        // 所以上面那个用例是主守卫,这里锁默认值本身。
        MockEnvironment env = new MockEnvironment();
        LoomAgentProperties props =
                new LoomAgentConfiguration.InfrastructureConfiguration().loomAgentProperties(env);
        assertThat(props.getRender()).isNotNull();
        assertThat(props.getRender().getChromiumPath()).isNull();
        assertThat(props.getRender().getDeviceScaleFactor()).isEqualTo(2);
        assertThat(props.getRender().getTimeoutSeconds()).isEqualTo(30);
        assertThat(props.getRender().getRenderWaitMs()).isEqualTo(1500);
        assertThat(props.getRender().isNetworkBlocked()).isTrue();
        assertThat(props.getRender().getMaxHtmlBytes()).isEqualTo(2L * 1024 * 1024);
    }
}
