package cn.wubo.spring.ai.loom.agent.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

/**
 * 公共抽象基类,实现 {@link IMarketContentAdminService} 中的 admin 方法
 * (approve / reject / setOfficial / setFeaturedRank / setCategory / listPaged / search / delete / getById)。
 * 子类通过 hook 方法 ({@link #tableName()} / {@link #rowMapper()} /
 * 可选的 {@link #currentStatusImpl(Object)}) 提供具体表/行映射。
 *
 * <p>字段默认状态:
 * <ul>
 *   <li>status (PENDING / APPROVED / REJECTED) — 经 {@link MarketContentStatus} 序列化</li>
 *   <li>is_official / featured_rank / category — 排序 / 过滤字段</li>
 *   <li>reviewed_at / reviewed_by / review_comment — admin 审核字段</li>
 * </ul>
 *
 * <p>类型参数 (M3+ T1.2):
 * <ul>
 *   <li>{@code K} — 主键类型 ({@link Long} for skill;{@link String} for KB UUID),
 *       由子类 extends 时具体化</li>
 *   <li>{@code M} — 市场条目实体类型 (market_skill / loom_market_knowledge 等)</li>
 *   <li>{@code U} — 子类专有的 update 请求类型(预留;接口侧使用 {@link MarketUpdateRequest})</li>
 *   <li>{@code R} — 子类专有的 review 类型(预留)</li>
 * </ul>
 *
 * <p>SQL 行为:本抽象基类的所有 admin 方法用 {@code WHERE id=?} 占位符,
 * 由 JDBC 自动绑定 {@code K} 类型参数 — skill 端为 BIGINT 走 numeric 路径,
 * KB 端为 VARCHAR(36) UUID 走 string 路径,H2 自动按列类型 coerce。
 */
public abstract class AbstractMarketAdminService<K, M, U, R> implements IMarketContentAdminService<K, M, U, R> {

    protected final JdbcTemplate jdbc;

    protected AbstractMarketAdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 表名,例如 {@code "market_skill"} / {@code "loom_market_knowledge"}。
     */
    protected abstract String tableName();

    /**
     * 行映射器 — 把 {@link java.sql.ResultSet} 转成 M 类型。
     */
    protected abstract RowMapper<M> rowMapper();

    /**
     * 取得条目当前审核状态。final:子类必须通过 {@link #currentStatusImpl(Object)} 提供实现,
     * 不能直接覆盖 {@code currentStatus} 以保留接口契约。
     */
    @Override
    public final MarketContentStatus currentStatus(M entry) {
        return currentStatusImpl(entry);
    }

    /**
     * 子类实现入口 — 返回该条目当前在 DB 中的状态。默认抛
     * {@link UnsupportedOperationException},要求子类显式提供。
     */
    protected MarketContentStatus currentStatusImpl(M entry) {
        throw new UnsupportedOperationException(
            "currentStatusImpl must be implemented by subclass to provide current status");
    }

    /**
     * 按 id 查询单条。子类如有额外缓存 / join,可 override。
     */
    @Override
    public M getById(K id) {
        return jdbc.queryForObject(
            "SELECT * FROM " + tableName() + " WHERE id=?",
            rowMapper(), id);
    }

    /**
     * 默认 create 不实现 — 不同表的列结构差异较大,要求子类提供。
     */
    @Override
    public M create(String author, MarketCreateRequest req) {
        throw new UnsupportedOperationException("create must be implemented by subclass");
    }

    /**
     * 默认 update 不实现 — 同 {@link #create(String, MarketCreateRequest)}。
     */
    @Override
    public M update(K id, MarketUpdateRequest req) {
        throw new UnsupportedOperationException("update must be implemented by subclass");
    }

    /**
     * approve:置 status='APPROVED',记录 reviewer / reviewed_at,回读最新行。
     */
    @Override
    public M approve(K id, String reviewer) {
        jdbc.update(
            "UPDATE " + tableName() + " SET status='APPROVED', reviewed_at=CURRENT_TIMESTAMP, reviewed_by=? WHERE id=?",
            reviewer, id);
        return getById(id);
    }

    /**
     * reject:必须填 comment,否则抛 {@link IllegalArgumentException};
     * 置 status='REJECTED',记录 reviewer / comment / reviewed_at,回读最新行。
     */
    @Override
    public M reject(K id, String reviewer, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("reject 必须填评论(comment 必填)");
        }
        jdbc.update(
            "UPDATE " + tableName() + " SET status='REJECTED', reviewed_at=CURRENT_TIMESTAMP, reviewed_by=?, review_comment=? WHERE id=?",
            reviewer, comment, id);
        return getById(id);
    }

    @Override
    public void setOfficial(K id, boolean isOfficial, String reviewer) {
        jdbc.update("UPDATE " + tableName() + " SET is_official=? WHERE id=?", isOfficial, id);
    }

    @Override
    public void setFeaturedRank(K id, int rank, String reviewer) {
        jdbc.update("UPDATE " + tableName() + " SET featured_rank=? WHERE id=?", rank, id);
    }

    @Override
    public void setCategory(K id, String category, String reviewer) {
        jdbc.update("UPDATE " + tableName() + " SET category=? WHERE id=?", category, id);
    }

    /**
     * 通用分页查询 — 按 {@link MarketFilter} 拼 WHERE / ORDER BY / LIMIT / OFFSET。
     * sortBy 支持 {@code "official_rank"} (默认) 和 {@code "submitted_at"}。
     */
    @Override
    public Page<M> listPaged(MarketFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(tableName());
        appendCommonFilters(sql, filter);
        sql.append(" ORDER BY ");
        switch (filter.sortBy()) {
            case "official_rank" -> sql.append("is_official DESC, featured_rank DESC, submitted_at DESC");
            case "submitted_at"  -> sql.append("submitted_at DESC");
            default              -> sql.append("is_official DESC, featured_rank DESC, submitted_at DESC");
        }
        sql.append(" LIMIT ").append(filter.size()).append(" OFFSET ").append(filter.page() * filter.size());
        List<M> rows = jdbc.query(sql.toString(), rowMapper());
        long total = countWith(filter);
        return new Page<>(rows, total, filter.page(), filter.size());
    }

    private void appendCommonFilters(StringBuilder sql, MarketFilter filter) {
        sql.append(" WHERE 1=1");
        if (filter.status() != null) {
            sql.append(" AND status='").append(filter.status().name()).append("'");
        }
        if (filter.category() != null && !filter.category().isBlank()) {
            sql.append(" AND category='").append(filter.category().replace("'", "''")).append("'");
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            String q = "%" + filter.query().replace("'", "''") + "%";
            sql.append(" AND (name LIKE '").append(q).append("' OR description LIKE '").append(q).append("')");
        }
    }

    private long countWith(MarketFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(tableName());
        appendCommonFilters(sql, filter);
        Long n = jdbc.queryForObject(sql.toString(), Long.class);
        return n == null ? 0L : n;
    }

    /**
     * 公共搜索入口 — 用 APPROVED 状态过滤,默认按 official_rank 排序。
     * (MVP 用 SQL LIKE;后续可换到全文索引)
     */
    @Override
    public Page<M> search(String query, String category, int page, int size) {
        return listPaged(new MarketFilter(page, size, MarketContentStatus.APPROVED, category, query, "official_rank"));
    }

    @Override
    public void delete(K id) {
        jdbc.update("DELETE FROM " + tableName() + " WHERE id=?", id);
    }
}
