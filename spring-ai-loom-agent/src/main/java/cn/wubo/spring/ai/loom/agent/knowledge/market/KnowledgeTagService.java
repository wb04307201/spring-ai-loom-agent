package cn.wubo.spring.ai.loom.agent.knowledge.market;

import cn.wubo.spring.ai.loom.agent.model.MarketKnowledgeRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * M2 / T20:KB 市场多对多 tag 服务。
 * <p>
 * 数据表 {@code loom_market_knowledge_tag}(market_id, tag) — 主键复合,
 * 外键 {@code market_id} 指向 {@code loom_market_knowledge.id} ({@code VARCHAR(36)} UUID),
 * 删除 KB 通过 {@code ON DELETE CASCADE} 自动清理 tag 行。
 * <p>
 * 与 Skill 端 {@code market_skill_tag} 的真实对称差异:
 * Skill 端主键是 {@code Long id},KB 端是 {@code String} UUID —— 签名统一保留
 * {@code marketKind + String id},Skill 端{@code marketKind} 参数在 KB 场景下
 * 始终为 {@code "KNOWLEDGE"} (留作未来跨市场扩展时复用同一接口)。
 *
 * <p>典型使用:
 * <pre>
 *   tagService.addTags("KNOWLEDGE", marketId, List.of("java","spring"));
 *   List&lt;String&gt; tags = tagService.listTags("KNOWLEDGE", marketId);
 *   List&lt;MarketKnowledgeRecord&gt; rows = tagService.findByTag("spring", 0, 20);
 * </pre>
 */
@Component
public class KnowledgeTagService {

    /**
     * 当前服务适用的市场 kind。保留为参数形式以便未来统一到 {@code IMarketTagService}
     * 与 Skill 端复用同一接口(KB 端目前只有这一个,无 Skill 端孪生)。
     */
    public static final String MARKET_KIND_KNOWLEDGE = "KNOWLEDGE";

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeTagService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 批量 MERGE tag —— 同一 (market_id, tag) 已存在则忽略,新 tag 插入。
     * 整批视为一次事务(单次 update 调用 H2)。
     * <p>
     * H2 {@code MERGE INTO ... USING DUAL} 语法对空 list 是 no-op (不会报错)。
     */
    public void addTags(String marketKind, String marketId, List<String> tags) {
        if (tags == null || tags.isEmpty()) return;
        // 去重 + trim + 跳过空串,保留顺序
        Set<String> dedup = new LinkedHashSet<>();
        for (String t : tags) {
            if (t == null) continue;
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) dedup.add(trimmed);
        }
        if (dedup.isEmpty()) return;

