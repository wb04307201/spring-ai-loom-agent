package cn.wubo.spring.ai.loom.agent.market;

/**
 * 市场内容公告仓储(M3+ T1.4 / R3)。
 *
 * <p>R3 起本接口是 <b>String-native 单一 API</b>:B1 真修后
 * {@code market_content_announcement.market_id} 列是 VARCHAR(36),SKILL
 * (BIGINT 起源,以十进制字符串写入)与 KNOWLEDGE(UUID)共用同一列类型。
 * 旧的 {@code Long} 主键 overload 与 {@code parseMarketIdOrThrow}
 * graceful-degradation 全部删除 — 不再存在 Long↔VARCHAR 强转路径
 * (AT2:跨 kind 行不会互相打挂;R3 修 a13 间歇 500 的根因)。
 *
 * <p>SKILL 端调用方(router / service)仍持有 {@code Long id},在调用处
 * {@code String.valueOf(id)} 转换后传入;skill 业务服务(setFeaturedRank 等)
 * 保持 Long 主键不变。
 */
public interface MarketAnnouncementRepository {

    void upsert(String marketKind, String marketId, String title, String body);

    void delete(String marketKind, String marketId);

    /** 找不到时返回 {@code null} — 调用方决定 404 / 204 / 默认值。 */
    MarketAnnouncement findOne(String marketKind, String marketId);

    Page<MarketAnnouncement> listAllForKind(String marketKind, int page, int size);
}
