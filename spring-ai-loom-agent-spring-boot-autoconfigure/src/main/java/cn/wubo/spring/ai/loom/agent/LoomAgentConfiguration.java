package cn.wubo.spring.ai.loom.agent;

import cn.wubo.file.view.storage.IFileStorage;
import cn.wubo.spring.ai.loom.agent.chat.DefaultChat;
import cn.wubo.spring.ai.loom.agent.chat.IChat;
import cn.wubo.spring.ai.loom.agent.document.DefaultDocumentRead;
import cn.wubo.spring.ai.loom.agent.document.DefaultFileDocument;
import cn.wubo.spring.ai.loom.agent.document.IDocumentRead;
import cn.wubo.spring.ai.loom.agent.document.IFileDocument;
import cn.wubo.spring.ai.loom.agent.file.DefaultFile;
import cn.wubo.spring.ai.loom.agent.file.DefaultUpload;
import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.file.IUpload;
import cn.wubo.spring.ai.loom.agent.file.view.LoomAgentFileStorageImpl;
import cn.wubo.spring.ai.loom.agent.knowledge.DefaultKnowledge;
import cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge;
import cn.wubo.spring.ai.loom.agent.mcp.ASyncMcp;
import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.mcp.SyncMcp;
import cn.wubo.spring.ai.loom.agent.model.*;
import cn.wubo.spring.ai.loom.agent.skill.DefaultSkillStorage;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.compile.DefaultCompileAndDeployTool;
import cn.wubo.spring.ai.loom.agent.tool.compile.ICompileAndDeployTool;
import cn.wubo.spring.ai.loom.agent.tool.file.DefaultFileTool;
import cn.wubo.spring.ai.loom.agent.tool.file.IFileTool;
import cn.wubo.spring.ai.loom.agent.tool.git.DefaultGitTool;
import cn.wubo.spring.ai.loom.agent.tool.git.IGitTool;
import cn.wubo.spring.ai.loom.agent.tool.maven.DefaultMavenTool;
import cn.wubo.spring.ai.loom.agent.tool.maven.IMavenTool;
import cn.wubo.spring.ai.loom.agent.tool.skill.DefaultSkillTool;
import cn.wubo.spring.ai.loom.agent.tool.skill.ISkillTool;
import cn.wubo.spring.ai.loom.agent.tool.time.DefaultTimeTool;
import cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool;
import cn.wubo.spring.ai.loom.agent.user.*;
import cn.wubo.spring.ai.loom.agent.vectorstore.JVectorStore;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@AutoConfiguration
@AutoConfigureBefore(cn.wubo.file.view.autoconfigure.FileViewConfiguration.class)
@AutoConfigureAfter(name = {
        // ChatModel
        "org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration",
        "org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration",
        "org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration",
        "org.springframework.ai.model.minimax.autoconfigure.MiniMaxChatAutoConfiguration",
        "org.springframework.ai.model.mistralai.autoconfigure.MistralAiChatAutoConfiguration",
        "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
        "org.springframework.ai.model.bedrock.converse.autoconfigure.BedrockConverseProxyChatAutoConfiguration",
        "org.springframework.ai.model.transformers.autoconfigure.TransformersChatAutoConfiguration",
        "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration",
        // EmbeddingModel
        "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
        "org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration",
        "org.springframework.ai.model.minimax.autoconfigure.MiniMaxEmbeddingAutoConfiguration",
        "org.springframework.ai.model.mistralai.autoconfigure.MistralAiEmbeddingAutoConfiguration",
        "org.springframework.ai.model.bedrock.titan.autoconfigure.BedrockTitanEmbeddingAutoConfiguration",
        "org.springframework.ai.model.bedrock.cohere.autoconfigure.BedrockCohereEmbeddingAutoConfiguration",
        "org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration",
        "org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiTextEmbeddingAutoConfiguration",
        "org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiMultiModalEmbeddingAutoConfiguration",
        "org.springframework.ai.model.transformers.autoconfigure.TransformersEmbeddingModelAutoConfiguration",
        "org.springframework.ai.model.postgresml.autoconfigure.PostgresMlEmbeddingAutoConfiguration",
        "org.springframework.ai.model.embedding.observation.autoconfigure.EmbeddingObservationAutoConfiguration",
        "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingAutoConfiguration",
        // VectorStore
        "org.springframework.ai.vectorstore.azure.autoconfigure.AzureVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.cosmosdb.autoconfigure.CosmosDBVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.cassandra.autoconfigure.CassandraVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.couchbase.autoconfigure.CouchbaseSearchVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.elasticsearch.autoconfigure.ElasticsearchVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.gemfire.autoconfigure.GemFireVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.mariadb.autoconfigure.MariaDbStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.mongodb.autoconfigure.MongoDBAtlasVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.neo4j.autoconfigure.Neo4jVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.observation.autoconfigure.VectorStoreObservationAutoConfiguration",
        "org.springframework.ai.vectorstore.opensearch.autoconfigure.OpenSearchVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.oracle.autoconfigure.OracleVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.pinecone.autoconfigure.PineconeVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.typesense.autoconfigure.TypesenseVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.weaviate.autoconfigure.WeaviateVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.s3.autoconfigure.S3VectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.infinispan.autoconfigure.InfinispanVectorStoreAutoConfiguration",
        "org.springframework.ai.vectorstore.bedrockknowledgebase.autoconfigure.BedrockKnowledgeBaseVectorStoreAutoConfiguration",
        // ChatMemory
        "org.springframework.ai.model.chat.memory.redis.autoconfigure.RedisChatMemoryAutoConfiguration",
        "org.springframework.ai.model.chat.memory.repository.cassandra.autoconfigure.CassandraChatMemoryRepositoryAutoConfiguration",
        "org.springframework.ai.model.chat.memory.repository.cosmosdb.autoconfigure.CosmosDBChatMemoryRepositoryAutoConfiguration",
        "org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration",
        "org.springframework.ai.model.chat.memory.repository.mongo.autoconfigure.MongoChatMemoryRepositoryAutoConfiguration",
        "org.springframework.ai.model.chat.memory.repository.neo4j.autoconfigure.Neo4jChatMemoryRepositoryAutoConfiguration",
        // MCP
        "org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration",
        "org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration",
        "org.springframework.ai.mcp.client.common.autoconfigure.annotations.McpClientAnnotationScannerAutoConfiguration"})
