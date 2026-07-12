package cn.wubo.spring.ai.loom.agent.model;

import java.time.LocalDateTime;

/**
 * Skill 市场记录。
 * 唯一约束：{@code (author, name, version)}。
 * status: PENDING / APPROVED / REJECTED
 */
public record MarketSkill(
        Long id,
        String name,
        String description,
        String content,
        String version,
        String author,
        String status,
        LocalDateTime submittedAt,
        LocalDateTime reviewedAt,
        String reviewedBy,
        String reviewComment
) {
    /** 标识常量 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
}
