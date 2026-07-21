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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
import java.time.Instant;
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
        "org.springframework.ai.mcp.client.common.autoconfigure.annotations.McpClientAnnotationScannerAutoConfiguration",
        // flex-schedule (so @ConditionalOnBean(flexScheduledTaskService) in ScheduleConfiguration sees the bean)
        "cn.wubo.flex.schedule.autoconfigure.FlexScheduleAutoConfiguration"})
public class LoomAgentConfiguration {

    // ==================== Infrastructure ====================

    @Configuration
    static class InfrastructureConfiguration {

        private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(InfrastructureConfiguration.class);

        /**
         * 放宽 Spring AI 内部 {@code JsonParser} 使用的 ObjectMapper，
         * 允许 JS 风格注释（{@code //}、{@code /* *}{@code /}）和单引号。
         * <p>
         * 部分 LLM（特别是 qwen 系列）在工具调用时输出的 JSON 会带 JS 注释，
         * 默认的 Jackson 配置会抛 {@code JsonParseException: Unexpected character ('/') ... maybe a comment}，
         * 整条工具链直接断掉。开启 {@code ALLOW_COMMENTS} 后这类 LLM 输出能正常解析。
         */
        @Bean
        public org.springframework.beans.factory.SmartInitializingSingleton springAiJsonParserConfig() {
            return () -> {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper om =
                            org.springframework.ai.util.json.JsonParser.getObjectMapper();
                    om.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);
                    om.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_YAML_COMMENTS, true);
                    om.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
                    om.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
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
                // mcps 已迁移到数据库，配置不再需要
                // skills 已迁移到 DB（V10），不从 yml 读
                properties.setJvector(bound.getJvector());
                properties.setTimezone(bound.getTimezone());
                properties.setLoomHome(bound.getLoomHome());
                properties.setFileBasePath(bound.getFileBasePath());
                properties.setKnowledgeBasePath(bound.getKnowledgeBasePath());
                properties.setDatasourceDir(bound.getDatasourceDir());
                properties.setGitUsername(bound.getGitUsername());
                properties.setGitToken(bound.getGitToken());
                properties.setAuth(bound.getAuth());
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
         * 库的 SQL 用 V1.0 版本号（与业务的 V1.1 区分），走 Spring Boot 默认 Flyway 实例
         * （classpath:db/migration + flyway_schema_history）。这样库和业务模块都在 db/migration，
         * 业务模块开发者按 Flyway 默认规则写 SQL 即可。
         * 版本号字典序：V1.0 < V1.1，库 SQL 先建表 + admin，业务的 V1.1 后 seed mcp / skill。
         */
        @Bean
        public org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer libraryFlywayCustomizer() {
            return configuration -> {
                // baseline-on-migrate 让空 schema 也能跑（V1.0 库 + V1.1 业务都能跑）
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
        public ChatClient chatClient(ChatModel chatModel,
                                     @Qualifier("messageChatMemoryAdvisor") MessageChatMemoryAdvisor messageChatMemoryAdvisor,
                                     LoomAgentProperties properties) {
            ChatClient.Builder builder = ChatClient.builder(chatModel);
            if (properties.getDefaultSystem() != null) builder.defaultSystem(properties.getDefaultSystem());
            builder.defaultAdvisors(messageChatMemoryAdvisor, // chat-memory advisor (bean, so sub-task executor can also reuse it)
                    new SimpleLoggerAdvisor() // logger advisor
            );
            return builder.build();
        }

        /**
         * 暴露 {@link MessageChatMemoryAdvisor} 为独立 bean —— 之前只在
         * {@code ChatClient.Builder.defaultAdvisors(...)} 里 inline 构造,
         * 没有暴露,导致 {@code SubTaskConfiguration#defaultSubTaskExecutor} 通过
         * {@code ObjectProvider.getIfAvailable()} 拿到 null,在
         * {@code DefaultSubTaskExecutor#doExecute} 第 181 行
         * {@code spec.advisors(memoryAdvisor)} 抛出
         * {@code IllegalArgumentException: advisors cannot contain null elements},
         * 子任务每次都被 fail,history 永远为空。
         */
        @Bean
        public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
            return MessageChatMemoryAdvisor.builder(chatMemory).build();
        }

        @ConditionalOnMissingBean(IChat.class)
        @Bean
        public IChat chat(@Qualifier("chatClient") ChatClient chatClient, Optional<RetrievalAugmentationAdvisor> retrievalAugmentationAdvisor, IMcp mcp,
                          // @Lazy on the tools list breaks a 3-hop circular dep:
                          // chat -> List<IEmbedTool> (eagerly resolves defaultSubTaskTool)
                          //   -> defaultSubTaskExecutor
                          //     -> loomSubTaskChatClient
                          //       -> List<IEmbedTool> (would re-enter)
                          // With @Lazy, the list is materialised on first access (inside
                          // DefaultChat.stream()) after every bean is fully constructed.
                          @Lazy java.util.List<cn.wubo.spring.ai.loom.agent.tool.IEmbedTool> embedTools,
                          IUserConversation userConversation, IFile file) {
            return new DefaultChat(chatClient, retrievalAugmentationAdvisor, mcp, embedTools, userConversation, file);
        }

        @Slf4j
        @Data
        @RequiredArgsConstructor
        @RestController
        @RequestMapping
        public static class SseController {

            private final IChat chat;
            private final cn.wubo.spring.ai.loom.agent.token.ITokenUsage tokenUsage;
            private final cn.wubo.spring.ai.loom.agent.stream.SseEmitterRegistry emitterRegistry;

            @PostMapping(value = "/spring/ai/loom/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
            public SseEmitter stream(@RequestBody ChatRequestRecord chatRecord, HttpServletRequest request) {
                SseEmitter emitter = new SseEmitter(0L);

                String username = UserContextHolder.getCurrentUser();
                final long startMs = System.currentTimeMillis();
                final String conversationId = chatRecord.conversationId();
                // DashScope 流式每个 chunk 都带累计 usage（最后 chunk 是最终值）。
                // 用 holder 记录最后一次，stream 结束时统一入库（避免一条对话记 N 行）。
                final int[] finalPrompt = {0};
                final int[] finalCompletion = {0};
                final int[] finalTotal = {0};
                final String[] finalModel = {null};
                final boolean[] hasUsage = {false};

                // 注册到 registry：disposable 暂存 wrapper；onStop 回调用于在停止时记录累积 usage
                final java.util.concurrent.atomic.AtomicReference<reactor.core.Disposable> subRef = new java.util.concurrent.atomic.AtomicReference<>();
                emitterRegistry.register(username, conversationId, emitter,
                        new reactor.core.Disposable() {
                            @Override public void dispose() {
                                reactor.core.Disposable d = subRef.get();
                                if (d != null && !d.isDisposed()) d.dispose();
                            }
                            @Override public boolean isDisposed() {
                                reactor.core.Disposable d = subRef.get();
                                return d == null || d.isDisposed();
                            }
                        },
                        () -> {
                            // 用户主动 stop 时触发：把已经累积的 usage 入库（避免丢数据）
                            if (hasUsage[0]) {
                                try {
                                    tokenUsage.record(
                                            conversationId, username, "ASSISTANT",
                                            finalPrompt[0], finalCompletion[0], finalTotal[0],
                                            finalModel[0],
                                            (int) (System.currentTimeMillis() - startMs));
                                } catch (Exception ignore) { /* 重复记录不阻塞 */ }
                            }
                        });

                // 注册 lifecycle 自动清理
                emitter.onTimeout(() -> {
                    log.debug("SSE 链接超时: user={} conv={}", username, conversationId);
                    emitterRegistry.autoCleanup(username, conversationId);
                    emitter.complete();
                });
                emitter.onCompletion(() -> {
                    log.debug("SSE 链接完成: user={} conv={}", username, conversationId);
                    emitterRegistry.autoCleanup(username, conversationId);
                });
                emitter.onError(e -> {
                    log.debug("SSE 链接错误: user={} conv={} err={}", username, conversationId, e.getMessage());
                    emitterRegistry.autoCleanup(username, conversationId);
                });
                CompletableFuture.runAsync(() -> {
                    try {
                        Flux<ChatResponse> chatResponseFlux = chat.stream(chatRecord, username, request);

                        chatResponseFlux
                                .filter(chatResponse -> chatResponse.getResult() != null)
                                .subscribe(chatResponse -> {
                            try {
                                String reasoningContent = (String) chatResponse.getResult().getOutput().getMetadata().get("reasoningContent");
                                emitter.send(new ChatResponseRecord(chatResponse.getResult().getOutput().getText(), reasoningContent), MediaType.APPLICATION_JSON);
                                // 累计 usage：每个 chunk 都返累计值，只保留最后
                                try {
                                    var respMeta = chatResponse.getMetadata();
                                    if (respMeta != null && respMeta.getUsage() != null) {
                                        var usage = respMeta.getUsage();
                                        Integer pt = usage.getPromptTokens();
                                        Integer ct = usage.getCompletionTokens();
                                        Integer tt = usage.getTotalTokens();
                                        if (tt != null && tt > 0) {
                                            if (pt != null) finalPrompt[0] = pt;
                                            if (ct != null) finalCompletion[0] = ct;
                                            finalTotal[0] = tt;
                                            finalModel[0] = respMeta.getModel();
                                            hasUsage[0] = true;
                                        }
                                    }
                                } catch (Exception ignore) {
                                    // 抓 usage 失败不阻塞流
                                }
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        }, emitter::completeWithError, () -> {
                            // 流结束：把累计的 usage 一次性入库
                            if (hasUsage[0]) {
                                try {
                                    tokenUsage.record(
                                            conversationId, username, "ASSISTANT",
                                            finalPrompt[0], finalCompletion[0], finalTotal[0],
                                            finalModel[0],
                                            (int) (System.currentTimeMillis() - startMs));
                                } catch (Exception e) {
                                    log.debug("记录 token usage 失败：{}", e.getMessage());
                                }
                            }
                            emitterRegistry.autoCleanup(username, conversationId);
                            emitter.complete();
                        });
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                });

                return emitter;
            }

            /** 主动停止某个会话的 AI 流（前端"停止"按钮调用） */
            @PostMapping(value = "/spring/ai/loom/stream/{conversationId}/stop")
            public java.util.Map<String, Object> stopStream(@PathVariable("conversationId") String conversationId) {
                String username = UserContextHolder.getCurrentUser();
                boolean stopped = emitterRegistry.stop(username, conversationId);
                return java.util.Map.of("stopped", stopped, "conversationId", conversationId);
            }

            /** 调试用：当前用户的活跃流 */
            @GetMapping("/spring/ai/loom/stream/active")
            public java.util.Map<String, Object> activeStreams() {
                String username = UserContextHolder.getCurrentUser();
                return java.util.Map.of(
                        "user", username,
                        "conversations", emitterRegistry.activeSnapshot().getOrDefault(username, java.util.Set.of()),
                        "totalActive", emitterRegistry.activeCount());
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

        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.rbac.IRoleService.class)
        @Bean
        public cn.wubo.spring.ai.loom.agent.rbac.IRoleService defaultRoleService(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                                                                               cn.wubo.spring.ai.loom.agent.rbac.IMcpServerAdmin mcpServerAdmin) {
            return new cn.wubo.spring.ai.loom.agent.rbac.DefaultRoleService(jdbcTemplate, mcpServerAdmin);
        }

        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.rbac.IMcpServerAdmin.class)
        @Bean
        public cn.wubo.spring.ai.loom.agent.rbac.IMcpServerAdmin defaultMcpServerAdmin(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                                                                                       org.springframework.beans.factory.ObjectProvider<cn.wubo.spring.ai.loom.agent.mcp.IMcp> mcpProvider) {
            return new cn.wubo.spring.ai.loom.agent.rbac.DefaultMcpServerAdmin(jdbcTemplate, mcpProvider);
        }

        @ConditionalOnProperty(name = "spring.ai.mcp.client.stdio", havingValue = "ASYNC")
        @Bean
        public IMcp aSyncMcp(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                              List<McpAsyncClient> mcpAsyncClients,
                              cn.wubo.spring.ai.loom.agent.rbac.IRoleService roleService) {
            return new ASyncMcp(jdbcTemplate, mcpAsyncClients, roleService);
        }

        @ConditionalOnMissingBean
        @Bean
        public IMcp syncMcp(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                            List<McpSyncClient> mcpSyncClients,
                            cn.wubo.spring.ai.loom.agent.rbac.IRoleService roleService) {
            return new SyncMcp(jdbcTemplate, mcpSyncClients, roleService);
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

    // ==================== Sub-task ====================

    /**
     * 子任务基础设施：构造过滤版 ChatClient（排除 ISubTaskTool / IScheduleTool 防 LLM 自递归）、
     * 专用线程池、Registry、Executor 以及 BFF 路由。
     * <p>
     * 完整的 LLM 工具（{@code ISubTaskTool} / {@code IScheduleTool} 的实现类）在 Task 3.1/3.2/4.1/4.2
     * 才加入 —— 本配置只搭骨架。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "spring.ai.loom.agent.subtask.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor.class)
    @Slf4j
    public static class SubTaskConfiguration {

        /**
         * 构建子任务专用 ChatClient：复用主对话的 ChatModel + memory，
         * 但工具集合过滤掉 {@link cn.wubo.spring.ai.loom.agent.subtask.ISubTaskTool} 和
         * {@link cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool}，从源头杜绝 LLM 自递归。
         * <p>
         * 启动时构造一次。RAG / File / MCP 在 Phase 3 通过 ChatRequestComposer 注入；
         * 当前只 bake {@code IEmbedTool} 集合。
         */
        // NOTE: removed the independent `loomSubTaskChatClient` bean. It was the root
        // cause of a 3-hop cycle: chat -> List<IEmbedTool> -> defaultSubTaskTool ->
        // defaultSubTaskExecutor -> loomSubTaskChatClient -> List<IEmbedTool>.
        //
        // The sub-task executor now reuses the main `chatClient` bean directly. Tools
        // (filtered to exclude ISubTaskTool/IScheduleTool) and MCP callbacks are
        // attached per-call inside `DefaultSubTaskExecutor.doExecute(...)`, after
        // the bean graph is fully resolved. This avoids eager circular resolution.

        /**
         * 子任务专用线程池：bounded queue、corePool = maxPool = maxConcurrent（默认 4）。
         * <p>
         * 返回 {@link java.util.concurrent.ExecutorService}（接口）而不是
         * {@link org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor}
         * （具体类），因为 Spring 在返回接口类型时只能暴露 {@code getThreadPoolExecutor()}
         * 这一入口，更便于将来切换实现。
         */
        @Bean(name = "loomSubTaskExecutor", destroyMethod = "shutdown")
        public java.util.concurrent.ExecutorService loomSubTaskExecutor(LoomAgentProperties properties) {
            int n = Math.max(1, properties.getSubtask().getMaxConcurrent());
            org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor exec =
                    new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
            exec.setCorePoolSize(n);
            exec.setMaxPoolSize(n);
            exec.setQueueCapacity(50);
            exec.setThreadNamePrefix("loom-subtask-");
            exec.setWaitForTasksToCompleteOnShutdown(true);
            exec.setAwaitTerminationSeconds(10);
            exec.initialize();
            log.info("loomSubTaskExecutor initialized: pool={}, queue=50", n);
            return exec.getThreadPoolExecutor();
        }

        @Bean
        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.subtask.ILoomSubTaskHistoryRepository.class)
        public cn.wubo.spring.ai.loom.agent.subtask.ILoomSubTaskHistoryRepository loomSubTaskHistoryRepository(
                JdbcTemplate jdbcTemplate) {
            cn.wubo.spring.ai.loom.agent.subtask.JdbcLoomSubTaskHistoryRepository repo =
                    new cn.wubo.spring.ai.loom.agent.subtask.JdbcLoomSubTaskHistoryRepository(jdbcTemplate);
            repo.ensureSchema();
            log.info("JdbcLoomSubTaskHistoryRepository wired (table = loom_subtask_history)");
            return repo;
        }

        @Bean
        public cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry subTaskRegistry(
                LoomAgentProperties properties,
                cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor subTaskExecutor,
                cn.wubo.spring.ai.loom.agent.subtask.ILoomSubTaskHistoryRepository subTaskHistoryRepo) {
            // Wire the cancel hook so SubTaskRegistry.kill(id) actually interrupts the
            // worker thread via subTaskExecutor::cancel(id) rather than just marking the
            // record CANCELLED. Also wire a write-through hook that mirrors every
            // terminal record to loom_subtask_history so history survives restarts.
            return new cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry(
                    properties.getSubtask().getMaxConcurrent(),
                    properties.getSubtask().getMaxHistory(),
                    subTaskExecutor::cancel,
                    subTaskHistoryRepo::save);
        }

        /**
         * Cold-start rehydration: on ApplicationReadyEvent, load up to maxHistory
         * most-recent records per user from H2 into the in-memory registry so the
         * very first API call after restart already has full history visible
         * (otherwise the in-memory deque starts empty until something fires).
         */
        @Bean
        public cn.wubo.spring.ai.loom.agent.subtask.SubTaskHistoryPreloader subTaskHistoryPreloader(
                cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry registry,
                cn.wubo.spring.ai.loom.agent.subtask.ILoomSubTaskHistoryRepository repo,
                LoomAgentProperties properties) {
            return new cn.wubo.spring.ai.loom.agent.subtask.SubTaskHistoryPreloader(
                    registry, repo, properties.getSubtask().getMaxHistory());
        }

        @Bean
        public cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor defaultSubTaskExecutor(
                @Qualifier("chatClient") ChatClient chatClient,
                @Qualifier("messageChatMemoryAdvisor") MessageChatMemoryAdvisor memoryAdvisor,
                @Qualifier("loomSubTaskExecutor") java.util.concurrent.ExecutorService loomSubTaskExecutor,
                cn.wubo.spring.ai.loom.agent.mcp.IMcp mcp,
                // Lazy lookup: the executor ALSO passes a cancel hook back to
                // SubTaskRegistry's constructor (which needs the executor as a
                // parameter). Direct injection of SubTaskRegistry here would
                // close a circular bean graph. Deferred resolution breaks
                // the cycle: by the time the executor's first execute() call
                // runs, the registry has already been created.
                @Lazy cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry subTaskRegistry,
                // Lazy lookup: the embedTools list is materialized on first use
                // inside DefaultSubTaskExecutor.doExecute() (a worker thread, after
                // Spring startup completes). This breaks the cycle where chat ->
                // List<IEmbedTool> -> defaultSubTaskTool -> defaultSubTaskExecutor ->
                // [embedTools] would force eager resolution of the still-creating
                // defaultSubTaskTool bean.
                @Lazy java.util.List<cn.wubo.spring.ai.loom.agent.tool.IEmbedTool> embedTools) {
            return new cn.wubo.spring.ai.loom.agent.subtask.DefaultSubTaskExecutor(
                    chatClient, memoryAdvisor, loomSubTaskExecutor, mcp, embedTools,
                    subTaskRegistry);
        }

        /**
         * 子任务 CRUD 路由：active list / history list / kill。
         * 完整的任务列表 / 详情由后续 phase 补齐。
         */
        /**
         * 默认子任务工具 bean 注册到主对话工具列表。
         * 因为 {@code ISubTaskTool extends IEmbedTool},此 bean 会被 Spring AI 自动收集到
         * {@code ChatConfiguration#chat(...)} 注入的 {@code List<IEmbedTool>} 里,无需额外配置。
         */
        @Bean
        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.subtask.ISubTaskTool.class)
        public cn.wubo.spring.ai.loom.agent.subtask.ISubTaskTool defaultSubTaskTool(
                cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor executor,
                cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry registry) {
            return new cn.wubo.spring.ai.loom.agent.subtask.DefaultSubTaskTool(executor, registry);
        }

        /**
         * 子任务 CRUD 路由: active list / history list / kill。
         * 完整的任务列表 / 详情由后续 phase 补齐。
         */
        @Bean("loomAgentSubTaskRouter")
        public RouterFunction<ServerResponse> loomAgentSubTaskRouter(
                cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry registry,
                cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties properties,
                cn.wubo.spring.ai.loom.agent.subtask.ILoomSubTaskHistoryRepository loomSubTaskHistoryRepository) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            // Configured sub-task limits so the UI hint reflects the actual
            // spring.ai.loom.agent.subtask.* instead of a hardcoded "200".
            builder.GET("spring/ai/loom/subtask/limits", request -> {
                java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("maxHistory", properties.getSubtask().getMaxHistory());
                body.put("maxConcurrent", properties.getSubtask().getMaxConcurrent());
                return ServerResponse.ok().body(body);
            });
            // active (with optional ?conversationId= filter)
            builder.GET("spring/ai/loom/subtask/list/active",
                    request -> {
                        String user = cn.wubo.spring.ai.loom.agent.user.UserContextHolder.getCurrentUser();
                        String conv = request.param("conversationId").orElse(null);
                        java.util.List<cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry.SubTaskRecord> body =
                                conv == null
                                        ? registry.listActive(user)
                                        : registry.listActiveByConversation(user, conv);
                        return ServerResponse.ok().body(body);
                    });
            // history (with optional ?conversationId= filter)
            builder.GET("spring/ai/loom/subtask/list/history",
                    request -> {
                        String user = cn.wubo.spring.ai.loom.agent.user.UserContextHolder.getCurrentUser();
                        String conv = request.param("conversationId").orElse(null);
                        java.util.List<cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry.SubTaskRecord> body =
                                conv == null
                                        ? registry.listHistory(user, 50)
                                        : registry.listHistoryByConversation(user, conv, 50);
                        return ServerResponse.ok().body(body);
                    });
            builder.POST("spring/ai/loom/subtask/kill/{id}",
                    request -> {
                        // Fix for BUG-RBAC-SUBTASK-KILL: pass the current user to
                        // SubTaskRegistry.kill(username, id) so cross-user cancel
                        // attempts are refused. The router previously only passed
                        // id, letting any authenticated user cancel any RUNNING
                        // sub-task that wasn't theirs.
                        String user = cn.wubo.spring.ai.loom.agent.user.UserContextHolder.getCurrentUser();
                        String id = request.pathVariable("id");
                        boolean killed = registry.kill(user, id);
                        return ServerResponse.ok().body(killed);
                    });
            // Delete a (typically CANCELLED or COMPLETED) sub-task history row.
            // The kill endpoint above only marks active tasks CANCELLED; this
            // one strips the in-memory + H2 history row for finished records.
            builder.DELETE("spring/ai/loom/subtask/history/{id}", request -> {
                String user = cn.wubo.spring.ai.loom.agent.user.UserContextHolder.getCurrentUser();
                String id = request.pathVariable("id");
                boolean ok = loomSubTaskHistoryRepository != null
                        && loomSubTaskHistoryRepository.deleteById(user, id);
                return ok
                        ? ServerResponse.ok().body(true)
                        : ServerResponse.notFound().build();
            });
            return builder.build();
        }
    }

    // ==================== Schedule ====================

    /**
     * 定时任务配置：注册 {@link cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool} 及其 BFF 路由。
     * <p>
     * 仅当 flex-schedule 在 classpath 且 {@code flexScheduledTaskService} bean 存在时启用。
     * 触发间隔/存活上限由 flex-schedule 的 {@code flex.schedule.limits.*} 强校验。
     * <p>
     * 该配置同时注册 loom-agent 自有的 H2 持久化层
     * ({@link cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleTriggerRepository})
     * 以及启动时的恢复监听器 ({@link cn.wubo.spring.ai.loom.agent.schedule.ScheduleRestoreListener}),
     * 用于在 ApplicationReadyEvent 阶段把持久化的定时任务重新装载回 flex-schedule。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "cn.wubo.flex.schedule.core.FlexScheduledTaskService")
    @ConditionalOnProperty(name = "spring.ai.loom.agent.schedule.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool.class)
    @Slf4j
    public static class ScheduleConfiguration {

        @Bean
        public cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool defaultScheduleTool(
                @Qualifier("flexScheduledTaskService") cn.wubo.flex.schedule.core.FlexScheduledTaskService flexService,
                cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor subTaskExecutor,
                cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleTriggerRepository loomScheduleTriggerRepository,
                cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleExecutionRepository loomScheduleExecutionRepository,
                cn.wubo.spring.ai.loom.agent.schedule.ScheduleExecutionProperties execProps) {
            return new cn.wubo.spring.ai.loom.agent.schedule.DefaultScheduleTool(
                    flexService, subTaskExecutor, loomScheduleTriggerRepository,
                    loomScheduleExecutionRepository, execProps.getMaxPerTask());
        }

        @Bean
        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleTriggerRepository.class)
        public cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleTriggerRepository loomScheduleTriggerRepository(
                JdbcTemplate jdbcTemplate) {
            cn.wubo.spring.ai.loom.agent.schedule.JdbcLoomScheduleTriggerRepository repo =
                    new cn.wubo.spring.ai.loom.agent.schedule.JdbcLoomScheduleTriggerRepository(jdbcTemplate);
            repo.ensureSchema();
            log.info("JdbcLoomScheduleTriggerRepository wired (table = loom_scheduled_task)");
            return repo;
        }

        @Bean
        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleExecutionRepository.class)
        public cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleExecutionRepository loomScheduleExecutionRepository(
                JdbcTemplate jdbcTemplate) {
            cn.wubo.spring.ai.loom.agent.schedule.JdbcLoomScheduleExecutionRepository repo =
                    new cn.wubo.spring.ai.loom.agent.schedule.JdbcLoomScheduleExecutionRepository(jdbcTemplate);
            repo.ensureSchema();
            log.info("JdbcLoomScheduleExecutionRepository wired (table = loom_schedule_execution)");
            return repo;
        }

        @Bean
        public cn.wubo.spring.ai.loom.agent.schedule.ScheduleRestoreListener scheduleRestoreListener(
                @Qualifier("flexScheduledTaskService") cn.wubo.flex.schedule.core.FlexScheduledTaskService flexService,
                cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleTriggerRepository loomScheduleTriggerRepository,
                cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleExecutionRepository loomScheduleExecutionRepository,
                cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor subTaskExecutor,
                cn.wubo.flex.schedule.core.TaskLimits taskLimits,
                cn.wubo.spring.ai.loom.agent.schedule.ScheduleExecutionProperties execProps) {
            return new cn.wubo.spring.ai.loom.agent.schedule.ScheduleRestoreListener(
                    flexService, loomScheduleTriggerRepository, subTaskExecutor, taskLimits,
                    loomScheduleExecutionRepository, execProps.getMaxPerTask());
        }

        @Bean
        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.schedule.ScheduleExecutionProperties.class)
        public cn.wubo.spring.ai.loom.agent.schedule.ScheduleExecutionProperties scheduleExecutionProperties() {
            return new cn.wubo.spring.ai.loom.agent.schedule.ScheduleExecutionProperties();
        }

        /**
         * Daily cleanup task that prunes {@code loom_schedule_execution} rows older
         * than the configured retention window (default 30 days). Wired here as a
         * {@code @Scheduled} method so it runs alongside the rest of the app's
         * scheduled jobs; on a real production deployment this should be moved
         * to a dedicated scheduler that survives app restarts, but for the
         * single-instance loom-agent use case this is fine.
         */
        @Bean
        public cn.wubo.spring.ai.loom.agent.schedule.ScheduleExecutionCleanup scheduleExecutionCleanup(
                cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleExecutionRepository repo,
                cn.wubo.spring.ai.loom.agent.schedule.ScheduleExecutionProperties props) {
            return new cn.wubo.spring.ai.loom.agent.schedule.ScheduleExecutionCleanup(repo, props);
        }

        @Bean("loomAgentScheduleRouter")
        public RouterFunction<ServerResponse> loomAgentScheduleRouter(
                @Qualifier("flexScheduledTaskService") cn.wubo.flex.schedule.core.FlexScheduledTaskService flexService,
                cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleTriggerRepository loomScheduleTriggerRepository,
                cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleExecutionRepository loomScheduleExecutionRepository,
                ObjectProvider<cn.wubo.flex.schedule.core.TaskLimits> taskLimitsProvider,
                cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool scheduleTool) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            // Expose the configured trigger limits so the UI hint reflects the
            // actual flex.schedule.limits.* instead of a hardcoded guess. Returns
            // {enforcing, minInterval, maxLifetime, mode} with durations formatted
            // compactly (e.g. "10m", "72h"); null when a limit is unset/disabled.
            builder.GET("spring/ai/loom/schedule/limits", request -> {
                cn.wubo.flex.schedule.core.TaskLimits limits = taskLimitsProvider.getIfAvailable();
                java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
                boolean enforcing = limits != null && limits.isEnforcing();
                body.put("enforcing", enforcing);
                body.put("minInterval", enforcing ? formatScheduleDuration(limits.minInterval()) : null);
                body.put("maxLifetime", (limits != null && limits.hasMaxLifetime())
                        ? formatScheduleDuration(limits.maxLifetime()) : null);
                body.put("mode", limits != null && limits.mode() != null ? limits.mode().toString() : null);
                return ServerResponse.ok().body(body);
            });
            // Structured list for the UI: this user's tasks only (TaskInfo{taskName,taskType,schedule}).
            // Optional ?conversationId= filter so the per-conversation schedule modal
            // can show just THIS conversation's currently-running tasks without
            // mixing in tasks from other conversations.
            builder.GET("spring/ai/loom/schedule/list", request -> {
                String user = cn.wubo.spring.ai.loom.agent.user.UserContextHolder.getCurrentUser();
                String convFilter = request.param("conversationId").orElse(null);
                String prefix = convFilter != null && !convFilter.isBlank()
                        ? "loom-sched-" + user + "-" + convFilter + "-"
                        : "loom-sched-" + user + "-";
                java.util.List<cn.wubo.flex.schedule.core.TaskInfo> list = flexService.listTasks().stream()
                        .filter(t -> t.taskName().startsWith(prefix))
                        .toList();
                return ServerResponse.ok().body(list);
            });
            // Bulk cancel every schedule bound to a conversation. Used by the
            // schedule modal's "全部停止" button. Goes through handleScheduleCancel
            // for each live task so the H2 row is deleted too (otherwise the
            // ScheduleRestoreListener would resurrect it on next restart).
            builder.POST("spring/ai/loom/schedule/by-conversation/{conversationId}/cancel-all",
                    request -> {
                        String user = cn.wubo.spring.ai.loom.agent.user.UserContextHolder.getCurrentUser();
                        String conv = request.pathVariable("conversationId");
                        String prefix = "loom-sched-" + user + "-" + conv + "-";
                        int cancelled = 0;
                        for (cn.wubo.flex.schedule.core.TaskInfo info : flexService.listTasks()) {
                            if (info.taskName().startsWith(prefix)) {
                                if (handleScheduleCancel(info.taskName(),
                                        flexService, loomScheduleTriggerRepository, log)) {
                                    cancelled++;
                                }
                            }
                        }
                        // Also drop the H2 rows that have no live counterpart
                        // (one_shots that fired and got auto-removed, but config
                        // rows are kept around for audit).
                        int rowsDeleted = 0;
                        try {
                            rowsDeleted = loomScheduleTriggerRepository.deleteAllForConversation(user, conv);
                        } catch (Exception e) {
                            log.warn("cancel-all: H2 cleanup failed: {}", e.getMessage());
                        }
                        // And the audit-trail execution rows.
                        int execRowsDeleted = 0;
                        try {
                            execRowsDeleted = loomScheduleExecutionRepository
                                    .deleteByUserAndConversation(user, conv);
                        } catch (Exception e) {
                            log.warn("cancel-all: execution cleanup failed: {}", e.getMessage());
                        }
                        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
                        body.put("cancelled", cancelled);
                        body.put("rowsDeleted", rowsDeleted);
                        body.put("execRowsDeleted", execRowsDeleted);
                        log.info("schedule cancel-all: user={}, conv={}, cancelled={}, rowsDeleted={}, execRowsDeleted={}",
                                user, conv, cancelled, rowsDeleted, execRowsDeleted);
                        return ServerResponse.ok().body(body);
                    });
            // Frontend posts the FULL task name (already namespaced) in a JSON body {"name": "..."}.
            // IMPORTANT: also delete the corresponding loom_scheduled_task row — otherwise the
            // ScheduleRestoreListener would resurrect this task on the next restart. The
            // LLM-tool path (DefaultScheduleTool.cancelSchedule) was already doing this
            // twice over; we replicate it here for the REST path that the UI calls directly.
            builder.POST("spring/ai/loom/schedule/cancel", request -> {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> body = request.body(java.util.Map.class);
                String name = body != null ? (String) body.get("name") : null;
                boolean ok = handleScheduleCancel(name, flexService, loomScheduleTriggerRepository, log);
                if (!ok) {
                    return ServerResponse.status(HttpStatus.FORBIDDEN).body(
                            java.util.Map.of("message", "无权取消该定时任务或任务不存在"));
                }
                return ServerResponse.ok().body(true);
            });
            builder.GET("spring/ai/loom/schedule/history/{name}",
                    request -> ServerResponse.ok().body(
                            flexService.getExecutionHistory(request.pathVariable("name"), 50)));
            // Per-conversation schedule history.
            //   - source-of-truth for "what schedules exist for this conversation" is
            //     loom_scheduled_task (H2). This covers BOTH still-registered tasks
            //     (visible in flex-schedule runtime) AND one_shots that already fired
            //     and were auto-removed from flex-schedule's runtime.
            //   - execution events come from loom_schedule_execution (H2), which
            //     survives restarts and is not auto-trimmed on one_shot completion.
            // The previous implementation walked flexService.listTasks() only, which
            // silently dropped fired one_shots — fixed here.
            builder.GET("spring/ai/loom/schedule/history/by-conversation/{conversationId}",
                    request -> {
                        String user = cn.wubo.spring.ai.loom.agent.user.UserContextHolder.getCurrentUser();
                        String conv = request.pathVariable("conversationId");
                        String prefix = "loom-sched-" + user + "-" + conv + "-";
                        java.util.List<cn.wubo.spring.ai.loom.agent.schedule.LoomScheduleTriggerRecord> configs =
                                loomScheduleTriggerRepository.findByUserAndConv(user, conv);
                        java.util.Set<String> liveTaskNames = flexService.listTasks().stream()
                                .map(cn.wubo.flex.schedule.core.TaskInfo::taskName)
                                .collect(java.util.stream.Collectors.toSet());
                        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
                        for (cn.wubo.spring.ai.loom.agent.schedule.LoomScheduleTriggerRecord r : configs) {
                            if (!r.taskName().startsWith(prefix)) continue;
                            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
                            entry.put("taskName", r.taskName());
                            entry.put("taskType", r.scheduleType());
                            entry.put("schedule", formatSchedule(r));
                            entry.put("prompt", r.prompt());
                            entry.put("createdAt", r.createdAt().toString());
                            entry.put("paused", r.paused());
                            entry.put("live", liveTaskNames.contains(r.taskName()));
                            // Merged execution history: prefer H2 (durable), but include
                            // any in-memory rows flex-schedule still holds that aren't
                            // yet persisted (rare race window). For now we just use H2.
                            java.util.List<cn.wubo.spring.ai.loom.agent.schedule.LoomScheduleExecutionRecord> execs =
                                    loomScheduleExecutionRepository.findByTaskName(r.taskName(), 50);
                            java.util.List<java.util.Map<String, Object>> execOut = new java.util.ArrayList<>();
                            for (cn.wubo.spring.ai.loom.agent.schedule.LoomScheduleExecutionRecord e : execs) {
                                java.util.Map<String, Object> er = new java.util.LinkedHashMap<>();
                                er.put("executionId", e.executionId());
                                er.put("fireTime", e.fireTime().toString());
                                er.put("durationMs", e.durationMs());
                                er.put("success", e.success());
                                er.put("errorMessage", e.errorMessage());
                                er.put("firedBy", e.firedBy());
                                execOut.add(er);
                            }
                            entry.put("executions", execOut);
                            out.add(entry);
                        }
                        return ServerResponse.ok().body(out);
                    });
            return builder.build();
        }

