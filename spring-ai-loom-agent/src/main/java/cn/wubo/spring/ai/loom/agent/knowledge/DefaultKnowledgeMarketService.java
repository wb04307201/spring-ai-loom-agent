package cn.wubo.spring.ai.loom.agent.knowledge;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.market.AbstractMarketAdminService;
import cn.wubo.spring.ai.loom.agent.market.MarketContentStatus;
import cn.wubo.spring.ai.loom.agent.market.MarketCreateRequest;
import cn.wubo.spring.ai.loom.agent.market.MarketFilter;
import cn.wubo.spring.ai.loom.agent.market.MarketUpdateRequest;
import cn.wubo.spring.ai.loom.agent.market.Page;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库市场服务 — 同时实现两套契约(与 {@code DefaultSkillMarketService} 镜像):
 * <ul>
 *   <li>M0 重构后的 {@link AbstractMarketAdminService} (admin CRUD / 审批流) —
 *       M3+ T1.2/T1.3 重构后,主键类型 {@code <String>} (UUID),
 *       不再有 String/Long 孪生方法,直接走 abstract base 的 {@code K=String} 路径</li>
 *   <li>旧的 {@link IKnowledgeMarketService} (用户侧浏览 / 提交 / 拉取 / 撤回)
 *       — 为 {@code LoomAgentConfiguration} 中已有的路由器 bean 提供向后兼容</li>
 * </ul>
 *
 * <p><b>与 Skill 端的真实对称差异 —— 主键类型</b>:
 * {@code loom_market_knowledge.id} 是 {@code VARCHAR(36)} (UUID),而
 * {@code market_skill.id} 是 {@code BIGINT}。M3+ T1.2 通过 {@code IMarketContentAdminService<K, M, U, R>}
 * 类型参数化抹平差异 — KB 端 {@code K=String},所有 admin 路径
 * (approve / reject / setOfficial / setFeaturedRank / setCategory / update / delete)
 * 直接走 abstract base 的 {@code K=String} 路径,JdbcTemplate 自动按 VARCHAR(36) coerce。
 *
 * <p>{@code <String, MarketKnowledgeRecord, Void, Void>} 中 {@code U} / {@code R} 不参与具体方法签名,
 * 故用 {@link Void} 占位(review 类尚未存在,见 T17)。
 */
