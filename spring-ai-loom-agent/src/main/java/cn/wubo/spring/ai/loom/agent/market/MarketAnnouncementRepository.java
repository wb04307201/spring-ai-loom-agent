package cn.wubo.spring.ai.loom.agent.market;

public interface MarketAnnouncementRepository {
    void upsert(String marketKind, Long marketId, String title, String body);
    void delete(String marketKind, Long marketId);
    MarketAnnouncement findOne(String marketKind, Long marketId);
    Page<MarketAnnouncement> listAllForKind(String marketKind, int page, int size);
}
