package cn.wubo.spring.ai.loom.agent.skill.market;

import cn.wubo.spring.ai.loom.agent.model.MarketSkill;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * M4 / T4:Skill 市场多对多 tag 服务 —— 镜像
 * {@link cn.wubo.spring.ai.loom.agent.knowledge.market.KnowledgeTagService}。
 * <p>
 * 数据表 {@code market_skill_tag}(market_skill_id, tag) — 主键复合,
 * 外键 {@code market_skill_id} 指向 {@code market_skill.id} ({@code BIGINT}),
 * 删除 skill 通过 {@code ON DELETE CASCADE} 自动清理 tag 行。
 * <p>
 * 与 KB 端 {@code loom_market_knowledge_tag} 的真实对称差异:
 * Skill 端主键是 {@code Long id}(BIGINT),KB 端是 {@code String} UUID —— 签名统一保留
 * {@code marketKind + id},Skill 端 {@code marketKind} 参数在本场景下始终为
 * {@code "SKILL"} (留作未来跨市场扩展时复用同一接口)。
 *
 * <p>典型使用:
 * <pre>
 *   tagService.addTags("SKILL", marketSkillId, List.of("java","spring"));
 *   List&lt;String&gt; tags = tagService.listTags("SKILL", marketSkillId);
 *   List&lt;MarketSkill&gt; rows = tagService.findByTag("spring", 0, 20);
 * </pre>
 */
@Component
@Transactional
public class SkillTagService {

    /**
     * 当前服务适用的市场 kind。保留为参数形式以便未来统一到 {@code IMarketTagService}
     * 与 KB 端复用同一接口(Skill 端目前只有这一个,与 {@code KnowledgeTagService} 对称)。
     */
    public static final String MARKET_KIND_SKILL = "SKILL";

    private final JdbcTemplate jdbcTemplate;

    public SkillTagService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 批量 MERGE tag —— 同一 (market_skill_id, tag) 已存在则忽略,新 tag 插入。
     * 整批视为一次事务(单次 update 调用 H2)。
     * <p>
     * H2 {@code MERGE INTO ... KEY(...)} 语法对空 list 是 no-op (不会报错)。
     */
    public void addTags(String marketKind, Long marketSkillId, List<String> tags) {
        if (marketSkillId == null || tags == null || tags.isEmpty()) return;
        // 去重 + trim + 跳过空串,保留顺序
        Set<String> dedup = new LinkedHashSet<>();
        for (String t : tags) {
            if (t == null) continue;
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) dedup.add(trimmed);
        }
        if (dedup.isEmpty()) return;

