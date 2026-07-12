package cn.wubo.spring.ai.loom.agent.model;

/** 用户提交 Skill 到市场（status=PENDING） */
public record MarketSkillSubmitRequest(
        String name,
        String description,
        String content,
        String version
) {
}
