# H2 向量存储(#3)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把默认向量存储从"json 文件持久化 + 启动全量 re-embed"换成"H2 表 `loom_vector_store` 持久化 + `ApplicationReadyEvent` hydrate 内存 HNSW 图",搜索路径零改动。

**Architecture:** 新类 `H2JVectorStore`(内存结构与搜索路径照搬 `JVectorStore`,持久化后端换 H2;写/删 **DB 先行**)+ `VectorRowCodec`(float[]↔BLOB little-endian + metadata JSON)+ `H2VectorStoreReloader`(`ApplicationReadyEvent` 全量读表、dim 守卫、hydrate)。退役 `JVectorStore` 类与 `jvector.indexPath` 属性;`~/.loom/jvector-index/` 目录退役。接线仍 `@ConditionalOnMissingBean(VectorStore.class)`,下游零改动。

**Tech Stack:** Spring AI 1.1.8 `VectorStore`/`AbstractObservationVectorStore`、JVector HNSW(依赖保留)、Spring `JdbcTemplate`/`TransactionTemplate`、H2(BLOB)、Flyway V1.0 append、JUnit5 + Mockito + AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-07-h2-vector-store-design.md`(binding authority;决策 D1-D8 以 spec §1 为准)

## Global Constraints

- **local dev only**:不 push、不 merge、不 PR;全部落在 `dev` 分支。
- 每个 commit 以 `Co-Authored-By: Claude Code <noreply@anthropic.com>` 结尾;commit message 用 `feat:` / `test:` / `docs:` 前缀。
- **V1.0 append-only**:schema 改动只允许在 `spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql` 末尾追加(SQL 注释分段);**全新库政策** —— 跑 IT 前清库。
- **多模块 stale-JAR 陷阱**:库模块(`spring-ai-loom-agent` / `-spring-boot-autoconfigure` / `-spring-boot-starter`)源码改动后,**必须先** `mvn clean install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter -am -Dgpg.skip=true -DskipTests` **再**跑 test 模块的任何测试(否则 test 模块编译/运行用的是旧 JAR)。库模块自己的单测(`-pl spring-ai-loom-agent`)直接跑,无需 install。
- **IT 仪式**:从仓库根执行 `rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports`,然后 `mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false`(默认 surefire 不跑 `*IT`,必须显式指定)。
- **回归门三段式**(T5 末尾):库单元(`mvn test -pl spring-ai-loom-agent`)+ test 模块单元(383)+ IT gate(120,清库起跑)全绿。
- **编辑纪律**(post-#4 协议):Edit 前先 Read 精确区域;每次 Edit 后 grep 验证(新方法/新字段在文件中恰好出现一次);**永不 `git add -A`**,逐文件显式 add。
- **Javadoc 陷阱**:注释正文里不得出现 `*/` 字符序列(如 `user_*/role_*` 会提前闭合注释导致编译错)。
- 仓库行尾 CRLF(`core.autocrlf` 已配置):用 Read/Edit 工具改文件保留原行尾;**新建文件**用 LF 写入即可(git 会规范化)。
- `spring.ai.loom.agent.jvector.*` 配置节**保留节名**,只删 `indexPath`(spec D5)。
- 隔离模型不变(spec D1 档 A):新表**不加** username/knowledge_id 列。
- 概览图:**#3 不需要重生成概览图、不改 `.claude/skills/project-overview-image`**(spec §8 核实结论:8 技术栈胶囊仍全部准确)。

---

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/vectorstore/VectorRowCodec.java` | Create | float[]↔byte[] BLOB 编解码(little-endian)+ metadata Map↔JSON |
| `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/vectorstore/VectorRowCodecTest.java` | Create | codec 单测 |
| `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/vectorstore/FakeEmbeddingModel.java` | Create | 确定性 fake embedder(维度可配、调用计数、**覆盖 `dimensions()` 避免默认实现走网络**) |
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2VectorRow.java` | Create | `loom_vector_store` 行 record |
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2JVectorStore.java` | Create | VectorStore 实现(内存 HNSW + H2 持久化,DB 先行) |
| `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2JVectorStoreTest.java` | Create | store 单测(真 H2 内存库,~10 用例) |
| `spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql` | Modify(末尾追加) | `loom_vector_store` 表 |
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2VectorStoreReloader.java` | Create | `ApplicationReadyEvent` → hydrate;非 H2JVectorStore 实例短路 |
| `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2VectorStoreReloaderTest.java` | Create | reloader 短路分支单测 |
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java` | Modify | 删 `JVectorProperties.indexPath` |
| `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java` | Modify | `jVectorStore` bean → `h2VectorStore` + `h2VectorStoreReloader` bean |
| `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentPropertiesDefaultsTest.java` | Modify | 删 indexPath 断言 ×2 处 |
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/vectorstore/JVectorStore.java` | **Delete** | 旧实现退役 |
| `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2VectorStoreIT.java` | Create | IT:Flyway 建表 + DB 持久化 + 模拟重启 hydrate(no-re-embed) |
| `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/SubTaskAndScheduleHistoryIntegrationTest.java` | Modify | 删 no-op 属性 `spring.ai.vectorstore.jvector.auto-pull=false` + 其注释行 |
| CLAUDE.md / README×2 / API×2 / CUSTOMIZATION×2 / roadmap | Modify | 文档同步(T5) |

**任务依赖**:T1(codec+fake)→ T2(schema+store+单测)→ T3(reloader+接线+properties)→ T4(删旧类+IT)→ T5(文档+回归门)。

---

### Task 1: VectorRowCodec + FakeEmbeddingModel(测试基建)

**Files:**
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/vectorstore/VectorRowCodec.java`
- Test: `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/vectorstore/VectorRowCodecTest.java`
- Test: `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/vectorstore/FakeEmbeddingModel.java`

**Interfaces:**
- Consumes: 无(纯新代码)。
- Produces(T2/T4 依赖,签名逐字):
  - `public static byte[] VectorRowCodec.encodeEmbedding(float[] vector)`
  - `public static float[] VectorRowCodec.decodeEmbedding(byte[] bytes)`
  - `public static String VectorRowCodec.metadataToJson(Map<String, Object> metadata)`
  - `public static Map<String, Object> VectorRowCodec.metadataFromJson(String json)`
  - `public FakeEmbeddingModel(int dim)`;`public float[] embed(Document)`;`public float[] embed(String)`;`public int dimensions()`;`public int embedCount()`

- [ ] **Step 1: 写失败测试 `VectorRowCodecTest`**

