package cn.wubo.spring.ai.loom.agent.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

/**
 * 在流终止时捕获 token 用量并落库。
 *
 * <p><b>为什么需要 advisor 而不是在 SseController 里读</b>：
 * SseController 订阅的是 {@code ChatClient.stream().chatResponse()} 返回的**原始 chunk 流**，
 * 而 OpenAI 兼容端点的 usage **只在流终止后的聚合响应里**才有值。
 * 实测证据（每帧探针）：4 个 chunk 帧的 {@code getMetadata().getUsage()} 全是
 * {@code DefaultUsage(0,0,0)}，而 {@code SimpleLoggerAdvisor} 在同一请求的
 * **286ms 后**拿到了 {@code promptTokens=6560}。
 *
 * <p>Spring AI 的 {@link ChatClientMessageAggregator#aggregateChatClientResponse}
 * 正是"聚合后在回调里给出完整响应"的机制 —— {@code SimpleLoggerAdvisor} 用的就是它。
 * 本类照此实现，只是把"打日志"换成"落库"。
 *
 * <p><b>为什么不用 {@code getUsage() != null} 判断</b>：
 * 空 metadata 的 {@code getUsage()} 返回的是 {@code EmptyUsage}/{@code DefaultUsage(0,0,0)}
 * 而**不是 null** —— 用 null 判断会通过，但值全 0。故此处按**值**判断。
 */
public class ChatUsageRecordingAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ChatUsageRecordingAdvisor.class);

    /** 落库前的最小有效 token 数：三项全 0 视为"上游未提供 usage"，跳过。 */
    private static final long MIN_MEANINGFUL_TOKENS = 1L;

    private final ChatUsageService chatUsageService;
    private final String conversationId;
    private final String username;
    private final int order;

    public ChatUsageRecordingAdvisor(ChatUsageService chatUsageService,
                                     String conversationId,
                                     String username,
                                     int order) {
        this.chatUsageService = chatUsageService;
        this.conversationId = conversationId;
        this.username = username;
        this.order = order;
    }

    @Override
    public String getName() {
        return "ChatUsageRecordingAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    /** 非流式调用：响应直接带 usage。 */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        record(response);
        return response;
    }

    /**
     * 流式调用：聚合后回调里捕获 usage。
     *
     * <p>返回的 Flux 仍是**原始 chunk 流**（未改变对上游的语义）——
     * 聚合器只旁路观察，不替换元素。
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Flux<ChatClientResponse> stream = chain.nextStream(request);
        Consumer<ChatClientResponse> onAggregated = this::record;
        return new ChatClientMessageAggregator().aggregateChatClientResponse(stream, onAggregated);
    }

    private void record(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) return;
        Usage u = response.chatResponse().getMetadata() == null
                ? null
                : response.chatResponse().getMetadata().getUsage();
        if (u == null) return;
        long p = u.getPromptTokens() == null ? 0 : u.getPromptTokens();
        long c = u.getCompletionTokens() == null ? 0 : u.getCompletionTokens();
        long t = u.getTotalTokens() == null ? 0 : u.getTotalTokens();
        // 按值判断而非 null 判断：空 metadata 返回的是 DefaultUsage(0,0,0) 而非 null
        if (p < MIN_MEANINGFUL_TOKENS && c < MIN_MEANINGFUL_TOKENS && t < MIN_MEANINGFUL_TOKENS) {
            return;
        }
        try {
            chatUsageService.record(conversationId, username, p, c, t);
            log.debug("token 用量已记录: conv={} user={} p={} c={} t={}",
                    conversationId, username, p, c, t);
        } catch (Exception e) {
            // 落库失败不影响对话主流程（与 attachThinking 的降级策略一致）
            log.warn("token 用量落库失败: conv={} err={}: {}",
                    conversationId, e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
