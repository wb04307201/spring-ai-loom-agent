package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge;
import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.model.ChatRequestRecord;
import cn.wubo.spring.ai.loom.agent.model.KnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.model.SkillRecord;
import cn.wubo.spring.ai.loom.agent.model.UserConversationRecord;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
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
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DefaultChat implements IChat {

    private static final Logger log = LoggerFactory.getLogger(DefaultChat.class);

    private final ChatClient chatClient;
    private final IMcp mcp;
    private final List<IEmbedTool> embedTools;
    private final IUserConversation userConversation;
    private final IFile file;
    private final ISkillStorage skillStorage;
    private final IKnowledge knowledge;
    private final LoomAgentProperties properties;

    public DefaultChat(ChatClient chatClient, IMcp mcp, List<IEmbedTool> embedTools,
                       IUserConversation userConversation, IFile file,
                       ISkillStorage skillStorage, IKnowledge knowledge,
                       LoomAgentProperties properties) {
        this.chatClient = chatClient;
        this.mcp = mcp;
        this.embedTools = embedTools;
        this.userConversation = userConversation;
        this.file = file;
        this.skillStorage = skillStorage;
        this.knowledge = knowledge;
        this.properties = properties;
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

        String dynamicSystemPrompt = buildDynamicSystemPrompt(username, chatRequestRecord.enabledKnowledgeIds());

        // Prepare document content if files are attached (appended to dynamic system prompt)
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
                dynamicSystemPrompt = dynamicSystemPrompt + "\n\n" + extraText;
            }
        }

        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt().system(dynamicSystemPrompt);

        if (chatRequestRecord.fileIds() != null && !chatRequestRecord.fileIds().isEmpty()) {
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
        // 注入会话 id,让 sub-task / schedule 这类工具知道这次调用属于哪个会话
        // (用于 ChatMemory 命名空间 + 删除历史时的清理)。ISubTaskTool/IScheduleTool
        // 通过 toolContext.getContext().get("parentConversationId") 读取。
        props.put("parentConversationId", conversationId);
        // 注入用户选中的知识库 ID 列表，让 IKnowledgeTool 可以过滤
        // toolContext 不允许 null 值（Spring AI 断言），前端未选知识库时发 null —— 缺省即“不过滤”，与工具侧读取语义一致
        if (chatRequestRecord.enabledKnowledgeIds() != null) {
            props.put("enabledKnowledgeIds", chatRequestRecord.enabledKnowledgeIds());
        }
        String scheme = request.getScheme();         // http 或 https
        String serverName = request.getServerName(); // localhost 或 IP
        int serverPort = request.getServerPort();    // 8080
        props.put("baseUrl", scheme + "://" + serverName + ":" + serverPort);
        requestSpec.toolContext(props);

        requestSpec.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId));

        ToolCallbackProvider toolCallbackProvider = mcp.getVisibleToolCallbackProvider(username, chatRequestRecord.mcps());

        if (toolCallbackProvider != null) {
            requestSpec.toolCallbacks(toolCallbackProvider);
        }

        return requestSpec.stream().chatResponse()
                .onErrorResume(err -> reactor.core.publisher.Flux.just(toErrorResponse(err)));
    }

    /**
     * 把上游 AI 调用失败映射为单条 ChatResponse，让前端以「助手气泡」展示可读错误，
     * 避免 SseController 直接 completeWithError 后浏览器只看到模糊的 HTTP 500。
     */
    private org.springframework.ai.chat.model.ChatResponse toErrorResponse(Throwable err) {
        String msg = friendlyMessage(err);
        log.warn("聊天上游失败，转为可读错误返回前端：{}", msg, err);
        org.springframework.ai.chat.model.Generation gen =
                new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(msg));
        return new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(gen));
    }

    private String friendlyMessage(Throwable err) {
        Throwable t = err;
        while (t != null) {
            String name = t.getClass().getSimpleName();
            String message = t.getMessage();
            if (message != null && (message.contains("Arrearage") || message.contains("账户欠费") || message.contains("overdue"))) {
                return "模型服务暂不可用（账户欠费），请联系管理员充值后重试。";
            }
            if (name.contains("NonTransientAiException") && message != null && (message.contains("400") || message.contains("Bad Request"))) {
                return "上游模型服务拒绝了请求（" + abbr(message) + "），请调整消息后重试。";
            }
            if (name.contains("WebClientRequestException") || message != null && message.contains("Connection reset")) {
                return "网络异常连接被重置，请稍后重试。";
            }
            if (name.contains("ResourceAccessException") || (message != null && message.contains("Connection refused"))) {
                return "无法连接模型服务，请检查网络或稍后重试。";
            }
            t = t.getCause();
        }
        String name = err.getClass().getSimpleName();
        return "聊天服务异常（" + (name != null && !name.isBlank() ? name : "Unknown") + "），请稍后重试。";
    }

    private String abbr(String s) {
        if (s == null) return "";
        String oneLine = s.replace('\n', ' ').trim();
        return oneLine.length() > 120 ? oneLine.substring(0, 120) + "…" : oneLine;
    }

    private String buildDynamicSystemPrompt(String username, List<String> enabledKnowledgeIds) {
        StringBuilder sb = new StringBuilder();

        // Base system prompt from properties
        sb.append(properties.getDefaultSystem()).append("\n\n");

        // Inject skill summary (first 20)
        List<SkillRecord> allSkills = skillStorage.list(username);
        List<SkillRecord> skills = allSkills.stream()
                .filter(SkillRecord::load)
                .limit(20)
                .toList();

        if (!skills.isEmpty()) {
            sb.append("【技能】（共 ").append(allSkills.size()).append(" 个");
            if (skills.size() < allSkills.size()) {
                sb.append("，显示前 ").append(skills.size()).append(" 个");
            }
            sb.append("）\n");

            for (SkillRecord skill : skills) {
                sb.append("• ").append(skill.name()).append(" - ").append(skill.description()).append("\n");
            }
            sb.append("\n");
        }

        // Inject knowledge base summary - only show user-selected KBs with their IDs
        if (enabledKnowledgeIds != null && !enabledKnowledgeIds.isEmpty()) {
            List<KnowledgeRecord> userKbs = knowledge.list(username);
            List<KnowledgeRecord> enabledKbs = userKbs.stream()
                    .filter(kb -> enabledKnowledgeIds.contains(kb.id()))
                    .toList();
            if (!enabledKbs.isEmpty()) {
                sb.append("【知识库】（用户已启用 ").append(enabledKbs.size()).append(" 个）\n");
                enabledKbs.forEach(kb ->
                        sb.append("• ID=").append(kb.id())
                                .append(", 名称=").append(kb.name())
                                .append(", 描述=").append(kb.description()).append("\n"));
                sb.append("\n当用户问题涉及以上知识库内容时，请调用 @searchKnowledge 检索相关信息（knowledgeId 使用以上列出的 ID）。\n\n");
            }
        }

        return sb.toString();
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