```java
package cn.wubo.spring.ai.loom.agent.vectorstore;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VectorRowCodecTest {

    @Test
    void embeddingRoundTrip_isBitwiseIdentical() {
        float[] v = {0.0f, 1.5f, -2.25f, Float.MAX_VALUE, 1e-30f};
        // 确定性往返,逐位相等,无需容差(containsExactly 不接受 Offset 参数)
        assertThat(VectorRowCodec.decodeEmbedding(VectorRowCodec.encodeEmbedding(v)))
                .containsExactly(v);
    }

    @Test
    void encoding_isLittleEndianFourBytesPerDim() {
        byte[] bytes = VectorRowCodec.encodeEmbedding(new float[]{1.0f, -2.5f});
        assertThat(bytes).hasSize(8);
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(bb.getFloat(0)).isEqualTo(1.0f);
        assertThat(bb.getFloat(4)).isEqualTo(-2.5f);
    }

    @Test
    void metadataRoundTrip_preservesTypicalChunkKeys() {
        Map<String, Object> md = Map.of(
                "type", "knowledge",
                "knowledgeId", "kb-123",
                "doc_index", 3,
                "weight", 0.75);
        Map<String, Object> back = VectorRowCodec.metadataFromJson(VectorRowCodec.metadataToJson(md));
        assertThat(back)
                .containsEntry("type", "knowledge")
                .containsEntry("knowledgeId", "kb-123");
        assertThat(((Number) back.get("doc_index")).intValue()).isEqualTo(3);
        assertThat(((Number) back.get("weight")).doubleValue()).isEqualTo(0.75);
    }

    @Test
    void metadataFromJson_nullOrBlank_returnsEmptyMap() {
        assertThat(VectorRowCodec.metadataFromJson(null)).isEmpty();
        assertThat(VectorRowCodec.metadataFromJson("")).isEmpty();
    }

    @Test
    void metadataToJson_null_returnsEmptyJsonObject() {
        assertThat(VectorRowCodec.metadataToJson(null)).isEqualTo("{}");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run(仓库根):`mvn test -pl spring-ai-loom-agent -Dtest=VectorRowCodecTest`
Expected: **编译失败**(`VectorRowCodec` 不存在)。

- [ ] **Step 3: 实现 `VectorRowCodec`**

```java
package cn.wubo.spring.ai.loom.agent.vectorstore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/**
 * loom_vector_store 行字段的编解码器(单一职责,便于单测)。
 *
 * <p>embedding 序列化:little-endian,4 bytes/维(float32);无 JSON 开销。
 * 1024 维约 4KB/行。metadata 用 Jackson 序列化(与旧 docs.json 同族工具链)。
 */
public final class VectorRowCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private VectorRowCodec() {
    }

    public static byte[] encodeEmbedding(float[] vector) {
        ByteBuffer bb = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : vector) {
            bb.putFloat(f);
        }
        return bb.array();
    }

    public static float[] decodeEmbedding(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < out.length; i++) {
            out[i] = bb.getFloat();
        }
        return out;
    }

    public static String metadataToJson(Map<String, Object> metadata) {
        try {
            return MAPPER.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception e) {
            throw new IllegalStateException("metadata 序列化失败", e);
        }
    }

    public static Map<String, Object> metadataFromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            // 单行毒化不拖垮全量加载:调用方(hydrate)捕获后跳过该行
            throw new IllegalStateException("metadata 解析失败: " + e.getMessage(), e);
        }
    }
}
```

注意:`MAPPER.readValue` 可能返回 null(json 为字面量 `"null"` 时),保留 null 判断;不要留任何 unused import(编译警告级)。

- [ ] **Step 4: 跑测试确认通过**

Run:`mvn test -pl spring-ai-loom-agent -Dtest=VectorRowCodecTest`
Expected: **Tests run: 5, Failures: 0**。

- [ ] **Step 5: 创建 `FakeEmbeddingModel`(测试基建,T2/T4 复用)**

```java
package cn.wubo.spring.ai.loom.agent.vectorstore;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 确定性 fake embedder:hash 播种的伪随机向量,同文本恒等向量。
 *
 * <p>关键点:覆盖 dimensions() —— 接口 default 实现会调 embed("Test String")
 * 再走真实网络/模型链路;fake 必须短路。embedCount() 供 no-re-embed 断言
 * (hydrate 后计数不得增长)。
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    private final int dim;
    private final AtomicInteger embedCalls = new AtomicInteger();

    public FakeEmbeddingModel(int dim) {
        this.dim = dim;
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        embedCalls.incrementAndGet();
        float[] v = new float[dim];
        long seed = text == null ? 0L : text.hashCode();
        java.util.Random rnd = new java.util.Random(seed);
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
        throw new UnsupportedOperationException("fake 不支持批量 call");
    }

    public int embedCount() {
        return embedCalls.get();
    }
}
```

- [ ] **Step 6: 编译验证 fake(无独立测试,T2 起被消费)**

Run:`mvn test-compile -pl spring-ai-loom-agent`
Expected: BUILD SUCCESS。

- [ ] **Step 7: Commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/vectorstore/VectorRowCodec.java \
        spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/vectorstore/VectorRowCodecTest.java \
        spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/vectorstore/FakeEmbeddingModel.java
git commit -m "feat: VectorRowCodec (float[]<->BLOB little-endian + metadata JSON) + FakeEmbeddingModel test fixture

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: loom_vector_store 表 + H2JVectorStore + 单测

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql`(末尾追加,现 792 行,最后一行是 `loom_market_knowledge_archive` 的 `);`)
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2VectorRow.java`
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2JVectorStore.java`
- Test: `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2JVectorStoreTest.java`

**Interfaces:**
- Consumes: T1 的 `VectorRowCodec`(4 个 static 方法,签名见 T1)、`FakeEmbeddingModel(int dim)` / `embedCount()`。
- Produces(T3/T4 依赖,签名逐字):
  - `public record H2VectorRow(String documentId, String content, String metadataJson, byte[] embedding, int dim, Double score)`
  - `public static H2JVectorStore.Builder H2JVectorStore.builder(EmbeddingModel embeddingModel)`;Builder 方法:`jdbcTemplate(JdbcTemplate)` / `transactionTemplate(TransactionTemplate)` / `m(int)` / `efConstruction(int)` / `efSearch(int)` / `similarityFunction(VectorSimilarityFunction)` / `build()`
  - `public void H2JVectorStore.hydrate(List<H2VectorRow> rows)`
  - 继承 `AbstractObservationVectorStore` 的公开 `add(List<Document>)` / `delete(List<String>)` / `delete(Filter.Expression)` / `similaritySearch(SearchRequest)`

- [ ] **Step 1: V1.0 末尾追加表(先 Read 文件末尾 30 行确认追加点,再 Edit;append-only,不动已有内容)**

在 `V1.0__init.sql` 最后一行之后追加:

```sql

-- =============================================================
-- #3 H2 向量存储:H2JVectorStore 持久层(向量 + 文档 + metadata)
-- 替代旧 ~/.loom/jvector-index/{docs,ids}.json;启动 ApplicationReadyEvent
-- 时全量 hydrate 进内存 JVector HNSW 图,消灭启动 re-embed。
-- 不加 username/knowledge_id 列(隔离档 A:knowledgeId 在 metadata_json 内,
-- SpEL 后过滤照旧)。dim 列是换 embedding 模型守卫(不匹配行加载时跳过)。
-- =============================================================
CREATE TABLE IF NOT EXISTS loom_vector_store (
  document_id   VARCHAR(64) PRIMARY KEY,
  content       CLOB NOT NULL,
  metadata_json CLOB NOT NULL,
  embedding     BLOB NOT NULL,
  dim           INT NOT NULL,
  score         DOUBLE,
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_loom_vector_store_created ON loom_vector_store(created_at);
```

