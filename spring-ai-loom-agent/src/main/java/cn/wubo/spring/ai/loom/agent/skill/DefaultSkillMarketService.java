package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.market.AbstractMarketAdminService;
import cn.wubo.spring.ai.loom.agent.market.MarketContentStatus;
import cn.wubo.spring.ai.loom.agent.market.MarketCreateRequest;
import cn.wubo.spring.ai.loom.agent.market.MarketUpdateRequest;
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
 * <p>{@code <MarketSkill, Void, Void>} 中 {@code U} / {@code R} 不参与具体方法签名,
 * 故用 {@link Void} 占位 (review 类尚未存在,见 T17)。
 */
@Component
public class DefaultSkillMarketService extends AbstractMarketAdminService<MarketSkill, Void, Void> implements ISkillMarketService {

    public DefaultSkillMarketService(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    /* ===== AbstractMarketAdminService hook ===== */

    @Override
    protected String tableName() {
        return "market_skill";
    }

    @Override
    protected RowMapper<MarketSkill> rowMapper() {
        return (rs, n) -> MarketSkill.from(rs);
    }

    @Override
    protected Long extractId(MarketSkill entry) {
        return entry.id();
    }

    @Override
    protected MarketContentStatus currentStatusImpl(MarketSkill entry) {
        return MarketContentStatus.from(entry.status());
    }

    /**
     * 按 id 查询单条 — {@link AbstractMarketAdminService#getById(Long)} 已提供默认实现,
     * 这里显式 override 让 SQL 文案可读且与旧 {@link #get(Long)} 保持一致。
     */
    @Override
    public MarketSkill getById(Long id) {
        return jdbc.queryForObject(
                "SELECT * FROM market_skill WHERE id = ?",
                rowMapper(), id);
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
        // UPSERT(同一作者+name 只保留一行)+ 直接 APPROVED
        Long existingId = null;
        try {
            existingId = jdbc.queryForObject(
                    "SELECT id FROM market_skill WHERE author = ? AND name = ? LIMIT 1",
                    Long.class, username, req.name());
        } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
        }
        Long marketId;
        if (existingId != null) {
            // UPDATE 内容 + 标记 APPROVED
            jdbc.update(
                    "UPDATE market_skill SET description = ?, content = ?, status = 'APPROVED', " +
                            "reviewed_at = CURRENT_TIMESTAMP, reviewed_by = ?, review_comment = NULL WHERE id = ?",
                    req.description(), req.content(), username, existingId);
            marketId = existingId;
        } else {
            jdbc.update(
                    "INSERT INTO market_skill (name, description, content, author, status, reviewed_at, reviewed_by) " +
                            "VALUES (?, ?, ?, ?, 'APPROVED', CURRENT_TIMESTAMP, ?)",
                    req.name(), req.description(), req.content(), username, username);
            marketId = jdbc.queryForObject(
                    "SELECT MAX(id) FROM market_skill WHERE author = ? AND name = ?",
                    Long.class, username, req.name());
        }
        // 反写 author 自己的 user_skill.market_skill_id(用于 save() 反向同步 + 推送)
        jdbc.update(
                "UPDATE user_skill SET market_skill_id = ? WHERE username = ? AND name = ?",
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
        // 去掉 status='APPROVED' 校验(提交即上架)
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
