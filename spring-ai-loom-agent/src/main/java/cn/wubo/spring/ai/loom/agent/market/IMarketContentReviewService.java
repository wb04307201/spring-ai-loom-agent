package cn.wubo.spring.ai.loom.agent.market;

/**
 * 市场内容 review 接口(M3+ T1.4 / T1.7 gap 修复 — R2)。
 *
 * <p>类型参数 {@code <K>} 表示 market id 形态,允许两端用各自的主键类型
 * (镜像 T1.5 的 {@link IMarketContentStatsService} 泛型化模式):
 * <ul>
 *   <li>{@code Long} — skill review({@code market_skill_review.market_skill_id}
 *       仍是 {@code BIGINT},沿用历史 Long 路径)</li>
 *   <li>{@code String} — KB review({@code loom_market_knowledge_review.market_id}
 *       自 T1.1 schema 迁移后是 {@code VARCHAR(36)} UUID);String 路径<b>走真路径</b>
 *       —— 真实 KB UUID 可以端到端流入 review 表,<b>不再有
 *       {@code Long.parseLong} + NFE → 404 的 graceful-degradation 孪生方法</b></li>
 * </ul>
 *
 * <p>对应实现:
 * <ul>
 *   <li>{@link cn.wubo.spring.ai.loom.agent.skill.review.DefaultSkillReviewService}
 *       extends {@code AbstractMarketReviewService<Long>}</li>
 *   <li>{@link cn.wubo.spring.ai.loom.agent.knowledge.review.DefaultKnowledgeReviewService}
 *       extends {@code AbstractMarketReviewService<String>}</li>
 * </ul>
 *
 * @param <K> market id 类型:Long for skill,String (UUID) for KB
 */
public interface IMarketContentReviewService<K> {

    Page<ReviewRow<K>> listReviews(K marketId, int page, int size);

    ReviewRow<K> submit(K marketId, String username, ReviewSubmitRequest req);

    ReviewRow<K> update(K marketId, String username, ReviewUpdateRequest req);

    void deleteAsAdmin(K marketId, String username);

    RatingAggregate aggregate(K marketId);
}
