package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.MarketSkill;
import cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest;
import cn.wubo.spring.ai.loom.agent.model.MarketSkillUpsertRequest;
import cn.wubo.spring.ai.loom.agent.model.UserSkill;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class DefaultSkillMarketService implements ISkillMarketService {

    private final JdbcTemplate jdbcTemplate;

    public DefaultSkillMarketService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private MarketSkill mapMarketSkill(ResultSet rs, int rowNum) throws SQLException {
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        return new MarketSkill(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("content"),
                rs.getString("version"),
                rs.getString("author"),
                rs.getString("status"),
                rs.getTimestamp("submitted_at").toLocalDateTime(),
                reviewedAt == null ? null : reviewedAt.toLocalDateTime(),
                rs.getString("reviewed_by"),
                rs.getString("review_comment")
        );
    }

    /* ===== 市场浏览 ===== */

    @Override
    public List<MarketSkill> listApproved() {
        return jdbcTemplate.query(
                "SELECT * FROM market_skill WHERE status = 'APPROVED' ORDER BY author, name, version DESC",
                this::mapMarketSkill);
    }

    @Override
    public MarketSkill get(Long id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM market_skill WHERE id = ?",
                    this::mapMarketSkill, id);
        } catch (EmptyResultDataAccessException e) {
            throw new LoomAgentRuntimeException("Skill 不存在: id=" + id);
        }
    }

    @Override
    public List<MarketSkill> listAllForAdmin() {
        return jdbcTemplate.query(
                "SELECT * FROM market_skill ORDER BY status, author, name, version DESC",
                this::mapMarketSkill);
    }

    @Override
    public List<MarketSkill> listPending() {
        return jdbcTemplate.query(
                "SELECT * FROM market_skill WHERE status = 'PENDING' ORDER BY submitted_at",
                this::mapMarketSkill);
    }

    /* ===== 用户提交 ===== */

    @Override
    public MarketSkill submit(String username, MarketSkillSubmitRequest req) {
        validateNameVersion(req.name(), req.version());
        if (req.content() == null || req.content().isBlank()) {
            throw new LoomAgentRuntimeException("content 不能为空");
        }
        Integer dup = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_skill WHERE author = ? AND name = ? AND version = ?",
                Integer.class, username, req.name(), req.version());
        if (dup != null && dup > 0) {
            throw new LoomAgentRuntimeException("已存在同名同版本的提交：author=" + username
                    + " name=" + req.name() + " version=" + req.version());
        }
        jdbcTemplate.update(
                "INSERT INTO market_skill (name, description, content, version, author, status) " +
                        "VALUES (?, ?, ?, ?, ?, 'PENDING')",
                req.name(), req.description(), req.content(), req.version(), username);
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM market_skill WHERE author = ? AND name = ? AND version = ?",
                Long.class, username, req.name(), req.version());
        return get(id);
    }

    /* ===== admin 审批 ===== */

    @Override
    public MarketSkill approve(String adminUsername, Long id, String comment) {
        jdbcTemplate.update(
                "UPDATE market_skill SET status = 'APPROVED', reviewed_at = CURRENT_TIMESTAMP, " +
                        "reviewed_by = ?, review_comment = ? WHERE id = ? AND status = 'PENDING'",
                adminUsername, comment, id);
        return get(id);
    }

    @Override
    public MarketSkill reject(String adminUsername, Long id, String comment) {
        jdbcTemplate.update(
                "UPDATE market_skill SET status = 'REJECTED', reviewed_at = CURRENT_TIMESTAMP, " +
                        "reviewed_by = ?, review_comment = ? WHERE id = ? AND status = 'PENDING'",
                adminUsername, comment, id);
        return get(id);
    }

    /* ===== admin 直接 CRUD ===== */

    @Override
    public MarketSkill adminCreate(String adminUsername, MarketSkillUpsertRequest req) {
        validateNameVersion(req.name(), req.version());
        if (req.content() == null || req.content().isBlank()) {
            throw new LoomAgentRuntimeException("content 不能为空");
        }
        String status = req.status() == null ? MarketSkill.STATUS_APPROVED : req.status();
        Integer dup = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_skill WHERE author = ? AND name = ? AND version = ?",
                Integer.class, adminUsername, req.name(), req.version());
        if (dup != null && dup > 0) {
            throw new LoomAgentRuntimeException("已存在同名同版本的 Skill：author=" + adminUsername
                    + " name=" + req.name() + " version=" + req.version());
        }
        jdbcTemplate.update(
                "INSERT INTO market_skill (name, description, content, version, author, status, " +
                        "reviewed_at, reviewed_by) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)",
                req.name(), req.description(), req.content(), req.version(),
                adminUsername, status, adminUsername);
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM market_skill WHERE author = ? AND name = ? AND version = ?",
                Long.class, adminUsername, req.name(), req.version());
        return get(id);
    }

    @Override
    public MarketSkill adminUpdate(String adminUsername, Long id, MarketSkillUpsertRequest req) {
        MarketSkill existing = get(id);
        jdbcTemplate.update(
                "UPDATE market_skill SET name = ?, description = ?, content = ?, version = ?, " +
                        "status = COALESCE(?, status) WHERE id = ?",
                req.name() == null ? existing.name() : req.name(),
                req.description() == null ? existing.description() : req.description(),
                req.content() == null ? existing.content() : req.content(),
                req.version() == null ? existing.version() : req.version(),
                req.status(),
                id);
        return get(id);
    }

    @Override
    public void adminDelete(String adminUsername, Long id) {
        // 先把 user_skill / role_skill 里所有引用清掉
        jdbcTemplate.update("DELETE FROM user_skill WHERE market_skill_id = ?", id);
        jdbcTemplate.update("DELETE FROM role_skill WHERE market_skill_id = ?", id);
        int n = jdbcTemplate.update("DELETE FROM market_skill WHERE id = ?", id);
        if (n == 0) throw new LoomAgentRuntimeException("Skill 不存在: id=" + id);
    }

    /* ===== 用户拉取 ===== */

    @Override
    public UserSkill pull(String username, Long marketSkillId) {
        MarketSkill m = get(marketSkillId);
        if (!MarketSkill.STATUS_APPROVED.equals(m.status())) {
            throw new LoomAgentRuntimeException("只能拉取已审批的 Skill（当前 status=" + m.status() + "）");
        }
        // 检查 user_skill 是否已存在同 name
        List<UserSkill> existing = jdbcTemplate.query(
                "SELECT * FROM user_skill WHERE username = ? AND name = ?",
                (rs, n) -> new UserSkill(
                        rs.getLong("id"), rs.getString("username"), rs.getString("name"),
                        rs.getString("description"), rs.getString("content"), rs.getString("source"),
                        (Long) rs.getObject("market_skill_id"), rs.getString("market_version"),
                        rs.getBoolean("default_loaded"), rs.getBoolean("locked"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()),
                username, m.name());
        if (!existing.isEmpty()) {
            UserSkill e = existing.get(0);
            if (e.locked()) {
                throw new LoomAgentRuntimeException("该 Skill 已被角色授权锁定，不能从市场覆盖");
            }
            // 刷新 content / source / market_skill_id / market_version
            jdbcTemplate.update(
                    "UPDATE user_skill SET description = ?, content = ?, source = 'MARKET_PULLED', " +
                            "market_skill_id = ?, market_version = ?, updated_at = CURRENT_TIMESTAMP " +
                            "WHERE id = ?",
                    m.description(), m.content(), m.id(), m.version(), e.id());
            return getUserSkill(e.id());
        }
        jdbcTemplate.update(
                "INSERT INTO user_skill (username, name, description, content, source, market_skill_id, " +
                        "market_version, default_loaded, locked) VALUES (?, ?, ?, ?, 'MARKET_PULLED', ?, ?, TRUE, FALSE)",
                username, m.name(), m.description(), m.content(), m.id(), m.version());
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM user_skill WHERE username = ? AND name = ?",
                Long.class, username, m.name());
        return getUserSkill(id);
    }

    private UserSkill getUserSkill(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT * FROM user_skill WHERE id = ?",
                (rs, n) -> new UserSkill(
                        rs.getLong("id"), rs.getString("username"), rs.getString("name"),
                        rs.getString("description"), rs.getString("content"), rs.getString("source"),
                        (Long) rs.getObject("market_skill_id"), rs.getString("market_version"),
                        rs.getBoolean("default_loaded"), rs.getBoolean("locked"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()),
                id);
    }

    private void validateNameVersion(String name, String version) {
        if (name == null || name.isBlank()) throw new LoomAgentRuntimeException("name 不能为空");
        if (version == null || version.isBlank()) throw new LoomAgentRuntimeException("version 不能为空");
    }
}