public class LoomAgentConfiguration {

    // ==================== Infrastructure ====================

    @Configuration
    static class InfrastructureConfiguration {

        private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(InfrastructureConfiguration.class);

        /**
         * 放宽 Spring AI 内部 {@code JsonParser} 使用的 JSON mapper，
         * 允许 JS 风格注释（{@code //}、{@code /* *}{@code /}）和单引号。
         * <p>
         * 部分 LLM（特别是 qwen 系列）在工具调用时输出的 JSON 会带 JS 注释，
         * 默认的 Jackson 配置会抛 {@code JsonParseException}，整条工具链直接断掉。
         * Spring AI 2.0 切到了 Jackson 3（{@code tools.jackson}），配置入口换成
         * {@code JsonMapper.builder().enable(...)}，这里同步更新。
         */
        @Bean
        public org.springframework.beans.factory.SmartInitializingSingleton springAiJsonParserConfig() {
            return () -> {
                try {
                    tools.jackson.databind.json.JsonMapper mapper =
                            org.springframework.ai.util.json.JsonParser.getJsonMapper();
                    mapper.rebuild()
                            .enable(tools.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS)
                            .enable(tools.jackson.core.json.JsonReadFeature.ALLOW_YAML_COMMENTS)
                            .enable(tools.jackson.core.json.JsonReadFeature.ALLOW_SINGLE_QUOTES)
                            .enable(tools.jackson.core.json.JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES)
                            .build();
                } catch (Throwable t) {
                    // 静默失败 —— Spring AI 内部 API 可能在新版本里被替换
                    LOG.warn("Could not configure Spring AI JsonParser to allow comments: {}", t.getMessage());
                }
            };
        }

        @Bean
        public LoomAgentProperties loomAgentProperties(org.springframework.core.env.Environment environment) {
            LoomAgentProperties properties = new LoomAgentProperties();
            org.springframework.boot.context.properties.bind.Binder binder = org.springframework.boot.context.properties.bind.Binder.get(environment);
            org.springframework.boot.context.properties.bind.BindResult<LoomAgentProperties> result = binder.bind("spring.ai.loom.agent", LoomAgentProperties.class);
            if (result.isBound()) {
                LoomAgentProperties bound = result.get();
                properties.setDefaultSystem(bound.getDefaultSystem());
                properties.setInit(bound.isInit());
                properties.setRag(bound.getRag());
                properties.setMcps(bound.getMcps());
                properties.setSkills(bound.getSkills());
                properties.setJvector(bound.getJvector());
                properties.setTimezone(bound.getTimezone());
                properties.setGitUsername(bound.getGitUsername());
                properties.setGitToken(bound.getGitToken());
                properties.setAuth(bound.getAuth());
                properties.setUser(bound.getUser());
                properties.setMaven(bound.getMaven());
                properties.setCompile(bound.getCompile());
            }
            return properties;
        }

        @Bean
        public static BeanFactoryPostProcessor fileViewDefaultsBeanFactoryPostProcessor(org.springframework.core.env.ConfigurableEnvironment environment) {
            return new FileViewDefaultsBeanFactoryPostProcessor(environment);
        }

        /**
         * Spring AI 2.0 在 {@code SPRING_AI_CHAT_MEMORY} 表上自动加了一条
         * {@code CHECK (type IN ('USER','ASSISTANT','SYSTEM','TOOL'))} 约束，
         * 但 H2 在某些状态下对这条约束校验会误报（即使 type 字段值是合法的
         * 'USER'/'ASSISTANT'，仍然抛 {@code CONSTRAINT_A: }），导致每次 chat
         * 写入记忆时报 HTTP 500。这里在启动后把这条约束直接 drop 掉，让
         * {@link org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository}
         * 按 Java enum 名裸写即可。
         * <p>
         * Constraint 名不固定：Spring AI schema 初始化时新建表叫 {@code CONSTRAINT_A}，
         * 重启时复用旧表叫 {@code TYPE_CHECK}，两条 SQL 都要尝试。
         */
        @Bean
        public org.springframework.beans.factory.SmartInitializingSingleton dropBrokenChatMemoryTypeCheck(
                javax.sql.DataSource dataSource) {
            return () -> {
                for (String name : new String[]{"TYPE_CHECK", "CONSTRAINT_A"}) {
                    try (java.sql.Connection conn = dataSource.getConnection();
                         java.sql.Statement st = conn.createStatement()) {
                        int updated = st.executeUpdate(
                                "ALTER TABLE SPRING_AI_CHAT_MEMORY DROP CONSTRAINT IF EXISTS " + name);
                        if (updated > 0) {
                            LOG.info("Dropped broken {} constraint on SPRING_AI_CHAT_MEMORY", name);
                        }
                    } catch (Throwable t) {
                        LOG.warn("Could not drop {} constraint on SPRING_AI_CHAT_MEMORY ({}). " +
                                "Chat memory inserts may fail. Cause: {}", name, t.getMessage(), t.toString());
                    }
                }
            };
        }

