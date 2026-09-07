package cn.wubo.spring.ai.loom.agent.vectorstore;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class H2JVectorStoreTest {

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private FakeEmbeddingModel fake;

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS loom_vector_store (
              document_id   VARCHAR(64) PRIMARY KEY,
              content       CLOB NOT NULL,
              metadata_json CLOB NOT NULL,
              embedding     BLOB NOT NULL,
              dim           INT NOT NULL,
              score         DOUBLE,
              created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:vecstore-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(DDL);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        fake = new FakeEmbeddingModel(4);
    }

    private H2JVectorStore newStore() {
        return H2JVectorStore.builder(fake)
                .jdbcTemplate(jdbc)
                .transactionTemplate(tx)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    private static Document doc(String id, String text, String kbId) {
        return Document.builder().id(id).text(text)
                .metadata(Map.of("type", "knowledge", "knowledgeId", kbId))
                .build();
    }

    private List<H2VectorRow> readAllRows() {
        return jdbc.query("SELECT * FROM loom_vector_store", (rs, n) -> new H2VectorRow(
                rs.getString("document_id"),
                rs.getString("content"),
                rs.getString("metadata_json"),
                rs.getBytes("embedding"),
                rs.getInt("dim"),
                rs.getObject("score") == null ? null : ((Number) rs.getObject("score")).doubleValue()));
    }

    @Test
    void addPersistsRowWithDimAndDecodableEmbedding() {
        H2JVectorStore store = newStore();
        store.add(List.of(doc("d1", "hello world", "kb-1")));

        List<H2VectorRow> rows = readAllRows();
        assertThat(rows).hasSize(1);
        H2VectorRow row = rows.get(0);
        assertThat(row.documentId()).isEqualTo("d1");
        assertThat(row.content()).isEqualTo("hello world");
        assertThat(row.dim()).isEqualTo(4);
        assertThat(row.metadataJson()).contains("kb-1");
        // FakeEmbeddingModel 确定性 → 解码结果与重算逐位相等(containsExactly(float[]) 走 varargs 展开,不带 Offset)
        assertThat(VectorRowCodec.decodeEmbedding(row.embedding()))
                .containsExactly(fake.embed("hello world"));
    }

    @Test
    void hydrateRebuildsIndexWithoutReEmbedding() {
        H2JVectorStore first = newStore();
        first.add(List.of(doc("d1", "alpha text", "kb-1"), doc("d2", "beta text", "kb-1")));
        int afterAdd = fake.embedCount();

        H2JVectorStore second = newStore();
        second.hydrate(readAllRows());

        assertThat(fake.embedCount()).isEqualTo(afterAdd);   // no-re-embed 核心断言
        List<Document> hits = second.similaritySearch(
                SearchRequest.builder().query("alpha text").topK(1).build());
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getId()).isEqualTo("d1");
    }

    @Test
    void hydrateSkipsRowsWithMismatchedDim() {
        H2JVectorStore store = newStore();
        store.add(List.of(doc("d1", "good", "kb-1")));
        jdbc.update("INSERT INTO loom_vector_store (document_id, content, metadata_json, embedding, dim)"
                        + " VALUES ('d2', 'bad-dim', '{}', ?, 768)",
                VectorRowCodec.encodeEmbedding(new float[768]));

        store.hydrate(readAllRows());

        List<Document> hits = store.similaritySearch(
                SearchRequest.builder().query("good").topK(5).build());
        assertThat(hits).extracting(Document::getId).contains("d1").doesNotContain("d2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loom_vector_store", Integer.class)).isEqualTo(2);
    }

    @Test
    void hydrateSkipsPoisonMetadataRow() {
        H2JVectorStore store = newStore();
        store.add(List.of(doc("d1", "good", "kb-1")));
        jdbc.update("INSERT INTO loom_vector_store (document_id, content, metadata_json, embedding, dim)"
                        + " VALUES ('d2', 'poison', 'not-json{{{', ?, 4)",
                VectorRowCodec.encodeEmbedding(new float[4]));

        store.hydrate(readAllRows());

        List<Document> hits = store.similaritySearch(
                SearchRequest.builder().query("good").topK(5).build());
        assertThat(hits).extracting(Document::getId).contains("d1").doesNotContain("d2");
    }

    @Test
    void deleteByIdsRemovesDbRowsAndMemory() {
        H2JVectorStore store = newStore();
        store.add(List.of(doc("d1", "aaa", "kb-1"), doc("d2", "bbb", "kb-1")));
        store.delete(List.of("d1"));
        assertThat(readAllRows()).extracting(H2VectorRow::documentId).containsExactly("d2");
        assertThat(store.similaritySearch(
                SearchRequest.builder().query("aaa").topK(5).build()))
                .extracting(Document::getId).doesNotContain("d1");
    }

    @Test
    void deleteByFilterRemovesMatchingRows() {
        H2JVectorStore store = newStore();
        store.add(List.of(doc("d1", "aaa", "kb-1"), doc("d2", "bbb", "kb-2")));
        Filter.Expression expr = new FilterExpressionBuilder().eq("knowledgeId", "kb-1").build();
        store.delete(expr);
        assertThat(readAllRows()).extracting(H2VectorRow::documentId).containsExactly("d2");
    }

    @Test
    void reAddSameIdMergesIntoSingleRow() {
        H2JVectorStore store = newStore();
        store.add(List.of(doc("d1", "v1", "kb-1")));
        store.add(List.of(doc("d1", "v2", "kb-1")));
        List<H2VectorRow> rows = readAllRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).content()).isEqualTo("v2");
        List<Document> hits = store.similaritySearch(
                SearchRequest.builder().query("v2").topK(5).build());
        assertThat(hits).hasSize(1);
    }

    @Test
    void dbFailureOnAddLeavesMemoryUntouchedAndThrows() {
        H2JVectorStore store = newStore();
        store.add(List.of(doc("d1", "aaa", "kb-1")));

        JdbcTemplate brokenJdbc = mock(JdbcTemplate.class);
        when(brokenJdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        H2JVectorStore broken = H2JVectorStore.builder(fake)
                .jdbcTemplate(brokenJdbc)
                .transactionTemplate(new TransactionTemplate(mock(org.springframework.transaction.PlatformTransactionManager.class)))
                .observationRegistry(ObservationRegistry.NOOP)
                .build();

        assertThatThrownBy(() -> broken.add(List.of(doc("d2", "bbb", "kb-1"))))
                .isInstanceOf(DataAccessResourceFailureException.class);
        // 内存无幽灵行:hydrate 前搜索为空
        assertThat(broken.similaritySearch(
                SearchRequest.builder().query("bbb").topK(5).build())).isEmpty();
    }

    @Test
    void searchBeforeHydrateReturnsEmpty() {
        H2JVectorStore store = newStore();
        store.add(List.of(doc("d1", "aaa", "kb-1")));

        H2JVectorStore fresh = newStore();   // 模拟重启后、hydrate 前
        assertThat(fresh.similaritySearch(
                SearchRequest.builder().query("aaa").topK(5).build())).isEmpty();
    }
}