        private static String formatSchedule(cn.wubo.spring.ai.loom.agent.schedule.LoomScheduleTriggerRecord r) {
            return switch (r.scheduleType()) {
                case cn.wubo.spring.ai.loom.agent.schedule.LoomScheduleTriggerRecord.TYPE_CRON ->
                        "cron=" + (r.cronExpression() == null ? "?" : r.cronExpression());
                case cn.wubo.spring.ai.loom.agent.schedule.LoomScheduleTriggerRecord.TYPE_FIXED_DELAY ->
                        "fixed_delay=" + (r.intervalSeconds() == null ? "?" : r.intervalSeconds() + "s");
                case cn.wubo.spring.ai.loom.agent.schedule.LoomScheduleTriggerRecord.TYPE_FIXED_RATE ->
                        "fixed_rate=" + (r.intervalSeconds() == null ? "?" : r.intervalSeconds() + "s");
                case cn.wubo.spring.ai.loom.agent.schedule.LoomScheduleTriggerRecord.TYPE_ONE_SHOT ->
                        "one_shot_delay=" + (r.oneShotDelaySeconds() == null ? "?" : r.oneShotDelaySeconds() + "s");
                default -> r.scheduleType();
            };
        }
    }

    // ==================== Storage ====================

    @Configuration
    static class StorageConfiguration {

