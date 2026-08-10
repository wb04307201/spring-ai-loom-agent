package cn.wubo.spring.ai.loom.agent.autoconfigure;

import cn.wubo.spring.ai.loom.agent.LoomAgentConfiguration;
import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import cn.wubo.spring.ai.loom.agent.tool.file.IFileTool;
import cn.wubo.spring.ai.loom.agent.tool.git.IGitTool;
import cn.wubo.spring.ai.loom.agent.tool.maven.IMavenTool;
import cn.wubo.spring.ai.loom.agent.tool.skill.ISkillTool;
import cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 验证所有 tool 默认都加载，且用户可通过 yml 关闭。
 * <p>
 * 只测 ToolConfiguration（嵌套静态 @Configuration 类），不触发其他需要 Jdbc/ChatMemory
 * 的 inner config。
 */
@DisplayName("LoomAgentConfiguration Tool 默认加载验证")
class LoomAgentToolAutoConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class, LoomAgentConfiguration.ToolConfiguration.class);

    @Test
    @DisplayName("默认配置下常用 tool bean 加载（git/maven 是 opt-in，默认不加载）")
    void allToolsLoadedByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ITimeTool.class);
            assertThat(ctx).hasSingleBean(ISkillTool.class);
            assertThat(ctx).hasSingleBean(IFileTool.class);
            // IGitTool / IMavenTool 是 opt-in（默认 false），应由显式 enabled=true 启用
            assertThat(ctx).doesNotHaveBean(IGitTool.class);
            assertThat(ctx).doesNotHaveBean(IMavenTool.class);
        });
    }

    @Test
    @DisplayName("yml spring.ai.loom.agent.git.enabled=false 时 IGitTool 不加载")
    void gitDisabledByConfig() {
        runner.withPropertyValues("spring.ai.loom.agent.git.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(IGitTool.class);
                    // 其他 tool 仍加载
                    assertThat(ctx).hasSingleBean(ITimeTool.class);
                    assertThat(ctx).hasSingleBean(IFileTool.class);
                });
    }

    @Test
    @DisplayName("yml spring.ai.loom.agent.maven.enabled=false 时 IMavenTool 不加载")
    void mavenDisabledByConfig() {
        // 默认 git/maven 都是 opt-in（默认 false），这里显式开 git 以验证 maven 关闭仍能加载 git
        runner.withPropertyValues(
                        "spring.ai.loom.agent.maven.enabled=false",
                        "spring.ai.loom.agent.git.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(IMavenTool.class);
                    assertThat(ctx).hasSingleBean(IGitTool.class);
                });
    }

    @Test
    @DisplayName("yml spring.ai.loom.agent.time.enabled=false 时 ITimeTool 不加载")
    void timeDisabledByConfig() {
        runner.withPropertyValues("spring.ai.loom.agent.time.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ITimeTool.class));
    }

    @Test
    @DisplayName("yml spring.ai.loom.agent.file.enabled=false 时 IFileTool 不加载")
    void fileDisabledByConfig() {
        runner.withPropertyValues("spring.ai.loom.agent.file.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(IFileTool.class));
    }

    @Test
    @DisplayName("yml spring.ai.loom.agent.skill.enabled=false 时 ISkillTool 不加载")
    void skillDisabledByConfig() {
        runner.withPropertyValues("spring.ai.loom.agent.skill.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ISkillTool.class));
    }

    @Configuration
    static class TestConfig {
        @Bean
        LoomAgentProperties loomAgentProperties() {
            return new LoomAgentProperties();
        }

        @Bean
        IFile iFile() {
            return mock(IFile.class);
        }

        @Bean
        ISkillStorage iSkillStorage() {
            return mock(ISkillStorage.class);
        }
    }
}