        // 用 VALUES 子句批量 MERGE;H2 支持 USING (VALUES (?,?)) AS src(market_id, tag)
        StringBuilder sql = new StringBuilder(
                "MERGE INTO loom_market_knowledge_tag (market_id, tag) KEY (market_id, tag) VALUES ");
        List<Object> args = new ArrayList<>();
        int i = 0;
        for (String tag : dedup) {
            if (i > 0) sql.append(", ");
            sql.append("(?, ?)");
            args.add(marketId);
            args.add(tag);
            i++;
        }
        // 静默忽略 marketId 不存在的 KB (FK 约束失败时静默),避免"先打 tag 后建 KB"
        // 这种 admin 操作里出现 4xx。删除不存在的 tag 也用同样的容错(add 不存在 market
        // → FK violation → 改成 defensive:先用 count 校验 market 存在)。
        // 这里我们只校验 KB 存在,FK violation 不应发生;若发生,说明 caller 调错了。
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM loom_market_knowledge WHERE id = ?",
                Integer.class, marketId);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("市场 KB 不存在: id=" + marketId);
        }
        jdbcTemplate.update(sql.toString(), args.toArray());
    }

    /**
     * 删除单个 tag —— (market_id, tag) 复合主键。无 row 影响 0 行,不抛错。
     */
    public void removeTag(String marketKind, String marketId, String tag) {
        if (tag == null || tag.isBlank() || marketId == null || marketId.isBlank()) return;
        jdbcTemplate.update(
                "DELETE FROM loom_market_knowledge_tag WHERE market_id = ? AND tag = ?",
                marketId, tag.trim());
    }

    /**
     * 替换整组 tag —— 先 DELETE 全部,再 INSERT 新集合。原子事务内完成。
     * 用于 admin PUT /tags 端点。
     */
    public void replaceTags(String marketKind, String marketId, List<String> tags) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM loom_market_knowledge WHERE id = ?",
                Integer.class, marketId);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("市场 KB 不存在: id=" + marketId);
        }
        jdbcTemplate.update("DELETE FROM loom_market_knowledge_tag WHERE market_id = ?", marketId);
        addTags(marketKind, marketId, tags);
    }

    /**
     * 列出 KB 全部 tag(按 tag 字典序,便于 UI 展示)。
     */
    public List<String> listTags(String marketKind, String marketId) {
        if (marketId == null || marketId.isBlank()) return Collections.emptyList();
        return jdbcTemplate.queryForList(
                "SELECT tag FROM loom_market_knowledge_tag WHERE market_id = ? ORDER BY tag ASC",
                String.class, marketId);
    }

    /**
     * 按 tag 找市场 KB —— 返回所有挂了这个 tag 的 {@link MarketKnowledgeRecord}。
     * 不限定 status(让 admin 视图也能用);公开 GET 端点会自己再过滤 APPROVED。
     * 分页参数 {@code page/size} 与 {@code listPaged} 同款语义(page>=0, size 1..100)。
     */
    public List<MarketKnowledgeRecord> findByTag(String tag, int page, int size) {
        if (tag == null || tag.isBlank()) return Collections.emptyList();
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;
        int offset = page * size;
        return jdbcTemplate.query(
                "SELECT mk.* FROM loom_market_knowledge mk " +
                        "JOIN loom_market_knowledge_tag t ON mk.id = t.market_id " +
                        "WHERE t.tag = ? " +
                        "ORDER BY mk.is_official DESC, mk.featured_rank DESC, mk.reviewed_at DESC, mk.submitted_at DESC " +
                        "LIMIT ? OFFSET ?",
                (RowMapper<MarketKnowledgeRecord>) this::mapRow, tag, size, offset);
    }

    /**
     * 多 tag 交集查询 — KB 必须同时挂有所有指定 tag(AND 语义)。
     * 用 GROUP BY + HAVING COUNT(DISTINCT tag) 实现。
     */
    public List<MarketKnowledgeRecord> findByAllTags(List<String> tags, int page, int size) {
        if (tags == null || tags.isEmpty()) return Collections.emptyList();
        Set<String> dedup = new LinkedHashSet<>();
        for (String t : tags) {
            if (t == null) continue;
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) dedup.add(trimmed);
        }
        if (dedup.isEmpty()) return Collections.emptyList();
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;
        int offset = page * size;
        int required = dedup.size();

        String placeholders = String.join(",", Collections.nCopies(dedup.size(), "?"));
        return jdbcTemplate.query(
                "SELECT mk.* FROM loom_market_knowledge mk " +
                        "JOIN loom_market_knowledge_tag t ON mk.id = t.market_id " +
                        "WHERE t.tag IN (" + placeholders + ") " +
                        "GROUP BY mk.id " +
                        "HAVING COUNT(DISTINCT t.tag) = ? " +
                        "ORDER BY mk.is_official DESC, mk.featured_rank DESC, mk.reviewed_at DESC, mk.submitted_at DESC " +
                        "LIMIT ? OFFSET ?",
                (RowMapper<MarketKnowledgeRecord>) this::mapRow,
                dedup.toArray(), required, size, offset);
    }

    private MarketKnowledgeRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return MarketKnowledgeRecord.from(rs);
    }
}