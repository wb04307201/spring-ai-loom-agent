package cn.wubo.spring.ai.loom.agent.model;

/**
 * 角色授权知识库时传 [{marketKnowledgeId, defaultEnabled}, ...]
 */
public record RoleKnowledgeItem(
        String marketKnowledgeId,
        Boolean defaultEnabled
) {
}
