package cn.wubo.spring.ai.loom.agent.vectorstore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.jbellis.jvector.graph.GraphIndex;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.SearchResult;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * JVector-based VectorStore implementation with disk persistence.
 * Uses JVector off-heap HNSW index for similarity search and
 * ConcurrentHashMap for document metadata storage.
 */
public class JVectorStore extends AbstractObservationVectorStore {

 private static final Logger logger = LoggerFactory.getLogger(JVectorStore.class);

 private static final String METADATA_FILE = "docs.json";

 private final String indexPath;
 private final int m;
 private final int efConstruction;
 private final int efSearch;
 private final VectorSimilarityFunction similarityFunction;

 private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
 private final ConcurrentHashMap<String, Document> documentStore = new ConcurrentHashMap<>();
 // Ordered list of document IDs -- index matches JVector graph node index
 private final List<String> documentIds = Collections.synchronizedList(new ArrayList<>());
 // Vector storage keyed by document ID (as VectorFloat)
 private final Map<String, VectorFloat<?>> embeddingMap = new ConcurrentHashMap<>();
 // Current vector list for search-time RAVV construction
 private volatile List<VectorFloat<?>> currentVectors = List.of();
 @SuppressWarnings("rawtypes")
 private volatile GraphIndex graphIndex;
 private static final SimpleVectorStoreFilterExpressionConverter FILTER_CONVERTER = new SimpleVectorStoreFilterExpressionConverter();
 private final ObjectMapper objectMapper;
 private final ExpressionParser expressionParser;
 private final VectorizationProvider vectorizationProvider;

 public JVectorStore(JVectorStoreBuilder builder) {
 super(builder);
 this.indexPath = builder.indexPath;
 this.m = builder.m;
 this.efConstruction = builder.efConstruction;
 this.efSearch = builder.efSearch;
 this.similarityFunction = builder.similarityFunction;
 this.objectMapper = JsonMapper.builder().build();
 this.expressionParser = new SpelExpressionParser();
 this.vectorizationProvider = VectorizationProvider.getInstance();
 initialize();
 }

 public static JVectorStoreBuilder builder(EmbeddingModel embeddingModel) {
 return new JVectorStoreBuilder(embeddingModel);
 }

 private void initialize() {
 Path path = Paths.get(indexPath);
 Path metadataFile = path.resolve(METADATA_FILE);

 if (Files.exists(metadataFile)) {
 loadFromDisk(path, metadataFile);
 } else {
 createNewIndex();
 }
 }

 private void loadFromDisk(Path path, Path metadataFile) {
 try {
 TypeReference<Map<String, Map<String, Object>>> typeRef = new TypeReference<>() {};
 Map<String, Map<String, Object>> rawDocs = objectMapper.readValue(metadataFile.toFile(), typeRef);

 for (Map.Entry<String, Map<String, Object>> entry : rawDocs.entrySet()) {
 String id = entry.getKey();
 Map<String, Object> data = entry.getValue();
 String text = (String) data.get("text");
 @SuppressWarnings("unchecked")
 Map<String, Object> metadata = (Map<String, Object>) data.get("metadata");
 Double score = data.get("score") != null ? ((Number) data.get("score")).doubleValue() : null;

 if (text != null && !text.isEmpty()) {
 Document.Builder builder = Document.builder().id(id).text(text);
 if (metadata != null) builder.metadata(metadata);
 if (score != null) builder.score(score);
 documentStore.put(id, builder.build());
 } else {
 logger.warn("[JVector] Skipping doc id={} with empty text on load", id);
 }
 }

 logger.info("[JVector] Loaded {} docs from docs.json", documentStore.size());

 Path idsFile = path.resolve("ids.json");
 if (Files.exists(idsFile)) {
 TypeReference<List<String>> idTypeRef = new TypeReference<>() {};
 List<String> loadedIds = objectMapper.readValue(idsFile.toFile(), idTypeRef);
 // Only include IDs that have corresponding documents
 for (String id : loadedIds) {
 if (documentStore.containsKey(id)) {
 documentIds.add(id);
 }
 }
 } else {
 documentIds.addAll(documentStore.keySet().stream().sorted().toList());
 }

 for (String id : documentIds) {
 Document doc = documentStore.get(id);
 if (doc != null) {
 float[] embedding = embeddingModel.embed(doc);
 VectorFloat<?> vf = toVectorFloat(embedding);
 embeddingMap.put(id, vf);
 }
 }

 if (!embeddingMap.isEmpty()) {
 rebuildGraph();
 logger.info("[JVector] Rebuilt graph from {} loaded embeddings", embeddingMap.size());
 } else {
 createNewIndex();
 }

 logger.info("Loaded {} documents from disk index at {}", documentStore.size(), indexPath);
 } catch (IOException e) {
 logger.error("Failed to load index from disk, creating new index", e);
 createNewIndex();
 }
 }

