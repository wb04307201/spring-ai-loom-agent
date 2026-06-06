package cn.wubo.spring.ai.loom.agent;

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
import cn.wubo.spring.ai.loom.agent.knowledge.DefaultKnowledge;
import cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge;
import cn.wubo.spring.ai.loom.agent.mcp.ASyncMcp;
import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.mcp.SyncMcp;
import cn.wubo.spring.ai.loom.agent.model.*;
import cn.wubo.spring.ai.loom.agent.skill.DefaultSkillStorage;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.file.DefaultFileTool;
import cn.wubo.spring.ai.loom.agent.tool.file.IFileTool;
import cn.wubo.spring.ai.loom.agent.tool.git.DefaultGitTool;
import cn.wubo.spring.ai.loom.agent.tool.git.IGitTool;
import cn.wubo.spring.ai.loom.agent.tool.skill.DefaultSkillTool;
import cn.wubo.spring.ai.loom.agent.tool.skill.ISkillTool;
import cn.wubo.spring.ai.loom.agent.tool.time.DefaultTimeTool;
import cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool;
import cn.wubo.spring.ai.loom.agent.user.*;
import cn.wubo.spring.ai.loom.agent.vectorstore.JVectorStore;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@AutoConfiguration
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
            }
            return properties;
        }

        @Bean
        public static BeanFactoryPostProcessor fileViewDefaultsBeanFactoryPostProcessor(org.springframework.core.env.ConfigurableEnvironment environment) {
            return new FileViewDefaultsBeanFactoryPostProcessor(environment);
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
        public IChat chat(ChatClient chatClient, Optional<RetrievalAugmentationAdvisor> retrievalAugmentationAdvisor, IMcp mcp, List<IEmbedTool> embedTools, IUserConversation userConversation, IFile file) {
            return new DefaultChat(chatClient, retrievalAugmentationAdvisor, mcp, embedTools, userConversation, file);
        }

        @Slf4j
        @Data
        @RequiredArgsConstructor
        @RestController
        @RequestMapping
        public static class SseController {

            private final IChat chat;

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

                        chatResponseFlux.subscribe(chatResponse -> {
                            try {
                                String reasoningContent = (String) chatResponse.getResult().getOutput().getMetadata().get("reasoningContent");
                                emitter.send(new ChatResponseRecord(chatResponse.getResult().getOutput().getText(), reasoningContent), MediaType.APPLICATION_JSON);
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
        public IUpload defaultUpload(IFile file, IFileDocument fileDocument, IDocumentRead documentRead, VectorStore vectorStore, IKnowledge knowledge) {
            return new DefaultUpload(file, fileDocument, documentRead, vectorStore, knowledge);
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
    static class ToolConfiguration {

        @ConditionalOnMissingBean(ITimeTool.class)
        @Bean
        public ITimeTool defaultTimeTool(LoomAgentProperties properties) {
            return new DefaultTimeTool(properties);
        }

        @ConditionalOnMissingBean(ISkillTool.class)
        @Bean
        public ISkillTool defaultSkillTool(ISkillStorage skillStorage) {
            return new DefaultSkillTool(skillStorage);
        }

        @ConditionalOnMissingBean(IFileTool.class)
        @Bean
        public IFileTool defaultFileTool(IFile file) {
            return new DefaultFileTool(file);
        }

        @ConditionalOnProperty(name = "spring.ai.loom.agent.git.enabled", havingValue = "true")
        @ConditionalOnMissingBean(IGitTool.class)
        @Bean
        public IGitTool defaultGitTool(IFile file, LoomAgentProperties properties) {
            return new DefaultGitTool(file, properties);
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

        @ConditionalOnBean(IUpload.class)
        @Bean("loomAgentFileRouter")
        public RouterFunction<ServerResponse> loomAgentFileRouter(IUpload upload, IFile file) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            builder.POST("/spring/ai/loom/file/upload", request -> {
                Part part = request.multipartData().getFirst("file");
                if (part == null) {
                    throw new IllegalArgumentException("上传的文件不能为空，请检查请求参数中是否包含名为'file'的文件");
                }
                String fileId = upload.upload(part.getInputStream(), part.getSubmittedFileName(), part.getContentType());
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(java.util.Map.of("fileId", fileId, "status", "success"));
            });
            builder.GET("/spring/ai/loom/file", request -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(file.list(null, UserContextHolder.getCurrentUser())));
            builder.DELETE("/spring/ai/loom/file/{id}", request -> {
                String id = request.pathVariable("id");
                String username = UserContextHolder.getCurrentUser();
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(file.delete(id, username));
            });
            builder.GET("/spring/ai/loom/file/{id}/download", request -> {
                String id = request.pathVariable("id");
                FileRecord fileRecord = file.getById(id, UserContextHolder.getCurrentUser());
                return ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .contentLength(fileRecord.size())
                        .header("Content-Disposition", "attachment; filename=\"" + fileRecord.fileName() + "\"")
                        .build((res, req) -> {
                            try (OutputStream os = req.getOutputStream()) {
                                os.write(upload.getContentByLocation(fileRecord.path()));
                                os.flush();
                            }
                            return new ModelAndView();
                        });
            });
            return builder.build();
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
}
