package cn.wubo.spring.ai.loom.agent.market;

import java.time.LocalDateTime;

public record StatsRow(Long marketId, long pullCountOrSearchCount, LocalDateTime lastAt) {}
