package cn.wubo.spring.ai.loom.agent.token;

import java.time.Instant;

/**
 * 写入 {@code loom_chat_token_usage} 的中间 record。
 * 当前 ChatUsageService.record() 仍直接吃 4 个字段（conversationId / username /
 * prompt / completion / total），这里保留一个 record 形态以备未来暴露 API。
 */
public record TokenUsageRecord(
        String conversationId,
        String username,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        Instant createdAt
) {
    public TokenUsageRecord(String conversationId, String username, long prompt, long completion, long total) {
        this(conversationId, username, prompt, completion, total, Instant.now());
    }
}