        @ConditionalOnMissingBean(IUser.class)
        @Bean
        public IUser defaultUser(JdbcTemplate jdbcTemplate, org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder passwordEncoder, Cache sessionCache) {
            return new DefaultUser(jdbcTemplate, passwordEncoder, sessionCache);
        }

        @Bean
        public org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder passwordEncoder() {
            return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        }

        /**
         * 初始管理员账户的种子数据已迁移到 V3__seed_default_admin.sql
         * （硬编码 BCrypt hash "123456"）。这样不再依赖 Java runner，
         * 也方便 DBA 在 SQL 里直接管理。
         */

        @ConditionalOnMissingBean(IUserConversation.class)
        @Bean
        public IUserConversation defaultUserConversation(JdbcTemplate jdbcTemplate, ChatMemory chatMemory, org.springframework.cache.Cache sessionCache) {
            return new DefaultUserConversation(jdbcTemplate, chatMemory, sessionCache);
        }

        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.token.ITokenUsage.class)
        @Bean
        public cn.wubo.spring.ai.loom.agent.token.ITokenUsage defaultTokenUsage(JdbcTemplate jdbcTemplate) {
            return new cn.wubo.spring.ai.loom.agent.token.DefaultTokenUsage(jdbcTemplate);
        }

        @ConditionalOnMissingBean(ISkillStorage.class)
        @Bean
        public ISkillStorage defaultSkillStorage(JdbcTemplate jdbcTemplate, ResourceLoader resourceLoader,
                                                  cn.wubo.spring.ai.loom.agent.skill.ISkillRoleAdmin roleAdmin,
                                                  IUser user) {
            return new DefaultSkillStorage(jdbcTemplate, resourceLoader, roleAdmin, user);
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

    /**
     * 删除会话时的资源清理：先杀掉该会话名下所有在飞子任务，再取消该会话名下所有定时任务，
     * 最后由调用方软删 user_conversation 映射。
     * <p>
     * 三个依赖都可缺省(subtask/schedule 功能可被关闭)，缺省时对应清理跳过。
     *
     * @return {@code [subtasksKilled, schedulesCancelled, scheduleRowsDeleted]}
     */
    static int[] cleanupConversationResources(
            String conversationId,
            String username,
            cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry registry,
            cn.wubo.flex.schedule.core.FlexScheduledTaskService flexService,
            cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleTriggerRepository loomScheduleTriggerRepository,
            cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleExecutionRepository loomScheduleExecutionRepository,
            cn.wubo.spring.ai.loom.agent.subtask.ILoomSubTaskHistoryRepository loomSubTaskHistoryRepository) {
        // 1. Stop active sub-tasks (cancels the running workers via the cancel hook).
        int subtasksKilled = registry != null ? registry.killAllByConversation(conversationId) : 0;
        // 2. Cancel every flex-schedule task bound to this conversation in the runtime.
        int schedulesCancelled = 0;
        if (flexService != null && username != null && conversationId != null) {
            String prefix = "loom-sched-" + username + "-" + conversationId + "-";
            for (cn.wubo.flex.schedule.core.TaskInfo info : flexService.listTasks()) {
                if (info.taskName().startsWith(prefix)) {
                    flexService.cancel(info.taskName());
                    schedulesCancelled++;
                }
            }
        }
        // 3. Drop the schedule declarations from H2 (loom_scheduled_task).
        int scheduleRowsDeleted = (loomScheduleTriggerRepository != null && username != null && conversationId != null)
                ? loomScheduleTriggerRepository.deleteAllForConversation(username, conversationId)
                : 0;
        // 4. Drop the schedule execution-event audit trail from H2 (loom_schedule_execution).
        int scheduleExecRowsDeleted = (loomScheduleExecutionRepository != null && username != null && conversationId != null)
                ? loomScheduleExecutionRepository.deleteByUserAndConversation(username, conversationId)
                : 0;
        // 5. Drop the sub-task H2 history (loom_subtask_history) and clear the
        //    in-memory deque for this conversation so the API no longer serves
        //    ghost records from the dying conversation.
        int subTaskHistoryRowsDeleted = (loomSubTaskHistoryRepository != null && username != null && conversationId != null)
                ? loomSubTaskHistoryRepository.deleteAllByConversation(username, conversationId)
                : 0;
        int subTaskDequeCleared = (registry != null && username != null && conversationId != null)
                ? registry.removeAllByConversation(username, conversationId)
                : 0;
        return new int[]{subtasksKilled, schedulesCancelled, scheduleRowsDeleted,
                scheduleExecRowsDeleted, subTaskHistoryRowsDeleted, subTaskDequeCleared};
    }

    /**
     * Dual-write cancel handler for the REST {@code POST /spring/ai/loom/schedule/cancel}
     * route. Cancels the in-memory task AND deletes the corresponding
     * {@code loom_scheduled_task} row so the {@link cn.wubo.spring.ai.loom.agent.schedule.ScheduleRestoreListener}
     * doesn't resurrect it on the next restart. Package-private + static so the
     * {@code loomAgentScheduleRouterCancelRegressionTest} can drive it without
     * needing the full Spring Web reactive test apparatus.
     *
     * <p>Failure of the repository delete is logged at WARN (and swallowed) so
     * the user-facing cancel still reports success — the only state that
     * matters for end users is that the live task is gone; a stuck persistent
     * row can be cleaned up by an ops tool.</p>
     *
     * @return {@code true} if the cancel ran end-to-end; {@code false} if name is null.
     */
    public static boolean handleScheduleCancel(String fullName,
                                         cn.wubo.flex.schedule.core.FlexScheduledTaskService flexService,
                                         cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleTriggerRepository repo,
                                         org.slf4j.Logger log) {
        if (fullName == null) {
            return false;
        }
        // BUG-13: cross-user schedule cancel. Before letting flex-schedule
        // fire the cancellation, verify the row is owned by the calling
        // user (resolved from the AuthenticationFilter-then-set
        // UserContextHolder). Without this any logged-in user could cancel
        // someone else's task by guessing the namespaced name. The lookup is
        // by PK so no enumeration is exposed; the response is identical
        // regardless of "not found" vs "owned by someone else".
        String caller = cn.wubo.spring.ai.loom.agent.user.UserContextHolder.getCurrentUser();
        try {
            var rec = repo.findByName(fullName);
            if (rec.isEmpty() || caller == null || !caller.equals(rec.get().username())) {
                log.warn("拒绝跨用户取消 REST 调用: caller={}, target={}, owner={}",
                        caller, fullName,
                        rec.map(cn.wubo.spring.ai.loom.agent.schedule.LoomScheduleTriggerRecord::username).orElse("<none>"));
                return false;
            }
        } catch (Exception e) {
            log.warn("schedule cancel 跨用户检查失败: name={}, err={}", fullName, e.getMessage());
            return false;
        }
        flexService.cancel(fullName);
        try {
            repo.delete(fullName);
        } catch (Exception e) {
            log.warn("取消定时任务时删除持久化行失败: name={}", fullName, e);
        }
        return true;
    }

    /**
     * Formats a schedule limit {@link java.time.Duration} into the compact form
     * the UI hint shows ("5s" / "10m" / "72h" / "1d"). Returns {@code null} for a
     * null / zero / negative duration so the endpoint can omit an unset limit.
     */
    static String formatScheduleDuration(java.time.Duration d) {
        if (d == null || d.isZero() || d.isNegative()) {
            return null;
        }
        long s = d.getSeconds();
        if (s % 86400 == 0) return (s / 86400) + "d";
        if (s % 3600 == 0) return (s / 3600) + "h";
        if (s % 60 == 0) return (s / 60) + "m";
        return s + "s";
    }

    @Configuration
    @Slf4j
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
        public RouterFunction<ServerResponse> loomAgentBaseRouter(IUser user, LoomAgentProperties properties,
                                                                 IUserConversation userConversation,
                                                                 cn.wubo.spring.ai.loom.agent.token.ITokenUsage tokenUsage,
                                                                 cn.wubo.spring.ai.loom.agent.rbac.IRoleService roleService,
                                                                 cn.wubo.spring.ai.loom.agent.rbac.IMcpServerAdmin mcpServerAdmin,
                                                                 JdbcTemplate jdbcTemplate) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            builder.GET("spring/ai/loom", request -> ServerResponse.temporaryRedirect(URI.create("/spring/ai/loom/index.html")).build());

            // isAutoLogin: 仅根据 session cookie 判断是否已登录
            builder.POST("spring/ai/loom/user/isAutoLogin", request -> {
                String token = extractTokenFromCookies(request, properties.getAuth().getCookie().getName());
                boolean hasValidSession = token != null && user.validateToken(token);
                return ServerResponse.ok().body(hasValidSession);
            });

            // login: 校验 username + password，成功设 cookie
            builder.POST("spring/ai/loom/user/login", request -> {
                UserRequestRecord body = request.body(UserRequestRecord.class);
                UserResponseRecord response;
                try {
                    response = user.login(body);
                } catch (Exception e) {
                    // 凭据错误 / 用户不存在 / 已停用 → 401，而不是 500
                    return ServerResponse.status(HttpStatus.UNAUTHORIZED)
                            .body(java.util.Map.of("message", e.getMessage() == null ? "登录失败" : e.getMessage()));
                }
                String token = user.createToken(body.username());
                LoomAgentProperties.AuthProperty.CookieProperty cookieProp = properties.getAuth().getCookie();
                return ServerResponse.ok()
                        .cookie(createSessionCookie(token, cookieProp))
                        .body(new UserResponseRecord(token, response.nickname()));
            });

            // logout: 清除 token 和 cookie
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

            // currentIsAdmin: 当前用户是否管理员（需登录）
            builder.POST("spring/ai/loom/user/currentIsAdmin", request -> {
                String username = UserContextHolder.getCurrentUser();
                return ServerResponse.ok().body(user.isAdmin(username));
            });

            // currentUser: 返回当前用户信息（昵称 + 类型）
            builder.POST("spring/ai/loom/user/currentUser", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (username == null) {
                    return ServerResponse.ok().body(java.util.Map.of("username", "", "nickname", "", "type", ""));
                }
                String nickname = user.getNicknameByUsername(username);
                String type = user.isAdmin(username) ? "ADMIN" : "USER";
                return ServerResponse.ok().body(java.util.Map.of(
                        "username", username,
                        "nickname", nickname == null ? username : nickname,
                        "type", type));
            });