 private void createNewIndex() {
 try {
 closeOldGraphIndex();
 Path path = Paths.get(indexPath);
 if (!Files.exists(path)) {
 Files.createDirectories(path);
 }
 GraphIndexBuilder builder = createGraphBuilder(List.of());
 graphIndex = builder.build(new ListRandomAccessVectorValues(List.of(), embeddingModel.dimensions()));
 try {
 builder.close();
 } catch (IOException e) {
 logger.warn("Error closing GraphIndexBuilder", e);
 }
 logger.info("Created new JVector index at {} with dimensions={}", indexPath, embeddingModel.dimensions());
 } catch (IOException e) {
 throw new RuntimeException("Failed to create JVector index directory", e);
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

 @Override
 public void doAdd(List<Document> documents) {
 if (documents == null || documents.isEmpty()) {
 return;
 }

 rwLock.writeLock().lock();
 try {
 logger.info("[JVector] Adding {} documents", documents.size());
 for (Document document : documents) {
 logger.debug("[JVector] Embedding document id={}, metadata keys={}", document.getId(), document.getMetadata().keySet());
 float[] embedding = embeddingModel.embed(document);
 VectorFloat<?> vf = toVectorFloat(embedding);
 documentStore.put(document.getId(), document);
 embeddingMap.put(document.getId(), vf);
 documentIds.add(document.getId());
 }

 rebuildGraph();
 persistToDisk();
 logger.info("[JVector] After add: totalDocs={}, documentIds={}, embeddingMap={}",
 documentStore.size(), documentIds.size(), embeddingMap.size());
 } finally {
 rwLock.writeLock().unlock();
 }
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

 private void persistToDisk() {
 try {
 Path path = Paths.get(indexPath);
 if (!Files.exists(path)) {
 Files.createDirectories(path);
 }

 // Convert Documents to serializable format (Jackson can't deserialize Document directly)
 Map<String, Map<String, Object>> serializableDocs = documentStore.entrySet().stream()
 .collect(Collectors.toMap(
 Map.Entry::getKey,
 e -> {
 Document doc = e.getValue();
 Map<String, Object> map = new HashMap<>();
 map.put("text", doc.getText());
 map.put("metadata", doc.getMetadata());
 map.put("score", doc.getScore());
 return map;
 }
 ));

 objectMapper.writeValue(path.resolve(METADATA_FILE).toFile(), serializableDocs);
 objectMapper.writeValue(path.resolve("ids.json").toFile(), documentIds);

 logger.debug("Persisted {} documents to disk index at {}", documentStore.size(), indexPath);
 } catch (IOException e) {
 logger.error("Failed to persist index to disk", e);
 throw new RuntimeException("Failed to persist JVector index", e);
 }
 }

 @Override
 public void doDelete(List<String> idList) {
 if (idList == null || idList.isEmpty()) {
 return;
 }

 rwLock.writeLock().lock();
 try {
 for (String id : idList) {
 documentStore.remove(id);
 embeddingMap.remove(id);
 documentIds.remove(id);
 }

 rebuildGraph();
 persistToDisk();
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
 for (String id : idsToDelete) {
 documentStore.remove(id);
 embeddingMap.remove(id);
 documentIds.remove(id);
 }
 rebuildGraph();
 persistToDisk();
 }
 } finally {
 rwLock.writeLock().unlock();
 }
 }

 @Override
 public List<Document> doSimilaritySearch(SearchRequest request) {
 rwLock.readLock().lock();
 try {
 logger.info("[JVector] Search query='{}', topK={}, threshold={}, filter={}",
 request.getQuery(), request.getTopK(), request.getSimilarityThreshold(),
 request.hasFilterExpression() ? request.getFilterExpression() : "none");
 logger.info("[JVector] Index state: totalDocs={}, documentIds.size={}, embeddingMap.size={}, graphIndex={}",
 documentStore.size(), documentIds.size(), embeddingMap.size(),
 graphIndex == null ? "null" : "size=" + graphIndex.size());

 float[] queryEmbedding = embeddingModel.embed(request.getQuery());

 if (graphIndex == null || documentIds.isEmpty()) {
 logger.warn("[JVector] Empty index - returning empty results");
 return Collections.emptyList();
 }

 VectorFloat<?> queryVector = toVectorFloat(queryEmbedding);

 SearchResult result;
 try {
 // GraphSearcher.search() 3rd param is RandomAccessVectorValues (NOT acceptOrds)
 // Must provide the vector data provider for similarity computation
 ListRandomAccessVectorValues ravv = new ListRandomAccessVectorValues(currentVectors, embeddingModel.dimensions());
 result = GraphSearcher.search(queryVector, request.getTopK() * 2,
 ravv, similarityFunction, graphIndex, Bits.ALL);
 logger.info("[JVector] HNSW search returned {} candidates", result.getNodes().length);
 } catch (Exception e) {
 logger.error("Error during JVector search", e);
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
 logger.debug("[JVector] docId={} filtered out by metadata filter", docId);
 continue;
 }
 if (score < request.getSimilarityThreshold()) {
 belowThresholdCount++;
 logger.debug("[JVector] docId={} score={} below threshold={}", docId, score, request.getSimilarityThreshold());
 continue;
 }
 Document scoredDoc = new Document.Builder()
 .id(doc.getId())
 .text(doc.getText())
 .metadata(doc.getMetadata())
 .score(score)
 .build();
 results.add(scoredDoc);
 logger.debug("[JVector] docId={} score={} PASSED", docId, score);
 }
 }
 }
 }

