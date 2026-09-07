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
            AtomicInteger dimSkipped = new AtomicInteger();
            AtomicInteger poisonSkipped = new AtomicInteger();
            // 先建局部副本再原子换入,保证 docs/ids/vectors 三者严格对齐
            //(单行 decode 失败不得让 ids 与 vectors 错位 → 否则 embeddingMap 重建会 IndexOutOfBounds)
            Map<String, Document> loadedDocs = new LinkedHashMap<>();
            List<String> ids = new ArrayList<>();
            List<VectorFloat<?>> vectors = new ArrayList<>();
            for (H2VectorRow row : rows) {
                if (row.dim() != expectedDim) {
                    dimSkipped.incrementAndGet();
                    continue;
                }
                try {
                    float[] vec = VectorRowCodec.decodeEmbedding(row.embedding());   // 先解码,失败即整行跳过
                    Map<String, Object> metadata = VectorRowCodec.metadataFromJson(row.metadataJson());
                    Document.Builder docBuilder = Document.builder()
                            .id(row.documentId())
                            .text(row.content())
                            .metadata(metadata);
                    if (row.score() != null) {
                        docBuilder.score(row.score());
                    }
                    Document doc = docBuilder.build();
                    // 三个结构同步提交(decode 已成功)
                    loadedDocs.put(row.documentId(), doc);
                    ids.add(row.documentId());
                    vectors.add(toVectorFloat(vec));
                } catch (Exception e) {
                    poisonSkipped.incrementAndGet();
                    logger.warn("[H2Vector] skipping poison row documentId={}: {}",
                            row.documentId(), e.getMessage());
                }
            }
            // 原子换入:hydrate 后内存索引 == DB 有效行的精确镜像(清掉构造期/上轮残留)
            documentStore.clear();
            documentStore.putAll(loadedDocs);
            documentIds.clear();
            documentIds.addAll(ids);
            embeddingMap.clear();
            for (int i = 0; i < ids.size(); i++) {
                embeddingMap.put(ids.get(i), vectors.get(i));
            }
            rebuildGraph();
            hydrated = true;
            logger.info("[H2Vector] hydrate done: loaded={}, dimSkipped={}, poisonSkipped={}",
                    ids.size(), dimSkipped.get(), poisonSkipped.get());
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

    private void rebuildGraph() {
        closeOldGraphIndex();
        int dimensions = embeddingModel.dimensions();

        if (embeddingMap.isEmpty()) {
            GraphIndexBuilder builder = createGraphBuilder(List.of());
            graphIndex = builder.build(new ListRandomAccessVectorValues(List.of(), dimensions));
            currentVectors = List.of();
            try {
                builder.close();
            } catch (IOException e) {
                logger.warn("Error closing GraphIndexBuilder", e);
            }
            return;
        }

        // Build ordered vector list from documentIds to keep index alignment
        List<VectorFloat<?>> allVectors = new ArrayList<>(documentIds.size());
        for (String id : documentIds) {
            VectorFloat<?> vec = embeddingMap.get(id);
            if (vec != null) {
                allVectors.add(vec);
            }
        }

        GraphIndexBuilder builder = createGraphBuilder(allVectors);
        ListRandomAccessVectorValues ravv = new ListRandomAccessVectorValues(allVectors, dimensions);
        graphIndex = builder.build(ravv);
        currentVectors = allVectors;
        try {
            builder.close();
        } catch (IOException e) {
            logger.warn("Error closing GraphIndexBuilder", e);
        }
    }

    private GraphIndexBuilder createGraphBuilder(List<VectorFloat<?>> vectors) {
        int dimensions = embeddingModel.dimensions();
        ListRandomAccessVectorValues ravv = new ListRandomAccessVectorValues(vectors, dimensions);
        return new GraphIndexBuilder(ravv, similarityFunction, m, efConstruction, 1.0f, 1.4f);
    }

    private VectorFloat<?> toVectorFloat(float[] data) {
        return vectorizationProvider.getVectorTypeSupport().createFloatVector(data);
    }

    private double cosineSimilarity(float[] vectorX, VectorFloat<?> vectorY) {
        if (vectorX == null || vectorY == null) {
            throw new IllegalArgumentException("Vectors must not be null");
        }
        if (vectorX.length != vectorY.length()) {
            throw new IllegalArgumentException("Vectors lengths must be equal");
        }

        float dotProduct = 0;
        float normX = 0;
        float normY = 0;
        for (int i = 0; i < vectorX.length; i++) {
            dotProduct += vectorX[i] * vectorY.get(i);
            normX += vectorX[i] * vectorX[i];
            normY += vectorY.get(i) * vectorY.get(i);
        }

        if (normX == 0 || normY == 0) {
            return 0;
        }

        return dotProduct / (Math.sqrt(normX) * Math.sqrt(normY));
    }

    private boolean matchesFilter(Document document, Filter.Expression filterExpression) {
        if (filterExpression == null) {
            return true;
        }
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("metadata", document.getMetadata());
        String spelExpression = FILTER_CONVERTER.convertExpression(filterExpression);
        logger.debug("[H2Vector] SpEL filter: '{}' on metadata: {}", spelExpression, document.getMetadata());
        Expression expression = expressionParser.parseExpression(spelExpression);
        Boolean result = expression.getValue(context, Boolean.class);
        logger.debug("[H2Vector] SpEL result: {} for docId={}", result, document.getId());
        return result != null && result;
    }

    private String getDocIdByNodeIndex(int nodeIndex) {
        if (nodeIndex >= 0 && nodeIndex < documentIds.size()) {
            return documentIds.get(nodeIndex);
        }
        return null;
    }

    private void closeOldGraphIndex() {
        if (graphIndex != null && graphIndex instanceof AutoCloseable) {
            try {
                ((AutoCloseable) graphIndex).close();
            } catch (Exception e) {
                logger.warn("Failed to close old graph index", e);
            }
        }
    }

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
