package cn.wubo.spring.ai.loom.agent.model;

/**
 * 创建 / 更新 user_skill（USER_CREATED）请求体。
 * 端点 {@code POST /spring/ai/loom/skill/upsert} 与 chat 工具 {@code createOrUpdateSkill} 共用。
 */
public record UpsertSkillRequest(
        String name,
        String description,
        String content
) {
}
