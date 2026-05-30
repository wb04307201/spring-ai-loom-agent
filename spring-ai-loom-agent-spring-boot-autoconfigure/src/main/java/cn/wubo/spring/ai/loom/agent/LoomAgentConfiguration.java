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
import cn.wubo.spring.ai.loom.agent.tool.DefaultEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.user.*;
import cn.wubo.spring.ai.loom.agent.vectorstore.JVectorStore;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
@Slf4j
public class LoomAgentConfiguration {

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
            properties.setAllowedDirectories(bound.getAllowedDirectories());
        }
        return properties;
    }

    @Bean
    public static BeanFactoryPostProcessor fileViewDefaultsBeanFactoryPostProcessor(org.springframework.core.env.ConfigurableEnvironment environment) {
        return new FileViewDefaultsBeanFactoryPostProcessor(environment);
    }

    @Bean
    public ChatMemory jdbChatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder().chatMemoryRepository(chatMemoryRepository).build();
    }

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

    @ConditionalOnBean(EmbeddingModel.class)
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
        return RetrievalAugmentationAdvisor.builder().documentRetriever(VectorStoreDocumentRetriever.builder().similarityThreshold(properties.getRag().getSimilarityThreshold()).topK(properties.getRag().getTopK()).vectorStore(vectorStore).build()).queryAugmenter(ContextualQueryAugmenter.builder().promptTemplate(PromptTemplate.builder().template(properties.getRag().getDefaultPromptTemplate()).build()).emptyContextPromptTemplate(PromptTemplate.builder().template(properties.getRag().getDefaultEmptyContextPromptTemplate()).build()).allowEmptyContext(true).build() // RAG advisor
        ).build();
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

    @ConditionalOnMissingBean(IUser.class)
    @Bean
    public IUser defaultUser(
            @org.springframework.beans.factory.annotation.Value("${spring.ai.loom.agent.user.username:username}") String defaultUsername,
            @org.springframework.beans.factory.annotation.Value("${spring.ai.loom.agent.user.nickname:用户}") String defaultNickname,
            @org.springframework.beans.factory.annotation.Value("${spring.ai.loom.agent.user.authentication:loom-agent-auth}") String defaultAuthentication) {
        return new DefaultUser(defaultUsername, defaultNickname, defaultAuthentication);
    }

    @ConditionalOnMissingBean(IUserConversation.class)
    @Bean
    public IUserConversation defaultUserConversation(JdbcTemplate jdbcTemplate, ChatMemoryRepository chatMemoryRepository) {
        return new DefaultUserConversation(jdbcTemplate, chatMemoryRepository);
    }

    @ConditionalOnMissingBean(IEmbedTool.class)
    @Bean
    public IEmbedTool embedTool(ISkillStorage skillStorage, IFile file, LoomAgentProperties properties) {
        return new DefaultEmbedTool(skillStorage, file, properties);
    }

    @Bean
    public BeanFactoryPostProcessor allowedDirectoriesInitializer(LoomAgentProperties properties) {
        return bf -> {
            for (String dir : properties.getAllowedDirectories()) {
                Path path = Paths.get(dir).toAbsolutePath().normalize();
                if (!Files.exists(path)) {
                    try {
                        Files.createDirectories(path);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to create allowed directory: " + path, e);
                    }
                }
            }
        };
    }

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

    @ConditionalOnMissingBean(IUpload.class)
    @Bean
    public IUpload defaultUpload(IFile file, IFileDocument fileDocument, IDocumentRead documentRead, VectorStore vectorStore, IKnowledge knowledge) {
        return new DefaultUpload(file, fileDocument, documentRead, vectorStore, knowledge);
    }

    @Bean
    public FilterRegistrationBean<AuthenticationFilter> authenticationFilter(IUser user) {
        FilterRegistrationBean<AuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AuthenticationFilter(user));
        registration.addUrlPatterns("/spring/ai/loom/*");
        registration.setOrder(1);
        return registration;
    }

    @Bean("loomAgentBaseRouter")
    public RouterFunction<ServerResponse> loomAgentBaseRouter(IUser user) {
        RouterFunctions.Builder builder = RouterFunctions.route();
        builder.GET("spring/ai/loom", request -> ServerResponse.temporaryRedirect(URI.create("/spring/ai/loom/index.html")).build());
        builder.POST("spring/ai/loom/user/isAutoLogin", request -> ServerResponse.ok().body(user.isAutoLogin()));
        builder.POST("spring/ai/loom/user/login", request -> ServerResponse.ok().body(user.login(request.body(UserRequestRecord.class))));
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
            skillStorage.save(skill,username);
            return ServerResponse.ok().body(true);
        });
        builder.GET("spring/ai/loom/skill/{name}", request -> {
            String name = request.pathVariable("name");
            String username = UserContextHolder.getCurrentUser();
            return ServerResponse.ok().body(skillStorage.get(name,username));
        });
        builder.DELETE("spring/ai/loom/skill/{name}", request -> {
            String name = request.pathVariable("name");
            String username = UserContextHolder.getCurrentUser();
            skillStorage.remove(name,username);
            return ServerResponse.ok().body(true);
        });
        return builder.build();
    }

    @ConditionalOnMissingBean(IChat.class)
    @Bean
    public IChat chat(ChatClient chatClient, Optional<RetrievalAugmentationAdvisor> retrievalAugmentationAdvisor, IMcp mcp, IEmbedTool embedTool, IUserConversation userConversation, IUser user, IFile file) {
        return new DefaultChat(chatClient, retrievalAugmentationAdvisor, mcp, embedTool, userConversation, user, file);
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
            // 1. 显式设置超时时间（单位毫秒），0 表示永不超时
            SseEmitter emitter = new SseEmitter(0L);

            // 2. 设置超时回调，防止连接泄露
            emitter.onTimeout(() -> {
                log.debug("SSE 链接超时");
                emitter.complete();
            });
            emitter.onCompletion(() -> log.debug("SSE 链接完成"));
            emitter.onError(e -> log.debug("SSE 链接错误：{}", e.getMessage()));


            // 3. 在异步线程中处理 AI 请求，避免阻塞 Tomcat 线程
            CompletableFuture.runAsync(() -> {
                try {
                    // 4. 订阅 Flux 流并将数据发送给 emitter
                    Flux<ChatResponse> chatResponseFlux = chat.stream(chatRecord, request);

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
}