 List<Document> filteredResults = results.stream()
 .sorted(Comparator.comparing(Document::getScore).reversed())
 .limit(request.getTopK())
 .toList();

 logger.info("[JVector] Final results: {} returned (filtered={}, belowThreshold={}, postSorted={})",
 filteredResults.size(), filteredCount, belowThresholdCount, results.size());

 return filteredResults;
 } finally {
 rwLock.readLock().unlock();
 }
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
 logger.debug("[JVector] SpEL filter: '{}' on metadata: {}", spelExpression, document.getMetadata());
 Expression expression = expressionParser.parseExpression(spelExpression);
 Boolean result = expression.getValue(context, Boolean.class);
 logger.debug("[JVector] SpEL result: {} for docId={}", result, document.getId());
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
 .collectionName("jvector-disk-index")
 .similarityMetric(metric.value());
 }

 public static final class JVectorStoreBuilder extends AbstractVectorStoreBuilder<JVectorStoreBuilder> {

 private String indexPath = ".local/jvector-index";
 private int m = 16;
 private int efConstruction = 100;
 private int efSearch = 10;
 private VectorSimilarityFunction similarityFunction = VectorSimilarityFunction.COSINE;

 private JVectorStoreBuilder(EmbeddingModel embeddingModel) {
 super(embeddingModel);
 }

 public JVectorStoreBuilder indexPath(String indexPath) {
 this.indexPath = indexPath;
 return this;
 }

 public JVectorStoreBuilder m(int m) {
 this.m = m;
 return this;
 }

 public JVectorStoreBuilder efConstruction(int efConstruction) {
 this.efConstruction = efConstruction;
 return this;
 }

 public JVectorStoreBuilder efSearch(int efSearch) {
 this.efSearch = efSearch;
 return this;
 }

 public JVectorStoreBuilder similarityFunction(VectorSimilarityFunction similarityFunction) {
 this.similarityFunction = similarityFunction;
 return this;
 }

 @Override
 public JVectorStore build() {
 return new JVectorStore(this);
 }
 }
}
