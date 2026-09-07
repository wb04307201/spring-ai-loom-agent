package cn.wubo.spring.ai.loom.agent.vectorstore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ApplicationReadyEvent 时把 loom_vector_store 全量 hydrate 进
 * {@link H2JVectorStore} 内存 HNSW 图(spec D7)。
 *
 * <p>选 ApplicationReadyEvent 的原因:Flyway 在 context refresh 期间建表,
 * bean 构造期查库会有竞态;ready 事件后表必然存在(沿用 ScheduleRestoreListener 先例)。
 * 用户用自带 VectorStore 替换默认实现时(instanceof 短路,spec §6 末行),
 * 本监听器不做任何事;表不存在(Flyway 被禁)时 ERROR + 空索引启动,不 fail-fast。
 */
public class H2VectorStoreReloader {

    private static final Logger logger = LoggerFactory.getLogger(H2VectorStoreReloader.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    public H2VectorStoreReloader(JdbcTemplate jdbcTemplate, ObjectProvider<VectorStore> vectorStoreProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStoreProvider = vectorStoreProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        hydrateNow();
    }

    public void hydrateNow() {
        VectorStore store = vectorStoreProvider.getIfAvailable();
        if (!(store instanceof H2JVectorStore h2Store)) {
            logger.debug("[H2Vector] active VectorStore is {} — reloader skipped",
                    store == null ? "absent" : store.getClass().getSimpleName());
            return;
        }
        try {
            List<H2VectorRow> rows = jdbcTemplate.query(
                    "SELECT document_id, content, metadata_json, embedding, dim, score FROM loom_vector_store",
                    (rs, n) -> new H2VectorRow(
                            rs.getString("document_id"),
                            rs.getString("content"),
                            rs.getString("metadata_json"),
                            rs.getBytes("embedding"),
                            rs.getInt("dim"),
                            rs.getObject("score") == null ? null : ((Number) rs.getObject("score")).doubleValue()));
            logger.info("[H2Vector] hydrating {} row(s) from loom_vector_store", rows.size());
            h2Store.hydrate(rows);
        } catch (Exception e) {
            // 表不存在(Flyway 未跑/被禁)或 DB 故障:空索引启动,不 fail-fast(spec §6)
            logger.error("[H2Vector] hydrate failed — starting with EMPTY vector index; "
                    + "RAG search will return no results until next successful restart", e);
        }
    }
}
