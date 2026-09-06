package cn.wubo.spring.ai.loom.agent.model;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Skill 市场记录。
 * 移除 version 字段；唯一约束改为 {@code (author, name)}。
 * status: 起直接 APPROVED（无审批流）。
 *
 * <p>M3+ T2.1 — {@code announcementTitle} / {@code announcementBody}
 * 由 {@link cn.wubo.spring.ai.loom.agent.market.AbstractMarketAdminService#listPaged}
 * 在批量查询时通过 {@code LEFT JOIN market_content_announcement} 一次性填充,
 * 避免前端 per-row 二次 GET (N+1 修复)。
 *
 * <p>M4 T3 — 末尾 5 个字段 {@code avgRating} / {@code ratingCount} /
 * {@code isOfficial} / {@code featuredRank} / {@code tags}:前 4 个由
 * {@code listPaged} 的 review-aggregate LEFT JOIN + {@code m.*} 列填充;
 * {@code tags} 预留给 T4 (plan D7:record 一次性加齐,from(rs) 恒传 null,
 * 由后续 copy-helper {@link #withTags(java.util.List)} 填充 — 与
 * {@link MarketKnowledgeRecord} 的 tags 模式镜像)。
 * 非 listPaged 路径 (getById / search / listApproved / role join 等) 经
 * findColumn 探针读到 null/默认值,不改变行为。
 */
public record MarketSkill(
        Long id,
        String name,
        String description,
        String content,
        String author,
        String status,
        LocalDateTime submittedAt,
        LocalDateTime reviewedAt,
        String reviewedBy,
        String reviewComment,
        String announcementTitle,
        String announcementBody,
        Double avgRating,
        Long ratingCount,
        Boolean isOfficial,
        Integer featuredRank,
        java.util.List<String> tags
) {
    /**
     * 标识常量
     */
    public static final String STATUS_APPROVED = "APPROVED";

    /**
     * ResultSet 工厂方法 — 读取 market_skill 表的 10 个核心字段 + 2 个 announcement 字段
     * + M4 T3 的 4 个 rating/official 字段({@code avg_rating} / {@code rating_count} /
     * {@code is_official} / {@code featured_rank},均经 findColumn 探针 — 列缺失时
     * 落 null/0 默认值)。{@code tags} 恒为 null,由 {@link #withTags(java.util.List)}
     * 后续填充(plan D7)。{@code category} / {@code created_by_kind} 列仍不读取。
     *
     * <p>announcement 字段以 {@code announcement_title} / {@code announcement_body}
     * 列别名读出,SQL 由 listPaged 用 LEFT JOIN 提供;若无 announcement 行则返回 null。
     *
     * @param rs 已定位到当前行的 {@link ResultSet}
     * @return 填充后的 {@link MarketSkill} 实例
     */
    public static MarketSkill from(ResultSet rs) throws SQLException {
        Timestamp submittedAt = rs.getTimestamp("submitted_at");
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        // M3+ T2.1 — announcementTitle / announcementBody may be absent
        // when the SELECT did not LEFT JOIN market_content_announcement
        // (e.g. {@code getById} which reads the main table only). Probe
        // via findColumn in a try/catch so the helper works for both
        // joined and un-joined queries.
        boolean annPresent = hasColumn(rs, "announcement_title");
        // M4 T3 — avg_rating / rating_count only present when the SELECT
        // LEFT JOINed the review aggregate (listPaged); is_official /
        // featured_rank come from m.* on market-table queries but probe
        // anyway so the helper stays safe for any caller. tags is never
        // read from the ResultSet — filled via withTags() (plan D7).
        boolean ratingPresent = hasColumn(rs, "avg_rating");
        boolean officialPresent = hasColumn(rs, "is_official");
        boolean rankPresent = hasColumn(rs, "featured_rank");
        Double avgRating = null;
        Long ratingCount = null;
        if (ratingPresent) {
            double avg = rs.getDouble("avg_rating");
            avgRating = rs.wasNull() ? null : avg;
            long cnt = rs.getLong("rating_count");
            ratingCount = rs.wasNull() ? 0L : cnt;
        }
        return new MarketSkill(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("content"),
                rs.getString("author"),
                rs.getString("status"),
                submittedAt == null ? null : submittedAt.toLocalDateTime(),
                reviewedAt == null ? null : reviewedAt.toLocalDateTime(),
                rs.getString("reviewed_by"),
                rs.getString("review_comment"),
                annPresent ? rs.getString("announcement_title") : null,
                annPresent ? rs.getString("announcement_body") : null,
                avgRating,
                ratingCount,
                officialPresent && rs.getBoolean("is_official"),
                rankPresent ? rs.getInt("featured_rank") : 0,
                null
        );
    }

    /** findColumn probe — true when the column label exists on this ResultSet. */
    private static boolean hasColumn(ResultSet rs, String label) {
        try {
            rs.findColumn(label);
            return true;
        } catch (SQLException notFound) {
            return false;
        }
    }

    /**
     * M4 T3 (plan D7) — copy-helper mirroring
     * {@link MarketKnowledgeRecord#withTags(java.util.List)}: returns a new
     * record differing only in {@code tags}; all other components preserved.
     * {@code null} input normalizes to {@code List.of()} (never null).
     */
    public MarketSkill withTags(java.util.List<String> newTags) {
        return new MarketSkill(
                id, name, description, content, author, status,
                submittedAt, reviewedAt, reviewedBy, reviewComment,
                announcementTitle, announcementBody,
                avgRating, ratingCount, isOfficial, featuredRank,
                newTags == null ? java.util.List.of() : java.util.List.copyOf(newTags));
    }
}
