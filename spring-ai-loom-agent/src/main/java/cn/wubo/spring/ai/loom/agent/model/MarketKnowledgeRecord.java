package cn.wubo.spring.ai.loom.agent.model;

import java.time.LocalDateTime;

/**
 * 市场知识库条目。
 * 唯一约束：{@code (username, name)}。
 * status: PENDING / APPROVED / REJECTED
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
}