验证:`grep -c "loom_vector_store" spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql` → 恰好 **3**(CREATE TABLE 1 + CREATE INDEX 2)。

- [ ] **Step 2: 创建 `H2VectorRow`**

```java
package cn.wubo.spring.ai.loom.agent.vectorstore;

/**
 * loom_vector_store 一行(不含 created_at —— 运行时无消费方)。
 * score 可空,镜像旧 docs.json 的 score 字段。
 */
public record H2VectorRow(
        String documentId,
        String content,
        String metadataJson,
        byte[] embedding,
        int dim,
        Double score) {
}
```

- [ ] **Step 3: 写失败测试 `H2JVectorStoreTest`(真 H2 内存库,先例:`JdbcToolCallLogRepositoryTest`)**

```java
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
```

- [ ] **Step 4: 跑测试确认失败**

Run:`mvn test -pl spring-ai-loom-agent -Dtest=H2JVectorStoreTest`
Expected: **编译失败**(`H2JVectorStore` / `H2VectorRow` 不存在)。

- [ ] **Step 5: 实现 `H2JVectorStore`**

> 实现基线 = 现有 `JVectorStore.java`(同目录,先完整 Read 它):内存三件套(`documentStore` / `documentIds` / `embeddingMap`)、`rwLock`、`rebuildGraph()`、`createGraphBuilder()`、`toVectorFloat()`、`doSimilaritySearch` 主体、`cosineSimilarity()`、`matchesFilter()`、`getDocIdByNodeIndex()`、`closeOldGraphIndex()`、Builder 骨架 —— **逐字照搬**。以下是全部差异点,按此改写:

