package cn.wubo.spring.ai.loom.agent.knowledge;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.market.AbstractMarketAdminService;
import cn.wubo.spring.ai.loom.agent.market.MarketContentStatus;
import cn.wubo.spring.ai.loom.agent.market.MarketCreateRequest;
import cn.wubo.spring.ai.loom.agent.market.MarketUpdateRequest;
import cn.wubo.spring.ai.loom.agent.model.KnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.model.MarketKnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 知识库市场服务 — 同时实现两套契约(与 {@code DefaultSkillMarketService} 镜像):
 * <ul>
 *   <li>M0 重构后的 {@link AbstractMarketAdminService} (admin CRUD / 审批流)</li>
 *   <li>旧的 {@link IKnowledgeMarketService} (用户侧浏览 / 提交 / 拉取 / 撤回)
 *       — 为 {@code LoomAgentConfiguration} 中已有的路由器 bean 提供向后兼容</li>
 * </ul>
 *
 * <p><b>与 Skill 端的真实对称差异 —— 主键类型</b>:
 * {@code loom_market_knowledge.id} 是 {@code VARCHAR(36)} (UUID),而
 * {@code market_skill.id} 是 {@code BIGINT}。{@link AbstractMarketAdminService} /
 * {@link cn.wubo.spring.ai.loom.agent.market.IMarketContentAdminService} 的
 * admin 方法签名统一使用 {@code Long id},无法表达 UUID 主键。
 *
 * <p>处理方式:本类为每个 id 相关的 admin 操作提供 <b>String 主键孪生方法</b>
 * ({@link #approve(String, String)} / {@link #reject(String, String, String)} /
 * {@link #setOfficial(String, boolean, String)} / {@link #setFeaturedRank(String, int, String)} /
 * {@link #setCategory(String, String, String)} / {@link #update(String, MarketUpdateRequest)} /
 * {@link #delete(String)}),这些才是 KB 端的<b>正规入口</b>;
 * 继承自基类的 {@code Long} 重载一律抛
 * {@link UnsupportedOperationException} 并指向对应的 String 版本,避免
 * 「把 Long 强转成字符串后静默匹配不到任何行」的隐性错误。
 * 与 id 无关的基类能力({@code listPaged} / {@code search} / 过滤与分页)照常复用。
 *
 * <p>{@code <MarketKnowledgeRecord, Void, Void>} 中 {@code U} / {@code R} 不参与具体方法签名,
 * 故用 {@link Void} 占位(review 类尚未存在,见 T17)。
 */
@Component
public class DefaultKnowledgeMarketService
        extends AbstractMarketAdminService<MarketKnowledgeRecord, Void, Void>
        implements IKnowledgeMarketService {

    private final JdbcTemplate jdbcTemplate;
    private final IKnowledge knowledge;
    private final IUser user;

    public DefaultKnowledgeMarketService(JdbcTemplate jdbcTemplate, IKnowledge knowledge, IUser user) {
        super(jdbcTemplate);
        this.jdbcTemplate = jdbcTemplate;
        this.knowledge = knowledge;
        this.user = user;
    }

    private MarketKnowledgeRecord mapMarketKnowledgeRecord(ResultSet rs, int rowNum) throws SQLException {
        return MarketKnowledgeRecord.from(rs);
    }

    /* ===== AbstractMarketAdminService hook ===== */

    @Override
    protected String tableName() {
        return "loom_market_knowledge";
    }

    @Override
    protected RowMapper<MarketKnowledgeRecord> rowMapper() {
        return (rs, n) -> MarketKnowledgeRecord.from(rs);
    }

    /**
     * KB 主键是 {@code VARCHAR(36)} UUID,无法表示为 {@link Long}。
     * 基类内部从不调用本方法(仅作为子类自用 hook 声明),故此处显式拒绝,
     * 由 {@link #extractStringId(MarketKnowledgeRecord)} 提供真实实现。
     */
    @Override
    protected Long extractId(MarketKnowledgeRecord entry) {
        throw new UnsupportedOperationException(
                "loom_market_knowledge.id 是 VARCHAR(36) UUID,请使用 extractStringId(...)");
    }

    /**
     * KB 端的真实 id 抽取入口。
     */
    protected String extractStringId(MarketKnowledgeRecord entry) {
        return entry == null ? null : entry.id();
    }

    @Override
    protected MarketContentStatus currentStatusImpl(MarketKnowledgeRecord entry) {
        return MarketContentStatus.from(entry.status());
    }

    /* ===== admin CRUD / 审批流 —— String 主键(KB 正规入口) ===== */

    /**
     * user 直接创建 → 落 PENDING(admin 之后调 {@link #approve(String, String)})。
     * 注意:{@code loom_market_knowledge} 没有 {@code content} 列,
     * {@link MarketCreateRequest#content()} 在 KB 端被忽略。
     */
    @Override
    public MarketKnowledgeRecord create(String author, MarketCreateRequest req) {
        String marketId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO loom_market_knowledge (id, username, name, description, category, status, created_by_kind) " +
                        "VALUES (?, ?, ?, ?, ?, 'PENDING', 'USER')",
                marketId, author, req.name(), req.description(), req.category());
        return getById(marketId);
    }

    /**
     * 动态 SET 更新。{@code content} 列在 KB 端不存在,忽略。
     */
    public MarketKnowledgeRecord update(String id, MarketUpdateRequest req) {
        StringBuilder sql = new StringBuilder("UPDATE loom_market_knowledge SET ");
        List<Object> args = new ArrayList<>();
        List<String> sets = new ArrayList<>();
        if (req.name() != null) {
            sets.add("name=?");
            args.add(req.name());
        }
        if (req.description() != null) {
            sets.add("description=?");
            args.add(req.description());
        }
        if (req.category() != null) {
            sets.add("category=?");
            args.add(req.category());
        }
        if (req.isOfficial() != null) {
            sets.add("is_official=?");
            args.add(req.isOfficial());
        }
        if (req.featuredRank() != null) {
            sets.add("featured_rank=?");
            args.add(req.featuredRank());
        }
        if (sets.isEmpty()) {
            return getById(id);
        }
        sql.append(String.join(", ", sets)).append(" WHERE id=?");
        args.add(id);
        jdbcTemplate.update(sql.toString(), args.toArray());
        return getById(id);
    }

    /**
     * 审核通过:置 {@code status='APPROVED'},记录 reviewer / reviewed_at。
     */
    public MarketKnowledgeRecord approve(String id, String reviewer) {
        jdbcTemplate.update(
                "UPDATE loom_market_knowledge SET status='APPROVED', reviewed_at=CURRENT_TIMESTAMP, reviewed_by=? WHERE id=?",
                reviewer, id);
        return getById(id);
    }

    /**
     * 审核驳回:{@code comment} 必填,否则抛 {@link IllegalArgumentException}。
     */
    public MarketKnowledgeRecord reject(String id, String reviewer, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("reject 必须填评论(comment 必填)");
        }
        jdbcTemplate.update(
                "UPDATE loom_market_knowledge SET status='REJECTED', reviewed_at=CURRENT_TIMESTAMP, reviewed_by=?, review_comment=? WHERE id=?",
                reviewer, comment, id);
        return getById(id);
    }

    public void setOfficial(String id, boolean isOfficial, String reviewer) {
        jdbcTemplate.update("UPDATE loom_market_knowledge SET is_official=? WHERE id=?", isOfficial, id);
    }

    public void setFeaturedRank(String id, int rank, String reviewer) {
        jdbcTemplate.update("UPDATE loom_market_knowledge SET featured_rank=? WHERE id=?", rank, id);
    }

    public void setCategory(String id, String category, String reviewer) {
        jdbcTemplate.update("UPDATE loom_market_knowledge SET category=? WHERE id=?", category, id);
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM loom_market_knowledge WHERE id=?", id);
    }

    /* ===== admin CRUD —— Long 主键重载:KB 不支持,统一指向 String 孪生方法 ===== */

    private static UnsupportedOperationException longIdUnsupported(String stringVariant) {
        return new UnsupportedOperationException(
                "loom_market_knowledge.id 是 VARCHAR(36) UUID,不支持 Long 主键;请改用 " + stringVariant);
    }

    @Override
    public MarketKnowledgeRecord getById(Long id) {
        throw longIdUnsupported("getById(String)");
    }

    @Override
    public MarketKnowledgeRecord update(Long id, MarketUpdateRequest req) {
        throw longIdUnsupported("update(String, MarketUpdateRequest)");
    }

    @Override
    public MarketKnowledgeRecord approve(Long id, String reviewer) {
        throw longIdUnsupported("approve(String, String)");
    }

    @Override
    public MarketKnowledgeRecord reject(Long id, String reviewer, String comment) {
        throw longIdUnsupported("reject(String, String, String)");
    }

    @Override
    public void setOfficial(Long id, boolean isOfficial, String reviewer) {
        throw longIdUnsupported("setOfficial(String, boolean, String)");
    }

    @Override
    public void setFeaturedRank(Long id, int rank, String reviewer) {
        throw longIdUnsupported("setFeaturedRank(String, int, String)");
    }

    @Override
    public void setCategory(Long id, String category, String reviewer) {
        throw longIdUnsupported("setCategory(String, String, String)");
    }

    @Override
    public void delete(Long id) {
        throw longIdUnsupported("delete(String)");
    }

    /* ===== 市场浏览 ===== */

    @Override
    public List<MarketKnowledgeRecord> listApproved(int page, int size) {
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        int offset = (page - 1) * size;
        return jdbcTemplate.query(
                "SELECT * FROM loom_market_knowledge WHERE status = 'APPROVED' ORDER BY reviewed_at DESC, submitted_at DESC LIMIT ? OFFSET ?",
                this::mapMarketKnowledgeRecord, size, offset);
    }

    @Override
    public MarketKnowledgeRecord getById(String marketKnowledgeId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM loom_market_knowledge WHERE id = ?",
                    this::mapMarketKnowledgeRecord, marketKnowledgeId);
        } catch (EmptyResultDataAccessException e) {
            throw new LoomAgentRuntimeException(404, "市场知识库不存在: id=" + marketKnowledgeId);
        }
    }

    @Override
    public List<MarketKnowledgeRecord> listAllForAdmin() {
        // 无审批流，所有条目都是 APPROVED；按上架时间倒序
        return jdbcTemplate.query(
                "SELECT * FROM loom_market_knowledge ORDER BY reviewed_at DESC, submitted_at DESC",
                this::mapMarketKnowledgeRecord);
    }

    /* ===== 用户提交（直接 APPROVED，UPSERT 同一 username+name） ===== */

    @Override
    @Transactional
    public MarketKnowledgeRecord submit(String knowledgeId) {
        String username = UserContextHolder.getCurrentUser();
        if (username == null || username.isBlank()) {
            throw new LoomAgentRuntimeException(401, "未认证用户");
        }

        // 查询知识库并校验所有权
        List<KnowledgeRecord> userKbs = knowledge.list(username);
        KnowledgeRecord kb = userKbs.stream()
                .filter(k -> k.id().equals(knowledgeId))
                .findFirst()
                .orElseThrow(() -> new LoomAgentRuntimeException(404, "知识库不存在或不属于当前用户: " + knowledgeId));

        // UPSERT（同一 username+name 不限 status，只保留一行）
        String existingId = null;
        try {
            existingId = jdbcTemplate.queryForObject(
                    "SELECT id FROM loom_market_knowledge WHERE username = ? AND name = ? LIMIT 1",
                    String.class, username, kb.name());
        } catch (EmptyResultDataAccessException ignored) {
        }

        String marketId;
        if (existingId != null) {
            // 已存在 → UPDATE description + status='APPROVED' + 重置 reviewed_at
            jdbcTemplate.update(
                    "UPDATE loom_market_knowledge SET description = ?, status = 'APPROVED', " +
                            "reviewed_at = CURRENT_TIMESTAMP, reviewed_by = ?, review_comment = NULL WHERE id = ?",
                    kb.description(), username, existingId);
            marketId = existingId;
        } else {
            // 不存在 → INSERT 全新行（直接 APPROVED）
            marketId = UUID.randomUUID().toString();
            jdbcTemplate.update(
                    "INSERT INTO loom_market_knowledge (id, username, name, description, status, reviewed_at, reviewed_by) " +
                            "VALUES (?, ?, ?, ?, 'APPROVED', CURRENT_TIMESTAMP, ?)",
                    marketId, username, kb.name(), kb.description(), username);
        }
        return getById(marketId);
    }

    @Override
    public List<MarketKnowledgeRecord> listMySubmitted(String username) {
        return jdbcTemplate.query(
                "SELECT * FROM loom_market_knowledge WHERE username = ? ORDER BY reviewed_at DESC, submitted_at DESC",
                this::mapMarketKnowledgeRecord, username);
    }

    /* ===== 用户撤回 / admin 删除（统一端点 DELETE，权限内部判断） ===== */

    @Override
    @Transactional
    public void withdraw(String marketKnowledgeId) {
        String username = UserContextHolder.getCurrentUser();
        boolean isAdmin = user.isAdmin(username);
        MarketKnowledgeRecord existing = getById(marketKnowledgeId);
        if (!isAdmin && !existing.username().equals(username)) {
            throw new LoomAgentRuntimeException(403, "只能撤回/删除自己的提交");
        }
        // 清除引用（admin DELETE 也级联清理 user_knowledge + role_knowledge）
        jdbcTemplate.update("DELETE FROM loom_user_knowledge WHERE market_knowledge_id = ?", marketKnowledgeId);
        jdbcTemplate.update("DELETE FROM loom_role_knowledge WHERE market_knowledge_id = ?", marketKnowledgeId);
        int rows;
        if (isAdmin) {
            rows = jdbcTemplate.update(
                    "DELETE FROM loom_market_knowledge WHERE id = ?",
                    marketKnowledgeId);
        } else {
            rows = jdbcTemplate.update(
                    "DELETE FROM loom_market_knowledge WHERE id = ? AND username = ?",
                    marketKnowledgeId, username);
        }
        if (rows == 0) {
            throw new LoomAgentRuntimeException(404, "市场知识库不存在: " + marketKnowledgeId);
        }
    }

    /* ===== 用户拉取（不再校验 status='APPROVED'，提交即上架） ===== */

    @Override
    @Transactional
    public void pull(String username, String marketKnowledgeId) {
        MarketKnowledgeRecord mk = getById(marketKnowledgeId);
        // 去掉 status='APPROVED' 校验（永远 APPROVED）

        // 检查是否已存在
        Integer existingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM loom_user_knowledge WHERE username = ? AND market_knowledge_id = ?",
                Integer.class, username, marketKnowledgeId);
        if (existingCount != null && existingCount > 0) {
            throw new LoomAgentRuntimeException(409, "已订阅该知识库");
        }

        // 检查是否有 ROLE_GRANTED 锁定的同名订阅
        List<java.util.Map<String, Object>> lockedRows = jdbcTemplate.queryForList(
                "SELECT uk.* FROM loom_user_knowledge uk " +
                        "JOIN loom_market_knowledge mk ON uk.market_knowledge_id = mk.id " +
                        "WHERE uk.username = ? AND mk.name = ? AND uk.locked = TRUE",
                username, mk.name());
        if (!lockedRows.isEmpty()) {
            throw new LoomAgentRuntimeException(409, "同名知识库已被角色授权锁定，不能从市场覆盖");
        }

        jdbcTemplate.update(
                "INSERT INTO loom_user_knowledge (username, market_knowledge_id, source, locked) VALUES (?, ?, 'MARKET_PULLED', FALSE)",
                username, marketKnowledgeId);
    }

    @Override
    public List<MarketKnowledgeRecord> listMyPulled(String username) {
        return jdbcTemplate.query(
                "SELECT mk.* FROM loom_market_knowledge mk " +
                        "JOIN loom_user_knowledge uk ON mk.id = uk.market_knowledge_id " +
                        "WHERE uk.username = ? AND uk.source = 'MARKET_PULLED' " +
                        "ORDER BY mk.reviewed_at DESC, mk.submitted_at DESC",
                this::mapMarketKnowledgeRecord, username);
    }

    /* ===== T10: 公共 access 端点（KB 端独有） ===== */

    /**
     * 用户访问市场知识库 → 自增 {@code loom_user_knowledge.access_count}。
     * <p>
     * 仅在用户已经订阅（{@code loom_user_knowledge} 存在对应行）时才递增计数;
     * 未订阅则 no-op（rows=0），不会创建 phantom pull 占用
     * {@code source='MARKET_PULLED'} 配额（{@code pull} 会因 409 拒绝重复行）。
     * 完整 KB search stat 接线（{@code loom_market_knowledge_stats}）由 T16 落地。
     *
     * @return 递增后的 {@code access_count};未订阅时返回 0。
     * @throws LoomAgentRuntimeException 市场 KB 不存在时抛 404
     */
    public long access(String username, String marketKnowledgeId) {
        MarketKnowledgeRecord mk = getById(marketKnowledgeId);
        int rows = jdbcTemplate.update(
                "UPDATE loom_user_knowledge SET access_count = access_count + 1 " +
                        "WHERE username = ? AND market_knowledge_id = ?",
                username, mk.id());
        if (rows == 0) {
            // 用户未订阅(无 row)→ no-op,不能创建 phantom pull 阻塞后续 pull 端点
            return 0L;
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT access_count FROM loom_user_knowledge WHERE username = ? AND market_knowledge_id = ?",
                Long.class, username, mk.id());
        return count == null ? 0L : count;
    }
}