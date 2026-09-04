package cn.wubo.spring.ai.loom.agent.market;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

/**
 * 市场公告仓储 — 面向 {@code market_content_announcement} (PK: market_kind, market_id)。
 *
 * <p>字段固定 — market_kind (VARCHAR(16), 'SKILL'|'KNOWLEDGE')、market_id (BIGINT)、
 * title (VARCHAR(128))、body (TEXT)、created_at (TIMESTAMP)。upsert 用 H2
 * {@code MERGE INTO} (同 T16 / T17 review 的 upsert 风格),保持幂等。
 *
 * <p><b>findOne 返回 null:</b> 与 {@code Optional<...>} 不同,repository 直接返回
 * {@code null} 让调用方做存在性判断;不需要引入 {@code Optional}。{@code listAllForKind}
 * 在无数据时返回空列表 + count=0。
 *
 * <p><b>为何本类:</b> T3 已声明 {@link MarketAnnouncementRepository} 接口,T7/T8 router
 * 写了 {@code announcementRepo.upsert(...)} / {@code findOne(...)} 的 forward refs
 * (由 T18 端点正式接入)。T17 在仓储层落地具体实现,使 T18 路由可以直接按名注入。
 *
 * <p>Wiring: 在 {@code LoomAgentConfiguration.StorageConfiguration} 注册为 {@code @Bean},
 * 名字 {@code marketAnnouncementRepository} (供路由器按名注入)。本类不带
 * {@code @Component},因为配置类已经显式按名注册;同时存在两个 Bean 会导致
 * Spring "no unique bean"。
 */
public class DefaultMarketAnnouncementRepository implements MarketAnnouncementRepository {

    private static final String TABLE = "market_content_announcement";

    /** 与 schema 字段顺序对齐 — market_kind, market_id, title, body, created_at。 */
    private static final RowMapper<MarketAnnouncement> ROW_MAPPER = (rs, n) -> new MarketAnnouncement(
            rs.getString(1),
            rs.getLong(2),
            rs.getString(3),
            rs.getString(4),
            rs.getTimestamp(5).toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public DefaultMarketAnnouncementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入或覆盖公告 — H2 {@code MERGE INTO} 按 PK (market_kind, market_id) upsert。
     * 命中时 UPDATE title / body (created_at 保留原值);未命中时 INSERT。
     * <p>注意:{@code created_at} 列不在 MERGE 列表里 → 命中时不被覆盖,未命中时取
     * schema DEFAULT {@code CURRENT_TIMESTAMP}。
     */
    @Override
    public void upsert(String marketKind, Long marketId, String title, String body) {
        jdbc.update(
                "MERGE INTO " + TABLE + " (market_kind, market_id, title, body) " +
                        "KEY(market_kind, market_id) VALUES (?, ?, ?, ?)",
                marketKind, marketId, title, body);
    }

    @Override
    public void delete(String marketKind, Long marketId) {
        jdbc.update(
                "DELETE FROM " + TABLE + " WHERE market_kind = ? AND market_id = ?",
                marketKind, marketId);
    }

    /** 找不到时返回 {@code null} — 调用方决定 404 vs 默认值。 */
    @Override
    public MarketAnnouncement findOne(String marketKind, Long marketId) {
        try {
            return jdbc.queryForObject(
                    "SELECT market_kind, market_id, title, body, created_at FROM " + TABLE +
                            " WHERE market_kind = ? AND market_id = ?",
                    ROW_MAPPER, marketKind, marketId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * @see MarketAnnouncementRepository#findOneByRawId(String, String)
     */
    @Override
    public MarketAnnouncement findOneByRawId(String marketKind, String rawMarketId) {
        if (rawMarketId == null || rawMarketId.isBlank()) return null;
        Long id;
        try {
            id = Long.parseLong(rawMarketId);
        } catch (NumberFormatException nfe) {
            // V1.0 schema: market_content_announcement.market_id is BIGINT;
            // VARCHAR(36) UUID KB ids can never be stored here. Return null
            // without touching the DB to keep graceful-degradation intact.
            return null;
        }
        return findOne(marketKind, id);
    }

    @Override
    public Page<MarketAnnouncement> listAllForKind(String marketKind, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.max(size, 1);
        long offset = (long) p * s;
        List<MarketAnnouncement> rows = jdbc.query(
                "SELECT market_kind, market_id, title, body, created_at FROM " + TABLE +
                        " WHERE market_kind = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, marketKind, s, offset);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE market_kind = ?",
                Long.class, marketKind);
        return new Page<>(rows, total == null ? 0L : total, p, s);
    }
}
