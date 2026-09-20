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
import org.springframework.ai.tool.annotation.ToolParam;
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
        if (s == null) return null;
        String t = s.trim();
        // Tolerate LLMs (esp. qwen) that pass the literal string "null" for a
        // nullable @ToolParam described as "可传 null" — normalize to absent so
        // the card never renders a stray "null" header/background line.
        if (t.isEmpty() || t.equalsIgnoreCase("null")) return null;
        return t;
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

    // 描述措辞契约(2026-09-20 触发率优化,AskUserGuidanceContractTest 锁定):
    // 旧版"仅在确实需要用户决策时使用;能自行合理决定的不要问"是抑制型措辞,
    // 实测模型几乎不主动提问(研究: LLM 能感知歧义但很少行动,基线澄清率仅 ~5%)。
    // 新版对齐 Claude Code AskUserQuestion 机制: 正向场景枚举 + 澄清模式循环协议
    // + 推荐项放首位的选项约定;防打扰条款弱化为收尾一句。
    @Tool(description = "在聊天窗口弹出一张选项卡片向当前用户提问,用户点选后你会收到答案并继续任务。"
            + "以下情形优先提问而不是猜测:(1)请求含糊或缺少关键信息(目标、范围、技术选型、风格、部署方式等),"
            + "不同假设会导致明显不同的结果;(2)存在多个可行方案,选择会影响用户后续工作;"
            + "(3)用户要求'澄清''一问一答''先确认再做'——此时必须进入澄清模式:每次只问一个问题,"
            + "收到答案后重新评估是否还有疑问,持续调用本工具逐轮提问,直到没有疑问才开始执行;一次任务中可以多次调用。"
            + "等待期间当前回复会暂停,这是正常的。选项给 2-4 个,把你推荐的放第一位并在 label 后标注'(推荐)'。"
            + "仅对无法自行合理决定的事项提问,常规细节不要打扰用户。")
    @Override
    public String askUser(@ToolParam(description = "要问用户的问题文本,一句话,清晰具体") String question,
                          @ToolParam(description = "问题的短标题(2-6 字,如'部署方式'),可传 null") String header,
                          @ToolParam(description = "为什么问这个问题的背景说明(1-2 句),可传 null") String background,
                          @ToolParam(description = "选项 JSON 数组,2-4 个,推荐项放第一位并在 label 后标注'(推荐)',形如 [{\"label\":\"选项A(推荐)\",\"description\":\"补充说明\"},{\"label\":\"选项B\"}]") String optionsJson,
                          @ToolParam(description = "是否允许多选,默认 false") Boolean multiSelect,
                          @ToolParam(description = "是否允许用户自由输入自定义答案,默认 false") Boolean allowCustomInput,
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