@Component
public class DefaultKnowledgeMarketService
        extends AbstractMarketAdminService<String, MarketKnowledgeRecord, Void, Void>
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

    /**
     * M3+ R4 (AT2 follow-up, change 0) — 返回 canonical kind {@code "KNOWLEDGE"}
     * (与 KB announcement router 写入 {@code market_content_announcement.market_kind}
     * 的值、以及 {@link cn.wubo.spring.ai.loom.agent.knowledge.market.KnowledgeTagService#MARKET_KIND_KNOWLEDGE}
     * 一致)。历史值 {@code "KB"} 让 {@code listPaged} 的 JOIN 过滤
     * {@code a.market_kind='KB'} 永远匹配不到 {@code 'KNOWLEDGE'} 公告行 —
     * KB 列表 DTO 从未嵌入过公告。Skill 端 {@code "SKILL"} 一直正确,未动。
     */
    @Override
    protected String marketKind() {
        return "KNOWLEDGE";
    }

    /** M4 T3 — KB review 表(代码常量,注入安全)。 */
    @Override
    protected String reviewTable() {
        return "loom_market_knowledge_review";
    }

    /** M4 T3 — KB review 表指向 loom_market_knowledge.id (VARCHAR(36)) 的列。 */
    @Override
    protected String reviewIdColumn() {
        return "market_id";
    }

    @Override
    protected RowMapper<MarketKnowledgeRecord> rowMapper() {
        return (rs, n) -> MarketKnowledgeRecord.from(rs);
    }

    /**
     * KB 端主键是 VARCHAR(36) UUID。{@code extractId} 从 abstract base 提升到 interface
     * (M3+ T1.2) 后,override 由 {@code protected} 改为 {@code public} 返回 {@link String}。
     */
    @Override
    public String extractId(MarketKnowledgeRecord entry) {
        return entry == null ? null : entry.id();
    }

    @Override
    protected MarketContentStatus currentStatusImpl(MarketKnowledgeRecord entry) {
        return MarketContentStatus.from(entry.status());
    }

    /* ===== admin CRUD / 审批流 —— K=String 主键直接走 abstract base (M3+ T1.3 删 twins) ===== */

    /**
     * user 直接创建 → 落 PENDING(admin 之后调 {@link #approve(Object, String)})。
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
     * admin 直发 APPROVED — 可信主路径,绕过 PENDING 审批 (Task 2 / #4)。
     * 新 UUID id;落 {@code status='APPROVED', created_by_kind='ADMIN',
     * reviewed_at=NOW, reviewed_by=adminUsername, category=req.category}。
     * 注意:{@code loom_market_knowledge} 没有 {@code content} 列,
     * {@link MarketCreateRequest#content()} 在 KB 端被忽略。
     */
    @Override
    @Transactional
    public MarketKnowledgeRecord createApproved(String adminUsername, MarketCreateRequest req) {
        String marketId = UUID.randomUUID().toString();
        jdbcTemplate.update(
            "INSERT INTO loom_market_knowledge (id, username, name, description, category, status, " +
                "created_by_kind, reviewed_at, reviewed_by) " +
                "VALUES (?, ?, ?, ?, ?, 'APPROVED', 'ADMIN', CURRENT_TIMESTAMP, ?)",
            marketId, adminUsername, req.name(), req.description(), req.category(), adminUsername);
        return getById(marketId);
    }

    /**
     * 动态 SET 更新。{@code content} 列在 KB 端不存在,忽略。
     * Override abstract base 的 {@code update(K, MarketUpdateRequest)} (K=String → UUID 直接传)。
     */
    @Override
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
     * 按 id 查询单条 — Override abstract base 的 {@code getById(K=String)} 让空 id 抛
     * {@link LoomAgentRuntimeException} (404) 而非 {@link EmptyResultDataAccessException} (500),
     * 与 router 的 LoomAgentRuntimeException catch 块对齐。
     */
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

    /* ===== 市场浏览 ===== */

    /**
     * M3+ R4 (AT2 follow-up, change 1) — override 基类 {@code listPaged},在
     * announcement LEFT JOIN(基类 T2.1/R3)之上再补齐 {@code tags}:对当前页的
     * 全部 id 做 <b>单次批量 SELECT</b>(NOT per-row N+1),Java 侧按
     * {@code market_id} 分组成 {@code Map<String, List<String>>},然后用
     * {@link MarketKnowledgeRecord#withTags(List)} 重建每行 — 无 tag 的 KB 得到
     * {@code List.of()}(永远非 null)。
     *
     * <p>tag 排序 {@code ORDER BY tag ASC} 与
     * {@link cn.wubo.spring.ai.loom.agent.knowledge.market.KnowledgeTagService#listTags}
     * (per-id 端点)一致,保证列表嵌入与详情端点展示相同顺序。
     * H2 无 GROUP_CONCAT/LISTAGG 可移植写法 → 不用 SQL 聚合。
     *
     * <p>基类 {@code search(query, category, page, size)} 委派到 {@code listPaged}
     * (见 {@link AbstractMarketAdminService#search}),故本 override 同时覆盖
     * admin 列表、公开列表与搜索三条路径。分页元数据(total/page/size)原样保留。
     *
     * <p>wiring:直接用已注入的 {@code jdbcTemplate}(brief 首选路线)— 避免为
     * {@code KnowledgeTagService} 增加构造器依赖与 LoomAgentConfiguration bean
     * 接线变更。tag 表 {@code loom_market_knowledge_tag(market_id, tag)} 本身就是
     * KB 专属(无 market_kind 列),无需 kind 过滤。
     */
    @Override
    public Page<MarketKnowledgeRecord> listPaged(MarketFilter filter) {
        Page<MarketKnowledgeRecord> page = super.listPaged(filter);
        return new Page<>(embedTags(page.items()), page.total(), page.page(), page.size());
    }

    /**
     * M3+ R4 (change 2) — 单次批量 tag SELECT + Java 侧分组 +
     * {@link MarketKnowledgeRecord#withTags(List)} 重建,供 {@link #listPaged}
     * 与 {@link #listApproved} 共用。无 tag 的行得到空 list(非 null)。
     * 空输入 / 全 null id 时原样返回,不发 SQL。
     */
    private List<MarketKnowledgeRecord> embedTags(List<MarketKnowledgeRecord> rows) {
        List<String> ids = rows.stream()
                .map(MarketKnowledgeRecord::id)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return rows;
        }
        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids);
        Map<String, List<String>> tagsById = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT market_id, tag FROM loom_market_knowledge_tag " +
                        "WHERE market_id IN (" + placeholders + ") " +
                        "ORDER BY market_id ASC, tag ASC",
                rs -> {
                    tagsById.computeIfAbsent(rs.getString("market_id"), k -> new ArrayList<>())
                            .add(rs.getString("tag"));
                },
                args.toArray());
        return rows.stream()
                .map(r -> r.withTags(tagsById.getOrDefault(r.id(), List.of())))
                .toList();
    }

    /**
     * M3+ R4 (change 2) — 用户端聊天面板的「知识库市场」tab(app.js
     * {@code _renderMarketTab})实际调用的是本 v1 端点
     * ({@code GET /spring/ai/loom/api/knowledge-market}),且读取
     * {@code row.tags} 渲染 tag chips / 聚合过滤栏 — 按 brief 的条件分支
     * ("do NOT add tags there unless the market tab demonstrably reads one of
     * them")在此也嵌入 tags。仍然是单次批量 SELECT(embedTags),无 N+1。
     * <p>
     * announcement 字段在 v1 bare {@code SELECT *} 下保持 null(v1 契约遗留,
     * admin/公开 v2 路由走 listPaged 已带公告);listMySubmitted / listMyPulled /
     * listAllForAdmin 未动(market tab 不读取其 tags)。
     */
    @Override
    public List<MarketKnowledgeRecord> listApproved(int page, int size) {
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        int offset = (page - 1) * size;
        return embedTags(jdbcTemplate.query(
                "SELECT * FROM loom_market_knowledge WHERE status = 'APPROVED' ORDER BY reviewed_at DESC, submitted_at DESC LIMIT ? OFFSET ?",
                this::mapMarketKnowledgeRecord, size, offset));
    }

    @Override
    public List<MarketKnowledgeRecord> listAllForAdmin() {
        // admin 视角:全状态列出(PENDING/APPROVED/REJECTED);按审核/上架时间倒序
        return jdbcTemplate.query(
                "SELECT * FROM loom_market_knowledge ORDER BY reviewed_at DESC, submitted_at DESC",
                this::mapMarketKnowledgeRecord);
    }

    /* ===== 用户提交（进 PENDING 审批流；REJECTED 重投归档旧行） ===== */

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

        // 查同名旧行(可能不存在)
        String existingId = null;
        String existingStatus = null;
        try {
            existingId = jdbcTemplate.queryForObject(
                    "SELECT id FROM loom_market_knowledge WHERE username=? AND name=? LIMIT 1",
                    String.class, username, kb.name());
            existingStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM loom_market_knowledge WHERE id=?", String.class, existingId);
        } catch (EmptyResultDataAccessException ignored) {
        }

        String marketId;
        if (existingId != null && "REJECTED".equals(existingStatus)) {
            // REJECTED 重投:旧行整行归档 → 主表删 → 新建 PENDING 行(新 id)
            jdbcTemplate.update(
                    "INSERT INTO loom_market_knowledge_archive (id, username, name, description, status, " +
                            "submitted_at, reviewed_at, reviewed_by, review_comment) " +
                            "SELECT id, username, name, description, status, submitted_at, reviewed_at, " +
                            "reviewed_by, review_comment FROM loom_market_knowledge WHERE id=?", existingId);
            jdbcTemplate.update("DELETE FROM loom_market_knowledge WHERE id=?", existingId);
            marketId = UUID.randomUUID().toString();
            jdbcTemplate.update(
                    "INSERT INTO loom_market_knowledge (id, username, name, description, status, created_by_kind) " +
                            "VALUES (?, ?, ?, ?, 'PENDING', 'USER')",
                    marketId, username, kb.name(), kb.description());
        } else if (existingId != null) {
            // 非 REJECTED 同名行(PENDING/APPROVED)→ 仅更新内容,状态与审核字段不动
            // (spec §2: APPROVED→PENDING 禁止;T3 Ruling 已定,KB 镜像同语义)
            jdbcTemplate.update(
                    "UPDATE loom_market_knowledge SET description=? WHERE id=?",
                    kb.description(), existingId);
            marketId = existingId;
        } else {
            // 全新 INSERT → PENDING(等 admin approve)
            marketId = UUID.randomUUID().toString();
            jdbcTemplate.update(
                    "INSERT INTO loom_market_knowledge (id, username, name, description, status, created_by_kind) " +
                            "VALUES (?, ?, ?, ?, 'PENDING', 'USER')",
                    marketId, username, kb.name(), kb.description());
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

    @Override
    protected void cascadeCleanup(String id) {
        jdbcTemplate.update("DELETE FROM loom_user_knowledge WHERE market_knowledge_id=?", id);
        jdbcTemplate.update("DELETE FROM loom_role_knowledge WHERE market_knowledge_id=?", id);
    }

    /* ===== 用户拉取（仅 APPROVED 可拉取，镜像 Skill 端 pull 403 校验） ===== */

    @Override
    @Transactional
    public void pull(String username, String marketKnowledgeId) {
        MarketKnowledgeRecord mk = getById(marketKnowledgeId);
        if (!MarketKnowledgeRecord.STATUS_APPROVED.equals(mk.status())) {
            throw new LoomAgentRuntimeException(403, "该知识库未通过审批,暂不可拉取(status=" + mk.status() + ")");
        }

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
