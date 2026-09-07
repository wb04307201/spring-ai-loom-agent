package cn.wubo.spring.ai.loom.agent.askuser;

import cn.wubo.spring.ai.loom.agent.model.AskUserEvent;
import cn.wubo.spring.ai.loom.agent.model.AskUserOption;
import cn.wubo.spring.ai.loom.agent.model.ChatResponseRecord;
import cn.wubo.spring.ai.loom.agent.stream.SseEmitterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 默认实现:推卡片 → future.get(timeout) 阻塞 → 返回答案文本(spec §4 数据流)。
 * <p>
 * 所有失败路径都 return 文本、不抛异常 —— 保住 Flux ON_COMPLETE 让
 * LastChunkMessageChatMemoryAdvisor 落库(ChatMemory 硬约束,spec §0 目标 3)。
 * 阻塞发生在 Spring AI 同步 tool 执行线程(subtask 的 future.get() 同模式先例);
 * D9 应急预案(复验卡顿才启用)见 plan Global Constraints。
 */
public class DefaultAskUserTool implements IAskUserTool {

    private static final Logger log = LoggerFactory.getLogger(DefaultAskUserTool.class);

    private final AskUserRegistry askUserRegistry;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final long timeoutSeconds;

    public DefaultAskUserTool(AskUserRegistry askUserRegistry,
                              SseEmitterRegistry sseEmitterRegistry,
                              long timeoutSeconds) {
        this.askUserRegistry = askUserRegistry;
        this.sseEmitterRegistry = sseEmitterRegistry;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 与 DefaultSubTaskTool.readContextString 同款(L34-38)。 */
    private static String readContextString(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) return "";
        Object value = toolContext.getContext().get(key);
        return (value instanceof String s) ? s : "";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static List<AskUserOption> parseOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) return List.of();
        // Spring AI 的共享 ObjectMapper:InfrastructureConfiguration 已开
        // ALLOW_COMMENTS / ALLOW_SINGLE_QUOTES(qwen tool-args 容错先例)
        ObjectMapper om = org.springframework.ai.util.json.JsonParser.getObjectMapper();
        try {
            return om.readValue(optionsJson,
                    om.getTypeFactory().constructCollectionType(List.class, AskUserOption.class));
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    @Tool(description = "当需要用户在若干明确选项中做出选择、确认或澄清时,向当前用户提出一个问题。"
            + "问题会以选项卡片形式出现在聊天窗口,用户点选后你会收到答案并继续当前任务。"
            + "等待期间当前回复会暂停,这是正常的。仅在确实需要用户决策时使用;"
            + "能自行合理决定的不要问。一次只问一个问题,需要多个答案时分多次调用。")
    @Override
    public String askUser(String question, String header, String background,
                          String optionsJson, Boolean multiSelect, Boolean allowCustomInput,
                          ToolContext toolContext) {
        String username = readContextString(toolContext, "username");
        String conversationId = readContextString(toolContext, "parentConversationId");
        if (username.isEmpty() || conversationId.isEmpty()) {
            log.warn("askUser 缺少会话上下文,拒绝提问: username='{}' conv='{}'", username, conversationId);
            return "[提问失败] 当前调用缺少用户会话上下文,无法向用户提问。";
        }
        if (question == null || question.isBlank()) {
            return "[提问失败] question 不能为空,请提供要问用户的问题文本后重试。";
        }

        List<AskUserOption> options;
        try {
            options = parseOptions(optionsJson);
        } catch (IllegalArgumentException e) {
            return "[提问失败] optionsJson 解析失败: " + e.getMessage()
                    + "。请提供形如 [{\"label\":\"选项A\",\"description\":\"说明\"},{\"label\":\"选项B\"}] 的 JSON 数组后重试。";
        }
        if (options.size() < 2 || options.size() > 4) {
            return "[提问失败] options 数量必须在 2-4 个之间,当前 " + options.size() + " 个。请调整后重试。";
        }
        for (AskUserOption o : options) {
            if (o == null || o.label() == null || o.label().isBlank()) {
                return "[提问失败] 每个选项必须有非空 label。请修正 optionsJson 后重试。";
            }
        }

        SseEmitterRegistry.Entry entry = sseEmitterRegistry.get(username, conversationId);
        if (entry == null || entry.emitter() == null) {
            log.warn("askUser 无活跃流: user={} conv={}", username, conversationId);
            return "[提问失败] 无法向用户提问(会话流不可用)。";
        }

        String questionId = UUID.randomUUID().toString();
        AskUserEvent event = new AskUserEvent(questionId, question.trim(),
                blankToNull(header), blankToNull(background), options,
                Boolean.TRUE.equals(multiSelect), Boolean.TRUE.equals(allowCustomInput),
                timeoutSeconds);
        CompletableFuture<String> future = new CompletableFuture<>();
        askUserRegistry.register(new AskUserRegistry.PendingQuestion(
                event, username, conversationId, future, System.currentTimeMillis()));

        try {
            // ResponseBodyEmitter.send 内部 synchronized,与 SseController 内容帧并发安全
            entry.emitter().send(new ChatResponseRecord(null, null, event), MediaType.APPLICATION_JSON);
        } catch (Exception e) {
            askUserRegistry.remove(questionId);
            log.warn("askUser 卡片推送失败: user={} conv={} err={}", username, conversationId, e.getMessage());
            return "[提问失败] 无法向用户提问(会话流不可用)。";
        }
        log.info("askUser 提问: qid={} user={} conv={} question={}", questionId, username, conversationId, question);

        String answer;
        try {
            answer = future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            long minutes = Math.max(1, timeoutSeconds / 60);
            return "[用户未作答] 用户未在 " + minutes + " 分钟内作答。"
                    + "请基于现有信息自行合理决策并继续,或改用其他方式推进。";
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "[提问被中断] 等待用户作答时被中断,未获得答案。";
        } catch (ExecutionException ee) {
            log.warn("askUser 等待异常: qid={} err={}", questionId, ee.getMessage());
            return "[提问失败] 等待用户作答时发生异常: " + ee.getMessage();
        } finally {
            // 覆盖正常/超时/中断/异常全部路径:注册表不留幽灵行
            askUserRegistry.remove(questionId);
        }

        if (AskUserRegistry.CANCELLED_SENTINEL.equals(answer)) {
            return "[用户未作答] 用户已停止本次对话,未作答。";
        }
        return "[用户已回答] " + answer;
    }
}