        // 用 VALUES 子句批量 MERGE;H2 支持 KEY (market_skill_id, tag) 复合主键去重
        StringBuilder sql = new StringBuilder(
                "MERGE INTO market_skill_tag (market_skill_id, tag) KEY (market_skill_id, tag) VALUES ");
        List<Object> args = new ArrayList<>();
        int i = 0;
        for (String tag : dedup) {
            if (i > 0) sql.append(", ");
            sql.append("(?, ?)");
            args.add(marketSkillId);
            args.add(tag);
            i++;
        }
        // defensive:先用 count 校验 skill 存在,避免 FK violation 泄漏成 5xx。
        // 不存在 → IllegalArgumentException(router 翻译成 404),与 KB 端一致。
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_skill WHERE id = ?",
                Integer.class, marketSkillId);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("市场 Skill 不存在: id=" + marketSkillId);
        }
        jdbcTemplate.update(sql.toString(), args.toArray());
    }

    /**
     * 删除单个 tag —— (market_skill_id, tag) 复合主键。无 row 影响 0 行,不抛错。
     */
    public void removeTag(String marketKind, Long marketSkillId, String tag) {
        if (tag == null || tag.isBlank() || marketSkillId == null) return;
        jdbcTemplate.update(
                "DELETE FROM market_skill_tag WHERE market_skill_id = ? AND tag = ?",
                marketSkillId, tag.trim());
    }

    /**
     * 替换整组 tag —— 先 DELETE 全部,再 INSERT 新集合。原子事务内完成。
     * 用于 admin PUT /tags 端点。
     * <p>
     * {@link Transactional} 是必须的:DELETE 是单独的 {@code jdbcTemplate.update(...)} 调用,
     * 没有事务边界时如果 {@link #addTags} 后续失败 (FK 违反、字段超长等),
     * DELETE 已经 auto-commit,skill 的 tag 会被静默清零。
     * 加 {@code @Transactional} 后,任何一步失败都会回滚到调用前的状态 —
     * admin 看到的错误是真正的失败,而不是表面"OK 但 skill 失去了所有 tag"。
     */
    @Transactional
    public void replaceTags(String marketKind, Long marketSkillId, List<String> tags) {
        if (marketSkillId == null) {
            throw new IllegalArgumentException("市场 Skill 不存在: id=null");
        }
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_skill WHERE id = ?",
                Integer.class, marketSkillId);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("市场 Skill 不存在: id=" + marketSkillId);
        }
        jdbcTemplate.update("DELETE FROM market_skill_tag WHERE market_skill_id = ?", marketSkillId);
        addTags(marketKind, marketSkillId, tags);
    }

    /**
     * 列出 skill 全部 tag(按 tag 字典序,便于 UI 展示)。
     */
    public List<String> listTags(String marketKind, Long marketSkillId) {
        if (marketSkillId == null) return Collections.emptyList();
        return jdbcTemplate.queryForList(
                "SELECT tag FROM market_skill_tag WHERE market_skill_id = ? ORDER BY tag ASC",
                String.class, marketSkillId);
    }

    /**
     * 按 tag 找市场 skill —— 返回所有挂了这个 tag 的 {@link MarketSkill}。
     * 不限定 status(让 admin 视图也能用);公开 GET 端点会自己再过滤 APPROVED。
     * 分页参数 {@code page/size} 与 {@code listPaged} 同款语义(page>=0, size 1..100)。
     * <p>
     * 固定 ORDER BY {@code is_official DESC, featured_rank DESC, reviewed_at DESC,
     * submitted_at DESC} —— {@code sortBy} 在 tag 路径被忽略(plan D11),与 KB 端一致。
     */
    public List<MarketSkill> findByTag(String tag, int page, int size) {
        if (tag == null || tag.isBlank()) return Collections.emptyList();
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;
        int offset = page * size;
        return jdbcTemplate.query(
                "SELECT m.* FROM market_skill m " +
                        "JOIN market_skill_tag t ON m.id = t.market_skill_id " +
                        "WHERE t.tag = ? " +
                        "ORDER BY m.is_official DESC, m.featured_rank DESC, m.reviewed_at DESC, m.submitted_at DESC " +
                        "LIMIT ? OFFSET ?",
                (RowMapper<MarketSkill>) this::mapRow, tag, size, offset);
    }

    /**
     * 多 tag 交集查询 — skill 必须同时挂有所有指定 tag(AND 语义)。
     * 用 GROUP BY + HAVING COUNT(DISTINCT tag) 实现。
     */
    public List<MarketSkill> findByAllTags(List<String> tags, int page, int size) {
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
                "SELECT m.* FROM market_skill m " +
                        "JOIN market_skill_tag t ON m.id = t.market_skill_id " +
                        "WHERE t.tag IN (" + placeholders + ") " +
                        "GROUP BY m.id " +
                        "HAVING COUNT(DISTINCT t.tag) = ? " +
                        "ORDER BY m.is_official DESC, m.featured_rank DESC, m.reviewed_at DESC, m.submitted_at DESC " +
                        "LIMIT ? OFFSET ?",
                (RowMapper<MarketSkill>) this::mapRow,
                withRequired(dedup, required, size, offset));
    }

    /**
     * 组装 findByAllTags 的参数数组:tag 占位符值 + HAVING count + LIMIT + OFFSET。
     */
    private Object[] withRequired(Set<String> dedup, int required, int size, int offset) {
        List<Object> args = new ArrayList<>(dedup);
        args.add(required);
        args.add(size);
        args.add(offset);
        return args.toArray();
    }

    private MarketSkill mapRow(ResultSet rs, int rowNum) throws SQLException {
        return MarketSkill.from(rs);
    }
}
