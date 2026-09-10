package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.document.IDocumentRead;
import cn.wubo.spring.ai.loom.agent.document.IFileDocument;
import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.file.IFileStorage;
import cn.wubo.spring.ai.loom.agent.file.IUpload;
import cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagConfiguration 优雅降级切片(知识空间全局关闭开关,方案 A+B)。
 * <p>
 * 锁定三个行为:
 * <ol>
 *   <li><b>崩溃防护(方案 B)</b>:容器无 EmbeddingModel bean(如
 *       {@code spring.ai.model.embedding.text=none})时,h2VectorStore 因
 *       {@code @ConditionalOnBean(EmbeddingModel.class)} 整体跳过,上下文正常启动
 *       —— 修复前该场景 NoSuchBeanDefinition 直接启动失败;</li>
 *   <li><b>全局开关(方案 A)</b>:{@code spring.ai.loom.agent.rag.enabled=false}
 *       时即使 EmbeddingModel 在场,RAG 链(VectorStore/IUpload/IDocumentRead)
 *       也整段不激活;</li>
 *   <li><b>正向对照</b>:默认(不配置)+ EmbeddingModel 在场 → 链完整创建。
 *       守卫 @ConditionalOnProperty 的 matchIfMissing=true 语义(键名笔误会
 *       静默把所有人的 RAG 关掉,此用例即回归锁)。</li>
 * </ol>
 * 注:RagConfiguration 是包私有嵌套类,本测试须与其同包。
 */
@DisplayName("RagConfiguration 降级切片(rag.enabled 开关 + EmbeddingModel 缺席防护)")
class RagConfigurationSliceTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class, LoomAgentConfiguration.RagConfiguration.class);

    private static EmbeddingModel embeddingModel() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.dimensions()).thenReturn(1024);
        return model;
    }

    @Test
    @DisplayName("无 EmbeddingModel bean → 上下文正常启动,VectorStore/IUpload/IDocumentRead 全部缺席")
    void noEmbeddingModel_contextStartsWithoutVectorStoreChain() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(VectorStore.class);
            assertThat(ctx).doesNotHaveBean(IUpload.class);
            assertThat(ctx).doesNotHaveBean(IDocumentRead.class);
        });
    }

    @Test
    @DisplayName("rag.enabled=false → 即使 EmbeddingModel 在场,RAG 链整段不激活")
    void ragDisabled_skipsWholeChainEvenWithEmbeddingModel() {
        runner.withPropertyValues("spring.ai.loom.agent.rag.enabled=false")
                .withBean(EmbeddingModel.class, RagConfigurationSliceTest::embeddingModel)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(VectorStore.class);
                    assertThat(ctx).doesNotHaveBean(IUpload.class);
                    assertThat(ctx).doesNotHaveBean(IDocumentRead.class);
                });
    }

    @Test
    @DisplayName("默认(不配置)+ EmbeddingModel 在场 → VectorStore/IUpload/IDocumentRead 链完整创建")
    void defaultWithEmbeddingModel_createsFullChain() {
        runner.withBean(EmbeddingModel.class, RagConfigurationSliceTest::embeddingModel)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(VectorStore.class);
                    assertThat(ctx).hasSingleBean(IUpload.class);
                    assertThat(ctx).hasSingleBean(IDocumentRead.class);
                });
    }

    @Test
    @DisplayName("rag.enabled=true 显式配置 → 与默认等价(链创建)")
    void ragEnabledExplicitly_createsChain() {
        runner.withPropertyValues("spring.ai.loom.agent.rag.enabled=true")
                .withBean(EmbeddingModel.class, RagConfigurationSliceTest::embeddingModel)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(VectorStore.class);
                });
    }

    /** RagConfiguration 四个 bean 方法的全部协作者(mock;正向用例才会真正消费)。 */
    @Configuration
    static class TestConfig {
        @Bean
        LoomAgentProperties loomAgentProperties() {
            return new LoomAgentProperties();
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        IFile iFile() {
            return mock(IFile.class);
        }

        @Bean
        IFileDocument iFileDocument() {
            return mock(IFileDocument.class);
        }

        @Bean
        IKnowledge iKnowledge() {
            return mock(IKnowledge.class);
        }

        @Bean
        IFileStorage iFileStorage() {
            return mock(IFileStorage.class);
        }
    }
}
