package cn.wubo.spring.ai.loom.agent.model;

/**
 * 修改 user_skill 字段（按权限矩阵校验）
 */
public record UserSkillPatchRequest(
        String description,
        Boolean defaultLoaded
) {
}
