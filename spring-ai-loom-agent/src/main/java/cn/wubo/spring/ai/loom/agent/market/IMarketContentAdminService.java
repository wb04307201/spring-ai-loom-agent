package cn.wubo.spring.ai.loom.agent.market;

/**
 * 市场内容 admin 接口 — 参数化版本 (M3+ T1.2)。
 *
 * <p>类型参数:
 * <ul>
 *   <li>{@code K} — 主键类型 ({@link Long} for skill;{@link String} for KB UUID)</li>
 *   <li>{@code M} — 市场条目实体类型 (market_skill / loom_market_knowledge 等)</li>
 *   <li>{@code U} — 子类专有的 update 请求类型(预留;接口侧使用 {@link MarketUpdateRequest})</li>
 *   <li>{@code R} — 子类专有的 review 类型(预留)</li>
 * </ul>
 *
 * <p>{@link #extractId(Object)} 已从 {@link AbstractMarketAdminService} 提升到接口,
 * 子类必须 public 实现 — 这样子类可同时实现 {@link IMarketContentAdminService} 暴露
 * 主键提取入口(供 list / search / admin 路由使用),无需反射。
 */
public interface IMarketContentAdminService<K, M, U, R> {

    Page<M> listPaged(MarketFilter filter);

    M getById(K id);

    M create(String author, MarketCreateRequest req);

    M update(K id, MarketUpdateRequest req);

    void delete(K id);

    M approve(K id, String reviewer);

    M reject(K id, String reviewer, String comment);

    void setOfficial(K id, boolean isOfficial, String reviewer);

    void setFeaturedRank(K id, int rank, String reviewer);

    void setCategory(K id, String category, String reviewer);

    /** 全字段搜索(MVP 用 SQL LIKE)。 */
    Page<M> search(String query, String category, int page, int size);

    /**
     * 从条目抽取主键 — 接口抽象方法 (M3+ T1.2 提升)。
     * skill 端返 {@link Long};KB 端返 {@link String}(UUID)。
     */
    K extractId(M entry);

    /**
     * 提取条目当前审核状态 — 供 abstract base / 调用方在不直接读取 DB 的情况下
     * 取得条目的 {@link MarketContentStatus}(PENDING / APPROVED / REJECTED)。
     */
    MarketContentStatus currentStatus(M entry);
}