            // changePassword: 当前用户改密（需登录）
            builder.POST("spring/ai/loom/user/changePassword", request -> {
                String username = UserContextHolder.getCurrentUser();
                ChangePasswordRequest body = request.body(ChangePasswordRequest.class);
                user.changePassword(username, body.oldPassword(), body.newPassword());
                return ServerResponse.ok().body(true);
            });

            // 当前用户可见的 mcp（按角色过滤；admin 全可见）
            builder.GET("spring/ai/loom/mcps", request -> {
                String username = UserContextHolder.getCurrentUser();
                try {
                    return ServerResponse.ok().body(roleService.getVisibleMcpsForUser(username));
                } catch (Exception e) {
                    // 临时不可用（例如角色数据初始化失败）→ 返回空列表，避免页面 init 卡死
                    log.warn("/mcps degraded, returning empty list: {}", e.getMessage());
                    return ServerResponse.ok().body(java.util.Collections.emptyList());
                }
            });

            // 当前用户角色允许的 mcp 的工具列表
            builder.GET("/spring/ai/loom/mcps/{name}/tools", request -> {
                String username = UserContextHolder.getCurrentUser();
                String mcpName = request.pathVariable("name");
                boolean allowed = roleService.getVisibleMcpsForUser(username).stream()
                        .anyMatch(m -> m.name().equals(mcpName));
                if (!allowed) return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                return ServerResponse.ok().body(mcpServerAdmin.listTools(mcpName));
            });

