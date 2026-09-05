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
 * / {@code tags} 由 {@link cn.wubo.spring.ai.loom.agent.knowledge.DefaultKnowledgeMarketService#listPaged}
 * 在批量查询时通过 LEFT JOIN announcement + 单次批量 SELECT tag 一次性填充,
 * 避免前端 per-row 二次 GET (N+1 修复)。Skill 端无 tags 字段。
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
        List<String> tags
) {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    /**
     * ResultSet 工厂方法 — 读取 {@code loom_market_knowledge} 表的 9 个核心字段
     * + announcement 字段。{@code tags} 不从此 ResultSet 读取 —— 由
     * {@code DefaultKnowledgeMarketService.listPaged} 在主查询后用批量
     * {@code SELECT ... WHERE market_id IN (...)} 补齐,以避免 H2 不支持的
     * GROUP_CONCAT 聚合。
     *
     * <p>M0 升级新增的 {@code is_official} / {@code featured_rank} / {@code category} /
     * {@code created_by_kind} 4 列在此不读取(record 上没有对应字段);
     * 它们由 {@code setOfficial} / {@code setFeaturedRank} / {@code setCategory}
     * 等单字段 update 操作维护 —— 与 {@link MarketSkill#from(ResultSet)} 的处理一致。
     *
     * @param rs 已定位到当前行的 {@link ResultSet}
     * @return 填充后的 {@link MarketKnowledgeRecord} 实例(tags 字段为 null,
     *         待 listPaged 后处理填充)
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
        boolean annPresent;
        try {
            rs.findColumn("announcement_title");
            annPresent = true;
        } catch (java.sql.SQLException notFound) {
            annPresent = false;
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
                null);
    }

    /**
     * M3+ R4 (AT2 follow-up, change 1) — copy-helper:返回一个仅 {@code tags}
     * 字段不同的新 record,其余 11 个字段原样保留。
     * {@code DefaultKnowledgeMarketService.listPaged} 用它把批量 tag SELECT
     * 的结果写回页面行(空 tag 时传 {@code List.of()},不允许 null)。
     */
    public MarketKnowledgeRecord withTags(List<String> newTags) {
        return new MarketKnowledgeRecord(
                id, username, name, description, status,
                submittedAt, reviewedAt, reviewedBy, reviewComment,
                announcementTitle, announcementBody,
                newTags == null ? List.of() : List.copyOf(newTags));
    }
}
