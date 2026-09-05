package cn.wubo.spring.ai.loom.agent;

import cn.wubo.file.view.storage.IFileStorage;
import cn.wubo.flex.schedule.core.ExecutionHistory;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
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
import cn.wubo.spring.ai.loom.agent.knowledge.stats.DefaultKnowledgeStatsService;
import cn.wubo.spring.ai.loom.agent.market.IMarketContentStatsService;
import cn.wubo.spring.ai.loom.agent.mcp.ASyncMcp;
import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.mcp.SyncMcp;
import cn.wubo.spring.ai.loom.agent.model.*;
import cn.wubo.spring.ai.loom.agent.skill.DefaultSkillStorage;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import cn.wubo.spring.ai.loom.agent.skill.stats.DefaultSkillStatsService;
import cn.wubo.spring.ai.loom.agent.tool.compile.DefaultCompileAndDeployTool;
import cn.wubo.spring.ai.loom.agent.tool.compile.ICompileAndDeployTool;
import cn.wubo.spring.ai.loom.agent.tool.file.DefaultFileTool;
import cn.wubo.spring.ai.loom.agent.tool.file.IFileTool;
import cn.wubo.spring.ai.loom.agent.tool.git.DefaultGitTool;
import cn.wubo.spring.ai.loom.agent.tool.git.IGitTool;
import cn.wubo.spring.ai.loom.agent.tool.knowledge.DefaultKnowledgeTool;
import cn.wubo.spring.ai.loom.agent.tool.knowledge.IKnowledgeTool;
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
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@AutoConfiguration
@AutoConfigureBefore(cn.wubo.file.view.autoconfigure.FileViewConfiguration.class)
@AutoConfigureAfter(name = {
        // ChatModel
        "org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration",
        "org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration",
        "org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration",
        "org.springframework.ai.model.minimax.autoconfigure.MiniMaxEmbeddingAutoConfiguration",
        "org.springframework.ai.model.mistralai.autoconfigure.MistralAiChatAutoConfiguration",
        // spring-ai-model 1.1.8 通过传递依赖保留 ollama starter;测试只跑 dashscope(chat + embedding),
        // 这里 exclude OllamaChatAutoConfiguration 避免 ollamaChatModel 跟 dashScopeChatModel 撞。
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
        // in-memory deque for this conversation so the API no longer serves
        // ghost records from the dying conversation.
        int subTaskHistoryRowsDeleted = (loomSubTaskHistoryRepository != null && username != null && conversationId != null)
                ? loomSubTaskHistoryRepository.deleteAllByConversation(username, conversationId)
                : 0;
        int subTaskDequeCleared = (registry != null && username != null && conversationId != null)
                ? registry.removeAllByConversation(username, conversationId)
                : 0;
        return new int[]{subtasksKilled, schedulesCancelled, scheduleRowsDeleted,
                scheduleExecRowsDeleted, subTaskHistoryRowsDeleted, subTaskDequeCleared};
    }

    // ==================== Chat ====================

    /**
     * Verifies ownership before exposing the in-memory execution history for a
     * namespaced schedule. Missing rows and foreign rows deliberately share the
     * same false result so the endpoint does not disclose task existence.
     */
    public static boolean handleScheduleHistoryOwnership(
            String fullName,
            cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleTriggerRepository repo,
            org.slf4j.Logger log) {
        if (fullName == null) return false;
        String caller = cn.wubo.spring.ai.loom.agent.user.UserContextHolder.getCurrentUser();
        try {
            var record = repo.findByName(fullName);
            if (record.isEmpty() || caller == null || !caller.equals(record.get().username())) {
                log.warn("拒绝跨用户读取定时历史: caller={}, target={}", caller, fullName);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("读取定时历史权限校验失败: name={}, err={}", fullName, e.getMessage());
            return false;
        }
    }

    // ==================== RAG (all beans conditional on VectorStore) ====================

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

    // ==================== MCP ====================

    static ServerResponse runtimeErrorResponse(
            cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex,
            int fallbackStatus) {
        int status = ex.getStatusCode() != null ? ex.getStatusCode() : fallbackStatus;
        return ServerResponse.status(status).body(java.util.Map.of(
                "message", ex.getMessage() == null ? "请求失败" : ex.getMessage()));
    }

    // ==================== Embed Tools ====================

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
        if (name.endsWith(".md") || name.endsWith(".markdown"))
            return MediaType.parseMediaType("text/markdown;charset=UTF-8");
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

    // ==================== Sub-task ====================

    /**
     * 拼 Content-Disposition 头，处理中文文件名。
     * 输出形式：
     * <ul>
     * <li>全 ASCII：{@code attachment; filename="report.md"}</li>
     * <li>含非 ASCII：{@code attachment; filename="report.md"; filename*=UTF-8''%E5%95%86%E5%93%81...md}
     * ——RFC 5987 双键，filename 是把非 ASCII 字符替换成 _ 后的 ASCII 兜底</li>
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

    // ==================== Schedule ====================

    @Configuration
    static class InfrastructureConfiguration {

        private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(InfrastructureConfiguration.class);

        @Bean
        public static BeanFactoryPostProcessor fileViewDefaultsBeanFactoryPostProcessor(org.springframework.core.env.ConfigurableEnvironment environment) {
            return new FileViewDefaultsBeanFactoryPostProcessor(environment);
        }

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

        /**
         * 库的 SQL 用 版本号（与业务的 区分），走 Spring Boot 默认 Flyway 实例
         * （classpath:db/migration + flyway_schema_history）。这样库和业务模块都在 db/migration，
         * 业务模块开发者按 Flyway 默认规则写 SQL 即可。
         * 版本号字典序：< ，库 SQL 先建表 + admin，业务的 后 seed mcp / skill。
         */
        @Bean
        public org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer libraryFlywayCustomizer() {
            return configuration -> {
                // baseline-on-migrate 让空 schema 也能跑（库 + 业务都能跑）
                configuration.baselineOnMigrate(true);
                configuration.baselineVersion("0");
            };
        }

        @Bean
        public ChatMemory jdbChatMemory(ChatMemoryRepository chatMemoryRepository) {
            return MessageWindowChatMemory.builder().chatMemoryRepository(chatMemoryRepository).build();
        }
    }

    // ==================== Storage ====================

    @Configuration
    static class ChatConfiguration {

        @ConditionalOnProperty(name = "spring.ai.chat.ui.init", havingValue = "true", matchIfMissing = true)
        @Bean
        public ChatClient chatClient(ChatModel chatModel,
                                     @Qualifier("messageChatMemoryAdvisor") org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor messageChatMemoryAdvisor,
                                     LoomAgentProperties properties) {
            ChatClient.Builder builder = ChatClient.builder(chatModel);
            if (properties.getDefaultSystem() != null) builder.defaultSystem(properties.getDefaultSystem());
            builder.defaultAdvisors((org.springframework.ai.chat.client.advisor.api.Advisor) messageChatMemoryAdvisor, // chat-memory advisor (bean, so sub-task executor can also reuse it)
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
        // 返回自定义 LastChunkMessageChatMemoryAdvisor（只在流式最后一个 chunk
        // 触发 chatMemory.add），替代 Spring AI 默认 MessageChatMemoryAdvisor（每个 chunk 都写
        // → chat_memory TOOL 消息多次重复）。
        public org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor messageChatMemoryAdvisor(JdbcTemplate jdbcTemplate) {
            return new cn.wubo.spring.ai.loom.agent.memory.LastChunkMessageChatMemoryAdvisor(jdbcTemplate, 0);
        }

        @ConditionalOnMissingBean(IChat.class)
        @Bean
        public IChat chat(@Qualifier("chatClient") ChatClient chatClient, IMcp mcp,
                          // @Lazy on the tools list breaks a 3-hop circular dep:
                          // chat -> List<IEmbedTool> (eagerly resolves defaultSubTaskTool)
                          // -> defaultSubTaskExecutor
                          // -> loomSubTaskChatClient
                          // -> List<IEmbedTool> (would re-enter)
                          // With @Lazy, the list is materialised on first access (inside
                          // DefaultChat.stream()) after every bean is fully constructed.
                          @Lazy java.util.List<cn.wubo.spring.ai.loom.agent.tool.IEmbedTool> embedTools,
                          IUserConversation userConversation, IFile file,
                          ISkillStorage skillStorage, IKnowledge knowledge,
                          LoomAgentProperties properties,
                          cn.wubo.spring.ai.loom.agent.tool.IToolCallLogRepository toolCallLogRepository,
                          cn.wubo.spring.ai.loom.agent.capability.CapabilityService capabilityService) {
            return new DefaultChat(chatClient, mcp, embedTools, userConversation, file,
                    skillStorage, knowledge, properties, toolCallLogRepository, capabilityService);
        }

        // ============== ：loom_tool_call_log 仓储 ==============
        @Bean
        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.tool.IToolCallLogRepository.class)
        public cn.wubo.spring.ai.loom.agent.tool.IToolCallLogRepository defaultToolCallLogRepository(
                org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
            return new cn.wubo.spring.ai.loom.agent.tool.JdbcToolCallLogRepository(jdbcTemplate);
        }

        @Slf4j
        @Data
        @RequiredArgsConstructor
        @RestController
        @RequestMapping
        public static class SseController {

            private final IChat chat;
            private final cn.wubo.spring.ai.loom.agent.stream.SseEmitterRegistry emitterRegistry;
            private final cn.wubo.spring.ai.loom.agent.token.ChatUsageService chatUsageService;
            // tool_call_log 唯一写入入口是 LoggingToolCallback — SseController 不再写

            @PostMapping(value = "/spring/ai/loom/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
            public SseEmitter stream(@RequestBody ChatRequestRecord chatRecord, HttpServletRequest request) {
                SseEmitter emitter = new SseEmitter(0L);

                String username = UserContextHolder.getCurrentUser();
                final String conversationId = chatRecord.conversationId();

                // 注册到 registry：disposable 暂存 wrapper
                final java.util.concurrent.atomic.AtomicReference<reactor.core.Disposable> subRef = new java.util.concurrent.atomic.AtomicReference<>();
                final java.util.concurrent.atomic.AtomicBoolean disposeRequested =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                emitterRegistry.register(username, conversationId, emitter,
                        new reactor.core.Disposable() {
                            @Override
                            public void dispose() {
                                disposeRequested.set(true);
                                reactor.core.Disposable d = subRef.get();
                                if (d != null && !d.isDisposed()) d.dispose();
                            }

                            @Override
                            public boolean isDisposed() {
                                reactor.core.Disposable d = subRef.get();
                                return disposeRequested.get() || d == null || d.isDisposed();
                            }
                        },
                        () -> { /* 用户主动 stop 时不再落库（：usage 实时从 chat_memory 聚合） */ });

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
                    // ：让 ToolCallLogObservationHandler 拿到 conversationId / username
                    // 写入 Map（**不** clear() — sub-task 在主 chat 完成后跑，可能在
                    // clear 之后才调 tool。下一个 chat 会覆盖 LATEST，不会内存泄漏）
                    cn.wubo.spring.ai.loom.agent.tool.ToolCallContextHolder.set(conversationId, username);
                    try {
                        Flux<ChatResponse> chatResponseFlux = chat.stream(chatRecord, username, request);

                        // ：累积 DashScope enable_thinking 模式下的 reasoningContent
                        // Spring AI DashScope 实测是 incremental（每条 chunk 是当前为止的
                        // 增量片段），所以得 append 不是覆盖。content 文本也是增量同理。
                        final StringBuilder reasoningAccum = new StringBuilder();

                        reactor.core.Disposable subscription = chatResponseFlux
                                .filter(chatResponse -> chatResponse.getResult() != null)
                                .subscribe(chatResponse -> {
                                    log.info(" stream NEXT: conv={} reasoningAccumLen={}", conversationId, reasoningAccum.length());
                                    try {
                                        String reasoningContent = (String) chatResponse.getResult().getOutput().getMetadata().get("reasoningContent");
                                        if (reasoningContent != null && !reasoningContent.isBlank()) {
                                            reasoningAccum.append(reasoningContent);
                                        }
                                        emitter.send(new ChatResponseRecord(chatResponse.getResult().getOutput().getText(), reasoningContent), MediaType.APPLICATION_JSON);
                                        // ：每条 ChatResponse 携带有效 usage 时写一行到 loom_chat_usage
                                        // (假设从 chat_memory 反推 JSON 在新版 Spring AI 失效，故改显式记录)
                                        var chatMeta = chatResponse.getMetadata();
                                        if (chatMeta != null && chatMeta.getUsage() != null) {
                                            var u = chatMeta.getUsage();
                                            chatUsageService.record(
                                                    conversationId, username,
                                                    u.getPromptTokens(), u.getCompletionTokens(), u.getTotalTokens());
                                        }
                                        // tool_call_log 唯一入口是 LoggingToolCallback.call()。
                                        // 之前 SseController 在 stream chunk 里提前抓 chatResponse.getResult().getOutput().getToolCalls()
                                        // 也写一行（result=null），加上 DashScope 流式 chunk 重复 emit 同一 tool_call_id，
                                        // 导致 1 次真实工具调用 → 4-8 行重复 log。删除这段，只保留 callback 入口。
                                    } catch (IOException e) {
                                        emitter.completeWithError(e);
                                    }
                                }, err -> {
                                    log.warn(" stream ERROR: conv={} err={}", conversationId, err.getMessage());
                                    emitter.completeWithError(err);
                                }, () -> {
                                    log.info(" stream COMPLETE: conv={} reasoningAccumLen={}", conversationId, reasoningAccum.length());
                                    // ：流完整结束时把 AI 思考落库
                                    String r = reasoningAccum.toString();
                                    if (!r.isBlank()) {
                                        try {
                                            chatUsageService.saveReasoning(conversationId, r);
                                            log.info(" saveReasoning OK: conv={} len={}", conversationId, r.length());
                                        } catch (Exception e) {
                                            log.warn("saveReasoning 失败: {}", e.getMessage());
                                        }
                                    }
                                    emitterRegistry.autoCleanup(username, conversationId);
                                    emitter.complete();
                                });
                        subRef.set(subscription);
                        if (disposeRequested.get()) subscription.dispose();
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    } finally {
                        // ：不 clear — 让 sub-task 的 tool call（runAsync 异步）也能读到
                    }
                });

                return emitter;
            }

            /**
             * 主动停止某个会话的 AI 流（前端"停止"按钮调用）
             */
            @PostMapping(value = "/spring/ai/loom/stream/{conversationId}/stop")
            public java.util.Map<String, Object> stopStream(@PathVariable("conversationId") String conversationId) {
                String username = UserContextHolder.getCurrentUser();
                boolean stopped = emitterRegistry.stop(username, conversationId);
                return java.util.Map.of("stopped", stopped, "conversationId", conversationId);
            }

            /**
             * 调试用：当前用户的活跃流
             */
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

    // ==================== Web ====================

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
        public IDocumentRead defaultDocumentRead() {
            return new DefaultDocumentRead();
        }

        @ConditionalOnBean(VectorStore.class)
        @ConditionalOnMissingBean(IUpload.class)
        @Bean
        public IUpload defaultUpload(IFile file, IFileDocument fileDocument, IDocumentRead documentRead, VectorStore vectorStore, IKnowledge knowledge, cn.wubo.spring.ai.loom.agent.file.IFileStorage fileStorage, LoomAgentProperties properties) {
            return new DefaultUpload(file, fileDocument, documentRead, vectorStore, knowledge, fileStorage, properties.getFileBasePath());
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

    @Configuration
    public static class ToolConfiguration {

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
        public IFileTool defaultFileTool(IFile file, LoomAgentProperties properties) {
            return new DefaultFileTool(file, properties.getFileBasePath(), properties.getFile());
        }

        @ConditionalOnMissingBean(IGitTool.class)
        @Bean
        public IGitTool defaultGitTool(LoomAgentProperties properties) {
            return new DefaultGitTool(properties);
        }

        @ConditionalOnClass(name = "org.apache.maven.shared.invoker.Invoker")
        @ConditionalOnMissingBean(IMavenTool.class)
        @Bean
        public IMavenTool defaultMavenTool(LoomAgentProperties properties) {
            return new DefaultMavenTool(properties.getMaven(), properties.getFileBasePath());
        }

        @ConditionalOnMissingBean(ICompileAndDeployTool.class)
        @Bean
        public ICompileAndDeployTool defaultCompileAndDeployTool(LoomAgentProperties properties) {
            return new DefaultCompileAndDeployTool(properties);
        }

        @ConditionalOnMissingBean(IKnowledgeTool.class)
        @ConditionalOnBean(VectorStore.class)
        @Bean
        public IKnowledgeTool defaultKnowledgeTool(
                IKnowledge knowledge,
                VectorStore vectorStore,
                LoomAgentProperties properties,
                @Qualifier("kbStatsService") IMarketContentStatsService kbStatsService) {
            return new DefaultKnowledgeTool(knowledge, vectorStore, properties.getRag(), kbStatsService);
        }
    }

    /**
     * 子任务基础设施：构造过滤版 ChatClient（排除 ISubTaskTool / IScheduleTool 防 LLM 自递归）、
     * 专用线程池、Registry、Executor 以及 BFF 路由。
     * <p>
     * 完整的 LLM 工具（{@code ISubTaskTool} / {@code IScheduleTool} 的实现类）在 Task 3.1/3.2/4.1/4.2
     * 才加入 —— 本配置只搭骨架。
     */
    @Configuration(proxyBeanMethods = false)
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
                @Qualifier("messageChatMemoryAdvisor") org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor memoryAdvisor,
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
                                        ? registry.listHistory(user, properties.getSubtask().getMaxHistory())
                                        : registry.listHistoryByConversation(user, conv, properties.getSubtask().getMaxHistory());
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
    @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool.class)
    @Slf4j
    public static class ScheduleConfiguration {

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

        @Bean
        @ConditionalOnMissingBean(ExecutionHistory.class)
        public ExecutionHistory loomFlexExecutionHistory() {
            return new cn.wubo.flex.schedule.core.InMemoryExecutionHistory();
        }

        @Bean
        public cn.wubo.spring.ai.loom.agent.schedule.LoomFlexExecutionHistoryRegistrar
        loomFlexExecutionHistoryRegistrar(FlexScheduledTaskRegistrar registrar,
                                          ExecutionHistory executionHistory) {
            return new cn.wubo.spring.ai.loom.agent.schedule.LoomFlexExecutionHistoryRegistrar(
                    registrar, executionHistory);
        }

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
                cn.wubo.spring.ai.loom.agent.schedule.ScheduleExecutionProperties execProps,
                JdbcTemplate jdbcTemplate) {
            return new cn.wubo.spring.ai.loom.agent.schedule.ScheduleRestoreListener(
                    flexService, loomScheduleTriggerRepository, subTaskExecutor, taskLimits,
                    loomScheduleExecutionRepository, execProps.getMaxPerTask(), jdbcTemplate);
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
                cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool scheduleTool,
                cn.wubo.spring.ai.loom.agent.schedule.ScheduleExecutionProperties execProps) {
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
            builder.GET("spring/ai/loom/schedule/history/{name}", request -> {
                String name = request.pathVariable("name");
                if (!handleScheduleHistoryOwnership(name, loomScheduleTriggerRepository, log)) {
                    return ServerResponse.status(HttpStatus.FORBIDDEN).body(
                            java.util.Map.of("message", "无权访问该定时任务历史"));
                }
                return ServerResponse.ok().body(
                        loomScheduleExecutionRepository.findByTaskName(name, execProps.getMaxPerTask()));
            });
            // Per-conversation schedule history.
            // - source-of-truth for "what schedules exist for this conversation" is
            // loom_scheduled_task (H2). This covers BOTH still-registered tasks
            // (visible in flex-schedule runtime) AND one_shots that already fired
            // and were auto-removed from flex-schedule's runtime.
            // - execution events come from loom_schedule_execution (H2), which
            // survives restarts and is not auto-trimmed on one_shot completion.
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
    }

    @Configuration
    @EnableScheduling
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

        // ：旧 ITokenUsage / DefaultTokenUsage 删除。token 统计改由 ChatUsageService
        // 从 chat_memory 实时聚合，不需要额外的依赖 tokenUsage 的 Bean。
        // @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.token.ITokenUsage.class) — 删除
        // public cn.wubo.spring.ai.loom.agent.token.ITokenUsage defaultTokenUsage(JdbcTemplate jdbcTemplate) — 删除

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
         * 默认文件存储实现：基于 H2 数据库存储知识库文件内容。
         * 通过 {@code @ConditionalOnMissingBean} 允许替换为 S3/MinIO 等实现。
         */
        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.file.IFileStorage.class)
        @Bean
        public cn.wubo.spring.ai.loom.agent.file.IFileStorage loomFileStorage(JdbcTemplate jdbcTemplate) {
            return new cn.wubo.spring.ai.loom.agent.file.storage.DatabaseFileStorage(jdbcTemplate);
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

        /**
         * 知识库市场服务：支持发布、审批、订阅、角色分配。
         * 通过 {@code @ConditionalOnMissingBean} 允许替换为自定义实现。
         */
        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.knowledge.IKnowledgeMarketService.class)
        @Bean
        public cn.wubo.spring.ai.loom.agent.knowledge.IKnowledgeMarketService defaultKnowledgeMarketService(
                JdbcTemplate jdbcTemplate, IKnowledge knowledge, IUser user) {
            return new cn.wubo.spring.ai.loom.agent.knowledge.DefaultKnowledgeMarketService(jdbcTemplate, knowledge, user);
        }

        /**
         * 知识库角色管理：支持角色-知识库关联和同步。
         * 通过 {@code @ConditionalOnMissingBean} 允许替换为自定义实现。
         */
        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.knowledge.IKnowledgeRoleAdmin.class)
        @Bean
        public cn.wubo.spring.ai.loom.agent.knowledge.IKnowledgeRoleAdmin defaultKnowledgeRoleAdmin(
                JdbcTemplate jdbcTemplate, cn.wubo.spring.ai.loom.agent.knowledge.IKnowledgeMarketService marketService) {
            return new cn.wubo.spring.ai.loom.agent.knowledge.DefaultKnowledgeRoleAdmin(jdbcTemplate, marketService);
        }

        /**
         * M2 / T20:KB 市场多对多 tag 服务。
         * 通过 {@code @ConditionalOnMissingBean} 允许替换为自定义实现。
         */
        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.knowledge.market.KnowledgeTagService.class)
        @Bean
        public cn.wubo.spring.ai.loom.agent.knowledge.market.KnowledgeTagService knowledgeTagService(
                JdbcTemplate jdbcTemplate) {
            return new cn.wubo.spring.ai.loom.agent.knowledge.market.KnowledgeTagService(jdbcTemplate);
        }

        /**
         * 文件下载与预览：通过 {@code @ConditionalOnMissingBean} 允许替换为自定义实现。
         * 依赖 {@code IFileStorage}（数据库或磁盘）透明读取知识库文件内容。
         */
        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.file.IFileDownload.class)
        @Bean
        public cn.wubo.spring.ai.loom.agent.file.IFileDownload defaultFileDownload(
                IFile file, cn.wubo.spring.ai.loom.agent.file.IFileStorage fileStorage) {
            return new cn.wubo.spring.ai.loom.agent.file.DefaultFileDownload(file, fileStorage);
        }

        /**
         * Generic batched counter for marketplace stats (skill pulls, KB searches,
         * content views). Buffers increments in memory and flushes via
         * {@code @Scheduled(fixedDelay = 30s)} + {@code @PreDestroy} drain.
         * Through {@code @ConditionalOnMissingBean} consumers can swap in a
         * custom implementation (e.g. with metrics or an outbox table).
         * <p>
         * The 30s scheduler fires automatically because
         * {@link StorageConfiguration} is annotated with
         * {@code @EnableScheduling} (M3+ T0.2) — consumers do not need to
         * add it themselves, and the same declaration also covers
         * {@link cn.wubo.spring.ai.loom.agent.schedule.ScheduleExecutionCleanup}.
         * </p>
         */
        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.market.BatchedCounterService.class)
        @Bean
        public cn.wubo.spring.ai.loom.agent.market.BatchedCounterService batchedCounterService(JdbcTemplate jdbcTemplate) {
            return new cn.wubo.spring.ai.loom.agent.market.BatchedCounterService(jdbcTemplate);
        }

        /**
         * Stats for Skill marketplace content (T16). Backed by
         * {@code market_skill_stats}; buffered writes flow through
         * {@link cn.wubo.spring.ai.loom.agent.market.BatchedCounterService}.
         * The {@link IMarketContentStatsService} interface allows the
         * {@code DefaultKnowledgeTool} to take a stats dep without knowing
         * about Skill vs KB specifics — it only sees
         * {@code incrementStat(marketId, "SEARCH")}.
         */
        @ConditionalOnMissingBean(name = "skillStatsService")
        @Bean("skillStatsService")
        public DefaultSkillStatsService skillStatsService(
                JdbcTemplate jdbcTemplate,
                cn.wubo.spring.ai.loom.agent.market.BatchedCounterService batchedCounterService) {
            return new DefaultSkillStatsService(jdbcTemplate, batchedCounterService);
        }

        /**
         * Stats for KB marketplace content (T16). Backed by
         * {@code loom_market_knowledge_stats}; same BatchedCounterService
         * pattern as Skill. Note the schema mismatch in
         * {@link DefaultKnowledgeStatsService} (stats PK is BIGINT while
         * parent {@code loom_market_knowledge.id} is VARCHAR(36) UUID) —
         * the call-site converts gracefully and skips the stat when the
         * {@code knowledgeId} is a non-numeric String.
         */
        @ConditionalOnMissingBean(name = "kbStatsService")
        @Bean("kbStatsService")
        public DefaultKnowledgeStatsService kbStatsService(
                JdbcTemplate jdbcTemplate,
                cn.wubo.spring.ai.loom.agent.market.BatchedCounterService batchedCounterService) {
            return new DefaultKnowledgeStatsService(jdbcTemplate, batchedCounterService);
        }

        /**
         * Skill marketplace review service (T17). Backed by
         * {@code market_skill_review};MERGE INTO upsert;{@code edit_count}
         * 1 次修改上限;{@code aggregate} 通过 {@code JOIN user_info} 排除 admin 自评。
         * 路由器(T18)按名 {@code skillReviewService} 注入。
         */
        @ConditionalOnMissingBean(name = "skillReviewService")
        @Bean("skillReviewService")
        public cn.wubo.spring.ai.loom.agent.skill.review.DefaultSkillReviewService skillReviewService(
                JdbcTemplate jdbcTemplate) {
            return new cn.wubo.spring.ai.loom.agent.skill.review.DefaultSkillReviewService(jdbcTemplate);
        }

        /**
         * KB marketplace review service (T17)。在 skill review 基础上额外加
         * KB 严门槛 ({@code loom_user_knowledge.access_count >= 1}),否则抛 403。
         * 路由器(T18)按名 {@code kbReviewService} 注入。
         */
        @ConditionalOnMissingBean(name = "kbReviewService")
        @Bean("kbReviewService")
        public cn.wubo.spring.ai.loom.agent.knowledge.review.DefaultKnowledgeReviewService kbReviewService(
                JdbcTemplate jdbcTemplate) {
            return new cn.wubo.spring.ai.loom.agent.knowledge.review.DefaultKnowledgeReviewService(jdbcTemplate);
        }

        /**
         * 市场公告仓储 (T17)。面向 {@code market_content_announcement},由
         * T7/T8 router forward refs 调用 {@code upsert/findOne/listAllForKind/delete},
         * 真正的 admin 端点由 T18 落地。路由器按名 {@code marketAnnouncementRepository}
         * 注入。
         */
        @ConditionalOnMissingBean(name = "marketAnnouncementRepository")
        @Bean("marketAnnouncementRepository")
        public cn.wubo.spring.ai.loom.agent.market.DefaultMarketAnnouncementRepository marketAnnouncementRepository(
                JdbcTemplate jdbcTemplate) {
            return new cn.wubo.spring.ai.loom.agent.market.DefaultMarketAnnouncementRepository(jdbcTemplate);
        }
    }

    @Configuration
    @Slf4j
    static class WebConfiguration {

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
            // visible in flex-schedule runtime.
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
                                                                  cn.wubo.spring.ai.loom.agent.token.ChatUsageService chatUsageService,
                                                                  cn.wubo.spring.ai.loom.agent.chat.ConversationFlowService conversationFlowService,
                                                                  cn.wubo.spring.ai.loom.agent.rbac.IRoleService roleService,
                                                                  cn.wubo.spring.ai.loom.agent.rbac.IMcpServerAdmin mcpServerAdmin,
                                                                  JdbcTemplate jdbcTemplate,
                                                                  cn.wubo.spring.ai.loom.agent.capability.CapabilityService capabilityService) {
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
                // DefaultUser.login() already calls createToken() internally; reuse that
                // token instead of minting a second one (which would leak an orphan
                // session into the cache for the full maxAge window).
                String token = response.token();
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
                try {
                    user.changePassword(username, body.oldPassword(), body.newPassword());
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException e) {
                    return runtimeErrorResponse(e, 400);
                }
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

            // M5：统一 capability 列表（本地 tool group + MCP server），给 chat 面板渲染
            // 返回 List<CapabilityInfo>，前端按 type 字段区分 local / MCP
            builder.GET("spring/ai/loom/api/capabilities", request -> {
                String username = UserContextHolder.getCurrentUser();
                try {
                    return ServerResponse.ok().body(capabilityService.list(username));
                } catch (Exception e) {
                    log.warn("/api/capabilities degraded, returning empty list: {}", e.getMessage());
                    return ServerResponse.ok().body(java.util.Collections.emptyList());
                }
            });

            // B.5.5：admin 用 — 列出所有 LOCAL capability(忽略 RBAC / 默认勾选),
            // 供 admin 角色授权页("授权本地工具"section)替代之前的硬编码 KNOWN_TOOL_GROUPS。
            builder.GET("spring/ai/loom/admin/capabilities", request -> {
                try {
                    // 过滤 LOCAL 类型,排除 MCP(用同一份 CapabilityInfo shape)
                    java.util.List<cn.wubo.spring.ai.loom.agent.model.CapabilityInfo> all = capabilityService.listAll();
                    java.util.List<cn.wubo.spring.ai.loom.agent.model.CapabilityInfo> local =
                            all.stream()
                                    .filter(c -> c.type() == cn.wubo.spring.ai.loom.agent.model.CapabilityInfo.Type.LOCAL)
                                    .toList();
                    return ServerResponse.ok().body(local);
                } catch (Exception e) {
                    log.warn("/admin/capabilities degraded, returning empty list: {}", e.getMessage());
                    return ServerResponse.ok().body(java.util.Collections.emptyList());
                }
            });

            // 当前用户角色允许的 mcp 的工具列表
            // 用 query string 接收 name：path variable 在 mcp name 含 "/" 时
            // （如 "spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch"）
            // Tomcat 会把 URL 编码后的 %2F 还原成 "/" 当路径分隔符，导致 404。
            builder.GET("/spring/ai/loom/mcps/tools", request -> {
                String username = UserContextHolder.getCurrentUser();
                String mcpName = request.param("name").orElse(null);
                if (mcpName == null || mcpName.isEmpty()) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "name 参数必填"));
                }
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
                try {
                    user.createUser(body.username(), body.nickname(), body.password(), body.type());
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException e) {
                    return runtimeErrorResponse(e, 400);
                }
            });

            // 管理员：删除用户
            builder.DELETE("spring/ai/loom/admin/users/{username}", request -> {
                String username = request.pathVariable("username");
                try {
                    user.deleteUser(username);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException e) {
                    return runtimeErrorResponse(e, 400);
                }
            });

            // 管理员：列出某用户全部会话（含已软删 + content_cleaned 标记）
            builder.GET("spring/ai/loom/admin/users/{username}/conversations", request -> {
                String username = request.pathVariable("username");
                return ServerResponse.ok().body(userConversation.adminListByUsername(username));
            });

            // 管理员：列出会话每 turn 的 token + 内容（移除，用 /flow 替代）
            // builder.GET("spring/ai/loom/admin/conversations/{conversationId}/turns", ...) — 删除

            // 管理员：全局月度统计（按用户聚合，从 chat_memory 实时聚合，替代旧 token_usage）
            builder.GET("spring/ai/loom/admin/stats/tokens/monthly", request -> {
                int year = java.time.LocalDate.now().getYear();
                int month = java.time.LocalDate.now().getMonthValue();
                String y = request.param("year").orElse(null);
                String m = request.param("month").orElse(null);
                // year/month 必须是数字——非数字 (year=abc 或超出 int 范围) 走 400 而不是 500
                try {
                    if (y != null) year = Integer.parseInt(y);
                    if (m != null) month = Integer.parseInt(m);
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "year/month 必须是数字: year=" + y + ", month=" + m));
                }
                return ServerResponse.ok().body(chatUsageService.monthlyByUser(year, month));
            });

            // 管理员：批量清理（移除 — 控制台不再支持删除；如需直接连 DB 操作）
            // builder.POST("spring/ai/loom/admin/conversations/clean-batch", ...) — 删除

            // ===== ：全量对话流（单个会话时间线） =====
            builder.GET("spring/ai/loom/admin/conversations/{conversationId}/flow", request -> {
                String conversationId = request.pathVariable("conversationId");
                String username = request.param("username").orElse(null);
                int page = request.param("page").map(s -> {
                    try {
                        return Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }).orElse(0);
                int size = request.param("size").map(s -> {
                    try {
                        return Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        return 50;
                    }
                }).orElse(50);
                java.util.Set<String> types = request.param("types")
                        .map(s -> java.util.Set.of(s.split(",")))
                        .orElse(java.util.Set.of());
                return ServerResponse.ok().body(
                        conversationFlowService.flow(conversationId, username, page, size, types));
            });

            // ===== 角色管理 =====
            builder.GET("spring/ai/loom/admin/roles", request -> ServerResponse.ok().body(roleService.list()));
            builder.POST("spring/ai/loom/admin/roles", request -> {
                cn.wubo.spring.ai.loom.agent.model.CreateRoleRequest body = request.body(cn.wubo.spring.ai.loom.agent.model.CreateRoleRequest.class);
                // P3.1:role.code 长度校验(DB VARCHAR 32)。超长返 400 而不是 500。
                if (body.code() == null || body.code().length() > 32 || body.code().isBlank()) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "role.code 必填且长度 1-32 字符"));
                }
                if (body.name() == null || body.name().isBlank()) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "role.name 必填"));
                }
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
            // M5：本地 tool group 授权（与 mcps 平行）
            builder.GET("spring/ai/loom/admin/roles/{code}/tools", request -> {
                return ServerResponse.ok().body(roleService.getRoleToolsWithDefault(request.pathVariable("code")));
            });
            builder.PUT("spring/ai/loom/admin/roles/{code}/tools", request -> {
                cn.wubo.spring.ai.loom.agent.model.SetRoleToolsRequest body = request.body(cn.wubo.spring.ai.loom.agent.model.SetRoleToolsRequest.class);
                // role 不存在 → 4xx 而不是 500（service 抛 LoomAgentRuntimeException）
                try {
                    roleService.setRoleTools(request.pathVariable("code"),
                            body == null ? null : body.items());
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    Integer sc = ex.getStatusCode();
                    int code = sc != null ? sc : 400;
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            builder.GET("spring/ai/loom/admin/users/{username}/roles", request -> {
                return ServerResponse.ok().body(roleService.getUserRoles(request.pathVariable("username")));
            });
            builder.PUT("spring/ai/loom/admin/users/{username}/roles", request -> {
                cn.wubo.spring.ai.loom.agent.model.SetUserRolesRequest body = request.body(cn.wubo.spring.ai.loom.agent.model.SetUserRolesRequest.class);
                // 用户不存在 → 4xx 而不是 500（service 抛 LoomAgentRuntimeException）
                try {
                    roleService.setUserRolesOrSkipAdmin(request.pathVariable("username"), body == null ? null : body.roleCodes());
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    Integer sc = ex.getStatusCode();
                    int code = sc != null ? sc : 400;
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
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
                String toolIdRaw = request.pathVariable("toolId");
                Long toolId;
                try {
                    toolId = toolIdRaw.equals("0") ? 0L : Long.parseLong(toolIdRaw);
                } catch (NumberFormatException nfe) {
                    return ServerResponse.status(HttpStatus.BAD_REQUEST)
                            .body(java.util.Map.of("error", "toolId 必须是数字: " + toolIdRaw));
                }
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
                } catch (org.springframework.dao.DataAccessException dae) {
                    // 入参不满足 DB 约束（NOT NULL 字段为空等）→ 400 而非 500，
                    // 避免 admin 端意外把后端异常以 500 形式抛给前端。
                    log.warn("admin upsertTool 数据约束失败: toolId={}, message={}", toolId, dae.getMessage());
                    return ServerResponse.status(HttpStatus.BAD_REQUEST)
                            .body(java.util.Map.of("error", "数据约束失败: " + dae.getMostSpecificCause().getMessage()));
                }
            });
            // 删除已维护的工具描述记录（删除后回退到 SDK 默认）
            builder.DELETE("spring/ai/loom/admin/mcp-tools/{toolId}", request -> {
                // toolId 必须是数字——非数字走 400 而不是 500（PUT 已在 R22 修过，DELETE 同根因）
                Long toolId;
                try {
                    toolId = Long.parseLong(request.pathVariable("toolId"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "toolId 必须是数字: " + request.pathVariable("toolId")));
                }
                int n = jdbcTemplate.update("DELETE FROM mcp_tool WHERE id = ?", toolId);
                if (n == 0) return ServerResponse.notFound().build();
                return ServerResponse.ok().body(true);
            });

            // 当前用户：本月 token 用量（：从 loom_chat_token_usage 聚合 prompt/completion/total 全部真实值）
            builder.GET("/spring/ai/loom/user/tokens/current-month", request -> {
                String username = UserContextHolder.getCurrentUser();
                return ServerResponse.ok().body(chatUsageService.currentMonthForUser(username));
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
            builder.GET("/spring/ai/loom/admin/conversations/{conversationId}/messages", request -> {
                String targetUser = request.param("username").orElse(null);
                String conversationId = request.pathVariable("conversationId");
                boolean owned = targetUser != null
                        && userConversation.adminListByUsername(targetUser).stream()
                        .anyMatch(view -> conversationId.equals(view.conversationId()));
                if (!owned) {
                    return ServerResponse.notFound().build();
                }
                // ：保留此端点供 /flow fallback 使用（admin 自家查 chat_memory 仍然合法）。
                // 前端 /flow 端点已统一处理，不直接调本端点。
                return ServerResponse.ok().body(
                        chatMemoryRepository.findByConversationId(conversationId));
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
                // 空 body 或 service 抛异常 → 4xx 而不是 500
                try {
                    if (skill == null) {
                        return ServerResponse.badRequest().body(java.util.Map.of("error", "请求体不能为空"));
                    }
                    skillStorage.save(skill, username);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    Integer sc = ex.getStatusCode();
                    int code = sc != null ? sc : 400;
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "数据约束失败: " + ex.getMostSpecificCause().getMessage()));
                } catch (NullPointerException npe) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "Skill 字段缺失（name/content 必填）"));
                }
            });
            builder.GET("spring/ai/loom/skill/{name}", request -> {
                String name = request.pathVariable("name");
                String username = UserContextHolder.getCurrentUser();
                try {
                    return ServerResponse.ok().body(skillStorage.get(name, username));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    // "Skill 不存在或无权限" 由 service 抛 message-only 异常；
                    // 没有显式 statusCode，路由层就近兜底成 404 而不是默认 500。
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    log.debug("skill get 失败: name={}, user={}, message={}", name, username, ex.getMessage());
                    return ServerResponse.status(code).body(
                            java.util.Map.of("error", ex.getMessage() == null ? "Skill 不存在或无权限" : ex.getMessage()));
                }
            });

            // 创建 / 更新（前端「导入 skill」与 chat 工具共用）
            // 返回 {status: "created"|"updated", name, description} 让前端区分新建 / 覆盖
            builder.POST("spring/ai/loom/skill/upsert", request -> {
                String username = UserContextHolder.getCurrentUser();
                cn.wubo.spring.ai.loom.agent.model.UpsertSkillRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.model.UpsertSkillRequest.class);
                if (body == null) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "请求体不能为空"));
                }
                String name = body.name() == null ? null : body.name().trim();
                String description = body.description() == null ? "" : body.description();
                String content = body.content();
                if (name == null || name.isEmpty()) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "name 必填"));
                }
                if (name.length() > 128) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "name 长度超过 128"));
                }
                if (content == null || content.isBlank()) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "content 必填"));
                }
                // 锁检查：ROLE_GRANTED / MARKET_PULLED 不能改 —— skillStorage.save 内部会校验并抛 403
                boolean existed = false;
                try {
                    skillStorage.get(name, username);
                    existed = true;
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ignore) {
                    // 不存在时 service 抛 message-only 异常，吞掉
                }
                try {
                    skillStorage.save(new cn.wubo.spring.ai.loom.agent.model.SkillRecord(
                            name, description, true, content, "USER_CREATED"), username);
                    return ServerResponse.ok().body(java.util.Map.of(
                            "status", existed ? "updated" : "created",
                            "name", name,
                            "description", description));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    Integer sc = ex.getStatusCode();
                    int code = sc != null ? sc : 400;
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            builder.DELETE("spring/ai/loom/skill/{name}", request -> {
                String name = request.pathVariable("name");
                String username = UserContextHolder.getCurrentUser();
                try {
                    skillStorage.remove(name, username);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    log.debug("skill delete 失败: name={}, user={}, message={}", name, username, ex.getMessage());
                    return ServerResponse.status(code).body(
                            java.util.Map.of("error", ex.getMessage() == null ? "Skill 不存在或无权限" : ex.getMessage()));
                }
            });
            // PATCH：改描述 / 默认加载
            builder.PATCH("spring/ai/loom/skill/{name}", request -> {
                String name = request.pathVariable("name");
                String username = UserContextHolder.getCurrentUser();
                cn.wubo.spring.ai.loom.agent.model.UserSkillPatchRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.model.UserSkillPatchRequest.class);
                try {
                    skillStorage.patch(name, username, body);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    log.debug("skill patch 失败: name={}, user={}, message={}", name, username, ex.getMessage());
                    return ServerResponse.status(code).body(
                            java.util.Map.of("error", ex.getMessage() == null ? "Skill 不存在或无权限" : ex.getMessage()));
                }
            });
            // 手动触发同步
            builder.POST("spring/ai/loom/skill/sync", request -> {
                String username = UserContextHolder.getCurrentUser();
                skillStorage.sync(username);
                return ServerResponse.ok().body(true);
            });
            // 复制 Skill（USER_CREATED / MARKET_PULLED → 新 USER_CREATED）
            // body: { "name": "<新名字>" } —— 可空；空则用「<源名>_副本」
            builder.POST("spring/ai/loom/skill/{name}/duplicate", request -> {
                String sourceName = request.pathVariable("name");
                String username = UserContextHolder.getCurrentUser();
                java.util.Map<String, Object> body;
                try {
                    body = request.body(java.util.Map.class);
                } catch (Exception ex) {
                    body = java.util.Map.of();
                }
                String newName = body == null ? null : (String) body.get("name");
                try {
                    String actual = skillStorage.duplicate(sourceName, newName, username);
                    return ServerResponse.ok().body(java.util.Map.of("name", actual));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    Integer sc = ex.getStatusCode();
                    int code = sc != null ? sc : 400;
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            return builder.build();
        }

        /**
         * Skill 市场（公共浏览 + 用户拉取 + 用户提交）
         */
        @Bean("loomAgentSkillMarketRouter")
        public RouterFunction<ServerResponse> loomAgentSkillMarketRouter(
                cn.wubo.spring.ai.loom.agent.skill.ISkillMarketService marketService,
                IUser user) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            // 任意用户：列出所有 APPROVED
            builder.GET("spring/ai/loom/market-skills", request -> ServerResponse.ok().body(marketService.listApproved()));
            // 任意用户：按 id 查
            builder.GET("spring/ai/loom/market-skills/{id}", request -> {
                // id 必须是数字——非数字走 400 而不是 500；service 抛 "Skill 不存在" → 404 而不是 500
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                try {
                    return ServerResponse.ok().body(marketService.get(id));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 任意用户：拉取到自己的 user_skill
            builder.POST("spring/ai/loom/market-skills/{id}/pull", request -> {
                String username = UserContextHolder.getCurrentUser();
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                try {
                    return ServerResponse.ok().body(marketService.pull(username, id));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 任意用户：提交新 Skill（status=PENDING）
            builder.POST("spring/ai/loom/user/market-skills", request -> {
                String username = UserContextHolder.getCurrentUser();
                cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest.class);
                if (body == null) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "请求体不能为空"));
                }
                try {
                    return ServerResponse.ok().body(marketService.submit(username, body));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    Integer sc = ex.getStatusCode();
                    int code = sc != null ? sc : 400;
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "数据约束失败: " + ex.getMostSpecificCause().getMessage()));
                } catch (NullPointerException npe) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "字段缺失（name/description/content 必填）"));
                }
            });
            // 任意用户：查看自己提交的 Skill
            builder.GET("spring/ai/loom/user/market-skills", request -> {
                String username = UserContextHolder.getCurrentUser();
                return ServerResponse.ok().body(marketService.listMySubmitted(username));
            });
            // 任意用户：撤回 PENDING 状态的提交
            builder.DELETE("spring/ai/loom/user/market-skills/{id}", request -> {
                String username = UserContextHolder.getCurrentUser();
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                try {
                    boolean ok = marketService.withdraw(username, id);
                    if (!ok) {
                        return ServerResponse.badRequest().body(java.util.Map.of("error", "只能撤回 PENDING 状态的提交"));
                    }
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    Integer sc = ex.getStatusCode();
                    int code = sc != null ? sc : 400;
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            return builder.build();
        }

        /**
         * Skill 市场管理（仅 admin）
         */
        @Bean("loomAgentSkillMarketAdminRouter")
        public RouterFunction<ServerResponse> loomAgentSkillMarketAdminRouter(
                cn.wubo.spring.ai.loom.agent.skill.ISkillMarketService marketService,
                IUser user) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            // 列出所有（含 PENDING/REJECTED）
            builder.GET("spring/ai/loom/admin/market-skills", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                return ServerResponse.ok().body(marketService.listAllForAdmin());
            });
            // 去掉 listPending 路由（无审批流，没有 PENDING 状态）
            // admin 直接新增（绕过审批）
            builder.POST("spring/ai/loom/admin/market-skills", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                cn.wubo.spring.ai.loom.agent.model.MarketSkillUpsertRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.model.MarketSkillUpsertRequest.class);
                if (body == null) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "请求体不能为空"));
                }
                try {
                    return ServerResponse.ok().body(marketService.adminCreate(username, body));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    Integer sc = ex.getStatusCode();
                    int code = sc != null ? sc : 400;
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "数据约束失败: " + ex.getMostSpecificCause().getMessage()));
                } catch (NullPointerException npe) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "字段缺失（name/description/content 必填）"));
                }
            });
            // admin 改任意 Skill
            builder.PUT("spring/ai/loom/admin/market-skills/{id}", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                // id 必须是数字——非数字走 400 而不是 500；service 抛 "Skill 不存在" → 404
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                cn.wubo.spring.ai.loom.agent.model.MarketSkillUpsertRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.model.MarketSkillUpsertRequest.class);
                try {
                    return ServerResponse.ok().body(marketService.adminUpdate(username, id, body));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // admin 删
            builder.DELETE("spring/ai/loom/admin/market-skills/{id}", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                try {
                    marketService.adminDelete(username, id);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 去掉审批流（approve/reject）。admin 下架走 adminDelete（级联清理 user_skill + role_skill）
            return builder.build();
        }

        /**
         * Skill 市场管理 v2 — 走 M0 重构后的 {@link cn.wubo.spring.ai.loom.agent.market.AbstractMarketAdminService}
         * 模板（统一的 admin CRUD + 审批/官方/精选/分类）。{@code loomAgentSkillMarketAdminRouter}
         * 是 v1 旧契约；本 bean 是 v2 新契约，二者并行存在以便灰度切换。
         * <p>
         * 由 {@link cn.wubo.spring.ai.loom.agent.user.AuthenticationFilter} 通过
         * {@code auth.adminPathPatterns=/spring/ai/loom/admin/**} 在 Servlet filter 层做
         * 管理员二次校验；router 内仍保留 {@code user.isAdmin(...)} 的兜底检查作为防御性
         * 深度（与 {@code loomAgentSkillMarketAdminRouter} 保持一致）。
         * <p>
         * T7 范围：CRUD + approve / reject / setOfficial / setFeaturedRank / setCategory 共 9 个端点。
         * T18 引入 {@code /announcement}（PUT/DELETE — 走 {@link cn.wubo.spring.ai.loom.agent.market.MarketAnnouncementRepository}）
         * 和 {@code /reviews/{username}}（DELETE — 走 {@link cn.wubo.spring.ai.loom.agent.market.IMarketContentReviewService}），
         * 共 3 个端点。
         */
        @Bean("loomAgentMarketSkillAdminRouter")
        public RouterFunction<ServerResponse> loomAgentMarketSkillAdminRouter(
                cn.wubo.spring.ai.loom.agent.skill.DefaultSkillMarketService svc,
                IUser user,
                @org.springframework.beans.factory.annotation.Qualifier("skillStatsService") IMarketContentStatsService skillStatsService,
                @org.springframework.beans.factory.annotation.Qualifier("skillReviewService") cn.wubo.spring.ai.loom.agent.market.IMarketContentReviewService skillReviewService,
                @org.springframework.beans.factory.annotation.Qualifier("marketAnnouncementRepository") cn.wubo.spring.ai.loom.agent.market.MarketAnnouncementRepository marketAnnouncementRepository) {
            RouterFunctions.Builder builder = RouterFunctions.route();

            // 9.1 列出所有（含 PENDING / APPROVED / REJECTED，按 MarketFilter 分页 + 排序）
            builder.GET("spring/ai/loom/admin/market-skills", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                cn.wubo.spring.ai.loom.agent.market.MarketFilter filter =
                        new cn.wubo.spring.ai.loom.agent.market.MarketFilter(
                                parsePageOr(request, "page", 0),
                                parsePageOr(request, "size", 20),
                                cn.wubo.spring.ai.loom.agent.market.MarketContentStatus.from(
                                        request.param("status").orElse(null)),
                                request.param("category").orElse(null),
                                request.param("query").orElse(null),
                                request.param("sortBy").orElse("official_rank"));
                try {
                    return ServerResponse.ok().body(svc.listPaged(filter));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.BAD_REQUEST.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 9.2 直接创建（绕过审批 — admin 走 bypass，默认 PENDING 由 service 落库）
            builder.POST("spring/ai/loom/admin/market-skills", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                cn.wubo.spring.ai.loom.agent.market.MarketCreateRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.MarketCreateRequest.class);
                if (body == null) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "请求体不能为空"));
                }
                try {
                    return ServerResponse.ok().body(svc.create(username, body));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.BAD_REQUEST.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "数据约束失败: " + ex.getMostSpecificCause().getMessage()));
                } catch (NullPointerException npe) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "字段缺失（name/description/content 必填）"));
                }
            });
            // 9.3 admin 改任意 Skill（name/description/content/category）
            builder.PUT("spring/ai/loom/admin/market-skills/{id}", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                // id 必须是数字 —— 非数字走 400 而不是 500；service 抛 "Skill 不存在" → 404
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                cn.wubo.spring.ai.loom.agent.market.MarketUpdateRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.MarketUpdateRequest.class);
                try {
                    return ServerResponse.ok().body(svc.update(id, body));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 9.4 admin 删
            builder.DELETE("spring/ai/loom/admin/market-skills/{id}", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                try {
                    svc.delete(id);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 9.5 admin 审批通过：status=APPROVED, reviewed_by/at 由 AbstractMarketAdminService#approve 落库
            builder.POST("spring/ai/loom/admin/market-skills/{id}/approve", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                try {
                    return ServerResponse.ok().body(svc.approve(id, username));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 9.6 admin 拒绝：body.comment 必填（service 抛 IllegalArgumentException → 400）
            builder.POST("spring/ai/loom/admin/market-skills/{id}/reject", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                cn.wubo.spring.ai.loom.agent.market.RejectBody body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.RejectBody.class);
                if (body == null || body.comment() == null || body.comment().isBlank()) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "reject 必须填评论(comment 必填)"));
                }
                try {
                    return ServerResponse.ok().body(svc.reject(id, username, body.comment()));
                } catch (IllegalArgumentException ex) {
                    // service 抛 IllegalArgumentException("reject 必须填评论(comment 必填)")
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", ex.getMessage()));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 9.7 标记/取消官方：isOfficial=true 提升到精选排序第一位
            builder.PUT("spring/ai/loom/admin/market-skills/{id}/official", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                cn.wubo.spring.ai.loom.agent.market.OfficialBody body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.OfficialBody.class);
                if (body == null) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "请求体不能为空"));
                }
                try {
                    svc.setOfficial(id, body.isOfficial(), username);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 9.8 调整精选排序：rank 越大越靠前（同 rank 内部按 submitted_at DESC）
            builder.PUT("spring/ai/loom/admin/market-skills/{id}/featured-rank", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                cn.wubo.spring.ai.loom.agent.market.FeaturedRankBody body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.FeaturedRankBody.class);
                if (body == null) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "请求体不能为空"));
                }
                try {
                    svc.setFeaturedRank(id, body.rank(), username);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 9.9 改分类（null/空字符串清空）
            builder.PUT("spring/ai/loom/admin/market-skills/{id}/category", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                cn.wubo.spring.ai.loom.agent.market.CategoryBody body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.CategoryBody.class);
                if (body == null) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "请求体不能为空"));
                }
                try {
                    svc.setCategory(id, body.category(), username);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // T18: admin 写入/覆盖公告 — announcement upsert + 把 featured_rank 钉到 999
            // （list 排序 is_official DESC, featured_rank DESC, submitted_at DESC →
            // rank=999 让带公告的 Skill 自然置顶）。
            // 走 svc.setFeaturedRank(Long, int, String) 与 admin 路由 9.8 一致,reviewer
            // 字段落 username 用于审计。title/body 必填(AnnouncementBody 仅声明,
            // service 不校验),router 层做空值校验。
            builder.PUT("spring/ai/loom/admin/market-skills/{id}/announcement", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                cn.wubo.spring.ai.loom.agent.market.AnnouncementBody body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.AnnouncementBody.class);
                if (body == null || body.title() == null || body.title().isBlank()
                        || body.body() == null || body.body().isBlank()) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "title 与 body 必填且不能为空"));
                }
                try {
                    marketAnnouncementRepository.upsert("SKILL", id, body.title(), body.body());
                    // 钉到顶部 — 999 让 list 排序自然把带公告的 Skill 置顶
                    svc.setFeaturedRank(id, 999, username);
                    return ServerResponse.ok().body(marketAnnouncementRepository.findOne("SKILL", id));
                } catch (RuntimeException ex) {
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    log.warn("announcement upsert failed for skill {}: {}", id, msg, ex);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(java.util.Map.of("error", msg));
                }
            });
            // T18: admin 删除公告 — announcement delete + featured_rank 回 0
            // (rank=0 让该 Skill 回到默认排序 — 即与官方位同 rank 时按 submitted_at DESC)。
            // 删除幂等 — 公告不存在或 featured_rank 已经是 0 都不报错。
            builder.DELETE("spring/ai/loom/admin/market-skills/{id}/announcement", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                try {
                    marketAnnouncementRepository.delete("SKILL", id);
                    svc.setFeaturedRank(id, 0, username);
                    return ServerResponse.ok().body(true);
                } catch (RuntimeException ex) {
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    log.warn("announcement delete failed for skill {}: {}", id, msg, ex);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(java.util.Map.of("error", msg));
                }
            });
            // T18: admin 强制删除某条评价 — username 是路径变量,非当前登录用户。
            // 路由层做 admin 校验,service.deleteAsAdmin 只做 SQL DELETE,不做权限二次校验。
            builder.DELETE("spring/ai/loom/admin/market-skills/{id}/reviews/{username}", request -> {
                String admin = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(admin))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                String targetUser = request.pathVariable("username");
                if (targetUser == null || targetUser.isBlank()) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "username 路径变量不能为空"));
                }
                try {
                    skillReviewService.deleteAsAdmin(id, targetUser);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.BAD_REQUEST.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });

            // T16: admin 重置 market_skill 的 pull_count / last_pulled_at。
            // body { count: 0 } → 把 pull_count 改写成 body.count、last_pulled_at 置 null
            // (canonical "reset" 语义)。即便 stats row 不存在也会 lazy-upsert 创建。
            builder.PUT("spring/ai/loom/admin/market-skills/{id}/stats-reset", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                java.util.Map<String, Object> body = request.body(java.util.Map.class);
                long newCount = 0L;
                if (body != null && body.get("count") instanceof Number n) {
                    newCount = n.longValue();
                }
                try {
                    skillStatsService.resetStats(id, newCount, null);
                    cn.wubo.spring.ai.loom.agent.market.StatsRow row = skillStatsService.getStats(id);
                    // HashMap (not Map.of) — lastAt can be null after reset, and
                    // Map.of forbids null values which would 500 the response.
                    java.util.Map<String, Object> statsBody = new java.util.HashMap<>();
                    statsBody.put("id", row.marketId());
                    statsBody.put("pullCount", row.pullCountOrSearchCount());
                    statsBody.put("lastAt", row.lastAt());
                    return ServerResponse.ok().body(statsBody);
                } catch (RuntimeException ex) {
                    // null-safe: getMessage() can be null (e.g. NullPointerException
                    // without a message), and Map.of rejects null values.
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    log.warn("stats-reset failed for skill {}: {}", id, msg, ex);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(java.util.Map.of("error", msg));
                }
            });
            return builder.build();
        }

        /**
         * Skill 市场公共路由 v2（M0 重构后的契约）— 任意已登录用户使用,无需 admin 权限。
         * <p>
         * {@code loomAgentSkillMarketRouter} 是 v1 旧契约（{@code ISkillMarketService} 上的
         * {@code listApproved()} / {@code submit()} / {@code pull()} / {@code withdraw()}
         * 等老方法），直接 SELECT ALL APPROVED；本 bean 是 v2 新契约（{@code listPaged(MarketFilter)}
         * 支持分页 / 搜索 / 分类 / 排序；submit 走 {@code create(...)} 直接落 {@code PENDING}）。
         * 二者并行存在以便灰度切换，与 T7/T8 admin router 的 v1/v2 拆分对称。
         * <p>
         * 认证由 {@link cn.wubo.spring.ai.loom.agent.user.AuthenticationFilter}（path patterns = /*）
         * 在 Servlet filter 层拦截,本 router 内不再做 admin 二次校验（5 个端点全部面向普通用户）。
         * <p>
         * T9 范围：spec § 6.1 的 5 个公开端点 + T18 接线的 3 个 {@code /reviews} 端点
         * （POST 提交 / GET 列表 / PUT 修改 — 走 {@link cn.wubo.spring.ai.loom.agent.market.IMarketContentReviewService}），
         * {@code /stats} GET 端点由 T16 接线（依赖 {@link cn.wubo.spring.ai.loom.agent.market.IMarketContentStatsService}）。
         */
        @Bean("loomAgentSkillMarketPublicRouter")
        public RouterFunction<ServerResponse> loomAgentSkillMarketPublicRouter(
                cn.wubo.spring.ai.loom.agent.skill.DefaultSkillMarketService svc,
                @org.springframework.beans.factory.annotation.Qualifier("skillStatsService") IMarketContentStatsService skillStatsService,
                @org.springframework.beans.factory.annotation.Qualifier("skillReviewService") cn.wubo.spring.ai.loom.agent.market.IMarketContentReviewService skillReviewService,
                @org.springframework.beans.factory.annotation.Qualifier("marketAnnouncementRepository") cn.wubo.spring.ai.loom.agent.market.MarketAnnouncementRepository marketAnnouncementRepository) {
            RouterFunctions.Builder builder = RouterFunctions.route();

            // 9.1 公开 list(APPROVED only) — MarketFilter.status 强制 APPROVED,防止 leak PENDING/REJECTED。
            // 支持分页(page/size)、分类过滤(category)、关键字搜索(query)、排序(sortBy)。
            builder.GET("spring/ai/loom/market-skills", request -> {
                cn.wubo.spring.ai.loom.agent.market.MarketFilter filter =
                        new cn.wubo.spring.ai.loom.agent.market.MarketFilter(
                                parsePageOr(request, "page", 0),
                                parsePageOr(request, "size", 20),
                                cn.wubo.spring.ai.loom.agent.market.MarketContentStatus.APPROVED,
                                request.param("category").orElse(null),
                                request.param("query").orElse(null),
                                request.param("sortBy").orElse("official_rank"));
                try {
                    return ServerResponse.ok().body(svc.listPaged(filter));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.BAD_REQUEST.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });

            // 9.2 公开 detail — 任意已登录用户按 id 查(返回 MarketSkill record)
            builder.GET("spring/ai/loom/market-skills/{id}", request -> {
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                try {
                    return ServerResponse.ok().body(svc.getById(id));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });

            // 9.3 author submit — status=PENDING(DefaultSkillMarketService.create 默认落 PENDING)。
            // body 走 MarketCreateRequest(name/description/content/category),与 admin create 共享同一 DTO,
            // 通过 router 层级差异(public vs admin)走不同的 service 方法,行为差异由 service 自身保证。
            builder.POST("spring/ai/loom/user/market-skills", request -> {
                String username = UserContextHolder.getCurrentUser();
                cn.wubo.spring.ai.loom.agent.market.MarketCreateRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.MarketCreateRequest.class);
                if (body == null) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "请求体不能为空"));
                }
                try {
                    return ServerResponse.ok().body(svc.create(username, body));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.BAD_REQUEST.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "数据约束失败: " + ex.getMostSpecificCause().getMessage()));
                } catch (NullPointerException npe) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "字段缺失（name/description/content 必填）"));
                }
            });

            // 9.4 author withdraw — 删除当前用户自己的 market_skill 行。
            // 走 ISkillMarketService.withdraw(username, id) — service 内部校验 author == username,
            // 避免 M0 的 delete(id) 无作者校验导致跨用户删除。本端点不接受 admin 删任意条目 —
            // 那个能力由 loomAgentMarketSkillAdminRouter.DELETE 提供。
            builder.DELETE("spring/ai/loom/user/market-skills/{id}", request -> {
                String username = UserContextHolder.getCurrentUser();
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                try {
                    boolean ok = svc.withdraw(username, id);
                    if (!ok) {
                        return ServerResponse.status(HttpStatus.NOT_FOUND)
                                .body(java.util.Map.of("error", "market_skill 不存在或不属于当前用户"));
                    }
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.BAD_REQUEST.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });

            // 9.5 pull — 把市场 Skill 拉到当前用户的 user_skill。
            // 走 ISkillMarketService.pull(username, id) — service 内部处理 USER_CREATED 冲突、
            // MARKET_PULLED 刷新 content 等语义,M0 抽象层不覆盖此 user-side 行为。
            //
            // T16 接线 (fix-up B1): 每次成功的 pull 自增 market_skill_stats.pull_count。
            // 失败/异常分支不计数。统计写入走 BatchedCounterService(30s 周期刷),失败重试一次
            // 后丢弃 — 与 incrementStat 内部 upsert 同款"best-effort"语义;绝不能让 stats
            // 故障把用户面 pull 拉崩。
            builder.POST("spring/ai/loom/market-skills/{id}/pull", request -> {
                String username = UserContextHolder.getCurrentUser();
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                try {
                    Object body = svc.pull(username, id);
                    // 统计写入必须不阻塞主路径 — 失败仅 WARN,不重抛
                    try {
                        skillStatsService.incrementStat(id, "PULL");
                    } catch (RuntimeException statEx) {
                        log.warn("skill pull stats increment failed for skill {}: {}",
                                id, statEx.getMessage());
                    }
                    return ServerResponse.ok().body(body);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });

            // T18: 公开评价提交 — H2 MERGE INTO upsert,(market_id, username) 是 PK。
            // Skill 端没有"先访问过"门槛,直接走 skillReviewService.submit(...);
            // 首次提交允许,无 edit_count 校验。冲突(同时两请求)由 PK + MERGE 解决。
            builder.POST("spring/ai/loom/market-skills/{id}/reviews", request -> {
                String username = UserContextHolder.getCurrentUser();
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                cn.wubo.spring.ai.loom.agent.market.ReviewSubmitRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.ReviewSubmitRequest.class);
                if (body == null) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "请求体不能为空"));
                }
                try {
                    return ServerResponse.ok().body(skillReviewService.submit(id, username, body));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.BAD_REQUEST.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // T18: 公开评价列表 — page/size 走 parsePageOr 默认 0/20。
            // 返回 Page<ReviewRow>(items, total, page, size),前端可按 total 渲染分页器。
            // 不暴露 admin 自评 — 由 skillReviewService.aggregate() 内部通过
            // JOIN user_info 过滤(本端点不直接用 aggregate,但保留该契约)。
            builder.GET("spring/ai/loom/market-skills/{id}/reviews", request -> {
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                int page = parsePageOr(request, "page", 0);
                int size = parsePageOr(request, "size", 20);
                return ServerResponse.ok().body(skillReviewService.listReviews(id, page, size));
            });
            // T18: 公开评价更新 — 1 次修改上限由 AbstractMarketReviewService.update
            // 内部校验 edit_count < 1,第二次 update 直接抛 LoomAgentRuntimeException(403,
            // "评价只能修改一次,请删除后重新提交")。路由层把 statusCode 原样转发(403)。
            // brief 描述为 "throws 422" 是规范期望;service 实际抛 403 以与 spec § 9.2
            // 对齐 — 状态码差异由 spec note 单独记录。
            builder.PUT("spring/ai/loom/market-skills/{id}/reviews/me", request -> {
                String username = UserContextHolder.getCurrentUser();
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                cn.wubo.spring.ai.loom.agent.market.ReviewUpdateRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.ReviewUpdateRequest.class);
                if (body == null) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "请求体不能为空"));
                }
                try {
                    return ServerResponse.ok().body(skillReviewService.update(id, username, body));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.BAD_REQUEST.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });

            // T16: 公开 stats — 任意已登录用户可查 market_skill 的 pull_count / last_pulled_at。
            // 返回 { id, pullCount, lastAt };首次访问(无 row)返回 count=0, lastAt=null。
            // 不需要 admin 权限,因为这只是只读计数,不暴露任何敏感数据。
            builder.GET("spring/ai/loom/market-skills/{id}/stats", request -> {
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                cn.wubo.spring.ai.loom.agent.market.StatsRow row = skillStatsService.getStats(id);
                // HashMap (not Map.of) — lastAt can be null (no row yet / just-reset),
                // and Map.of forbids null values.
                java.util.Map<String, Object> body = new java.util.HashMap<>();
                body.put("id", row.marketId());
                body.put("pullCount", row.pullCountOrSearchCount());
                body.put("lastAt", row.lastAt());
                return ServerResponse.ok().body(body);
            });

            // T19 fix-up: 公开读取公告 — 任何已登录用户可查 market_skill 的公告。
            // 不需要 admin 权限,因为公告是 admin 已发布的内容,纯只读,无敏感字段。
            // Long.parseLong 失败走 4xx;无公告 row 返回 204 No Content(announcementRepo.findOne
            // 在 row 不存在时返回 null)。
            builder.GET("spring/ai/loom/market-skills/{id}/announcement", request -> {
                Long id;
                try {
                    id = Long.parseLong(request.pathVariable("id"));
                } catch (NumberFormatException nfe) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 必须是数字: " + request.pathVariable("id")));
                }
                try {
                    cn.wubo.spring.ai.loom.agent.market.MarketAnnouncement ann =
                            marketAnnouncementRepository.findOne("SKILL", id);
                    if (ann == null) {
                        return ServerResponse.noContent().build();
                    }
                    // HashMap (not Map.of) — createdAt can be null in pathological rows.
                    java.util.Map<String, Object> body = new java.util.HashMap<>();
                    body.put("marketKind", ann.marketKind());
                    body.put("marketId", ann.marketId());
                    body.put("title", ann.title());
                    body.put("body", ann.body());
                    body.put("createdAt", ann.createdAt());
                    return ServerResponse.ok().body(body);
                } catch (RuntimeException ex) {
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    log.warn("announcement read failed for skill {}: {}", id, msg, ex);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(java.util.Map.of("error", msg));
                }
            });
            return builder.build();
        }

        /**
         * KB 市场管理 v2 — 走 M0 重构后的 {@link cn.wubo.spring.ai.loom.agent.market.AbstractMarketAdminService}
         * 模板（统一的 admin CRUD + 审批/官方/精选/分类）。{@code loomAgentKnowledgeMarketAdminRouter}
         * 是 v1 旧契约；本 bean 是 v2 新契约，二者并行存在以便灰度切换。
         * <p>
         * 与 Skill 端的真实差异：KB 主键 {@code loom_market_knowledge.id} 是
         * {@code VARCHAR(36)} UUID，而 {@code market_skill.id} 是 {@code BIGINT}。
         * 因此本 bean 的 path-variable id 保持 {@code String} 形态，service 调用走
         * {@code DefaultKnowledgeMarketService} 提供的 String 孪生方法
         * （{@code getById(String)} / {@code update(String, ...)} / {@code delete(String)} /
         * {@code approve(String, ...)} 等），其 {@code Long} 重载统一抛
         * {@link UnsupportedOperationException}。不允许把 String id parse 成 Long。
         * <p>
         * 由 {@link cn.wubo.spring.ai.loom.agent.user.AuthenticationFilter} 通过
         * {@code auth.adminPathPatterns=/spring/ai/loom/admin/**} 在 Servlet filter 层做
         * 管理员二次校验；router 内仍保留 {@code user.isAdmin(...)} 的兜底检查作为防御性
         * 深度（与 {@code loomAgentMarketSkillAdminRouter} 保持一致）。
         * <p>
         * T8 范围：CRUD + approve / reject / setOfficial / setFeaturedRank / setCategory 共 9 个端点，
         * 与 {@code loomAgentMarketSkillAdminRouter}（T7）1:1 镜像。
         * T18 引入 {@code /announcement}（PUT/DELETE — 走 {@link cn.wubo.spring.ai.loom.agent.market.MarketAnnouncementRepository}）
         * 和 {@code /reviews/{username}}（DELETE — 走 {@link cn.wubo.spring.ai.loom.agent.market.IMarketContentReviewService}），
         * 共 3 个端点。
         * <p>
         * TODO T8.7 refactor: T7 + T8 router 9 个 handler 高度对称(id 解析 + admin check + try/catch),
         * 唯一真正差异是 id 类型(String vs Long)与 service 类型。可抽
         * {@code private static <M, S> RouterFunctions.Builder marketAdminRoutes(
         *     String prefix, S svc, IMarketAdminOps<M> ops, Function<String, ?> idParser)}
         * 把两份 router 共享的样板代码收敛。KB / Skill 两端的 service 抽象层接口不统一
         * (KB 没有显式的 {@code IMarketAdminOps<String>}),需要先在 T7/T8 收敛完毕后再起
         * T8.7 PR 重构,避免引入跨 bean 的 interface 变更。
         */
        @Bean("loomAgentMarketKnowledgeAdminRouter")
        public RouterFunction<ServerResponse> loomAgentMarketKnowledgeAdminRouter(
                cn.wubo.spring.ai.loom.agent.knowledge.DefaultKnowledgeMarketService svc,
                cn.wubo.spring.ai.loom.agent.knowledge.market.KnowledgeTagService kbTagService,
                IUser user,
                @org.springframework.beans.factory.annotation.Qualifier("kbStatsService") IMarketContentStatsService kbStatsService,
                @org.springframework.beans.factory.annotation.Qualifier("kbReviewService") cn.wubo.spring.ai.loom.agent.market.IMarketContentReviewService kbReviewService,
                @org.springframework.beans.factory.annotation.Qualifier("marketAnnouncementRepository") cn.wubo.spring.ai.loom.agent.market.MarketAnnouncementRepository marketAnnouncementRepository,
                // M3+ T1.4: KB router 走 RouterIdParserKnowledge 把 path-variable 解析为
                // String(KB 主键是 VARCHAR(36) UUID,不允许 Long.parseLong)。
                // review/stats/announcement 服务的 String overload 内部再做
                // Long.parseLong + graceful-degradation(UUID → 404)。
                cn.wubo.spring.ai.loom.agent.knowledge.RouterIdParserKnowledge kbIdParser) {
            RouterFunctions.Builder builder = RouterFunctions.route();

            // 8.1 列出所有（含 PENDING / APPROVED / REJECTED，按 MarketFilter 分页 + 排序）
            builder.GET("spring/ai/loom/admin/market-knowledge", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                cn.wubo.spring.ai.loom.agent.market.MarketFilter filter =
                        new cn.wubo.spring.ai.loom.agent.market.MarketFilter(
                                parsePageOr(request, "page", 0),
                                parsePageOr(request, "size", 20),
                                cn.wubo.spring.ai.loom.agent.market.MarketContentStatus.from(
                                        request.param("status").orElse(null)),
                                request.param("category").orElse(null),
                                request.param("query").orElse(null),
                                request.param("sortBy").orElse("official_rank"));
                try {
                    return ServerResponse.ok().body(svc.listPaged(filter));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.BAD_REQUEST.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 8.2 直接创建（绕过审批 — admin 走 bypass，默认 PENDING 由 service 落库）
            builder.POST("spring/ai/loom/admin/market-knowledge", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                cn.wubo.spring.ai.loom.agent.market.MarketCreateRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.MarketCreateRequest.class);
                if (body == null) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "请求体不能为空"));
                }
                try {
                    return ServerResponse.ok().body(svc.create(username, body));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.BAD_REQUEST.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "数据约束失败: " + ex.getMostSpecificCause().getMessage()));
                } catch (NullPointerException npe) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "字段缺失（name/description 必填）"));
                }
            });
            // 8.3 admin 改任意 KB（name/description/category/isOfficial/featuredRank）
            // KB 端没有 content 列，service 自动忽略 body.content()。
            // id 是 VARCHAR(36) UUID —— path-variable 直接传 String,不允许 parseLong。
            builder.PUT("spring/ai/loom/admin/market-knowledge/{id}", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                String id = request.pathVariable("id");
                if (id == null || id.isBlank()) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 不能为空"));
                }
                cn.wubo.spring.ai.loom.agent.market.MarketUpdateRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.MarketUpdateRequest.class);
                try {
                    return ServerResponse.ok().body(svc.update(id, body));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 8.4 admin 删
            builder.DELETE("spring/ai/loom/admin/market-knowledge/{id}", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                String id = request.pathVariable("id");
                if (id == null || id.isBlank()) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 不能为空"));
                }
                try {
                    svc.delete(id);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 8.5 admin 审批通过：status=APPROVED, reviewed_by/at 由 service 落库
            builder.POST("spring/ai/loom/admin/market-knowledge/{id}/approve", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                String id = request.pathVariable("id");
                if (id == null || id.isBlank()) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 不能为空"));
                }
                try {
                    return ServerResponse.ok().body(svc.approve(id, username));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 8.6 admin 拒绝：body.comment 必填（service 抛 IllegalArgumentException → 400）
            builder.POST("spring/ai/loom/admin/market-knowledge/{id}/reject", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                String id = request.pathVariable("id");
                if (id == null || id.isBlank()) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 不能为空"));
                }
                cn.wubo.spring.ai.loom.agent.market.RejectBody body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.RejectBody.class);
                if (body == null || body.comment() == null || body.comment().isBlank()) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "reject 必须填评论(comment 必填)"));
                }
                try {
                    return ServerResponse.ok().body(svc.reject(id, username, body.comment()));
                } catch (IllegalArgumentException ex) {
                    // service 抛 IllegalArgumentException("reject 必须填评论(comment 必填)")
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", ex.getMessage()));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 8.7 标记/取消官方：isOfficial=true 提升到精选排序第一位
            builder.PUT("spring/ai/loom/admin/market-knowledge/{id}/official", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                String id = request.pathVariable("id");
                if (id == null || id.isBlank()) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 不能为空"));
                }
                cn.wubo.spring.ai.loom.agent.market.OfficialBody body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.OfficialBody.class);
                if (body == null) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "请求体不能为空"));
                }
                try {
                    svc.setOfficial(id, body.isOfficial(), username);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 8.8 调整精选排序：rank 越大越靠前（同 rank 内部按 submitted_at DESC）
            builder.PUT("spring/ai/loom/admin/market-knowledge/{id}/featured-rank", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                String id = request.pathVariable("id");
                if (id == null || id.isBlank()) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 不能为空"));
                }
                cn.wubo.spring.ai.loom.agent.market.FeaturedRankBody body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.FeaturedRankBody.class);
                if (body == null) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "请求体不能为空"));
                }
                try {
                    svc.setFeaturedRank(id, body.rank(), username);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // 8.9 改分类（null/空字符串清空）
            builder.PUT("spring/ai/loom/admin/market-knowledge/{id}/category", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                String id = request.pathVariable("id");
                if (id == null || id.isBlank()) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 不能为空"));
                }
                cn.wubo.spring.ai.loom.agent.market.CategoryBody body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.CategoryBody.class);
                if (body == null) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "请求体不能为空"));
                }
                try {
                    svc.setCategory(id, body.category(), username);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // T18: admin 写入/覆盖公告 — announcement upsert + 把 featured_rank 钉到 999
            // （KB 端排序同样走 is_official DESC, featured_rank DESC, submitted_at DESC,
            // 999 让带公告的 KB 自然置顶）。
            //
            // M3+ T1.4:KB id 是 VARCHAR(36) UUID,经 {@link cn.wubo.spring.ai.loom.agent.knowledge.RouterIdParserKnowledge}
            // parse 后直接传 String 给 announcementRepo 的 String overload;String overload
            // 内部 Long.parseLong + NFE→404 graceful-degradation(UUID 永远不在 BIGINT
            // announcement 表中)。删除 router 层的 Long.parseLong,真实 UUID 可达 service。
            builder.PUT("spring/ai/loom/admin/market-knowledge/{id}/announcement", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                String rawId = kbIdParser.parse(request.pathVariable("id"));
                cn.wubo.spring.ai.loom.agent.market.AnnouncementBody body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.AnnouncementBody.class);
                if (body == null || body.title() == null || body.title().isBlank()
                        || body.body() == null || body.body().isBlank()) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "title 与 body 必填且不能为空"));
                }
                try {
                    marketAnnouncementRepository.upsert("KNOWLEDGE", rawId, body.title(), body.body());
                    // KB 端 setFeaturedRank 接受 String(与 KB 的 VARCHAR(36) UUID 主键对齐)
                    svc.setFeaturedRank(rawId, 999, username);
                    return ServerResponse.ok().body(marketAnnouncementRepository.findOne("KNOWLEDGE", rawId));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                } catch (RuntimeException ex) {
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    log.warn("announcement upsert failed for kb {}: {}", rawId, msg, ex);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(java.util.Map.of("error", msg));
                }
            });
            // T18: admin 删除公告 — announcement delete + featured_rank 回 0
            // (KB String id 版本,与 PUT 对称)。删除幂等,公告不存在或 rank 已为 0 都不报错。
            builder.DELETE("spring/ai/loom/admin/market-knowledge/{id}/announcement", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                String rawId = kbIdParser.parse(request.pathVariable("id"));
                try {
                    marketAnnouncementRepository.delete("KNOWLEDGE", rawId);
                    svc.setFeaturedRank(rawId, 0, username);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                } catch (RuntimeException ex) {
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    log.warn("announcement delete failed for kb {}: {}", rawId, msg, ex);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(java.util.Map.of("error", msg));
                }
            });
            // T18: admin 强制删除某条评价 — username 是路径变量,非当前登录用户。
            // 路由层做 admin 校验,service.deleteAsAdmin 只做 SQL DELETE,不做权限二次校验。
            builder.DELETE("spring/ai/loom/admin/market-knowledge/{id}/reviews/{username}", request -> {
                String admin = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(admin))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                String rawId = kbIdParser.parse(request.pathVariable("id"));
                String targetUser = request.pathVariable("username");
                if (targetUser == null || targetUser.isBlank()) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "username 路径变量不能为空"));
                }
                try {
                    kbReviewService.deleteAsAdmin(rawId, targetUser);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.BAD_REQUEST.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });

            // T16: admin 重置 market_knowledge 的 search_count / last_searched_at。
            // 与 Skill 端 stats-reset 镜像。
            // M3+ T1.4:KB id 经 RouterIdParserKnowledge parse 后直接传 String 给
            // kbStatsService String overload;UUID 路径走 graceful-degradation
            // (抛 404 — stats 表 BIGINT PK 与 KB UUID 不兼容)。
            builder.PUT("spring/ai/loom/admin/market-knowledge/{id}/stats-reset", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                String rawId = kbIdParser.parse(request.pathVariable("id"));
                java.util.Map<String, Object> body = request.body(java.util.Map.class);
                long newCount = 0L;
                if (body != null && body.get("count") instanceof Number n) {
                    newCount = n.longValue();
                }
                try {
                    kbStatsService.resetStats(rawId, newCount, null);
                    cn.wubo.spring.ai.loom.agent.market.StatsRow row = kbStatsService.getStats(rawId);
                    // HashMap (not Map.of) — lastAt can be null after reset.
                    java.util.Map<String, Object> statsBody = new java.util.HashMap<>();
                    statsBody.put("id", row.marketId());
                    statsBody.put("searchCount", row.pullCountOrSearchCount());
                    statsBody.put("lastAt", row.lastAt());
                    return ServerResponse.ok().body(statsBody);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                } catch (RuntimeException ex) {
                    // null-safe: getMessage() can be null, and Map.of rejects null values.
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    log.warn("stats-reset failed for kb {}: {}", rawId, msg, ex);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(java.util.Map.of("error", msg));
                }
            });

            // M2/T20: admin 改 KB tag —— body {tags: [...]} 整组替换。
            // KB id 是 VARCHAR(36) UUID,path-variable 直接当 String,不 parseLong。
            // replaceTags 内部 DELETE + 批量 MERGE,KB 不存在时抛 IllegalArgumentException
            // → router 翻译成 404 (与 GET detail 端点对齐)。
            builder.PUT("spring/ai/loom/admin/market-knowledge/{id}/tags", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                String id = request.pathVariable("id");
                if (id == null || id.isBlank()) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "id 不能为空"));
                }
                java.util.Map<String, Object> body = request.body(java.util.Map.class);
                if (body == null) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "请求体不能为空"));
                }
                Object tagsObj = body.get("tags");
                java.util.List<String> tags;
                if (tagsObj == null) {
                    tags = java.util.Collections.emptyList();
                } else if (tagsObj instanceof java.util.List<?> raw) {
                    tags = new java.util.ArrayList<>();
                    for (Object o : raw) {
                        if (o != null) tags.add(o.toString());
                    }
                } else {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "tags 字段必须是数组"));
                }
                try {
                    kbTagService.replaceTags(
                            cn.wubo.spring.ai.loom.agent.knowledge.market.KnowledgeTagService.MARKET_KIND_KNOWLEDGE,
                            id, tags);
                    java.util.List<String> after = kbTagService.listTags(
                            cn.wubo.spring.ai.loom.agent.knowledge.market.KnowledgeTagService.MARKET_KIND_KNOWLEDGE,
                            id);
                    return ServerResponse.ok().body(java.util.Map.of("tags", after));
                } catch (IllegalArgumentException ex) {
                    return ServerResponse.status(HttpStatus.NOT_FOUND)
                            .body(java.util.Map.of("error", ex.getMessage()));
                } catch (RuntimeException ex) {
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    log.warn("replace tags failed for kb {}: {}", id, msg, ex);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(java.util.Map.of("error", msg));
                }
            });

            // M2/T20: admin 查 KB tag —— 与公开 GET 等价但路径在 /admin/ 下,
            // 方便 admin 控制台 / 表单预填。语义一致:返回 {tags: [...]};
            // 不存在 / 空 KB 直接 [] (no 4xx),保证 admin UI 不会因脏 id 弹 toast。
            builder.GET("spring/ai/loom/admin/market-knowledge/{id}/tags", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "无权限"));
                String id = request.pathVariable("id");
                if (id == null || id.isBlank()) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "id 不能为空"));
                }
                try {
                    java.util.List<String> tags = kbTagService.listTags(
                            cn.wubo.spring.ai.loom.agent.knowledge.market.KnowledgeTagService.MARKET_KIND_KNOWLEDGE, id);
                    return ServerResponse.ok().body(java.util.Map.of("tags", tags));
                } catch (RuntimeException ex) {
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    log.warn("admin list tags failed for kb {}: {}", id, msg, ex);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(java.util.Map.of("error", msg));
                }
            });

            return builder.build();
        }

        /**
         * KB 市场公共路由 v2(M0 重构后的契约) — 任意已登录用户使用,无需 admin 权限。
         * <p>
         * 与 T8 admin 路由(走 M0 重构后的 {@code AbstractMarketAdminService})对称;本 bean 镜像
         * T9 skill public router 的 5 端点 + 1 KB 独有端点:
         * <ol>
         *   <li>{@code GET /spring/ai/loom/market-knowledge} — 公开 list(APPROVED only)</li>
         *   <li>{@code GET /spring/ai/loom/market-knowledge/{id}} — 公开 detail</li>
         *   <li>{@code POST /spring/ai/loom/user/market-knowledge} — author submit → PENDING
         *       (走 v2 {@code kbSvc.create(username, body)})</li>
         *   <li>{@code DELETE /spring/ai/loom/user/market-knowledge/{id}} — author withdraw
         *       (走 v1 {@code IKnowledgeMarketService.withdraw(String)};非作者/不存在统一返回 404,
         *       与 T9 同款隐私适配,不泄露他人市场条目的存在性)</li>
         *   <li>{@code POST /spring/ai/loom/market-knowledge/{id}/pull} — 拉取到自己的 KB
         *       (走 v1 {@code IKnowledgeMarketService.pull(username, id)})</li>
         *   <li>{@code POST /spring/ai/loom/market-knowledge/{id}/access} — KB 独有,
         *       自增 {@code loom_user_knowledge.access_count};完整 KB search stat 由 T16 接线</li>
         * </ol>
         * {@code /reviews} POST/GET/PUT 三个端点由 T18 接线
         * (走 {@link cn.wubo.spring.ai.loom.agent.market.IMarketContentReviewService} 的 KB 子类实现,
         * submit 带 access_count 严门槛);
         * {@code /stats} GET 端点由 T16 接线
         * (依赖 {@link cn.wubo.spring.ai.loom.agent.market.IMarketContentStatsService})。
         * <p>
         * KB 端 id 是 {@code VARCHAR(36)} UUID,不允许 parseLong;所有 path-variable 直接当 String 传。
         * 认证由 {@link cn.wubo.spring.ai.loom.agent.user.AuthenticationFilter} 在 Servlet filter 层
         * 拦截,本 router 内不再做 admin 二次校验(6 个端点全部面向普通已登录用户)。
         */
        @Bean("loomAgentMarketKnowledgePublicRouter")
        public RouterFunction<ServerResponse> loomAgentMarketKnowledgePublicRouter(
                cn.wubo.spring.ai.loom.agent.knowledge.DefaultKnowledgeMarketService kbSvc,
                cn.wubo.spring.ai.loom.agent.knowledge.market.KnowledgeTagService kbTagService,
                @org.springframework.beans.factory.annotation.Qualifier("kbStatsService") IMarketContentStatsService kbStatsService,
                @org.springframework.beans.factory.annotation.Qualifier("kbReviewService") cn.wubo.spring.ai.loom.agent.market.IMarketContentReviewService kbReviewService,
                @org.springframework.beans.factory.annotation.Qualifier("marketAnnouncementRepository") cn.wubo.spring.ai.loom.agent.market.MarketAnnouncementRepository marketAnnouncementRepository,
                // M3+ T1.4: 同 admin router — KB id 是 VARCHAR(36) UUID,
                // review/stats 服务的 String overload 走 graceful-degradation。
                cn.wubo.spring.ai.loom.agent.knowledge.RouterIdParserKnowledge kbIdParser) {
            RouterFunctions.Builder builder = RouterFunctions.route();

            // 10.1 公开 list(APPROVED only) — MarketFilter.status 强制 APPROVED,防止 leak PENDING/REJECTED。
            // 支持分页(page/size)、分类过滤(category)、关键字搜索(query)、排序(sortBy)。
            // M2/T20 tag 过滤:?tag=foo&tag=bar → 交集查询(KB 必须同时挂有所有指定 tag),
            // 与 MarketFilter.status='APPROVED' 联合生效。无 tag 参数时走原 listPaged 路径。
            builder.GET("spring/ai/loom/market-knowledge", request -> {
                java.util.List<String> tagParams = new java.util.ArrayList<>();
                request.params().getOrDefault("tag", java.util.Collections.emptyList())
                        .forEach(t -> {
                            if (t != null && !t.isBlank()) tagParams.add(t.trim());
                        });
                int page = parsePageOr(request, "page", 0);
                int size = parsePageOr(request, "size", 20);
                try {
                    if (!tagParams.isEmpty()) {
                        // tag 交集模式 — 直接走 tagService.findByAllTags (不通过 MarketFilter;
                        // 该方法内部仅以 tag AND status=APPROVED 过滤,语义与 listPaged 一致)。
                        // post-filter APPROVED:findByAllTags 当前不过滤 status,需在 router 层收口。
                        java.util.List<cn.wubo.spring.ai.loom.agent.model.MarketKnowledgeRecord> rows =
                                kbTagService.findByAllTags(tagParams, page, size);
                        java.util.List<cn.wubo.spring.ai.loom.agent.model.MarketKnowledgeRecord> approved =
                                rows.stream()
                                        .filter(r -> cn.wubo.spring.ai.loom.agent.market.MarketContentStatus.APPROVED.name().equals(r.status()))
                                        .toList();
                        return ServerResponse.ok().body(approved);
                    }
                    cn.wubo.spring.ai.loom.agent.market.MarketFilter filter =
                            new cn.wubo.spring.ai.loom.agent.market.MarketFilter(
                                    page,
                                    size,
                                    cn.wubo.spring.ai.loom.agent.market.MarketContentStatus.APPROVED,
                                    request.param("category").orElse(null),
                                    request.param("query").orElse(null),
                                    request.param("sortBy").orElse("official_rank"));
                    return ServerResponse.ok().body(kbSvc.listPaged(filter));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.BAD_REQUEST.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });

            // M2/T20: 公开 GET tag 列表 — 任何已登录用户可查 KB 挂的 tag。
            // KB id 是 VARCHAR(36) UUID — path-variable 直接当 String,不 parseLong。
            // 不存在 / 空 KB 直接返回 [] (no 4xx),与 listApproved 模式一致。
            builder.GET("spring/ai/loom/market-knowledge/{id}/tags", request -> {
                String id = request.pathVariable("id");
                if (id == null || id.isBlank()) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "id 不能为空"));
                }
                try {
                    java.util.List<String> tags = kbTagService.listTags(
                            cn.wubo.spring.ai.loom.agent.knowledge.market.KnowledgeTagService.MARKET_KIND_KNOWLEDGE, id);
                    return ServerResponse.ok().body(java.util.Map.of("tags", tags));
                } catch (RuntimeException ex) {
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    log.warn("list tags failed for kb {}: {}", id, msg, ex);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(java.util.Map.of("error", msg));
                }
            });

            // 10.2 公开 detail — KB id 是 VARCHAR(36) UUID,path-variable 直接传 String,不 parseLong。
            builder.GET("spring/ai/loom/market-knowledge/{id}", request -> {
                String id = request.pathVariable("id");
                if (id == null || id.isBlank()) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "id 不能为空"));
                }
                try {
                    return ServerResponse.ok().body(kbSvc.getById(id));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });

            // 10.3 author submit — 走 v2 kbSvc.create(username, body),落 PENDING(M0 默认行为)。
            // body 走 MarketCreateRequest(name/description/content/category);content 在 KB 端
            // 被忽略(loom_market_knowledge 无 content 列),行为差异由 service 自身保证。
            builder.POST("spring/ai/loom/user/market-knowledge", request -> {
                String username = UserContextHolder.getCurrentUser();
                cn.wubo.spring.ai.loom.agent.market.MarketCreateRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.MarketCreateRequest.class);
                if (body == null) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "请求体不能为空"));
                }
                try {
                    return ServerResponse.ok().body(kbSvc.create(username, body));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.BAD_REQUEST.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "数据约束失败: " + ex.getMostSpecificCause().getMessage()));
                } catch (NullPointerException npe) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "字段缺失（name/description 必填）"));
                }
            });

            // 10.4 author withdraw — v1 IKnowledgeMarketService.withdraw(String) 内部已做 author 校验
            // (非作者抛 403,不存在抛 404)。router 层把两种异常统一映射到 404,与 T9 同款隐私适配
            // (避免泄露他人市场条目的存在性)。
            builder.DELETE("spring/ai/loom/user/market-knowledge/{id}", request -> {
                String id = request.pathVariable("id");
                if (id == null || id.isBlank()) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "id 不能为空"));
                }
                try {
                    kbSvc.withdraw(id);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    // 404 for both not-found & not-owned — privacy adaptation (mirrors T9)
                    return ServerResponse.status(HttpStatus.NOT_FOUND)
                            .body(java.util.Map.of("error", "market_knowledge 不存在或不属于当前用户"));
                }
            });

            // 10.5 pull — v1 IKnowledgeMarketService.pull(username, id) 内部处理已订阅检查
            // (409) 与 ROLE_GRANTED 锁定冲突(409),M0 抽象层不覆盖此 user-side 行为。
            builder.POST("spring/ai/loom/market-knowledge/{id}/pull", request -> {
                String username = UserContextHolder.getCurrentUser();
                String id = request.pathVariable("id");
                if (id == null || id.isBlank()) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", "id 不能为空"));
                }
                try {
                    kbSvc.pull(username, id);
                    return ServerResponse.ok().body(java.util.Map.of("success", true));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });

            // 10.6 access(KB 独有) — 自增 loom_user_knowledge.access_count;用户未订阅时 no-op
            // (rows=0 → 返回 0),不创建 phantom pull 占用 source='MARKET_PULLED' 配额。
            // 完整 KB search stat(loom_market_knowledge_stats)由 T16 接线。
            //
            // M3+ T1.4:access 成功后把 KB id 走 RouterIdParserKnowledge + kbStatsService.incrementStat(String);
            // UUID 路径由 String overload 内部静默跳过(no-op,best-effort),access 主路径不受影响。
            // 删掉 router 层的 Long.parseLong + try/catch 样板代码,语义不变。
            builder.POST("spring/ai/loom/market-knowledge/{id}/access", request -> {
                String username = UserContextHolder.getCurrentUser();
                String id = kbIdParser.parse(request.pathVariable("id"));
                try {
                    long newCount = kbSvc.access(username, id);
                    // KB search stat 接线:String overload 内部 UUID 静默跳过,异常仍上抛
                    // 但不会阻塞主路径(原 try/catch RuntimeException 保留)。
                    try {
                        kbStatsService.incrementStat(id, "SEARCH");
                    } catch (RuntimeException statEx) {
                        log.warn("KB access stats increment failed for kb {}: {}", id, statEx.getMessage());
                    }
                    return ServerResponse.ok().body(java.util.Map.of(
                            "accessCount", newCount,
                            "subscribed", newCount > 0));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });

            // T18: 公开评价提交 — KB 端在 submit 内部加严门槛:用户必须先 access 过
            // 该 KB(loom_user_knowledge.access_count >= 1),否则由 kbReviewService.submit
            // 抛 LoomAgentRuntimeException(403, "请先访问过该知识库再评")。路由层把 statusCode
            // 原样转发,前端可在 403 时引导用户先去搜/读 KB 再来评。
            //
            // M3+ T1.4: KB id 经 RouterIdParserKnowledge parse 后直接传 String 给
            // kbReviewService String overload;UUID → 抛 404 "市场知识库不存在",
            // numeric id → Long 路径走 submit 业务逻辑(严门槛 / MERGE INTO upsert)。
            builder.POST("spring/ai/loom/market-knowledge/{id}/reviews", request -> {
                String username = UserContextHolder.getCurrentUser();
                String rawId = kbIdParser.parse(request.pathVariable("id"));
                cn.wubo.spring.ai.loom.agent.market.ReviewSubmitRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.ReviewSubmitRequest.class);
                if (body == null) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "请求体不能为空"));
                }
                try {
                    return ServerResponse.ok().body(kbReviewService.submit(rawId, username, body));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.BAD_REQUEST.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            // T18: 公开评价列表 — KB 端与 Skill 端同款分页契约(Page<ReviewRow>)。
            // M3+ T1.4: KB id 走 RouterIdParserKnowledge;UUID → 抛 404;numeric id → Long 路径。
            builder.GET("spring/ai/loom/market-knowledge/{id}/reviews", request -> {
                String rawId = kbIdParser.parse(request.pathVariable("id"));
                int page = parsePageOr(request, "page", 0);
                int size = parsePageOr(request, "size", 20);
                return ServerResponse.ok().body(kbReviewService.listReviews(rawId, page, size));
            });
            // T18: 公开评价更新 — 1 次修改上限由 AbstractMarketReviewService.update
            // 内部校验 edit_count < 1,第二次 update 直接抛 LoomAgentRuntimeException(403,
            // "评价只能修改一次,请删除后重新提交")。KB 端与 Skill 端完全镜像,严门槛只在
            // submit 时生效;update 不需要 access_count 校验(已经 submit 过)。
            //
            // M3+ T1.4: KB id 走 RouterIdParserKnowledge。
            builder.PUT("spring/ai/loom/market-knowledge/{id}/reviews/me", request -> {
                String username = UserContextHolder.getCurrentUser();
                String rawId = kbIdParser.parse(request.pathVariable("id"));
                cn.wubo.spring.ai.loom.agent.market.ReviewUpdateRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.market.ReviewUpdateRequest.class);
                if (body == null) {
                    return ServerResponse.badRequest()
                            .body(java.util.Map.of("error", "请求体不能为空"));
                }
                try {
                    return ServerResponse.ok().body(kbReviewService.update(rawId, username, body));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.BAD_REQUEST.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });

            // T16: 公开 stats — 任意已登录用户可查 market_knowledge 的 search_count / last_searched_at。
            // 返回 { id, searchCount, lastAt };首次访问(无 row)返回 count=0, lastAt=null。
            //
            // M3+ T1.4: KB id 走 RouterIdParserKnowledge → kbStatsService.getStats(String);
            // UUID 走 String overload 抛 404;numeric id → Long 路径正常查表。
            builder.GET("spring/ai/loom/market-knowledge/{id}/stats", request -> {
                String rawId = kbIdParser.parse(request.pathVariable("id"));
                try {
                    cn.wubo.spring.ai.loom.agent.market.StatsRow row = kbStatsService.getStats(rawId);
                    // HashMap (not Map.of) — lastAt can be null, and Map.of forbids null values.
                    java.util.Map<String, Object> body = new java.util.HashMap<>();
                    body.put("id", row.marketId());
                    body.put("searchCount", row.pullCountOrSearchCount());
                    body.put("lastAt", row.lastAt());
                    return ServerResponse.ok().body(body);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : HttpStatus.NOT_FOUND.value();
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });

            // T19 fix-up: 公开读取公告 — 任何已登录用户可查 market_knowledge 的公告。
            // 不需要 admin 权限,因为公告是 admin 已发布的内容,纯只读,无敏感字段。
            // KB id 在 V1.0 schema 是 VARCHAR(36) UUID,market_content_announcement.market_id
            // 是 BIGINT — UUIDs 永远不会有匹配 row。通过 findOneByRawId 把 UUID 字符串
            // 走 graceful-degradation 返回 null (204),而不是 4xx。Numeric id (测试用)
            // 走 Long 路径正常查表。
            builder.GET("spring/ai/loom/market-knowledge/{id}/announcement", request -> {
                String idStr = request.pathVariable("id");
                if (idStr == null || idStr.isBlank()) {
                    return ServerResponse.badRequest().body(java.util.Map.of(
                            "error", "id 不能为空"));
                }
                try {
                    cn.wubo.spring.ai.loom.agent.market.MarketAnnouncement ann =
                            marketAnnouncementRepository.findOneByRawId("KNOWLEDGE", idStr);
                    if (ann == null) {
                        return ServerResponse.noContent().build();
                    }
                    java.util.Map<String, Object> body = new java.util.HashMap<>();
                    body.put("marketKind", ann.marketKind());
                    body.put("marketId", ann.marketId());
                    body.put("title", ann.title());
                    body.put("body", ann.body());
                    body.put("createdAt", ann.createdAt());
                    return ServerResponse.ok().body(body);
                } catch (RuntimeException ex) {
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    log.warn("announcement read failed for kb {}: {}", idStr, msg, ex);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(java.util.Map.of("error", msg));
                }
            });
            return builder.build();
        }

        /**
         * 解析整数 query 参数 —— 非数字 / 缺失走默认值，不抛 500。
         */
        private static int parsePageOr(
                org.springframework.web.servlet.function.ServerRequest request,
                String param, int defaultValue) {
            return request.param(param)
                    .map(s -> {
                        try {
                            return Integer.parseInt(s);
                        } catch (NumberFormatException e) {
                            return defaultValue;
                        }
                    })
                    .orElse(defaultValue);
        }

        /**
         * 角色授权 Skill（仅 admin）
         */
        @Bean("loomAgentSkillRoleAdminRouter")
        public RouterFunction<ServerResponse> loomAgentSkillRoleAdminRouter(
                cn.wubo.spring.ai.loom.agent.skill.ISkillRoleAdmin roleAdmin,
                IUser user) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            builder.GET("spring/ai/loom/admin/roles/{code}/skills", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                String code = request.pathVariable("code");
                return ServerResponse.ok().body(roleAdmin.getRoleSkills(code));
            });
            builder.PUT("spring/ai/loom/admin/roles/{code}/skills", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                String code = request.pathVariable("code");
                cn.wubo.spring.ai.loom.agent.model.SetRoleSkillsRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.model.SetRoleSkillsRequest.class);
                roleAdmin.setRoleSkills(code, body == null ? null : body.items());
                return ServerResponse.ok().body(true);
            });
            return builder.build();
        }

        /**
         * 角色授权知识库（仅 admin）
         */
        @Bean("loomAgentKnowledgeRoleAdminRouter")
        public RouterFunction<ServerResponse> loomAgentKnowledgeRoleAdminRouter(
                cn.wubo.spring.ai.loom.agent.knowledge.IKnowledgeRoleAdmin knowledgeRoleAdmin,
                IUser user) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            builder.GET("spring/ai/loom/admin/roles/{code}/knowledge", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                String code = request.pathVariable("code");
                return ServerResponse.ok().body(knowledgeRoleAdmin.getRoleKnowledges(code));
            });
            builder.PUT("spring/ai/loom/admin/roles/{code}/knowledge", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                String code = request.pathVariable("code");
                cn.wubo.spring.ai.loom.agent.model.SetRoleKnowledgeRequest body =
                        request.body(cn.wubo.spring.ai.loom.agent.model.SetRoleKnowledgeRequest.class);
                knowledgeRoleAdmin.setRoleKnowledges(code, body == null ? null : body.items());
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
                // IFile.getById 在 row 不存在时抛 EmptyResultDataAccessException（queryForObject），
                // 任意非 UUID 字符串也走不到 row 但同样会触发异常——这里统一兜底成 404 而不是 500
                FileRecord fileRecord;
                try {
                    fileRecord = file.getById(id, UserContextHolder.getCurrentUser());
                } catch (org.springframework.dao.EmptyResultDataAccessException e) {
                    return ServerResponse.notFound().build();
                } catch (org.springframework.dao.IncorrectResultSizeDataAccessException e) {
                    return ServerResponse.notFound().build();
                }
                if (fileRecord == null) {
                    return ServerResponse.notFound().build();
                }
                // 选 Content-Type：优先 FileRecord.mimeType（写入时 Tika 探测过），没有再按扩展名猜，最后兜底 octet-stream
                MediaType contentType = resolveContentType(fileRecord);
                // 拼 Content-Disposition：中文文件名按 RFC 5987 用 filename*=UTF-8''<urlencoded>，
                // 同时给一个 ASCII 兜底（去掉非 ASCII 字符 + 保留扩展名），老浏览器/curl 也能用
                return ServerResponse.ok()
                        .contentType(contentType)
                        .contentLength(fileRecord.size())
                        .header("Content-Disposition", buildContentDisposition(fileRecord.fileName()))
                        .build((res, req) -> {
                            try (InputStream is = Files.newInputStream(Path.of(fileRecord.path()));
                                 OutputStream os = req.getOutputStream()) {
                                is.transferTo(os);
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
                // multipartData() 自身在 Content-Type 不是 multipart/* 或 body 为空时会抛
                // InvalidContentTypeException (Tomcat) / ServerWebInputException (WebFlux)，需要提前捕获。
                String fileErrMsg = "上传的文件不能为空，请检查请求参数中是否包含名为'file'的文件";
                Part part;
                try {
                    part = request.multipartData().getFirst("file");
                } catch (Exception e) {
                    // Tomcat 缺 multipart header / body 不解析 → 400 而不是 500
                    return ServerResponse.badRequest().body(java.util.Map.of("error", fileErrMsg));
                }
                if (part == null) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", fileErrMsg));
                }
                String fileId = upload.upload(part.getInputStream(), part.getSubmittedFileName(), part.getContentType());
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(java.util.Map.of("fileId", fileId, "status", "success"));
            });
            return builder.build();
        }

        /**
         * 构建用户文件目录树 JSON
         */
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

        /**
         * 根据路径获取或创建 fileId，用于预览/下载桥接
         */
        private String getOrCreateFileId(String fileBasePath, String path, String username, IFile file) {
            try {
                Path baseDir = Paths.get(fileBasePath, username);
                Path resolved = baseDir.resolve(path).normalize();
                if (!resolved.startsWith(baseDir) || !Files.exists(resolved) || !Files.isRegularFile(resolved)) {
                    return null;
                }
                // Normalize again after resolving symlinks. A lexical prefix check alone
                // would allow a link inside the user's directory to point outside it.
                Path realBaseDir = baseDir.toRealPath();
                Path realResolved = resolved.toRealPath();
                if (!realResolved.startsWith(realBaseDir) || !Files.isRegularFile(realResolved)) {
                    return null;
                }
                String pathStr = realResolved.toString();
                FileRecord existing = file.getByExactPath(pathStr, username);
                if (existing != null) return existing.id();

                org.apache.tika.Tika tika = new org.apache.tika.Tika();
                String mimeType = tika.detect(realResolved.toFile());
                String fileId = java.util.UUID.randomUUID().toString();
                java.nio.file.attribute.BasicFileAttributes attrs = Files.readAttributes(realResolved, java.nio.file.attribute.BasicFileAttributes.class);
                file.insert(new FileRecord(
                        fileId,
                        null,
                        realResolved.getFileName().toString(),
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
                // 空 body / 缺 name → 400 而不是 500（request.body 返回 null → knowledgeRecord.name() NPE）
                if (knowledgeRecord == null || knowledgeRecord.name() == null || knowledgeRecord.name().isBlank()) {
                    return ServerResponse.badRequest().contentType(MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of("message", "知识库名称(name)不能为空"));
                }
                try {
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(knowledge.insert(knowledgeRecord.name(), knowledgeRecord.description()));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException e) {
                    // 知识库名称冲突、参数错误等 → 明确的 4xx 而不是 500 (BUG-11)
                    Integer sc = e.getStatusCode();
                    HttpStatus status = sc != null
                            ? HttpStatus.valueOf(sc)
                            : HttpStatus.BAD_REQUEST;
                    return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of("message", e.getMessage() == null ? "请求失败" : e.getMessage()));
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    // DB 层 NOT NULL / UNIQUE 约束 → 400 而不是 500
                    return ServerResponse.badRequest().contentType(MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of("message", "数据约束失败: " + e.getMostSpecificCause().getMessage()));
                }
            });
            builder.PATCH("/spring/ai/loom/knowledge/{knowledgeId}", request -> {
                String knowledgeId = request.pathVariable("knowledgeId");
                // 使用 Map 接收部分更新数据，避免 Java record 要求所有字段都存在的问题
                java.util.Map<String, Object> body = request.body(java.util.Map.class);
                if (body == null || !body.containsKey("name") || body.get("name") == null) {
                    return ServerResponse.badRequest().contentType(MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of("message", "知识库名称(name)不能为空"));
                }
                String name = String.valueOf(body.get("name"));
                if (name.isBlank()) {
                    return ServerResponse.badRequest().contentType(MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of("message", "知识库名称(name)不能为空"));
                }
                String description = body.containsKey("description") && body.get("description") != null
                        ? String.valueOf(body.get("description")) : null;
                try {
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                            .body(knowledge.update(knowledgeId, name, description));
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException e) {
                    Integer sc = e.getStatusCode();
                    HttpStatus status = sc != null ? HttpStatus.valueOf(sc) : HttpStatus.BAD_REQUEST;
                    return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of("message", e.getMessage() == null ? "请求失败" : e.getMessage()));
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    return ServerResponse.badRequest().contentType(MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of("message", "数据约束失败: " + e.getMostSpecificCause().getMessage()));
                }
            });
            builder.GET("/spring/ai/loom/knowledge/{knowledgeId}/can-edit", request -> {
                String knowledgeId = request.pathVariable("knowledgeId");
                boolean canEdit = knowledge.canEdit(knowledgeId);
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(Map.of("canEdit", canEdit));
            });
            builder.DELETE("/spring/ai/loom/knowledge/{knowledgeId}", request -> {
                String knowledgeId = request.pathVariable("knowledgeId");
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(upload.deleteAllKnowledge(knowledgeId));
            });
            builder.POST("/spring/ai/loom/knowledge/{knowledgeId}/upload", request -> {
                // 与 /file/upload 同理：缺 multipart header / body 走 400 而不是 500
                String fileErrMsg = "上传的文件不能为空，请检查请求参数中是否包含名为'file'的文件";
                Part part;
                try {
                    part = request.multipartData().getFirst("file");
                } catch (Exception e) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", fileErrMsg));
                }
                if (part == null) {
                    return ServerResponse.badRequest().body(java.util.Map.of("error", fileErrMsg));
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
                // upload.delete 内部调 file.getById，row 不存在抛 EmptyResultDataAccessException → 404
                try {
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(upload.delete(fileId));
                } catch (org.springframework.dao.EmptyResultDataAccessException e) {
                    return ServerResponse.notFound().build();
                } catch (org.springframework.dao.IncorrectResultSizeDataAccessException e) {
                    return ServerResponse.notFound().build();
                }
            });
            return builder.build();
        }

        /**
         * 知识库市场路由：提供市场浏览、提交、审批、订阅、撤回等功能。
         * 依赖 IKnowledgeMarketService 和 IKnowledgeRoleAdmin。
         */
        @Bean("loomAgentKnowledgeMarketRouter")
        public RouterFunction<ServerResponse> loomAgentKnowledgeMarketRouter(
                cn.wubo.spring.ai.loom.agent.knowledge.IKnowledgeMarketService marketService,
                cn.wubo.spring.ai.loom.agent.knowledge.IKnowledgeRoleAdmin roleAdmin,
                IKnowledge knowledge) {
            RouterFunctions.Builder builder = RouterFunctions.route();

            // 获取用户可访问的知识库列表（自己的 + 订阅的 + 角色授予的）
            builder.GET("/spring/ai/loom/api/knowledge/accessible", request -> {
                String username = UserContextHolder.getCurrentUser();
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                        .body(knowledge.listAccessible(username));
            });

            // 获取市场已审批的知识库列表（分页）
            builder.GET("/spring/ai/loom/api/knowledge-market", request -> {
                String pageParam = request.param("page").orElse("1");
                String sizeParam = request.param("size").orElse("20");
                int page = Integer.parseInt(pageParam);
                int size = Integer.parseInt(sizeParam);
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                        .body(marketService.listApproved(page, size));
            });

            // 从市场订阅知识库
            builder.POST("/spring/ai/loom/api/knowledge-market/{marketId}/pull", request -> {
                String marketId = request.pathVariable("marketId");
                String username = UserContextHolder.getCurrentUser();
                try {
                    marketService.pull(username, marketId);
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of("success", true));
                } catch (Exception e) {
                    return ServerResponse.badRequest().contentType(MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of("message", e.getMessage()));
                }
            });

            // 提交知识库到市场
            builder.POST("/spring/ai/loom/api/knowledge/{knowledgeId}/submit", request -> {
                String knowledgeId = request.pathVariable("knowledgeId");
                try {
                    var result = marketService.submit(knowledgeId);
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(result);
                } catch (Exception e) {
                    return ServerResponse.badRequest().contentType(MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of("message", e.getMessage()));
                }
            });

            // 撤回/删除我的市场提交（统一 DELETE 端点；内部判断作者或 admin）
            builder.DELETE("/spring/ai/loom/api/knowledge-market/{marketId}", request -> {
                String marketId = request.pathVariable("marketId");
                try {
                    marketService.withdraw(marketId);
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of("success", true));
                } catch (Exception e) {
                    return ServerResponse.badRequest().contentType(MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of("message", e.getMessage()));
                }
            });

            // 去掉 approve/reject 端点（无审批流）
            // _removed_ /api/knowledge-market/{marketId}/approve
            // _removed_ /api/knowledge-market/{marketId}/reject

            // 获取我订阅的知识库列表
            builder.GET("/spring/ai/loom/api/knowledge-market/my-pulled", request -> {
                String username = UserContextHolder.getCurrentUser();
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                        .body(marketService.listMyPulled(username));
            });

            // 获取我提交到市场的知识库列表
            builder.GET("/spring/ai/loom/api/knowledge-market/my-submitted", request -> {
                String username = UserContextHolder.getCurrentUser();
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                        .body(marketService.listMySubmitted(username));
            });

            return builder.build();
        }

        /**
         * 文件下载/预览路由：为知识库文件提供 attachment 下载和 inline 预览端点。
         * 通过 IFileDownload 透明处理数据库存储和磁盘存储两种模式。
         */
        @Bean("loomAgentKnowledgeMarketAdminRouter")
        public RouterFunction<ServerResponse> loomAgentKnowledgeMarketAdminRouter(
                cn.wubo.spring.ai.loom.agent.knowledge.IKnowledgeMarketService marketService,
                IUser user) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            // admin：列出所有市场知识库
            builder.GET("spring/ai/loom/admin/market-knowledge", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                return ServerResponse.ok().body(marketService.listAllForAdmin());
            });
            // admin：删除市场知识库（级联清理 user_knowledge + role_knowledge）
            builder.DELETE("spring/ai/loom/admin/market-knowledge/{marketId}", request -> {
                String username = UserContextHolder.getCurrentUser();
                if (!user.isAdmin(username))
                    return ServerResponse.status(403).body(java.util.Map.of("error", "无权限"));
                String marketId = request.pathVariable("marketId");
                try {
                    marketService.withdraw(marketId);
                    return ServerResponse.ok().body(true);
                } catch (cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex) {
                    int code = ex.getStatusCode() != null ? ex.getStatusCode() : 400;
                    return ServerResponse.status(code).body(java.util.Map.of("error", ex.getMessage()));
                }
            });
            return builder.build();
        }

        @Bean("loomAgentFileDownloadRouter")
        public RouterFunction<ServerResponse> loomAgentFileDownloadRouter(
                cn.wubo.spring.ai.loom.agent.file.IFileDownload fileDownload) {
            return RouterFunctions.route()
                    .GET("/spring/ai/loom/api/file/{fileId}/download", request -> {
                        String fileId = request.pathVariable("fileId");
                        String username = UserContextHolder.getCurrentUser();
                        try {
                            FileRecord record = fileDownload.getFileRecord(fileId, username);
                            byte[] content = fileDownload.readFileContent(fileId, username);
                            MediaType contentType = resolveContentType(record);
                            ByteArrayResource resource = new ByteArrayResource(content) {
                                @Override
                                public String getFilename() {
                                    return record.fileName();
                                }
                            };
                            return ServerResponse.ok()
                                    .header("Content-Disposition", buildContentDisposition(record.fileName()))
                                    .contentType(contentType)
                                    .body(resource);
                        } catch (IllegalArgumentException e) {
                            return ServerResponse.notFound().build();
                        }
                    })
                    .GET("/spring/ai/loom/api/file/{fileId}/preview", request -> {
                        String fileId = request.pathVariable("fileId");
                        String username = UserContextHolder.getCurrentUser();
                        try {
                            FileRecord record = fileDownload.getFileRecord(fileId, username);
                            byte[] content = fileDownload.readFileContent(fileId, username);
                            MediaType contentType = resolveContentType(record);
                            ByteArrayResource resource = new ByteArrayResource(content) {
                                @Override
                                public String getFilename() {
                                    return record.fileName();
                                }
                            };
                            return ServerResponse.ok()
                                    .contentType(contentType)
                                    .body(resource);
                        } catch (IllegalArgumentException e) {
                            return ServerResponse.notFound().build();
                        }
                    })
                    .build();
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
    }
}
