package cn.wubo.spring.ai.loom.agent.model;

public record MonthlyTokenStat(
        String username,
        long totalTokens,
        long promptTokens,
        long completionTokens,
        long callCount
) {
}
