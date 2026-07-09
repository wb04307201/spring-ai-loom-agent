package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.model.ChatRequestRecord;
import cn.wubo.spring.ai.loom.agent.model.UserConversationRecord;
import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.user.IUserConversation;
import cn.wubo.spring.ai.loom.agent.util.TikaUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DefaultChat implements IChat {

    private static final Logger log = LoggerFactory.getLogger(DefaultChat.class);

    private final ChatClient chatClient;
    private final Optional<RetrievalAugmentationAdvisor> retrievalAugmentationAdvisor;
    private final IMcp mcp;
    private final List<IEmbedTool> embedTools;
    private final IUserConversation userConversation;
    private final IFile file;
    private final org.springframework.core.env.Environment environment;

    public DefaultChat(ChatClient chatClient, Optional<RetrievalAugmentationAdvisor> retrievalAugmentationAdvisor, IMcp mcp, List<IEmbedTool> embedTools, IUserConversation userConversation, IFile file, org.springframework.core.env.Environment environment) {
        this.chatClient = chatClient;
        this.retrievalAugmentationAdvisor = retrievalAugmentationAdvisor;
        this.mcp = mcp;
        this.embedTools = embedTools;
        this.userConversation = userConversation;
        this.file = file;
        this.environment = environment;
    }

    @Override
    public Flux<ChatResponse> stream(ChatRequestRecord chatRequestRecord, String username, HttpServletRequest request) {
        log.info("Chat request: message={}, fileIds={}", chatRequestRecord.message(), chatRequestRecord.fileIds());
        // 前端可能在用户还没点过任何对话时直接发消息，
        // 此时 conversationId=null，会让 user_conversation 写入触发
        // H2 NOT NULL 约束并抛出 500。这里兜底生成一个会话 id。
        String requested = chatRequestRecord.conversationId();
        final String conversationId = (requested == null || requested.isBlank())
                ? java.util.UUID.randomUUID().toString()
                : requested;
        if (requested == null || requested.isBlank()) {
            log.debug("Auto-generated conversationId={} (client did not supply one)", conversationId);
        }
        boolean exists = userConversation.exists(new UserConversationRecord(username, conversationId));
        if (!exists) {
            userConversation.insert(new UserConversationRecord(username, conversationId));
        }

        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt();
        if (chatRequestRecord.fileIds() != null && !chatRequestRecord.fileIds().isEmpty()) {
            StringBuilder extraText = new StringBuilder();
            for (String fileId : chatRequestRecord.fileIds()) {
                var fileRecord = file.getById(fileId, username);
                if (fileRecord == null) continue;
                if (isDocument(fileRecord.mimeType())) {
                    if(extraText.isEmpty()){
                        extraText.append("以下是用户上传的文档的内容提取结果:");
                    }
                    try (InputStream in = file.getResourceById(fileId, username).getInputStream()) {
                        String content = TikaUtils.TIKA.parseToString(in);
                        extraText.append("\n\n--- ").append(fileRecord.fileName()).append(" ---\n\n").append(content);
                    } catch (IOException | TikaException e) {
                        log.error("Failed to parse document: {}", fileRecord.fileName(), e);
                        extraText.append("\n\n--- ").append(fileRecord.fileName()).append(" ---\n\n文件无法解析: ").append(e.getMessage());
                    }
                }
            }
            if (!extraText.isEmpty()){
                extraText.append("\n\n以上是文档内容提取结果，请根据文档内容进行回答。");
                requestSpec.system(extraText.toString());
            }

            requestSpec.user(u -> {
                u.text(chatRequestRecord.message());
                for (String fileId : chatRequestRecord.fileIds()) {
                    try {
                        var fileRecord = file.getById(fileId, username);
                        if (fileRecord == null) continue;
                        if (isImage(fileRecord.mimeType())) {
                            u.media(MimeTypeUtils.IMAGE_JPEG, file.getResourceById(fileId, username));
                        }
                    } catch (Exception e) {
                        log.error("Failed to add media", e);
                    }
                }
            }).tools(embedTools.toArray());
        }else{
            requestSpec.user(chatRequestRecord.message()).tools(embedTools.toArray());
        }
        Map<String, Object> props = new HashMap<>();
        props.put("username", username);
        String scheme = request.getScheme();         // http 或 https
        String serverName = request.getServerName(); // localhost 或 IP
        int serverPort = request.getServerPort();    // 8080
        props.put("baseUrl", scheme + "://" + serverName + ":" + serverPort);
        requestSpec.toolContext(props);

        requestSpec.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId));

        if (retrievalAugmentationAdvisor.isPresent() && StringUtils.hasText(chatRequestRecord.knowledgeId())) {
            requestSpec.advisors(retrievalAugmentationAdvisor.get());
            requestSpec.advisors(advisor -> advisor.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, "type == 'knowledge' && knowledgeId == '" + chatRequestRecord.knowledgeId() + "' && username == '" + username + "'"));
        }

        ToolCallbackProvider toolCallbackProvider = mcp.getToolCallbackProvider(chatRequestRecord.mcps());

        if (toolCallbackProvider != null) {
            requestSpec.toolCallbacks(toolCallbackProvider);
        }

        // Spring AI 2.0's OpenAiChatModel stream() goes through ChunkMerger.chunkToChatConversion
        // which only reads delta.content() and delta.refusal() — it drops delta._additionalProperties()
        // where Bailian (and other OpenAI-compat reasoning servers) put reasoning_content.
        // Result: the streaming ChatResponse always has empty reasoningContent, so the UI can't show
        // the model's thinking process.
        // Workaround: when reasoning is enabled (extra-body.enable_thinking=true), use the
        // non-streaming .call() path which preserves reasoningContent from the full message JSON,
        // then wrap the result in a Flux so the SSE controller contract is unchanged. Streaming UX
        // is lost for reasoning models but the user actually sees the thinking.
        if (isReasoningEnabled(requestSpec)) {
            return Flux.just(requestSpec.call().chatResponse());
        }

        return requestSpec.stream().chatResponse();
    }

    /**
     * Detects whether the upstream chat request is configured for reasoning content. Spring AI
     * 2.0's OpenAiChatModel stream() goes through ChunkMerger.chunkToChatConversion which drops
     * delta._additionalProperties() — Bailian's reasoning_content lives there and gets silently
     * lost. The non-streaming .call() path preserves it. We detect the reasoning flag from the
     * Spring environment instead of poking into ChatClientRequestSpec via reflection (the spec
     * field shape differs across Spring AI versions).
     */
    private boolean isReasoningEnabled(ChatClient.ChatClientRequestSpec requestSpec) {
        if (environment != null) {
            Boolean flag = environment.getProperty(
                    "spring.ai.openai.chat.extra-body.enable_thinking", Boolean.class);
            if (flag != null) {
                return flag;
            }
        }
        return false;
    }

    private boolean isImage(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        return mimeType.startsWith("image/");
    }

    private boolean isDocument(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        return mimeType.equals("application/pdf") ||
                mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document") ||
                mimeType.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation") ||
                mimeType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") ||
                mimeType.equals("text/markdown") ||
                mimeType.equals("text/plain") ||
                mimeType.equals("application/msword") ||
                mimeType.equals("application/vnd.ms-powerpoint") ||
                mimeType.equals("application/vnd.ms-excel") ||
                mimeType.equals("text/html") ||
                mimeType.equals("text/csv") ||
                mimeType.equals("text/xml") ||
                mimeType.equals("application/rtf") ||
                mimeType.equals("text/rtf");
    }
}
