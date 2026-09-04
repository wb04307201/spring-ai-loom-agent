package cn.wubo.spring.ai.loom.agent.market;

public record MarketUpdateRequest(
    String name,            // for rename; null = no change
    String description,
    String content,
    String category,
    Boolean isOfficial,
    Integer featuredRank
) {}