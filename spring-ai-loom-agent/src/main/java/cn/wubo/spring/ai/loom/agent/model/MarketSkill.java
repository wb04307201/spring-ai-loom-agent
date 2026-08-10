package cn.wubo.spring.ai.loom.agent.model;

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
}
