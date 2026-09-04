package cn.wubo.spring.ai.loom.agent.market;

public enum MarketContentStatus {
    PENDING, APPROVED, REJECTED;

    public static MarketContentStatus from(String s) {
        if (s == null || s.isBlank()) return null;
        return MarketContentStatus.valueOf(s.trim().toUpperCase());
    }
}