package cn.wubo.spring.ai.loom.agent.model;

/** 角色授权 Skill 时传 [{marketSkillId, defaultLoaded}, ...] */
public record RoleSkillItem(
 Long marketSkillId,
 Boolean defaultLoaded
) {
}
