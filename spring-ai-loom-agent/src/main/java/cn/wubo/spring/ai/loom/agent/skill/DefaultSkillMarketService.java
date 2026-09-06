package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.market.AbstractMarketAdminService;
import cn.wubo.spring.ai.loom.agent.market.MarketContentStatus;
import cn.wubo.spring.ai.loom.agent.market.MarketCreateRequest;
import cn.wubo.spring.ai.loom.agent.market.MarketFilter;
import cn.wubo.spring.ai.loom.agent.market.MarketUpdateRequest;
import cn.wubo.spring.ai.loom.agent.market.Page;
import cn.wubo.spring.ai.loom.agent.model.MarketSkill;
import cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest;
import cn.wubo.spring.ai.loom.agent.model.MarketSkillUpsertRequest;
import cn.wubo.spring.ai.loom.agent.model.UserSkill;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;

/**
 * Skill 市场服务 — 同时实现两套契约：
 * <ul>
 *   <li>M0 重构后的 {@link AbstractMarketAdminService} (admin CRUD / 审批流)
 *       — {@link #create(String, MarketCreateRequest)} /
 *       {@link #update(Long, MarketUpdateRequest)} / {@link #approve(Long, String)} /
 *       {@link #reject(Long, String, String)} / {@link #setOfficial(Long, boolean, String)} 等</li>
 *   <li>旧的 {@link ISkillMarketService} (用户侧浏览 / 提交 / 拉取 / 撤回)
 *       — 为 {@code LoomAgentConfiguration} 中已有的路由器 bean 提供向后兼容</li>
 * </ul>
 *
 * <p>{@code <Long, MarketSkill, Void, Void>} 中 {@code K=Long} (market_skill.id BIGINT),
 * {@code U} / {@code R} 不参与具体方法签名,故用 {@link Void} 占位
 * (review 类尚未存在,见 T17)。{@link #extractId(MarketSkill)} 从 abstract base
 * 提升到 interface 后,此处 override 由 {@code protected} 改为 {@code public} (M3+ T1.2)。
 */
@Component
public class DefaultSkillMarketService extends AbstractMarketAdminService<Long, MarketSkill, Void, Void> implements ISkillMarketService {

