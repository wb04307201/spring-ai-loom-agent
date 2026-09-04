package cn.wubo.spring.ai.loom.agent.market;

public record MarketCreateRequest(
    String name,
    String description,
    String content,
    String category
) {}