```java
package cn.wubo.spring.ai.loom.agent.vectorstore;

import io.github.jbellis.jvector.graph.*;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.observation.conventions.VectorStoreProvider;
import org.springframework.ai.observation.conventions.VectorStoreSimilarityMetric;
import org.springframework.ai.vectorstore.AbstractVectorStoreBuilder;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.converter.SimpleVectorStoreFilterExpressionConverter;
import org.springframework.ai.vectorstore.observation.AbstractObservationVectorStore;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * H2-backed VectorStore:内存 JVector HNSW 索引(搜索路径与旧 JVectorStore
 * 逐字节一致)+ H2 表 loom_vector_store 持久化(embedding BLOB 落盘,
 * 启动 hydrate 不再调 embedding API)。
 *
 * <p>生命周期(spec §5):
 * <ul>
 *   <li>构造:只建空内存图,不碰 DB(避开 Flyway 建表竞态,D7);</li>
 *   <li>ApplicationReadyEvent:H2VectorStoreReloader 全量读表 + dim 守卫(D8)
 *       + hydrate(rows) 重建 HNSW;hydrate 前搜索返回空列表 + WARN;</li>
 *   <li>写:doAdd 先 embed,再 DB 批 MERGE(TransactionTemplate 整批回滚),
 *       DB 成功后才改内存 + rebuildGraph(D6 DB 先行);</li>
 *   <li>删:先 DELETE FROM loom_vector_store,后清内存(DB 删 0 行幂等);</li>
 *   <li>搜索:零改动照搬旧实现 —— 不读 DB。</li>
 * </ul>
 */
public class H2JVectorStore extends AbstractObservationVectorStore {

    private static final Logger logger = LoggerFactory.getLogger(H2JVectorStore.class);
    private static final SimpleVectorStoreFilterExpressionConverter FILTER_CONVERTER =
            new SimpleVectorStoreFilterExpressionConverter();

    static final String UPSERT_SQL =
            "MERGE INTO loom_vector_store (document_id, content, metadata_json, embedding, dim, score) "
                    + "KEY(document_id) VALUES (?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int m;
    private final int efConstruction;
    private final int efSearch;
    private final VectorSimilarityFunction similarityFunction;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final ConcurrentHashMap<String, Document> documentStore = new ConcurrentHashMap<>();
    // Ordered list of document IDs -- index matches JVector graph node index
    private final List<String> documentIds = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, VectorFloat<?>> embeddingMap = new ConcurrentHashMap<>();
    private final ExpressionParser expressionParser;
    private final VectorizationProvider vectorizationProvider;
    private volatile List<VectorFloat<?>> currentVectors = List.of();
    @SuppressWarnings("rawtypes")
    private volatile GraphIndex graphIndex;
    // false until H2VectorStoreReloader.hydrate() ran once (D7 cold-start window)
    private volatile boolean hydrated = false;

    public H2JVectorStore(Builder builder) {
        super(builder);
        this.jdbcTemplate = builder.jdbcTemplate;
        this.transactionTemplate = builder.transactionTemplate;
        this.m = builder.m;
        this.efConstruction = builder.efConstruction;
        this.efSearch = builder.efSearch;
        this.similarityFunction = builder.similarityFunction;
        this.expressionParser = new SpelExpressionParser();
        this.vectorizationProvider = VectorizationProvider.getInstance();
        createNewIndex();
    }

    public static Builder builder(EmbeddingModel embeddingModel) {
        return new Builder(embeddingModel);
    }

    /* ===== hydrate(reloader 调用,D7/D8)===== */

    public void hydrate(List<H2VectorRow> rows) {
        rwLock.writeLock().lock();
        try {
            int expectedDim = embeddingModel.dimensions();
            int loaded = 0;
            AtomicInteger dimSkipped = new AtomicInteger();
            AtomicInteger poisonSkipped = new AtomicInteger();
            List<String> ids = new ArrayList<>();
            List<VectorFloat<?>> vectors = new ArrayList<>();
            for (H2VectorRow row : rows) {
                if (row.dim() != expectedDim) {
                    dimSkipped.incrementAndGet();
                    continue;
                }
                try {
                    Map<String, Object> metadata = VectorRowCodec.metadataFromJson(row.metadataJson());
                    Document.Builder docBuilder = Document.builder()
                            .id(row.documentId())
                            .text(row.content())
                            .metadata(metadata);
                    if (row.score() != null) {
                        docBuilder.score(row.score());
                    }
                    documentStore.put(row.documentId(), docBuilder.build());
                    ids.add(row.documentId());
                    vectors.add(toVectorFloat(VectorRowCodec.decodeEmbedding(row.embedding())));
                    loaded++;
                } catch (Exception e) {
                    poisonSkipped.incrementAndGet();
                    logger.warn("[H2Vector] skipping poison row documentId={}: {}",
                            row.documentId(), e.getMessage());
                }
            }
            documentIds.clear();
            documentIds.addAll(ids);
            embeddingMap.clear();
            for (int i = 0; i < ids.size(); i++) {
                embeddingMap.put(ids.get(i), vectors.get(i));
            }
            rebuildGraph();
            hydrated = true;
            logger.info("[H2Vector] hydrate done: loaded={}, dimSkipped={}, poisonSkipped={}",
                    loaded, dimSkipped.get(), poisonSkipped.get());
            if (dimSkipped.get() > 0) {
                logger.warn("[H2Vector] {} row(s) skipped for dim mismatch (expected {}) — "
                        + "embedding 模型已更换,需清库(删 loom_vector_store 行)并重传知识库文档",
                        dimSkipped.get(), expectedDim);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /* ===== 写 / 删(D6:DB 先行)===== */

    @Override
    public void doAdd(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        logger.info("[H2Vector] Adding {} documents", documents.size());
        // 1. embed(不持锁;网络调用)
        Map<String, float[]> embeddings = new LinkedHashMap<>();
        for (Document document : documents) {
            embeddings.put(document.getId(), embeddingModel.embed(document));
        }
        // 2. DB 批写 + 3. 内存变更,同一事务模板包裹;DB 失败整体抛出、内存不动
        rwLock.writeLock().lock();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                for (Document document : documents) {
                    float[] embedding = embeddings.get(document.getId());
                    jdbcTemplate.update(UPSERT_SQL,
                            document.getId(),
                            document.getText(),
                            VectorRowCodec.metadataToJson(document.getMetadata()),
                            VectorRowCodec.encodeEmbedding(embedding),
                            embedding.length,
                            document.getScore());
                }
            });
            for (Document document : documents) {
                VectorFloat<?> vf = toVectorFloat(embeddings.get(document.getId()));
                if (!documentStore.containsKey(document.getId())) {
                    documentIds.add(document.getId());
                }
                documentStore.put(document.getId(), document);
                embeddingMap.put(document.getId(), vf);
            }
            rebuildGraph();
            hydrated = true;   // 本会话写入后立即可搜,不等 reloader
            logger.info("[H2Vector] After add: totalDocs={}, documentIds={}, embeddingMap={}",
                    documentStore.size(), documentIds.size(), embeddingMap.size());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void doDelete(List<String> idList) {
        if (idList == null || idList.isEmpty()) {
            return;
        }
        rwLock.writeLock().lock();
        try {
            // DB 先行;删 0 行幂等不算错(spec §5.3)
            deleteRowsFromDb(idList);
            for (String id : idList) {
                documentStore.remove(id);
                embeddingMap.remove(id);
                documentIds.remove(id);
            }
            rebuildGraph();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    protected void doDelete(Filter.Expression filterExpression) {
        rwLock.writeLock().lock();
        try {
            List<String> idsToDelete = documentStore.keySet().stream()
                    .filter(id -> matchesFilter(documentStore.get(id), filterExpression))
                    .toList();
            if (!idsToDelete.isEmpty()) {
                deleteRowsFromDb(idsToDelete);
                for (String id : idsToDelete) {
                    documentStore.remove(id);
                    embeddingMap.remove(id);
                    documentIds.remove(id);
                }
                rebuildGraph();
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** 多 id 删除:动态拼 IN (?,?,...) 占位符(单占位符 = ? 传数组会参数数不匹配)。 */
    private void deleteRowsFromDb(List<String> ids) {
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        jdbcTemplate.update(
                "DELETE FROM loom_vector_store WHERE document_id IN (" + placeholders + ")",
                ids.toArray());
    }

    /* ===== 搜索(零改动照搬,加 hydrate 守卫)===== */

    @Override
    public List<Document> doSimilaritySearch(SearchRequest request) {
        rwLock.readLock().lock();
        try {
            if (!hydrated) {
                logger.warn("[H2Vector] index not hydrated yet (cold-start window before "
                        + "ApplicationReadyEvent) — returning empty results");
                return Collections.emptyList();
            }
            logger.info("[H2Vector] Search query='{}', topK={}, threshold={}, filter={}",
                    request.getQuery(), request.getTopK(), request.getSimilarityThreshold(),
                    request.hasFilterExpression() ? request.getFilterExpression() : "none");
            logger.info("[H2Vector] Index state: totalDocs={}, documentIds.size={}, embeddingMap.size={}, graphIndex={}",
                    documentStore.size(), documentIds.size(), embeddingMap.size(),
                    graphIndex == null ? "null" : "size=" + graphIndex.size());

            float[] queryEmbedding = embeddingModel.embed(request.getQuery());

            if (graphIndex == null || documentIds.isEmpty()) {
                logger.warn("[H2Vector] Empty index - returning empty results");
                return Collections.emptyList();
            }

            VectorFloat<?> queryVector = toVectorFloat(queryEmbedding);

            SearchResult result;
            try {
                // GraphSearcher.search() 3rd param is RandomAccessVectorValues (NOT acceptOrds)
                ListRandomAccessVectorValues ravv = new ListRandomAccessVectorValues(currentVectors, embeddingModel.dimensions());
                result = GraphSearcher.search(queryVector, request.getTopK() * 2,
                        ravv, similarityFunction, graphIndex, Bits.ALL);
                logger.info("[H2Vector] HNSW search returned {} candidates", result.getNodes().length);
            } catch (Exception e) {
                logger.error("Error during H2Vector search", e);
                return Collections.emptyList();
            }

            List<Document> results = new ArrayList<>();
            int filteredCount = 0;
            int belowThresholdCount = 0;
            for (SearchResult.NodeScore nodeScore : result.getNodes()) {
                String docId = getDocIdByNodeIndex(nodeScore.node);
                if (docId != null) {
                    Document doc = documentStore.get(docId);
                    if (doc != null) {
                        VectorFloat<?> docEmbedding = embeddingMap.get(docId);
                        if (docEmbedding != null) {
                            double score = cosineSimilarity(queryEmbedding, docEmbedding);
                            boolean passedFilter = !request.hasFilterExpression() || matchesFilter(doc, request.getFilterExpression());
                            if (!passedFilter) {
                                filteredCount++;
                                logger.debug("[H2Vector] docId={} filtered out by metadata filter", docId);
                                continue;
                            }
                            if (score < request.getSimilarityThreshold()) {
                                belowThresholdCount++;
                                logger.debug("[H2Vector] docId={} score={} below threshold={}", docId, score, request.getSimilarityThreshold());
                                continue;
                            }
                            Document scoredDoc = new Document.Builder()
                                    .id(doc.getId())
                                    .text(doc.getText())
                                    .metadata(doc.getMetadata())
                                    .score(score)
                                    .build();
                            results.add(scoredDoc);
                            logger.debug("[H2Vector] docId={} score={} PASSED", docId, score);
                        }
                    }
                }
            }

            List<Document> filteredResults = results.stream()
                    .sorted(Comparator.comparing(Document::getScore).reversed())
                    .limit(request.getTopK())
                    .toList();

            logger.info("[H2Vector] Final results: {} returned (filtered={}, belowThreshold={}, postSorted={})",
                    filteredResults.size(), filteredCount, belowThresholdCount, results.size());

            return filteredResults;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** 构造期建空内存图(无磁盘 I/O;删去旧 createNewIndex 的 Files.createDirectories + throws IOException + 外层 catch)。 */
    private void createNewIndex() {
        closeOldGraphIndex();
        GraphIndexBuilder builder = createGraphBuilder(List.of());
        graphIndex = builder.build(new ListRandomAccessVectorValues(List.of(), embeddingModel.dimensions()));
        try {
            builder.close();
        } catch (IOException e) {
            logger.warn("Error closing GraphIndexBuilder", e);
        }
        logger.info("[H2Vector] Created empty in-memory HNSW index with dimensions={}", embeddingModel.dimensions());
    }
```

