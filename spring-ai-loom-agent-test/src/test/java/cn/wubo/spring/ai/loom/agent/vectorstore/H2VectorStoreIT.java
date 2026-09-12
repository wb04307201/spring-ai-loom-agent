package cn.wubo.spring.ai.loom.agent.vectorstore;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #3 IT:loom_vector_store 表由 V1.0 Flyway 真建;H2JVectorStore + reloader
 * 对着真实应用 DataSource 手动接线(mock VectorStore 退位真实 bean,避免
 * DashScope 网络调用 —— ChatTest/SubTask IT 同款先例)。
 */
@MockBean(VectorStore.class)
@SpringBootTest(classes = LoomAgentTestApplication.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:file:./target/test-ds/db;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE",
        "spring.ai.loom.agent.users-base-path=./target/test-users"
})
class H2VectorStoreIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private ItFakeEmbeddingModel fake;
    private H2JVectorStore store;
    private H2VectorStoreReloader reloader;

    /** IT 自带确定性 fake(不依赖库模块 test-jar):hash 播种,同文本恒等向量。 */
    static class ItFakeEmbeddingModel implements EmbeddingModel {
        private final int dim;
        private final AtomicInteger calls = new AtomicInteger();

        ItFakeEmbeddingModel(int dim) {
            this.dim = dim;
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public float[] embed(String text) {
            calls.incrementAndGet();
            float[] v = new float[dim];
            java.util.Random rnd = new java.util.Random(text == null ? 0L : text.hashCode());
            for (int i = 0; i < dim; i++) {
                v[i] = rnd.nextFloat() * 2f - 1f;
            }
            return v;
        }

        @Override
        public int dimensions() {
            return dim;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException();
        }

        int embedCount() {
            return calls.get();
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<VectorStore> providerOf(VectorStore s) {
        ObjectProvider<VectorStore> p = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(p.getIfAvailable()).thenReturn(s);
        return p;
    }

    @BeforeEach
    void wireFixtures() {
        fake = new ItFakeEmbeddingModel(16);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        store = H2JVectorStore.builder(fake)
                .jdbcTemplate(jdbc)
                .transactionTemplate(tx)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
        reloader = new H2VectorStoreReloader(jdbc, providerOf(store));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM loom_vector_store WHERE document_id LIKE 'h2vit-%'");
    }

    @Test
    void flywayCreatedLoomVectorStoreTable() {
        Integer cols = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name='LOOM_VECTOR_STORE'",
                Integer.class);
        assertNotNull(cols);
        assertTrue(cols >= 7, "loom_vector_store 应由 V1.0 Flyway 建表(>=7 列)");
    }

    @Test
    void addPersistsToRealFlywayTable() {
        store.add(List.of(Document.builder()
                .id("h2vit-" + System.nanoTime()).text("hello h2 vector")
                .metadata(Map.of("type", "knowledge", "knowledgeId", "kb-it"))
                .build()));
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM loom_vector_store WHERE document_id LIKE 'h2vit-%' AND dim=16",
                Integer.class);
        assertEquals(1, n);
    }

    @Test
    void reloaderHydratesSecondStoreWithoutReEmbedding() {
        String id = "h2vit-" + System.nanoTime();
        store.add(List.of(Document.builder().id(id).text("restart simulation payload")
                .metadata(Map.of("type", "knowledge", "knowledgeId", "kb-it"))
                .build()));
        int afterAdd = fake.embedCount();

        // 模拟重启:新实例(空图)+ reloader 从真表 hydrate
        H2JVectorStore second = H2JVectorStore.builder(fake)
                .jdbcTemplate(jdbc)
                .transactionTemplate(new TransactionTemplate(new DataSourceTransactionManager(dataSource)))
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
        new H2VectorStoreReloader(jdbc, providerOf(second)).hydrateNow();

        assertEquals(afterAdd, fake.embedCount(), "hydrate 不得重调 embedding(no-re-embed 核心收益)");
        List<Document> hits = second.similaritySearch(
                SearchRequest.builder().query("restart simulation payload").topK(1).build());
        assertFalse(hits.isEmpty(), "hydrate 后应能搜到重启前写入的文档");
        assertEquals(id, hits.get(0).getId());
    }
}
