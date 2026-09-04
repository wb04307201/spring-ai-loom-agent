package cn.wubo.spring.ai.loom.agent.skill.stats;

import cn.wubo.spring.ai.loom.agent.market.AbstractMarketStatsService;
import cn.wubo.spring.ai.loom.agent.market.BatchedCounterService;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Stats for Skill marketplace content.
 * <p>
 * Backing table: {@code market_skill_stats}
 * <pre>
 *   market_skill_id BIGINT PRIMARY KEY,   -- FK -> market_skill(id)
 *   pull_count      BIGINT NOT NULL DEFAULT 0,
 *   last_pulled_at  TIMESTAMP
 * </pre>
 *
 * <p>This service owns exactly ONE counter column ({@code pull_count}) —
 * the {@code kind} parameter on
 * {@link cn.wubo.spring.ai.loom.agent.market.IMarketContentStatsService#incrementStat}
 * is documentation-only; callers should pass {@code "PULL"} for clarity in
 * logs. {@link AbstractMarketStatsService} ignores the value and routes the
 * delta to {@code pull_count} unconditionally.</p>
 *
 * <p>Wiring: registered as a Spring {@code @Bean} in
 * {@code LoomAgentConfiguration.StorageConfiguration}. Spring picks this up
 * automatically; consumers don't need to touch the bean name.</p>
 */
public class DefaultSkillStatsService extends AbstractMarketStatsService<Long> {

    public static final String TABLE = "market_skill_stats";
    public static final String KEY_COL = "market_skill_id";
    public static final String COUNT_COL = "pull_count";
    public static final String LAST_COL = "last_pulled_at";

    public DefaultSkillStatsService(JdbcTemplate jdbc, BatchedCounterService batchedCounterService) {
        super(jdbc, batchedCounterService);
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
    protected String countCol() {
        return COUNT_COL;
    }

    @Override
    protected String lastCol() {
        return LAST_COL;
    }
}