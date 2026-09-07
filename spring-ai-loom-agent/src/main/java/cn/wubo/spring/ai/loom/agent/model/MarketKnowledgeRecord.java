package cn.wubo.spring.ai.loom.agent.model;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 市场知识库条目。
 * 唯一约束：{@code (username, name)}。
 * status: PENDING / APPROVED / REJECTED
 *
 * <p>注意：{@code loom_market_knowledge.id} 是 {@code VARCHAR(36)}(UUID),
 * 与 {@link MarketSkill} 的 {@code BIGINT id} 是真实的对称差异。
 *
 * <p>M3+ T2.1 — 末尾 3 个字段 {@code announcementTitle} / {@code announcementBody}
 * / {@code tags} 避免前端 per-row 二次 GET (N+1 修复)。R4-deferred #2 修正:
 * {@code tags} 由 {@link cn.wubo.spring.ai.loom.agent.knowledge.DefaultKnowledgeMarketService}
 * 的 <b>{@code listPaged} 与 {@code listApproved} 两条路径</b>共同填充(同一
 * {@code embedTags} 批量 SELECT);{@code announcementTitle} / {@code announcementBody}
 * 仅由 {@code listPaged} 的 LEFT JOIN announcement 填充({@code listApproved}
 * 的 SELECT 不 JOIN 公告表)。Skill 端无 tags 字段。
 *
 * <p>M4 T3 — 末尾 4 个字段 {@code avgRating} / {@code ratingCount} /
 * {@code isOfficial} / {@code featuredRank} 由
 * {@link cn.wubo.spring.ai.loom.agent.market.AbstractMarketAdminService#listPaged}
 * 的 review-aggregate LEFT JOIN + {@code m.*} 列填充;非 listPaged 路径经
 * findColumn 探针读到 null/默认值。
 */
public record MarketKnowledgeRecord(
        String id,
        String username,
        String name,
        String description,
        String status,
        LocalDateTime submittedAt,
        LocalDateTime reviewedAt,
        String reviewedBy,
        String reviewComment,
        String announcementTitle,
        String announcementBody,
        List<String> tags,
        Double avgRating,
        Long ratingCount,
        Boolean isOfficial,
        Integer featuredRank,
        String category
) {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    /**
     * ResultSet 工厂方法 — 读取 {@code loom_market_knowledge} 表的 9 个核心字段
     * + announcement 字段(仅当 SELECT LEFT JOIN 了公告表时,通过 findColumn 探测)。
     * {@code tags} 不从此 ResultSet 读取 —— 由
     * {@code DefaultKnowledgeMarketService} 的 {@code listPaged} 与 {@code listApproved}
     * <b>两条路径</b>在主查询后各自用批量 {@code SELECT ... WHERE market_id IN (...)}
     * ({@code embedTags})补齐(R4-deferred #2 修正:旧 doc 只提 listPaged),
     * 以避免 H2 不支持的 GROUP_CONCAT 聚合。
     *
     * <p>M4 T3 — {@code avg_rating} / {@code rating_count}(review-aggregate LEFT JOIN
     * 列别名)与 {@code is_official} / {@code featured_rank}({@code m.*} 列)经
     * findColumn 探针读取,列缺失时落 null/0 默认值;{@code category} 同样经
     * findColumn 探针读取(gate fix:此前列存在但 DTO 从不回读,admin UI 分类列/
     * 编辑框成 write-only);{@code created_by_kind} 列仍不读取(record 上没有对应字段)。
     *
     * @param rs 已定位到当前行的 {@link ResultSet}
     * @return 填充后的 {@link MarketKnowledgeRecord} 实例(tags 字段为 null,
     *         待 listPaged / listApproved 的 embedTags 后处理填充)
     */
    public static MarketKnowledgeRecord from(ResultSet rs) throws SQLException {
        Timestamp submittedAt = rs.getTimestamp("submitted_at");
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        // M3+ T2.1 — announcement fields present only when the SELECT
        // LEFT JOINed market_content_announcement; tags is currently
        // always null from this factory and is filled by listPaged's
        // batch SELECT follow-up. Use findColumn to detect the joined
        // columns so the helper works for both joined and un-joined
        // queries.
        boolean annPresent = hasColumn(rs, "announcement_title");
        // M4 T3 — avg_rating / rating_count only present when the SELECT
        // LEFT JOINed the review aggregate (listPaged); is_official /
        // featured_rank come from m.* on market-table queries but probe
        // anyway so the helper stays safe for any caller (getById /
        // listApproved / role join queries pass through here too).
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
        return new MarketKnowledgeRecord(
                rs.getString("id"),
                rs.getString("username"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("status"),
                submittedAt == null ? null : submittedAt.toLocalDateTime(),
                reviewedAt == null ? null : reviewedAt.toLocalDateTime(),
                rs.getString("reviewed_by"),
                rs.getString("review_comment"),
                annPresent ? rs.getString("announcement_title") : null,
                annPresent ? rs.getString("announcement_body") : null,
                null,
                avgRating,
                ratingCount,
                officialPresent && rs.getBoolean("is_official"),
                rankPresent ? rs.getInt("featured_rank") : 0,
                hasColumn(rs, "category") ? rs.getString("category") : null);
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
     * M3+ R4 (AT2 follow-up, change 1) — copy-helper:返回一个仅 {@code tags}
     * 字段不同的新 record,其余字段原样保留(M4 T3:含新增的
     * {@code avgRating} / {@code ratingCount} / {@code isOfficial} /
     * {@code featuredRank} 4 个组件)。
     * {@code DefaultKnowledgeMarketService.listPaged} 用它把批量 tag SELECT
     * 的结果写回页面行(空 tag 时传 {@code List.of()},不允许 null)。
     */
    public MarketKnowledgeRecord withTags(List<String> newTags) {
        return new MarketKnowledgeRecord(
                id, username, name, description, status,
                submittedAt, reviewedAt, reviewedBy, reviewComment,
                announcementTitle, announcementBody,
                newTags == null ? List.of() : List.copyOf(newTags),
                avgRating, ratingCount, isOfficial, featuredRank, category);
    }
}