        /**
         * Spring Boot 4.x / Spring AI 2.0 默认绑定的是 Jackson 3 的 {@code tools.jackson.databind.json.JsonMapper}
         * （用作 spring.ai 的 JSON 工具），不再自动配置 Jackson 2 的 {@code ObjectMapper}。
         * 但 LoomAgent 里前端 SSE 协议用的还是 Jackson 2（{@code ObjectMapper.writeValueAsString}），
         * 这里显式定义一个 Jackson 2 的 {@code ObjectMapper} Bean 给 SseController 用。
         */
        @Bean
        public ObjectMapper loomAgentJacksonObjectMapper() {
            return new ObjectMapper();
        }

        @Bean
        public FlywayConfigurationCustomizer myStarterFlywayCustomizer() {
            return configuration -> {
                configuration.locations("classpath:db/loom");
                configuration.table("loomAgent_schema_history");
                configuration.baselineOnMigrate(true);
                configuration.baselineVersion("0");
            };
        }

        @Bean
        public ChatMemory jdbChatMemory(ChatMemoryRepository chatMemoryRepository) {
            return MessageWindowChatMemory.builder().chatMemoryRepository(chatMemoryRepository).build();
        }
    }

    // ==================== Chat ====================

    @Configuration
    static class ChatConfiguration {

        @ConditionalOnProperty(name = "spring.ai.chat.ui.init", havingValue = "true", matchIfMissing = true)
        @Bean
        public ChatClient chatClient(ChatModel chatModel, ChatMemory chatMemory, LoomAgentProperties properties) {
            ChatClient.Builder builder = ChatClient.builder(chatModel);
            if (properties.getDefaultSystem() != null) builder.defaultSystem(properties.getDefaultSystem());
            builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(), // chat-memory advisor
                    new SimpleLoggerAdvisor() // logger advisor
            );
            return builder.build();
        }

        @ConditionalOnMissingBean(IChat.class)
        @Bean
        public IChat chat(ChatClient chatClient, Optional<RetrievalAugmentationAdvisor> retrievalAugmentationAdvisor, IMcp mcp, List<IEmbedTool> embedTools, IUserConversation userConversation, IFile file, org.springframework.core.env.Environment environment) {
            return new DefaultChat(chatClient, retrievalAugmentationAdvisor, mcp, embedTools, userConversation, file, environment);
        }

        @Slf4j
        @Data
        @RestController
        @RequestMapping
        public static class SseController {

            private final IChat chat;
            private final ObjectMapper objectMapper;

            public SseController(IChat chat, ObjectMapper objectMapper) {
                this.chat = chat;
                this.objectMapper = objectMapper;
            }

