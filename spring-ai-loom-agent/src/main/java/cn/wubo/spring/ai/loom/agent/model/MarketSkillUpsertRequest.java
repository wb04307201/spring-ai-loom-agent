package cn.wubo.spring.ai.loom.agent.model;

/** admin 直接新增 / 修改 Skill（移除 version） */
public record MarketSkillUpsertRequest(
 String name,
 String description,
 String content,
 String status
) {
}