            // 当前用户的角色列表
            builder.GET("spring/ai/loom/user/roles", request -> {
                String username = UserContextHolder.getCurrentUser();
                return ServerResponse.ok().body(roleService.getUserRoles(username));
            });

            // 管理员：用户列表
            builder.GET("spring/ai/loom/admin/users", request -> {
                return ServerResponse.ok().body(user.listAllUsers());
            });

            // 管理员：创建用户
            builder.POST("spring/ai/loom/admin/users", request -> {
                CreateUserRequest body = request.body(CreateUserRequest.class);
                user.createUser(body.username(), body.nickname(), body.password(), body.type());
                return ServerResponse.ok().body(true);
            });

            // 管理员：删除用户
            builder.DELETE("spring/ai/loom/admin/users/{username}", request -> {
                String username = request.pathVariable("username");
                user.deleteUser(username);
                return ServerResponse.ok().body(true);
            });

            // 管理员：列出某用户全部会话（含已软删 + content_cleaned 标记）
            builder.GET("spring/ai/loom/admin/users/{username}/conversations", request -> {
                String username = request.pathVariable("username");
                return ServerResponse.ok().body(userConversation.adminListByUsername(username));
            });

            // 管理员：列出会话每 turn 的 token + 内容
            builder.GET("spring/ai/loom/admin/conversations/{conversationId}/turns", request -> {
                String conversationId = request.pathVariable("conversationId");
                return ServerResponse.ok().body(tokenUsage.byConversation(conversationId));
            });

