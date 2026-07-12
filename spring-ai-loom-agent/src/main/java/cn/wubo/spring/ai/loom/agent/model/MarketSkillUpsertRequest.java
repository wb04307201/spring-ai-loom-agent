package cn.wubo.spring.ai.loom.agent.model;

/** admin 直接新增 / 修改 Skill（status=APPROVED） */
public record MarketSkillUpsertRequest(
        String name,
        String description,
        String content,
        String version,
        String status
) {
}
