package cn.wubo.spring.ai.loom.agent.model;

/**
 * 用户提交 Skill 到市场（移除 version；P13 直接 APPROVED 无审批）
 */
public record MarketSkillSubmitRequest(
        String name,
        String description,
        String content
) {
}