    public DefaultSkillMarketService(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    /* ===== AbstractMarketAdminService hook ===== */

    @Override
    protected String tableName() {
        return "market_skill";
    }

    @Override
    protected String marketKind() {
        return "SKILL";
    }

    /** M4 T3 — skill review 表(代码常量,注入安全)。 */
    @Override
    protected String reviewTable() {
        return "market_skill_review";
    }

    /** M4 T3 — skill review 表指向 market_skill.id (BIGINT) 的列。 */
    @Override
    protected String reviewIdColumn() {
        return "market_skill_id";
    }

    @Override
    protected RowMapper<MarketSkill> rowMapper() {
        return (rs, n) -> MarketSkill.from(rs);
    }

    @Override
    public Long extractId(MarketSkill entry) {
        return entry.id();
    }

    @Override
    protected MarketContentStatus currentStatusImpl(MarketSkill entry) {
        return MarketContentStatus.from(entry.status());
    }

    /**
     * 按 id 查询单条 — {@link AbstractMarketAdminService#getById(Object)} 已提供默认实现,
     * 这里显式 override 让 SQL 文案可读且与旧 {@link #get(Long)} 保持一致。
     * <p>
     * 行为契约:不存在时抛 {@link LoomAgentRuntimeException} (404),
     * 与 {@link #get(Long)} 同款 — 这样 {@code AbstractMarketAdminService#approve} /
     * {@code #reject} / {@link #update} 在 id 不存在时也能落到 router 的
     * {@code LoomAgentRuntimeException} catch 块里返回 404,而不是 500。
     */
    @Override
    public MarketSkill getById(Long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM market_skill WHERE id = ?",
                    rowMapper(), id);
        } catch (EmptyResultDataAccessException e) {
            throw new LoomAgentRuntimeException(404, "Skill 不存在: id=" + id);
        }
    }

    /**
     * 子类自定义 create 路径:user 直接创建 → 落 PENDING(基类模板默认走 approve 流,
     * 但 user 提交后 admin 才会调 {@link #approve(Long, String)})。
     */
    @Override
    public MarketSkill create(String author, MarketCreateRequest req) {
        jdbc.update(
                "INSERT INTO market_skill (name, description, content, author, status, created_by_kind) " +
                        "VALUES (?, ?, ?, ?, 'PENDING', 'USER')",
                req.name(), req.description(), req.content(), author);
        Long id = jdbc.queryForObject(
                "SELECT id FROM market_skill WHERE author=? AND name=? ORDER BY id DESC LIMIT 1",
                Long.class, author, req.name());
        return getById(id);
    }

    /**
     * admin 直发 APPROVED — 可信主路径,绕过 PENDING 审批 (Task 2 / #4)。
     * 落 {@code status='APPROVED', created_by_kind='ADMIN', reviewed_at=NOW,
     * reviewed_by=adminUsername, category=req.category};先按 author+name 查重,
     * 同名已存在抛 422 (镜像 adminCreate 的查重语义)。
     */
    @Override
    @Transactional
    public MarketSkill createApproved(String adminUsername, MarketCreateRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new LoomAgentRuntimeException("name 不能为空");
        }
        if (req.content() == null || req.content().isBlank()) {
            throw new LoomAgentRuntimeException("content 不能为空");
        }
        Integer dup = jdbc.queryForObject(
            "SELECT COUNT(*) FROM market_skill WHERE author=? AND name=?",
            Integer.class, adminUsername, req.name());
        if (dup != null && dup > 0) {
            throw new LoomAgentRuntimeException(422,
                "已存在同名 Skill: author=" + adminUsername + " name=" + req.name());
        }
        jdbc.update(
            "INSERT INTO market_skill (name, description, content, author, status, category, " +
                "created_by_kind, reviewed_at, reviewed_by) " +
                "VALUES (?, ?, ?, ?, 'APPROVED', ?, 'ADMIN', CURRENT_TIMESTAMP, ?)",
            req.name(), req.description(), req.content(), adminUsername, req.category(), adminUsername);
        Long id = jdbc.queryForObject(
            "SELECT MAX(id) FROM market_skill WHERE author=? AND name=?",
            Long.class, adminUsername, req.name());
        return get(id);
    }

    @Override
    public MarketSkill update(Long id, MarketUpdateRequest req) {
        StringBuilder sql = new StringBuilder("UPDATE market_skill SET updated_at=CURRENT_TIMESTAMP");
        List<Object> args = new java.util.ArrayList<>();
        if (req.name() != null) {
            sql.append(", name=?");
            args.add(req.name());
        }
        if (req.description() != null) {
            sql.append(", description=?");
            args.add(req.description());
        }
        if (req.content() != null) {
            sql.append(", content=?");
            args.add(req.content());
        }
        if (req.category() != null) {
            sql.append(", category=?");
            args.add(req.category());
        }
        sql.append(" WHERE id=?");
        args.add(id);
        jdbc.update(sql.toString(), args.toArray());
        return getById(id);
    }

    /* ===== ISkillMarketService — 市场浏览 ===== */

    @Override
    public List<MarketSkill> listApproved() {
        return jdbc.query(
                "SELECT * FROM market_skill WHERE status = 'APPROVED' ORDER BY author, name",
                rowMapper());
    }

    @Override
    public MarketSkill get(Long id) {
        try {
            return getById(id);
        } catch (EmptyResultDataAccessException e) {
            throw new LoomAgentRuntimeException("Skill 不存在: id=" + id);
        }
    }

    @Override
    public List<MarketSkill> listAllForAdmin() {
        return jdbc.query(
                "SELECT * FROM market_skill ORDER BY author, name",
                rowMapper());
    }

    /* ===== M4 T4: tags embed (镜像 DefaultKnowledgeMarketService R4) ===== */

    /**
     * M4 T4 — override 基类 {@code listPaged},在 announcement + review-aggregate
     * LEFT JOIN(基类 T2.1/R3/T3)之上再补齐 {@code tags}:对当前页的全部 id 做
     * <b>单次批量 SELECT</b>(NOT per-row N+1),Java 侧按 {@code market_skill_id}
     * 分组成 {@code Map<Long, List<String>>},然后用 {@link MarketSkill#withTags(List)}
     * 重建每行 — 无 tag 的 skill 得到 {@code List.of()}(永远非 null)。
     *
     * <p>tag 排序 {@code ORDER BY tag ASC} 与
     * {@link cn.wubo.spring.ai.loom.agent.skill.market.SkillTagService#listTags}
     * (per-id 端点)一致,保证列表嵌入与详情端点展示相同顺序。
     * H2 无 GROUP_CONCAT/LISTAGG 可移植写法 → 不用 SQL 聚合。
     *
     * <p>基类 {@code search(query, category, page, size)} 委派到 {@code listPaged},
     * 故本 override 同时覆盖 admin 列表、公开列表与搜索三条路径。分页元数据
     * (total/page/size)原样保留。
     */
    @Override
    public Page<MarketSkill> listPaged(MarketFilter filter) {
        Page<MarketSkill> page = super.listPaged(filter);
        return new Page<>(embedTags(page.items()), page.total(), page.page(), page.size());
    }

    /**
     * M4 T4 — 单次批量 tag SELECT + Java 侧分组 + {@link MarketSkill#withTags(List)}
     * 重建,供 {@link #listPaged} 与 {@link #enrich(List)} 共用。无 tag 的行得到
     * 空 list(非 null)。空输入 / 全 null id 时原样返回,不发 SQL。
     */
    private List<MarketSkill> embedTags(List<MarketSkill> rows) {
        List<Long> ids = rows.stream()
                .map(MarketSkill::id)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return rows;
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new java.util.ArrayList<>(ids);
        java.util.Map<Long, List<String>> tagsById = new java.util.LinkedHashMap<>();
        jdbc.query(
                "SELECT market_skill_id, tag FROM market_skill_tag " +
                        "WHERE market_skill_id IN (" + placeholders + ") " +
                        "ORDER BY market_skill_id ASC, tag ASC",
                rs -> {
                    tagsById.computeIfAbsent(rs.getLong("market_skill_id"), k -> new java.util.ArrayList<>())
                            .add(rs.getString("tag"));
                },
                args.toArray());
        return rows.stream()
                .map(r -> r.withTags(tagsById.getOrDefault(r.id(), List.of())))
                .toList();
    }

    /**
     * M4 T4 — 富化 {@code ?tag=} 路径(tag 交集查询)返回的行:先用单次批量 SELECT
     * 补齐 announcement(title/body),再 {@link #embedTags(List)} 补齐 tags。
     * <p>
     * tag 路径的 {@code SELECT m.*} 不带 announcement LEFT JOIN,故 announcement 字段
     * 为 null;本方法从 {@code market_content_announcement}(kind='SKILL')批量回读并
     * 用 {@link MarketSkill#withAnnouncement(String, String)} 重建。注意
     * {@code market_content_announcement.market_id} 是 VARCHAR(36),skill id 是 BIGINT,
     * 用 {@code String.valueOf(id)} 绑定(与 {@code listPaged} 的 CAST join 语义一致,
     * 跨 kind UUID 公告行天然不匹配十进制 id)。
     * <p>
     * 单次批量 announcement SELECT + 单次批量 tag SELECT — 无 N+1。供
     * {@code loomAgentSkillMarketPublicRouter} 的 {@code ?tag=} 分支复用。
     */
    public List<MarketSkill> enrich(List<MarketSkill> rows) {
        if (rows == null || rows.isEmpty()) {
            return rows == null ? List.of() : rows;
        }
        List<String> idStrings = rows.stream()
                .map(MarketSkill::id)
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .toList();
        List<MarketSkill> withAnnouncement;
        if (idStrings.isEmpty()) {
            withAnnouncement = rows;
        } else {
            String placeholders = String.join(", ", java.util.Collections.nCopies(idStrings.size(), "?"));
            List<Object> args = new java.util.ArrayList<>(idStrings);
            java.util.Map<String, String[]> annById = new java.util.LinkedHashMap<>();
            jdbc.query(
                    "SELECT market_id, title, body FROM market_content_announcement " +
                            "WHERE market_kind = 'SKILL' AND market_id IN (" + placeholders + ")",
                    rs -> {
                        annById.put(rs.getString("market_id"),
                                new String[]{rs.getString("title"), rs.getString("body")});
                    },
                    args.toArray());
            withAnnouncement = rows.stream()
                    .map(r -> {
                        String[] ann = r.id() == null ? null : annById.get(String.valueOf(r.id()));
                        return ann == null ? r : r.withAnnouncement(ann[0], ann[1]);
                    })
                    .toList();
        }
        return embedTags(withAnnouncement);
    }

    /* ===== ISkillMarketService — 用户提交 ===== */

    @Override
    @Transactional
    public MarketSkill submit(String username, MarketSkillSubmitRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new LoomAgentRuntimeException("name 不能为空");
        }
        if (req.content() == null || req.content().isBlank()) {
            throw new LoomAgentRuntimeException("content 不能为空");
        }
        // 查同名旧行(可能不存在)
        Long existingId = null;
        String existingStatus = null;
        try {
            existingId = jdbc.queryForObject(
                "SELECT id FROM market_skill WHERE author=? AND name=? LIMIT 1",
                Long.class, username, req.name());
            existingStatus = jdbc.queryForObject(
                "SELECT status FROM market_skill WHERE id=?", String.class, existingId);
        } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
        }
        Long marketId;
        if (existingId != null && "REJECTED".equals(existingStatus)) {
            // REJECTED 重投:旧行整行归档 → 主表删 → 新建 PENDING 行(新 id)
            jdbc.update(
                "INSERT INTO market_skill_archive (id, name, description, content, author, status, " +
                    "submitted_at, reviewed_at, reviewed_by, review_comment) " +
                    "SELECT id, name, description, content, author, status, submitted_at, reviewed_at, " +
                    "reviewed_by, review_comment FROM market_skill WHERE id=?", existingId);
            jdbc.update("DELETE FROM market_skill WHERE id=?", existingId);
            jdbc.update(
                "INSERT INTO market_skill (name, description, content, author, status, created_by_kind) " +
                    "VALUES (?, ?, ?, ?, 'PENDING', 'USER')",
                req.name(), req.description(), req.content(), username);
            marketId = jdbc.queryForObject(
                "SELECT MAX(id) FROM market_skill WHERE author=? AND name=?",
                Long.class, username, req.name());
        } else if (existingId != null) {
            // 非 REJECTED 同名行(PENDING/APPROVED)→ 仅更新内容,状态与审核字段不动
            // (spec §2: APPROVED→PENDING 禁止;PENDING 本就未审,review 字段已是 NULL)
            jdbc.update(
                    "UPDATE market_skill SET description = ?, content = ? WHERE id = ?",
                    req.description(), req.content(), existingId);
            marketId = existingId;
        } else {
            jdbc.update(
                "INSERT INTO market_skill (name, description, content, author, status, created_by_kind) " +
                    "VALUES (?, ?, ?, ?, 'PENDING', 'USER')",
                req.name(), req.description(), req.content(), username);
            marketId = jdbc.queryForObject(
                "SELECT MAX(id) FROM market_skill WHERE author=? AND name=?",
                Long.class, username, req.name());
        }
        // backlink 重写为新 marketId(REJECTED 重投时指向新行)
        jdbc.update(
            "UPDATE user_skill SET market_skill_id=? WHERE username=? AND name=?",
            marketId, username, req.name());
        return get(marketId);
    }

    /* ===== ISkillMarketService — admin 直接 CRUD ===== */

    @Override
    @Transactional
    public MarketSkill adminCreate(String adminUsername, MarketSkillUpsertRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new LoomAgentRuntimeException("name 不能为空");
        }
        if (req.content() == null || req.content().isBlank()) {
            throw new LoomAgentRuntimeException("content 不能为空");
        }
        String status = req.status() == null ? MarketSkill.STATUS_APPROVED : req.status();
        Integer dup = jdbc.queryForObject(
                "SELECT COUNT(*) FROM market_skill WHERE author = ? AND name = ?",
                Integer.class, adminUsername, req.name());
        if (dup != null && dup > 0) {
            throw new LoomAgentRuntimeException("已存在同名 Skill:author=" + adminUsername + " name=" + req.name());
        }
        jdbc.update(
                "INSERT INTO market_skill (name, description, content, author, status, " +
                        "reviewed_at, reviewed_by) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)",
                req.name(), req.description(), req.content(),
                adminUsername, status, adminUsername);
        Long id = jdbc.queryForObject(
                "SELECT MAX(id) FROM market_skill WHERE author = ? AND name = ?",
                Long.class, adminUsername, req.name());
        return get(id);
    }

    @Override
    @Transactional
    public MarketSkill adminUpdate(String adminUsername, Long id, MarketSkillUpsertRequest req) {
        MarketSkill existing = get(id);
        jdbc.update(
                "UPDATE market_skill SET name = ?, description = ?, content = ?, " +
                        "status = COALESCE(?, status) WHERE id = ?",
                req.name() == null ? existing.name() : req.name(),
                req.description() == null ? existing.description() : req.description(),
                req.content() == null ? existing.content() : req.content(),
                req.status(),
                id);
        return get(id);
    }

    @Override
    @Transactional
    public void adminDelete(String adminUsername, Long id) {
        // 先把 user_skill / role_skill 里所有引用清掉
        jdbc.update("DELETE FROM user_skill WHERE market_skill_id = ?", id);
        jdbc.update("DELETE FROM role_skill WHERE market_skill_id = ?", id);
        int n = jdbc.update("DELETE FROM market_skill WHERE id = ?", id);
        if (n == 0) throw new LoomAgentRuntimeException("Skill 不存在: id=" + id);
    }

    /* ===== ISkillMarketService — 用户拉取 ===== */

    @Override
    @Transactional
    public UserSkill pull(String username, Long marketSkillId) {
        MarketSkill m = get(marketSkillId);
        if (!MarketSkill.STATUS_APPROVED.equals(m.status())) {
            throw new LoomAgentRuntimeException(403, "该技能未通过审批,暂不可拉取(status=" + m.status() + ")");
        }
        // 检查 user_skill 是否已存在同 name
        List<UserSkill> existing = jdbc.query(
                "SELECT * FROM user_skill WHERE username = ? AND name = ?",
                (rs, n) -> new UserSkill(
                        rs.getLong("id"), rs.getString("username"), rs.getString("name"),
                        rs.getString("description"), rs.getString("content"), rs.getString("source"),
                        (Long) rs.getObject("market_skill_id"),
                        rs.getBoolean("default_loaded"), rs.getBoolean("locked"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()),
                username, m.name());
        if (!existing.isEmpty()) {
            UserSkill e = existing.get(0);
            // 按 source 区分(USER_CREATED 不能被 pull 覆盖,否则会"吃掉"用户自建的内容)
            if ("USER_CREATED".equals(e.source())) {
                throw new LoomAgentRuntimeException(403,
                        "你已有同名自建 skill「" + m.name() + "」,不能从市场覆盖。如需使用市场版本,请先删除自建版本。");
            }
            // MARKET_PULLED:刷新 content(拉取最新市场快照)
            jdbc.update(
                    "UPDATE user_skill SET description = ?, content = ?, source = 'MARKET_PULLED', " +
                            "market_skill_id = ?, updated_at = CURRENT_TIMESTAMP " +
                            "WHERE id = ?",
                    m.description(), m.content(), m.id(), e.id());
            return getUserSkill(e.id());
        }
        jdbc.update(
                "INSERT INTO user_skill (username, name, description, content, source, market_skill_id, " +
                        "default_loaded, locked) VALUES (?, ?, ?, ?, 'MARKET_PULLED', ?, TRUE, FALSE)",
                username, m.name(), m.description(), m.content(), m.id());
        Long id = jdbc.queryForObject(
                "SELECT MAX(id) FROM user_skill WHERE username = ? AND name = ?",
                Long.class, username, m.name());
        return getUserSkill(id);
    }

    /* ===== ISkillMarketService — 用户查看/撤回 ===== */

    @Override
    public List<MarketSkill> listMySubmitted(String username) {
        return jdbc.query(
                "SELECT * FROM market_skill WHERE author = ? ORDER BY submitted_at DESC",
                rowMapper(), username);
    }

    @Override
    @Transactional
    public boolean withdraw(String username, Long marketSkillId) {
        // 去掉 status='PENDING' 限制(任意状态可撤回)
        int rows = jdbc.update(
                "DELETE FROM market_skill WHERE id = ? AND author = ?",
                marketSkillId, username);
        if (rows > 0) {
            // 反清空 author 自己的 user_skill.market_skill_id(断绝反向同步链路)
            jdbc.update(
                    "UPDATE user_skill SET market_skill_id = NULL WHERE username = ? AND market_skill_id = ?",
                    username, marketSkillId);
        }
        return rows > 0;
    }

    /* ===== helpers ===== */

    private UserSkill getUserSkill(Long id) {
        return jdbc.queryForObject(
                "SELECT * FROM user_skill WHERE id = ?",
                (rs, n) -> new UserSkill(
                        rs.getLong("id"), rs.getString("username"), rs.getString("name"),
                        rs.getString("description"), rs.getString("content"), rs.getString("source"),
                        (Long) rs.getObject("market_skill_id"),
                        rs.getBoolean("default_loaded"), rs.getBoolean("locked"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()),
                id);
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new LoomAgentRuntimeException("name 不能为空");
        }
    }
}