            @PostMapping(value = "/spring/ai/loom/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
            public SseEmitter stream(@RequestBody ChatRequestRecord chatRecord, HttpServletRequest request) {
                SseEmitter emitter = new SseEmitter(0L);

                emitter.onTimeout(() -> {
                    log.debug("SSE 链接超时");
                    emitter.complete();
                });
                emitter.onCompletion(() -> log.debug("SSE 链接完成"));
                emitter.onError(e -> log.debug("SSE 链接错误：{}", e.getMessage()));

                String username = UserContextHolder.getCurrentUser();
                CompletableFuture.runAsync(() -> {
                    try {
                        Flux<ChatResponse> chatResponseFlux = chat.stream(chatRecord, username, request);

                        chatResponseFlux
                                .filter(chatResponse -> chatResponse.getResult() != null)
                                .subscribe(chatResponse -> {
                            try {
                                String reasoningContent = (String) chatResponse.getResult().getOutput().getMetadata().get("reasoningContent");
                                // SseEmitter.send(Object, MediaType) does not pick a JSON
                                // converter for SSE event payloads. Use the typed builder
                                // API (SseEmitter.event().data(...)) — Spring serializes the
                                // payload with the configured HTTP message converter and
                                // prepends the SSE "data: " prefix and trailing blank line
                                // itself, so the client EventSource parser sees a valid
                                // JSON per event.
                                emitter.send(SseEmitter.event()
                                        .data(new ChatResponseRecord(chatResponse.getResult().getOutput().getText(), reasoningContent),
                                                MediaType.APPLICATION_JSON));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        }, emitter::completeWithError, emitter::complete);
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                });

                return emitter;
            }
        }
    }

    // ==================== RAG (all beans conditional on VectorStore) ====================

    @Configuration
    @Conditional(AnyEmbeddingProviderCondition.class)
    static class RagConfiguration {

        @ConditionalOnMissingBean(VectorStore.class)
        @Bean
        public VectorStore jVectorStore(EmbeddingModel embeddingModel, LoomAgentProperties properties) {
            LoomAgentProperties.JVectorProperties jv = properties.getJvector();
            return JVectorStore.builder(embeddingModel)
                    .indexPath(jv.getIndexPath())
                    .m(jv.getM())
                    .efConstruction(jv.getEfConstruction())
                    .efSearch(jv.getEfSearch())
                    .build();
        }

        @ConditionalOnBean(VectorStore.class)
        @ConditionalOnMissingBean(IDocumentRead.class)
        @Bean
        public IDocumentRead defaultDocumentRead(ChatModel chatModel, LoomAgentProperties properties) {
            return new DefaultDocumentRead(chatModel, properties.getRag());
        }

        @ConditionalOnBean(VectorStore.class)
        @Bean
        public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(VectorStore vectorStore, LoomAgentProperties properties) {
            return RetrievalAugmentationAdvisor.builder().documentRetriever(VectorStoreDocumentRetriever.builder().similarityThreshold(properties.getRag().getSimilarityThreshold()).topK(properties.getRag().getTopK()).vectorStore(vectorStore).build()).queryAugmenter(ContextualQueryAugmenter.builder().promptTemplate(PromptTemplate.builder().template(properties.getRag().getDefaultPromptTemplate()).build()).emptyContextPromptTemplate(PromptTemplate.builder().template(properties.getRag().getDefaultEmptyContextPromptTemplate()).build()).allowEmptyContext(true).build()
            ).build();
        }

        @ConditionalOnBean(VectorStore.class)
        @ConditionalOnMissingBean(IUpload.class)
        @Bean
        public IUpload defaultUpload(IFile file, IFileDocument fileDocument, IDocumentRead documentRead, VectorStore vectorStore, IKnowledge knowledge, LoomAgentProperties properties) {
            return new DefaultUpload(file, fileDocument, documentRead, vectorStore, knowledge, properties.getFileBasePath(), properties.getKnowledgeBasePath());
        }
    }

    /**
     * Nested condition: RagConfiguration activates if ANY embedding provider
     * auto-configuration is present on the classpath.
     */
    static class AnyEmbeddingProviderCondition extends AnyNestedCondition {

        AnyEmbeddingProviderCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnClass(name = "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingAutoConfiguration")
        static class DashScopePresent {
        }

        @ConditionalOnClass(name = "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration")
        static class OpenAiEmbeddingPresent {
        }

        @ConditionalOnClass(name = "org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration")
        static class OllamaEmbeddingPresent {
        }

        @ConditionalOnClass(name = "org.springframework.ai.model.deepseek.autoconfigure.DeepSeekEmbeddingAutoConfiguration")
        static class DeepSeekEmbeddingPresent {
        }

        @ConditionalOnClass(name = "org.springframework.ai.model.zhipuai.autoconfigure.ZhiPuAiEmbeddingAutoConfiguration")
        static class ZhiPuAiEmbeddingPresent {
        }
    }

    // ==================== MCP ====================

    @Configuration
    static class McpConfiguration {

        @ConditionalOnProperty(name = "spring.ai.mcp.client.stdio", havingValue = "ASYNC")
        @Bean
        public IMcp aSyncMcp(LoomAgentProperties properties, List<McpAsyncClient> mcpAsyncClients) {
            return new ASyncMcp(properties.getMcps(), mcpAsyncClients);
        }

        @ConditionalOnMissingBean
        @Bean
        public IMcp syncMcp(LoomAgentProperties properties, List<McpSyncClient> mcpSyncClients) {
            return new SyncMcp(properties.getMcps(), mcpSyncClients);
        }
    }

    // ==================== Embed Tools ====================

    @Configuration
    public static class ToolConfiguration {

        @ConditionalOnProperty(name = "spring.ai.loom.agent.time.enabled", havingValue = "true", matchIfMissing = true)
        @ConditionalOnMissingBean(ITimeTool.class)
        @Bean
        public ITimeTool defaultTimeTool(LoomAgentProperties properties) {
            return new DefaultTimeTool(properties);
        }

        @ConditionalOnProperty(name = "spring.ai.loom.agent.skill.enabled", havingValue = "true", matchIfMissing = true)
        @ConditionalOnMissingBean(ISkillTool.class)
        @Bean
        public ISkillTool defaultSkillTool(ISkillStorage skillStorage) {
            return new DefaultSkillTool(skillStorage);
        }

        @ConditionalOnProperty(name = "spring.ai.loom.agent.file.enabled", havingValue = "true", matchIfMissing = true)
        @ConditionalOnMissingBean(IFileTool.class)
        @Bean
        public IFileTool defaultFileTool(IFile file, LoomAgentProperties properties) {
            return new DefaultFileTool(file, properties.getFileBasePath(), properties.getFile());
        }

        @ConditionalOnProperty(name = "spring.ai.loom.agent.git.enabled", havingValue = "true")
        @ConditionalOnMissingBean(IGitTool.class)
        @Bean
        public IGitTool defaultGitTool(LoomAgentProperties properties) {
            return new DefaultGitTool(properties);
        }

        @ConditionalOnClass(name = "org.apache.maven.shared.invoker.Invoker")
        @ConditionalOnProperty(name = "spring.ai.loom.agent.maven.enabled", havingValue = "true")
        @ConditionalOnMissingBean(IMavenTool.class)
        @Bean
        public IMavenTool defaultMavenTool(LoomAgentProperties properties) {
            return new DefaultMavenTool(properties.getMaven(), properties.getFileBasePath());
        }

        @ConditionalOnProperty(name = "spring.ai.loom.agent.compile.enabled", havingValue = "true", matchIfMissing = true)
        @ConditionalOnMissingBean(ICompileAndDeployTool.class)
        @Bean
        public ICompileAndDeployTool defaultCompileAndDeployTool(LoomAgentProperties properties) {
            return new DefaultCompileAndDeployTool(properties);
        }
    }

    // ==================== Storage ====================

    @Configuration
    static class StorageConfiguration {

        @ConditionalOnMissingBean(IUser.class)
        @Bean
        public IUser defaultUser(LoomAgentProperties properties, Cache sessionCache) {
            LoomAgentProperties.UserProperty userProp = properties.getUser();
            return new DefaultUser(userProp.getUsername(), userProp.getNickname(), userProp.getAuthentication(), sessionCache);
        }

        @ConditionalOnMissingBean(IUserConversation.class)
        @Bean
        public IUserConversation defaultUserConversation(JdbcTemplate jdbcTemplate, ChatMemoryRepository chatMemoryRepository) {
            return new DefaultUserConversation(jdbcTemplate, chatMemoryRepository);
        }

        @ConditionalOnMissingBean(ISkillStorage.class)
        @Bean
        public ISkillStorage defaultSkillStorage(JdbcTemplate jdbcTemplate, LoomAgentProperties properties, ResourceLoader resourceLoader) {
            return new DefaultSkillStorage(jdbcTemplate, properties.getSkills(), resourceLoader);
        }

        @ConditionalOnMissingBean(IFile.class)
        @Bean
        public IFile defaultFile(JdbcTemplate jdbcTemplate) {
            return new DefaultFile(jdbcTemplate);
        }

        /**
         * 显式注册 file-view 的 IFileStorage 桥接实现，避免 file-view 默认的内存版
         * {@code LocalFileStorageImpl} 覆盖本实现（{@code @Service} 在跨包扫描时不会生效）。
         * 没有这一项，{@code /file/view/{id}} 与 {@code /wopi/files/{id}/contents}
         * 永远查不到 {@code file_info} 表中的记录。
         */
        @ConditionalOnMissingBean(IFileStorage.class)
        @Bean
        public IFileStorage loomAgentFileStorage(JdbcTemplate jdbcTemplate) {
            return new LoomAgentFileStorageImpl(jdbcTemplate);
        }

        @ConditionalOnMissingBean(IFileDocument.class)
        @Bean
        public IFileDocument defaultFileDocument(JdbcTemplate jdbcTemplate) {
            return new DefaultFileDocument(jdbcTemplate);
        }

        @ConditionalOnMissingBean(IKnowledge.class)
        @Bean
        public IKnowledge defaultKnowledge(JdbcTemplate jdbcTemplate) {
            return new DefaultKnowledge(jdbcTemplate);
        }
    }

    // ==================== Web ====================

    @Configuration
    static class WebConfiguration {

        @Bean
        public Cache sessionCache(LoomAgentProperties properties) {
            int ttlSeconds = properties.getAuth().getCookie().getMaxAge();
            return new CaffeineCache("loom-agent-auth",
                    Caffeine.newBuilder()
                            .maximumSize(10_000)
                            .expireAfterWrite(java.time.Duration.ofSeconds(ttlSeconds))
                            .build());
        }

        @Bean
        public FilterRegistrationBean<AuthenticationFilter> authenticationFilter(IUser user, LoomAgentProperties properties) {
            FilterRegistrationBean<AuthenticationFilter> registration = new FilterRegistrationBean<>();
            registration.setFilter(new AuthenticationFilter(user, properties.getAuth()));
            registration.addUrlPatterns("/*");
            registration.setOrder(1);
            return registration;
        }

        @Bean("loomAgentBaseRouter")
        public RouterFunction<ServerResponse> loomAgentBaseRouter(IUser user, LoomAgentProperties properties) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            builder.GET("spring/ai/loom", request -> ServerResponse.temporaryRedirect(URI.create("/spring/ai/loom/index.html")).build());

            // isAutoLogin: check if there's a valid session cookie, or always return true to allow auto-login
            builder.POST("spring/ai/loom/user/isAutoLogin", request -> {
                String token = extractTokenFromCookies(request, properties.getAuth().getCookie().getName());
                boolean hasValidSession = token != null && user.validateToken(token);
                return ServerResponse.ok().body(hasValidSession || user.isAutoLogin());
            });

            // login: validate credentials, create session token, set cookie
            builder.POST("spring/ai/loom/user/login", request -> {
                UserRequestRecord body = request.body(UserRequestRecord.class);
                UserResponseRecord response = user.login(body);
                String username = body.username() != null && !body.username().isEmpty()
                        ? body.username()
                        : properties.getUser().getUsername();
                String token = user.createToken(username);
                LoomAgentProperties.AuthProperty.CookieProperty cookieProp = properties.getAuth().getCookie();
                return ServerResponse.ok()
                        .cookie(createSessionCookie(token, cookieProp))
                        .body(new UserResponseRecord(token, response.nickname()));
            });

            // logout: invalidate token and clear cookie
            builder.POST("spring/ai/loom/user/logout", request -> {
                String token = extractTokenFromCookies(request, properties.getAuth().getCookie().getName());
                if (token != null) {
                    user.invalidateToken(token);
                }
                LoomAgentProperties.AuthProperty.CookieProperty cookieProp = properties.getAuth().getCookie();
                Cookie clearCookie = new Cookie(cookieProp.getName(), "");
                clearCookie.setPath(cookieProp.getPath());
                clearCookie.setMaxAge(0);
                clearCookie.setHttpOnly(true);
                clearCookie.setSecure(cookieProp.isSecure());
                clearCookie.setAttribute("SameSite", cookieProp.getSameSite());
                if (cookieProp.getDomain() != null && !cookieProp.getDomain().isEmpty()) {
                    clearCookie.setDomain(cookieProp.getDomain());
                }
                return ServerResponse.ok()
                        .cookie(clearCookie)
                        .body(true);
            });

            return builder.build();
        }

        @Bean("loomAgentConversationRouter")
        public RouterFunction<ServerResponse> loomAgentConversationRouter(JdbcChatMemoryRepository chatMemoryRepository, IUserConversation userConversation) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            builder.GET("spring/ai/loom/conversation", request -> ServerResponse.ok().body(userConversation.getList()));
            builder.GET("spring/ai/loom/conversation/{conversationId}", request -> {
                String conversationId = request.pathVariable("conversationId");
                return ServerResponse.ok().body(chatMemoryRepository.findByConversationId(conversationId));
            });
            builder.DELETE("spring/ai/loom/conversation/{conversationId}", request -> {
                String conversationId = request.pathVariable("conversationId");
                userConversation.deleteById(conversationId);
                return ServerResponse.ok().body(true);
            });
            return builder.build();
        }

