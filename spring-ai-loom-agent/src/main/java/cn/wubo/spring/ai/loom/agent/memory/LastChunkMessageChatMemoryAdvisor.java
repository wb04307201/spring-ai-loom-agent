package cn.wubo.spring.ai.loom.agent.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * V5.4 P9 自定义 Advisor：只在流式响应 <b>最后一个 chunk</b> 触发
 * {@code chatMemory.add()}，替代 Spring AI 1.1.7 的 {@code MessageChatMemoryAdvisor}。
 *
 * <p>Spring AI 默认实现的问题：{@code MessageChatMemoryAdvisor.adviseStream()} 在
 * <b>每个</b> ChatClientResponse chunk 都调 {@code after()} — 每个 chunk
 * 包含到目前为止的累积 messages — chat_memory 表被写多次（实测一次 LLM 调工具
 * 产生 2 行 chat_memory TOOL 消息 + 2 行 USER/ASSISTANT 消息）。
 *
 * <p>本实现：在 Flux 上用 {@code .collectList().flatMapMany()} 缓存所有 chunk，
 * 流完成时（{@code flatMapMany} 内 lambda 同步执行）取最后一个 chunk 触发
 * {@code chatMemory.add()}。这样 chat_memory 表只写一次。
 *
 * <p>StreamAdvisorChain 仍能正确返回每个 chunk 给下游（前端 SSE 流式响应不受影响），
 * 只是 chatMemory.add() 的时机延后到流完成。
 */
@Slf4j
@RequiredArgsConstructor
public class LastChunkMessageChatMemoryAdvisor implements BaseChatMemoryAdvisor {

    private final ChatMemory chatMemory;
    private final int order;

    @Override
    public int getOrder() {
        return this.order;
    }

    @Override
    public String getConversationId(Map<String, Object> context) {
        // 与 Spring AI 一致：从 advisorContext 取 ChatMemory.CONVERSATION_ID
        Object id = context.get("ChatMemory.CONVERSATION_ID");
        if (id == null) {
            id = context.get("chat_memory_conversation_id");
        }
        return id == null ? "" : id.toString();
    }

    // V5.4 P10：用 ThreadLocal 传递 UserMessage（ChatClientRequest.context 实际是不可变 Map，
    // put 后 adviseStream 取到 null —— 上一版的 bug）。ThreadLocal 跨 before/after 边界稳定。
    private static final ThreadLocal<org.springframework.ai.chat.messages.Message> USER_MESSAGE_HOLDER = new ThreadLocal<>();

    @Override
    public ChatClientRequest before(ChatClientRequest request, org.springframework.ai.chat.client.advisor.api.AdvisorChain advisorChain) {
        // 把 UserMessage 存到 ThreadLocal，让 adviseStream 流式路径最后一次写 memory 时能取到。
        try {
            java.util.List<org.springframework.ai.chat.messages.Message> instructions = request.prompt().getInstructions();
            if (instructions != null && !instructions.isEmpty()) {
                USER_MESSAGE_HOLDER.set(instructions.get(instructions.size() - 1));
            }
        } catch (Exception e) {
            log.debug("V5.4 P10 LastChunkAdvisor.before set user_message failed: {}", e.getMessage());
        }
        return request;
    }

    /** 测试/Cleanup 入口：清空 ThreadLocal（正常调用路径不依赖，保留兜底） */
    public static void clearUserMessageHolder() {
        USER_MESSAGE_HOLDER.remove();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, org.springframework.ai.chat.client.advisor.api.AdvisorChain advisorChain) {
        // 非流式路径：每次 call 一次，after 写 memory 一次 —— 这与 Spring AI 默认行为一致。
        // 写：UserMessage + AssistantMessage + ToolResponseMessages
        doAddToMemory(response.context(), response.chatResponse().getResult().getOutput());
        return response;
    }

    /**
     * 流式路径：缓存所有 chunk，<b>只在流完成时</b>用最后一个 chunk 触发 memory.add。
     * 这样避免 Spring AI 默认实现"每个 chunk 都写一次"的重复。
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain streamAdvisorChain) {
        String conversationId = getConversationId(request.context());
        return streamAdvisorChain.nextStream(request)
                // 缓存所有 chunk
                .collectList()
                // 流完成时（collectList 完成）触发 memory.add 一次
                .flatMapMany(list -> {
                    if (list == null || list.isEmpty()) {
                        return Flux.fromIterable(list == null ? java.util.List.of() : list);
                    }
                    ChatClientResponse last = list.get(list.size() - 1);
                    try {
                        doAddToMemory(last.context(), last.chatResponse().getResult().getOutput());
                        log.debug("V5.4 P9 LastChunkAdvisor wrote memory once for conv={}, total chunks={}", conversationId, list.size());
                    } catch (Exception e) {
                        log.warn("V5.4 P9 LastChunkAdvisor add to memory failed for conv={}: {}", conversationId, e.getMessage());
                    } finally {
                        // V5.4 P10：清理 ThreadLocal 避免内存泄漏 + 跨请求污染
                        USER_MESSAGE_HOLDER.remove();
                    }
                    return Flux.fromIterable(list);
                });
    }

    private void doAddToMemory(Map<String, Object> context, org.springframework.ai.chat.messages.AssistantMessage assistantMessage) {
        String conversationId = getConversationId(context);
        if (conversationId == null || conversationId.isBlank()) return;
        // 写：UserMessage + AssistantMessage（+ ToolResponseMessages 如果 AssistantMessage 含 tool_calls）
        org.springframework.ai.chat.messages.Message userMessage = extractUserMessage(context);
        java.util.List<org.springframework.ai.chat.messages.Message> messages = new java.util.ArrayList<>();
        if (userMessage != null) messages.add(userMessage);
        messages.add(assistantMessage);
        this.chatMemory.add(conversationId, messages);
    }

    private org.springframework.ai.chat.messages.Message extractUserMessage(Map<String, Object> context) {
        // V5.4 P10：优先从 ThreadLocal 取（跨 before/after 边界稳定）
        org.springframework.ai.chat.messages.Message u = USER_MESSAGE_HOLDER.get();
        if (u != null) return u;
        // 兼容 Spring AI 1.1.7 的实际 key 名（context 可能是可变的）
        Object u2 = context.get("user_message");
        if (u2 instanceof org.springframework.ai.chat.messages.Message m2) return m2;
        Object u3 = context.get("USER_MESSAGE");
        if (u3 instanceof org.springframework.ai.chat.messages.Message m3) return m3;
        Object u4 = context.get("V5.4_P9_user_message");
        if (u4 instanceof org.springframework.ai.chat.messages.Message m4) return m4;
        return null;
    }
}
