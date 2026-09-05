package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;

/**
 * 市场内容公告仓储(M3+ T1.4)。
 *
 * <p>保留两套主键形态 — 兼容 skill(BIGINT)与 KB (VARCHAR(36) UUID)两类内容:
 * <ul>
 *   <li>{@link Long} 主键版本 — skill 端既有调用方</li>
 *   <li>{@link String} 主键版本 — M3+ T1.4 新增,KB router 走
 *       {@code RouterIdParserKnowledge#parse} 后直接传 String,不预先
 *       {@code Long.parseLong};UUID 路径走 graceful-degradation
 *       (抛 {@link LoomAgentRuntimeException}(404))</li>
 * </ul>
 *
 * <p>注意:本接口的 {@code market_id} 列是 BIGINT,KB 端 UUID 永远不会有匹配 row
 * — String 版本对 UUID 抛 404,与 review/stats 服务的 graceful-degradation 对齐。
 */
public interface MarketAnnouncementRepository {

    /* ===== Long 主键版本(skill / 既有调用方) ===== */

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

    /* ===== String 主键版本(M3+ T1.4:KB router 用) ===== */

    default void upsert(String marketKind, String marketId, String title, String body) {
        upsert(marketKind, parseMarketIdOrThrow(marketId), title, body);
    }

    default void delete(String marketKind, String marketId) {
        delete(marketKind, parseMarketIdOrThrow(marketId));
    }

    default MarketAnnouncement findOne(String marketKind, String marketId) {
        return findOne(marketKind, parseMarketIdOrThrow(marketId));
    }

    private static Long parseMarketIdOrThrow(String s) {
        if (s == null || s.isBlank()) {
            throw new LoomAgentRuntimeException(404, "市场知识库不存在: id=" + s);
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            throw new LoomAgentRuntimeException(404, "市场知识库不存在: id=" + s);
        }
    }
}
