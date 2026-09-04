package cn.wubo.spring.ai.loom.agent.market;

public interface IMarketContentAdminService<M, U, R> {

    Page<M> listPaged(MarketFilter filter);

    M getById(Long id);

    M create(String author, MarketCreateRequest req);

    M update(Long id, MarketUpdateRequest req);

    void delete(Long id);

    M approve(Long id, String reviewer);

    M reject(Long id, String reviewer, String comment);

    void setOfficial(Long id, boolean isOfficial, String reviewer);

    void setFeaturedRank(Long id, int rank, String reviewer);

    void setCategory(Long id, String category, String reviewer);

    /** 全字段搜索(MVP 用 SQL LIKE)。 */
    Page<M> search(String query, String category, int page, int size);

    /**
     * 提取条目当前审核状态 — 供 abstract base / 调用方在不直接读取 DB 的情况下
     * 取得条目的 {@link MarketContentStatus}(PENDING / APPROVED / REJECTED)。
     */
    MarketContentStatus currentStatus(M entry);
}
