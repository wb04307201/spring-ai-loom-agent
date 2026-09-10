package cn.wubo.spring.ai.loom.agent.rag;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.file.IUpload;
import cn.wubo.spring.ai.loom.agent.tool.knowledge.IKnowledgeTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认配置(RAG on)下完整上下文必须建出 RAG 链 —— 回归锁:
 * h2VectorStore 的 embedding 守卫若在真实 auto-config 顺序下评估过早
 * (消费方与库共享根包 → 嵌套 @Configuration 被组件扫描提前注册,deferred
 * DashScope embedding 定义尚未到位),VectorStore 会被静默跳过,默认部署的知识空间
 * 整体失效(2026-09-10 活体冒烟抓到的真实回归;EmbeddingModelAvailableCondition
 * 顺序无关判定即为其修复)。本 IT 用完整 {@link LoomAgentTestApplication} 上下文
 * (含真实组件扫描路径)证明默认配置下全链在场。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:file:./target/test-ds/db;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE",
        "spring.ai.loom.agent.file-base-path=./target/test-file-base",
        "spring.ai.loom.agent.knowledge-base-path=./target/test-knowledge-base"
})
@DisplayName("默认配置 RAG 链在场 IT")
class RagChainPresenceIT {

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("EmbeddingModel 在场 → VectorStore/IUpload/IKnowledgeTool 全链创建")
    void defaultConfigBuildsFullRagChain() {
        assertThat(ctx.getBeanNamesForType(EmbeddingModel.class))
                .as("测试 app 配置了 dashscope embedding,EmbeddingModel bean 必须在场")
                .isNotEmpty();
        assertThat(ctx.getBeanNamesForType(VectorStore.class))
                .as("默认 rag.enabled=true + EmbeddingModel 在场 → h2VectorStore 必须创建")
                .isNotEmpty();
        assertThat(ctx.getBeanNamesForType(IUpload.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(IKnowledgeTool.class)).isNotEmpty();
    }
}