**照搬清单**(从旧 `JVectorStore.java` 逐字复制进新类,零逻辑改动):`rebuildGraph()`(L216-250)、`createGraphBuilder(List<VectorFloat<?>>)`(L179-183)、`toVectorFloat(float[])`(L185-187)、`cosineSimilarity(float[], VectorFloat<?>)`(L407-429)、`matchesFilter(Document, Filter.Expression)`(L431-443)、`getDocIdByNodeIndex(int)`(L445-450)、`closeOldGraphIndex()`(L452-460)。`doSimilaritySearch` 与 `createNewIndex` 的完整最终形态已在上方代码块给出(前者加了 hydrate 守卫 + 前缀 `[JVector]`→`[H2Vector]`,后者删了磁盘 I/O),**直接采用上方代码,不要再去 copy 旧版**。旧文件 `JVectorStore.java` 在同目录(T4 才删),`rebuildGraph` 等 verbatim 方法实施时 Read 它对照。保留 `import java.io.IOException;`(createGraphBuilder/rebuildGraph 的 `builder.close()` 仍 throws IOException)。

**观测**(照搬旧 L462-471,仅改 collectionName):

```java
    @Override
    public VectorStoreObservationContext.Builder createObservationContextBuilder(String operationName) {
        VectorStoreSimilarityMetric metric = (similarityFunction == VectorSimilarityFunction.DOT_PRODUCT)
                ? VectorStoreSimilarityMetric.DOT
                : VectorStoreSimilarityMetric.COSINE;
        return VectorStoreObservationContext.builder(VectorStoreProvider.SIMPLE.value(), operationName)
                .dimensions(embeddingModel.dimensions())
                .collectionName("loom-vector-store-h2")
                .similarityMetric(metric.value());
    }
```

**Builder**(照搬旧 L473-514 骨架,`indexPath` 换成 `jdbcTemplate` + `transactionTemplate`):

```java
    public static final class Builder extends AbstractVectorStoreBuilder<Builder> {

        private JdbcTemplate jdbcTemplate;
        private TransactionTemplate transactionTemplate;
        private int m = 16;
        private int efConstruction = 100;
        private int efSearch = 10;
        private VectorSimilarityFunction similarityFunction = VectorSimilarityFunction.COSINE;

        private Builder(EmbeddingModel embeddingModel) {
            super(embeddingModel);
        }

        public Builder jdbcTemplate(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
            return this;
        }

        public Builder transactionTemplate(TransactionTemplate transactionTemplate) {
            this.transactionTemplate = transactionTemplate;
            return this;
        }

        public Builder m(int m) {
            this.m = m;
            return this;
        }

        public Builder efConstruction(int efConstruction) {
            this.efConstruction = efConstruction;
            return this;
        }

        public Builder efSearch(int efSearch) {
            this.efSearch = efSearch;
            return this;
        }

        public Builder similarityFunction(VectorSimilarityFunction similarityFunction) {
            this.similarityFunction = similarityFunction;
            return this;
        }

        @Override
        public H2JVectorStore build() {
            return new H2JVectorStore(this);
        }
    }
}
```

注意:`efSearch` 字段照旧声明保留但搜索不消费(与旧类一致的 pre-existing 现状,**不要**顺手接线——超出 spec 范围);`AtomicInteger` import 供 hydrate 计数;`LinkedHashMap` 供 doAdd 保序。

- [ ] **Step 6: 跑测试确认通过**

Run:`mvn test -pl spring-ai-loom-agent -Dtest=H2JVectorStoreTest`
Expected: **Tests run: 9, Failures: 0**。

- [ ] **Step 7: 全库模块单测回归(确认未破坏既有测试)**

Run:`mvn test -pl spring-ai-loom-agent`
Expected: BUILD SUCCESS,0 failures。

- [ ] **Step 8: Commit**

```bash
git add spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql \
        spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2VectorRow.java \
        spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2JVectorStore.java \
        spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2JVectorStoreTest.java
git commit -m "feat: loom_vector_store table + H2JVectorStore (H2 persistence, DB-first writes, hydrate API)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: H2VectorStoreReloader + RagConfiguration 接线 + indexPath 退役

**Files:**
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2VectorStoreReloader.java`
- Test: `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2VectorStoreReloaderTest.java`
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java`(L106-118 `JVectorProperties` 内部类)
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java`(L43 import、L703-713 bean)
- Modify: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentPropertiesDefaultsTest.java`(L44-51 + L75)

**Interfaces:**
- Consumes: T2 的 `H2JVectorStore.hydrate(List<H2VectorRow>)`、`H2JVectorStore.builder(...)`、`H2VectorRow` 6 组件 record;`LoomAgentProperties.JVectorProperties.getM()/getEfConstruction()/getEfSearch()`(保留项)。
- Produces(T4 IT 依赖):
  - `public H2VectorStoreReloader(JdbcTemplate jdbcTemplate, org.springframework.beans.factory.ObjectProvider<VectorStore> vectorStoreProvider)`
  - `public void H2VectorStoreReloader.onApplicationReady(ApplicationReadyEvent event)`(`@EventListener`)
  - `public void H2VectorStoreReloader.hydrateNow()`
  - autoconfigure bean 名:`h2VectorStore`(VectorStore)、`h2VectorStoreReloader`

- [ ] **Step 1: 写失败测试 `H2VectorStoreReloaderTest`**

```java
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
```

- [ ] **Step 2: 跑测试确认失败**

Run:`mvn test -pl spring-ai-loom-agent -Dtest=H2VectorStoreReloaderTest`
Expected: **编译失败**(`H2VectorStoreReloader` 不存在)。

- [ ] **Step 3: 实现 `H2VectorStoreReloader`**

```java
package cn.wubo.spring.ai.loom.agent.vectorstore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ApplicationReadyEvent 时把 loom_vector_store 全量 hydrate 进
 * {@link H2JVectorStore} 内存 HNSW 图(spec D7)。
 *
 * <p>选 ApplicationReadyEvent 的原因:Flyway 在 context refresh 期间建表,
 * bean 构造期查库会有竞态;ready 事件后表必然存在(沿用 ScheduleRestoreListener 先例)。
 * 用户用自带 VectorStore 替换默认实现时(instanceof 短路,spec §6 末行),
 * 本监听器不做任何事;表不存在(Flyway 被禁)时 ERROR + 空索引启动,不 fail-fast。
 */
