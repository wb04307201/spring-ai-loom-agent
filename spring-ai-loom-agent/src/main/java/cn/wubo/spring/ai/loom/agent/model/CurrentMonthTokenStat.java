package cn.wubo.spring.ai.loom.agent.model;

public record CurrentMonthTokenStat(
        String username,
        long totalTokens,
        long promptTokens,
        long completionTokens,
        long callCount,
        double avgTokensPerCall
) {
}