            // 管理员：全局月度统计（按用户聚合）
            builder.GET("spring/ai/loom/admin/stats/tokens/monthly", request -> {
                int year = java.time.LocalDate.now().getYear();
                int month = java.time.LocalDate.now().getMonthValue();
                String y = request.param("year").orElse(null);
                String m = request.param("month").orElse(null);
                if (y != null) year = Integer.parseInt(y);
                if (m != null) month = Integer.parseInt(m);
                return ServerResponse.ok().body(tokenUsage.monthlyByUser(year, month));
            });

            // 管理员：批量清理已软删的会话消息
            builder.POST("spring/ai/loom/admin/conversations/clean-batch", request -> {
                CleanBatchRequest body = request.body(CleanBatchRequest.class);
                if (body == null || body.items() == null) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "items 不能为空"));
                }
                int ok = 0, fail = 0;
                java.util.List<String> errors = new java.util.ArrayList<>();
                for (CleanBatchRequest.CleanItem item : body.items()) {
                    try {
                        userConversation.cleanContentForUserConv(item.username(), item.conversationId());
                        ok++;
                    } catch (Exception e) {
                        fail++;
                        errors.add(item.username() + "/" + item.conversationId() + ": " + e.getMessage());
                    }
                }
                return ServerResponse.ok().body(java.util.Map.of("ok", ok, "fail", fail, "errors", errors));
            });

            // ===== 角色管理 =====
            builder.GET("spring/ai/loom/admin/roles", request -> ServerResponse.ok().body(roleService.list()));
            builder.POST("spring/ai/loom/admin/roles", request -> {
                cn.wubo.spring.ai.loom.agent.model.CreateRoleRequest body = request.body(cn.wubo.spring.ai.loom.agent.model.CreateRoleRequest.class);
                try {
                    return ServerResponse.ok().body(roleService.create(body.code(), body.name(), body.description(), body.mcpNames()));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : 400;
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            builder.DELETE("spring/ai/loom/admin/roles/{code}", request -> {
                try {
                    roleService.deleteOrThrow(request.pathVariable("code"));
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : 400;
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            builder.GET("spring/ai/loom/admin/roles/{code}/mcps", request -> {
                return ServerResponse.ok().body(roleService.getRoleMcpsWithDefault(request.pathVariable("code")));
            });
            builder.PUT("spring/ai/loom/admin/roles/{code}/mcps", request -> {
                cn.wubo.spring.ai.loom.agent.model.SetRoleMcpsRequest body = request.body(cn.wubo.spring.ai.loom.agent.model.SetRoleMcpsRequest.class);
                roleService.setRoleMcps(request.pathVariable("code"),
                        body == null ? null : body.items());
                return ServerResponse.ok().body(true);
            });
            builder.GET("spring/ai/loom/admin/users/{username}/roles", request -> {
                return ServerResponse.ok().body(roleService.getUserRoles(request.pathVariable("username")));
            });
            builder.PUT("spring/ai/loom/admin/users/{username}/roles", request -> {
                cn.wubo.spring.ai.loom.agent.model.SetUserRolesRequest body = request.body(cn.wubo.spring.ai.loom.agent.model.SetUserRolesRequest.class);
                roleService.setUserRolesOrSkipAdmin(request.pathVariable("username"), body == null ? null : body.roleCodes());
                return ServerResponse.ok().body(true);
            });

            // ===== MCP 元数据管理 =====
            builder.GET("spring/ai/loom/admin/mcps", request -> ServerResponse.ok().body(mcpServerAdmin.listAll()));
            // 系统视图：合并 SDK 实时 mcp + DB 元数据（mcps.html 和 roles.html 都用这个）
            builder.GET("spring/ai/loom/admin/mcp-system", request -> ServerResponse.ok().body(mcpServerAdmin.listSystem()));
            builder.PUT("spring/ai/loom/admin/mcps/{name}", request -> {
                cn.wubo.spring.ai.loom.agent.model.UpdateMcpServerRequest body = request.body(cn.wubo.spring.ai.loom.agent.model.UpdateMcpServerRequest.class);
                try {
                    return ServerResponse.ok().body(mcpServerAdmin.update(request.pathVariable("name"),
                            body == null ? null : body.title(),
                            body == null ? null : body.description()));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    // name 不是 SDK 实时 mcp → 404 而不是 200 (BUG-12-MCP-SERVER-PUT-GHOST)
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : 400;
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // V7 起删除 /active 端点：mcp 是否可用完全由角色授权决定
            // 工具列表 / 更新改用 query string 或独立路径，避免 mcp 名含 @ / / 等特殊字符触发 Tomcat 400
            builder.GET("spring/ai/loom/admin/mcps/tools", request -> {
                String name = request.param("name").orElse(null);
                return ServerResponse.ok().body(mcpServerAdmin.listTools(name));
            });
            // 工具描述保存：toolId=0 表示 DB 没记录 → INSERT；否则 UPDATE
            // body 里带 mcpName + name（用于 INSERT 时定位）
            builder.PUT("spring/ai/loom/admin/mcp-tools/{toolId}", request -> {
                cn.wubo.spring.ai.loom.agent.model.UpsertMcpToolRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.model.UpsertMcpToolRequest.class);
                Long toolId = request.pathVariable("toolId").equals("0") ? 0L
                        : Long.parseLong(request.pathVariable("toolId"));
                try {
                    return ServerResponse.ok().body(mcpServerAdmin.upsertTool(
                            toolId,
                            body == null ? null : body.mcpName(),
                            body == null ? null : body.name(),
                            body == null ? null : body.description()));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    // toolId 非 0 但 DB 里找不到 → 404 而不是 500 (BUG-12-MCP-TOOL-PUT-404)
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : 404;
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 删除已维护的工具描述记录（删除后回退到 SDK 默认）
            builder.DELETE("spring/ai/loom/admin/mcp-tools/{toolId}", request -> {
                Long toolId = Long.parseLong(request.pathVariable("toolId"));
                int n = jdbcTemplate.update("DELETE FROM mcp_tool WHERE id = ?", toolId);
                if (n == 0) return ServerResponse.notFound().build();
                return ServerResponse.ok().body(true);
            });

            // 当前用户：本月 token 用量
            builder.GET("/spring/ai/loom/user/tokens/current-month", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (username == null) {
                    return ServerResponse.ok().body(new cn.wubo.spring.ai.loom.agent.model.CurrentMonthTokenStat("", 0, 0, 0, 0, 0));
                }
                return ServerResponse.ok().body(tokenUsage.currentMonthForUser(username));
            });

            return builder.build();
        }

        @Bean("loomAgentConversationRouter")
        public RouterFunction<ServerResponse> loomAgentConversationRouter(
                JdbcChatMemoryRepository chatMemoryRepository,
                IUserConversation userConversation,
                ObjectProvider<cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry> subTaskRegistry,
                ObjectProvider<cn.wubo.flex.schedule.core.FlexScheduledTaskService> flexService,
                ObjectProvider<cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleTriggerRepository> loomScheduleTriggerRepository,
                ObjectProvider<cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleExecutionRepository> loomScheduleExecutionRepository,
                ObjectProvider<cn.wubo.spring.ai.loom.agent.subtask.ILoomSubTaskHistoryRepository> loomSubTaskHistoryRepository) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            builder.GET("spring/ai/loom/conversation", request -> ServerResponse.ok().body(userConversation.getList()));
            builder.POST("spring/ai/loom/user-conversations", request -> {
                java.util.Map<String, Object> body = request.body(java.util.Map.class);
                String title = body == null ? null : java.util.Objects.toString(body.get("title"), null);
                return ServerResponse.status(HttpStatus.CREATED).body(userConversation.create(title));
            });
            builder.PATCH("spring/ai/loom/user-conversations/{conversationId}", request -> {
                String conversationId = request.pathVariable("conversationId");
                java.util.Map<String, Object> body = request.body(java.util.Map.class);
                String title = body == null ? null : java.util.Objects.toString(body.get("title"), null);
                int updated = userConversation.rename(conversationId, title);
                if (updated == 0) {
                    log.warn("拒绝跨用户重命名对话: caller={}, conv={}",
                            UserContextHolder.getCurrentUser(), conversationId);
                    // Return a structured body so the frontend can distinguish a
                    // cross-user 403 from a 500/network failure (the previous `body(false)`
                    // shape was indistinguishable from a generic network drop).
                    return ServerResponse.status(HttpStatus.FORBIDDEN).body(
                            java.util.Map.of("error", "forbidden", "code", 403));
                }
                return ServerResponse.ok().body(true);
            });
            builder.GET("spring/ai/loom/conversation/{conversationId}", request -> {
                String conversationId = request.pathVariable("conversationId");
                String username = UserContextHolder.getCurrentUser();
                // BUG-12a: cross-user conversation read. Reject unless the
                // caller owns this conversation in user_conversation. The
                // response is 403 with an empty list — same shape as success
                // — so client code doesn't break; the security side is the
                // 403 status code, which the UI can render as "无权限".
                boolean owned = userConversation.exists(new cn.wubo.spring.ai.loom.agent.model.UserConversationRecord(
                        username, conversationId));
                if (!owned) {
                    log.warn("拒绝跨用户读对话: caller={}, conv={}", username, conversationId);
                    return ServerResponse.status(HttpStatus.FORBIDDEN).body(
                            java.util.List.of());
                }
                return ServerResponse.ok().body(chatMemoryRepository.findByConversationId(conversationId));
            });
            builder.DELETE("spring/ai/loom/conversation/{conversationId}", request -> {
                String conversationId = request.pathVariable("conversationId");
                String username = UserContextHolder.getCurrentUser();
                // 先停子任务 + 取消定时任务 + 删除持久化行，再软删会话映射
                int[] cleaned = cleanupConversationResources(conversationId, username,
                        subTaskRegistry.getIfAvailable(),
                        flexService.getIfAvailable(),
                        loomScheduleTriggerRepository.getIfAvailable(),
                        loomScheduleExecutionRepository.getIfAvailable(),
                        loomSubTaskHistoryRepository.getIfAvailable());
                userConversation.deleteById(conversationId);
                log.info("会话删除清理: conv={}, user={}, subtasks={}, schedules={}, scheduleRowsDeleted={}, " +
                                "scheduleExecRowsDeleted={}, subTaskHistoryRowsDeleted={}, subTaskDequeCleared={}",
                        conversationId, username, cleaned[0], cleaned[1], cleaned[2], cleaned[3], cleaned[4], cleaned[5]);
                return ServerResponse.ok().body(true);
            });

            // ----- Feature 3: aggregated conversation state for the side panel -----
            // Used by the chat header to render "1 定时在跑, 3 子任务今天跑过" etc.
            // Cheap aggregation over H2 + the in-memory registry.
            builder.GET("spring/ai/loom/conversation/{conversationId}/state", request -> {
                String conversationId = request.pathVariable("conversationId");
                String username = UserContextHolder.getCurrentUser();
                return ServerResponse.ok().body(buildConversationState(
                        conversationId, username,
                        flexService.getIfAvailable(),
                        loomScheduleTriggerRepository.getIfAvailable(),
                        loomScheduleExecutionRepository.getIfAvailable(),
                        subTaskRegistry.getIfAvailable(),
                        loomSubTaskHistoryRepository.getIfAvailable()));
            });

            return builder.build();
        }

        /**
         * Aggregated, low-cost snapshot of a conversation's automation footprint
         * for the chat-header side panel. Returns counts only (no record lists);
         * the actual records still come from /schedule/history/by-conversation
         * and /subtask/list/history?conversationId=.
         */
        static java.util.Map<String, Object> buildConversationState(
                String conversationId,
                String username,
                cn.wubo.flex.schedule.core.FlexScheduledTaskService flexService,
                cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleTriggerRepository loomScheduleTriggerRepository,
                cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleExecutionRepository loomScheduleExecutionRepository,
                cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry subTaskRegistry,
                cn.wubo.spring.ai.loom.agent.subtask.ILoomSubTaskHistoryRepository loomSubTaskHistoryRepository) {
            java.util.Map<String, Object> state = new java.util.LinkedHashMap<>();
            if (username == null || conversationId == null) {
                state.put("activeSchedules", 0);
                state.put("executionsLast7d", 0);
                state.put("executionsFailedLast7d", 0);
                state.put("activeSubTasks", 0);
                state.put("subTaskHistoryLast7d", 0);
                state.put("subTaskFailedLast7d", 0);
                state.put("hasIssues", false);
                return state;
            }
            String prefix = "loom-sched-" + username + "-" + conversationId + "-";

            // 1. Active schedules = configs registered for this conv AND still
            //    visible in flex-schedule runtime.
            java.util.Set<String> liveNames = new java.util.HashSet<>();
            if (flexService != null) {
                for (cn.wubo.flex.schedule.core.TaskInfo info : flexService.listTasks()) {
                    if (info.taskName().startsWith(prefix)) liveNames.add(info.taskName());
                }
            }
            state.put("activeSchedules", liveNames.size());

            // 2. Executions last 7d / failed last 7d
            long now = System.currentTimeMillis();
            long cutoffMs = now - 7L * 24 * 3600 * 1000;
            Instant cutoff = Instant.ofEpochMilli(cutoffMs);
            int execLast7d = 0;
            int execFailedLast7d = 0;
            if (loomScheduleExecutionRepository != null && loomScheduleTriggerRepository != null) {
                java.util.List<cn.wubo.spring.ai.loom.agent.schedule.LoomScheduleTriggerRecord> configs =
                        loomScheduleTriggerRepository.findByUserAndConv(username, conversationId);
                for (cn.wubo.spring.ai.loom.agent.schedule.LoomScheduleTriggerRecord cfg : configs) {
                    java.util.List<cn.wubo.spring.ai.loom.agent.schedule.LoomScheduleExecutionRecord> rows =
                            loomScheduleExecutionRepository.findByTaskName(cfg.taskName(), 200);
                    for (cn.wubo.spring.ai.loom.agent.schedule.LoomScheduleExecutionRecord r : rows) {
                        if (r.fireTime().toEpochMilli() < cutoffMs) continue;
                        execLast7d++;
                        if (!r.success()) execFailedLast7d++;
                    }
                }
            }
            state.put("executionsLast7d", execLast7d);
            state.put("executionsFailedLast7d", execFailedLast7d);

            // 3. Active sub-tasks (currently RUNNING in this conv).
            int activeSubTasks = 0;
            if (subTaskRegistry != null) {
                for (cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry.SubTaskRecord rec : subTaskRegistry.listActive(username)) {
                    if (conversationId.equals(rec.conversationId())) activeSubTasks++;
                }
            }
            state.put("activeSubTasks", activeSubTasks);

            // 4. Sub-task history last 7d / failed last 7d (cheap SQL aggregation).
            int subTaskLast7d = 0;
            int subTaskFailedLast7d = 0;
            if (loomSubTaskHistoryRepository != null) {
                java.util.List<cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry.SubTaskRecord> rows =
                        loomSubTaskHistoryRepository.findByUsernameAndConversation(username, conversationId, 200);
                for (cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry.SubTaskRecord rec : rows) {
                    if (rec.finishedAt() < cutoffMs) continue;
                    subTaskLast7d++;
                    if (rec.status() == cn.wubo.spring.ai.loom.agent.model.SubTaskStatus.FAILED
                            || rec.status() == cn.wubo.spring.ai.loom.agent.model.SubTaskStatus.CANCELLED) {
                        subTaskFailedLast7d++;
                    }
                }
            }
            state.put("subTaskHistoryLast7d", subTaskLast7d);
            state.put("subTaskFailedLast7d", subTaskFailedLast7d);

            // 5. Aggregate "issues" flag — anything the user might want to act on.
            state.put("hasIssues", activeSubTasks > 0 || execFailedLast7d > 0 || subTaskFailedLast7d > 0);
            return state;
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
            // PATCH：改描述 / 默认加载
            builder.PATCH("spring/ai/loom/skill/{name}", request -> {
                String name = request.pathVariable("name");
                String username = UserContextHolder.getCurrentUser();
                cn.wubo.spring.ai.loom.agent.model.UserSkillPatchRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.model.UserSkillPatchRequest.class);
                skillStorage.patch(name, username, body);
                return ServerResponse.ok().body(true);
            });
            // 手动触发同步
            builder.POST("spring/ai/loom/skill/sync", request -> {
                String username = UserContextHolder.getCurrentUser();
                skillStorage.sync(username);
                return ServerResponse.ok().body(true);
            });
            return builder.build();
        }

        /** Skill 市场（公共浏览 + 用户拉取 + 用户提交） */
        @Bean("loomAgentSkillMarketRouter")
        public RouterFunction<ServerResponse> loomAgentSkillMarketRouter(
                cn.wubo.spring.ai.loom.agent.skill.ISkillMarketService marketService,
                IUser user) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            // 任意用户：列出所有 APPROVED
            builder.GET("spring/ai/loom/market-skills", request -> ServerResponse.ok().body(marketService.listApproved()));
            // 任意用户：按 id 查
            builder.GET("spring/ai/loom/market-skills/{id}", request -> {
                Long id = Long.parseLong(request.pathVariable("id"));
                return ServerResponse.ok().body(marketService.get(id));
            });
            // 任意用户：拉取到自己的 user_skill
            builder.POST("spring/ai/loom/market-skills/{id}/pull", request -> {
                String username = UserContextHolder.getCurrentUser();
                Long id = Long.parseLong(request.pathVariable("id"));
                return ServerResponse.ok().body(marketService.pull(username, id));
            });
            // 任意用户：提交新 Skill（status=PENDING）
            builder.POST("spring/ai/loom/user/market-skills", request -> {
                String username = UserContextHolder.getCurrentUser();
                cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest.class);
                return ServerResponse.ok().body(marketService.submit(username, body));
            });
            return builder.build();
        }

        /** Skill 市场管理（仅 admin） */
        @Bean("loomAgentSkillMarketAdminRouter")
        public RouterFunction<ServerResponse> loomAgentSkillMarketAdminRouter(
                cn.wubo.spring.ai.loom.agent.skill.ISkillMarketService marketService,
                IUser user) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            // 列出所有（含 PENDING/REJECTED）
            builder.GET("spring/ai/loom/admin/market-skills", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username)) return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                return ServerResponse.ok().body(marketService.listAllForAdmin());
            });
            // 列出 PENDING
            builder.GET("spring/ai/loom/admin/market-skills/pending", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username)) return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                return ServerResponse.ok().body(marketService.listPending());
            });
            // admin 直接新增（绕过审批）
            builder.POST("spring/ai/loom/admin/market-skills", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username)) return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                cn.wubo.spring.ai.loom.agent.model.MarketSkillUpsertRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.model.MarketSkillUpsertRequest.class);
                return ServerResponse.ok().body(marketService.adminCreate(username, body));
            });
            // admin 改任意 Skill
            builder.PUT("spring/ai/loom/admin/market-skills/{id}", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username)) return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                Long id = Long.parseLong(request.pathVariable("id"));
                cn.wubo.spring.ai.loom.agent.model.MarketSkillUpsertRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.model.MarketSkillUpsertRequest.class);
                return ServerResponse.ok().body(marketService.adminUpdate(username, id, body));
            });
            // admin 删
            builder.DELETE("spring/ai/loom/admin/market-skills/{id}", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username)) return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                Long id = Long.parseLong(request.pathVariable("id"));
                marketService.adminDelete(username, id);
                return ServerResponse.ok().body(true);
            });
            // 审批
            builder.POST("spring/ai/loom/admin/market-skills/{id}/approve", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username)) return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                Long id = Long.parseLong(request.pathVariable("id"));
                cn.wubo.spring.ai.loom.agent.model.MarketSkillReviewRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.model.MarketSkillReviewRequest.class);
                String comment = body == null ? null : body.comment();
                return ServerResponse.ok().body(marketService.approve(username, id, comment));
            });
            builder.POST("spring/ai/loom/admin/market-skills/{id}/reject", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username)) return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                Long id = Long.parseLong(request.pathVariable("id"));
                cn.wubo.spring.ai.loom.agent.model.MarketSkillReviewRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.model.MarketSkillReviewRequest.class);
                String comment = body == null ? null : body.comment();
                return ServerResponse.ok().body(marketService.reject(username, id, comment));
            });
            return builder.build();
        }

        /** 角色授权 Skill（仅 admin） */
        @Bean("loomAgentSkillRoleAdminRouter")
        public RouterFunction<ServerResponse> loomAgentSkillRoleAdminRouter(
                cn.wubo.spring.ai.loom.agent.skill.ISkillRoleAdmin roleAdmin,
                IUser user) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            builder.GET("spring/ai/loom/admin/roles/{code}/skills", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username)) return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                String code = request.pathVariable("code");
                return ServerResponse.ok().body(roleAdmin.getRoleSkills(code));
            });
            builder.PUT("spring/ai/loom/admin/roles/{code}/skills", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username)) return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                String code = request.pathVariable("code");
                cn.wubo.spring.ai.loom.agent.model.SetRoleSkillsRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.model.SetRoleSkillsRequest.class);
                roleAdmin.setRoleSkills(code, body == null ? null : body.items());
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
            // Ensure the per-user directory exists so subsequent IUpload writes
            // (which use the same path) land somewhere — and the UI never sees
            // a 'directory not found' error on first run.
            try {
                Files.createDirectories(baseDir);
            } catch (java.io.IOException e) {
                log.warn("Cannot create user file directory {}: {}", baseDir, e.getMessage());
            }
            node.put("name", username);
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
                try {
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(knowledge.insert(knowledgeRecord.name()));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException e) {
                    // 知识库名称冲突、参数错误等 → 明确的 4xx 而不是 500 (BUG-11)
                    Integer sc = e.getStatusCode();
                    HttpStatus status = sc != null
                            ? HttpStatus.valueOf(sc)
                            : HttpStatus.BAD_REQUEST;
                    return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of("message", e.getMessage() == null ? "请求失败" : e.getMessage()));
                }
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