public class H2VectorStoreReloader {

    private static final Logger logger = LoggerFactory.getLogger(H2VectorStoreReloader.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    public H2VectorStoreReloader(JdbcTemplate jdbcTemplate, ObjectProvider<VectorStore> vectorStoreProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStoreProvider = vectorStoreProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        hydrateNow();
    }

    public void hydrateNow() {
        VectorStore store = vectorStoreProvider.getIfAvailable();
        if (!(store instanceof H2JVectorStore h2Store)) {
            logger.debug("[H2Vector] active VectorStore is {} — reloader skipped",
                    store == null ? "absent" : store.getClass().getSimpleName());
            return;
        }
        try {
            List<H2VectorRow> rows = jdbcTemplate.query(
                    "SELECT document_id, content, metadata_json, embedding, dim, score FROM loom_vector_store",
                    (rs, n) -> new H2VectorRow(
                            rs.getString("document_id"),
                            rs.getString("content"),
                            rs.getString("metadata_json"),
                            rs.getBytes("embedding"),
                            rs.getInt("dim"),
                            rs.getObject("score") == null ? null : ((Number) rs.getObject("score")).doubleValue()));
            logger.info("[H2Vector] hydrating {} row(s) from loom_vector_store", rows.size());
            h2Store.hydrate(rows);
        } catch (Exception e) {
            // 表不存在(Flyway 未跑/被禁)或 DB 故障:空索引启动,不 fail-fast(spec §6)
            logger.error("[H2Vector] hydrate failed — starting with EMPTY vector index; "
                    + "RAG search will return no results until next successful restart", e);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run:`mvn test -pl spring-ai-loom-agent -Dtest=H2VectorStoreReloaderTest`
Expected: **Tests run: 3, Failures: 0**。

- [ ] **Step 5: 删 `JVectorProperties.indexPath`**

`LoomAgentProperties.java` L106-118:删 `private String indexPath = ...;` 行及其上方 javadoc 块(L108-113),保留 `m` / `efConstruction` / `efSearch` 三字段与 `@Data`。改后内部类形如:

```java
 @Data
 public static class JVectorProperties {
  /**
   * JVector HNSW 引擎参数。持久化已迁到 H2 表 loom_vector_store(#3),
   * 不再有磁盘索引目录;旧 indexPath 属性已删除,yml 里的残留键被 binder 静默忽略。
   */
  private int m = 16;
  private int efConstruction = 100;
  private int efSearch = 10;
 }
```

验证:`grep -rn "indexPath\|getIndexPath\|jvector-index" spring-ai-loom-agent/src/main/java` → 只剩 `JVectorStore.java`(T4 才删,本 task 不动它——它此刻仍能编译,因为 builder 默认值自给自足;`LoomAgentConfiguration` L708 的 `.indexPath(jv.getIndexPath())` 在 Step 6 一并移除)。

- [ ] **Step 6: 改 `LoomAgentConfiguration` 接线**

6a. L43 import 替换:
```java
// 旧: import cn.wubo.spring.ai.loom.agent.vectorstore.JVectorStore;
import cn.wubo.spring.ai.loom.agent.vectorstore.H2JVectorStore;
import cn.wubo.spring.ai.loom.agent.vectorstore.H2VectorStoreReloader;
```

6b. RagConfiguration 内 L703-713 bean 整体替换:

```java
        @ConditionalOnMissingBean(VectorStore.class)
        @Bean
        public VectorStore h2VectorStore(EmbeddingModel embeddingModel,
                                         org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                                         org.springframework.transaction.PlatformTransactionManager transactionManager,
                                         LoomAgentProperties properties) {
            LoomAgentProperties.JVectorProperties jv = properties.getJvector();
            return H2JVectorStore.builder(embeddingModel)
                    .jdbcTemplate(jdbcTemplate)
                    .transactionTemplate(new org.springframework.transaction.support.TransactionTemplate(transactionManager))
                    .m(jv.getM())
                    .efConstruction(jv.getEfConstruction())
                    .efSearch(jv.getEfSearch())
                    .build();
        }

        /**
         * #3 spec D7:ApplicationReadyEvent 时从 loom_vector_store hydrate 内存 HNSW 图。
         * ObjectProvider + reloader 内 instanceof 短路:用户替换 VectorStore 时自动失效。
         */
        @ConditionalOnBean(VectorStore.class)
        @Bean
        public H2VectorStoreReloader h2VectorStoreReloader(
                org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                org.springframework.beans.factory.ObjectProvider<VectorStore> vectorStoreProvider) {
            return new H2VectorStoreReloader(jdbcTemplate, vectorStoreProvider);
        }
```

(`@ConditionalOnBean(VectorStore.class)` + 同配置类内声明 = 既有 `defaultDocumentRead`/`defaultUpload` L715-727 的同款模式,顺序风险已被现存代码验证。)

验证:
- `grep -n "jVectorStore\|JVectorStore" spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java` → **0 处**;
- `grep -c "h2VectorStore" 同文件` → 恰好 **2**(bean 方法名 + reloader bean 名里的前缀不算,核对方法声明 `public VectorStore h2VectorStore(` 与 `public H2VectorStoreReloader h2VectorStoreReloader(` 各 1)。

- [ ] **Step 7: 改 `LoomAgentPropertiesDefaultsTest`(test 模块)**

7a. 删整个 `jvectorIndexPath_defaultsUnderUserHome_dot_loom` 测试方法(L44-51)。
7b. `singleRm_rfTargets_allUserState` 的数组(L71-76)删 `p.getJvector().getIndexPath()` 一行(连带上一行行尾逗号调整:`p.getDatasourceDir()` 成为最后一项,无尾逗号)。

- [ ] **Step 8: 装库 + 编译验证(test 模块对着新 JAR 编译)**

```bash
mvn clean install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter -am -Dgpg.skip=true -DskipTests
mvn test -pl spring-ai-loom-agent-test -Dtest=LoomAgentPropertiesDefaultsTest
```
Expected: install BUILD SUCCESS;test **Tests run: 5, Failures: 0**(6 减 1)。

- [ ] **Step 9: Commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2VectorStoreReloader.java \
        spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2VectorStoreReloaderTest.java \
        spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java \
        spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentPropertiesDefaultsTest.java
git commit -m "feat: H2VectorStoreReloader (ApplicationReadyEvent hydrate) + RagConfiguration wiring; retire jvector.indexPath

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: 删除旧 JVectorStore + H2VectorStoreIT + SubTask IT no-op 属性清理

**Files:**
- Delete: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/vectorstore/JVectorStore.java`
- Create: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2VectorStoreIT.java`
- Modify: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/SubTaskAndScheduleHistoryIntegrationTest.java`(L44-46 两行)

**Interfaces:**
- Consumes: T2 `H2VectorRow` / `H2JVectorStore.builder` / `hydrate`;T3 `H2VectorStoreReloader(JdbcTemplate, ObjectProvider<VectorStore>)` / `hydrateNow()`;V1.0 Flyway 建好的 `loom_vector_store` 表。
- Produces: 无(终端任务;T5 只动文档)。

- [ ] **Step 1: 删除旧类**

```bash
git rm spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/vectorstore/JVectorStore.java
```

验证:`grep -rn "JVectorStore" --include=*.java spring-ai-loom-agent/src spring-ai-loom-agent-spring-boot-autoconfigure/src spring-ai-loom-agent-test/src | grep -v H2JVectorStore` → **0 处**(若 `H2JVectorStore.java` javadoc 里提到"旧 JVectorStore"属注释文本,允许;import/类型引用不允许)。

- [ ] **Step 2: 写 `H2VectorStoreIT`(test 模块)**

> 上下文策略(沿用 `ChatTest`/`SubTaskAndScheduleHistoryIntegrationTest` 先例):`@MockBean(VectorStore.class)` 让 RagConfiguration 的真实 bean 退位(避免 DashScope 网络),**store/reloader 手动接线到真实 Flyway DataSource** —— 这样测的是"V1.0 真表 + 真 H2 file DB"的持久化契约;bean 接线本身由 T3 Step 8 的 install 编译 + 既有上下文测试(`LoomAgentToolAutoConfigTest` 提供 mock VectorStore bean 的场景)覆盖。

```java
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
        "spring.ai.loom.agent.file-base-path=./target/test-file-base",
        "spring.ai.loom.agent.knowledge-base-path=./target/test-knowledge-base"
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
```

- [ ] **Step 3: 删 SubTask IT 的 no-op 属性(核实结论:属性名不在本项目命名空间 `spring.ai.loom.agent.*`,binder 从不读它;真正让上下文跳过模型拉取的是 `@MockBean(VectorStore.class)`)**

`SubTaskAndScheduleHistoryIntegrationTest.java` L44-46 删两行:
```java
        // 本地 ollama 经常没预 pull mxbai-embed-large → 关闭 auto-pull 跳过拉取
        // (测试不真正用 vector store,只是验证 sub-task / schedule 流程)
        "spring.ai.vectorstore.jvector.auto-pull=false"
```
(只删注释两行 + 属性一行;数组前一项 `knowledge-base-path` 行尾逗号同步删除,保持语法。)

- [ ] **Step 4: 装库 + 清库 + 跑新 IT**

```bash
mvn clean install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter -am -Dgpg.skip=true -DskipTests
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn test -pl spring-ai-loom-agent-test -Dtest=H2VectorStoreIT -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: **Tests run: 3, Failures: 0**。

- [ ] **Step 5: 跑受影响 IT 回归(SubTask IT 改过属性)**

Run:`mvn test -pl spring-ai-loom-agent-test -Dtest=SubTaskAndScheduleHistoryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS(该测试本就 mock VectorStore,属性删除无行为影响)。

- [ ] **Step 6: Commit**

```bash
git add spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/vectorstore/H2VectorStoreIT.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/SubTaskAndScheduleHistoryIntegrationTest.java
git commit -m "test: H2VectorStoreIT (Flyway table + no-re-embed restart simulation); drop no-op auto-pull property; retire JVectorStore class

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

(注:`git rm` 的 `JVectorStore.java` 已在 Step 1 进入暂存区,随本 commit 一并落地。)

---

### Task 5: 文档同步 + 全量回归门

**Files:**
- Modify: `CLAUDE.md`(L23、L102、L218、L235、L247、L270、L282 + 升级注意段)
- Modify: `README.md`(L30)、`README.zh-CN.md`(L26)
- Modify: `docs/API.md`(L1402-1409)、`docs/API.zh-CN.md`(L1593-1600)
- Modify: `docs/CUSTOMIZATION.md`(L18、L67-74、L474-496、L664)、`docs/CUSTOMIZATION.zh-CN.md`(镜像行)
- Modify: `docs/superpowers/roadmap-2026-09-four-items.md`(#3 节 🔵→✅ + 落地记录)

**Interfaces:**
- Consumes: T1-T4 的 commit SHA(`git log --oneline` 取,填进 roadmap 落地记录)。
- Produces: 无(收尾任务)。

**裁决(写死,实施者不再判断):** `docs/TOOLS.md` L545 / `docs/TOOLS.zh-CN.md` L496 的 "RAG / JVector / MCP" 字样**不改** —— JVector 仍是 HNSW 引擎名,表述准确。

- [ ] **Step 1: CLAUDE.md 七处(每处先 Read 精确行再 Edit)**

| 位置 | 旧 | 新 |
|---|---|---|
| L23 Module 表 | `JVector vector store, H2 schema` | `H2-backed JVector vector store (loom_vector_store), H2 schema` |
| L102 RagConfiguration 行 | `VectorStore (JVector fallback)` | `VectorStore (H2-backed JVector fallback)` |
| L218 schema 清单 | `... / market_skill_archive / loom_market_knowledge_archive)` | `... / market_skill_archive / loom_market_knowledge_archive / loom_vector_store)` |
| L235 存储表行 | 整行 `\| ~/.loom/jvector-index/ \| HNSW 向量索引 \| jvector.indexPath 默认 ... \|` | **删除整行** |
| L247 配置属性行 | `- \`jvector\` — index path (默认 \`${user.home}/.loom/jvector-index\`)、HNSW params (m, efConstruction, efSearch)` | `- \`jvector\` — HNSW params (m, efConstruction, efSearch);持久化在 H2 表 \`loom_vector_store\`(#3 起,原 indexPath/json 目录已退役)` |
| L270 文件管理模态框 | `不显示 ~/.loom/jvector-index/、~/.loom/datasource/、...` | `不显示 ~/.loom/datasource/、~/.loom/compile-deploy-workspaces/ 这些工具/系统目录。` |
| L282 扩展点 | `\`JVectorStore\` won't be created due to` | `\`H2JVectorStore\` won't be created due to` |

另在 Data Layer "**升级注意**" 段(L222 附近)句尾追加一句:
`#3 起向量(embedding BLOB)也存 H2 表 loom_vector_store —— 清库重跑后知识库文档需重传(一次性 re-embed);更换 embedding 模型同理(dim 守卫会跳过旧维度行并 WARN)。`

验证:`grep -n "jvector-index\|JVectorStore" CLAUDE.md | grep -v H2JVectorStore` → **0 处**。

- [ ] **Step 2: README×2 各一处**

- `README.md` L30:`built-in JVector local store (swap in any Spring AI vector store)` → `built-in H2-backed vector store (JVector HNSW in-memory index; swap in any Spring AI vector store)`
- `README.zh-CN.md` L26:`内置 JVector 本地向量库（可替换为任意 Spring AI 向量存储）` → `内置 H2 持久化向量库（JVector HNSW 内存索引；可替换为任意 Spring AI 向量存储）`

- [ ] **Step 3: API×2 配置表**

`docs/API.md` L1402-1409:节标题 `### 10.5 JVector Configuration` → `### 10.5 Vector Store Configuration (H2-backed JVector)`;**删 `spring.ai.loom.agent.jvector.indexPath` 一行**;表后追加:

```markdown
> Persistence: vectors live in the H2 table `loom_vector_store` (embedding BLOB, little-endian float32) and are hydrated into the in-memory JVector HNSW graph on `ApplicationReadyEvent` — no boot-time re-embedding. The legacy `~/.loom/jvector-index/` json files are retired. Changing the embedding model invalidates stored vectors (dim-guarded rows are skipped with a WARN); wipe the table and re-upload knowledge documents.
```

`docs/API.zh-CN.md` L1593-1600 镜像:标题 → `### 11.5 向量存储配置(H2 持久化 JVector)`;删 indexPath 行;追加:

```markdown
> 持久化:向量存 H2 表 `loom_vector_store`(embedding BLOB,little-endian float32),`ApplicationReadyEvent` 时 hydrate 进内存 JVector HNSW 图 —— 启动不再 re-embed。旧 `~/.loom/jvector-index/` json 文件已退役。更换 embedding 模型会使存量向量失效(dim 守卫跳过旧行并 WARN);需清表并重传知识库文档。
```

- [ ] **Step 4: CUSTOMIZATION×2**

每处先 Read 再 Edit,EN 与 zh 镜像同改:
1. L18 目录树:`vectorstore/ JVectorStore # Default vector store` → `vectorstore/ H2JVectorStore # Default vector store (H2-backed)`(zh:`# 默认向量存储(H2 持久化)`)。
2. §1.3(L67-74):标题 `### 1.3 JVector Vector Store Configuration (\`jvector.*\`)` → `### 1.3 Vector Store Configuration (\`jvector.*\`, H2-backed)`(zh 镜像);**删 `jvector.indexPath` 表行**;m/efConstruction/efSearch 三行保留;节首加一句持久化说明(同 Step 3 追加文案的精简版)。
3. 替换点对比表(L474-496):`| **JVector (fallback)** | Built-in | Local file persistence, zero external dependencies |` → `| **H2-backed JVector (fallback)** | Built-in | H2 table persistence (loom_vector_store), in-memory HNSW, zero external dependencies |`;`JVector is the fallback` / `\`JVectorStore\` is skipped automatically` 两处类名/措辞同步(zh 镜像:`本地文件持久化` → `H2 表持久化(loom_vector_store)+ 内存 HNSW`)。
4. 故障矩阵(L664/L657):`\`JVectorStore\` is not created` → `\`H2JVectorStore\` is not created`(zh 镜像)。

验证:`grep -rn "JVectorStore\|jvector-index\|indexPath" docs/CUSTOMIZATION.md docs/CUSTOMIZATION.zh-CN.md | grep -v H2JVectorStore` → **0 处**。

- [ ] **Step 5: roadmap #3 节更新**

1. 总排期表:`| 3 | #3 H2 向量存储(自研 H2VectorStore) | 🔵 | 大 |` → `| 3 | #3 H2 向量存储(H2JVectorStore) | ✅ 完成(2026-09-07) | 大 |`
2. `## #3 H2 向量存储(🔵)` → `## #3 H2 向量存储(✅ 完成 2026-09-07)`
3. "推荐方案"小节后追加"### 落地记录(2026-09-07)":列 T1-T5 commit SHA(`git log --oneline -6` 取)、与 spec 的偏差(若无写"无")、回归门数字(Step 6 跑完回填)、spec §8 概览图结论(不重生成)。

- [ ] **Step 6: 全量回归门(三段式,清库起跑)**

```bash
mvn test -pl spring-ai-loom-agent
mvn clean install -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter -am -Dgpg.skip=true -DskipTests
mvn test -pl spring-ai-loom-agent-test
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected:库单元 BUILD SUCCESS;test 模块单元 **383 − 1(PropertiesDefaultsTest 删了一个)+ 0 新增 = 382, Failures: 0**;IT gate **120 + 3(H2VectorStoreIT)= 123 run, 0 failures, 3 skipped**(skip 数为既有 Maven 工具 IT 的条件跳过,±0)。任何红 → 修到绿再 commit。

- [ ] **Step 7: Commit(文档 + roadmap 一起)**

```bash
git add CLAUDE.md README.md README.zh-CN.md docs/API.md docs/API.zh-CN.md \
        docs/CUSTOMIZATION.md docs/CUSTOMIZATION.zh-CN.md \
        docs/superpowers/roadmap-2026-09-four-items.md
git commit -m "docs: align all live docs to H2-backed vector store (#3); retire jvector-index references

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

- [ ] **Step 8: 提醒用户(实施者不做,controller 转达)**

按 spec §8 / roadmap 维护约定,controller 在 #3 完成汇报中向用户确认:**概览图本次不需要重生成**(8 胶囊仍准确);下一个触发源是 #1(工具组 9→10)。

---

## Self-Review 记录(plan 作者自审,2026-09-07)

1. **Spec 覆盖**:D1(无 username 列→T2 Step 1 DDL)/ D2(新类→T2,删旧类→T4)/ D3(无迁移工具→无 task,全新库政策→Global Constraints + T4/T5 清库)/ D4(搜索照搬→T2 Step 5)/ D5(节名保留只删 indexPath→T3 Step 5)/ D6(DB 先行→T2 Step 5 doAdd/doDelete)/ D7(ApplicationReadyEvent→T3)/ D8(dim 守卫→T2 hydrate + 测试③)/ §6 错误矩阵(表不存在→T3 reloader catch;毒行→T2 测试④;UPSERT 失败→T2 测试⑧;替换 VectorStore→T3 测试①② + instanceof)/ §7 测试计划全部映射(§8 文档→T5;§9 收尾→T3/T5;§10 顺序=T1-T5)。无缺口。
2. **占位符扫描**:T2 Step 5 的 doSimilaritySearch 用"逐字照搬旧文件 L329-404 + 行号 + 日志前缀替换规则"表达 —— 这是有意的 DRY(旧文件在同目录可读,照搬指令带精确行号与差异点,非 "similar to Task N" 式含糊);其余步骤全为完整代码。无 TBD/TODO。
3. **类型一致性**:`H2VectorRow` 6 组件在 T2 定义、T3 reloader RowMapper 与 T4 IT 手工构造处逐字段核对一致(documentId/content/metadataJson/embedding/dim/score);`hydrate(List<H2VectorRow>)`、`hydrateNow()`、`builder(...).jdbcTemplate(...).transactionTemplate(...).observationRegistry(...).build()` 链在 T2/T3/T4 三处用法一致;`FakeEmbeddingModel(int)`/`embedCount()` T1 定义 T2 消费一致;`UPSERT_SQL` 6 占位符与 doAdd 6 实参一致。
4. **回归门数字口径**:test 模块单元 383→382(PropertiesDefaultsTest 6→5),IT 120→123(H2VectorStoreIT +3);库单元新增 VectorRowCodecTest 5 + H2JVectorStoreTest 9 + H2VectorStoreReloaderTest 3。
