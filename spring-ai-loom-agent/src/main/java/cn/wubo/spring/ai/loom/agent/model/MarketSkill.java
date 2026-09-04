package cn.wubo.spring.ai.loom.agent.model;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Skill 市场记录。
 * 移除 version 字段；唯一约束改为 {@code (author, name)}。
 * status: 起直接 APPROVED（无审批流）。
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
        String reviewComment
) {
    /**
     * 标识常量
     */
    public static final String STATUS_APPROVED = "APPROVED";

    /**
     * ResultSet 工厂方法 — 读取 market_skill 表的 10 个核心字段。
     * M0 升级新增的 {@code is_official} / {@code featured_rank} / {@code category} /
     * {@code created_by_kind} 4 列在此不读取（不影响 {@link #id()} /
     * {@link #name()} 等基础字段的映射；这些列由 {@code setOfficial} /
     * {@code setFeaturedRank} / {@code setCategory} 等单字段 update 操作维护）。
     *
     * @param rs 已定位到当前行的 {@link ResultSet}
     * @return 填充后的 {@link MarketSkill} 实例
     */
    public static MarketSkill from(ResultSet rs) throws SQLException {
        Timestamp submittedAt = rs.getTimestamp("submitted_at");
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
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
                rs.getString("review_comment")
        );
    }
}
