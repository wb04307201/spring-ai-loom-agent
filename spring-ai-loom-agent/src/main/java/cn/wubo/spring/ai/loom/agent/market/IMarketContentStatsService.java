package cn.wubo.spring.ai.loom.agent.market;

public interface IMarketContentStatsService {
    void incrementStat(Long marketId, String kind);
    StatsRow getStats(Long marketId);
}