        @Bean("loomAgentMcpRouter")
        public RouterFunction<ServerResponse> loomAgentMcpRouter(IMcp mcp) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            builder.GET("spring/ai/chat/loom/mcp", request -> ServerResponse.ok().body(mcp.mcps()));
            return builder.build();
        }

        @Bean("loomAgentSkillRouter")
        public RouterFunction<ServerResponse> loomAgentSkillRouter(ISkillStorage skillStorage) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            builder.GET("spring/ai/loom/skill", request -> {
                String username = UserContextHolder.getCurrentUser();
                return ServerResponse.ok().body(skillStorage.list(username));
            });
            builder.PUT("spring/ai/loom/skill", request -> {
                SkillRecord skill = request.body(SkillRecord.class);
                String username = UserContextHolder.getCurrentUser();
                skillStorage.save(skill, username);
                return ServerResponse.ok().body(true);
            });
            builder.GET("spring/ai/loom/skill/{name}", request -> {
                String name = request.pathVariable("name");
                String username = UserContextHolder.getCurrentUser();
                return ServerResponse.ok().body(skillStorage.get(name, username));
            });
            builder.DELETE("spring/ai/loom/skill/{name}", request -> {
                String name = request.pathVariable("name");
                String username = UserContextHolder.getCurrentUser();
                skillStorage.remove(name, username);
                return ServerResponse.ok().body(true);
            });
            return builder.build();
        }

        @Bean("loomAgentFileRouter")
        public RouterFunction<ServerResponse> loomAgentFileRouter(IFile file, LoomAgentProperties properties) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            // 返回目录树（前端文件管理器用）
            builder.GET("/spring/ai/loom/file", request -> ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildFileTree(properties.getFileBasePath(), UserContextHolder.getCurrentUser())));
            builder.GET("/spring/ai/loom/file/tree", request -> ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildFileTree(properties.getFileBasePath(), UserContextHolder.getCurrentUser())));
            // 按路径预览：自动注册 temp 记录后重定向到 /file/view/{id}
            builder.GET("/spring/ai/loom/file/by-path/view", request -> {
                String path = request.param("path").orElse("");
                if (path.isEmpty()) {
                    return ServerResponse.badRequest().body("缺少 path 参数");
                }
                String username = UserContextHolder.getCurrentUser();
                String fileId = getOrCreateFileId(properties.getFileBasePath(), path, username, file);
                if (fileId == null) {
                    return ServerResponse.notFound().build();
                }
                return ServerResponse.temporaryRedirect(URI.create("/file/view/" + fileId)).build();
            });
            // 按路径下载：自动注册 temp 记录后重定向到 /wopi/files/{id}/contents
            builder.GET("/spring/ai/loom/file/by-path/download", request -> {
                String path = request.param("path").orElse("");
                if (path.isEmpty()) {
                    return ServerResponse.badRequest().body("缺少 path 参数");
                }
                String username = UserContextHolder.getCurrentUser();
                String fileId = getOrCreateFileId(properties.getFileBasePath(), path, username, file);
                if (fileId == null) {
                    return ServerResponse.notFound().build();
                }
                return ServerResponse.temporaryRedirect(URI.create("/wopi/files/" + fileId + "/contents")).build();
            });
            // 按 fileId 下载：直接读磁盘，不依赖 IUpload（@Tool downloadFileUrl 用此端点）
            builder.GET("/spring/ai/loom/file/{id}/download", request -> {
                String id = request.pathVariable("id");
                FileRecord fileRecord = file.getById(id, UserContextHolder.getCurrentUser());
                // 选 Content-Type：优先 FileRecord.mimeType（写入时 Tika 探测过），没有再按扩展名猜，最后兜底 octet-stream
                MediaType contentType = resolveContentType(fileRecord);
                // 拼 Content-Disposition：中文文件名按 RFC 5987 用 filename*=UTF-8''<urlencoded>，
                // 同时给一个 ASCII 兜底（去掉非 ASCII 字符 + 保留扩展名），老浏览器/curl 也能用
                return ServerResponse.ok()
                        .contentType(contentType)
                        .contentLength(fileRecord.size())
                        .header("Content-Disposition", buildContentDisposition(fileRecord.fileName()))
                        .build((res, req) -> {
                            try (OutputStream os = req.getOutputStream()) {
                                os.write(Files.readAllBytes(Path.of(fileRecord.path())));
                                os.flush();
                            }
                            return new ModelAndView();
                        });
            });
            return builder.build();
        }

        /**
         * 上传路由：依赖 IUpload（默认实现需要 VectorStore + IDocumentRead + IKnowledge）。
         * 与 {@link #loomAgentFileRouter(IFile, LoomAgentProperties)} 拆开，是因为下载/列表类路由
         * 只需要 IFile，不应该被 VectorStore 等知识库组件的可用性拖累——没有 RAG 的纯聊天场景
         * 也能正常下载/列出文件。
         */
        @ConditionalOnBean(IUpload.class)
        @Bean("loomAgentUploadRouter")
        public RouterFunction<ServerResponse> loomAgentUploadRouter(IUpload upload) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            builder.POST("/spring/ai/loom/file/upload", request -> {
                Part part = request.multipartData().getFirst("file");
                if (part == null) {
                    throw new IllegalArgumentException("上传的文件不能为空，请检查请求参数中是否包含名为'file'的文件");
                }
                String fileId = upload.upload(part.getInputStream(), part.getSubmittedFileName(), part.getContentType());
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(java.util.Map.of("fileId", fileId, "status", "success"));
            });
            return builder.build();
        }

        /** 构建用户文件目录树 JSON */
        @SuppressWarnings("unchecked")
        private java.util.Map<String, Object> buildFileTree(String fileBasePath, String username) {
            java.util.Map<String, Object> node = new java.util.LinkedHashMap<>();
            Path baseDir = Paths.get(fileBasePath, username);
            node.put("name", ".");
            node.put("type", "directory");
            node.put("children", buildChildren(baseDir));
            return node;
        }

        @SuppressWarnings("unchecked")
        private java.util.List<java.util.Map<String, Object>> buildChildren(Path dir) {
            java.util.List<java.util.Map<String, Object>> children = new java.util.ArrayList<>();
            if (!Files.exists(dir)) return children;
            try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
                var sorted = stream.sorted(java.util.Comparator.comparing(p -> Files.isDirectory(p) ? 0 : 1)).toList();
                for (Path item : sorted) {
                    java.util.Map<String, Object> child = new java.util.LinkedHashMap<>();
                    String name = item.getFileName().toString();
                    if (Files.isDirectory(item)) {
                        child.put("name", name);
                        child.put("type", "directory");
                        child.put("children", buildChildren(item));
                    } else {
                        child.put("name", name);
                        child.put("type", "file");
                        try {
                            child.put("size", Files.size(item));
                        } catch (java.io.IOException e) {
                            child.put("size", 0);
                        }
                    }
                    children.add(child);
                }
            } catch (java.io.IOException e) {
                // return empty list on error
            }
            return children;
        }

        /** 根据路径获取或创建 fileId，用于预览/下载桥接 */
        private String getOrCreateFileId(String fileBasePath, String path, String username, IFile file) {
            try {
                Path baseDir = Paths.get(fileBasePath, username);
                Path resolved = baseDir.resolve(path).normalize();
                if (!resolved.startsWith(baseDir) || !Files.exists(resolved) || !Files.isRegularFile(resolved)) {
                    return null;
                }
                String pathStr = resolved.toString();
                FileRecord existing = file.getByExactPath(pathStr, username);
                if (existing != null) return existing.id();

                org.apache.tika.Tika tika = new org.apache.tika.Tika();
                String mimeType = tika.detect(resolved.toFile());
                String fileId = java.util.UUID.randomUUID().toString();
                java.nio.file.attribute.BasicFileAttributes attrs = Files.readAttributes(resolved, java.nio.file.attribute.BasicFileAttributes.class);
                file.insert(new FileRecord(
                        fileId,
                        null,
                        resolved.getFileName().toString(),
                        attrs.size(),
                        java.time.LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), java.time.ZoneId.systemDefault()),
                        pathStr,
                        "temp",
                        mimeType
                ), username);
                return fileId;
            } catch (Exception e) {
                return null;
            }
        }


        @ConditionalOnBean(VectorStore.class)
        @Bean("loomAgentKnowledgeRouter")
        public RouterFunction<ServerResponse> loomAgentKnowledgeRouter(IKnowledge knowledge, IUpload upload, IFile file) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            builder.GET("/spring/ai/loom/knowledge/checkKnowledgeUpload", request -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(true));
            builder.GET("/spring/ai/loom/knowledge", request -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(knowledge.list()));
            builder.PUT("/spring/ai/loom/knowledge", request -> {
                KnowledgeRecord knowledgeRecord = request.body(KnowledgeRecord.class);
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(knowledge.insert(knowledgeRecord.name()));
            });
            builder.DELETE("/spring/ai/loom/knowledge/{knowledgeId}", request -> {
                String knowledgeId = request.pathVariable("knowledgeId");
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(upload.deleteAllKnowledge(knowledgeId));
            });
            builder.POST("/spring/ai/loom/knowledge/{knowledgeId}/upload", request -> {
                Part part = request.multipartData().getFirst("file");
                if (part == null) {
                    throw new IllegalArgumentException("上传的文件不能为空，请检查请求参数中是否包含名为'file'的文件");
                }
                String knowledgeId = request.pathVariable("knowledgeId");

                String fileId = upload.uploadWithKnowledge(part.getInputStream(), part.getSubmittedFileName(), part.getContentType(), knowledgeId);
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(Map.of("fileId", fileId, "status", "success"));
            });
            builder.GET("/spring/ai/loom/knowledge/{knowledgeId}/file", request -> {
                String knowledgeId = request.pathVariable("knowledgeId");
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(file.list(knowledgeId, UserContextHolder.getCurrentUser()));
            });
            builder.DELETE("/spring/ai/loom/knowledge/{knowledgeId}/file/{fileId}", request -> {
                String fileId = request.pathVariable("fileId");
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(upload.delete(fileId));
            });
            return builder.build();
        }

        /**
         * file-view 鉴权 Bean，复用 LoomAgent 的 Cookie Session 鉴权机制。
         * 当 classpath 存在 file-view 库时自动注册。
         */
        @ConditionalOnClass(name = "cn.wubo.file.view.auth.IAuth")
        @ConditionalOnMissingBean(cn.wubo.file.view.auth.IAuth.class)
        @Bean
        public cn.wubo.file.view.auth.IAuth loomAgentFileViewAuth(IUser user, LoomAgentProperties properties) {
            return new cn.wubo.spring.ai.loom.agent.file.view.LoomAgentAuth(user, properties.getAuth().getCookie().getName());
        }

        private static String extractTokenFromCookies(
                org.springframework.web.servlet.function.ServerRequest request, String cookieName) {
            Cookie[] cookies = request.servletRequest().getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (cookieName.equals(cookie.getName())) {
                        return cookie.getValue();
                    }
                }
            }
            return null;
        }

        private static Cookie createSessionCookie(
                String token, LoomAgentProperties.AuthProperty.CookieProperty cookieProp) {
            Cookie cookie = new Cookie(cookieProp.getName(), token);
            cookie.setPath(cookieProp.getPath());
            cookie.setMaxAge(cookieProp.getMaxAge());
            cookie.setHttpOnly(true);
            cookie.setSecure(cookieProp.isSecure());
            if (cookieProp.getDomain() != null && !cookieProp.getDomain().isEmpty()) {
                cookie.setDomain(cookieProp.getDomain());
            }
            cookie.setAttribute("SameSite", cookieProp.getSameSite());
            return cookie;
        }
    }

    /**
     * 选下载响应的 Content-Type：
     * - 优先用 FileRecord.mimeType（writeFile 时 Tika 探测过，比较准）
     * - 缺失时按扩展名兜底（覆盖 .md / .txt / .json 等常见类型，markdown 给 text/markdown 让浏览器内联渲染）
     * - 都没有就 octet-stream
     */
    private static MediaType resolveContentType(FileRecord fileRecord) {
        if (fileRecord.mimeType() != null && !fileRecord.mimeType().isBlank()) {
            return MediaType.parseMediaType(fileRecord.mimeType());
        }
        String name = fileRecord.fileName() == null ? "" : fileRecord.fileName().toLowerCase();
        if (name.endsWith(".md") || name.endsWith(".markdown")) return MediaType.parseMediaType("text/markdown;charset=UTF-8");
        if (name.endsWith(".txt")) return MediaType.parseMediaType("text/plain;charset=UTF-8");
        if (name.endsWith(".json")) return MediaType.parseMediaType("application/json;charset=UTF-8");
        if (name.endsWith(".html") || name.endsWith(".htm")) return MediaType.parseMediaType("text/html;charset=UTF-8");
        if (name.endsWith(".csv")) return MediaType.parseMediaType("text/csv;charset=UTF-8");
        if (name.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (name.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (name.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (name.endsWith(".svg")) return MediaType.parseMediaType("image/svg+xml");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /**
     * 拼 Content-Disposition 头，处理中文文件名。
     * 输出形式：
     * <ul>
     *   <li>全 ASCII：{@code attachment; filename="report.md"}</li>
     *   <li>含非 ASCII：{@code attachment; filename="report.md"; filename*=UTF-8''%E5%95%86%E5%93%81...md}
     *       ——RFC 5987 双键，filename 是把非 ASCII 字符替换成 _ 后的 ASCII 兜底</li>
     * </ul>
     * 不做这层编码时，浏览器只能拿到原始 UTF-8 字节序列，文件名会乱码或变成 UUID。
     */
    private static String buildContentDisposition(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "attachment";
        }
        // 1. ASCII 兜底：把非 ASCII / 不可打印字符替换成 _
        String asciiFallback = fileName.replaceAll("[^\\x20-\\x7E]", "_").replaceAll("\"", "_");
        if (asciiFallback.isEmpty()) {
            asciiFallback = "download";
        }
        // 2. 全 ASCII 时单键即可
        if (fileName.chars().allMatch(c -> c >= 0x20 && c <= 0x7E)) {
            return "attachment; filename=\"" + asciiFallback + "\"";
        }
        // 3. 含中文等非 ASCII 时双键：ASCII 兜底 + RFC 5987 urlencoded
        String encoded = java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded;
    }
}
