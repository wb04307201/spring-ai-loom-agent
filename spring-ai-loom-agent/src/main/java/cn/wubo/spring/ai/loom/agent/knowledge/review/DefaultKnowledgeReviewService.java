package cn.wubo.spring.ai.loom.agent.knowledge.review;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.market.AbstractMarketReviewService;
import cn.wubo.spring.ai.loom.agent.market.ReviewRow;
import cn.wubo.spring.ai.loom.agent.market.ReviewSubmitRequest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * KB 市场评价服务 — 面向 {@code loom_market_knowledge_review} (PK: market_id, username)。
 *
 * <p>继承 {@link AbstractMarketReviewService} 实现 listReviews / update / deleteAsAdmin /
 * aggregate,只在 {@link #submit(String, String, ReviewSubmitRequest)} 处覆盖父类实现,
 * 加上 KB 严门槛:用户必须先 {@code access} 过该 KB
 * ({@code loom_user_knowledge.access_count >= 1}),否则抛 403
 * ("请先访问过该知识库再评")。这是 spec § 9.2 KB review 的独有规则 — Skill 端没有。
 *
 * <p><b>真 UUID 路径(M3+ T1.7 gap 修复 — R2):</b>类型参数 pin 到 {@code String}。
 * T1.1 schema 迁移已把 {@code loom_market_knowledge_review.market_id} 从 {@code BIGINT}
 * ALTER 成 {@code VARCHAR(36)},与 {@code loom_market_knowledge.id} (UUID) 类型一致 —
 * 旧的"跨类型 schema 折衷"(Long 参数 + {@code Long.toString} 转换 + 路由层
 * {@code Long.parseLong} graceful-degradation)已彻底移除。真实 KB UUID 经
 * {@code RouterIdParserKnowledge} 原样到达本服务,所有 SQL 绑定都是
 * String ↔ VARCHAR(36) 同类型对齐(不再把 Long 绑到 VARCHAR(36) 列 —
 * 那是 H2 隐式 coercion 下 "MERGE 成功但同值 SELECT 读不回" flaky 的根因)。
 *
 * <p><b>严门槛 SQL:</b>
 * <pre>
 * SELECT COUNT(*) FROM loom_user_knowledge
 *  WHERE username = ?
 *    AND market_knowledge_id = ?    -- String marketId 直接绑定(VARCHAR(36))
 *    AND access_count >= 1
 * </pre>
 * 无匹配行 → 抛 {@link LoomAgentRuntimeException} 403。
 *
 * <p>Wiring: 在 {@code LoomAgentConfiguration.StorageConfiguration} 注册为 {@code @Bean},
 * 名字 {@code kbReviewService} (供路由器按名注入)。本类不带 {@code @Component},
 * 因为配置类已经显式按名注册;同时存在两个 Bean 会导致 Spring "no unique bean"。
 */
public class DefaultKnowledgeReviewService extends AbstractMarketReviewService<String> {

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

    @Override
    protected String readKey(ResultSet rs) throws SQLException {
        // market_id 是 VARCHAR(36) UUID (post-T1.1) — 必须 getString 读回;
        // rs.getLong 会把 UUID coerce 成 0L 丢掉 id 值。
        return rs.getString(1);
    }

    /**
     * KB 严门槛 + upsert。
     * <ol>
     *   <li>先调用 {@link #hasAccessedKb(String, String)};false → 抛 403;</li>
     *   <li>通过则委托父类 {@link AbstractMarketReviewService#submit} 走 MERGE INTO upsert。</li>
     * </ol>
     */
    @Override
    public ReviewRow<String> submit(String marketId, String username, ReviewSubmitRequest req) {
        if (!hasAccessedKb(username, marketId)) {
            throw new LoomAgentRuntimeException(403, "请先访问过该知识库再评");
        }
        return super.submit(marketId, username, req);
    }

    /**
     * 判断 {@code (username, marketId)} 是否曾访问过该 KB — 通过
     * {@code loom_user_knowledge.access_count >= 1} 查询。
     *
     * <p>{@code market_knowledge_id} (VARCHAR(36)) 列直接绑定 String marketId —
     * 与列类型对齐,无任何 {@code Long.toString} / {@code Long.parseLong} 转换
     * (旧转换在真 UUID 下永远不命中,已随 T1.7 gap 修复移除)。
     */
    private boolean hasAccessedKb(String username, String marketId) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM loom_user_knowledge " +
                            " WHERE username = ? AND market_knowledge_id = ? AND access_count >= 1",
                    Integer.class, username, marketId);
            return count != null && count > 0;
        } catch (EmptyResultDataAccessException e) {
            return false;
        }
    }
}
