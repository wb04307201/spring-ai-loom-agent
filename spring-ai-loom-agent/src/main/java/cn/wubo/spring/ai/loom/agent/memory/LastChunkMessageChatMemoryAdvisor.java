package cn.wubo.spring.ai.loom.agent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * /P10/P11/P12 自定义 Advisor：只在流式响应 <b>最后一个 chunk</b> 触发
 * {@code chatMemory.add()}，替代 Spring AI 1.1.7 的 {@code MessageChatMemoryAdvisor}。
 *
 * <p>Spring AI 默认实现的问题：{@code MessageChatMemoryAdvisor.adviseStream()} 在
 * <b>每个</b> ChatClientResponse chunk 触发 after() → chat_memory TOOL 消息多次写入。
 *
 * <p>本实现：在 Flux 上用 {@code .collectList().flatMapMany()} 缓存所有 chunk，
 * 流完成时取最后一个 chunk 触发 {@code chatMemory.add()}。chat_memory 表只写一次。
 *
 * <p>直接用 {@link JdbcTemplate} 写入 {@code SPRING_AI_CHAT_MEMORY} 表，
 * 避免依赖 Spring AI 内部 chatMemory.add()（因为我们 bean 替代了默认 advisor，
 * first-write 路径不可靠）。同时写 UserMessage + AssistantMessage，type 受 CHECK 约束
 * 限制为 USER/ASSISTANT/SYSTEM/TOOL 之一。
 */
@Slf4j
public class LastChunkMessageChatMemoryAdvisor implements BaseChatMemoryAdvisor {

    private final JdbcTemplate jdbcTemplate;
    private final int order;

    public LastChunkMessageChatMemoryAdvisor(JdbcTemplate jdbcTemplate, int order) {
        this.jdbcTemplate = jdbcTemplate;
        this.order = order;
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    @Override
    public String getConversationId(Map<String, Object> context) {
        Object id = context.get("ChatMemory.CONVERSATION_ID");
        if (id == null) {
            id = context.get("chat_memory_conversation_id");
        }
        return id == null ? "" : id.toString();
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, org.springframework.ai.chat.client.advisor.api.AdvisorChain advisorChain) {
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, org.springframework.ai.chat.client.advisor.api.AdvisorChain advisorChain) {
        doWriteMemory(response.context(), requestOf(response), response.chatResponse().getResult().getOutput());
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain streamAdvisorChain) {
        String conversationId = getConversationId(request.context());
        log.info("LastChunkAdvisor.adviseStream called for conv={}", conversationId);
        final StringBuilder assistantTextBuf = new StringBuilder();
        // 闭包持有最后一个 chunk，用于在 doOnComplete 时拿到 context() 写库
        // （单元素数组是 Java 里避免 final 限制的惯用法；线程安全见注释）
        final ChatClientResponse[] lastResponse = new ChatClientResponse[1];
        return streamAdvisorChain.nextStream(request)
                // 累积所有 chunk 的 assistant text（Spring AI 1.1.7 流式 chunk 中 getText() 是 partial）
                // doOnNext 是 no-buffering side-effect operator，每个 chunk 立即透传下游，
                // 不再像之前的 .collectList() 那样把整条流卡到内存里再批量 emit
                .doOnNext(resp -> {
                    // 三层 null 守卫：Spring AI Anthropic 流里的 message_start /
                    // content_block_start (tool_use) / content_block_stop / ping / message_delta
                    // 等事件会产生 ChatResponse 但 getResults() 为空（或 getResult()==null）。
                    // 不加守卫 → .getOutput() NPE → Flux 进入 onError → 上游
                    // ToolCallingAdvisor 被截断 → tool_use chunk 没被消费 → 工具没被调用。
                    ChatResponse cr = resp.chatResponse();
                    Generation gen = (cr == null) ? null : cr.getResult();
                    AssistantMessage am = (gen == null) ? null : gen.getOutput();
                    if (am != null) {
                        String t = am.getText();
                        if (t != null) assistantTextBuf.append(t);
                    }
                    lastResponse[0] = resp;
                })
                // M5 修 doFinally 副作用:doFinally 不区分 ON_COMPLETE / ON_ERROR / CANCEL
                // —— 上游 error 时它也会触发,导致 partial 文本被持久化(测试 failing)。
                // 用 reactor.core.publisher.Signal.isOnComplete() 区分:
                //   - ON_COMPLETE → 正常完成 → 写库(每个会话 1 行 USER + 1 行 ASSISTANT)
                //   - ON_ERROR     → 上游异常 → 不写(避免 partial 文本持久化)
                //   - CANCEL       → 用户/框架主动取消 → 不写
                .doFinally(signal -> {
                    if (signal != reactor.core.publisher.SignalType.ON_COMPLETE) {
                        log.debug("LastChunkAdvisor: stream terminated with {} for conv={}, skip write",
                                signal, conversationId);
                        return;
                    }
                    if (lastResponse[0] == null) {
                        log.debug("LastChunkAdvisor: empty stream, skip write for conv={}", conversationId);
                        return;
                    }
                    String fullText = assistantTextBuf.toString();
                    AssistantMessage fullAssistant = new AssistantMessage(fullText);
                    try {
                        doWriteMemory(lastResponse[0].context(), request, fullAssistant);
                        log.info("LastChunkAdvisor wrote memory once for conv={}, assistantTextLen={}",
                                conversationId, fullText.length());
                    } catch (Exception e) {
                        log.warn("LastChunkAdvisor write failed for conv={}: {}", conversationId, e.getMessage());
                    }
                });
    }

    /**
     * 非流式路径下 from response 推回 ChatClientRequest（不严格必要，但保持一致）
     */
    private ChatClientRequest requestOf(ChatClientResponse response) {
        return null;
    }

    private void doWriteMemory(Map<String, Object> context, ChatClientRequest request, AssistantMessage assistantMessage) {
        String conversationId = getConversationId(context);
        if (conversationId == null || conversationId.isBlank()) {
            log.debug("skip write: empty convId");
            return;
        }
        // 1. 写 UserMessage（如果 request 里有）
        int userCount = 0;
        if (request != null) {
            List<Message> instructions = request.prompt().getInstructions();
            for (Message m : instructions) {
                if (m instanceof UserMessage um) {
                    insertMessage(conversationId, "USER", um.getText());
                    userCount++;
                }
            }
        }
        log.info("doWriteMemory conv={} wrote {} USER messages", conversationId, userCount);
        // 2. 写 AssistantMessage
        String assistantText = assistantMessage.getText();
        insertMessage(conversationId, "ASSISTANT", assistantText);
    }

    private void insertMessage(String conversationId, String type, String content) {
        if (content == null || content.isBlank()) return;
        // type CHECK 约束: USER/ASSISTANT/SYSTEM/TOOL
        String normalized = type.toUpperCase();
        if (!"USER".equals(normalized) && !"ASSISTANT".equals(normalized) && !"SYSTEM".equals(normalized) && !"TOOL".equals(normalized)) {
            log.warn("unsupported message type={} skip", normalized);
            return;
        }
        try {
            jdbcTemplate.update(
                    "insert into spring_ai_chat_memory (conversation_id, content, type) values (?, ?, ?)",
                    conversationId, content, normalized);
        } catch (Exception e) {
            log.warn("insertMessage failed: conv={} type={} err={}", conversationId, normalized, e.getMessage());
        }
    }
}
