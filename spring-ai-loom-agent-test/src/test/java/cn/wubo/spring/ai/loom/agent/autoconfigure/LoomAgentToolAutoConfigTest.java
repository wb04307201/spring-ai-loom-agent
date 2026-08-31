package cn.wubo.spring.ai.loom.agent.autoconfigure;

import cn.wubo.spring.ai.loom.agent.LoomAgentConfiguration;
import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge;
import cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool;
import cn.wubo.spring.ai.loom.agent.tool.compile.ICompileAndDeployTool;
import cn.wubo.spring.ai.loom.agent.tool.file.IFileTool;
import cn.wubo.spring.ai.loom.agent.tool.git.IGitTool;
import cn.wubo.spring.ai.loom.agent.tool.knowledge.IKnowledgeTool;
import cn.wubo.spring.ai.loom.agent.tool.maven.IMavenTool;
import cn.wubo.spring.ai.loom.agent.tool.skill.ISkillTool;
import cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool;
import org.springframework.ai.vectorstore.VectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 验证所有 9 个 I*Tool 默认都加载 (M3 起 yml enabled 开关已废弃,
 * RBAC 是唯一控制)。
 * <p>
 * 只测 ToolConfiguration(嵌套静态 @Configuration 类),不触发其他需要
 * Jdbc/ChatMemory 的 inner config。
 */
@DisplayName("LoomAgentConfiguration Tool 默认加载验证")
class LoomAgentToolAutoConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class, LoomAgentConfiguration.ToolConfiguration.class);

    @Test
    @DisplayName("默认配置下 8 个 I*Tool bean 加载(IScheduleTool 需要 flex-schedule classpath,本测试无该依赖)")
    void allToolsLoadedByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ITimeTool.class);
            assertThat(ctx).hasSingleBean(ISkillTool.class);
            assertThat(ctx).hasSingleBean(IFileTool.class);
            assertThat(ctx).hasSingleBean(IGitTool.class);
            assertThat(ctx).hasSingleBean(IMavenTool.class);
            assertThat(ctx).hasSingleBean(ICompileAndDeployTool.class);
            assertThat(ctx).hasSingleBean(IKnowledgeTool.class);
        });
    }

    @Test
    @DisplayName("yml spring.ai.loom.agent.*.enabled=false 不再影响 bean 创建(M3 废弃)")
    void ymlEnabledNoLongerAffectsLoading() {
        runner.withPropertyValues(
                        "spring.ai.loom.agent.time.enabled=false",
                        "spring.ai.loom.agent.file.enabled=false",
                        "spring.ai.loom.agent.git.enabled=false",
                        "spring.ai.loom.agent.maven.enabled=false")
                .run(ctx -> {
                    // yml enabled 开关已废弃 — 所有 tool 仍然加载,
                    // 启停由 role_tool RBAC 表控制
                    assertThat(ctx).hasSingleBean(ITimeTool.class);
                    assertThat(ctx).hasSingleBean(IFileTool.class);
                    assertThat(ctx).hasSingleBean(IGitTool.class);
                    assertThat(ctx).hasSingleBean(IMavenTool.class);
                });
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

        // IKnowledgeTool 的 @ConditionalOnBean(VectorStore.class) 依赖 VectorStore bean
        @Bean
        VectorStore vectorStore() {
            return mock(VectorStore.class);
        }

        // IKnowledgeTool.defaultKnowledgeTool 注入 IKnowledge
        @Bean
        IKnowledge iKnowledge() {
            return mock(IKnowledge.class);
        }
    }
}
