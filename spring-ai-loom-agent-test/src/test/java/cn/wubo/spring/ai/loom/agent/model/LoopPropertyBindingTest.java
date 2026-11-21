package cn.wubo.spring.ai.loom.agent.model;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 2(agentic-loop-steering):loop 护栏配置键。
 * 默认值:maxContinuations=10、runTimeoutSeconds=1800(spec 护栏,无 enabled 开关)。
 * binder 用例与 LoomAgentConfiguration#loomAgentProperties 相同机制
 * (Binder.bind("spring.ai.loom.agent", LoomAgentProperties.class))锁 yml 键
 * {@code spring.ai.loom.agent.loop.*} 的 kebab-case 绑定。
 */
class LoopPropertyBindingTest {

    @Test
    void defaults() {
        LoomAgentProperties p = new LoomAgentProperties();
        assertThat(p.getLoop()).isNotNull();
        assertThat(p.getLoop().getMaxContinuations()).isEqualTo(10);
        assertThat(p.getLoop().getRunTimeoutSeconds()).isEqualTo(1800);
    }

    @Test
    void binder_reads_loop_keys() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "spring.ai.loom.agent.loop.max-continuations", "3",
                "spring.ai.loom.agent.loop.run-timeout-seconds", "60"));
        LoomAgentProperties bound = new Binder(source)
                .bind("spring.ai.loom.agent", LoomAgentProperties.class)
                .get();
        assertThat(bound.getLoop().getMaxContinuations()).isEqualTo(3);
        assertThat(bound.getLoop().getRunTimeoutSeconds()).isEqualTo(60);
    }

    @Test
    void loop_defaults_survive_when_other_keys_bound() {
        // 守卫 binder 局部绑定:配置了别的段(loop 未配)时 loop 仍为默认实例,
        // 不为 null(loop 键在 bound 实例上是新对象,LoomAgentProperties 字段默认值兜底)
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "spring.ai.loom.agent.chat.timeout-seconds", "120"));
        LoomAgentProperties bound = new Binder(source)
                .bind("spring.ai.loom.agent", LoomAgentProperties.class)
                .get();
        assertThat(bound.getLoop()).isNotNull();
        assertThat(bound.getLoop().getMaxContinuations()).isEqualTo(10);
        assertThat(bound.getLoop().getRunTimeoutSeconds()).isEqualTo(1800);
        assertThat(bound.getChat().getTimeoutSeconds()).isEqualTo(120L);
    }
}
