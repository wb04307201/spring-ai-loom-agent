package cn.wubo.spring.ai.loom.agent.market;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 公共抽象基类,实现 {@link IMarketContentReviewService} 中的
 * listReviews / submit / update / deleteAsAdmin / aggregate。
 * 子类只需通过 hook 方法 ({@link #tableName()} / {@link #keyCol()} /
 * {@link #readKey(ResultSet)}) 提供具体表/主键列/主键读取方式。
 *
 * <p>面向 {@code market_skill_review} (PK: market_skill_id, username) 与
 * {@code loom_market_knowledge_review} (PK: market_id, username) 两张表:
 * <ul>
 *   <li>字段固定 — rating (SMALLINT 1-5)、comment (TEXT)、edit_count (SMALLINT)、
 *       created_at / updated_at (TIMESTAMP),无需子类扩展;</li>
 *   <li>{@code submit} 用 H2 {@code MERGE INTO} upsert;首次提交允许,无 edit_count 校验;</li>
 *   <li>{@code update} 必须满足 {@code edit_count < 1},校验失败抛 403;
 *       每次 update 把 {@code edit_count = edit_count + 1};</li>
 *   <li>{@code aggregate} 通过 {@code JOIN user_info u ON u.username = r.username}
 *       排除 {@code u.type = 'ADMIN'} 的自评,符合 spec § 9.2 第 5 行。</li>
 * </ul>
 *
 * <h2>类型参数 {@code K}(M3+ T1.7 gap 修复 — R2,镜像 T1.5 stats 模式)</h2>
 * <p>Generic over the market-id type — {@code Long} for skill reviews
 * ({@code market_skill_review.market_skill_id} 是 {@code BIGINT}),
 * {@code String} (UUID) for KB reviews
 * ({@code loom_market_knowledge_review.market_id} 自 T1.1 迁移后是
 * {@code VARCHAR(36)})。所有 {@code jdbc.update/query} 直接绑定 {@code K}
 * (String 绑 VARCHAR(36)、Long 绑 BIGINT,各自类型对齐)—— 彻底消除
 * "Long 绑 VARCHAR(36)" 的隐式 coercion(H2 在部分 session 状态下
 * MERGE 写入后同值 SELECT 读不回的 flaky 根因)。</p>
 *
 * <p>RowMapper 的主键列读取由子类 {@link #readKey(ResultSet)} hook 提供
 * (KB 读 {@code rs.getString},Skill 读 {@code rs.getLong}),其余列固定。</p>
 *
 * <p>upsert 保留 H2 {@code MERGE INTO}(ADR-T05.3 已裁定 portable-upsert
 * 延后,不在本任务改 SQL 方言)。</p>
 *
 * @param <K> market id 类型:Long for skill,String (UUID) for KB
 */
public abstract class AbstractMarketReviewService<K> implements IMarketContentReviewService<K> {

    protected final JdbcTemplate jdbc;

    protected AbstractMarketReviewService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** review 表名 — {@code market_skill_review} / {@code loom_market_knowledge_review}。 */
    protected abstract String tableName();

    /** review 表主键列(指向 market 内容表 id)— {@code market_skill_id} / {@code market_id}。 */
    protected abstract String keyCol();

    /**
     * 从 {@link ResultSet} 第 1 列读取主键 — Skill 子类返回
     * {@code rs.getLong(1)},KB 子类返回 {@code rs.getString(1)}
     * (列类型分别是 BIGINT / VARCHAR(36),必须按真实类型读,否则
     * UUID 会被 coerce 丢值)。
     */
    protected abstract K readKey(ResultSet rs) throws SQLException;

    /** 共享 {@link ReviewRow} 行映射器:固定列序 — keyCol, username, rating, comment, edit_count, created_at, updated_at;主键读取走 {@link #readKey(ResultSet)} hook。 */
    private final RowMapper<ReviewRow<K>> rowMapper = (rs, n) -> new ReviewRow<>(
            readKey(rs),
            rs.getString(2),
            rs.getInt(3),
            rs.getString(4),
            rs.getInt(5),
            rs.getTimestamp(6).toLocalDateTime(),
            rs.getTimestamp(7).toLocalDateTime()
    );

    @Override
    public Page<ReviewRow<K>> listReviews(K marketId, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.max(size, 1);
        long offset = (long) p * s;
        List<ReviewRow<K>> rows = jdbc.query(
                "SELECT " + keyCol() + ", username, rating, comment, edit_count, created_at, updated_at " +
                        " FROM " + tableName() + " WHERE " + keyCol() + " = ? " +
                        " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                rowMapper, marketId, s, offset);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName() + " WHERE " + keyCol() + " = ?",
                Long.class, marketId);
        return new Page<>(rows, total == null ? 0L : total, p, s);
    }

    /**
     * 提交评价 — H2 {@code MERGE INTO} upsert。命中 PK (market_id, username)
     * 时 UPDATE 全部列出列;未命中时 INSERT,其它列(edit_count / created_at /
     * updated_at)由 schema DEFAULT 填(edit_count=0, created_at=NOW, updated_at=NOW)。
     * <p>首次提交允许 — 不校验 {@code edit_count}。{@code update} 才有 1 次修改上限。
     */
    @Override
    public ReviewRow<K> submit(K marketId, String username, ReviewSubmitRequest req) {
        jdbc.update(
                "MERGE INTO " + tableName() + " (" + keyCol() + ", username, rating, comment) " +
                        "KEY(" + keyCol() + ", username) VALUES (?, ?, ?, ?)",
                marketId, username, req.rating(), req.comment());
        return readBack(marketId, username);
    }

    /**
     * 更新评价 — 必须满足 {@code edit_count < 1}。第二次 update 直接抛 403。
     * 任意字段为 null 时不更新对应列;rating / comment 至少要有一个非 null,否则空更新无意义。
     * 更新成功后 {@code edit_count = edit_count + 1},{@code updated_at = CURRENT_TIMESTAMP}。
     */
    @Override
    public ReviewRow<K> update(K marketId, String username, ReviewUpdateRequest req) {
        Integer editCount;
        try {
            editCount = jdbc.queryForObject(
                    "SELECT edit_count FROM " + tableName() +
                            " WHERE " + keyCol() + " = ? AND username = ?",
                    Integer.class, marketId, username);
        } catch (EmptyResultDataAccessException e) {
            // 行不存在 — 等价于 editCount==null 的契约
            throw new LoomAgentRuntimeException(404, "评价不存在,请先提交");
        }
        if (editCount == null) {
            throw new LoomAgentRuntimeException(404, "评价不存在,请先提交");
        }
        if (editCount >= 1) {
            throw new LoomAgentRuntimeException(403, "评价只能修改一次,请删除后重新提交");
        }

        StringBuilder sql = new StringBuilder("UPDATE ").append(tableName())
                .append(" SET updated_at = CURRENT_TIMESTAMP, edit_count = edit_count + 1");
        List<Object> args = new ArrayList<>();
        if (req.rating() != null) {
            sql.append(", rating = ?");
            args.add(req.rating());
        }
        if (req.comment() != null) {
            sql.append(", comment = ?");
            args.add(req.comment());
        }
        sql.append(" WHERE ").append(keyCol()).append(" = ? AND username = ?");
        args.add(marketId);
        args.add(username);
        jdbc.update(sql.toString(), args.toArray());
        return readBack(marketId, username);
    }

    /**
     * admin 删除某条评价 — 不需要 (market_id, username) 是当前用户本人的;
     * 路由层应在调用前完成 {@code user.isAdmin(...)} 校验。
     */
    @Override
    public void deleteAsAdmin(K marketId, String username) {
        jdbc.update(
                "DELETE FROM " + tableName() +
                        " WHERE " + keyCol() + " = ? AND username = ?",
                marketId, username);
    }

    /**
     * 聚合评分 — {@code count} + {@code avg},排除 admin 自评。
     * 通过 {@code JOIN user_info u ON u.username = r.username WHERE u.type <> 'ADMIN'}
     * 实现。{@code user_info} 表带 {@code type VARCHAR(20) CHECK (type IN ('ADMIN','USER'))}
     * 列(V1.0 已声明),可直接用于 SQL 过滤;不需要 fallback 到 user_role join。
     * <p>无评价时返回 {@code (0L, 0.0)} — {@code COALESCE} 把空集合的 AVG 抹平为 0。
     */
    @Override
    public RatingAggregate aggregate(K marketId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName() + " r " +
                        " JOIN user_info u ON u.username = r.username " +
                        " WHERE r." + keyCol() + " = ? AND u.type <> 'ADMIN'",
                Long.class, marketId);
        Double avg = jdbc.queryForObject(
                "SELECT COALESCE(AVG(r.rating), 0.0) FROM " + tableName() + " r " +
                        " JOIN user_info u ON u.username = r.username " +
                        " WHERE r." + keyCol() + " = ? AND u.type <> 'ADMIN'",
                Double.class, marketId);
        return new RatingAggregate(count == null ? 0L : count, avg == null ? 0.0 : avg);
    }

    /** 读回单条 (market_id, username) 的完整 ReviewRow;不存在抛 500(upsert 契约失败)。 */
    protected ReviewRow<K> readBack(K marketId, String username) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + keyCol() + ", username, rating, comment, edit_count, created_at, updated_at" +
                            " FROM " + tableName() + " WHERE " + keyCol() + " = ? AND username = ?",
                    rowMapper, marketId, username);
        } catch (EmptyResultDataAccessException e) {
            throw new LoomAgentRuntimeException(500,
                    "评价 upsert 失败:行未写入 (marketId=" + marketId + ", username=" + username + ")");
        }
    }
}
