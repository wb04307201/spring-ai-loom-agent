package cn.wubo.spring.ai.loom.agent.knowledge.stats;

import cn.wubo.spring.ai.loom.agent.market.AbstractMarketStatsService;
import cn.wubo.spring.ai.loom.agent.market.BatchedCounterService;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Stats for Knowledge-Base marketplace content.
 * <p>
 * Backing table: {@code loom_market_knowledge_stats}
 * <pre>
 *   market_id        BIGINT PRIMARY KEY,   -- FK -> loom_market_knowledge(id)
 *   search_count     BIGINT NOT NULL DEFAULT 0,
 *   last_searched_at TIMESTAMP
 * </pre>
 *
 * <p><b>Schema note:</b> the {@code loom_market_knowledge_stats.market_id}
 * column is {@code BIGINT} while the parent table
 * {@code loom_market_knowledge.id} is {@code VARCHAR(36)} UUID. H2 silently
 * accepts the cross-type FK declaration without enforcing it (verified by
 * the {@code MarketSchemaTest} integration suite). We follow the column
 * type as declared and pass {@link Long} ids — even though the rest of the
 * KB path uses String UUIDs. {@code DefaultKnowledgeTool.searchKnowledge}
 * converts the {@code String knowledgeId} via {@code Long.parseLong} so the
 * stats lookup uses the same numeric id the FK column expects.</p>
 *
 * <p>This service owns exactly ONE counter column ({@code search_count}) —
 * the {@code kind} parameter on
 * {@link cn.wubo.spring.ai.loom.agent.market.IMarketContentStatsService#incrementStat}
 * is documentation-only; callers should pass {@code "SEARCH"} for clarity in
 * logs.</p>
 *
 * <p>Wiring: registered as a Spring {@code @Bean} in
 * {@code LoomAgentConfiguration.StorageConfiguration}.</p>
 */
public class DefaultKnowledgeStatsService extends AbstractMarketStatsService<Long> {

    public static final String TABLE = "loom_market_knowledge_stats";
    public static final String KEY_COL = "market_id";
    public static final String COUNT_COL = "search_count";
    public static final String LAST_COL = "last_searched_at";

    public DefaultKnowledgeStatsService(JdbcTemplate jdbc, BatchedCounterService batchedCounterService) {
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