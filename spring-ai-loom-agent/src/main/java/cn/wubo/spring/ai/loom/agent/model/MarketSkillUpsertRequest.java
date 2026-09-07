package cn.wubo.spring.ai.loom.agent.model;

/**
 * admin 直接新增 / 修改 Skill（移除 version）
 *
 * @deprecated v1 路由已退役（#4），保留 1 个 minor 版本；由
 * {@code cn.wubo.spring.ai.loom.agent.market.MarketCreateRequest} /
 * {@code MarketUpdateRequest} 取代
 */
@Deprecated
public record MarketSkillUpsertRequest(
        String name,
        String description,
        String content,
        String status
) {
}
