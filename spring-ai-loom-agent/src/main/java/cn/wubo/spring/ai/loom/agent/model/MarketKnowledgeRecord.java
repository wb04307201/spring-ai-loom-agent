package cn.wubo.spring.ai.loom.agent.model;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 市场知识库条目。
 * 唯一约束：{@code (username, name)}。
 * status: PENDING / APPROVED / REJECTED
 *
 * <p>注意：{@code loom_market_knowledge.id} 是 {@code VARCHAR(36)}(UUID),
 * 与 {@link MarketSkill} 的 {@code BIGINT id} 是真实的对称差异。
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
        String reviewComment
) {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    /**
     * ResultSet 工厂方法 — 读取 {@code loom_market_knowledge} 表的 9 个核心字段。
     * M0 升级新增的 {@code is_official} / {@code featured_rank} / {@code category} /
     * {@code created_by_kind} 4 列在此不读取(record 上没有对应字段);
     * 它们由 {@code setOfficial} / {@code setFeaturedRank} / {@code setCategory}
     * 等单字段 update 操作维护 —— 与 {@link MarketSkill#from(ResultSet)} 的处理一致。
     *
     * @param rs 已定位到当前行的 {@link ResultSet}
     * @return 填充后的 {@link MarketKnowledgeRecord} 实例
     */
    public static MarketKnowledgeRecord from(ResultSet rs) throws SQLException {
        Timestamp submittedAt = rs.getTimestamp("submitted_at");
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        return new MarketKnowledgeRecord(
                rs.getString("id"),
                rs.getString("username"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("status"),
                submittedAt == null ? null : submittedAt.toLocalDateTime(),
                reviewedAt == null ? null : reviewedAt.toLocalDateTime(),
                rs.getString("reviewed_by"),
                rs.getString("review_comment"));
    }
}
