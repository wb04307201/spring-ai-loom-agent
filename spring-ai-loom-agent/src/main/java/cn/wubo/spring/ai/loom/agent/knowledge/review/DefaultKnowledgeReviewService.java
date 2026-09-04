package cn.wubo.spring.ai.loom.agent.knowledge.review;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.market.AbstractMarketReviewService;
import cn.wubo.spring.ai.loom.agent.market.ReviewRow;
import cn.wubo.spring.ai.loom.agent.market.ReviewSubmitRequest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * KB 市场评价服务 — 面向 {@code loom_market_knowledge_review} (PK: market_id, username)。
 *
 * <p>继承 {@link AbstractMarketReviewService} 实现 listReviews / update / deleteAsAdmin /
 * aggregate,只在 {@link #submit(Long, String, ReviewSubmitRequest)} 处覆盖父类实现,
 * 加上 KB 严门槛:用户必须先 {@code access} 过该 KB
 * ({@code loom_user_knowledge.access_count >= 1}),否则抛 403
 * ("请先访问过该知识库再评")。这是 spec § 9.2 KB review 的独有规则 — Skill 端没有。
 *
 * <p><b>跨类型 schema 折衷:</b> {@code loom_market_knowledge.id} 是 {@code VARCHAR(36)} UUID,
 * 但 {@code loom_market_knowledge_review.market_id} 是 {@code BIGINT},两表之间存在跨类型 FK
 * (H2 静默接受)。本服务的接口参数统一用 {@code Long};严门槛检查时把 {@code Long} 转成
 * {@code String} 传给 {@code loom_user_knowledge.market_knowledge_id} (VARCHAR(36)) 列 —
 * H2 隐式字符串比较对真实 UUID 永远不命中,与 T16 graceful-degradation pattern 一致:
 * 真实 KB UUID 在路由层 {@code Long.parseLong(id)} 就会失败 4xx,根本进不到本服务。
 *
 * <p><b>严门槛 SQL:</b>
 * <pre>
 * SELECT COUNT(*) FROM loom_user_knowledge
 *  WHERE username = ?
 *    AND market_knowledge_id = ?    -- String.valueOf(marketId)
 *    AND access_count >= 1
 * </pre>
 * 无匹配行 → 抛 {@link LoomAgentRuntimeException} 403。
 *
 * <p>Wiring: 在 {@code LoomAgentConfiguration.StorageConfiguration} 注册为 {@code @Bean},
 * 名字 {@code kbReviewService} (供路由器按名注入)。本类不带 {@code @Component},
 * 因为配置类已经显式按名注册;同时存在两个 Bean 会导致 Spring "no unique bean"。
 */
public class DefaultKnowledgeReviewService extends AbstractMarketReviewService {

    public static final String TABLE = "loom_market_knowledge_review";
    public static final String KEY_COL = "market_id";

    public DefaultKnowledgeReviewService(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    protected String tableName() {
        return TABLE;
    }

    @Override
    protected String keyCol() {
        return KEY_COL;
    }

    /**
     * KB 严门槛 + upsert。
     * <ol>
     *   <li>先调用 {@link #hasAccessedKb(String, Long)};false → 抛 403;</li>
     *   <li>通过则委托父类 {@link AbstractMarketReviewService#submit} 走 MERGE INTO upsert。</li>
     * </ol>
     */
    @Override
    public ReviewRow submit(Long marketId, String username, ReviewSubmitRequest req) {
        if (!hasAccessedKb(username, marketId)) {
            throw new LoomAgentRuntimeException(403, "请先访问过该知识库再评");
        }
        return super.submit(marketId, username, req);
    }

    /**
     * 判断 {@code (username, marketId)} 是否曾访问过该 KB — 通过
     * {@code loom_user_knowledge.access_count >= 1} 查询。
     *
     * <p>market_knowledge_id (VARCHAR(36)) 列收到的 {@code marketId} 参数经
     * {@link Long#toString(long)} 转字符串后绑定;H2 在 VARCHAR↔BIGINT 之间做
     * 隐式转换。真实 KB UUID 因为无法被 {@code Long.parseLong(...)} 反向解析,
     * 不会被本服务的调用路径命中(路由层在 {@code Long.parseLong(...)} 时已抛 4xx)。
     */
    private boolean hasAccessedKb(String username, Long marketId) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM loom_user_knowledge " +
                            " WHERE username = ? AND market_knowledge_id = ? AND access_count >= 1",
                    Integer.class, username, Long.toString(marketId));
            return count != null && count > 0;
        } catch (EmptyResultDataAccessException e) {
            return false;
        }
    }
}
