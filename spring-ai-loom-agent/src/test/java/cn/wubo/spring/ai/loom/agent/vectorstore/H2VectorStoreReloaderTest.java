package cn.wubo.spring.ai.loom.agent.vectorstore;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class H2VectorStoreReloaderTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<VectorStore> providerOf(VectorStore store) {
        ObjectProvider<VectorStore> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(store);
        return p;
    }

    @Test
    void hydrateNowIsNoOpWhenStoreIsNotH2JVectorStore() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VectorStore custom = mock(VectorStore.class);
        H2VectorStoreReloader reloader = new H2VectorStoreReloader(jdbc, providerOf(custom));

        reloader.hydrateNow();   // 用户替换了 VectorStore → 必须静默短路(spec §6 末行)

        verify(jdbc, never()).query(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<H2VectorRow>>any());
    }

    @Test
    void hydrateNowIsNoOpWhenStoreBeanAbsent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        H2VectorStoreReloader reloader = new H2VectorStoreReloader(jdbc, providerOf(null));

        reloader.hydrateNow();   // RagConfiguration 未激活(无 EmbeddingModel)→ 无 VectorStore bean

        verify(jdbc, never()).query(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<H2VectorRow>>any());
    }

    @Test
    void hydrateNowQueriesTableWhenStoreIsH2() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:reloader-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE loom_vector_store (
                  document_id VARCHAR(64) PRIMARY KEY, content CLOB NOT NULL,
                  metadata_json CLOB NOT NULL, embedding BLOB NOT NULL,
                  dim INT NOT NULL, score DOUBLE,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        FakeEmbeddingModel fake = new FakeEmbeddingModel(4);
        H2JVectorStore store = H2JVectorStore.builder(fake)
                .jdbcTemplate(jdbc)
                .transactionTemplate(new TransactionTemplate(new DataSourceTransactionManager(ds)))
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
        H2VectorStoreReloader reloader = new H2VectorStoreReloader(jdbc, providerOf(store));

        reloader.hydrateNow();   // 空表 → 正常完成,无异常
    }
}
