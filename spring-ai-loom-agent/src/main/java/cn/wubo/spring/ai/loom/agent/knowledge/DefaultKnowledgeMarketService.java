package cn.wubo.spring.ai.loom.agent.knowledge;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.KnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.model.MarketKnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DefaultKnowledgeMarketService implements IKnowledgeMarketService {

    private final JdbcTemplate jdbcTemplate;
    private final IKnowledge knowledge;
    private final IUser user;

    public DefaultKnowledgeMarketService(JdbcTemplate jdbcTemplate, IKnowledge knowledge, IUser user) {
        this.jdbcTemplate = jdbcTemplate;
        this.knowledge = knowledge;
        this.user = user;
    }

    private MarketKnowledgeRecord mapMarketKnowledgeRecord(ResultSet rs, int rowNum) throws SQLException {
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        Timestamp submittedAt = rs.getTimestamp("submitted_at");
        return new MarketKnowledgeRecord(
                rs.getString("id"),
                rs.getString("username"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("status"),
                submittedAt == null ? null : submittedAt.toLocalDateTime(),
                reviewedAt == null ? null : reviewedAt.toLocalDateTime(),
                rs.getString("reviewed_by"),
                rs.getString("review_comment"));
    }

    /* ===== 市场浏览 ===== */

    @Override
    public List<MarketKnowledgeRecord> listApproved(int page, int size) {
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        int offset = (page - 1) * size;
        return jdbcTemplate.query(
                "SELECT * FROM market_knowledge WHERE status = 'APPROVED' ORDER BY submitted_at DESC LIMIT ? OFFSET ?",
                this::mapMarketKnowledgeRecord, size, offset);
    }

    @Override
    public MarketKnowledgeRecord getById(String marketKnowledgeId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM market_knowledge WHERE id = ?",
                    this::mapMarketKnowledgeRecord, marketKnowledgeId);
        } catch (EmptyResultDataAccessException e) {
            throw new LoomAgentRuntimeException(404, "市场知识库不存在: id=" + marketKnowledgeId);
        }
    }

    @Override
    public List<MarketKnowledgeRecord> listAllForAdmin() {
        return jdbcTemplate.query(
                "SELECT * FROM market_knowledge ORDER BY status, submitted_at DESC",
                this::mapMarketKnowledgeRecord);
    }

    @Override
    public List<MarketKnowledgeRecord> listPending() {
        return jdbcTemplate.query(
                "SELECT * FROM market_knowledge WHERE status = 'PENDING' ORDER BY submitted_at",
                this::mapMarketKnowledgeRecord);
    }

    /* ===== 用户提交 ===== */

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

        // 检查是否已提交
        Integer dup = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_knowledge WHERE username = ? AND name = ?",
                Integer.class, username, kb.name());
        if (dup != null && dup > 0) {
            throw new LoomAgentRuntimeException(409, "已存在同名知识库提交：name=" + kb.name());
        }

        String marketId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO market_knowledge (id, username, name, description, status) VALUES (?, ?, ?, ?, 'PENDING')",
                marketId, username, kb.name(), kb.description());

        return getById(marketId);
    }

    /* ===== admin 审批 ===== */

    @Override
    @Transactional
    public MarketKnowledgeRecord approve(String marketKnowledgeId) {
        String adminUsername = UserContextHolder.getCurrentUser();
        jdbcTemplate.update(
                "UPDATE market_knowledge SET status = 'APPROVED', reviewed_at = CURRENT_TIMESTAMP, " +
                        "reviewed_by = ? WHERE id = ? AND status = 'PENDING'",
                adminUsername, marketKnowledgeId);
        return getById(marketKnowledgeId);
    }

    @Override
    @Transactional
    public MarketKnowledgeRecord reject(String marketKnowledgeId) {
        String adminUsername = UserContextHolder.getCurrentUser();
        jdbcTemplate.update(
                "UPDATE market_knowledge SET status = 'REJECTED', reviewed_at = CURRENT_TIMESTAMP, " +
                        "reviewed_by = ? WHERE id = ? AND status = 'PENDING'",
                adminUsername, marketKnowledgeId);
        return getById(marketKnowledgeId);
    }

    /* ===== 用户撤回 ===== */

    @Override
    @Transactional
    public void withdraw(String marketKnowledgeId) {
        String username = UserContextHolder.getCurrentUser();
        MarketKnowledgeRecord existing = getById(marketKnowledgeId);
        if (!existing.username().equals(username)) {
            throw new LoomAgentRuntimeException(403, "只能撤回自己的提交");
        }
        // 清除用户订阅引用
        jdbcTemplate.update("DELETE FROM user_knowledge WHERE market_knowledge_id = ?", marketKnowledgeId);
        int rows = jdbcTemplate.update("DELETE FROM market_knowledge WHERE id = ? AND username = ?",
                marketKnowledgeId, username);
        if (rows == 0) {
            throw new LoomAgentRuntimeException(404, "市场知识库不存在: " + marketKnowledgeId);
        }
    }

    /* ===== 用户拉取 ===== */

    @Override
    @Transactional
    public void pull(String username, String marketKnowledgeId) {
        MarketKnowledgeRecord mk = getById(marketKnowledgeId);
        if (!MarketKnowledgeRecord.STATUS_APPROVED.equals(mk.status())) {
            throw new LoomAgentRuntimeException(400, "只能订阅已审批的市场知识库（当前 status=" + mk.status() + "）");
        }

        // 检查是否已存在
        Integer existingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_knowledge WHERE username = ? AND market_knowledge_id = ?",
                Integer.class, username, marketKnowledgeId);
        if (existingCount != null && existingCount > 0) {
            throw new LoomAgentRuntimeException(409, "已订阅该知识库");
        }

        // 检查是否有 ROLE_GRANTED 锁定的同名订阅
        List<Map<String, Object>> lockedRows = jdbcTemplate.queryForList(
                "SELECT uk.* FROM user_knowledge uk " +
                        "JOIN market_knowledge mk ON uk.market_knowledge_id = mk.id " +
                        "WHERE uk.username = ? AND mk.name = ? AND uk.locked = TRUE",
                username, mk.name());
        if (!lockedRows.isEmpty()) {
            throw new LoomAgentRuntimeException(409, "同名知识库已被角色授权锁定，不能从市场覆盖");
        }

        jdbcTemplate.update(
                "INSERT INTO user_knowledge (username, market_knowledge_id, source, locked) VALUES (?, ?, 'MARKET_PULLED', FALSE)",
                username, marketKnowledgeId);
    }

    @Override
    public List<MarketKnowledgeRecord> listMyPulled(String username) {
        return jdbcTemplate.query(
                "SELECT mk.* FROM market_knowledge mk " +
                        "JOIN user_knowledge uk ON mk.id = uk.market_knowledge_id " +
                        "WHERE uk.username = ? AND uk.source = 'MARKET_PULLED' " +
                        "ORDER BY mk.submitted_at DESC",
                this::mapMarketKnowledgeRecord, username);
    }

    /* ===== 删除 ===== */

    @Override
    @Transactional
    public void delete(String marketKnowledgeId) {
        String username = UserContextHolder.getCurrentUser();
        boolean isAdmin = user.isAdmin(username);

        MarketKnowledgeRecord existing = getById(marketKnowledgeId);
        if (!isAdmin && !existing.username().equals(username)) {
            throw new LoomAgentRuntimeException(403, "无权限删除他人提交的知识库");
        }

        // 清引用
        jdbcTemplate.update("DELETE FROM user_knowledge WHERE market_knowledge_id = ?", marketKnowledgeId);
        jdbcTemplate.update("DELETE FROM role_knowledge WHERE market_knowledge_id = ?", marketKnowledgeId);
        jdbcTemplate.update("DELETE FROM market_knowledge WHERE id = ?", marketKnowledgeId);
    }
}
