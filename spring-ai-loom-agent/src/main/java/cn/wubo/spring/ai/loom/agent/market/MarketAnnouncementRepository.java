package cn.wubo.spring.ai.loom.agent.market;

public interface MarketAnnouncementRepository {
    void upsert(String marketKind, Long marketId, String title, String body);
    void delete(String marketKind, Long marketId);
    MarketAnnouncement findOne(String marketKind, Long marketId);

    /**
     * Lookup by raw (unparsed) market id string. The V1.0 schema declares
     * {@code market_content_announcement.market_id} as BIGINT, so only numeric
     * ids can ever be stored there — KB rows whose {@code loom_market_knowledge.id}
     * is a VARCHAR(36) UUID have no announcement row to begin with.
     *
     * <p>This method tries {@code Long.parseLong} first; on success it delegates
     * to {@link #findOne(String, Long)}. On {@link NumberFormatException}
     * (i.e. the raw id is a UUID) it returns {@code null} without touching the
     * DB — that mirrors the graceful-degradation pattern already used by
     * {@code /reviews} / {@code /stats} (UUIDs are blocked at deeper layers).
     *
     * <p>Used by the public {@code GET /market-{kind}s/{id}/announcement}
     * router so the endpoint accepts both numeric ids (returning the row)
     * and UUIDs (returning {@code null} → 204).
     */
    MarketAnnouncement findOneByRawId(String marketKind, String rawMarketId);

    Page<MarketAnnouncement> listAllForKind(String marketKind, int page, int size);
}
