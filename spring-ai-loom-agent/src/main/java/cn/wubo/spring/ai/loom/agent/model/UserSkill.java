package cn.wubo.spring.ai.loom.agent.model;

import java.time.LocalDateTime;

/**
 * 用户本地 Skill 实例。
 * source: USER_CREATED / MARKET_PULLED / ROLE_GRANTED
 * locked=true 表示 ROLE_GRANTED，用户不能改不能删。
 */
public record UserSkill(
 Long id,
 String username,
 String name,
 String description,
 String content,
 String source,
 Long marketSkillId,
 boolean defaultLoaded,
 boolean locked,
 LocalDateTime createdAt,
 LocalDateTime updatedAt
) {
 public static final String SOURCE_USER_CREATED = "USER_CREATED";
 public static final String SOURCE_MARKET_PULLED = "MARKET_PULLED";
 public static final String SOURCE_ROLE_GRANTED = "ROLE_GRANTED";
}
